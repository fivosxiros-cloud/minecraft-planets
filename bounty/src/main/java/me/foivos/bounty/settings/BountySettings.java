package me.foivos.bounty.settings;

import me.foivos.bounty.BountyPlugin;

import java.util.UUID;
import java.util.logging.Level;

/**
 * The three notification switches, wherever they live.
 *
 * <p>On a server running the Planets plugin these are its {@code /settings}
 * page — the switches a player already knows how to find, saved in the same
 * file as the rest of their preferences. On a server without it, the config.yml
 * defaults answer instead, so the plugin is never broken by a missing
 * dependency, only less configurable.
 *
 * <p>The Planets classes are reached through one small hook class that is only
 * loaded when Planets is actually installed ({@link #hook()}), so the plugin
 * jar runs unchanged on a server that has never heard of it.
 */
public final class BountySettings implements BountySettingsBackend {

    private final BountyPlugin plugin;
    private BountySettingsBackend backend;
    private String source = "config.yml";

    public BountySettings(BountyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Looks for the server's settings menu; falls back to config.yml. */
    public void hook() {
        if (plugin.getServer().getPluginManager().getPlugin("planets") == null) {
            plugin.getLogger().info("Planets is not installed — the bounty notification"
                    + " switches come from config.yml.");
            return;
        }
        try {
            Class<?> hook = Class.forName("me.foivos.bounty.settings.PlanetariumHook");
            backend = (BountySettingsBackend) hook
                    .getDeclaredConstructor(BountyPlugin.class)
                    .newInstance(plugin);
            source = "the /settings menu";
            plugin.getLogger().info("Bounty notification switches added to /settings.");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not add the bounty switches to /settings — using config.yml instead", ex);
            backend = null;
            source = "config.yml";
        }
    }

    @Override
    public boolean get(UUID uuid, BountySetting setting) {
        BountySettingsBackend current = backend;
        if (current == null) {
            return plugin.bountyConfig().personalDefault(setting.key());
        }
        try {
            return current.get(uuid, setting);
        } catch (RuntimeException | LinkageError ex) {
            // The settings menu went away underneath us: fall back rather than
            // dropping the notification altogether.
            plugin.getLogger().log(Level.WARNING,
                    "Could not read a bounty setting from " + source, ex);
            return plugin.bountyConfig().personalDefault(setting.key());
        }
    }

    /** Where the switches are read from, for /bounty reload output. */
    public String source() {
        return source;
    }
}
