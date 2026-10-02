package me.foivos.planets.api;

import org.bukkit.inventory.ItemStack;

/**
 * The Planets {@code /settings} menu, opened up so other plugins can add a page
 * of their own switches to it instead of inventing a second settings system.
 *
 * <p>The service is registered with Bukkit's services manager, so a plugin only
 * needs the Planets jar on its compile classpath (a {@code provided}
 * dependency) and this call at enable time:
 *
 * <pre>{@code
 * RegisteredServiceProvider<PlanetariumSettingsService> registration =
 *         getServer().getServicesManager().getRegistration(PlanetariumSettingsService.class);
 * if (registration == null) {
 *     return; // Planets is not installed: fall back to the plugin's own defaults
 * }
 * SettingsPage page = registration.getProvider().registerPage(
 *         "Bounty", "notifications", "Bounties", "What the bounty board tells you",
 *         new ItemStack(Material.GOLD_INGOT));
 * page.toggle(new SettingToggle("bounty-added", "Bounty Added To Me",
 *         new ItemStack(Material.GOLD_INGOT), "Money added to your bounty",
 *         "You're told when your bounty grows", "Increases arrive silently", true));
 * }</pre>
 *
 * <p>Declare {@code softdepend: [planets]} in the plugin.yml of the plugin
 * using it: Planets registers the service while it enables (before anything
 * that depends on it), and this lookup returns null when Planets is missing, so
 * the plugin can carry on with its own defaults.
 *
 * <p>A page must be registered at enable time, once: registering the same page
 * id again replaces the definition and keeps every player's saved choices.
 */
public interface PlanetariumSettingsService {

    /** How many pages {@code /settings} can show at once. */
    int MAX_PAGES = 4;

    /**
     * Registers a page of switches for {@code /settings}.
     *
     * @param pluginName  the registering plugin's name, shown on the page's item
     *                    and used as the first part of where choices are stored
     * @param id          the page's own id, e.g. {@code "notifications"}
     * @param displayName the name on the button that opens the page
     * @param description one line saying what the page is for
     * @param icon        the item drawn on that button
     * @return the live page to add switches to and read them back through
     */
    SettingsPage registerPage(String pluginName, String id, String displayName,
                              String description, ItemStack icon);
}
