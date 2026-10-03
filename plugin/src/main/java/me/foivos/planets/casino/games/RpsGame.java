package me.foivos.planets.casino.games;

import me.foivos.planets.MenuStyle;
import me.foivos.planets.casino.CasinoFeedback;
import me.foivos.planets.casino.CasinoGame;
import me.foivos.planets.casino.CasinoManager;
import me.foivos.planets.casino.CasinoScreenBase;
import me.foivos.planets.casino.CasinoStats;
import me.foivos.planets.casino.CasinoText;
import me.foivos.planets.casino.CasinoWager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rock, paper, scissors — played against another player, for real money.
 *
 * <p>This is the casino's one game that is not played against the house. A
 * player opens a table with a stake; the table appears in every other player's
 * lobby. Whoever accepts it has their own stake taken, and both then commit a
 * hand to the same round. The two stakes make up a single pot and the hands are
 * compared against each other, so one player's win is another player's loss and
 * the house takes nothing.
 *
 * <p>Because the stakes are real, abandonment is money. Every way a table can
 * end without a winner — either side leaving, either side disconnecting, an
 * open table whose host is gone — returns both stakes, and the table is
 * resolved exactly once so no path can pay out twice.
 *
 * <p>Nothing is revealed early: an opponent's hand is only ever drawn once
 * the round has been settled.
 */
public final class RpsGame implements CasinoGame {

    /** The id this game is known by in config.yml and in every statistic. */
    public static final String ID = "rps";

    /** The stakes a table may be opened for. */
    private static final double[] STAKES = {10, 25, 50, 100, 250};

    /** How often an open screen re-reads the live table: twice a second. */
    private static final long POLL_TICKS = 10L;

    /** How long a finished round stays on screen before returning to the hub. */
    private static final long RESULT_TICKS = 100L;

    private static final String ROCK = "rock";
    private static final String PAPER = "paper";
    private static final String SCISSORS = "scissors";

    private final CasinoManager casino;

    /** Every player in a table right now, whoever they are at it. */
    private final Map<UUID, Duel> atTable = new LinkedHashMap<>();

    /** Tables nobody has accepted yet, oldest first. */
    private final List<Duel> openTables = new ArrayList<>();

    public RpsGame(CasinoManager casino) {
        this.casino = casino;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "&bRock Paper Scissors";
    }

    @Override
    public Material icon() {
        return Material.STONE;
    }

    @Override
    public List<String> description() {
        return List.of(
                "Player vs player",
                "&eStake coins&7 against a real opponent.",
                "Winner takes the whole pot."
        );
    }

    @Override
    public void open(Player player) {
        new Screen(this, player).open(player);
    }

    // ── Tables ──────────────────────────────────────────────────────────

    /** One table: two players, equal stakes, one hidden hand each. */
    static final class Duel {

        final UUID host;
        final String hostName;
        final double stake;

        UUID guest;
        String guestName;
        String hostMove;
        String guestMove;
        /** Set the moment the table is paid out or voided, so it only ever happens once. */
        boolean settled;

        Duel(UUID host, String hostName, double stake) {
            this.host = host;
            this.hostName = hostName;
            this.stake = stake;
        }

        boolean waiting() {
            return guest == null;
        }

        UUID opponentOf(UUID who) {
            return host.equals(who) ? guest : host;
        }

        String nameOf(UUID who) {
            return host.equals(who) ? hostName : guestName;
        }

        String moveOf(UUID who) {
            return host.equals(who) ? hostMove : guestMove;
        }

        void put(UUID who, String move) {
            if (host.equals(who)) {
                hostMove = move;
            } else {
                guestMove = move;
            }
        }

        boolean bothChosen() {
            return hostMove != null && guestMove != null;
        }

        /** {@code 1} when {@code who} holds the winning hand, {@code -1} on a tie. */
        int beats(UUID who) {
            String mine = moveOf(who);
            String theirs = moveOf(opponentOf(who));
            if (mine == null || theirs == null || mine.equals(theirs)) {
                return -1;
            }
            boolean won = (mine.equals(ROCK) && theirs.equals(SCISSORS))
                    || (mine.equals(PAPER) && theirs.equals(ROCK))
                    || (mine.equals(SCISSORS) && theirs.equals(PAPER));
            return won ? 1 : 0;
        }
    }

    /** The table a player is sitting at, or null. */
    private Duel tableOf(UUID who) {
        return atTable.get(who);
    }

    /** Whether this player is seated at any table right now, as host or guest. */
    boolean seated(UUID who) {
        return who != null && atTable.containsKey(who);
    }

    /** The open table whose host is named, or null. */
    private Duel openTableOf(String hostName) {
        if (hostName == null || hostName.isBlank()) {
            return null;
        }
        for (Duel duel : List.copyOf(openTables)) {
            if (!duel.settled && duel.waiting()
                    && duel.hostName.equalsIgnoreCase(hostName.trim())) {
                return duel;
            }
        }
        return null;
    }

    /**
     * Invites a specific player to the host's open table. They get a line in
     * chat whose button joins directly ({@code /casino jointable <host>}); the
     * button runs through the same checks as picking a table in the lobby, so
     * a table that filled up, closed, or lost its host between the invitation
     * and the click simply says so.
     */
    void sendInvite(Player host, Player target) {
        Duel duel = tableOf(host.getUniqueId());
        if (duel == null || duel.settled || !duel.waiting()) {
            host.sendMessage(Component.text("Your table isn't open - ").color(NamedTextColor.GRAY)
                    .append(Component.text("open one from the lobby first.").color(NamedTextColor.GRAY)));
            return;
        }
        if (target == null || !target.isOnline()) {
            host.sendMessage(Component.text("Nobody called '").color(NamedTextColor.RED)
                    .append(Component.text(target == null ? "" : target.getName()).color(NamedTextColor.YELLOW))
                    .append(Component.text("' is online.").color(NamedTextColor.RED)));
            return;
        }
        if (target.getUniqueId().equals(host.getUniqueId())) {
            host.sendMessage(Component.text("That's you - your table is waiting for an opponent.")
                    .color(NamedTextColor.GRAY));
            return;
        }
        if (seated(target.getUniqueId())) {
            host.sendMessage(Component.text(target.getName() + " is already at a table.")
                    .color(NamedTextColor.GRAY));
            return;
        }
        target.sendMessage(Component.text("\u2694 ").color(NamedTextColor.DARK_AQUA)
                .append(Component.text(host.getName()).color(NamedTextColor.AQUA))
                .append(Component.text(" challenges you to rock, paper, scissors for ")
                        .color(NamedTextColor.GRAY))
                .append(Component.text(CasinoWager.money(duel.stake) + " coins")
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(" - winner takes the pot.").color(NamedTextColor.GRAY)));
        target.sendMessage(Component.text("   ")
                .append(Component.text("[\u25B6 Join the table]").color(NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD)
                        .hoverEvent(HoverEvent.showText(
                                Component.text("Duel " + host.getName() + " for "
                                        + CasinoWager.money(duel.stake) + " coins")))
                        .clickEvent(ClickEvent.runCommand("/casino jointable " + host.getName())))
                .append(Component.text("   or type ").color(NamedTextColor.DARK_GRAY))
                .append(Component.text("/casino jointable " + host.getName())
                        .color(NamedTextColor.AQUA)));
        target.playSound(target.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        host.sendMessage(Component.text("\u2694 Invited ").color(NamedTextColor.GRAY)
                .append(Component.text(target.getName()).color(NamedTextColor.AQUA))
                .append(Component.text(" - one click and they are at your table.")
                        .color(NamedTextColor.GRAY)));
    }

    /**
     * Joins the open table of the named host: the chat-invite button's path,
     * and {@code /casino jointable <host>} for anyone typing it by hand.
     *
     * @return whether the guest is now seated
     */
    public boolean joinTable(Player guest, String hostName) {
        Duel duel = openTableOf(hostName);
        if (duel == null) {
            casino.notice(guest, "table-gone",
                    "&7That table isn't open any more \u2014 its stake was returned.");
            return false;
        }
        if (!acceptShared(guest, duel)) {
            return false;
        }
        new Screen(this, guest).open(guest);
        return true;
    }

    /**
     * The checks and notices every way into a table goes through: the picker,
     * the chat-invite button and a hand-typed command all land here, so there
     * is exactly one set of rules about who may sit down.
     */
    private boolean acceptShared(Player guest, Duel duel) {
        if (!casino.wager().available()) {
            casino.notice(guest, "no-economy",
                    "&cThere is no economy on this server to bet with.");
            return false;
        }
        if (!casino.wager().canAfford(guest, duel.stake)) {
            casino.notice(guest, "not-enough",
                    "&cYou need &e%amount%&c coins to accept that table.",
                    "%amount%", CasinoWager.money(duel.stake));
            return false;
        }
        if (!casino.begin(guest, ID)) {
            return false;
        }
        if (!accept(guest, duel)) {
            casino.notice(guest, "withdraw-failed",
                    "&cThat table could not be joined \u2014 nothing was bet.");
            return false;
        }
        CasinoFeedback.click(guest);
        casino.notice(guest, "table-joined",
                "&aYou joined &f%player%&a's table for &e%amount%&a coins. Pick your hand!",
                "%player%", duel.hostName, "%amount%", CasinoWager.money(duel.stake));
        return true;
    }

    /** Opens a table for a host, taking their stake. */
    private Duel openTable(Player host, double stake) {
        CasinoWager wager = casino.wager();
        if (!wager.available() || !wager.canAfford(host, stake) || !wager.take(host, stake)) {
            return null;
        }
        Duel duel = new Duel(host.getUniqueId(), host.getName(), stake);
        openTables.add(duel);
        atTable.put(host.getUniqueId(), duel);
        return duel;
    }

    /** Accepts an open table, taking the guest's matching stake. */
    private boolean accept(Player guest, Duel duel) {
        if (duel.settled || !duel.waiting() || duel.host.equals(guest.getUniqueId())) {
            return false;
        }
        CasinoWager wager = casino.wager();
        if (!wager.available() || !wager.canAfford(guest, duel.stake)
                || !wager.take(guest, duel.stake)) {
            return false;
        }
        duel.guest = guest.getUniqueId();
        duel.guestName = guest.getName();
        openTables.remove(duel);
        atTable.put(guest.getUniqueId(), duel);
        return true;
    }

    /**
     * Ends a table with no winner and returns every stake. Both players are
     * told, when they are still around to be told.
     */
    private void voidTable(Duel duel, String message) {
        if (duel.settled) {
            return;
        }
        duel.settled = true;
        CasinoWager wager = casino.wager();
        wager.refund(Bukkit.getOfflinePlayer(duel.host), duel.stake);
        if (duel.guest != null) {
            wager.refund(Bukkit.getOfflinePlayer(duel.guest), duel.stake);
        }
        openTables.remove(duel);
        release(duel.host);
        release(duel.guest);
        Player host = Bukkit.getPlayer(duel.host);
        if (host != null) {
            casino.notice(host, "table-voided", "&7" + message);
        }
        Player guest = duel.guest == null ? null : Bukkit.getPlayer(duel.guest);
        if (guest != null) {
            casino.notice(guest, "table-voided", "&7" + message);
        }
    }

    /**
     * Settles a table the moment both hands are in: the pot is the two stakes
     * together, and it goes to whoever holds the winning hand. A tie returns
     * both stakes untouched.
     */
    private void settle(Duel duel) {
        if (duel.settled) {
            return;
        }
        duel.settled = true;
        CasinoWager wager = casino.wager();
        int hostResult = duel.beats(duel.host);
        if (hostResult == -1) {
            wager.refund(Bukkit.getOfflinePlayer(duel.host), duel.stake);
            if (duel.guest != null) {
                wager.refund(Bukkit.getOfflinePlayer(duel.guest), duel.stake);
            }
        } else {
            UUID winner = hostResult == 1 ? duel.host : duel.guest;
            if (!wager.give(Bukkit.getOfflinePlayer(winner), duel.stake * 2.0)) {
                // The payout was refused, so put both stakes back rather than
                // let the pot disappear between the two balances.
                wager.refund(Bukkit.getOfflinePlayer(duel.host), duel.stake);
                if (duel.guest != null) {
                    wager.refund(Bukkit.getOfflinePlayer(duel.guest), duel.stake);
                }
            }
        }
        report(duel, duel.host, hostResult);
        if (duel.guest != null) {
            report(duel, duel.guest, hostResult == -1 ? -1 : hostResult == 1 ? 0 : 1);
        }
    }

    /** Records one player's half of a settled round. */
    private void report(Duel duel, UUID who, int result) {
        Player player = Bukkit.getPlayer(who);
        if (player == null) {
            return;
        }
        if (result == -1) {
            casino.draw(player, ID);
            CasinoFeedback.tick(player, 1);
        } else if (result == 1) {
            casino.win(player, ID);
            CasinoFeedback.win(player);
        } else {
            casino.lose(player, ID);
            CasinoFeedback.lose(player);
        }
        casino.notice(player, result == -1 ? "tie" : result == 1 ? "duel-won" : "duel-lost",
                moneyLine(duel, who, result),
                "%amount%", CasinoWager.money(duel.stake));
    }

    private static String moneyLine(Duel duel, UUID who, int result) {
        if (result == -1) {
            return "&7A tie with &f" + duel.nameOf(duel.opponentOf(who))
                    + "&7 \u2014 both stakes returned.";
        }
        if (result == 1) {
            return "&a&lYou beat &f" + duel.nameOf(duel.opponentOf(who))
                    + "&a&l and took the pot of &e%amount%&a&l!";
        }
        return "&cYou lost to &f" + duel.nameOf(duel.opponentOf(who))
                + "&c \u2014 &e%amount%&c gone.";
    }

    /** Forgets one player's seat, leaving the other player's screen its copy. */
    private void release(UUID who) {
        if (who != null) {
            atTable.remove(who);
        }
    }

    /**
     * Returns the stake of any unaccepted table whose host has gone. A table
     * only exists while its host does, so this is what keeps a wager from
     * sitting in limbo after a disconnect.
     */
    private void sweepOpenTables() {
        if (openTables.isEmpty()) {
            return;
        }
        for (Duel duel : List.copyOf(openTables)) {
            if (duel.settled || !duel.waiting()) {
                openTables.remove(duel);
                continue;
            }
            if (Bukkit.getPlayer(duel.host) == null) {
                voidTable(duel, "That table's host left \u2014 the stake was returned.");
            }
        }
    }

    /**
     * Returns every stake still on a table. Called on shutdown, so a restart
     * never costs a player the coins they were waiting with.
     */
    @Override
    public void onDisable() {
        for (Duel duel : List.copyOf(openTables)) {
            voidTable(duel, "The casino closed \u2014 your stake was returned.");
        }
        for (Duel duel : List.copyOf(atTable.values())) {
            voidTable(duel, "The casino closed \u2014 your stake was returned.");
        }
    }

    // ── The screen ──────────────────────────────────────────────────────

    private enum Mode {
        /** Open tables to accept, and the button that opens one. */
        LOBBY,
        /** Choosing what to stake. */
        STAKE,
        /** Sitting at a table, committing a hand. */
        TABLE,
        /** A settled round, hands revealed. */
        RESULT
    }

    private static final class Screen extends CasinoScreenBase {

        private static final int[] TABLE_SLOTS = {10, 11, 12, 13, 14, 15, 16};
        private static final int[] HEADER_SLOTS = {20, 22, 24, 26, 28};
        private static final int ROCK_SLOT = 11;
        private static final int PAPER_SLOT = 13;
        private static final int SCISSORS_SLOT = 15;
        private static final int OPPONENT_SLOT = 22;
        private static final int CENTRE_SLOT = 31;
        private static final int LEAVE_SLOT = 45;
        private static final int RECORD_SLOT = 49;
        private static final int CLOSE_SLOT = 53;

        private final RpsGame game;

        private Mode mode = Mode.LOBBY;
        /** The stake being considered on the STAKE screen. */
        private double stakeChoice = STAKES[0];
        /** Set once, so a settled round schedules exactly one trip back to the hub. */
        private boolean returning;
        private boolean polling;

        Screen(RpsGame game, Player viewer) {
            super(game.casino.plugin(), game.casino, viewer, 54,
                    CasinoText.legacy("&8\u2694 Rock Paper Scissors", NamedTextColor.DARK_AQUA));
            this.game = game;
        }

        // ── Drawing ─────────────────────────────────────────────────────

        @Override
        protected void render() {
            Player player = viewer();
            if (player == null) {
                return;
            }
            ensurePolling();
            inventory().clear();

            Duel duel = game.tableOf(viewerId());
            if (duel != null && mode != Mode.RESULT) {
                mode = Mode.TABLE;
            }
            switch (mode) {
                case LOBBY -> drawLobby(player);
                case STAKE -> drawStake(player);
                case TABLE -> drawTable(player, duel);
                case RESULT -> drawResult(player, duel);
            }
        }

        private void drawLobby(Player player) {
            MenuStyle.decorate(inventory(), "\u2694 Rock Paper Scissors", "\uD83D\uDC65 Player tables");
            inventory().setItem(4, MenuStyle.header("Rock Paper Scissors", NamedTextColor.GOLD,
                    List.of("Player versus player",
                            "Both players stake the same amount.",
                            "Winner takes the whole pot.",
                            "",
                            "Balance: " + CasinoWager.money(casino().wager().balance(player)))));

            List<Duel> open = List.copyOf(game.openTables);
            for (int i = 0; i < open.size() && i < TABLE_SLOTS.length; i++) {
                Duel duel = open.get(i);
                inventory().setItem(TABLE_SLOTS[i], tableItem(duel));
            }
            if (open.isEmpty()) {
                inventory().setItem(OPPONENT_SLOT, item(Material.GRAY_DYE,
                        CasinoText.legacy("&7No open tables", NamedTextColor.GRAY),
                        List.of(grey("Nobody is waiting for a duel."),
                                grey("Open one and wait, or check back."))));
            }

            inventory().setItem(CENTRE_SLOT, button(Material.EMERALD,
                    "\u2694 Open a table",
                    "Stake coins and wait for a challenger",
                    "Winner takes double back"));

            inventory().setItem(LEAVE_SLOT, backButton("Casino"));
            inventory().setItem(RECORD_SLOT, recordItem());
            inventory().setItem(CLOSE_SLOT, closeButton());
        }

        private ItemStack tableItem(Duel duel) {
            return item(Material.PLAYER_HEAD,
                    plain("\uD83D\uDC64 " + duel.hostName).color(NamedTextColor.AQUA),
                    List.of(grey("Stake: " + CasinoWager.money(duel.stake) + " coins"),
                            grey("Win: " + CasinoWager.money(duel.stake * 2) + " coins"),
                            plain(""),
                            plain("Click to accept the duel").color(NamedTextColor.GREEN)));
        }

        private void drawStake(Player player) {
            MenuStyle.decorate(inventory(), "\u2694 Choose a stake", "\uD83D\uDCB0 Your table");
            inventory().setItem(4, MenuStyle.header("Choose a stake", NamedTextColor.GOLD,
                    List.of("What you are willing to put up",
                            "Balance: " + CasinoWager.money(casino().wager().balance(player)))));
            for (int i = 0; i < STAKES.length; i++) {
                inventory().setItem(HEADER_SLOTS[i], stakeItem(player, STAKES[i]));
            }
            inventory().setItem(LEAVE_SLOT, backButton("Tables"));
            inventory().setItem(CLOSE_SLOT, closeButton());
        }

        private ItemStack stakeItem(Player player, double stake) {
            boolean affordable = casino().wager().canAfford(player, stake);
            List<Component> lore = new ArrayList<>();
            lore.add(grey(affordable ? "You can cover this stake"
                    : "Not enough coins for this stake"));
            lore.add(grey("Pot if you win: " + CasinoWager.money(stake * 2) + " coins"));
            return item(affordable ? Material.GOLD_BLOCK : Material.GRAY_DYE,
                    plain("\uD83D\uDCB0 " + CasinoWager.money(stake) + " coins")
                            .color(affordable ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY),
                    lore);
        }

        private void drawTable(Player player, Duel duel) {
            if (duel == null) {
                mode = Mode.LOBBY;
                drawLobby(player);
                return;
            }
            MenuStyle.decorate(inventory(), "\u2694 Your table", "\uD83E\uDD1D Waiting for a hand");
            boolean waiting = duel.waiting();
            UUID opponent = duel.opponentOf(viewerId());
            String mine = duel.moveOf(viewerId());

            inventory().setItem(4, MenuStyle.header("Rock Paper Scissors", NamedTextColor.GOLD,
                    List.of(                            waiting ? "Waiting for a challenger\u2026"
                                    : "Opponent: " + duel.nameOf(opponent),
                            "Stake: " + CasinoWager.money(duel.stake) + " coins",
                            "Pot: " + CasinoWager.money(duel.stake * 2) + " coins",
                            "",
                            mine == null ? "Pick your hand below" : "Hand locked in")));

            inventory().setItem(ROCK_SLOT, handItem(duel, ROCK, Material.STONE, "\uD83E\uDEA8 Rock"));
            inventory().setItem(PAPER_SLOT, handItem(duel, PAPER, Material.PAPER, "\uD83D\uDCC4 Paper"));
            inventory().setItem(SCISSORS_SLOT, handItem(duel, SCISSORS, Material.SHEARS, "\u2702 Scissors"));

            if (waiting) {
                inventory().setItem(OPPONENT_SLOT, item(Material.GRAY_DYE,
                        plain("\uD83D\uDC64 Waiting for an opponent").color(NamedTextColor.GRAY),
                        List.of(grey("Your table is listed for everyone."),
                                grey("Stake: " + CasinoWager.money(duel.stake) + " coins"),
                                plain(""),
                                plain("Click to invite a player")
                                        .color(NamedTextColor.YELLOW))));
            } else {
                String theirs = mine == null ? "\u2026" : "hidden";
                inventory().setItem(OPPONENT_SLOT, headItem(opponent,
                        plain("\uD83D\uDC64 " + duel.nameOf(opponent)).color(NamedTextColor.AQUA),
                        List.of(grey("Their hand: " + theirs),
                                grey("Stake: " + CasinoWager.money(duel.stake) + " coins"))));
            }

            inventory().setItem(CENTRE_SLOT, item(Material.PAPER,
                    plain("\uD83C\uDFAF Your hand").color(NamedTextColor.AQUA),
                    List.of(grey(mine == null ? "Not chosen yet"
                                    : "You committed " + mine),
                            grey("Their hand stays hidden until you both play."))));

            inventory().setItem(LEAVE_SLOT, button(Material.ARROW, "\u274C Leave the table",
                    "Every stake is returned",
                    "The round is voided"));
            inventory().setItem(RECORD_SLOT, recordItem());
            inventory().setItem(CLOSE_SLOT, closeButton());
        }

        private ItemStack handItem(Duel duel, String move, Material icon, String label) {
            String chosen = duel.moveOf(viewerId());
            boolean mine = move.equals(chosen);
            List<Component> lore = new ArrayList<>();
            lore.add(grey(mine ? "Your committed hand" : "Commit this hand to the round"));
            lore.add(plain(""));
            lore.add(plain(mine ? "\u2713 Chosen" : "Click to play it")
                    .color(mine ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            return item(icon, plain(label)
                            .color(mine ? NamedTextColor.GREEN : NamedTextColor.WHITE),
                    lore);
        }

        private void drawResult(Player player, Duel duel) {
            if (duel == null) {
                mode = Mode.LOBBY;
                drawLobby(player);
                return;
            }
            MenuStyle.decorate(inventory(), "\u2694 Round over", "\uD83C\uDFC6 The pot");
            int result = duel.beats(viewerId());
            UUID opponent = duel.opponentOf(viewerId());

            inventory().setItem(4, MenuStyle.header("Rock Paper Scissors", NamedTextColor.GOLD,
                    List.of("Round complete",
                            "Stake: " + CasinoWager.money(duel.stake) + " coins")));

            inventory().setItem(ROCK_SLOT, revealItem(ROCK, Material.STONE, "\uD83E\uDEA8 Rock", duel));
            inventory().setItem(PAPER_SLOT, revealItem(PAPER, Material.PAPER, "\uD83D\uDCC4 Paper", duel));
            inventory().setItem(SCISSORS_SLOT,
                    revealItem(SCISSORS, Material.SHEARS, "\u2702 Scissors", duel));

            if (opponent != null) {
                inventory().setItem(OPPONENT_SLOT, headItem(opponent,
                        plain("\uD83D\uDC64 " + duel.nameOf(opponent)).color(NamedTextColor.AQUA),
                        List.of(grey("They played " + duel.moveOf(opponent)))));
            }

            if (result == -1) {
                inventory().setItem(CENTRE_SLOT, item(Material.WHITE_DYE,
                        plain("\uD83E\uDD1D A tie").color(NamedTextColor.GRAY),
                        List.of(grey("Both stakes were returned."))));
            } else if (result == 1) {
                inventory().setItem(CENTRE_SLOT, item(Material.GOLD_BLOCK,
                        plain("\uD83C\uDFC6 You won the pot").color(NamedTextColor.GOLD),
                        List.of(grey("You collected "
                                + CasinoWager.money(duel.stake * 2) + " coins."))));
            } else {
                inventory().setItem(CENTRE_SLOT, item(Material.COAL,
                        plain("\uD83D\uDC80 You lost").color(NamedTextColor.RED),
                        List.of(grey("Your stake of "
                                + CasinoWager.money(duel.stake) + " coins is gone."))));
            }

            inventory().setItem(LEAVE_SLOT, backButton("Casino"));
            inventory().setItem(RECORD_SLOT, recordItem());
            inventory().setItem(CLOSE_SLOT, closeButton());

            if (!returning) {
                returning = true;
                after(RESULT_TICKS, () -> {
                    Player viewer = viewer();
                    if (viewer != null) {
                        CasinoFeedback.back(viewer);
                        viewer.closeInventory();
                        casino().openHub(viewer);
                    }
                });
            }
        }

        private ItemStack revealItem(String move, Material icon, String label, Duel duel) {
            String mine = duel.moveOf(viewerId());
            String theirs = duel.moveOf(duel.opponentOf(viewerId()));
            boolean playedByMe = move.equals(mine);
            boolean playedByThem = move.equals(theirs);
            List<Component> lore = new ArrayList<>();
            lore.add(grey(playedByMe ? "You played this" : "You did not play this"));
            lore.add(grey(playedByThem ? "They played this" : "They did not play this"));
            return item(icon, plain(label)
                            .color(playedByMe ? NamedTextColor.GREEN
                                    : playedByThem ? NamedTextColor.RED : NamedTextColor.GRAY),
                    lore);
        }

        private ItemStack recordItem() {
            CasinoStats stats = casino().stats(viewerId());
            int plays = stats.plays(ID);
            List<Component> lore = new ArrayList<>();
            lore.add(grey("Tables: " + plays));
            lore.add(grey("Won: " + stats.wins()));
            lore.add(grey("Lost: " + stats.losses()));
            lore.add(grey("Ties: " + stats.draws()));
            return item(Material.PAPER, plain("\uD83D\uDCCA Your duels").color(NamedTextColor.AQUA), lore);
        }

        private ItemStack headItem(UUID who, Component name, List<Component> lore) {
            ItemStack stack = item(Material.PLAYER_HEAD, name, lore);
            if (who != null && stack.getItemMeta() instanceof SkullMeta skull) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(who));
                stack.setItemMeta(skull);
            }
            return stack;
        }

        // ── Keeping the screen live ─────────────────────────────────────

        private void ensurePolling() {
            if (polling) {
                return;
            }
            polling = true;
            every(POLL_TICKS, POLL_TICKS, this::poll);
        }

        /**
         * Re-reads the live table. One player's screen notices a new opponent,
         * an opponent's hand, or a settled round; whichever screen sees both
         * hands first does the settling, and the flag makes it happen once.
         */
        private void poll() {
            Player player = viewer();
            if (player == null) {
                return;
            }
            game.sweepOpenTables();

            Duel duel = game.tableOf(viewerId());
            if (duel == null) {
                if (mode == Mode.TABLE || mode == Mode.RESULT) {
                    mode = Mode.LOBBY;
                    returning = false;
                    render();
                } else if (mode == Mode.LOBBY) {
                    render();
                }
                return;
            }
            if (duel.settled) {
                if (mode != Mode.RESULT) {
                    mode = Mode.RESULT;
                    render();
                }
                return;
            }
            if (mode == Mode.RESULT) {
                return;
            }
            UUID opponent = duel.opponentOf(viewerId());
            if (opponent != null && Bukkit.getPlayer(opponent) == null) {
                game.voidTable(duel, "Your opponent left \u2014 both stakes were returned.");
                return;
            }
            if (duel.bothChosen()) {
                game.settle(duel);
                mode = Mode.RESULT;
            }
            render();
        }

        // ── Clicks ──────────────────────────────────────────────────────

        @Override
        public void handleClick(InventoryClickEvent event) {
            int slot = clickedSlot(event);
            Player player = viewer();
            if (slot < 0 || player == null) {
                return;
            }
            switch (slot) {
                case CLOSE_SLOT -> player.closeInventory();
                case RECORD_SLOT -> {
                    // The read-out is just a read-out.
                }
                case LEAVE_SLOT -> {
                    if (mode == Mode.STAKE) {
                        CasinoFeedback.back(player);
                        mode = Mode.LOBBY;
                        render();
                    } else if (mode == Mode.TABLE) {
                        leave(player);
                    } else {
                        CasinoFeedback.back(player);
                        player.closeInventory();
                        casino().openHub(player);
                    }
                }
                case CENTRE_SLOT -> {
                    if (mode == Mode.LOBBY) {
                        CasinoFeedback.click(player);
                        mode = Mode.STAKE;
                        render();
                    }
                }
                case OPPONENT_SLOT -> {
                    // Waiting for a challenger: the picker challenges one player
                    // directly, and the table stays listed for everyone else.
                    Duel duel = game.tableOf(viewerId());
                    if (mode == Mode.TABLE && duel != null && duel.waiting()) {
                        CasinoFeedback.click(player);
                        new InvitePicker(game, player).open(player);
                    }
                }
                default -> clickTable(player, slot);
            }
        }

        private void clickTable(Player player, int slot) {
            if (mode == Mode.LOBBY) {
                int index = indexOf(TABLE_SLOTS, slot);
                List<Duel> open = List.copyOf(game.openTables);
                if (index >= 0 && index < open.size()) {
                    accept(player, open.get(index));
                }
                return;
            }
            if (mode == Mode.STAKE) {
                int index = indexOf(HEADER_SLOTS, slot);
                if (index >= 0) {
                    stakeChoice = STAKES[index];
                    create(player);
                }
                return;
            }
            if (mode == Mode.TABLE) {
                String move = switch (slot) {
                    case ROCK_SLOT -> ROCK;
                    case PAPER_SLOT -> PAPER;
                    case SCISSORS_SLOT -> SCISSORS;
                    default -> null;
                };
                if (move != null) {
                    commit(player, move);
                }
            }
        }

        private static int indexOf(int[] slots, int slot) {
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == slot) {
                    return i;
                }
            }
            return -1;
        }

        // ── The actual moves ────────────────────────────────────────────

        private void create(Player player) {
            if (!casino().wager().available()) {
                casino().notice(player, "no-economy",
                        "&cThere is no economy on this server to bet with.");
                return;
            }
            if (!casino().wager().canAfford(player, stakeChoice)) {
                casino().notice(player, "not-enough",
                        "&cYou need &e%amount%&c coins to open that table.",
                        "%amount%", CasinoWager.money(stakeChoice));
                return;
            }
            if (!casino().begin(player, ID)) {
                return;
            }
            Duel duel = game.openTable(player, stakeChoice);
            if (duel == null) {
                casino().notice(player, "withdraw-failed",
                        "&cYour stake could not be taken \u2014 nothing was bet.");
                return;
            }
            CasinoFeedback.click(player);
            casino().notice(player, "table-open",
                    "&aYour table is open for &e%amount%&a coins. Waiting for an opponent\u2026",
                    "%amount%", CasinoWager.money(stakeChoice));
            mode = Mode.TABLE;
            render();
        }

        private void accept(Player player, Duel duel) {
            // The checks and the notices live on the game, so the chat-invite
            // button and this picker enforce exactly the same rules.
            if (game.acceptShared(player, duel)) {
                mode = Mode.TABLE;
                render();
            }
        }

        private void commit(Player player, String move) {
            Duel duel = game.tableOf(viewerId());
            if (duel == null || duel.settled) {
                mode = Mode.LOBBY;
                render();
                return;
            }
            duel.put(viewerId(), move);
            CasinoFeedback.click(player);
            render();
        }

        private void leave(Player player) {
            Duel duel = game.tableOf(viewerId());
            if (duel != null) {
                CasinoFeedback.back(player);
                game.voidTable(duel, "The table was closed \u2014 both stakes were returned.");
            }
            mode = Mode.LOBBY;
            render();
        }

        // ── Leaving ─────────────────────────────────────────────────────

        @Override
        protected void onClosed() {
            Duel duel = game.tableOf(viewerId());
            if (duel == null) {
                return;
            }
            if (duel.settled) {
                // The round is paid out; this seat is done with it.
                game.release(viewerId());
                return;
            }
            // Walking away from a live table voids it rather than losing a stake
            // to a menu the player has already closed.
            game.voidTable(duel, "A player left the table \u2014 both stakes were returned.");
        }
    }

    /**
     * The player picker behind "invite a player": everyone online who is not
     * already seated at a table, one head each, and a click sends them the
     * chat challenge with its join button.
     */
    private static final class InvitePicker extends CasinoScreenBase {

        private static final int SIZE = 54;
        private static final int HEADER_SLOT = 4;
        private static final int[] PLAYER_SLOTS = {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43};
        private static final int BACK_SLOT = 45;
        private static final int CLOSE_SLOT = 53;

        private final RpsGame game;
        /** The names on the heads, so a click resolves even after a re-render. */
        private final List<String> candidates = new ArrayList<>();

        InvitePicker(RpsGame game, Player viewer) {
            super(game.casino.plugin(), game.casino, viewer, SIZE,
                    CasinoText.legacy("&8\u2694 Challenge a player", NamedTextColor.DARK_AQUA));
            this.game = game;
        }

        @Override
        protected void render() {
            Player player = viewer();
            if (player == null) {
                return;
            }
            inventory().clear();
            MenuStyle.decorate(inventory(), "\u2694 Challenge a player", "\uD83D\uDC65 Online");
            candidates.clear();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.getUniqueId().equals(viewerId())
                        && !game.seated(online.getUniqueId())) {
                    candidates.add(online.getName());
                }
            }
            inventory().setItem(HEADER_SLOT, MenuStyle.header("Challenge a player", NamedTextColor.GOLD,
                    List.of("They get a challenge in chat with a join button.",
                            candidates.size() + " player(s) free to challenge.")));
            for (int i = 0; i < PLAYER_SLOTS.length && i < candidates.size(); i++) {
                inventory().setItem(PLAYER_SLOTS[i], headItem(candidates.get(i)));
            }
            if (candidates.isEmpty()) {
                inventory().setItem(22, item(Material.GRAY_DYE,
                        plain("\uD83D\uDC65 Nobody free to challenge").color(NamedTextColor.GRAY),
                        List.of(grey("Everyone online is already at a table,"),
                                grey("or there is nobody else on."))));
            }
            inventory().setItem(BACK_SLOT, item(Material.ARROW,
                    plain("\u2B05 Back to your table").color(NamedTextColor.GRAY), List.of()));
            inventory().setItem(CLOSE_SLOT, closeButton());
        }

        private ItemStack headItem(String name) {
            ItemStack stack = item(Material.PLAYER_HEAD,
                    plain("\uD83D\uDC64 " + name).color(NamedTextColor.AQUA),
                    List.of(grey("Send a challenge in chat"),
                            plain("Click to invite").color(NamedTextColor.YELLOW)));
            Player online = Bukkit.getPlayerExact(name);
            if (online != null && stack.getItemMeta() instanceof SkullMeta skull) {
                skull.setOwningPlayer(online);
                stack.setItemMeta(skull);
            }
            return stack;
        }

        @Override
        public void handleClick(InventoryClickEvent event) {
            int slot = clickedSlot(event);
            Player player = viewer();
            if (slot < 0 || player == null) {
                return;
            }
            if (slot == CLOSE_SLOT || slot == BACK_SLOT) {
                CasinoFeedback.back(player);
                player.closeInventory();
                game.openTable(player);
                return;
            }
            for (int i = 0; i < PLAYER_SLOTS.length && i < candidates.size(); i++) {
                if (PLAYER_SLOTS[i] == slot) {
                    Player target = Bukkit.getPlayerExact(candidates.get(i));
                    CasinoFeedback.click(player);
                    player.closeInventory();
                    game.sendInvite(player, target);
                    return;
                }
            }
        }
    }

    /** Puts a player back at their table screen, host or guest alike. */
    void openTable(Player player) {
        new Screen(this, player).open(player);
    }
}
