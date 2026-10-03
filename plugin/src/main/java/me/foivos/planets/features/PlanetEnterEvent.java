package me.foivos.planets.features;

import me.foivos.planets.worldgen.PlanetProfile;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player arrived on a planet: they teleported, walked through a portal or
 * logged in while standing on a world that is bound to a planet profile.
 * <p>
 * Fired by the {@link PlanetFeatureManager} before the features' own
 * {@link PlanetFeature#onPlanetEnter} hooks, so an external listener (another
 * plugin, a future integration) observes the same lifecycle the built-ins do.
 * Not cancellable — the player is already here; a feature that objects should
 * not have been enabled on this profile.
 */
public final class PlanetEnterEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String worldName;
    private final PlanetProfile profile;

    public PlanetEnterEvent(Player player, String worldName, PlanetProfile profile) {
        this.player = player;
        this.worldName = worldName;
        this.profile = profile;
    }

    public Player getPlayer() {
        return player;
    }

    /** The world the player entered. */
    public String worldName() {
        return worldName;
    }

    /** The profile of the planet that was entered; never null. */
    public PlanetProfile profile() {
        return profile;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
