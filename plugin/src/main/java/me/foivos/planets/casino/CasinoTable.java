package me.foivos.planets.casino;

import me.foivos.planets.Planets;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A round the whole server bets on at once — the engine behind the casino's
 * tables, where more than one player is in the same game at the same time.
 *
 * <p>A table runs itself: it opens a betting window, closes it, lets the game
 * draw an outcome, pays the round out, shows the result, and opens the next
 * round. Every player looking at the table sees the same round, and anyone may
 * join one already in progress — they simply bet in the next window. That is
 * what makes these games multi-player rather than one player against the house
 * with an audience.
 *
 * <p>Money moves in exactly two places: {@link #bet} takes a stake the moment a
 * player places it, and the game's {@link Rules#settle} pays the round out. The
 * round is drawn and settled once each, guarded here rather than by each game,
 * so no rounding path can pay twice.
 *
 * <p>The loop only runs while it has a reason to: a table with nobody watching
 * and no round waiting on an outcome stops ticking, and one that is only
 * running because a player bet and walked away finishes that round first —
 * their stake is honoured even though they are no longer looking.
 *
 * <p>A table survives the players: an outcome is paid to a uuid, so a player
 * who logs off mid-round still collects.
 */
public final class CasinoTable {

    /** Where a round is in its life. */
    public enum Phase {
        /** No round; the next one opens as soon as somebody is watching. */
        IDLE,
        /** Bets are open, and the countdown on screen is running. */
        BETTING,
        /** Bets are closed and the game is drawing its outcome. */
        DRAWING,
        /** The outcome is on screen, before the next round opens. */
        RESULT
    }

    /** One thing a player put money on. */
    public record Bet(String choice, double amount) {
    }

    /** How a game plugs its own rules into a table. */
    public interface Rules {

        /** The game id, for config.yml and for statistics. */
        String id();

        /** How long bets stay open. */
        int bettingSeconds();

        /** How long the draw is allowed to take. */
        int drawingSeconds();

        /** How long the result stays up before the next round opens. */
        int resultSeconds();

        /**
         * Decides this round: called with the bets closed, and must end in
         * {@link CasinoTable#publish} so the screens have something to show.
         */
        void draw(CasinoTable table);

        /**
         * Pays this round out, once. Called only after {@link #draw}.
         */
        void settle(CasinoTable table);
    }

    /** How often the table advances: twice a second, so the countdown is live. */
    private static final long TICK_TICKS = 10L;

    private final Planets plugin;
    private final CasinoManager casino;
    private final CasinoWager wager;
    private final Rules rules;

    /** Everybody with the table on screen. */
    private final Set<UUID> viewers = new LinkedHashSet<>();
    /** What each player has on this round, in the order they placed it. */
    private final Map<UUID, List<Bet>> bets = new LinkedHashMap<>();

    private Phase phase = Phase.IDLE;
    /** When the current phase ends, in epoch millis. */
    private long endsAt;
    private int round;
    private boolean settled;
    private Object outcome;
    private String outcomeText = "";
    private BukkitTask task;

    public CasinoTable(Planets plugin, CasinoManager casino, Rules rules) {
        this.plugin = plugin;
        this.casino = casino;
        this.wager = casino.wager();
        this.rules = rules;
    }

    /** The game's own id, for the screens that share this table. */
    public String id() {
        return rules.id();
    }

    // ── Watching ────────────────────────────────────────────────────────

    /** A player has the table open; a round starts if none was running. */
    public void watch(UUID who) {
        viewers.add(who);
        if (task == null) {
            task = plugin.getServer().getScheduler()
                    .runTaskTimer(plugin, this::tick, 1L, TICK_TICKS);
        }
    }

    /** A player has closed it. Their bets stay on the table, and still count. */
    public void unwatch(UUID who) {
        viewers.remove(who);
    }

    // ── Where the round is ──────────────────────────────────────────────

    public Phase phase() {
        return phase;
    }

    /** How many rounds this table has run, for the header. */
    public int round() {
        return round;
    }

    public Object outcome() {
        return outcome;
    }

    /** What the draw said, as a line ready for the screen. */
    public String outcomeText() {
        return outcomeText;
    }

    /** Everybody who has money on this round. */
    public Set<UUID> players() {
        return Set.copyOf(bets.keySet());
    }

    public List<Bet> betsOf(UUID who) {
        List<Bet> placed = bets.get(who);
        return placed == null ? List.of() : List.copyOf(placed);
    }

    /** Everything one player has staked this round, across all their bets. */
    public double stakeOf(UUID who) {
        double total = 0;
        for (Bet bet : betsOf(who)) {
            total += bet.amount();
        }
        return total;
    }

    /** Everything on the table. */
    public double pot() {
        double total = 0;
        for (List<Bet> placed : bets.values()) {
            for (Bet bet : placed) {
                total += bet.amount();
            }
        }
        return total;
    }

    /** How long the current phase has left, for the countdown. */
    public int secondsLeft() {
        if (phase == Phase.IDLE) {
            return 0;
        }
        return (int) Math.max(0, Math.ceil((endsAt - System.currentTimeMillis()) / 1000.0));
    }

    // ── Placing and paying ──────────────────────────────────────────────

    /**
     * Lays a stake on something. The money leaves the player here, so a bet is
     * only recorded once it has actually been taken.
     *
     * @return whether the bet was placed, having already told the player why not
     */
    public boolean bet(Player player, double stake, String choice) {
        if (player == null || phase != Phase.BETTING || settled || stake <= 0) {
            return false;
        }
        if (!wager.available()) {
            casino.notice(player, "no-economy",
                    "&cThere is no economy on this server to bet with.");
            return false;
        }
        if (!wager.canAfford(player, stake)) {
            casino.notice(player, "not-enough",
                    "&cYou need &e%amount%&c coins for that bet.",
                    "%amount%", CasinoWager.money(stake));
            return false;
        }
        // Entering a round goes through the manager once, which counts the play
        // and applies the cooldown. Adding chips to a round already entered does
        // not: a spin of the wheel is one round for the player, not one per chip,
        // and the cooldown must not stop somebody raising a bet they already have.
        boolean joining = !bets.containsKey(player.getUniqueId());
        if (joining && !casino.begin(player, rules.id())) {
            return false;
        }
        if (!wager.take(player, stake)) {
            casino.notice(player, "bet-failed",
                    "&cThat stake could not be taken \u2014 nothing was bet.");
            return false;
        }
        bets.computeIfAbsent(player.getUniqueId(), key -> new ArrayList<>())
                .add(new Bet(choice, stake));
        return true;
    }

    /** Publishes what the draw produced, for every screen to show. */
    public void publish(Object outcome, String text) {
        this.outcome = outcome;
        this.outcomeText = text == null ? "" : text;
    }

    /** Pays a player what they won. */
    public void pay(UUID who, double amount) {
        wager.give(Bukkit.getOfflinePlayer(who), amount);
    }

    /** Gives a player a stake back. */
    public void refund(UUID who, double amount) {
        wager.refund(Bukkit.getOfflinePlayer(who), amount);
    }

    /** Returns every stake on the table — what a shutdown or a dead heat does. */
    public void refundAll() {
        for (Map.Entry<UUID, List<Bet>> entry : bets.entrySet()) {
            for (Bet bet : entry.getValue()) {
                refund(entry.getKey(), bet.amount());
            }
        }
        bets.clear();
    }

    /** Stops the table and gives back anything still on it. Called on shutdown. */
    public void shutdown() {
        refundAll();
        settled = true;
        phase = Phase.IDLE;
        stop();
    }

    // ── The loop ────────────────────────────────────────────────────────

    private void tick() {
        long now = System.currentTimeMillis();
        switch (phase) {
            case IDLE -> {
                if (viewers.isEmpty()) {
                    stop();
                    return;
                }
                openRound(now);
            }
            case BETTING -> {
                if (now < endsAt) {
                    return;
                }
                rules.draw(this);
                phase = Phase.DRAWING;
                endsAt = now + rules.drawingSeconds() * 1000L;
            }
            case DRAWING -> {
                if (now < endsAt) {
                    return;
                }
                rules.settle(this);
                settled = true;
                phase = Phase.RESULT;
                endsAt = now + rules.resultSeconds() * 1000L;
            }
            case RESULT -> {
                if (now < endsAt) {
                    return;
                }
                clearRound();
                if (viewers.isEmpty()) {
                    stop();
                }
            }
        }
    }

    private void openRound(long now) {
        round++;
        settled = false;
        outcome = null;
        outcomeText = "";
        bets.clear();
        phase = Phase.BETTING;
        endsAt = now + rules.bettingSeconds() * 1000L;
    }

    private void clearRound() {
        phase = Phase.IDLE;
        settled = false;
        outcome = null;
        outcomeText = "";
        bets.clear();
    }

    private void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** The wager helper, for a game's own settle. */
    public CasinoWager wager() {
        return wager;
    }

    /** The manager, for a game's own settle to report wins and losses. */
    public CasinoManager casino() {
        return casino;
    }

    /** Formats money the casino's way. */
    public static String money(double amount) {
        return CasinoWager.money(amount);
    }

    /** A player by uuid, or null when they are not online. */
    public static Player online(UUID who) {
        return Bukkit.getPlayer(who);
    }

    /** Any player by uuid, online or not, for paying out. */
    public static OfflinePlayer off(UUID who) {
        return Bukkit.getOfflinePlayer(who);
    }
}
