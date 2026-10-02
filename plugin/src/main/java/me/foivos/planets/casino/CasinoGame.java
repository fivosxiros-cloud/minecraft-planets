package me.foivos.planets.casino;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/**
 * The games the casino hub offers.
 *
 * <p>A game owns everything that makes it a game: its GUI, its clicks, its
 * animation, its rules, its own settings under {@code casino.games.<id>} and
 * the way it reports a result to the {@link CasinoManager}. The hub never
 * knows which games exist — it asks the manager for what is registered and
 * draws them at the slots config.yml gives them.
 *
 * <p>{@link #displayName()}, {@link #icon()} and {@link #description()} are the
 * game's own defaults; a server can override all three per game in config.yml
 * without touching the class.
 *
 * <p>Adding a game is meant to be one class and one line:
 * {@code casino.registerGame(new WheelGame(casino));}
 */
public interface CasinoGame {

    /** The stable key used in config.yml and in every stored statistic. */
    String id();

    /** What the game is called when the config does not say otherwise. */
    String displayName();

    /** The item drawn for the game in the hub. */
    Material icon();

    /** The lines shown under the game's name in the hub. */
    List<String> description();

    /**
     * Opens the game for a player. Called only when the game is enabled, is
     * implemented and the hub is open, so a game can assume a real player who
     * is looking at the hub. Every round a game starts must go through
     * {@link CasinoManager#begin}, which is what records the play and applies
     * the cooldown.
     */
    void open(org.bukkit.entity.Player player);

    /**
     * Reads this game's own settings from {@code casino.games.<id>}. Called
     * once at startup and again on {@code /casino reload}, so a game can be
     * reconfigured without a restart.
     *
     * <p>A game that ships settings of its own — a symbol list, a set of wheel
     * sections — writes them into the section when they are not there yet and
     * says so, the same way the casino seeds everything else: the file is the
     * source of truth from then on, and a server can rewrite the game's own
     * keys without touching the class.
     *
     * @return whether something was written into the file and should be saved
     */
    default boolean loadConfig(org.bukkit.configuration.ConfigurationSection section) {
        return false;
    }

    /** Stops anything this game runs outside a screen. Called on shutdown. */
    default void onDisable() {
    }

    /** The prettier name for a game id the config mentioned but no code claims. */
    static String prettify(String id) {
        if (id == null || id.isBlank()) {
            return "Unknown";
        }
        StringBuilder out = new StringBuilder(id.length());
        for (String word : id.replace('-', '_').split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
