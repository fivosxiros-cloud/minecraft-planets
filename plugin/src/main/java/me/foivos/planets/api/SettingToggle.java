package me.foivos.planets.api;

import org.bukkit.inventory.ItemStack;

/**
 * One switch an external plugin contributes to the Planets {@code /settings}
 * menu: the same ✅/❌ toggle the plugin's own settings use, on a page of its
 * own, remembered in the player's own file.
 *
 * <p>Hover text is written the way the rest of the menu writes it: a one-line
 * description of what the switch affects, plus what the player gets with it on
 * and off. Legacy {@code &}-codes are accepted in the display name, the
 * description and the two state lines.
 *
 * @param key         the id this switch is stored under, unique inside its page
 * @param displayName the name shown on the item
 * @param icon        the item drawn in the menu (a fresh stack each time)
 * @param description one line saying what the switch affects
 * @param onText      what happens while it is on
 * @param offText     what happens while it is off
 * @param defaultOn   the state a player who never touched it has
 */
public record SettingToggle(String key, String displayName, ItemStack icon,
                            String description, String onText, String offText,
                            boolean defaultOn) {

    public SettingToggle {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("a toggle needs a key");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("a toggle needs a display name");
        }
        if (icon == null) {
            throw new IllegalArgumentException("a toggle needs an icon");
        }
    }

    /** A switch described in one line, with the same wording for on and off. */
    public static SettingToggle of(String key, String displayName, ItemStack icon,
                                   String description) {
        return new SettingToggle(key, displayName, icon, description,
                description, description, true);
    }
}
