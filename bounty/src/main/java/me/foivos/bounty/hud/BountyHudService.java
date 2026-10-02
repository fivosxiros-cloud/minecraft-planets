package me.foivos.bounty.hud;

import me.foivos.bounty.BountyPlugin;
import me.foivos.bounty.bounty.BountyManager;
import me.foivos.planets.api.PlanetariumHudService;

import java.util.UUID;

/**
 * The money-on-your-head number handed to the Planets sidebar.
 *
 * <p>Planets asks for the key {@code "bounty"} once a second for every player
 * whose sidebar shows the Bounty line; this answers from the board already in
 * memory, so the call is a map read and never touches the database. When the
 * player has no bounty the answer is {@code 0.0} rather than null — the sidebar
 * should read "$0", not fall back — while null is still returned when the board
 * itself is not there yet (during shutdown, say).
 *
 * <p>This class names Planets' api classes, so it is only registered when
 * Planets is actually installed; without it the bounty plugin runs unchanged.
 */
public final class BountyHudService implements PlanetariumHudService {

    /** The one key Planets currently asks about. */
    static final String BOUNTY_KEY = "bounty";

    private final BountyPlugin plugin;

    public BountyHudService(BountyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public Double number(String key, UUID uuid) {
        if (!BOUNTY_KEY.equals(key) || uuid == null) {
            return null;
        }
        BountyManager bounties = plugin.bounties();
        return bounties == null ? null : bounties.total(uuid);
    }
}
