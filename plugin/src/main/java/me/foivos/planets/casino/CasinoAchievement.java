package me.foivos.planets.casino;

import org.bukkit.Material;

/**
 * One achievement a player can unlock at the casino.
 *
 * <p>The definition says <i>what</i> has to happen (the {@link Kind}) and the
 * {@code goal} it has to reach; the numbers themselves come from config.yml,
 * so "play 50 games" can become "play 5" without a rebuild. {@code rarity} is
 * only used by {@link Kind#RARE_PRIZE}, which is the tier that counts as rare
 * enough for the lucky achievement.
 */
record CasinoAchievement(String id, String name, String description, Material icon,
                         Kind kind, int goal, CasinoRarity rarity, boolean enabled) {

    /** What a player has to do to unlock an achievement. */
    enum Kind {
        /** Start this many rounds of any game. */
        PLAY_ROUNDS,
        /** Win this many rounds. */
        WIN_ROUNDS,
        /** Use the free daily spin this many times in total. */
        DAILY_SPINS,
        /** Reach this many days in a row on the daily spin. */
        DAILY_STREAK,
        /** Play every game the hub offers. */
        EVERY_GAME,
        /** Set a reaction time at or under {@code goal} milliseconds. */
        REACTION_MS,
        /** Collect a prize of at least {@code rarity}. */
        RARE_PRIZE
    }

    /** Parses a kind from config, falling back when it is missing or unknown. */
    static Kind kind(String name, Kind fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String key = name.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return Kind.valueOf(key);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    /** How the requirement reads in the achievements screen. */
    String requirement() {
        return switch (kind) {
            case PLAY_ROUNDS -> "Play " + goal + (goal == 1 ? " game" : " games");
            case WIN_ROUNDS -> "Win " + goal + (goal == 1 ? " game" : " games");
            case DAILY_SPINS -> "Use the daily spin " + goal + " times";
            case DAILY_STREAK -> "Spin " + goal + " days in a row";
            case EVERY_GAME -> "Play every game in the hub";
            case REACTION_MS -> "React in under " + goal + " ms";
            case RARE_PRIZE -> "Collect a " + rarity.label().toLowerCase(java.util.Locale.ROOT) + " prize";
        };
    }

    /** Whether the player's record satisfies this achievement right now. */
    boolean metBy(CasinoStats stats, int gamesAvailable, boolean hasRarePrize) {
        if (stats == null) {
            return false;
        }
        return switch (kind) {
            case PLAY_ROUNDS -> stats.played() >= goal;
            case WIN_ROUNDS -> stats.wins() >= goal;
            case DAILY_SPINS -> stats.dailySpins() >= goal;
            case DAILY_STREAK -> stats.bestStreak() >= goal;
            case EVERY_GAME -> gamesAvailable > 0 && stats.gamesTried() >= gamesAvailable;
            case REACTION_MS -> stats.bestReactionMs() > 0 && stats.bestReactionMs() <= goal;
            case RARE_PRIZE -> hasRarePrize;
        };
    }
}
