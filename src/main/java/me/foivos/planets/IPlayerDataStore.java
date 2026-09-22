package me.foivos.planets;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Persistent per-player data center for the whole plugin.
 * <p>
 * Every player gets a {@code player-data/<uuid>.yml} file that snapshots the
 * most useful aggregate values the plugin knows about them: VPL balance, NEB,
 * playtime in hours, number of planets owned, number of homes, their current
 * personal settings, when they first joined this server and when they were last
 * online. Other plugins or future admin tools can read these files directly, or
 * ask the store through {@link #get( UUID )} / {@link #getAll()}.
 * <p>
 * The store is loaded on demand and written when a player's snapshot is updated
 * (join, quit, admin dump). It is <em>not</em> a replacement for the existing
 * per-feature YAML files (my-planets.yml, homes.yml, player-settings.yml) — it
 * is a read-friendly summary layer on top of them.
 */
public interface IPlayerDataStore {
    /**
     * Creates the data folder if it does not exist yet.
     */
    void ensureFolder();

    /**
     * Returns the snapshot for the given player, loading it from disk if needed.
     */
    PlayerData get(UUID uuid);

    /**
     * Refreshes the snapshot for every online player (called on join/quit).
     */
    void refreshOnlinePlayers(Planets planets);

    /**
     * Refreshes a single player's snapshot from the live source of truth.
     */
    void refresh(Player player, Planets planets);

    /**
     * Refreshes a single player's snapshot by uuid (works for offline players too).
     */
    void refreshByUuid(UUID uuid, Planets planets);

    /**
     * Returns every snapshot currently in memory + any still-on-disk entries.
     */
    List<PlayerData> getAll();

    /**
     * Returns every snapshot for players with the given name (case-insensitive prefix).
     */
    List<PlayerData> search(String prefix);
    /**
     * Prints a full snapshot of the given player to the command sender.
     */
    void dumpTo(CommandSender sender, UUID uuid);
}
