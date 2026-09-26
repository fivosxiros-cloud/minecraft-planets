package me.foivos.planets;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Which shelf of the collection a card sits on. The collection UI can be
 * filtered by these, and the shipped config files every entity under one of
 * them — but a card with an unknown category is not an error, it simply lands
 * in {@link #SPECIAL}.
 */
public enum CardCategory {

    ANIMALS("animals", "Animals", "\uD83D\uDC11", Material.SHEEP_SPAWN_EGG),
    MONSTERS("monsters", "Monsters", "\uD83D\uDC79", Material.ZOMBIE_SPAWN_EGG),
    BOSSES("bosses", "Bosses", "\uD83D\uDC51", Material.DRAGON_HEAD),
    SPECIAL("special", "Special", "\u2728", Material.NETHER_STAR);

    private final String key;
    private final String label;
    private final String emoji;
    private final Material icon;

    CardCategory(String key, String label, String emoji, Material icon) {
        this.key = key;
        this.label = label;
        this.emoji = emoji;
        this.icon = icon;
    }

    /** The lower-case name used in config.yml. */
    public String key() { return key; }

    public String label() { return label; }

    public String emoji() { return emoji; }

    /** The tab icon in the collection menu. */
    public Material icon() { return icon; }

    /** A display name with the category's emoji in front. */
    public String displayName() { return emoji + " " + label; }

    /** A category by its config name, falling back to {@link #SPECIAL}. */
    public static CardCategory byName(String name) {
        if (name == null || name.isBlank()) {
            return SPECIAL;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        for (CardCategory category : values()) {
            if (category.key.equals(key) || category.name().equalsIgnoreCase(key)) {
                return category;
            }
        }
        return SPECIAL;
    }
}
