package me.foivos.planets.features;

import me.foivos.planets.worldgen.PlanetProfile;
import me.foivos.planets.worldgen.PlanetRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the planet experience layer: it watches where players are, decides
 * which planet they are on, and switches that planet's features on and off
 * around them.
 * <p>
 * The lifecycle it implements, for every feature:
 * <ul>
 *   <li>player arrives (login or world change) on a profile-bound world →
 *       {@link PlanetEnterEvent}, then the features' enter hooks</li>
 *   <li>player leaves → exit hooks, then {@link PlanetExitEvent}</li>
 *   <li>player hops planet → planet → exit hooks for the old profile, enter
 *       (as change) hooks for the new</li>
 *   <li>plugin reload → every feature is disabled and re-enabled</li>
 * </ul>
 * The events are fired before the corresponding feature hooks, so external
 * listeners observe the same lifecycle the built-ins do, in a consistent
 * order: exit first, then enter.
 * <p>
 * Nothing here knows anything about terrain — generation and features are
 * separate layers that only share the {@link PlanetProfile}.
 */
public final class PlanetFeatureManager implements Listener {

    private final JavaPlugin plugin;
    private final PlanetRegistry planets;
    private final FeatureRegistry features = new FeatureRegistry();
    /** player uuid -> the profile they are currently standing on. */
    private final Map<UUID, PlanetProfile> current = new ConcurrentHashMap<>();

    public PlanetFeatureManager(JavaPlugin plugin, PlanetRegistry planets) {
        this.plugin = plugin;
        this.planets = planets;
    }

    /** Registers the built-in features, enables them and seeds online players. */
    public void start() {
        features.register(new ResourcePackFeature(plugin));
        features.register(new ClientIntegration(plugin));
        enableAll();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlanetProfile profile = planets.profileOf(player.getWorld().getName());
            if (profile != null) {
                current.put(player.getUniqueId(), profile);
            }
        }
        plugin.getLogger().info("[Features] " + features.size()
                + " feature(s) available: " + String.join(", ", features.ids()));
    }

    /** Re-reads nothing — features are code, not data — but restarts them all. */
    public void reload() {
        for (PlanetFeature feature : features.all()) {
            try {
                feature.disable();
            } catch (Throwable ex) {
                warn(feature, "disable", ex);
            }
        }
        current.clear();
        enableAll();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlanetProfile profile = planets.profileOf(player.getWorld().getName());
            if (profile != null) {
                current.put(player.getUniqueId(), profile);
            }
        }
    }

    /** Shuts every feature down for good (plugin disable). */
    public void shutdown() {
        for (PlanetFeature feature : features.all()) {
            try {
                feature.disable();
            } catch (Throwable ex) {
                warn(feature, "disable", ex);
            }
        }
        current.clear();
    }

    /** The feature registry, so another plugin (or a module) can add its own. */
    public FeatureRegistry registry() {
        return features;
    }

    /** The profile the player is standing on, or null outside any planet. */
    public PlanetProfile profileOf(Player player) {
        return player == null ? null : current.get(player.getUniqueId());
    }

    /** One line per feature for {@code /planets debug}. */
    public List<String> describe() {
        List<String> lines = new java.util.ArrayList<>();
        for (PlanetFeature feature : features.all()) {
            lines.add("  feature " + feature.id() + ": "
                    + (feature.description().isBlank() ? "(no description)" : feature.description()));
        }
        return lines;
    }

    // ── Lifecycle events ─────────────────────────────────────────────────

    /** A player logged in on a planet: enter. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PlanetProfile profile = planets.profileOf(event.getPlayer().getWorld().getName());
        if (profile != null) {
            enter(event.getPlayer(), profile, null);
        }
    }

    /** A player moved worlds: exit the old planet, enter the new one. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        PlanetProfile from = planets.profileOf(event.getFrom().getName());
        PlanetProfile to = planets.profileOf(player.getWorld().getName());
        boolean same = from == null ? to == null
                : to != null && from.id().equals(to.id());
        if (same) {
            return; // Nether/End of the same planet, or both worlds unbound
        }
        if (from != null) {
            exit(player, from);
        }
        if (to != null) {
            enter(player, to, from);
        }
    }

    /** A player logged out: run the exit hooks so nothing leaks past the session. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlanetProfile profile = current.remove(event.getPlayer().getUniqueId());
        if (profile != null) {
            exit(event.getPlayer(), profile);
        }
    }

    // ── Dispatch ─────────────────────────────────────────────────────────

    private void enter(Player player, PlanetProfile profile, PlanetProfile previous) {
        current.put(player.getUniqueId(), profile);
        Bukkit.getPluginManager().callEvent(new PlanetEnterEvent(player, player.getWorld().getName(), profile));
        for (PlanetFeature feature : features.all()) {
            if (!isActive(feature, profile)) {
                continue;
            }
            try {
                if (previous != null) {
                    feature.onPlanetChange(player, previous, profile);
                } else {
                    feature.onPlanetEnter(player, profile);
                }
            } catch (Throwable ex) {
                warn(feature, "onPlanetEnter", ex);
            }
        }
    }

    private void exit(Player player, PlanetProfile profile) {
        Bukkit.getPluginManager().callEvent(new PlanetExitEvent(player, player.getWorld().getName(), profile));
        for (PlanetFeature feature : features.all()) {
            if (!isActive(feature, profile)) {
                continue;
            }
            try {
                feature.onPlanetExit(player, profile);
            } catch (Throwable ex) {
                warn(feature, "onPlanetExit", ex);
            }
        }
    }

    private void enableAll() {
        for (PlanetFeature feature : features.all()) {
            try {
                feature.enable();
            } catch (Throwable ex) {
                warn(feature, "enable", ex);
            }
        }
    }

    /** A feature that misbehaves in appliesTo is off, never a crash. */
    private boolean isActive(PlanetFeature feature, PlanetProfile profile) {
        try {
            return feature.appliesTo(profile);
        } catch (Throwable ex) {
            return false;
        }
    }

    private void warn(PlanetFeature feature, String hook, Throwable ex) {
        plugin.getLogger().warning("[Feature:" + feature.id() + "] " + hook + " failed: " + ex);
    }
}
