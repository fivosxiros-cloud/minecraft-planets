package me.foivos.planets;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Bad-luck protection: a gentle nudge that makes a long dry streak slightly
 * more likely to end.
 *
 * <p>The hunt is designed around flat odds — a 1% card really is 1% per kill —
 * so this is <b>switched off by default</b> and nothing here happens until
 * {@code cards.bad-luck.enabled} is set to true. With it off, the effective
 * chance is always exactly the number written on the card.
 *
 * <p>When it is on, a streak shorter than {@code threshold} still rolls at the
 * card's own odds. Every failure past that point multiplies the chance by
 * another {@code growth-percent} of the base, up to {@code max-multiplier}, so
 * the boost creeps up rather than jumping the odds to certainty. Setting
 * {@code guarantee-at} makes the card finally drop on that many consecutive
 * failures whatever the odds say.
 *
 * <p>Only the <i>roll</i> changes. A card that has been boosted still has to be
 * won; nothing is ever handed out for simply killing enough mobs unless
 * {@code guarantee-at} was asked for.
 *
 * <pre>
 * bad-luck:
 *   enabled: false        # off = the odds are exactly what each card says
 *   threshold: 20         # failures before the odds start to drift up
 *   growth-percent: 15    # each later failure adds 15% of the base chance
 *   max-multiplier: 5.0   # the boost never exceeds 5x the base chance
 *   guarantee-at: 0       # >0 = the card drops at that many failures (0 = never)
 * </pre>
 */
final class CardLuckProtection {

    /** Failures before the odds start to drift, when nothing is configured. */
    private static final int DEFAULT_THRESHOLD = 20;
    /** Extra chance per failure past the threshold, as a fraction of the base. */
    private static final double DEFAULT_GROWTH = 0.15;
    /** How far the boost may go, as a multiple of the base chance. */
    private static final double DEFAULT_MAX_MULTIPLIER = 5.0;

    private final Planets plugin;

    private boolean enabled;
    private int threshold = DEFAULT_THRESHOLD;
    private double growth = DEFAULT_GROWTH;
    private double maxMultiplier = DEFAULT_MAX_MULTIPLIER;
    private int guaranteeAt;

    CardLuckProtection(Planets plugin) {
        this.plugin = plugin;
    }

    /**
     * Reads the {@code cards.bad-luck} block. Silly values are corrected rather
     * than refused: a threshold below 1 would boost every roll, and a multiplier
     * below 1 would make a card <i>less</i> likely the longer somebody tried.
     */
    void load(ConfigurationSection cards) {
        ConfigurationSection section = cards == null ? null : cards.getConfigurationSection("bad-luck");
        if (section == null) {
            enabled = false;
            return;
        }
        this.enabled = section.getBoolean("enabled", false);
        this.threshold = Math.max(1, section.getInt("threshold", DEFAULT_THRESHOLD));
        this.growth = Math.max(0, section.getDouble("growth-percent", DEFAULT_GROWTH * 100) / 100.0);
        this.maxMultiplier = Math.max(1.0, section.getDouble("max-multiplier", DEFAULT_MAX_MULTIPLIER));
        this.guaranteeAt = Math.max(0, section.getInt("guarantee-at", 0));
        if (enabled) {
            plugin.getLogger().info("Bad-luck protection is on: after " + threshold
                    + " failed rolls a card's chance grows by " + CardService.percent(growth * 100.0)
                    + " of its base per failure, up to " + maxMultiplier + "x"
                    + (guaranteeAt > 0
                    ? ", and is guaranteed at " + guaranteeAt + " failures." : "."));
        }
    }

    /** Whether a dry streak changes anything at all. */
    boolean enabled() {
        return enabled;
    }

    /** Consecutive failures needed before the odds start to rise. */
    int threshold() {
        return threshold;
    }

    /** The odds at which this card is actually rolled, given a dry streak. */
    double chance(double base, int streak) {
        return Math.min(1.0, base * multiplier(streak));
    }

    /**
     * How much better the odds are after {@code streak} failures. Always 1 when
     * the protection is off, which is what keeps a disabled server's rolls
     * identical to the odds in the config.
     */
    double multiplier(int streak) {
        if (!enabled || streak < threshold) {
            return 1.0;
        }
        // The first failure past the threshold is the first one that counts.
        double steps = streak - threshold + 1.0;
        return Math.min(maxMultiplier, 1.0 + growth * steps);
    }

    /**
     * Whether the next roll must succeed regardless of its odds, because this
     * player has failed {@code streak} times in a row.
     */
    boolean guaranteed(int streak) {
        return enabled && guaranteeAt > 0 && streak + 1 >= guaranteeAt;
    }

    /** How many more failures until the drop is certain, or 0 when it never is. */
    int remainingUntilGuaranteed(int streak) {
        if (!enabled || guaranteeAt <= 0) {
            return 0;
        }
        return Math.max(0, guaranteeAt - streak);
    }
}
