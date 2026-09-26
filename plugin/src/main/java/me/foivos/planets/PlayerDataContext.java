package me.foivos.planets;

import org.bukkit.OfflinePlayer;

import java.util.Map;
import java.util.UUID;

public interface PlayerDataContext {
    boolean hasEconomy();

    double getBalance(OfflinePlayer player);

    String nebPlaceholder();

    double hoursPlayed(UUID uuid);

    int planetsOwned(UUID uuid);

    int homesCount(UUID uuid);

    Map<String, Boolean> settings(UUID uuid);
}

