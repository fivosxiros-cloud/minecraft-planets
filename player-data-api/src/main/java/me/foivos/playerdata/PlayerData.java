package me.foivos.playerdata;

import java.util.Map;
import java.util.UUID;

/**
 * One immutable snapshot of everything the plugin knows about a player.
 */
public record PlayerData(UUID uuid, String name, double balance, double neb,
                         double playtimeHours, int planetsOwned, int homesCount,
                         Map<String, Boolean> settings, long firstJoinedMs, long lastOnlineMs) {
    /**
     * A sentinel for unknown players.
     */
    public static final PlayerData EMPTY = new PlayerData(null, "unknown", 0, 0, 0,
            0, 0, Map.of(), 0, 0);
}
