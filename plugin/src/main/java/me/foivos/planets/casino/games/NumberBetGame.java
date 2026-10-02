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
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Guess the number, for money.
 *
 * <p>The casino's simplest wager: pick a stake, pick a number between one and
 * ten, and the house rolls one. A correct guess pays {@link #PAYOUT} times the
 * stake; anything else loses it. Unlike the rest of the casino this game pays
 * in coins, so the player's balance — not a cosmetic — is what is on the table,
 * and the stake is only ever taken once, on the click that rolls.
 */
public final class NumberBetGame implements CasinoGame {

    /** The id this game is known by in config.yml and in every statistic. */
    static final String ID = "numberbet";

    /** The numbers that can be guessed. */
    private static final int SIDES = 10;

    /** What a correct guess pays. Deliberately below {@link #SIDES}, so the game still favours the house. */
    private static final int PAYOUT = 8;

    private final CasinoManager casino;

    public NumberBetGame(CasinoManager casino) {
        this.casino = casino;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "&bGuess the Number";
    }

    @Override
    public Material icon() {
        return Material.ENCHANTING_TABLE;
    }

    @Override
    public List<String> description() {
        return List.of(
                "Bet against the house",
                "&eStake coins&7 and guess 1\u201310.",
                "Right guess pays " + PAYOUT + "x."
        );
    }

    @Override
    public void open(Player player) {
        new Screen(casino, player).open(player);
    }

    // ── The screen ──────────────────────────────────────────────────────

    private static final class Screen extends CasinoScreenBase {

        /** The ten number buttons, two slots apart across the middle rows. */
        private static final int FIRST_NUMBER = 10;
        /** The four stake buttons, in the row beneath them. */
        private static final int[] STAKE_SLOTS = {30, 32, 34, 36};
        private static final double[] STAKE_AMOUNTS = {1, 5, 10, 25};

        private static final int AGAIN_SLOT = 40;
        private static final int BACK_SLOT = 45;
        private static final int RECORD_SLOT = 49;
        private static final int CLOSE_SLOT = 53;

        /** The stake the player has chosen, or 0 before they choose one. */
        private double stake = 0;
        /** The number that was rolled, or 0 before the first round. */
        private int rolled = 0;
        /** -1 tie can't happen here, so this is just won / lost once a round has run. */
        private boolean roundOver = false;
        private boolean won = false;

        Screen(CasinoManager casino, Player viewer) {
            super(casino.plugin(), casino, viewer, 54,
                    CasinoText.legacy("&8\uD83C\uDFB2 Guess the Number", NamedTextColor.DARK_AQUA));
        }

        @Override
        protected void render() {
            Player player = viewer();
            if (player == null) {
                return;
            }
            inventory().clear();
            MenuStyle.decorate(inventory(), "\uD83C\uDFB2 Guess the Number", "\uD83D\uDCB0 Your stake");

            inventory().setItem(4, MenuStyle.header("Guess the Number", NamedTextColor.GOLD,
                    List.of("Guess a number from 1 to " + SIDES,
                            "A right guess pays " + PAYOUT + " times your stake",
                            "",
                            "Stake: " + (stake > 0 ? CasinoWager.money(stake) + " coins" : "not chosen yet"),
                            "Balance: " + CasinoWager.money(casino().wager().balance(player)))));

            for (int i = 1; i <= SIDES; i++) {
                inventory().setItem(FIRST_NUMBER + (i - 1) * 2, numberButton(i));
            }
            for (int i = 0; i < STAKE_SLOTS.length; i++) {
                inventory().setItem(STAKE_SLOTS[i], stakeButton(player, STAKE_AMOUNTS[i]));
            }
            if (roundOver) {
                inventory().setItem(AGAIN_SLOT, button(Material.EMERALD,
                        "\u21BB Play again",
                        "Clear the roll and pick a new number",
                        "Same stake: " + CasinoWager.money(stake) + " coins"));
            }

            inventory().setItem(BACK_SLOT, backButton("Casino"));
            inventory().setItem(RECORD_SLOT, recordItem());
            inventory().setItem(CLOSE_SLOT, closeButton());
        }

        private ItemStack numberButton(int number) {
            List<Component> lore = new ArrayList<>();
            if (roundOver) {
                boolean wasRolled = number == rolled;
                lore.add(grey(wasRolled ? "The house rolled this" : "Not the roll"));
            } else if (stake <= 0) {
                lore.add(grey("Choose a stake first"));
            } else {
                lore.add(grey("Risk " + CasinoWager.money(stake) + " coins"));
                lore.add(grey("Win " + CasinoWager.money(stake * PAYOUT) + " coins"));
            }
            boolean highlight = roundOver && number == rolled;
            return item(highlight ? Material.LIME_DYE : Material.PAPER,
                    plain("\uD83C\uDFB2 " + number)
                            .color(highlight ? NamedTextColor.GREEN
                                    : roundOver ? NamedTextColor.GRAY : NamedTextColor.WHITE),
                    lore);
        }

        private ItemStack stakeButton(Player player, double amount) {
            boolean affordable = casino().wager().canAfford(player, amount);
            boolean selected = stake == amount && !roundOver;
            List<Component> lore = new ArrayList<>();
            lore.add(grey(affordable ? "Click to choose this stake"
                    : "Not enough coins for this stake"));
            lore.add(grey("Pays " + CasinoWager.money(amount * PAYOUT) + " coins on a hit"));
            return item(!affordable ? Material.GRAY_DYE : selected ? Material.GOLD_BLOCK : Material.GOLD_INGOT,
                    plain("\uD83D\uDCB0 " + CasinoWager.money(amount) + " coin"
                            + (amount > 1 ? "s" : ""))
                            .color(!affordable ? NamedTextColor.DARK_GRAY
                                    : selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW),
                    lore);
        }

        private ItemStack recordItem() {
            CasinoStats stats = casino().stats(viewerId());
            int plays = stats.plays(ID);
            List<Component> lore = new ArrayList<>();
            lore.add(grey("Rounds: " + plays));
            lore.add(grey("Won: " + stats.wins()));
            lore.add(grey("Win rate: " + (plays > 0
                    ? String.format("%.1f%%", stats.wins() * 100.0 / plays) : "0%")));
            return item(Material.PAPER, plain("\uD83D\uDCCA Your rounds").color(NamedTextColor.AQUA), lore);
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
                case BACK_SLOT -> {
                    CasinoFeedback.back(player);
                    player.closeInventory();
                    casino().openHub(player);
                }
                case RECORD_SLOT -> {
                    // The read-out is just a read-out.
                }
                case AGAIN_SLOT -> {
                    if (roundOver) {
                        roundOver = false;
                        rolled = 0;
                        CasinoFeedback.click(player);
                        render();
                    }
                }
                default -> {
                    int stakeIndex = indexOf(STAKE_SLOTS, slot);
                    if (stakeIndex >= 0) {
                        chooseStake(player, STAKE_AMOUNTS[stakeIndex]);
                        return;
                    }
                    int number = numberAt(slot);
                    if (number > 0) {
                        guess(player, number);
                    }
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

        /** The number drawn at a slot, or 0 when that slot is not a number button. */
        private static int numberAt(int slot) {
            if (slot < FIRST_NUMBER || (slot - FIRST_NUMBER) % 2 != 0) {
                return 0;
            }
            int number = (slot - FIRST_NUMBER) / 2 + 1;
            return number <= SIDES ? number : 0;
        }

        // ── The game ────────────────────────────────────────────────────

        private void chooseStake(Player player, double amount) {
            if (roundOver) {
                return;
            }
            if (!casino().wager().available()) {
                casino().notice(player, "no-economy",
                        "&cThere is no economy on this server to bet with.");
                return;
            }
            if (!casino().wager().canAfford(player, amount)) {
                casino().notice(player, "not-enough",
                        "&cYou need &e%amount%&c coins for that stake.",
                        "%amount%", CasinoWager.money(amount));
                return;
            }
            stake = amount;
            CasinoFeedback.click(player);
            casino().notice(player, "stake-set",
                    "&aStake set to &e%amount%&a coins. Now pick a number.",
                    "%amount%", CasinoWager.money(amount));
            render();
        }

        private void guess(Player player, int number) {
            if (roundOver) {
                return;
            }
            if (stake <= 0) {
                casino().notice(player, "no-stake",
                        "&cChoose a stake before picking a number.");
                return;
            }
            if (!casino().begin(player, ID)) {
                return;
            }
            if (!casino().wager().take(player, stake)) {
                casino().notice(player, "withdraw-failed",
                        "&cYour stake could not be taken \u2014 nothing was bet.");
                return;
            }

            rolled = ThreadLocalRandom.current().nextInt(1, SIDES + 1);
            won = rolled == number;
            roundOver = true;

            if (won) {
                double payout = stake * PAYOUT;
                casino().win(player, ID);
                CasinoFeedback.win(player);
                if (!casino().wager().give(player, payout)) {
                    // The stake is already gone, so this is the one case worth
                    // telling staff about rather than shrugging off.
                    casino().notice(player, "payout-failed",
                            "&cYour win of &e%amount%&c could not be paid \u2014 tell staff.",
                            "%amount%", CasinoWager.money(payout));
                } else {
                    casino().notice(player, "jackpot",
                            "&a&lIt was &e%number%&a&l! You won &e%amount%&a&l!",
                            "%number%", String.valueOf(rolled),
                            "%amount%", CasinoWager.money(payout));
                }
            } else {
                casino().lose(player, ID);
                CasinoFeedback.lose(player);
                casino().notice(player, "no-luck",
                        "&cThe house rolled &e%number%&c \u2014 your stake is gone.",
                        "%number%", String.valueOf(rolled));
            }
            render();
        }
    }
}
