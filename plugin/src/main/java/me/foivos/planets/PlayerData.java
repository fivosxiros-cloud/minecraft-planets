package me.foivos.planets;

import java.util.Map;
import java.util.UUID;

public record PlayerData(
        UUID uuid,
        String name,
        double balance,
        double neb,
        double playtimeHours,
        int planetsOwned,
        int homesCount,
        Map<String, Boolean> settings,
        long firstJoinedMs,
        long lastOnlineMs
) {
    public static final PlayerData EMPTY = new PlayerData(null, "unknown", 0, 0, 0,
            0, 0, Map.of(), 0, 0);
}

