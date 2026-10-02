package me.foivos.planets.casino.games;

import me.foivos.planets.MenuStyle;
import me.foivos.planets.casino.CasinoFeedback;
import me.foivos.planets.casino.CasinoManager;
import me.foivos.planets.casino.CasinoScreenBase;
import me.foivos.planets.casino.CasinoTable;
import me.foivos.planets.casino.CasinoTable.Bet;
import me.foivos.planets.casino.CasinoText;
import me.foivos.planets.casino.CasinoWager;
import me.foivos.planets.casino.TableGame;
import me.foivos.planets.casino.TableScreen;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The derby: four runners, one race, and the pot split between whoever backed
 * the winner.
 *
 * <p>This is the casino's betting game in the purest form — nobody plays
 * against the house at all. Players put money on one of four runners, each
 * click backing a runner for a fixed stake, and when the race is run the pot
 * goes to the winning backers in proportion to what they staked on the winner.
 * Back ten times what somebody else did and you take ten times as much of it,
 * so the house takes no cut and mints no money: everything staked is paid back
 * out to the players who read the race right.
 *
 * <p>Only the winning backers see a return, so the odds are not printed
 * anywhere — they are whatever the other players' money on the table makes
 * them, which is what a real pari-mutuel tote is. A race nobody backed the
 * winner of returns every stake rather than quietly keeping it.
 */
public final class DerbyGame extends TableGame {

    /** The id this game is known by in config.yml and in every statistic. */
    static final String ID = "derby";

    /** How many runners the field holds: the layout has room for four. */
    private static final int RUNNERS = 4;

    /**
     * The betting grid: one column per runner, one row per stake. A whole bet is
     * one click, so nobody has to pick a runner and then an amount.
     */
    private static final int[][] BET_SLOTS = {
            {19, 21, 23, 25},
            {28, 30, 32, 34},
            {37, 39, 41, 43}
    };

    private static final Material[] RUNNER_ICONS = {
            Material.SADDLE, Material.HAY_BLOCK, Material.LEATHER, Material.IRON_HORSE_ARMOR
    };

    private static final List<String> SHIPPED_RUNNERS =
            List.of("Bolt", "Dusty", "Comet", "Thunder");

    private static final double[] SHIPPED_STAKES = {50, 250, 1000};

    /** The strides a runner may take to finish: the field's spread. */
    private static final int SHORTEST_RUN = 8;
    private static final int LONGEST_RUN = 14;

    /** The last race's finishing strides, by runner, for the animation and the result. */
    private int[] lastRace = new int[RUNNERS];

    public DerbyGame(CasinoManager casino) {
        super(casino);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "&bDerby";
    }

    @Override
    public Material icon() {
        return Material.SADDLE;
    }

    @Override
    public List<String> description() {
        return List.of(
                "Four runners, one race",
                "&eBack a runner&7 with coins.",
                "The winning backers split the pot."
        );
    }

    @Override
    protected int defaultBettingSeconds() {
        return 25;
    }

    @Override
    protected int defaultDrawingSeconds() {
        return 6;
    }

    @Override
    protected int defaultResultSeconds() {
        return 9;
    }

    @Override
    protected boolean seedSettings(ConfigurationSection section) {
        boolean wrote = false;
        wrote |= seed(section, "runner-names", SHIPPED_RUNNERS);
        wrote |= seed(section, "stake-small", SHIPPED_STAKES[0]);
        wrote |= seed(section, "stake-medium", SHIPPED_STAKES[1]);
        wrote |= seed(section, "stake-large", SHIPPED_STAKES[2]);
        return wrote;
    }

    /** The field, as config names it. */
    List<String> runners() {
        ConfigurationSection section = casino().gameSection(id());
        List<String> names = section == null ? List.of() : section.getStringList("runner-names");
        if (names.isEmpty()) {
            return SHIPPED_RUNNERS;
        }
        return names.size() > RUNNERS ? names.subList(0, RUNNERS) : names;
    }

    private int runnerCount() {
        return Math.min(RUNNERS, runners().size());
    }

    /** The stakes a backer can choose from, small to large. */
    double[] stakes() {
        return new double[]{
                Math.max(1, setting("stake-small", SHIPPED_STAKES[0])),
                Math.max(1, setting("stake-medium", SHIPPED_STAKES[1])),
                Math.max(1, setting("stake-large", SHIPPED_STAKES[2]))
        };
    }

    /** What one player has riding on a runner, in coins. */
    double stakeOn(UUID who, int runner) {
        double total = 0;
        for (Bet bet : table().betsOf(who)) {
            if (bet.choice().equals(String.valueOf(runner))) {
                total += bet.amount();
            }
        }
        return total;
    }

    /** Everything the table has staked on one runner. */
    double moneyOn(int runner) {
        double total = 0;
        for (UUID who : table().players()) {
            total += stakeOn(who, runner);
        }
        return total;
    }

    // ── The round ───────────────────────────────────────────────────────

    @Override
    public void draw(CasinoTable table) {
        int field = Math.max(1, runnerCount());
        int[] strides = new int[RUNNERS];
        int winner = 0;
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < field; i++) {
            strides[i] = ThreadLocalRandom.current().nextInt(SHORTEST_RUN, LONGEST_RUN + 1);
            if (strides[i] < best) {
                best = strides[i];
                winner = i;
            }
        }
        lastRace = strides;
        table.publish(winner, runners().get(winner) + " wins in " + best + " strides");
    }

    @Override
    public void settle(CasinoTable table) {
        if (!(table.outcome() instanceof Integer winner)) {
            table.refundAll();
            return;
        }
        double onWinner = moneyOn(winner);
        if (onWinner <= 0) {
            // Nobody read the race right, so there is nobody to pay. Returning the
            // stakes is the honest ending: the house is not a bookmaker here.
            table.refundAll();
            for (UUID who : table.players()) {
                Player player = CasinoTable.online(who);
                if (player != null) {
                    casino().draw(player, ID);
                    casino().notice(player, "derby-unbacked",
                            "&eNobody backed &6%runner%&e, so every stake comes back.",
                            "%runner%", runners().get(winner));
                }
            }
            return;
        }
        double pot = table.pot();
        for (UUID who : table.players()) {
            double back = stakeOn(who, winner);
            Player player = CasinoTable.online(who);
            if (back <= 0) {
                if (player != null) {
                    casino().lose(player, ID);
                    CasinoFeedback.lose(player);
                    casino().notice(player, "derby-lost",
                            "&c%runner% took the race. Your &e%staked% coins&c stay in the pot.",
                            "%runner%", runners().get(winner),
                            "%staked%", CasinoWager.money(table.stakeOf(who)));
                }
                continue;
            }
            // The whole pot, shared out in proportion to what was backed on the winner.
            double payout = round(pot * (back / onWinner));
            table.pay(who, payout);
            if (player != null) {
                casino().win(player, ID);
                CasinoFeedback.win(player);
                casino().notice(player, "derby-won",
                        "&a%runner% won. Your &e%stake% coins&a on them came back as &6%payout% coins&a.",
                        "%runner%", runners().get(winner),
                        "%stake%", CasinoWager.money(back),
                        "%payout%", CasinoWager.money(payout));
            }
        }
    }

    private static double round(double amount) {
        return Math.round(amount * 100.0) / 100.0;
    }

    /** How many strides a runner took last race, for the animation. */
    int stridesOf(int runner) {
        if (runner < 0 || runner >= lastRace.length || lastRace[runner] <= 0) {
            return LONGEST_RUN;
        }
        return lastRace[runner];
    }

    // ── The screen ──────────────────────────────────────────────────────

    @Override
    protected CasinoScreenBase newScreen(Player player) {
        return new Screen(this, player);
    }

    private static final class Screen extends TableScreen {

        private final DerbyGame game;

        Screen(DerbyGame game, Player viewer) {
            super(game.plugin(), game.casino(), game.table(), ID, "Derby",
                    CasinoText.legacy("&6The Derby"), viewer);
            this.game = game;
        }

        @Override
        protected void drawBets(Player player) {
            double[] stakes = game.stakes();
            double balance = casino().wager().balance(player);
            List<String> runners = game.runners();
            for (int row = 0; row < BET_SLOTS.length; row++) {
                for (int column = 0; column < game.runnerCount(); column++) {
                    double stake = stakes[row];
                    String runner = runners.get(column);
                    List<String> lore = new ArrayList<>();
                    lore.add("Back " + runner + " for " + CasinoWager.money(stake) + " coins");
                    lore.add("");
                    lore.add("On " + runner + ": " + CasinoWager.money(game.moneyOn(column)) + " coins");
                    lore.add("Your stake: " + CasinoWager.money(game.stakeOn(viewerId(), column)));
                    lore.add(balance >= stake
                            ? "Click to back them"
                            : "You cannot cover this stake");
                    inventory().setItem(BET_SLOTS[row][column],
                            button(RUNNER_ICONS[column % RUNNER_ICONS.length],
                                    runner.toUpperCase(Locale.ROOT) + " &7\u00B7 &e"
                                            + CasinoWager.money(stake),
                                    lore.toArray(String[]::new)));
                }
            }
            inventory().setItem(13, MenuStyle.header("The race",
                    NamedTextColor.GOLD,
                    List.of("Four runners, one winner",
                            "The pot is " + CasinoWager.money(table().pot()) + " coins",
                            "Winning backers split it in proportion")));
        }

        @Override
        protected void drawDrawing(Player player) {
            // The runner that wins is already decided; the race only shows it
            // running, so the picture can never contradict the payout.
            int frames = Math.max(1, game.drawingSeconds());
            int elapsed = Math.min(frames, Math.max(0, frames - table().secondsLeft()));
            int winner = table().outcome() instanceof Integer value ? value : -1;
            for (int runner = 0; runner < game.runnerCount(); runner++) {
                int strides = game.stridesOf(runner);
                int taken = (int) Math.ceil(strides * (elapsed / (double) frames));
                inventory().setItem(BET_SLOTS[0][runner], runnerItem(runner, taken, strides,
                        runner == winner && elapsed >= frames));
            }
        }

        @Override
        protected void drawResult(Player player) {
            int winner = table().outcome() instanceof Integer value ? value : -1;
            List<Integer> order = new ArrayList<>();
            for (int runner = 0; runner < game.runnerCount(); runner++) {
                order.add(runner);
            }
            order.sort((left, right) -> Integer.compare(game.stridesOf(left), game.stridesOf(right)));
            for (int place = 0; place < order.size(); place++) {
                int runner = order.get(place);
                inventory().setItem(BET_SLOTS[0][place],
                        runnerItem(runner, game.stridesOf(runner), game.stridesOf(runner),
                                runner == winner));
            }
            List<String> lore = new ArrayList<>();
            lore.add(table().outcomeText());
            lore.add("");
            lore.add("Pot paid out: " + CasinoWager.money(table().pot()) + " coins");
            double staked = table().stakeOf(viewerId());
            if (staked > 0 && winner >= 0) {
                double back = game.stakeOn(viewerId(), winner);
                lore.add(back > 0
                        ? "Your " + CasinoWager.money(back) + " on the winner was paid"
                        : "You lost " + CasinoWager.money(staked) + " this race");
            }
            inventory().setItem(31, MenuStyle.header("The result", NamedTextColor.AQUA, lore));
        }

        @Override
        protected void clickBet(Player player, int slot, InventoryClickEvent event) {
            for (int row = 0; row < BET_SLOTS.length; row++) {
                for (int column = 0; column < game.runnerCount(); column++) {
                    if (BET_SLOTS[row][column] != slot) {
                        continue;
                    }
                    double stake = game.stakes()[row];
                    if (!table().bet(player, stake, String.valueOf(column))) {
                        return;
                    }
                    CasinoFeedback.tick(player, 2);
                    casino().notice(player, "derby-bet",
                            "&eYou backed &6%runner%&e for &6%stake% coins&e. "
                                    + "The pot holds &6%pot%&e.",
                            "%runner%", game.runners().get(column),
                            "%stake%", CasinoWager.money(stake),
                            "%pot%", CasinoWager.money(table().pot()));
                    return;
                }
            }
        }

        /** One runner as it is going or as it finished. */
        private ItemStack runnerItem(int runner, int taken, int strides, boolean won) {
            int blocks = 10;
            int filled = strides <= 0 ? blocks
                    : Math.max(0, Math.min(blocks, (int) Math.round(blocks * (taken / (double) strides))));
            String bar = "\u2588".repeat(filled) + "\u2591".repeat(blocks - filled);
            String name = game.runners().get(runner);
            List<Component> lore = new ArrayList<>();
            lore.add(grey(bar));
            lore.add(grey(won ? "First past the post" : "Still running"));
            return item(RUNNER_ICONS[runner % RUNNER_ICONS.length],
                    CasinoText.legacy((won ? "&6" : "&7") + name, NamedTextColor.GOLD), lore);
        }
    }
}
