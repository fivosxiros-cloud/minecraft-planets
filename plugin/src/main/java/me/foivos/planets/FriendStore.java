package me.foivos.planets;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The friend system's persistent store: every player's {@link FriendData},
 * cached in memory and written to {@code friends.yml}.
 *
 * <p>Nothing else in the plugin touches that file. Services change the cached
 * records through this class and it takes care of saving: changes are folded
 * together and written a few seconds later, so a burst of clicks in the friends
 * menu ends in a single disk write instead of one per click. A shutdown (or an
 * explicit flush) writes immediately.
 *
 * <pre>
 * players:
 *   &lt;uuid&gt;:
 *     friends: [&lt;uuid&gt;, ...]
 *     favorites: [&lt;uuid&gt;, ...]
 *     requests-out:
 *       &lt;uuid&gt;: 1750000000000
 *     requests-in:
 *       &lt;uuid&gt;: 1750000000000
 *     gifts-sent: 3
 *     gifts-received: 1
 *     activity:
 *       - "1750000000000|GIFT_SENT|&lt;uuid&gt;|250.0"
 * </pre>
 */
final class FriendStore {

    private static final String PLAYERS = "players";
    private static final String FRIENDS = "friends";
    private static final String FAVORITES = "favorites";
    private static final String OUT_KEY = "requests-out";
    private static final String IN_KEY = "requests-in";
    private static final String GIFTS_SENT = "gifts-sent";
    private static final String GIFTS_RECEIVED = "gifts-received";
    private static final String ACTIVITY = "activity";

    /** How long a change waits before it is written (ticks — 5 seconds). */
    private static final long SAVE_DELAY_TICKS = 100L;

    private final Planets plugin;
    private final File file;
    /** uuid -> record. Loaded lazily: an untouched player is a blank record. */
    private final Map<UUID, FriendData> cache = new ConcurrentHashMap<>();

    private BukkitTask pendingSave;
    private boolean dirty;

    FriendStore(Planets plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "friends.yml");
    }

    // ── Access ──────────────────────────────────────────────────────────

    /** This player's record, created blank the first time it is asked for. */
    FriendData get(UUID uuid) {
        if (uuid == null) {
            // Nothing to attach a record to; a throwaway keeps callers simple.
            return new FriendData();
        }
        return cache.computeIfAbsent(uuid, k -> new FriendData());
    }

    /** This player's record as it currently stands, or null if never touched. */
    FriendData peek(UUID uuid) {
        return uuid == null ? null : cache.get(uuid);
    }

    /** Every uuid this store holds a record for (used by the maintenance pass). */
    java.util.Set<UUID> ids() {
        return new java.util.HashSet<>(cache.keySet());
    }

    /**
     * Queues a save. The write happens a few seconds later, so a player
     * clicking through the friends menu does not cause a write per click.
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
            } catch (IllegalArgumentException ignored) {
                continue; // not a uuid — skip, same as the rest of the plugin
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            FriendData data = new FriendData();
            for (String raw : section.getStringList(FRIENDS)) {
                UUID id = parse(raw);
                if (id != null && !id.equals(uuid)) {
                    data.addFriend(id);
                }
            }
            for (String raw : section.getStringList(FAVORITES)) {
                UUID id = parse(raw);
                if (id != null) {
                    data.setFavorite(id, true);
                }
            }
            readRequests(section.getConfigurationSection(OUT_KEY), data, true);
            readRequests(section.getConfigurationSection(IN_KEY), data, false);
            for (int i = 0; i < section.getInt(GIFTS_SENT, 0); i++) {
                data.addGiftSent();
            }
            for (int i = 0; i < section.getInt(GIFTS_RECEIVED, 0); i++) {
                data.addGiftReceived();
            }
            List<String> activity = section.getStringList(ACTIVITY);
            // addActivity prepends, so replay oldest-first to end up newest-first.
            for (int i = activity.size() - 1; i >= 0; i--) {
                String entry = activity.get(i);
                if (entry != null && !entry.isBlank()) {
                    data.addActivity(Integer.MAX_VALUE, entry);
                }
            }
            if (!data.isEmpty()) {
                cache.put(uuid, data);
            }
        }
        plugin.getLogger().info("Loaded friends for " + cache.size() + " player(s).");
    }

    /** Writes every record to disk right away, cancelling any queued write. */
    void save() {
        dirty = false;
        if (pendingSave != null) {
            pendingSave.cancel();
            pendingSave = null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, FriendData> entry : cache.entrySet()) {
            FriendData data = entry.getValue();
            if (data.isEmpty()) {
                continue; // never write an empty record back
            }
            String base = PLAYERS + "." + entry.getKey() + ".";
            if (!data.friends().isEmpty()) {
                yaml.set(base + FRIENDS, new ArrayList<>(data.friends()));
            }
            if (!data.favorites().isEmpty()) {
                yaml.set(base + FAVORITES, new ArrayList<>(data.favorites()));
            }
            writeRequests(yaml, base + OUT_KEY, data.outgoing());
            writeRequests(yaml, base + IN_KEY, data.incoming());
            if (data.giftsSent() > 0) {
                yaml.set(base + GIFTS_SENT, data.giftsSent());
            }
            if (data.giftsReceived() > 0) {
                yaml.set(base + GIFTS_RECEIVED, data.giftsReceived());
            }
            if (!data.activity().isEmpty()) {
                yaml.set(base + ACTIVITY, data.activity());
            }
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save friends.yml", ex);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static void readRequests(ConfigurationSection section, FriendData data, boolean outgoing) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            UUID id = parse(key);
            if (id == null) {
                continue;
            }
            long at = section.getLong(key, System.currentTimeMillis());
            if (outgoing) {
                data.putOutgoing(id, at);
            } else {
                data.putIncoming(id, at);
            }
        }
    }

    private static void writeRequests(YamlConfiguration yaml, String base, Map<UUID, Long> requests) {
        if (requests.isEmpty()) {
            return;
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> entry : requests.entrySet()) {
            flat.put(entry.getKey().toString(), entry.getValue());
        }
        yaml.set(base, flat);
    }

    private static UUID parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
