package me.foivos.planets.casino;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the casino ships with: the games and where they sit in the hub, the
 * prize pool, and the achievements.
 *
 * <p>This is only ever used to <b>seed</b> {@code casino} in config.yml the
 * first time the plugin runs, and to fill in a game, prize or achievement that
 * a config.yml written earlier has never heard of. After that the file is the
 * source of truth: the layout, the odds and the achievements can all be
 * rewritten without touching any code, and this class is not consulted again
 * unless a section is missing.
 */
final class CasinoDefaults {

    private CasinoDefaults() {
    }

    /** The hub's shipped title and size. */
    static final String TITLE = "&8Casino";
    static final int SIZE = 54;
    /** The buttons along the bottom of the hub. */
    static final int STATS_SLOT = 48;
    static final int INFO_SLOT = 49;
    static final int CLOSE_SLOT = 50;

    /** One game as it is written into config.yml. */
    record GameDefault(String id, int slot, boolean enabled, Material icon, String name,
                       List<String> description) {
    }

    /** The games the hub lays out, in the order they are drawn. */
    static List<GameDefault> games() {
        return List.of(
                new GameDefault("coinflip", 20, true, Material.GOLD_NUGGET, "&bCoin Flip",
                        List.of("Call it in the air.", "Heads or tails \u2014 nothing staked.")),
                new GameDefault("dice", 22, true, Material.BONE, "&bDice",
                        List.of("Roll and take what comes.", "Six faces, one throw.")),
                new GameDefault("slots", 24, true, Material.SLIME_BALL, "&bSlots",
                        List.of("Three reels, one spin.", "Line the symbols up for a prize.")),
                new GameDefault("wheel", 30, true, Material.COMPASS, "&bWheel",
                        List.of("A wheel that spins and stops.", "Where it lands is where it lands.")),
                new GameDefault("cards", 32, true, Material.PAPER, "&bCard Draw",
                        List.of("Draw a card and reveal it.", "Nothing is wagered on it.")),
                new GameDefault("mystery_chest", 34, true, Material.CHEST, "&bMystery Chest",
                        List.of("A chest that opens itself.", "Inside is a cosmetic prize.")),
                new GameDefault("daily_spin", 39, true, Material.SUNFLOWER, "&bDaily Spin",
                        List.of("One free spin every day.", "Come back tomorrow for the next.")),
                new GameDefault("reaction", 41, true, Material.ENDER_EYE, "&bReaction",
                        List.of("One item lights up.", "Click it before it goes.")),
                new GameDefault("number_guess", 43, true, Material.KNOWLEDGE_BOOK, "&bNumber Guess",
                        List.of("Pick a number, see the draw.", "Ten numbers, one answer.")),
                // The two wagering games sit inside the block the others already
                // occupy — 20, 22, 24 become 20, 21, 22, 23, 24 — rather than
                // being dropped wherever the first free slot happens to be.
                new GameDefault("rps", 21, true, Material.STONE, "&bRock Paper Scissors",
                        List.of("Player vs player",
                                "&eStake coins&7 against a real opponent.",
                                "Winner takes the whole pot.")),
                new GameDefault("numberbet", 23, true, Material.ENCHANTING_TABLE, "&bGuess the Number",
                        List.of("Bet against the house",
                                "&eStake coins&7 and guess 1\u201310.",
                                "Right guess pays 8x.")),
                // The tables. These are the games more than one player is in at
                // once: the whole server bets on the same round, and the round is
                // shared rather than owned by whoever clicked first. They take the
                // free slots in the rows the other games already occupy, so the
                // hub reads as one block instead of growing a second one.
                new GameDefault("lottery", 19, true, Material.PAPER, "&bLottery",
                        List.of("Buy tickets, one pot",
                                "&eEvery ticket&7 goes into the drum.",
                                "One ticket takes it all.")),
                new GameDefault("roulette", 25, true, Material.RED_CONCRETE, "&bRoulette",
                        List.of("The whole table bets at once",
                                "&eStake coins&7 on red, black or zero.",
                                "Red or black pays 2x, zero pays 35x.")),
                new GameDefault("derby", 31, true, Material.SADDLE, "&bDerby",
                        List.of("Four runners, one race",
                                "&eBack a runner&7 with coins.",
                                "Winning backers split the pot.")));
    }

    /** One shipped game by id, or null when the id is not ours. */
    static GameDefault game(String id) {
        for (GameDefault def : games()) {
            if (def.id().equals(id)) {
                return def;
            }
        }
        return null;
    }

    /** Writes one game's shipped defaults into its section. */
    static void writeGame(ConfigurationSection section, GameDefault def) {
        section.set("enabled", def.enabled());
        section.set("slot", def.slot());
        section.set("icon", def.icon().name());
        section.set("name", def.name());
        section.set("description", def.description());
    }

    // ── Prizes ──────────────────────────────────────────────────────────

    /** One prize as it is written into config.yml. */
    record RewardDefault(String id, String name, CasinoRarity rarity, Material icon,
                         List<String> lore, int weight) {
    }

    /**
     * The shipped prize pool, commonest first. The weights add up to 100, so
     * each one reads as a rough percentage: a lucky penny is common, a cosmic
     * die is one throw in a hundred.
     */
    static List<RewardDefault> rewards() {
        List<RewardDefault> pool = new ArrayList<>();
        // ── Common ──
        pool.add(new RewardDefault("lucky-penny", "Lucky Penny", CasinoRarity.COMMON,
                Material.GOLD_NUGGET, List.of("A penny that never bought anything."), 18));
        pool.add(new RewardDefault("clover-sprig", "Clover Sprig", CasinoRarity.COMMON,
                Material.OAK_SAPLING, List.of("Three leaves. The fourth is missing."), 15));
        pool.add(new RewardDefault("chipped-die", "Chipped Die", CasinoRarity.COMMON,
                Material.BONE, List.of("It always lands on something."), 14));
        // ── Uncommon ──
        pool.add(new RewardDefault("silver-token", "Silver Token", CasinoRarity.UNCOMMON,
                Material.IRON_NUGGET, List.of("Spendable nowhere, which is the point."), 12));
        pool.add(new RewardDefault("lucky-clover", "Lucky Clover", CasinoRarity.UNCOMMON,
                Material.LILY_OF_THE_VALLEY, List.of("Picked on a good day."), 10));
        pool.add(new RewardDefault("house-medal", "House Medal", CasinoRarity.UNCOMMON,
                Material.SUNFLOWER, List.of("Given out for turning up."), 9));
        // ── Rare ──
        pool.add(new RewardDefault("golden-token", "Golden Token", CasinoRarity.RARE,
                Material.GOLD_INGOT, List.of("Heavier than it looks."), 6));
        pool.add(new RewardDefault("diamond-pip", "Diamond Pip", CasinoRarity.RARE,
                Material.DIAMOND, List.of("One perfect face of a die that never existed."), 5));
        pool.add(new RewardDefault("ace-of-luck", "Ace of Luck", CasinoRarity.RARE,
                Material.PAPER, List.of("A card from a deck nobody else has."), 4));
        // ── Epic ──
        pool.add(new RewardDefault("lucky-star", "Lucky Star", CasinoRarity.EPIC,
                Material.NETHER_STAR, List.of("Fell a long way to end up here."), 3));
        pool.add(new RewardDefault("rainbow-shell", "Rainbow Shell", CasinoRarity.EPIC,
                Material.HEART_OF_THE_SEA, List.of("Holds a colour for every day you played."), 2));
        // ── Legendary ──
        pool.add(new RewardDefault("jackpot-emblem", "Jackpot Emblem", CasinoRarity.LEGENDARY,
                Material.TOTEM_OF_UNDYING, List.of("The rarest thing the house owns."), 1));
        pool.add(new RewardDefault("cosmic-die", "Cosmic Die", CasinoRarity.LEGENDARY,
                Material.ECHO_SHARD, List.of("It has more than six faces."), 1));
        return pool;
    }

    /** Writes one prize's shipped defaults into its section. */
    static void writeReward(ConfigurationSection section, RewardDefault def) {
        section.set("name", def.name());
        section.set("rarity", def.rarity().name().toLowerCase(Locale.ROOT));
        section.set("icon", def.icon().name());
        section.set("weight", def.weight());
        section.set("lore", def.lore());
    }

    // ── Achievements ────────────────────────────────────────────────────

    /** The achievements the casino ships with. */
    static List<CasinoAchievement> achievements() {
        return List.of(
                new CasinoAchievement("first-game", "First Game",
                        "Play your first casino game", Material.GOLD_NUGGET,
                        CasinoAchievement.Kind.PLAY_ROUNDS, 1, CasinoRarity.COMMON, true),
                new CasinoAchievement("lucky", "Lucky",
                        "Collect a rare prize or better", Material.DIAMOND,
                        CasinoAchievement.Kind.RARE_PRIZE, 1, CasinoRarity.RARE, true),
                new CasinoAchievement("winner", "Winner",
                        "Win ten rounds", Material.GOLD_BLOCK,
                        CasinoAchievement.Kind.WIN_ROUNDS, 10, CasinoRarity.COMMON, true),
                new CasinoAchievement("dedicated", "Dedicated",
                        "Play fifty rounds", Material.CLOCK,
                        CasinoAchievement.Kind.PLAY_ROUNDS, 50, CasinoRarity.COMMON, true),
                new CasinoAchievement("casino-master", "Casino Master",
                        "Play every game the hub offers", Material.NETHER_STAR,
                        CasinoAchievement.Kind.EVERY_GAME, 1, CasinoRarity.LEGENDARY, true),
                new CasinoAchievement("daily-player", "Daily Player",
                        "Use the free daily spin seven times", Material.SUNFLOWER,
                        CasinoAchievement.Kind.DAILY_SPINS, 7, CasinoRarity.UNCOMMON, true),
                new CasinoAchievement("reaction-master", "Reaction Master",
                        "Set a reaction time under 300 ms", Material.ENDER_EYE,
                        CasinoAchievement.Kind.REACTION_MS, 300, CasinoRarity.EPIC, true));
    }

    /** One shipped achievement by id, or null when the id is not ours. */
    static CasinoAchievement achievement(String id) {
        for (CasinoAchievement achievement : achievements()) {
            if (achievement.id().equals(id)) {
                return achievement;
            }
        }
        return null;
    }

    /** Writes one achievement's shipped defaults into its section. */
    static void writeAchievement(ConfigurationSection section, CasinoAchievement achievement) {
        section.set("name", achievement.name());
        section.set("description", achievement.description());
        section.set("icon", achievement.icon().name());
        section.set("kind", achievement.kind().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        section.set("goal", achievement.goal());
        if (achievement.kind() == CasinoAchievement.Kind.RARE_PRIZE) {
            section.set("rarity", achievement.rarity().name().toLowerCase(Locale.ROOT));
        }
        section.set("enabled", achievement.enabled());
    }
}
