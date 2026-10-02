package me.foivos.planets.api;

import java.util.List;
import java.util.UUID;

/**
 * The live handle to one {@code /settings} page an external plugin registered.
 *
 * <p>Keep it for as long as the plugin is enabled: adding a switch to the page
 * after the fact is allowed (the menu is drawn fresh every time it is opened),
 * and reading a switch through this handle always returns what the player
 * actually chose, defaulting to {@link SettingToggle#defaultOn()} for a player
 * who never touched it.
 *
 * <p>Values are saved by the Planets plugin in its own {@code
 * player-settings.yml}, so they survive restarts, and they are cleared along
 * with everything else when a player uses {@code /settings} → Reset to
 * Defaults.
 */
public interface SettingsPage {

    /** The plugin name this page was registered under. */
    String pluginName();

    /** The page id this page was registered under. */
    String id();

    /** The switches currently on the page, in the order they were added. */
    List<SettingToggle> toggles();

    /** Adds a switch to the page. Adding one twice keeps the newer definition. */
    void toggle(SettingToggle toggle);

    /** Whether a player has this switch on (their choice, or its default). */
    boolean get(UUID uuid, String key);

    /** Sets a player's choice and saves it right away. */
    void set(UUID uuid, String key, boolean value);
}
