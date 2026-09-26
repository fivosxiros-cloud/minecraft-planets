package me.foivos.planets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a player's friends are and how reachable they are: online, AFK,
 * offline, or hidden because they asked for their status not to be shown.
 *
 * <p>Being online is a plain {@code Bukkit.getPlayer} lookup, which is already
 * a hash map read. AFK is tracked by one timestamp per player, bumped whenever
 * they change block, so the check while the friends menu redraws is a couple of
 * subtractions rather than a task per player.
 *
 * <p>Emptiness ("last seen") comes from the plugin's existing player-data
 * centre, so the friend system never keeps a second copy of that.
 */
final class FriendPresenceService implements Listener {

    /** How a player appears to somebody else. */
    enum Status {
        ONLINE("Online", "\uD83D\uDFE2"),
        AFK("AFK", "\uD83D\uDFE1"),
        OFFLINE("Offline", "\u26AA"),
        HIDDEN("Hidden", "\u26AB");

        private final String label;
        private final String dot;

        Status(String label, String dot) {
            this.label = label;
            this.dot = dot;
        }

        public String label() { return label; }
        public String dot() { return dot; }
    }

    /** Heartbeat tracked per player: uuid -> last time they changed block. */
    private final Map<UUID, Long> lastActivity = new ConcurrentHashMap<>();

    private final Planets plugin;

    FriendPresenceService(Planets plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /** A player is doing something right now (login, click, command). */
    void touch(Player player) {
        if (player != null) {
            lastActivity.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    /** A player is doing nothing: they count as AFK from now on. */
    void markAway(UUID uuid) {
        if (uuid != null) {
            lastActivity.remove(uuid);
        }
    }

    /** Forgets everything about a player who left, so nothing leaks. */
    void forget(UUID uuid) {
        lastActivity.remove(uuid);
    }

    /**
     * Any block-level movement counts as activity. Pure head turning does not,
     * so standing still while looking around still drifts into AFK.
     */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null
                || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        lastActivity.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    // ── Reads ───────────────────────────────────────────────────────────

    boolean isOnline(UUID uuid) {
        return uuid != null && Bukkit.getPlayer(uuid) != null;
    }

    /** How long without moving before a player is shown as AFK (config). */
    int afkSeconds() {
        return Math.max(0, plugin.getConfig().getInt("friends.afk-seconds", 300));
    }

    /** Whether the player has been idle long enough to count as AFK. */
    boolean isAfk(Player player) {
        int seconds = afkSeconds();
        if (seconds <= 0 || player == null) {
            return false;
        }
        Long last = lastActivity.get(player.getUniqueId());
        return last != null && System.currentTimeMillis() - last >= seconds * 1000L;
    }

    /** Whether a player lets other people see their online state. */
    boolean statusVisible(UUID uuid) {
        PlayerSettings settings = plugin.getPlayerSettings();
        return settings == null
                || settings.get(uuid, PlayerSettings.Setting.ONLINE_STATUS);
    }

    /**
     * How one player appears. {@code ownView} is the player looking at their own
     * profile: their own status is never hidden from themselves, and "AFK"
     * stays blank so it does not accuse them of idling.
     */
    Status statusOf(UUID uuid, boolean ownView) {
        Player online = uuid == null ? null : Bukkit.getPlayer(uuid);
        if (online == null) {
            return Status.OFFLINE;
        }
        if (!ownView && !statusVisible(uuid)) {
            return Status.HIDDEN;
        }
        if (ownView) {
            return Status.ONLINE;
        }
        return isAfk(online) ? Status.AFK : Status.ONLINE;
    }

    Status statusOf(UUID uuid) {
        return statusOf(uuid, false);
    }

    /** The world or planet a player is on right now, or null when offline. */
    String locationLabel(UUID uuid) {
        Player online = uuid == null ? null : Bukkit.getPlayer(uuid);
        if (online == null || online.getWorld() == null) {
            return null;
        }
        return plugin.worldLabel(online.getWorld());
    }

    /** The player's world name right now, or null when offline. */
    String worldName(UUID uuid) {
        Player online = uuid == null ? null : Bukkit.getPlayer(uuid);
        return online == null || online.getWorld() == null ? null : online.getWorld().getName();
    }

    /**
     * When a player was last online (ms). A player who is online right now
     * reports the current time, so the profile can simply say "now".
     */
    long lastSeen(UUID uuid) {
        if (isOnline(uuid)) {
            return System.currentTimeMillis();
        }
        PlayerData data = plugin.playerDataOf(uuid);
        return data == null ? 0L : data.lastOnlineMs();
    }

    /** The player's current ping in ms, or -1 when they are not online. */
    int ping(UUID uuid) {
        Player online = uuid == null ? null : Bukkit.getPlayer(uuid);
        return online == null ? -1 : online.getPing();
    }

    /** Whether two players are on the same world right now. */
    boolean sameWorld(UUID a, UUID b) {
        String left = worldName(a);
        String right = worldName(b);
        return left != null && left.equals(right);
    }
}
