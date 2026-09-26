package me.foivos.planets;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public interface IPlayerDataStore {
    void ensureFolder();

    void refreshOnlinePlayers(PlayerDataContext context);

    void refresh(Player player, PlayerDataContext context);

    void refreshByUuid(UUID uuid, PlayerDataContext context);

    PlayerData get(UUID uuid);

    List<PlayerData> getAll();

    List<PlayerData> search(String prefix);

    void dumpTo(CommandSender sender, UUID uuid);
}
