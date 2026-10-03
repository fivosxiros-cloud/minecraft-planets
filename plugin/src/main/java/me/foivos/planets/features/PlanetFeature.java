package me.foivos.planets.features;

import me.foivos.planets.worldgen.PlanetProfile;
import org.bukkit.entity.Player;

/**
 * One planet-specific capability: dinosaurs, backrooms entities, a resource
 * pack, ambient audio, a gameplay rule, a client integration — anything that
 * makes a planet an <i>experience</i> rather than just terrain.
 * <p>
 * A feature is registered once against the {@link FeatureRegistry} and then
 * switched on per planet by the profile's {@code features:} section (or, for
 * built-ins like the resource pack, by the section that configures it). The
 * feature itself never learns which planets exist and the world generator
 * never learns features exist — generation builds terrain, features provide
 * everything around it.
 * <p>
 * Only the hooks that have a real use today are here: enable/disable for the
 * feature's own lifetime, and enter/exit/change for the player lifecycle.
 * Chunk hooks and friends are deliberately absent until a feature actually
 * needs them.
 */
public interface PlanetFeature {

    /** The registry key, also the name a profile writes under {@code features:}. */
    String id();

    /** One line for {@code /planets debug}, describing what this feature does. */
    default String description() {
        return "";
    }

    /**
     * Whether this feature is active on the given planet. The default reads the
     * profile's {@code features.<id>} switch; a built-in that is configured
     * elsewhere (the resource pack reads {@code client.resource-pack}) overrides
     * this. Unknown switches are off, so a profile written before a module was
     * installed never activates it by accident.
     */
    default boolean appliesTo(PlanetProfile profile) {
        return profile.feature(id());
    }

    /** Called once when the feature is registered and again on every reload. */
    default void enable() {
    }

    /** Called on reload (before {@link #enable()}) and when the plugin shuts down. */
    default void disable() {
    }

    /** The player landed on a planet where this feature is active. */
    default void onPlanetEnter(Player player, PlanetProfile profile) {
    }

    /** The player left a planet where this feature was active. */
    default void onPlanetExit(Player player, PlanetProfile profile) {
    }

    /** The player moved between two planets (both profiles non-null). */
    default void onPlanetChange(Player player, PlanetProfile from, PlanetProfile to) {
    }
}
