package me.foivos.planets;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Every player's collection, on disk and in memory.
 *
 * <p>Written to {@code cards.yml} together with the event's end timestamp, so a
 * restart, a reload or a crash never costs anybody a card and the timer keeps
 * counting down from the same moment. Changes are folded together and flushed a
 * couple of seconds later — a player cutting through a mob farm causes one write
 * per burst, not one per kill — and a shutdown writes immediately.
 *
 * <pre>
 * event-ends-at: 1750000000000
 * players:
 *   &lt;uuid&gt;:
 *     name: Fivos
 *     view:
 *       category: monsters
 *       filter: missing
 *       page: 2
 *       album: true
 *       leaderboard-page: 1
 *     counts:
 *       sheep: 64
 *       zombie: 43
 *     first:
 *       sheep: 1750000000000
 *     streaks:
 *       warden: 37
 * </pre>
 *
 * <p>The {@code streaks} block counts, per card, how many kills in a row failed
 * to produce it. It only exists while bad-luck protection is switched on, and
 * it is written for nobody otherwise, so a server running flat odds keeps
 * exactly the odds it configured and an untouched file.
 *
 * <p>The {@code view} block is where the player last left the card menus
 * looking — the collection's category, filter and page, plus which page of the
 * leaderboard they were reading — so a relog reopens them where they were
 * instead of snapping back to the start. It is a preference rather than
 * collection data, so it never counts towards progress or the leaderboard.
 */
final class CardStore {

    /** What happened when a card was given to a player. */
    enum Give {
        /** First copy — the discovery celebration fires. */
        NEW,
        /** Another copy of a card they already had. */
        DUPLICATE,
        /** Already at the copy cap, so nothing changed. */
        CAPPED,
        /** Not a card this server knows about. */
        UNKNOWN
    }

    /** One player's collection. */
    static final class Record {
        private String name = "";
        /** card id -> copies held. */
        private final Map<String, Integer> counts = new LinkedHashMap<>();
        /** card id -> when the first copy was found (ms). */
        private final Map<String, Long> first = new LinkedHashMap<>();
        /** card id -> kills in a row that failed to drop it (bad-luck only). */
        private final Map<String, Integer> streaks = new LinkedHashMap<>();
        /** The category key the collection was last filtered to ("" = all). */
        private String viewCategory = "";
        /** The ownership filter key that was last selected ("" = the default). */
        private String viewFilter = "";
        /** Zero-based page the collection was last left on. */
        private int viewPage;
        /** Whether the collection was left in the compact album layout. */
        private boolean viewAlbum;
        /** Zero-based page of the leaderboard the player was last reading. */
        private int viewBoardPage;

        public String name() { return name; }

        public String viewCategory() { return viewCategory; }

        public String viewFilter() { return viewFilter; }

        public int viewPage() { return viewPage; }

        public boolean viewAlbum() { return viewAlbum; }

        public int viewBoardPage() { return viewBoardPage; }

        public Map<String, Integer> counts() { return counts; }

        public Map<String, Long> first() { return first; }

        /** Unique cards = how many different cards they have at least one of. */
        public int unique() { return counts.size(); }

        /** Every copy of every card added together. */
        public int copies() {
            int total = 0;
            for (int value : counts.values()) {
                total += value;
            }
            return total;
        }

        public int count(String id) {
            return counts.getOrDefault(id, 0);
        }

        public long firstAt(String id) {
            return first.getOrDefault(id, 0L);
        }

        /**
         * Whether there is anything worth writing. A stored name on its own is
         * not: a player needs at least one card, a dry streak worth keeping or
         * a saved menu view to be on disk, so joining the server never leaves a
         * record behind.
         */
        private boolean isEmpty() {
            return counts.isEmpty() && first.isEmpty() && streaks.isEmpty() && !hasView();
        }

        /** Whether the player left a card menu on a non-default view. */
        private boolean hasView() {
            return !viewCategory.isBlank() || !viewFilter.isBlank()
                    || viewPage > 0 || viewBoardPage > 0 || viewAlbum;
        }
    }

    /** How long a change waits before it is written (ticks — 5 seconds). */
    private static final long SAVE_DELAY_TICKS = 100L;

    private static final String EVENT_END = "event-ends-at";
    private static final String PLAYERS = "players";
    private static final String COUNTS = "counts";
    private static final String FIRST = "first";
    private static final String STREAKS = "streaks";
    private static final String VIEW = "view";
    private static final String VIEW_CATEGORY = "category";
    private static final String VIEW_FILTER = "filter";
    private static final String VIEW_PAGE = "page";
    private static final String VIEW_ALBUM = "album";
    private static final String VIEW_BOARD_PAGE = "leaderboard-page";

    private final Planets plugin;
    private final File file;
    private final Map<UUID, Record> cache = new ConcurrentHashMap<>();

    /** 0 means "no end set yet", which the service fills in from the duration. */
    private volatile long eventEndsAt;

    private BukkitTask pendingSave;
    private boolean dirty;

    CardStore(Planets plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cards.yml");
    }

    // ── Access ──────────────────────────────────────────────────────────

    /** A player's record, created blank on first use. */
    Record get(UUID uuid) {
        return uuid == null ? new Record() : cache.computeIfAbsent(uuid, key -> new Record());
    }

    /** A player's record as it stands, or null when they never had one. */
    Record peek(UUID uuid) {
        return uuid == null ? null : cache.get(uuid);
    }

    /** Every player with a collection, biggest first — the leaderboard source. */
    List<Map.Entry<UUID, Record>> ranked() {
        List<Map.Entry<UUID, Record>> entries = new ArrayList<>(cache.entrySet());
        entries.removeIf(entry -> entry.getValue().unique() <= 0);
        entries.sort(Comparator
                .comparingInt((Map.Entry<UUID, Record> entry) -> entry.getValue().unique())
                .reversed()
                .thenComparingInt(entry -> entry.getValue().copies())
                .thenComparing(entry -> entry.getValue().name(), String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /** How many players hold at least one card. */
    int participants() {
        int count = 0;
        for (Record record : cache.values()) {
            if (record.unique() > 0) {
                count++;
            }
        }
        return count;
    }

    void rememberName(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        Record record = get(uuid);
        if (!name.equals(record.name)) {
            record.name = name;
            markDirty();
        }
    }

    /**
     * Remembers the collection view a player left open, so the menu reopens on
     * the same category, filter and page. Blank values mean "no preference",
     * and nothing is queued for writing when the view has not actually changed.
     */
    void rememberView(UUID uuid, String categoryKey, String filterKey, int page, boolean album) {
        if (uuid == null) {
            return;
        }
        String category = categoryKey == null ? "" : categoryKey;
        String filter = filterKey == null ? "" : filterKey;
        int safePage = Math.max(0, page);
        Record record = get(uuid);
        if (category.equals(record.viewCategory)
                && filter.equals(record.viewFilter)
                && safePage == record.viewPage
                && album == record.viewAlbum) {
            return;
        }
        record.viewCategory = category;
        record.viewFilter = filter;
        record.viewPage = safePage;
        record.viewAlbum = album;
        markDirty();
    }

    /**
     * Remembers which page of the leaderboard a player was reading. Kept apart
     * from the collection's view so the two menus page independently.
     */
    void rememberBoardPage(UUID uuid, int page) {
        if (uuid == null) {
            return;
        }
        int safePage = Math.max(0, page);
        Record record = get(uuid);
        if (safePage == record.viewBoardPage) {
            return;
        }
        record.viewBoardPage = safePage;
        markDirty();
    }

    // ── Giving cards ────────────────────────────────────────────────────

    /**
     * Adds one copy of a card to a player's collection, respecting the copy cap.
     *
     * <p>Unique progress is counted from the number of distinct cards owned, so
     * this method is the only place that has to know the difference between a
     * first copy and a duplicate.
     */
    Give give(UUID uuid, CardDefinition card) {
        if (uuid == null || card == null) {
            return Give.UNKNOWN;
        }
        Record record = get(uuid);
        int held = record.count(card.id());
        if (held >= card.maxCopies()) {
            return Give.CAPPED;
        }
        record.counts.put(card.id(), held + 1);
        boolean first = held == 0;
        if (first) {
            record.first.put(card.id(), System.currentTimeMillis());
        }
        markDirty();
        return first ? Give.NEW : Give.DUPLICATE;
    }

    int count(UUID uuid, String cardId) {
        Record record = peek(uuid);
        return record == null ? 0 : record.count(cardId);
    }

    // ── Dry streaks (bad-luck protection) ───────────────────────────────

    /** How many kills in a row failed to produce this card. */
    int streak(UUID uuid, String cardId) {
        Record record = peek(uuid);
        return record == null ? 0 : record.streaks.getOrDefault(cardId, 0);
    }

    /** Records one more failure for this card. */
    void missStreak(UUID uuid, String cardId) {
        if (uuid == null || cardId == null) {
            return;
        }
        Record record = get(uuid);
        record.streaks.merge(cardId, 1, Integer::sum);
        markDirty();
    }

    /** Clears the streak after the card finally drops (or is maxed out). */
    void clearStreak(UUID uuid, String cardId) {
        if (uuid == null || cardId == null) {
            return;
        }
        Record record = peek(uuid);
        if (record != null && record.streaks.remove(cardId) != null) {
            markDirty();
        }
    }

    int unique(UUID uuid) {
        Record record = peek(uuid);
        return record == null ? 0 : record.unique();
    }

    int copies(UUID uuid) {
        Record record = peek(uuid);
        return record == null ? 0 : record.copies();
    }

    // ── Event timestamps ────────────────────────────────────────────────

    /** When the event ends (ms), or 0 when no end has been decided yet. */
    long eventEndsAt() {
        return eventEndsAt;
    }

    /** Sets a new end timestamp (admin command) and persists it right away. */
    void eventEndsAt(long millis) {
        this.eventEndsAt = millis;
        markDirty();
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /**
     * Queues a save. The write happens a few seconds later, so a burst of drops
     * costs one disk write rather than one per card.
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

    void load() {
        cache.clear();
        eventEndsAt = 0L;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        eventEndsAt = yaml.getLong(EVENT_END, 0L);
        ConfigurationSection players = yaml.getConfigurationSection(PLAYERS);
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue; // not a uuid — skip
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Record record = new Record();
            record.name = section.getString("name", "");
            ConfigurationSection counts = section.getConfigurationSection(COUNTS);
            if (counts != null) {
                for (String cardId : counts.getKeys(false)) {
                    int value = counts.getInt(cardId, 0);
                    if (value > 0) {
                        record.counts.put(cardId, value);
                    }
                }
            }
            ConfigurationSection first = section.getConfigurationSection(FIRST);
            if (first != null) {
                for (String cardId : first.getKeys(false)) {
                    long at = first.getLong(cardId, 0L);
                    if (at > 0) {
                        record.first.put(cardId, at);
                    }
                }
            }
            ConfigurationSection streaks = section.getConfigurationSection(STREAKS);
            if (streaks != null) {
                for (String cardId : streaks.getKeys(false)) {
                    int value = streaks.getInt(cardId, 0);
                    if (value > 0) {
                        record.streaks.put(cardId, value);
                    }
                }
            }
            ConfigurationSection view = section.getConfigurationSection(VIEW);
            if (view != null) {
                record.viewCategory = string(view, VIEW_CATEGORY);
                record.viewFilter = string(view, VIEW_FILTER);
                record.viewPage = Math.max(0, view.getInt(VIEW_PAGE, 0));
                record.viewAlbum = view.getBoolean(VIEW_ALBUM, false);
                record.viewBoardPage = Math.max(0, view.getInt(VIEW_BOARD_PAGE, 0));
            }
            if (!record.isEmpty()) {
                cache.put(uuid, record);
            }
        }
        plugin.getLogger().info("Loaded card collections for " + cache.size() + " player(s).");
    }

    /** Reads a string that may be absent or explicitly empty. */
    private static String string(ConfigurationSection section, String key) {
        String value = section.getString(key, "");
        return value == null ? "" : value;
    }

    void save() {
        dirty = false;
        if (pendingSave != null) {
            pendingSave.cancel();
            pendingSave = null;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        if (eventEndsAt > 0) {
            yaml.set(EVENT_END, eventEndsAt);
        }
        for (Map.Entry<UUID, Record> entry : cache.entrySet()) {
            Record record = entry.getValue();
            if (record.isEmpty()) {
                continue;
            }
            String base = PLAYERS + "." + entry.getKey() + ".";
            if (record.name != null && !record.name.isBlank()) {
                yaml.set(base + "name", record.name);
            }
            if (record.hasView()) {
                yaml.set(base + VIEW + "." + VIEW_CATEGORY, record.viewCategory);
                yaml.set(base + VIEW + "." + VIEW_FILTER, record.viewFilter);
                if (record.viewPage > 0) {
                    yaml.set(base + VIEW + "." + VIEW_PAGE, record.viewPage);
                }
                if (record.viewAlbum) {
                    yaml.set(base + VIEW + "." + VIEW_ALBUM, true);
                }
                if (record.viewBoardPage > 0) {
                    yaml.set(base + VIEW + "." + VIEW_BOARD_PAGE, record.viewBoardPage);
                }
            }
            if (!record.counts.isEmpty()) {
                yaml.set(base + COUNTS, new LinkedHashMap<>(record.counts));
            }
            if (!record.first.isEmpty()) {
                yaml.set(base + FIRST, new LinkedHashMap<>(record.first));
            }
            if (!record.streaks.isEmpty()) {
                yaml.set(base + STREAKS, new LinkedHashMap<>(record.streaks));
            }
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save cards.yml", ex);
        }
    }
}
