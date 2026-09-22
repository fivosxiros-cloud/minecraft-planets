package me.foivos.playerdata;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Persistent per-player data store contract.
 */
public interface IPlayerDataStore {
    void ensureFolder();

    PlayerData get(UUID uuid);

    void refreshOnlinePlayers(PlayerDataContext context);

    void refresh(Player player, PlayerDataContext context);

    void refreshByUuid(UUID uuid, PlayerDataContext context);

    List<PlayerData> getAll();

    List<PlayerData> search(String prefix);

    void dumpTo(CommandSender sender, UUID uuid);
}
