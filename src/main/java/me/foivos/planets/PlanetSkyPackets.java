package me.foivos.planets;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * True per-planet sky/fog tinting on top of ProtocolLib (the plugin falls back
 * to the colored particle haze when ProtocolLib isn't installed).
 * <p>
 * How it works on modern Minecraft: the respawn/login packets reference a
 * world's dimension <em>type</em> by registry ID, and the client renders sky,
 * fog and cloud colors from that type's {@code minecraft:visual/*} attributes.
 * All normal-type planets share the {@code minecraft:overworld} dimension type,
 * so this module rewrites the dimension-type <em>registry data</em> packet of
 * the configuration phase: for every player it replaces the vanilla dimension
 * type entries their current planet uses with clones carrying that planet's
 * sky/fog colors. When the player hops planets, the plugin re-runs the
 * configuration phase ({@code reenterConfiguration}) so the client re-receives
 * the registry with the new tint.
 * <p>
 * Known limits (by design of the 26.x protocol): while connected, all planets
 * sharing a dimension type show the player the tint of the planet they are
 * currently on; and water color is biome-driven in these versions, so only sky
 * and fog (plus the vanilla cloud color untouched) can be tinted this way.
 */
final class PlanetSkyPackets {

    /** The vanilla dimension type entries tinted for overworld-type planets. */
    private static final List<String> OVERWORLD_ENTRIES = List.of("minecraft:overworld", "minecraft:overworld_caves");

    private final Planets plugin;
    private final PlanetEnvironment environment;
    private final PacketAdapter adapter;
    /** Players whose packet rewriting was disabled after a failure (particles remain). */
    private final Set<UUID> disabled = ConcurrentHashMap.newKeySet();

    PlanetSkyPackets(Planets plugin, PlanetEnvironment environment) {
        this.plugin = plugin;
        this.environment = environment;
        ProtocolManager manager = ProtocolLibrary.getProtocolManager();
        this.adapter = new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Configuration.Server.REGISTRY_DATA) {
            @Override
            public void onPacketSending(PacketEvent event) {
                tintRegistry(event);
            }
        };
        manager.addPacketListener(adapter);
    }

    void shutdown() {
        ProtocolLibrary.getProtocolManager().removePacketListener(adapter);
    }

    /**
     * Re-runs the player's configuration phase so the dimension-type registry is
     * re-sent with the tint for the world they just entered (or the vanilla
     * colors when that world has no custom sky). No-op when the
     * {@code sky-live-refresh} toggle is off or rewriting was disabled.
     */
    void refreshPlayer(Player player) {
        if (disabled.contains(player.getUniqueId()) || !plugin.getConfig().getBoolean("sky-live-refresh", true)) {
            return;
        }
        try {
            player.getConnection().reenterConfiguration();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not refresh sky colors for " + player.getName() + ": " + ex.getMessage());
        }
    }

    /**
     * Replaces the dimension-type registry entries the player's current world
     * uses with clones carrying that planet's sky/fog colors. Only touches the
     * dimension-type registry; everything else passes through untouched.
     */
    private void tintRegistry(PacketEvent event) {
        Player player = event.getPlayer();
        if (player == null || disabled.contains(player.getUniqueId())) {
            return;
        }
        // A player who turned the colored sky off keeps the vanilla registry.
        if (!PlayerSettings.on(plugin, player.getUniqueId(), PlayerSettings.Setting.COLORED_SKY)) {
            return;
        }
        try {
            Object handle = event.getPacket().getHandle();
            if (handle == null) {
                return;
            }
            Object registryKey = invoke(handle, "registry");
            if (registryKey == null || !"minecraft:dimension_type".equals(identifierString(registryKey))) {
                return;
            }
            World world = player.getWorld();
            if (world == null) {
                return;
            }
            PlanetEnvironment.SkyColors colors = environment.colorsFor(world.getName());
            if (colors == null) {
                return;
            }
            List<?> entries = (List<?>) invoke(handle, "entries");
            if (entries == null) {
                return;
            }
            List<Object> rewritten = new ArrayList<>(entries.size());
            boolean changed = false;
            for (Object entry : entries) {
                if (isTintedEntry(entry, world.getEnvironment())) {
                    Object tinted = tintEntry(entry, colors);
                    if (tinted != null) {
                        rewritten.add(tinted);
                        changed = true;
                        continue;
                    }
                }
                rewritten.add(entry);
            }
            if (!changed) {
                return;
            }
            // Rebuild the packet with the rewritten entry list and hand it back
            // to ProtocolLib, which re-serializes it to the wire.
            Constructor<?> constructor = handle.getClass().getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object newHandle = constructor.newInstance(registryKey, rewritten);
            event.setPacket(new PacketContainer(event.getPacket().getType(), newHandle));
        } catch (Throwable ex) {
            disabled.add(player.getUniqueId());
            plugin.getLogger().warning("Sky packet rewriting disabled for " + player.getName()
                    + " — falling back to particles (" + ex + ")");
        }
    }

    /** Whether this registry entry is the dimension type of the given environment. */
    private static boolean isTintedEntry(Object entry, World.Environment environment) {
        String id = identifierString(invoke(entry, "id"));
        return switch (environment) {
            case NETHER -> "minecraft:the_nether".equals(id);
            case THE_END -> "minecraft:the_end".equals(id);
            default -> OVERWORLD_ENTRIES.contains(id);
        };
    }

    /**
     * Returns a clone of a registry entry whose NBT data carries the planet's
     * sky/fog colors, or null if the entry has no data to tint.
     */
    private static Object tintEntry(Object entry, PlanetEnvironment.SkyColors colors) {
        Object data = invoke(entry, "data");
        if (!(data instanceof Optional<?> optional) || optional.isEmpty()) {
            return null;
        }
        Object id = invoke(entry, "id");
        if (id == null) {
            return null;
        }
        NbtCompound compound = NbtFactory.fromNMSCompound(optional.get());
        NbtCompound clone = (NbtCompound) compound.deepClone();
        NbtCompound attributes = clone.getCompound("attributes");
        if (attributes == null) {
            return null;
        }
        attributes.put("minecraft:visual/sky_color", hex(colors.sky()));
        if (colors.fog() != null) {
            attributes.put("minecraft:visual/fog_color", hex(colors.fog()));
        }
        try {
            Constructor<?> constructor = entry.getClass().getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return constructor.newInstance(id, Optional.of(clone.getHandle()));
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    /** "#78a7ff"-style hex string, matching the vanilla dimension type format. */
    private static String hex(org.bukkit.Color color) {
        return String.format("#%06x", color.asRGB());
    }

    /**
     * "minecraft:overworld"-style string for a ResourceKey or Identifier: uses
     * its location() when present, otherwise falls back to its toString().
     */
    private static String identifierString(Object keyOrIdentifier) {
        Object location = invoke(keyOrIdentifier, "location");
        return String.valueOf(location == null ? keyOrIdentifier : location);
    }

    /** Invokes a public (record accessor) method by name, or returns null. */
    private static Object invoke(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }
}