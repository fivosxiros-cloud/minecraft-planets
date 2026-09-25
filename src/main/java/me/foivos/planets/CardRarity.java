package me.foivos.planets;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Locale;

/**
 * A collectible card's rarity, used for the colour of its name and lore and for
 * the small summary line on the card. It is purely cosmetic — what actually
 * limits a card is {@link CardDefinition#obtainable()} and its drop chance.
 */
public enum CardRarity {

    COMMON("Common", NamedTextColor.WHITE),
    UNCOMMON("Uncommon", NamedTextColor.GREEN),
    RARE("Rare", NamedTextColor.BLUE),
    EPIC("Epic", NamedTextColor.DARK_PURPLE),
    LEGENDARY("Legendary", NamedTextColor.GOLD),
    MYTHIC("Mythic", NamedTextColor.LIGHT_PURPLE),
    /** Never given out — the Ender Dragon card lives here. */
    UNOBTAINABLE("Unobtainable", NamedTextColor.DARK_RED);

    private final String label;
    private final NamedTextColor color;

    CardRarity(String label, NamedTextColor color) {
        this.label = label;
        this.color = color;
    }

    public String label() { return label; }

    public NamedTextColor color() { return color; }

    /** A rarity by its config name, falling back to {@link #COMMON}. */
    public static CardRarity byName(String name) {
        if (name == null || name.isBlank()) {
            return COMMON;
        }
        String key = name.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (CardRarity rarity : values()) {
            if (rarity.name().equalsIgnoreCase(key)) {
                return rarity;
            }
        }
        return COMMON;
    }
}
