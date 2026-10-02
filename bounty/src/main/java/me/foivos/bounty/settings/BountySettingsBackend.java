package me.foivos.bounty.settings;

import java.util.UUID;

/**
 * Where the three notification switches are read from.
 *
 * <p>Two implementations exist: the server's own {@code /settings} menu when
 * the Planets plugin is installed (so a player changes them on the same screen
 * as every other preference they have), and the plugin's config.yml defaults on
 * a server without it.
 *
 * <p>The bounty board only ever <em>reads</em> these: the switch itself is
 * flipped in the settings menu, which is the whole point of putting it there.
 */
public interface BountySettingsBackend {

    /** Whether this player wants this notification. */
    boolean get(UUID uuid, BountySetting setting);
}
