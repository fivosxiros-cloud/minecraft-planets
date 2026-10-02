package me.foivos.planets.casino;

import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One player's record at the casino: how much they have played, how it went,
 * what they have collected and what they have unlocked.
 *
 * <p>It reads and writes itself against a section of {@code casino.yml}
 * ({@code players.<uuid>}), so the keys and the fields they belong to sit next
 * to each other rather than in a second place that can drift. Only non-zero
 * values are written, so a record stays as small as the play behind it.
 *
 * <p>Mutators are package-private on purpose: a game reports a result to the
 * {@link CasinoManager}, and the manager does the bookkeeping — no menu can
 * award itself a win.
 */
public final class CasinoStats {

    private static final String NAME = "name";
    private static final String PLAYED = "played";
    private static final String COMPLETED = "completed";
    private static final String WINS = "wins";
    private static final String LOSSES = "losses";
    private static final String DRAWS = "draws";
    private static final String DAILY_SPINS = "daily-spins";
    private static final String STREAK = "streak";
    private static final String BEST_STREAK = "best-streak";
    private static final String LAST_SPIN = "last-spin";
    private static final String BEST_REACTION = "best-reaction-ms";
    private static final String PLAYS = "plays";
    private static final String REWARDS = "rewards";
    private static final String ACHIEVEMENTS = "achievements";

    private String name = "";
    private int played;
    private int completed;
    private int wins;
    private int losses;
    private int draws;
    private int dailySpins;
    private int streak;
    private int bestStreak;
    private String lastSpinDay = "";
    private long bestReactionMs;
    private final Map<String, Integer> plays = new LinkedHashMap<>();
    private final Map<String, Integer> rewards = new LinkedHashMap<>();
    private final Set<String> achievements = new LinkedHashSet<>();

    /** The name the player was last seen under, for the record and the cabinet. */
    public String name() {
        return name;
    }

    void name(String value) {
        this.name = value == null ? "" : value;
    }

    /** How many rounds this player has started. */
    public int played() {
        return played;
    }

    /** How many of those rounds reached a result. */
    public int completed() {
        return completed;
    }

    public int wins() {
        return wins;
    }

    public int losses() {
        return losses;
    }

    public int draws() {
        return draws;
    }

    /** How many times the free daily spin has been used. */
    public int dailySpins() {
        return dailySpins;
    }

    /** How many days in a row the daily spin has been used, as of today. */
    public int streak() {
        return streak;
    }

    /** The longest daily streak this player has ever had. */
    public int bestStreak() {
        return bestStreak;
    }

    /** The last day the daily spin was used, as an ISO date ("" for never). */
    public String lastSpinDay() {
        return lastSpinDay;
    }

    /** The fastest reaction recorded, in milliseconds (0 for none yet). */
    public long bestReactionMs() {
        return bestReactionMs;
    }

    /** How many rounds of each game the player has started, by game id. */
    public Map<String, Integer> plays() {
        return Collections.unmodifiableMap(plays);
    }

    /** How many of each prize the player has collected, by reward id. */
    public Map<String, Integer> rewards() {
        return Collections.unmodifiableMap(rewards);
    }

    /** The achievements the player has unlocked, by id. */
    public Set<String> achievements() {
        return Collections.unmodifiableSet(achievements);
    }

    /** How many rounds of one game the player has started. */
    public int plays(String gameId) {
        return gameId == null ? 0 : plays.getOrDefault(gameId, 0);
    }

    /** How many of one prize the player has collected. */
    public int rewardCount(String rewardId) {
        return rewardId == null ? 0 : rewards.getOrDefault(rewardId, 0);
    }

    /** Every prize the player has collected, copies included. */
    public int totalRewards() {
        int total = 0;
        for (int count : rewards.values()) {
            total += count;
        }
        return total;
    }

    /** How many distinct games the player has tried. */
    public int gamesTried() {
        int tried = 0;
        for (int count : plays.values()) {
            if (count > 0) {
                tried++;
            }
        }
        return tried;
    }

    /** The id of the game the player plays most, or "" when they never played. */
    public String favourite() {
        String best = "";
        int highest = 0;
        for (Map.Entry<String, Integer> entry : plays.entrySet()) {
            if (entry.getValue() > highest) {
                highest = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    public boolean hasAchievement(String id) {
        return id != null && achievements.contains(id);
    }

    /** Whether the daily spin has already been used on {@code day}. */
    public boolean spunOn(LocalDate day) {
        return day != null && day.toString().equals(lastSpinDay);
    }

    // ── Bookkeeping (the manager's, not a menu's) ───────────────────────

    void recordPlay(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return;
        }
        played++;
        plays.merge(gameId, 1, Integer::sum);
    }

    void recordOutcome(boolean win, boolean draw) {
        completed++;
        if (draw) {
            draws++;
        } else if (win) {
            wins++;
        } else {
            losses++;
        }
    }

    void collect(String rewardId) {
        if (rewardId != null && !rewardId.isBlank()) {
            rewards.merge(rewardId, 1, Integer::sum);
        }
    }

    /** Unlocks an achievement, answering whether it was new this time. */
    boolean unlock(String achievementId) {
        return achievementId != null && !achievementId.isBlank() && achievements.add(achievementId);
    }

    /**
     * Records a reaction time, answering whether it beat the player's best.
     * A time of 0 or less is treated as "no measurement".
     */
    boolean recordReaction(long millis) {
        if (millis <= 0) {
            return false;
        }
        if (bestReactionMs > 0 && millis >= bestReactionMs) {
            return false;
        }
        bestReactionMs = millis;
        return true;
    }

    /**
     * Uses today's free spin and answers the streak it leaves the player on,
     * or -1 when they have already spun today. The streak only continues when
     * the last spin was yesterday, so a missed day starts it again at 1.
     */
    int spinToday(LocalDate today) {
        if (today == null || spunOn(today)) {
            return -1;
        }
        LocalDate last = parseDay(lastSpinDay);
        streak = today.minusDays(1).equals(last) ? streak + 1 : 1;
        bestStreak = Math.max(bestStreak, streak);
        lastSpinDay = today.toString();
        dailySpins++;
        return streak;
    }

    /** The day after the current streak was last lengthened, or null. */
    LocalDate lastSpinDate() {
        return parseDay(lastSpinDay);
    }

    private static LocalDate parseDay(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    /**
     * Whether there is anything worth writing. A stored name on its own is
     * not: a player has to have played, collected or unlocked something to be
     * worth a line in casino.yml.
     */
    boolean isEmpty() {
        return played == 0 && completed == 0 && wins == 0 && losses == 0 && draws == 0
                && dailySpins == 0 && bestReactionMs <= 0 && rewards.isEmpty()
                && achievements.isEmpty() && plays.isEmpty();
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /** Fills this record from {@code players.<uuid>} in casino.yml. */
    void read(ConfigurationSection section) {
        name = section.getString(NAME, "");
        played = section.getInt(PLAYED, 0);
        completed = section.getInt(COMPLETED, 0);
        wins = section.getInt(WINS, 0);
        losses = section.getInt(LOSSES, 0);
        draws = section.getInt(DRAWS, 0);
        dailySpins = section.getInt(DAILY_SPINS, 0);
        streak = section.getInt(STREAK, 0);
        bestStreak = section.getInt(BEST_STREAK, 0);
        lastSpinDay = section.getString(LAST_SPIN, "");
        bestReactionMs = section.getLong(BEST_REACTION, 0L);
        readCounts(section.getConfigurationSection(PLAYS), plays);
        readCounts(section.getConfigurationSection(REWARDS), rewards);
        achievements.addAll(section.getStringList(ACHIEVEMENTS));
    }

    private static void readCounts(ConfigurationSection section, Map<String, Integer> into) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            int value = section.getInt(key, 0);
            if (value > 0) {
                into.put(key, value);
            }
        }
    }

    /** Writes this record into {@code players.<uuid>} in casino.yml. */
    void write(ConfigurationSection section) {
        if (name != null && !name.isBlank()) {
            section.set(NAME, name);
        }
        set(section, PLAYED, played);
        set(section, COMPLETED, completed);
        set(section, WINS, wins);
        set(section, LOSSES, losses);
        set(section, DRAWS, draws);
        set(section, DAILY_SPINS, dailySpins);
        set(section, STREAK, streak);
        set(section, BEST_STREAK, bestStreak);
        if (lastSpinDay != null && !lastSpinDay.isBlank()) {
            section.set(LAST_SPIN, lastSpinDay);
        }
        if (bestReactionMs > 0) {
            section.set(BEST_REACTION, bestReactionMs);
        }
        writeCounts(section, PLAYS, plays);
        writeCounts(section, REWARDS, rewards);
        if (!achievements.isEmpty()) {
            section.set(ACHIEVEMENTS, new java.util.ArrayList<>(achievements));
        }
    }

    private static void set(ConfigurationSection section, String key, int value) {
        if (value != 0) {
            section.set(key, value);
        }
    }

    /**
     * Writes a tally as its own subsection, one key per id. Handing Bukkit a
     * ready-made Map would be shorter, but a Map is only turned into a section
     * when the file is parsed again, and a record must read back exactly as it
     * was written.
     */
    private static void writeCounts(ConfigurationSection section, String key,
                                    Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return;
        }
        ConfigurationSection written = section.createSection(key);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            written.set(entry.getKey(), entry.getValue());
        }
    }
}
