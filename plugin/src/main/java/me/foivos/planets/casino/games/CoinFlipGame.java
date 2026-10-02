package me.foivos.planets.casino.games;

import me.foivos.planets.MenuStyle;
import me.foivos.planets.casino.CasinoFeedback;
import me.foivos.planets.casino.CasinoGame;
import me.foivos.planets.casino.CasinoManager;
import me.foivos.planets.casino.CasinoScreenBase;
import me.foivos.planets.casino.CasinoStats;
import me.foivos.planets.casino.CasinoText;
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
 * Heads or tails.
 *
 * <p>Call it, watch the coin turn over a few times, and see where it lands.
 * Nothing is staked on the call — losing costs a round and nothing else — and
 * a win rolls a cosmetic prize out of the pool the same way every other game
 * does.
 *
 * <p>The coin's face during the animation is the same flip that decides the
 * result, so what the screen shows and what the record says can never disagree.
 */
public final class CoinFlipGame implements CasinoGame {

    /** The id this game is known by in config.yml and in every statistic. */
    static final String ID = "coinflip";
    /** How many times the coin turns before it lands. */
    private static final int FLIPS = 8;
    /** Ticks between turns — about three a second. */
    private static final long FLIP_TICKS = 3L;

    private final CasinoManager casino;

    public CoinFlipGame(CasinoManager casino) {
        this.casino = casino;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "&bCoin Flip";
    }

    @Override
    public Material icon() {
        return Material.GOLD_NUGGET;
    }

    @Override
    public List<String> description() {
        return List.of("Call it in the air.", "Heads or tails \u2014 nothing staked.");
    }

    @Override
    public void open(Player player) {
        new Screen(casino, player).open(player);
    }

    /** The game's own GUI, clicks and animation. */
    private static final class Screen extends CasinoScreenBase {

        private static final int HEADS_SLOT = 20;
        private static final int COIN_SLOT = 22;
        private static final int TAILS_SLOT = 24;
        private static final int RESULT_SLOT = 31;
        private static final int BACK_SLOT = 45;
        private static final int RECORD_SLOT = 49;
        private static final int CLOSE_SLOT = 53;

        /** Which face the coin is showing right now. */
        private boolean showingHeads = true;
        /** The line under the coin once a round has landed. */
        private String result = "";
        private NamedTextColor resultColour = NamedTextColor.GRAY;

        Screen(CasinoManager casino, Player viewer) {
            super(casino.plugin(), casino, viewer, 54,
                    CasinoText.legacy("&8\uD83E\uDE99 Coin Flip", NamedTextColor.DARK_AQUA));
        }

        @Override
        protected void render() {
            inventory().clear();
            MenuStyle.decorate(inventory(), "\uD83E\uDE99 Coin Flip", "\uD83C\uDFAF Your call");
            inventory().setItem(4, MenuStyle.header("Coin Flip", NamedTextColor.GOLD,
                    List.of("Call it in the air", "Free to play \u00B7 cosmetic prizes only")));
            inventory().setItem(HEADS_SLOT, callButton(true));
            inventory().setItem(TAILS_SLOT, callButton(false));
            inventory().setItem(COIN_SLOT, coinItem());
            if (!result.isEmpty()) {
                inventory().setItem(RESULT_SLOT, item(Material.PAPER,
                        CasinoText.legacy("&fThe coin", NamedTextColor.WHITE),
                        List.of(CasinoText.legacy(result, resultColour))));
            }
            inventory().setItem(BACK_SLOT, backButton("Casino"));
            inventory().setItem(RECORD_SLOT, recordItem());
            inventory().setItem(CLOSE_SLOT, closeButton());
        }

        private ItemStack callButton(boolean heads) {
            List<String> lore = new ArrayList<>();
            lore.add(heads ? "It lands heads" : "It lands tails");
            lore.add("");
            lore.add(animating() ? "One moment\u2026" : "Click to flip");
            return button(heads ? Material.GOLD_INGOT : Material.IRON_INGOT,
                    heads ? "\uD83E\uDE99 Heads" : "\uD83E\uDE99 Tails", lore.toArray(new String[0]));
        }

        private ItemStack coinItem() {
            boolean heads = showingHeads;
            List<Component> lore = new ArrayList<>();
            lore.add(grey(animating() ? "The coin is in the air" : "Waiting for your call"));
            return item(heads ? Material.GOLD_INGOT : Material.IRON_INGOT,
                    plain("\uD83E\uDE99 " + (animating() ? "Flipping\u2026" : heads ? "Heads" : "Tails"))
                            .color(animating() ? NamedTextColor.YELLOW : NamedTextColor.AQUA),
                    lore);
        }

        private ItemStack recordItem() {
            CasinoStats stats = casino().stats(viewerId());
            List<Component> lore = new ArrayList<>();
            lore.add(grey("Rounds: " + stats.plays(ID)));
            lore.add(grey("Won: " + stats.wins()));
            lore.add(grey("Prizes: " + stats.totalRewards()));
            return item(Material.PAPER, plain("\uD83D\uDCCA Your flips").color(NamedTextColor.AQUA), lore);
        }

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
                case HEADS_SLOT -> call(player, true);
                case TAILS_SLOT -> call(player, false);
                default -> {
                    // The frame and the read-outs do nothing.
                }
            }
        }

        /** Starts a round: the manager records it, then the coin turns. */
        private void call(Player player, boolean calledHeads) {
            if (animating()) {
                casino().notice(player, "busy", "&7That round is still going \u2014 one moment.");
                return;
            }
            if (!casino().begin(player, ID)) {
                return;
            }
            CasinoFeedback.click(player);
            result = "";
            animating(true);
            render();
            int[] turns = {0};
            every(FLIP_TICKS, FLIP_TICKS, () -> {
                turns[0]++;
                showingHeads = !showingHeads;
                CasinoFeedback.tick(player, turns[0]);
                render();
                if (turns[0] >= FLIPS) {
                    stopTasks();
                    land(player, calledHeads);
                }
            });
        }

        /** Lands the coin, reports the round and shows what happened. */
        private void land(Player player, boolean calledHeads) {
            boolean landedHeads = ThreadLocalRandom.current().nextBoolean();
            showingHeads = landedHeads;
            boolean won = landedHeads == calledHeads;
            if (won) {
                casino().win(player, ID);
                CasinoFeedback.win(player);
                casino().grant(player, ID);
            } else {
                casino().lose(player, ID);
                CasinoFeedback.lose(player);
            }
            result = "&7It landed " + (landedHeads ? "&6heads" : "&ftails")
                    + "&7 \u2014 you called " + (calledHeads ? "&6heads" : "&ftails") + "&7.";
            resultColour = won ? NamedTextColor.GREEN : NamedTextColor.RED;
            animating(false);
            render();
        }
    }
}
