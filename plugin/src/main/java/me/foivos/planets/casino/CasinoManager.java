package me.foivos.planets.casino;

import me.foivos.planets.Planets;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The casino: the games it offers, what config.yml says about them, and the
 * one place every result is recorded.
 *
 * <p>It is the object the plugin holds and the menus talk to, and it is built
 * so that <b>adding a game is one class and one line</b>:
 * {@code casino.registerGame(new WheelGame(casino))}. Everything else — where
 * the game sits in the hub, its icon, its name, whether it is switched on, its
 * own settings, its statistics and its achievements — falls out of that: the
 * hub draws whatever is registered, at the slot config.yml gives it.
 *
 * <p>Games never touch statistics or achievements directly. They report what
 * happened ({@link #begin}, {@link #win}, {@link #lose}, {@link #draw},
 * {@link #reaction}, {@link #grant}) and the manager does the bookkeeping, so
 * the same rules — a cooldown, a played round, a prize, an unlock — apply to
 * every game, including the ones written later.
 *
 * <p>Nothing here is a wager. A round costs nothing, pays nothing, and a prize
 * is a record of itself in {@code casino.yml}: there is no stake, no cash-out
 * and nothing that can be traded, so the casino is cosmetic from end to end.
 */
public final class CasinoManager {

    /** The messages the casino falls back to when config.yml has none. */
    private static final Map<String, String> DEFAULT_MESSAGES = defaultMessages();

    /** One game as the hub sees it: the code, the config, and whether it is playable. */
    record Entry(String id, ConfigurationSection section, CasinoGame game, int slot,
                 boolean enabled, Material icon, String name, List<String> description) {

        /** Whether the game is only a placeholder so far. */
        boolean comingSoon() {
            return game == null;
        }

        /** Whether clicking it should open a game. */
        boolean playable() {
            return game != null && enabled;
        }
    }

    private final Planets plugin;
    private final CasinoStore store;
    private final CasinoWager wager;
    private final CasinoRewards rewards = new CasinoRewards();
    private final CasinoAchievements achievements = new CasinoAchievements();
    private final Map<String, CasinoGame> games = new LinkedHashMap<>();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** When each player may next play each game (uuid → game id → epoch millis). */
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    private ConfigurationSection root;
    private boolean builtInsRegistered;
    private boolean enabled;
    private int size;
    private String title;
    private String leftLabel;
    private String rightLabel;
    private int statsSlot;
    private int infoSlot;
    private int closeSlot;
    private boolean hideDisabled;
    private int cooldownSeconds;

    public CasinoManager(Planets plugin) {
        this.plugin = plugin;
        this.store = new CasinoStore(plugin);
        this.wager = new CasinoWager(plugin);
        this.store.load();
        configure();
    }

    // ── Configuration ───────────────────────────────────────────────────

    /** The {@code casino} section of config.yml, created when the file has none. */
    private ConfigurationSection section() {
        ConfigurationSection casino = plugin.getConfig().getConfigurationSection("casino");
        return casino == null ? plugin.getConfig().createSection("casino") : casino;
    }

    /** Reads everything out of config.yml, seeding what is missing. */
    private void configure() {
        ConfigurationSection casino = section();
        boolean seeded = seed(casino);
        this.root = casino;

        enabled = casino.getBoolean("enabled", true);
        cooldownSeconds = Math.max(0, casino.getInt("cooldown-seconds", 3));
        size = clampSize(casino.getInt("menu.size", CasinoDefaults.SIZE));

        title = casino.getString("menu.title", CasinoDefaults.TITLE);
        leftLabel = casino.getString("menu.left-label", "\uD83C\uDFB0 Games");
        rightLabel = casino.getString("menu.right-label", "\uD83E\uDE99 Yours");
        statsSlot = casino.getInt("menu.stats-slot", CasinoDefaults.STATS_SLOT);
        infoSlot = casino.getInt("menu.info-slot", CasinoDefaults.INFO_SLOT);
        closeSlot = casino.getInt("menu.close-slot", CasinoDefaults.CLOSE_SLOT);
        hideDisabled = casino.getBoolean("menu.hide-disabled", false);

        rewards.load(casino.getConfigurationSection("rewards"));
        boolean achievementsAdded = achievements.load(casino.getConfigurationSection("achievements"));

        registerBuiltIns();
        ConfigurationSection gamesSection = gamesSection();
        boolean gamesAdded = false;
        for (CasinoGame game : games.values()) {
            if (ensureGameSection(gamesSection, game)) {
                gamesAdded = true;
            }
            if (game.loadConfig(gamesSection.getConfigurationSection(game.id()))) {
                gamesAdded = true;
            }
        }
        boolean layoutWritten = rebuildEntries();
        // Each of these is a different reason the file is not what it should be:
        // a section that had to be seeded, a game that had to be added or told
        // where it sits, or a prize/achievement the file had never heard of.
        if (seeded || gamesAdded || layoutWritten || achievementsAdded) {
            plugin.saveConfigQuietly();
        }
        // The ids are named rather than counted, so a server owner can see from
        // the console which games this jar actually registered.
        plugin.getLogger().info("Casino: " + entries.size() + " game(s) in the hub ("
                + gamesAvailable() + " playable), " + rewards.rollableCount() + " rollable prize(s), "
                + achievements.enabledCount() + " achievement(s).");
        plugin.getLogger().info("Casino games: " + String.join(", ", games.keySet()));
    }

    /** Re-reads config.yml — what {@code /casino reload} does. */
    public boolean reload() {
        plugin.reloadConfig();
        configure();
        return true;
    }

    /** Writes the shipped menu, games, prizes, messages and achievements into a fresh section. */
    private boolean seed(ConfigurationSection casino) {
        boolean written = false;
        if (!casino.isConfigurationSection("menu")) {
            ConfigurationSection menu = casino.createSection("menu");
            menu.set("title", CasinoDefaults.TITLE);
            menu.set("size", CasinoDefaults.SIZE);
            menu.set("left-label", "\uD83C\uDFB0 Games");
            menu.set("right-label", "\uD83E\uDE99 Yours");
            menu.set("stats-slot", CasinoDefaults.STATS_SLOT);
            menu.set("info-slot", CasinoDefaults.INFO_SLOT);
            menu.set("close-slot", CasinoDefaults.CLOSE_SLOT);
            menu.set("hide-disabled", false);
            written = true;
        }
        if (!casino.contains("enabled")) {
            casino.set("enabled", true);
            written = true;
        }
        if (!casino.contains("cooldown-seconds")) {
            casino.set("cooldown-seconds", 3);
            written = true;
        }
        if (!casino.isConfigurationSection("games")) {
            ConfigurationSection games = casino.createSection("games");
            for (CasinoDefaults.GameDefault def : CasinoDefaults.games()) {
                CasinoDefaults.writeGame(games.createSection(def.id()), def);
            }
            written = true;
        }
        if (!casino.isConfigurationSection("rewards")) {
            ConfigurationSection pool = casino.createSection("rewards");
            for (CasinoDefaults.RewardDefault def : CasinoDefaults.rewards()) {
                CasinoDefaults.writeReward(pool.createSection(def.id()), def);
            }
            written = true;
        }
        if (!casino.isConfigurationSection("messages")) {
            ConfigurationSection messages = casino.createSection("messages");
            for (Map.Entry<String, String> entry : DEFAULT_MESSAGES.entrySet()) {
                messages.set(entry.getKey(), entry.getValue());
            }
            written = true;
        }
        return written;
    }

    /** A hub size that is a whole number of rows, between one and six. */
    private static int clampSize(int requested) {
        int rows = Math.max(1, Math.min(6, (int) Math.round(requested / 9.0)));
        return rows * 9;
    }

    // ── Games ───────────────────────────────────────────────────────────

    /**
     * Adds a game to the hub. If config.yml has never heard of it, an entry is
     * written for it — with the slot the game ships with, or the first free one
     * when it ships none — and the slot the hub actually used is written back,
     * so the file always names a number a server owner can move.
     */
    public void registerGame(CasinoGame game) {
        if (game == null || game.id() == null || game.id().isBlank()) {
            return;
        }
        games.put(game.id(), game);
        ConfigurationSection gamesSection = gamesSection();
        if (gamesSection != null) {
            if (ensureGameSection(gamesSection, game)) {
                plugin.saveConfigQuietly();
            }
            boolean seeded = game.loadConfig(gamesSection.getConfigurationSection(game.id()));
            if (seeded) {
                plugin.saveConfigQuietly();
            }
            if (rebuildEntries()) {
                plugin.saveConfigQuietly();
            }
        }
    }

    /** The games the plugin itself ships, registered once. */
    private void registerBuiltIns() {
        if (builtInsRegistered) {
            return;
        }
        builtInsRegistered = true;
        games.putIfAbsent("coinflip",
                new me.foivos.planets.casino.games.CoinFlipGame(this));
        games.putIfAbsent("dice",
                new me.foivos.planets.casino.games.DiceGame(this));
        games.putIfAbsent("slots",
                new me.foivos.planets.casino.games.SlotsGame(this));
        games.putIfAbsent("wheel",
                new me.foivos.planets.casino.games.WheelGame(this));
        games.putIfAbsent("mystery_chest",
                new me.foivos.planets.casino.games.MysteryChestGame(this));
        games.putIfAbsent("rps",
                new me.foivos.planets.casino.games.RpsGame(this));
        games.putIfAbsent("numberbet",
                new me.foivos.planets.casino.games.NumberBetGame(this));
        // The tables: rounds the whole server bets on together, so more than one
        // player is in the same game at the same time.
        games.putIfAbsent("roulette",
                new me.foivos.planets.casino.games.RouletteGame(this));
        games.putIfAbsent("lottery",
                new me.foivos.planets.casino.games.LotteryGame(this));
        games.putIfAbsent("derby",
                new me.foivos.planets.casino.games.DerbyGame(this));
    }

    /** Writes a game's shipped entry into config.yml when it has none. */
    private boolean ensureGameSection(ConfigurationSection gamesSection, CasinoGame game) {
        if (gamesSection.isConfigurationSection(game.id())) {
            return adoptShippedSlot(gamesSection.getConfigurationSection(game.id()), game);
        }
        ConfigurationSection fresh = gamesSection.createSection(game.id());
        CasinoDefaults.GameDefault def = CasinoDefaults.game(game.id());
        if (def != null) {
            CasinoDefaults.writeGame(fresh, def);
        } else {
            // A game the plugin has never heard of: lay it out sensibly and let
            // its author (or the server owner) move it about in the file. The
            // rebalance below gives it a real slot on this same start.
            fresh.set("enabled", true);
            fresh.set("slot", -1);
            fresh.set("icon", game.icon().name());
            fresh.set("name", game.displayName());
            fresh.set("description", game.description());
        }
        return true;
    }

    /**
     * Gives a game that has grown a shipped slot since the file was written the
     * slot it now ships with.
     *
     * <p>{@code slot: -1} is not a choice a server owner makes — a server owner
     * writes a number — it is what this plugin used to write for a game it had
     * no layout for. So the first start after that game gains a shipped slot
     * adopts it, and the file stops saying -1 from then on. Anything that is
     * already a number is left exactly as it is.
     */
    private boolean adoptShippedSlot(ConfigurationSection section, CasinoGame game) {
        if (section == null || section.getInt("slot", -1) != -1) {
            return false;
        }
        CasinoDefaults.GameDefault def = CasinoDefaults.game(game.id());
        if (def == null) {
            return false;
        }
        section.set("slot", def.slot());
        return true;
    }

    private ConfigurationSection gamesSection() {
        if (root == null) {
            return null;
        }
        ConfigurationSection games = root.getConfigurationSection("games");
        return games == null ? root.createSection("games") : games;
    }

    /**
     * Rebuilds what the hub draws: one entry per configured game, in config order.
     *
     * <p>A game with no slot of its own is given the first free one, and where
     * it landed is written back into the section — so {@code slot: -1} is a
     * one-start state, never something a server owner has to work out. The same
     * happens to a slot two games both claim, which would otherwise hide one of
     * them behind the other.
     *
     * @return whether a slot had to be filled in, and the file wants saving
     */
    private boolean rebuildEntries() {
        entries.clear();
        ConfigurationSection gamesSection = gamesSection();
        if (gamesSection == null) {
            return false;
        }
        boolean written = false;
        Set<Integer> taken = new HashSet<>();
        for (String id : gamesSection.getKeys(false)) {
            ConfigurationSection section = gamesSection.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            CasinoGame game = games.get(id);
            CasinoDefaults.GameDefault def = CasinoDefaults.game(id);
            Material icon = material(section.getString("icon"),
                    game != null ? game.icon() : def != null ? def.icon() : Material.GRAY_DYE);
            String name = text(section.getString("name"),
                    game != null ? game.displayName()
                            : def != null ? def.name() : "&b" + CasinoGame.prettify(id));
            List<String> described = section.getStringList("description");
            List<String> description = !described.isEmpty() ? described
                    : game != null ? game.description()
                    : def != null ? def.description() : List.of("&7Coming soon.");
            int stored = section.getInt("slot", -1);
            int slot = stored;
            if (slot < 0) {
                slot = nextFreeSlot(taken);
            } else if (taken.contains(slot)) {
                plugin.getLogger().warning("casino: game '" + id + "' asks for slot " + slot
                        + ", which an earlier game already holds.");
                slot = nextFreeSlot(taken);
            }
            if (slot < 0 || slot >= size) {
                plugin.getLogger().warning("casino: game '" + id + "' has no room in the "
                        + size + "-slot hub, so it is not shown. Give it a slot under"
                        + " casino.games." + id + " in config.yml.");
            } else if (slot != stored) {
                // Name the slot in the file, so it can be seen and moved.
                section.set("slot", slot);
                written = true;
            }
            taken.add(slot);
            entries.put(id, new Entry(id, section, game, slot,
                    section.getBoolean("enabled", true), icon, name, description));
        }
        return written;
    }

    /**
     * The first free slot in the hub's field — inside the frame drawn around
     * the menu, above the row the buttons sit in.
     */
    private int nextFreeSlot(Set<Integer> taken) {
        for (int slot = 10; slot <= size - 10; slot++) {
            int column = slot % 9;
            if (column == 0 || column == 8) {
                continue; // the frame
            }
            if (slot < 9 || slot >= size - 9) {
                continue; // the header and the button row
            }
            if (taken.contains(slot) || slot == statsSlot || slot == infoSlot || slot == closeSlot) {
                continue;
            }
            return slot;
        }
        return -1;
    }

    /** How many games a player can actually play right now. */
    public int gamesAvailable() {
        int available = 0;
        for (Entry entry : entries.values()) {
            if (entry.playable()) {
                available++;
            }
        }
        return available;
    }

    private static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material parsed = Material.matchMaterial(name);
        return parsed == null || parsed == Material.AIR ? fallback : parsed;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ── What the menus read ─────────────────────────────────────────────

    boolean enabled() {
        return enabled;
    }

    Collection<Entry> entries() {
        return entries.values();
    }

    Entry entry(String id) {
        return id == null ? null : entries.get(id);
    }

    int size() {
        return size;
    }

    String title() {
        return title;
    }

    String leftLabel() {
        return leftLabel;
    }

    String rightLabel() {
        return rightLabel;
    }

    int statsSlot() {
        return statsSlot;
    }

    int infoSlot() {
        return infoSlot;
    }

    int closeSlot() {
        return closeSlot;
    }

    boolean hideDisabled() {
        return hideDisabled;
    }

    /** The section a game's own settings live in ({@code casino.games.<id>}). */
    public ConfigurationSection gameSection(String gameId) {
        return root == null || gameId == null ? null
                : root.getConfigurationSection("games." + gameId);
    }

    /** How long a player must wait between rounds of one game. */
    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    // ── Opening screens ─────────────────────────────────────────────────

    public void openHub(Player player) {
        if (player == null) {
            return;
        }
        new CasinoMenu(plugin, this, player).open(player);
    }

    public void openStats(Player player) {
        if (player == null) {
            return;
        }
        new CasinoStatsMenu(plugin, this, player).open(player);
    }

    // ── Playing ─────────────────────────────────────────────────────────

    /**
     * Starts a round: applies the cooldown, counts the play and reports it.
     * Every game calls this on the click that begins a round, so "played" means
     * the same thing everywhere and a player cannot click a game to death.
     *
     * @return false when the round must not start, in which case the player has
     *         already been told why
     */
    public boolean begin(Player player, String gameId) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        if (!enabled) {
            deny(player, "closed", DEFAULT_MESSAGES.get("closed"));
            return false;
        }
        Entry entry = entries.get(gameId);
        if (entry == null || entry.comingSoon()) {
            deny(player, "coming-soon", DEFAULT_MESSAGES.get("coming-soon"),
                    "%game%", entry == null ? "That game" : CasinoText.plain(entry.name()));
            return false;
        }
        if (!entry.enabled()) {
            deny(player, "disabled", DEFAULT_MESSAGES.get("disabled"),
                    "%game%", CasinoText.plain(entry.name()));
            return false;
        }
        long left = cooldownLeft(player, gameId);
        if (left > 0) {
            deny(player, "cooldown", DEFAULT_MESSAGES.get("cooldown"),
                    "%seconds%", String.format("%.1f", left / 1000.0));
            return false;
        }
        long until = System.currentTimeMillis() + cooldownSeconds * 1000L;
        cooldowns.computeIfAbsent(player.getUniqueId(), key -> new HashMap<>())
                .put(gameId, until);

        CasinoStats stats = store.get(player.getUniqueId());
        stats.name(player.getName());
        stats.recordPlay(gameId);
        store.markDirty();
        checkAchievements(player, stats);
        return true;
    }

    /** Records a round that the player won. */
    public void win(Player player, String gameId) {
        settle(player, gameId, true, false);
    }

    /** Records a round that the player lost. */
    public void lose(Player player, String gameId) {
        settle(player, gameId, false, false);
    }

    /** Records a round that had no winner — a tie, a draw, a stand-off. */
    public void draw(Player player, String gameId) {
        settle(player, gameId, false, true);
    }

    private void settle(Player player, String gameId, boolean win, boolean draw) {
        if (player == null) {
            return;
        }
        CasinoStats stats = store.get(player.getUniqueId());
        stats.recordOutcome(win, draw);
        store.markDirty();
        checkAchievements(player, stats);
    }

    /** Records a reaction time from a game that measures one. */
    public void reaction(Player player, String gameId, long millis) {
        if (player == null) {
            return;
        }
        CasinoStats stats = store.get(player.getUniqueId());
        if (stats.recordReaction(millis)) {
            store.markDirty();
            checkAchievements(player, stats);
        }
    }

    /**
     * Rolls a prize for a round and files it. The prize is only ever a line in
     * a player's record — nothing is handed over — so a game decides what to
     * show for it and the manager decides that it happened.
     *
     * @return the prize, or null when the pool is empty
     */
    public CasinoReward grant(Player player, String gameId) {
        return grant(player, gameId, null);
    }

    /**
     * Files a prize for a round: the named one when {@code rewardId} is a prize
     * the pool still holds, and a roll of the pool otherwise. Naming a prize is
     * what lets a game with fixed outcomes — a wheel segment that says "Golden
     * Token" on it — hand over the thing it just promised, while a game with no
     * fixed outcome just asks for anything.
     *
     * @return the prize, or null when the pool has nothing to give
     */
    public CasinoReward grant(Player player, String gameId, String rewardId) {
        if (player == null) {
            return null;
        }
        CasinoReward reward = rewardId == null || rewardId.isBlank()
                ? null : rewards.byId(rewardId.trim());
        if (reward == null) {
            reward = rewards.roll();
        }
        if (reward == null) {
            notice(player, "no-prizes", DEFAULT_MESSAGES.get("no-prizes"));
            return null;
        }
        CasinoStats stats = store.get(player.getUniqueId());
        stats.collect(reward.id());
        store.markDirty();
        player.sendMessage(CasinoText.legacy(message("prize", DEFAULT_MESSAGES.get("prize"),
                "%prize%", reward.name()), NamedTextColor.GRAY));
        CasinoFeedback.prize(player, reward.rarity());
        checkAchievements(player, stats);
        return reward;
    }

    // ── The daily spin ──────────────────────────────────────────────────

    /**
     * The day the casino counts as today. {@code casino.games.daily_spin.reset-hour}
     * moves the boundary — with a reset at 4, a spin at 2am still belongs to
     * the day before, which is how a server whose players are up late wants it.
     */
    public LocalDate casinoDay() {
        int hour = Math.floorMod(setting("daily_spin", "reset-hour", 0), 24);
        LocalDateTime now = LocalDateTime.now();
        return now.getHour() < hour ? now.toLocalDate().minusDays(1) : now.toLocalDate();
    }

    /** When the daily spin becomes available again. */
    public LocalDateTime nextReset() {
        int hour = Math.floorMod(setting("daily_spin", "reset-hour", 0), 24);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime today = now.toLocalDate().atTime(hour, 0);
        return now.isBefore(today) ? today : today.plusDays(1);
    }

    /** How long until the daily spin comes back, as "4h 12m". */
    public String nextResetIn() {
        long minutes = Math.max(0L, Duration.between(LocalDateTime.now(), nextReset()).toMinutes());
        long hours = minutes / 60;
        return hours > 0 ? hours + "h " + (minutes % 60) + "m" : minutes + "m";
    }

    public boolean canSpinToday(Player player) {
        return player != null && !store.get(player.getUniqueId()).spunOn(casinoDay());
    }

    /**
     * Uses today's free spin.
     *
     * @return the streak it leaves the player on, or -1 when they have already
     *         spun today
     */
    public int useDailySpin(Player player) {
        if (player == null) {
            return -1;
        }
        CasinoStats stats = store.get(player.getUniqueId());
        stats.name(player.getName());
        int streak = stats.spinToday(casinoDay());
        if (streak > 0) {
            store.markDirty();
            checkAchievements(player, stats);
        }
        return streak;
    }

    // ── Statistics ──────────────────────────────────────────────────────

    public CasinoStats stats(Player player) {
        return player == null ? new CasinoStats() : store.get(player.getUniqueId());
    }

    public CasinoStats stats(UUID uuid) {
        return store.get(uuid);
    }

    /** Every prize in the pool with how many of it a player has collected. */
    public Map<CasinoReward, Integer> cabinet(UUID uuid) {
        CasinoStats stats = store.get(uuid);
        Map<CasinoReward, Integer> cabinet = new LinkedHashMap<>();
        for (CasinoReward reward : rewards.all()) {
            cabinet.put(reward, stats.rewardCount(reward.id()));
        }
        return cabinet;
    }

    /** The prizes in the pool, in config order. */
    public List<CasinoReward> prizes() {
        return rewards.all();
    }

    /**
     * Whether the pool holds a prize, for a game that promises one by name —
     * a wheel section that says what is printed on it.
     */
    public boolean hasPrize(String rewardId) {
        return rewardId != null && rewards.byId(rewardId.trim()) != null;
    }

    List<CasinoAchievement> achievements() {
        return achievements.all();
    }

    // ── Achievements ────────────────────────────────────────────────────

    /** Unlocks everything a player's record now qualifies for, and says so. */
    private void checkAchievements(Player player, CasinoStats stats) {
        if (player == null || stats == null) {
            return;
        }
        CasinoRarity rare = achievements.rareTier(CasinoRarity.RARE);
        boolean hasRarePrize = rewards.hasAtLeast(stats, rare);
        for (CasinoAchievement achievement
                : achievements.qualified(stats, gamesAvailable(), hasRarePrize)) {
            if (!stats.unlock(achievement.id())) {
                continue;
            }
            store.markDirty();
            player.sendMessage(CasinoText.legacy(message("achievement",
                    DEFAULT_MESSAGES.get("achievement"),
                    "%achievement%", achievement.name()), NamedTextColor.GOLD));
            CasinoFeedback.achievement(player);
        }
    }

    // ── Settings and messages ───────────────────────────────────────────

    /** One setting from a game's own section ({@code casino.games.<id>.<key>}). */
    public boolean setting(String gameId, String key, boolean fallback) {
        ConfigurationSection section = gameSection(gameId);
        return section == null ? fallback : section.getBoolean(key, fallback);
    }

    public int setting(String gameId, String key, int fallback) {
        ConfigurationSection section = gameSection(gameId);
        return section == null ? fallback : section.getInt(key, fallback);
    }

    public double setting(String gameId, String key, double fallback) {
        ConfigurationSection section = gameSection(gameId);
        return section == null ? fallback : section.getDouble(key, fallback);
    }

    public String setting(String gameId, String key, String fallback) {
        ConfigurationSection section = gameSection(gameId);
        String value = section == null ? null : section.getString(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** A message from {@code casino.messages.<key>}, with the shipped wording behind it. */
    public String message(String key, String fallback) {
        if (root == null) {
            return fallback == null ? "" : fallback;
        }
        String custom = root.getString("messages." + key);
        if (custom != null && !custom.isBlank()) {
            return custom;
        }
        String shipped = DEFAULT_MESSAGES.get(key);
        return shipped != null ? shipped : (fallback == null ? "" : fallback);
    }

    /**
     * A message from {@code casino.messages.<key>} with its {@code %name%}
     * placeholders filled — the shorthand for what every screen does with one.
     */
    public String message(String key, String fallback, String... pairs) {
        return fill(message(key, fallback), pairs);
    }

    /**
     * Sends a configurable message to a player, colour codes and all. Any
     * {@code %name%} placeholders are filled from {@code pairs}.
     */
    public void notice(Player player, String key, String fallback, String... pairs) {
        if (player == null) {
            return;
        }
        String text = fill(message(key, fallback), pairs);
        if (!text.isBlank()) {
            player.sendMessage(CasinoText.legacy(text, NamedTextColor.GRAY));
        }
    }

    /** Sends a message and the "no" sound, for a click that cannot be obeyed. */
    public void deny(Player player, String key, String fallback, String... pairs) {
        notice(player, key, fallback, pairs);
        CasinoFeedback.deny(player);
    }

    /** Replaces {@code %name%} placeholders in a message. */
    public static String fill(String text, String... pairs) {
        String filled = text == null ? "" : text;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            filled = filled.replace(pairs[i], pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return filled;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /** How long a player still has to wait on one game, in milliseconds. */
    private long cooldownLeft(Player player, String gameId) {
        Map<String, Long> mine = cooldowns.get(player.getUniqueId());
        if (mine == null) {
            return 0L;
        }
        Long until = mine.get(gameId);
        long now = System.currentTimeMillis();
        if (until == null) {
            return 0L;
        }
        if (until <= now) {
            // Cooldowns are dropped as soon as they expire, so the map holds
            // only players who are actually playing.
            mine.remove(gameId);
            if (mine.isEmpty()) {
                cooldowns.remove(player.getUniqueId());
            }
            return 0L;
        }
        return until - now;
    }

    /** Forgets a player's cooldowns (called when they leave the server). */
    public void forget(UUID uuid) {
        if (uuid != null) {
            cooldowns.remove(uuid);
        }
    }

    /** Stops the games and writes everything that is queued. */
    public void shutdown() {
        for (CasinoGame game : games.values()) {
            game.onDisable();
        }
        cooldowns.clear();
        store.save();
    }

    /** The plugin, for the games that need to schedule or read something. */
    public Planets plugin() {
        return plugin;
    }

    /**
     * The money side of the casino, for the games where players bet. Shared so
     * every wager reports its failures in one place.
     */
    public CasinoWager wager() {
        return wager;
    }

    private static Map<String, String> defaultMessages() {
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("closed", "&c\uD83C\uDFB0 The casino is closed right now.");
        messages.put("coming-soon", "&7\u23F3 &f%game%&7 is still being built \u2014 check back soon.");
        messages.put("disabled", "&7\u26D4 &f%game%&7 is switched off right now.");
        messages.put("cooldown", "&7\u231B Slow down \u2014 &e%seconds%s&7 to go.");
        messages.put("no-prizes", "&7There are no prizes in the pool right now.");
        messages.put("prize", "&7\uD83C\uDF81 You found &f%prize%&7.");
        messages.put("achievement", "&8[\u2605] &6Achievement unlocked: &e%achievement%");
        messages.put("busy", "&7That round is still going \u2014 one moment.");
        messages.put("info-intro", "&8\u2500\u2500 &6\uD83C\uDFB0 Casino &8\u2500\u2500");
        messages.put("info-body", "&7Every game is free to play and pays only in cosmetic prizes. "
                + "Nothing is wagered and nothing can be cashed out.");
        messages.put("info-commands", "&7\u2794 &f/casino &7opens the hub \u00B7 &f/casino stats &7your record");
        return messages;
    }

    /** The list of prizes, for a game that wants to advertise them. */
    public List<String> prizeNames() {
        List<String> names = new ArrayList<>();
        for (CasinoReward reward : rewards.all()) {
            if (reward.rollable()) {
                names.add(reward.name());
            }
        }
        return names;
    }
}
