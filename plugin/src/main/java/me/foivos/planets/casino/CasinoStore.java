package me.foivos.planets.casino;

import me.foivos.planets.Planets;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Every player's casino record, kept in {@code casino.yml}.
 *
 * <p>It is the same shape as the card collection's store: one file, one
 * section per player, loaded once at startup and written a few seconds after
 * the last change — so a burst of plays costs one disk write rather than one
 * per click, and a crash never costs a player their record.
 *
 * <p>Only players who have actually played are kept, so the file starts empty
 * and grows with the server rather than with its player list.
 */
final class CasinoStore {

    /** How long a change waits before it is written (ticks — five seconds). */
    private static final long SAVE_DELAY_TICKS = 100L;

    private static final String PLAYERS = "players";

    private final Planets plugin;
    private final File file;
    private final Map<UUID, CasinoStats> cache = new ConcurrentHashMap<>();

    private BukkitTask pendingSave;
    private boolean dirty;

    CasinoStore(Planets plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "casino.yml");
    }

    // ── Access ──────────────────────────────────────────────────────────

    /** A player's record, created blank the first time they are asked about. */
    CasinoStats get(UUID uuid) {
        return uuid == null ? new CasinoStats() : cache.computeIfAbsent(uuid, key -> new CasinoStats());
    }

    /** A player's record as it stands, or null when they never had one. */
    CasinoStats peek(UUID uuid) {
        return uuid == null ? null : cache.get(uuid);
    }

    /** How many players have a record of their own. */
    int players() {
        return cache.size();
    }

    /**
     * Queues a save. The write happens a few seconds later, so a round of
     * clicks on a slot machine is written once, not twenty times.
     */
    void markDirty() {
        dirty = true;
        if (pendingSave != null) {
            return;
        }
        pendingSave = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            pendingSave = null;
            if (dirty) {
                save();
            }
        }, SAVE_DELAY_TICKS);
    }

    // ── Persistence ─────────────────────────────────────────────────────

    void load() {
        cache.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection(PLAYERS);
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException notAUuid) {
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            CasinoStats stats = new CasinoStats();
            stats.read(section);
            if (!stats.isEmpty()) {
                cache.put(uuid, stats);
            }
        }
        plugin.getLogger().info("Loaded casino records for " + cache.size() + " player(s).");
    }

    void save() {
        dirty = false;
        if (pendingSave != null) {
            pendingSave.cancel();
            pendingSave = null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, CasinoStats> entry : cache.entrySet()) {
            CasinoStats stats = entry.getValue();
            if (stats.isEmpty()) {
                continue;
            }
            ConfigurationSection section = yaml.createSection(PLAYERS + "." + entry.getKey());
            stats.write(section);
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save casino.yml", ex);
        }
    }
}
