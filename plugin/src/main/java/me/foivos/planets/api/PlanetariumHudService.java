package me.foivos.planets.api;

/**
 * Numbers another plugin publishes so they can appear in the Planets sidebar
 * and HUD through placeholders such as {@code %bounty%}.
 *
 * <p>The lookup runs through Bukkit's services manager, the mirror image of how
 * this plugin's {@code /settings} pages are handed out: the plugin that owns
 * the numbers registers an implementation at enable time, and Planets reads
 * them without ever compiling against that plugin. Planets only asks for a
 * placeholder that is actually in use, so a server without one of the two
 * plugins simply never makes the call.
 *
 * <p>The implementing side:
 *
 * <pre>{@code
 * getServer().getServicesManager().register(
 *         PlanetariumHudService.class, myImpl, this, ServicePriority.Normal);
 * }</pre>
 */
public interface PlanetariumHudService {

    /**
     * The number shown for one player under one key, or null when this plugin
     * has nothing to say about them (the placeholder then renders as "0" or
     * the template's own fallback).
     *
     * <p>Keys are plain lowercase words agreed between the two plugins, e.g.
     * {@code "bounty"} for the money on a player's head. Implementations are
     * read on the main thread once a second per sidebar, so they must be cheap.
     *
     * @param key  which number is wanted, e.g. {@code "bounty"}
     * @param uuid the player the number is about
     * @return the raw number, or null to say "not tracked"
     */
    Double number(String key, java.util.UUID uuid);
}
