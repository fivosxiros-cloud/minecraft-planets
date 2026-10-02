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
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The lottery: everybody's tickets in one drum, one winner takes the pot.
 *
 * <p>Of the casino's tables this is the one that gets better the more people are
 * on it, which is the point of it. Tickets are sold for the length of the
 * betting window at a fixed price; every ticket anyone buys goes into a single
 * pot, one ticket is drawn, and its holder takes everything that was staked —
 * including their own ticket back. A round with three players holds three
 * times the money of a round with one, and the odds are simply how many of the
 * tickets in the drum are yours.
 *
 * <p>Nothing is minted and nothing is burned. The pot is exactly the money the
 * players put in, and it leaves as one payment, so the house neither wins nor
 * loses on a draw and a lone player drawing their own ticket is made whole.
 */
public final class LotteryGame extends TableGame {

    /** The id this game is known by in config.yml and in every statistic. */
    static final String ID = "lottery";

    /** What a player is holding when they click: one ticket. */
    private static final String TICKET = "ticket";

    private static final int DRUM_SLOT = 13;
    private static final int BUY_ONE = 20;
    private static final int BUY_FIVE = 22;
    private static final int BUY_TEN = 24;
    private static final int HOLDERS_SLOT = 31;

    private static final double SHIPPED_TICKET_PRICE = 250.0;
    private static final int SHIPPED_MAX_TICKETS = 10;

    public LotteryGame(CasinoManager casino) {
        super(casino);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "&bLottery";
    }

    @Override
    public Material icon() {
        return Material.PAPER;
    }

    @Override
    public List<String> description() {
        return List.of(
                "One pot, one winning ticket",
                "&eBuy tickets&7 while the drum is open.",
                "More players, bigger pot."
        );
    }

    @Override
    protected int defaultBettingSeconds() {
        return 30;
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
        wrote |= seed(section, "ticket-price", SHIPPED_TICKET_PRICE);
        wrote |= seed(section, "max-tickets", SHIPPED_MAX_TICKETS);
        return wrote;
    }

    /** What one ticket costs. */
    double ticketPrice() {
        return Math.max(1, setting("ticket-price", SHIPPED_TICKET_PRICE));
    }

    /** How much one player may hold in a single round, so one purse cannot own the drum. */
    int maxTickets() {
        return Math.max(1, setting("max-tickets", SHIPPED_MAX_TICKETS));
    }

    /** How many tickets one player is holding this round. */
    int ticketsOf(UUID who) {
        int count = 0;
        for (Bet bet : table().betsOf(who)) {
            if (TICKET.equals(bet.choice())) {
                count++;
            }
        }
        return count;
    }

    /** Tickets in the drum this round, whoever holds them. */
    int ticketsInPlay() {
        int count = 0;
        for (UUID who : table().players()) {
            count += ticketsOf(who);
        }
        return count;
    }

    /**
     * Sells tickets to one player, up to what they asked for, what they can
     * cover and what the round allows them to hold.
     *
     * @return how many were actually bought
     */
    int buy(Player player, int wanted) {
        int room = Math.min(wanted, maxTickets() - ticketsOf(player.getUniqueId()));
        if (room <= 0) {
            CasinoFeedback.deny(player);
            casino().notice(player, "lottery-limit",
                    "&eYou already hold the most tickets one player may have this round.");
            return 0;
        }
        double price = ticketPrice();
        int bought = 0;
        for (int i = 0; i < room; i++) {
            if (!table().bet(player, price, TICKET)) {
                break;
            }
            bought++;
        }
        if (bought == 0) {
            return 0;
        }
        CasinoFeedback.tick(player, bought >= 5 ? 3 : 2);
        casino().notice(player, "lottery-bought",
                "&aYou bought &e%bought% ticket(s) &afor &6%spent% coins&a. "
                        + "The pot is now &6%pot% coins&a.",
                "%bought%", String.valueOf(bought),
                "%spent%", CasinoWager.money(price * bought),
                "%pot%", CasinoWager.money(table().pot()));
        return bought;
    }

    // ── The round ───────────────────────────────────────────────────────

    @Override
    public void draw(CasinoTable table) {
        List<UUID> drum = new ArrayList<>();
        for (UUID who : table.players()) {
            for (Bet bet : table.betsOf(who)) {
                if (TICKET.equals(bet.choice())) {
                    drum.add(who);
                }
            }
        }
        if (drum.isEmpty()) {
            table.publish(null, "No tickets were sold this round");
            return;
        }
        UUID winner = drum.get(ThreadLocalRandom.current().nextInt(drum.size()));
        String name = name(winner);
        table.publish(winner, "The drum drew " + name + "'s ticket, out of " + drum.size());
    }

    @Override
    public void settle(CasinoTable table) {
        if (!(table.outcome() instanceof UUID winner)) {
            table.refundAll();
            return;
        }
        double pot = table.pot();
        table.pay(winner, pot);
        for (UUID who : table.players()) {
            Player player = CasinoTable.online(who);
            if (player == null) {
                continue;
            }
            if (who.equals(winner)) {
                casino().win(player, ID);
                CasinoFeedback.win(player);
                casino().notice(player, "lottery-won",
                        "&6&lYou won the lottery! &aYour ticket took the whole pot of "
                                + "&e%pot% coins&a.",
                        "%pot%", CasinoWager.money(pot));
            } else {
                casino().lose(player, ID);
                CasinoFeedback.lose(player);
                casino().notice(player, "lottery-lost",
                        "&cThe drum drew &e%winner%&c's ticket. Your &e%spent% coins&c stayed in the pot.",
                        "%winner%", name(winner),
                        "%spent%", CasinoWager.money(table.stakeOf(who)));
            }
        }
    }

    private static String name(UUID who) {
        String name = CasinoTable.off(who).getName();
        return name == null || name.isBlank() ? "a player" : name;
    }

    // ── The screen ──────────────────────────────────────────────────────

    @Override
    protected CasinoScreenBase newScreen(Player player) {
        return new Screen(this, player);
    }

    private static final class Screen extends TableScreen {

        private final LotteryGame game;

        Screen(LotteryGame game, Player viewer) {
            super(game.plugin(), game.casino(), game.table(), ID, "Lottery",
                    CasinoText.legacy("&6Lottery"), viewer);
            this.game = game;
        }

        @Override
        protected void drawBets(Player player) {
            double price = game.ticketPrice();
            int owned = game.ticketsOf(viewerId());
            int room = Math.max(0, game.maxTickets() - owned);
            inventory().setItem(DRUM_SLOT, drumItem("The drum \u00B7 " + game.ticketsInPlay()
                    + " ticket(s)"));
            inventory().setItem(BUY_ONE, buyButton(1, price, room, owned));
            inventory().setItem(BUY_FIVE, buyButton(5, price, room, owned));
            inventory().setItem(BUY_TEN, buyButton(10, price, room, owned));
            inventory().setItem(HOLDERS_SLOT, holdersItem());
        }

        @Override
        protected void drawDrawing(Player player) {
            int flicker = ThreadLocalRandom.current().nextInt(Math.max(1, game.ticketsInPlay()));
            inventory().setItem(DRUM_SLOT, item(Material.NOTE_BLOCK,
                    CasinoText.legacy("&7Drawing \u2026", NamedTextColor.GRAY),
                    List.of(grey("Tickets are being drawn"),
                            grey("ticket " + (flicker + 1) + " of " + game.ticketsInPlay()))));
        }

        @Override
        protected void drawResult(Player player) {
            inventory().setItem(DRUM_SLOT, drumItem("The draw"));
            List<String> lore = new ArrayList<>();
            lore.add(table().outcomeText());
            lore.add("");
            lore.add("Pot paid out: " + CasinoWager.money(table().pot()) + " coins");
            double staked = table().stakeOf(viewerId());
            if (staked > 0) {
                boolean won = table().outcome() instanceof UUID winner && winner.equals(viewerId());
                lore.add(won
                        ? "You took the pot with your ticket"
                        : "Your " + CasinoWager.money(staked) + " stayed in the pot");
            }
            inventory().setItem(HOLDERS_SLOT, MenuStyle.header("The draw", NamedTextColor.AQUA, lore));
        }

        @Override
        protected void clickBet(Player player, int slot, InventoryClickEvent event) {
            int wanted = switch (slot) {
                case BUY_ONE -> 1;
                case BUY_FIVE -> 5;
                case BUY_TEN -> 10;
                default -> 0;
            };
            if (wanted > 0) {
                game.buy(player, wanted);
            }
        }

        /** One of the three ways to buy. */
        private ItemStack buyButton(int wanted, double price, int room, int owned) {
            int available = Math.min(wanted, room);
            List<String> lore = new ArrayList<>();
            lore.add(available > 0
                    ? "Buy " + available + " ticket(s) for " + CasinoWager.money(price * available) + " coins"
                    : "You cannot buy that many right now");
            lore.add("A ticket is " + CasinoWager.money(price) + " coins");
            lore.add("You are holding " + owned + " of " + game.maxTickets());
            return button(Material.PAPER, "&eBuy " + wanted, lore.toArray(String[]::new));
        }

        /** The drum, named without colour codes: MenuStyle draws them literally. */
        private ItemStack drumItem(String name) {
            List<String> lore = new ArrayList<>();
            lore.add("Tickets in play: " + game.ticketsInPlay());
            lore.add("Pot: " + CasinoWager.money(table().pot()) + " coins");
            lore.add("You hold: " + game.ticketsOf(viewerId()));
            return MenuStyle.header(name, NamedTextColor.GOLD, lore);
        }

        /** Who is holding what, so the drum reads as a shared pot. */
        private ItemStack holdersItem() {
            Map<String, Integer> holders = new LinkedHashMap<>();
            for (UUID who : table().players()) {
                int held = game.ticketsOf(who);
                if (held > 0) {
                    holders.put(name(who), held);
                }
            }
            List<String> lore = new ArrayList<>();
            if (holders.isEmpty()) {
                lore.add("Nobody has a ticket yet");
            } else {
                for (Map.Entry<String, Integer> holder : holders.entrySet()) {
                    lore.add(holder.getKey() + ": " + holder.getValue() + " ticket(s)");
                }
            }
            return MenuStyle.header("Tickets in play", NamedTextColor.AQUA, lore);
        }

        private static String name(UUID who) {
            String name = CasinoTable.off(who).getName();
            return name == null || name.isBlank() ? "a player" : name;
        }
    }
}
