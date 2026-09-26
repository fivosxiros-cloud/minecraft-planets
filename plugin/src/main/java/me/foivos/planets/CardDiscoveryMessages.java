package me.foivos.planets;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The little celebration shown when a player finds a card they did not have
 * before.
 *
 * <p>Messages come in three tiers — normal, rare and ultra-rare — and which one
 * is drawn is weighted by {@code cards.discovery.rare-chance} and
 * {@code cards.discovery.ultra-chance} (13% and 2% by default, with the rest
 * normal). It is pure flavour: the tier changes the wording, the colour and the
 * sound, never the card or the odds.
 *
 * <p>Only a <b>first</b> copy celebrates. A duplicate just gets a quiet line so
 * a mob farm does not turn chat into a wall of exclamation marks.
 */
final class CardDiscoveryMessages {

    /** One drawn message and how it should be shown. */
    record Discovery(Tier tier, String message) {
    }

    /** The three flavour tiers. */
    enum Tier {
        NORMAL(NamedTextColor.AQUA, null),
        RARE(NamedTextColor.GOLD, "RARE FIND!"),
        ULTRA_RARE(NamedTextColor.LIGHT_PURPLE, "\u2728 ULTRA RARE \u2728");

        private final NamedTextColor color;
        private final String banner;

        Tier(NamedTextColor color, String banner) {
            this.color = color;
            this.banner = banner;
        }

        public NamedTextColor color() { return color; }

        /** A shout in front of the message, or null for the normal tier. */
        public String banner() { return banner; }
    }

    private static final List<String> DEFAULT_NORMAL = List.of(
            "Wow, looks like that's something new ...",
            "Nice card you found right there!",
            "Great job!",
            "Nice find!",
            "Cool find!",
            "Yeeeeyyyy!",
            "Keep up the good work!");

    private static final List<String> DEFAULT_RARE = List.of(
            "Ooooh... we haven't seen this one before!",
            "The collection grows...",
            "Someone's been hunting... \uD83D\uDC40",
            "Your collection just got a little more interesting.",
            "Bro is collecting Minecraft mobs \uD83D\uDC80",
            "Certified card collector moment.",
            "The Planetarium approves. \uD83C\uDF0C");

    private static final List<String> DEFAULT_ULTRA_RARE = List.of(
            "WAIT... YOU ACTUALLY FOUND IT?!",
            "The universe has chosen you.",
            "You were NOT supposed to find this one.",
            "Professional Mob Collector.");

    private final Planets plugin;

    private List<String> normal = DEFAULT_NORMAL;
    private List<String> rare = DEFAULT_RARE;
    private List<String> ultraRare = DEFAULT_ULTRA_RARE;
    private double rareChance = 13.0;
    private double ultraChance = 2.0;

    CardDiscoveryMessages(Planets plugin) {
        this.plugin = plugin;
    }

    /** Reads the pools and the odds; a missing or empty list keeps the defaults. */
    void load(ConfigurationSection cards) {
        ConfigurationSection section = cards == null ? null : cards.getConfigurationSection("discovery");
        this.normal = pool(section, "normal", DEFAULT_NORMAL);
        this.rare = pool(section, "rare", DEFAULT_RARE);
        this.ultraRare = pool(section, "ultra-rare", DEFAULT_ULTRA_RARE);
        this.rareChance = clamp(section == null ? 13.0 : section.getDouble("rare-chance", 13.0));
        this.ultraChance = clamp(section == null ? 2.0 : section.getDouble("ultra-chance", 2.0));
        if (rareChance + ultraChance > 100.0) {
            plugin.getLogger().warning("cards.discovery chances add up to more than 100% — "
                    + "scaling them down so the normal pool still shows.");
            ultraChance = Math.min(ultraChance, 100.0 - rareChance);
        }
    }

    private static List<String> pool(ConfigurationSection section, String key, List<String> fallback) {
        if (section == null) {
            return fallback;
        }
        List<String> configured = section.getStringList(key);
        List<String> cleaned = configured.stream()
                .filter(line -> line != null && !line.isBlank())
                .toList();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }

    /** Draws an ultra-rare, rare or normal message using the configured weights. */
    Discovery pick() {
        double roll = ThreadLocalRandom.current().nextDouble(100.0);
        if (roll < ultraChance && !ultraRare.isEmpty()) {
            return new Discovery(Tier.ULTRA_RARE, random(ultraRare));
        }
        if (roll < ultraChance + rareChance && !rare.isEmpty()) {
            return new Discovery(Tier.RARE, random(rare));
        }
        if (normal.isEmpty()) {
            return new Discovery(Tier.NORMAL, "Nice find!");
        }
        return new Discovery(Tier.NORMAL, random(normal));
    }

    private static String random(List<String> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** How many messages each pool holds, for the event-info page. */
    List<String> normalPool() { return normal; }

    List<String> rarePool() { return rare; }

    List<String> ultraRarePool() { return ultraRare; }

    double rareChance() { return rareChance; }

    double ultraChance() { return ultraChance; }
}
