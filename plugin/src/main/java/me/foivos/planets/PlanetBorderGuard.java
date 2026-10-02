package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps players inside a planet's world border.
 *
 * <p>A planet's border is meant to be a wall, but it is only a wall to
 * <em>walking</em>: an ender pearl (or a chorus fruit) thrown across it lands
 * outside, and the player follows it. This guard watches every planet that has
 * a border — the player-owned planets and any world with a border the plugin
 * recorded — and remembers where each player last stood <em>inside</em> it:
 *
 * <ul>
 *   <li>an ender-pearl / chorus-fruit teleport to a spot outside the border is
 *       cancelled, and the player is put back on the last spot they occupied
 *       inside it (or, if that is unknown, on the nearest point inside);</li>
 *   <li>a plain move from inside to outside — a fall, a knockback, a mount —
 *       is redirected to the same last-inside spot.</li>
 * </ul>
 *
 * <p>Only in-world movement is caught: a teleport from a command or another
 * plugin ({@code /hub}, {@code /myp}, an admin {@code /tp}) is still free to
 * take a player out, which is how leaving a planet normally works.
 */
public final class PlanetBorderGuard implements Listener {

    /** How far inside the border a player is placed when no last spot is known, in blocks. */
    private static final double MARGIN = 1.5;

    /** The last in-border spot per player, in the world they were standing in. */
    private final Map<UUID, Location> lastInside = new HashMap<>();

    private final Planets plugin;

    public PlanetBorderGuard(Planets plugin) {
        this.plugin = plugin;
    }

    // ── Walking / falling out ───────────────────────────────────────────

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        World world = to.getWorld();
        if (!guarded(world)) {
            return;
        }
        WorldBorder border = world.getWorldBorder();
        Player player = event.getPlayer();
        if (inside(border, to)) {
            lastInside.put(player.getUniqueId(), to.clone());
            return;
        }
        Location from = event.getFrom();
        if (!inside(border, from)) {
            return; // already outside — nothing sensible to put them back to
        }
        Landing landing = landingSpot(player, border, from);
        if (landing.spot() == null) {
            return;
        }
        event.setTo(landing.spot());
        warn(player, landing.fresh());
    }

    // ── Ender pearls (and chorus fruit) ─────────────────────────────────

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        World world = to.getWorld();
        if (!guarded(world)) {
            return;
        }
        WorldBorder border = world.getWorldBorder();
        if (inside(border, to)) {
            return; // a pearl that stays inside is fine
        }
        // In-world escapes only: an ender pearl (or a chorus fruit, which Paper
        // reports as a consumable effect) can never take a player off a planet
        // on purpose — unlike a portal, command or plugin teleport, which can.
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                && cause != PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Landing landing = landingSpot(player, border, to);
        if (landing.spot() != null) {
            // A cancelled teleport keeps the pearl-thrower where they stand, but
            // if they were already outside (a second pearl, a push) they still
            // need putting back, so the move is replayed a tick later.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.teleport(landing.spot());
                }
            });
        }
        warn(player, landing.fresh());
    }

    // ── Housekeeping ────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastInside.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        lastInside.remove(event.getPlayer().getUniqueId());
    }

    // ── Border maths ────────────────────────────────────────────────────

    /**
     * Whether this world's border is one the plugin manages: a player-owned
     * planet, or any world a border size was recorded for.
     */
    private boolean guarded(World world) {
        if (world == null || plugin.getMyPlanetManager() == null) {
            return false;
        }
        if (world.getWorldBorder().getSize() <= 0) {
            return false; // no border to cross
        }
        return plugin.getMyPlanetManager().isOwnedWorld(world.getName())
                || plugin.recordedBorder(world.getName()) != null;
    }

    /** Whether a location sits within the border (with a small safety margin). */
    private static boolean inside(WorldBorder border, Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        double half = border.getSize() / 2.0;
        if (half <= 0) {
            return false;
        }
        double centerX = border.getCenter().getX();
        double centerZ = border.getCenter().getZ();
        // Bukkit's border bounds are inclusive, so a spot exactly on the edge
        // counts as inside and doesn't trigger a bounce.
        return Math.abs(location.getX() - centerX) <= half
                && Math.abs(location.getZ() - centerZ) <= half;
    }

    /**
     * Where a breach lands: either the spot the player last stood on inside the
     * border, or — with {@code planet-border-breach-mode: random} — a fresh safe
     * spot somewhere inside the planet. Falls back to the remembered spot when
     * no safe ground could be found (a tiny or empty plot).
     */
    private Landing landingSpot(Player player, WorldBorder border, Location fallback) {
        if (randomLanding()) {
            Location fresh = PlanetTravel.randomSpotInsideBorder(fallback.getWorld());
            if (fresh != null) {
                return new Landing(fresh, true);
            }
        }
        return new Landing(pushBack(player, border, fallback), false);
    }

    /**
     * The configured breach landing: {@code return} (the default) bounces the
     * player back to where they stood, {@code random} drops them on a random
     * safe spot inside the planet instead. Read live, so a config reload is
     * picked up without a restart.
     */
    private boolean randomLanding() {
        String mode = plugin.getConfig().getString("planet-border-breach-mode", "return");
        return mode != null && (mode.equalsIgnoreCase("random") || mode.equalsIgnoreCase("teleport"));
    }

    /** Where a breach puts the player, and whether it is a fresh spot. */
    private record Landing(Location spot, boolean fresh) {
    }

    /**
     * Where to put a player who has just stepped (or been teleported) out of
     * the border: the last spot they stood on inside it, or the nearest point
     * inside it to where they were heading when nothing is remembered.
     */
    private Location pushBack(Player player, WorldBorder border, Location fallback) {
        Location remembered = lastInside.get(player.getUniqueId());
        if (remembered != null && inside(border, remembered)
                && fallback.getWorld() != null
                && remembered.getWorld() != null
                && fallback.getWorld().equals(remembered.getWorld())) {
            return remembered.clone();
        }
        if (fallback.getWorld() == null) {
            return null;
        }
        double centerX = border.getCenter().getX();
        double centerZ = border.getCenter().getZ();
        double half = Math.max(0, border.getSize() / 2.0 - MARGIN);
        double x = Math.min(Math.max(fallback.getX(), centerX - half), centerX + half);
        double z = Math.min(Math.max(fallback.getZ(), centerZ - half), centerZ + half);
        if (inside(border, fallback) && x == fallback.getX() && z == fallback.getZ()) {
            return null; // nothing to change
        }
        return new Location(fallback.getWorld(), x, fallback.getY(), z,
                fallback.getYaw(), fallback.getPitch());
    }

    /** A quiet bit of feedback so nobody thinks the game ate their pearl. */
    private void warn(Player player, boolean freshSpot) {
        player.sendMessage(Component.text(freshSpot
                        ? "You can't leave the planet's border — you were moved to a new spot on it."
                        : "You can't leave the planet's border.")
                .color(NamedTextColor.RED));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.5f, 1.6f);
    }
}
