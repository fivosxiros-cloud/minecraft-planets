package me.foivos.playerdata;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * Live data required by a player-data store implementation.
 */
public interface PlayerDataContext {
    boolean hasEconomy();

    double getBalance(Player player);

    String nebPlaceholder();

    double hoursPlayed(UUID uuid);

    int planetsOwned(UUID uuid);

    int homesCount(UUID uuid);

    Map<String, Boolean> settings(UUID uuid);
}
