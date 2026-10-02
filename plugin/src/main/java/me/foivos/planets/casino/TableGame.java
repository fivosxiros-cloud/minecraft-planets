package me.foivos.planets.casino;

import me.foivos.planets.Planets;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * A game played at a {@link CasinoTable}: more than one player in the same
 * round at the same time, betting real money on the same outcome.
 *
 * <p>The difference between these and the rest of the casino is where the round
 * lives. A coin flip belongs to the player who clicked it; a table round
 * belongs to the server, and any number of players may be in it. That is why
 * this base class exists: it owns the table, the timings and the shutdown, and
 * leaves the game to say only what is bet on, what happens, and who gets paid.
 *
 * <p>Timings are read from config on every round rather than cached, so
 * {@code /casino reload} changes a table's pace without a restart — and a
 * missing key still leaves a playable game, because every value falls back to
 * the number the game ships with.
 */
public abstract class TableGame implements CasinoGame, CasinoTable.Rules {

    private final CasinoManager casino;
    private CasinoTable table;

    protected TableGame(CasinoManager casino) {
        this.casino = casino;
    }

    protected final CasinoManager casino() {
        return casino;
    }

    protected final Planets plugin() {
        return casino.plugin();
    }

    /** The table this game's rounds run on, made once and then shared. */
    protected final CasinoTable table() {
        if (table == null) {
            table = new CasinoTable(plugin(), casino, this);
        }
        return table;
    }

    // ── Settings ────────────────────────────────────────────────────────

    protected final int setting(String key, int fallback) {
        return casino.setting(id(), key, fallback);
    }

    protected final double setting(String key, double fallback) {
        return casino.setting(id(), key, fallback);
    }

    protected final boolean setting(String key, boolean fallback) {
        return casino.setting(id(), key, fallback);
    }

    /** How long bets stay open, in seconds. */
    @Override
    public int bettingSeconds() {
        return Math.max(5, setting("betting-seconds", defaultBettingSeconds()));
    }

    /** How long the draw may take, in seconds. */
    @Override
    public int drawingSeconds() {
        return Math.max(1, setting("drawing-seconds", defaultDrawingSeconds()));
    }

    /** How long the result stays up, in seconds. */
    @Override
    public int resultSeconds() {
        return Math.max(2, setting("result-seconds", defaultResultSeconds()));
    }

    protected abstract int defaultBettingSeconds();

    protected abstract int defaultDrawingSeconds();

    protected abstract int defaultResultSeconds();

    // ── Config ──────────────────────────────────────────────────────────

    @Override
    public boolean loadConfig(ConfigurationSection section) {
        if (section == null) {
            return false;
        }
        boolean wrote = false;
        wrote |= seed(section, "betting-seconds", defaultBettingSeconds());
        wrote |= seed(section, "drawing-seconds", defaultDrawingSeconds());
        wrote |= seed(section, "result-seconds", defaultResultSeconds());
        wrote |= seedSettings(section);
        return wrote;
    }

    /** A game's own settings, written the same way as the shared ones. */
    protected boolean seedSettings(ConfigurationSection section) {
        return false;
    }

    /**
     * Writes a shipped setting into config.yml when the file has none. The
     * ignoreDefaults lookup matters: the plugin's own config.yml sits behind
     * the live file as defaults, so a plain {@code contains} would answer
     * "yes" for every key the jar ships and nothing would ever be written.
     */
    protected final boolean seed(ConfigurationSection section, String key, Object value) {
        if (section.contains(key, true)) {
            return false;
        }
        section.set(key, value);
        return true;
    }

    // ── Playing ─────────────────────────────────────────────────────────

    @Override
    public void open(Player player) {
        newScreen(player).open(player);
    }

    /** One viewer's seat at the table. */
    protected abstract CasinoScreenBase newScreen(Player player);

    @Override
    public void onDisable() {
        if (table != null) {
            table.shutdown();
        }
    }
}
