package me.foivos.planets.casino;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Locale;

/**
 * How unusual a casino prize is.
 *
 * <p>The tier only decides how a prize is coloured and announced — the odds
 * come from each reward's own weight in config.yml — so a server owner can
 * make a "legendary" prize common, or an "epic" one rare, without touching any
 * code. The tiers are listed worst-first, so {@link #atLeast} reads the way it
 * sounds.
 */
public enum CasinoRarity {

    COMMON("Common", NamedTextColor.GRAY),
    UNCOMMON("Uncommon", NamedTextColor.GREEN),
    RARE("Rare", NamedTextColor.AQUA),
    EPIC("Epic", NamedTextColor.LIGHT_PURPLE),
    LEGENDARY("Legendary", NamedTextColor.GOLD);

    private final String label;
    private final NamedTextColor colour;

    CasinoRarity(String label, NamedTextColor colour) {
        this.label = label;
        this.colour = colour;
    }

    /** The tier's name as it is written in front of a prize. */
    public String label() {
        return label;
    }

    /** The colour a prize of this tier is announced in. */
    public NamedTextColor colour() {
        return colour;
    }

    /** How far up the tiers this one sits (0 for {@link #COMMON}). */
    public int rank() {
        return ordinal();
    }

    /** Whether this tier is at least as good as {@code other}. */
    public boolean atLeast(CasinoRarity other) {
        return other == null || ordinal() >= other.ordinal();
    }

    /** Reads a tier from config, falling back when it is missing or unknown. */
    public static CasinoRarity parse(String name, CasinoRarity fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String key = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return valueOf(key);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
