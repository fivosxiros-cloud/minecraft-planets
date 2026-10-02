package me.foivos.planets.casino.games;

import me.foivos.planets.MenuStyle;
import me.foivos.planets.casino.CasinoFeedback;
import me.foivos.planets.casino.CasinoScreenBase;
import me.foivos.planets.casino.CasinoManager;
import me.foivos.planets.casino.CasinoTable;
import me.foivos.planets.casino.CasinoTable.Bet;
import me.foivos.planets.casino.CasinoText;
import me.foivos.planets.casino.CasinoWager;
import me.foivos.planets.casino.TableGame;
import me.foivos.planets.casino.TableScreen;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Roulette: one wheel, everybody's bets on it.
 *
 * <p>This is the casino's first game where players are betting against the house
 * on the <em>same</em> event. The table opens for twenty seconds, anyone at the
 * table may stake on red, black or zero, the wheel is spun once, and every bet
 * on the table is settled by that one number. Two players who both back red win
 * together; a player alone on black loses while the rest of the table is paid
 * around them.
 *
 * <p>The odds are the real ones, and so is the house's edge. Red or black pays
 * double, so eighteen chances out of thirty-seven pay 2x — the wheel is what
 * pays for the casino rather than an invented fee, and the house is not
 * guaranteed a round: a table that backs the right colour is paid out of the
 * server's economy, which is exactly what a casino is.
 *
 * <p>Each chip is a click, and chips can be added until the wheel spins. Entering
 * a round counts once, so raising a bet does not wait on the hub's cooldown.
 */
public final class RouletteGame extends TableGame {

    /** The id this game is known by in config.yml and in every statistic. */
    static final String ID = "roulette";

    /** The numbers that are red on a real wheel. Everything else but zero is black. */
    private static final Set<Integer> RED_NUMBERS =
            Set.of(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);

    /** The three colours a player can back. */
    private static final String RED = "red";
    private static final String BLACK = "black";
    private static final String GREEN = "green";

    /**
     * The betting grid: one column per colour, one row per stake. Every chip is
     * a single click, so nobody has to pick an amount and then a colour.
     */
    private static final int[][] BET_SLOTS = {
            {20, 22, 24},
            {29, 31, 33},
            {38, 40, 42}
    };

    private static final String[] COLOURS = {RED, BLACK, GREEN};
    private static final Material[] ICONS = {
            Material.RED_CONCRETE, Material.BLACK_CONCRETE, Material.LIME_CONCRETE
    };
    private static final String[] COLOUR_NAMES = {"Red", "Black", "Zero"};

    /** Where the wheel is shown while it spins and once it has stopped. */
    private static final int WHEEL_SLOT = 13;

    /** The stakes a player can put on one click. */
    private static final double[] SHIPPED_STAKES = {50, 250, 1000};

    public RouletteGame(CasinoManager casino) {
        super(casino);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "&bRoulette";
    }

    @Override
    public Material icon() {
        return Material.RED_CONCRETE;
    }

    @Override
    public List<String> description() {
        return List.of(
                "The whole table bets at once",
                "&eStake coins&7 on red, black or zero.",
                "Red or black pays 2x, zero pays 35x."
        );
    }

    @Override
    protected int defaultBettingSeconds() {
        return 20;
    }

    @Override
    protected int defaultDrawingSeconds() {
        return 4;
    }

    @Override
    protected int defaultResultSeconds() {
        return 8;
    }

    @Override
    protected boolean seedSettings(ConfigurationSection section) {
        boolean wrote = false;
        wrote |= seed(section, "stake-small", SHIPPED_STAKES[0]);
        wrote |= seed(section, "stake-medium", SHIPPED_STAKES[1]);
        wrote |= seed(section, "stake-large", SHIPPED_STAKES[2]);
        wrote |= seed(section, "payout-colour", 2.0);
        wrote |= seed(section, "payout-zero", 35.0);
        return wrote;
    }

    /** The three stakes, biggest last, so the grid reads top to bottom. */
    private double[] stakes() {
        return new double[]{
                Math.max(1, setting("stake-small", SHIPPED_STAKES[0])),
                Math.max(1, setting("stake-medium", SHIPPED_STAKES[1])),
                Math.max(1, setting("stake-large", SHIPPED_STAKES[2]))
        };
    }

    /** What a winning bet on a colour is paid, stake included. */
    private double multiplier(String colour) {
        return GREEN.equals(colour)
                ? Math.max(1, setting("payout-zero", 35.0))
                : Math.max(1, setting("payout-colour", 2.0));
    }

    /** The colour a number pays: zero is the house's, everything else is red or black. */
    static String colourOf(int number) {
        if (number == 0) {
            return GREEN;
        }
        return RED_NUMBERS.contains(number) ? RED : BLACK;
    }

    private static String colourName(String colour) {
        return GREEN.equals(colour) ? "zero" : colour;
    }

    // ── The round ───────────────────────────────────────────────────────

    @Override
    public void draw(CasinoTable table) {
        int number = ThreadLocalRandom.current().nextInt(37);
        table.publish(number, "The wheel stopped on " + number + " " + colourName(colourOf(number)));
    }

    @Override
    public void settle(CasinoTable table) {
        if (!(table.outcome() instanceof Integer number)) {
            table.refundAll();
            return;
        }
        String winning = colourOf(number);
        for (UUID who : table.players()) {
            double staked = table.stakeOf(who);
            double won = 0;
            for (Bet bet : table.betsOf(who)) {
                if (bet.choice().equals(winning)) {
                    won += bet.amount() * multiplier(winning);
                }
            }
            Player player = CasinoTable.online(who);
            if (won > 0) {
                table.pay(who, won);
                if (player != null) {
                    casino().win(player, ID);
                    CasinoFeedback.win(player);
                    casino().notice(player, "roulette-won",
                            "&aThe wheel paid &e%payout% coins&a on your %colour% stake.",
                            "%payout%", CasinoWager.money(won),
                            "%colour%", colourName(winning),
                            "%staked%", CasinoWager.money(staked));
                }
            } else {
                if (player != null) {
                    casino().lose(player, ID);
                    CasinoFeedback.lose(player);
                    casino().notice(player, "roulette-lost",
                            "&cThe wheel took &e%staked% coins&c: it stopped on %number% %colour%.",
                            "%staked%", CasinoWager.money(staked),
                            "%number%", String.valueOf(number),
                            "%colour%", colourName(winning));
                }
            }
        }
    }

    // ── The screen ──────────────────────────────────────────────────────

    @Override
    protected CasinoScreenBase newScreen(Player player) {
        return new Screen(this, player);
    }

    private static final class Screen extends TableScreen {

        private final RouletteGame game;

        Screen(RouletteGame game, Player viewer) {
            super(game.plugin(), game.casino(), game.table(), ID, "Roulette",
                    CasinoText.legacy("&6Roulette"), viewer);
            this.game = game;
        }

        @Override
        protected void drawBets(Player player) {
            double[] stakes = game.stakes();
            double balance = casino().wager().balance(player);
            for (int row = 0; row < BET_SLOTS.length; row++) {
                for (int column = 0; column < COLOURS.length; column++) {
                    double stake = stakes[row];
                    String colour = COLOURS[column];
                    List<String> lore = new ArrayList<>();
                    lore.add("Stake " + CasinoWager.money(stake) + " coins on " + COLOUR_NAMES[column]);
                    lore.add("A win pays " + CasinoWager.money(stake * game.multiplier(colour)) + " coins");
                    lore.add(balance >= stake
                            ? "Click to put it on the table"
                            : "You cannot cover this stake");
                    inventory().setItem(BET_SLOTS[row][column],
                            button(ICONS[column],
                                    colourName(colour).toUpperCase(java.util.Locale.ROOT)
                                            + " &7\u00B7 &e" + CasinoWager.money(stake),
                                    lore.toArray(String[]::new)));
                }
            }
            inventory().setItem(WHEEL_SLOT, wheelItem());
        }

        /** The wheel, spinning while it spins and still once it has stopped. */
        @Override
        protected void drawDrawing(Player player) {
            inventory().setItem(WHEEL_SLOT, spinningWheel());
        }

        @Override
        protected void drawResult(Player player) {
            inventory().setItem(WHEEL_SLOT, wheelItem());
            List<String> lore = new ArrayList<>();
            lore.add(table().outcomeText());
            lore.add("");
            lore.add("On the table: " + CasinoWager.money(table().pot()) + " coins");
            double staked = table().stakeOf(viewerId());
            if (staked > 0) {
                boolean won = false;
                for (Bet bet : table().betsOf(viewerId())) {
                    if (winner() >= 0 && bet.choice().equals(colourOf(winner()))) {
                        won = true;
                    }
                }
                lore.add(won
                        ? "Your " + CasinoWager.money(staked) + " came back a winner"
                        : "You lost " + CasinoWager.money(staked) + " this round");
            }
            inventory().setItem(31, MenuStyle.header("The wheel", NamedTextColor.AQUA, lore));
        }

        @Override
        protected void clickBet(Player player, int slot, InventoryClickEvent event) {
            for (int row = 0; row < BET_SLOTS.length; row++) {
                for (int column = 0; column < COLOURS.length; column++) {
                    if (BET_SLOTS[row][column] != slot) {
                        continue;
                    }
                    double stake = game.stakes()[row];
                    String colour = COLOURS[column];
                    if (!table().bet(player, stake, colour)) {
                        return;
                    }
                    CasinoFeedback.tick(player, 2);
                    casino().notice(player, "roulette-bet",
                            "&eStaked &6%stake% coins&e on %colour%. &7The table holds %pot%.",
                            "%stake%", CasinoWager.money(stake),
                            "%colour%", colourName(colour),
                            "%pot%", CasinoWager.money(table().pot()));
                    return;
                }
            }
        }

        private int winner() {
            return table().outcome() instanceof Integer number ? number : -1;
        }

        private ItemStack wheelItem() {
            return winner() < 0 ? wheelIcon() : colourItem(winner());
        }

        private ItemStack wheelIcon() {
            return item(Material.COMPASS, CasinoText.legacy("&6The wheel", NamedTextColor.GOLD),
                    List.of(grey("Bets close when the countdown runs out"),
                            grey("Red or black pays 2x, zero pays 35x")));
        }

        /** The stopped wheel: the colour that came up, with the number on it. */
        private ItemStack colourItem(int number) {
            String colour = colourOf(number);
            Material icon = switch (colour) {
                case RED -> Material.RED_CONCRETE;
                case BLACK -> Material.BLACK_CONCRETE;
                default -> Material.LIME_CONCRETE;
            };
            return item(icon, CasinoText.legacy("&6" + number + " " + colourName(colour),
                            NamedTextColor.GOLD),
                    List.of(grey("The house is paying " + colourName(colour))));
        }

        /** A wheel that has not decided yet: the number flickers while it slows. */
        private ItemStack spinningWheel() {
            int flicker = ThreadLocalRandom.current().nextInt(37);
            return item(colourOf(flicker) == RED ? Material.RED_CONCRETE : Material.BLACK_CONCRETE,
                    CasinoText.legacy("&7" + flicker + " \\u2026", NamedTextColor.GRAY),
                    List.of(grey("No more bets")));
        }

    }
}
