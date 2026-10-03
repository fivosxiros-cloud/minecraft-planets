package me.foivos.planets.features;

import me.foivos.planets.worldgen.PlanetProfile;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player left a planet: they teleported away, walked out, or logged out
 * while standing on a world bound to a planet profile.
 * <p>
 * Fired by the {@link PlanetFeatureManager} before the features' own
 * {@link PlanetFeature#onPlanetExit} hooks, so ambient sound stopped by this
 * event's listeners and by the features stays in one consistent order:
 * exit first, then {@link PlanetEnterEvent} for the destination.
 */
public final class PlanetExitEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String worldName;
    private final PlanetProfile profile;

    public PlanetExitEvent(Player player, String worldName, PlanetProfile profile) {
        this.player = player;
        this.worldName = worldName;
        this.profile = profile;
    }

    public Player getPlayer() {
        return player;
    }

    /** The world the player left. */
    public String worldName() {
        return worldName;
    }

    /** The profile of the planet that was left; never null. */
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
