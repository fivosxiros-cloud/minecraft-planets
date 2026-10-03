package me.foivos.planets.features;

import me.foivos.planets.worldgen.PlanetProfile;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;

/**
 * The hook a future client mod will hang off. Today it does exactly one thing:
 * when a player lands on a planet that has a client integration switched on,
 * it announces the planet's id on the {@code planets:client} plugin-message
 * channel, so a connected mod can already react to it (load its own assets,
 * switch its HUD, whatever it will want to do).
 * <p>
 * It is deliberately a stub: no mod exists yet, no dynamic code is loaded and
 * nothing breaks when nobody is listening — vanilla clients drop the message
 * silently. A mod connects by registering {@code planets:client} as an
 * incoming plugin channel, then parses the UTF-8 planet id. Later versions can
 * grow a proper handshake without changing anything on the profile side: a
 * profile turns this on with {@code integrations.crystal-client: true} (or any
 * {@code integrations.<name>} this class is taught to recognise).
 */
public final class ClientIntegration implements PlanetFeature {

    /** The plugin-message channel a client mod listens on. */
    public static final String CHANNEL = "planets:client";

    /** The integration switches a profile can use to switch this on. */
    private static final String[] SWITCHES = {"crystal-client", "client-mod"};

    private final JavaPlugin plugin;

    public ClientIntegration(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "client-mod";
    }

    @Override
    public String description() {
        return "announces the planet id to a connected client mod on '" + CHANNEL + "'";
    }

    @Override
    public boolean appliesTo(PlanetProfile profile) {
        for (String name : SWITCHES) {
            if (profile.integration(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void enable() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    @Override
    public void disable() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    @Override
    public void onPlanetEnter(Player player, PlanetProfile profile) {
        announce(player, profile);
    }

    @Override
    public void onPlanetChange(Player player, PlanetProfile from, PlanetProfile to) {
        announce(player, to);
    }

    /** Sends the planet id as plain UTF-8; unknown channels are dropped by the client. */
    private void announce(Player player, PlanetProfile profile) {
        try {
            player.sendPluginMessage(plugin, CHANNEL, profile.id().getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().fine("[Feature:" + id() + "] Announced '" + profile.id()
                    + "' to " + player.getName() + " on " + CHANNEL + ".");
        } catch (Throwable ignored) {
            // No mod is listening; the message dies silently and nothing breaks.
        }
    }
}
