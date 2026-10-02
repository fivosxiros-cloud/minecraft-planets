package me.foivos.bounty.settings;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * The three notifications a player controls for themselves.
 *
 * <p>They are the bounty board's contribution to the server's own
 * {@code /settings} menu: when the Planets plugin is installed, these become a
 * "Bounties" page in it and each switch is stored with everything else the
 * player has chosen. Without Planets the config.yml defaults are used and the
 * switches simply cannot be changed.
 *
 * <p>Keys match {@code notifications.personal} in config.yml, so the same word
 * means the same switch in both places.
 */
public enum BountySetting {

    BOUNTY_ADDED("bounty-added", "Bounty Added To Me", Material.GOLD_INGOT,
            "Money other players put on your bounty",
            "You're told when your bounty grows",
            "Increases to your bounty arrive silently"),

    BOUNTY_CLAIMED("bounty-claimed", "Bounty Claimed From Me", Material.SKELETON_SKULL,
            "A note when somebody claims your bounty by killing you",
            "You're told who claimed your bounty, and what it was worth",
            "Claims on you are silent"),

    BOUNTY_RECEIVED("bounty-received", "Bounty I Claimed", Material.DIAMOND_SWORD,
            "A note when you claim a bounty by killing its owner",
            "You're told what you claimed, the moment you claim it",
            "Claims you make are silent");

    private final String key;
    private final String displayName;
    private final Material icon;
    private final String description;
    private final String onText;
    private final String offText;

    BountySetting(String key, String displayName, Material icon, String description,
                  String onText, String offText) {
        this.key = key;
        this.displayName = displayName;
        this.icon = icon;
        this.description = description;
        this.onText = onText;
        this.offText = offText;
    }

    /** The id this switch is stored under, also its key in config.yml. */
    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    /** A fresh icon stack, because the menu puts its own name and lore on it. */
    public ItemStack icon() {
        return new ItemStack(icon);
    }

    public String description() {
        return description;
    }

    public String onText() {
        return onText;
    }

    public String offText() {
        return offText;
    }
}
