package me.foivos.planets.casino.games;

import me.foivos.planets.MenuStyle;
import me.foivos.planets.casino.CasinoFeedback;
import me.foivos.planets.casino.CasinoGame;
import me.foivos.planets.casino.CasinoManager;
import me.foivos.planets.casino.CasinoReward;
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

/**
 * A chest that opens itself and hands over whatever is inside.
 *
 * <p>This is the prize game: there is no way to lose it, so a round is filed
 * as a completed round rather than a win or a loss, and the thing worth
 * reading on the screen is the tier of what came out. The prizes are cosmetic
 * keepsakes — nothing is ever put into a player's inventory — which is why
 * opening the chest can never be worth anything but the collection.
 */
public final class MysteryChestGame implements CasinoGame {

    static final String ID = "mystery_chest";
    /** How many times the lid rattles before it gives. */
    private static final int SHAKES = 10;
    private static final long SHAKE_TICKS = 3L;

    private final CasinoManager casino;

    public MysteryChestGame(CasinoManager casino) {
        this.casino = casino;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "&bMystery Chest";
    }

    @Override
    public Material icon() {
        return Material.CHEST;
    }

    @Override
    public List<String> description() {
        return List.of("A chest that opens itself.", "Inside is a cosmetic prize.");
    }

    @Override
    public void open(Player player) {
        new Screen(casino, player).open(player);
    }

    /** The game's own GUI, clicks and animation. */
    private static final class Screen extends CasinoScreenBase {

        private static final int OPEN_SLOT = 22;
        private static final int CHEST_SLOT = 31;
        private static final int RESULT_SLOT = 40;
        private static final int BACK_SLOT = 45;
        private static final int RECORD_SLOT = 49;
        private static final int CLOSE_SLOT = 53;

        /** The prize the last round turned up, or null before the first open. */
        private CasinoReward lastPrize;
        private boolean opened;
        private String result = "";

        Screen(CasinoManager casino, Player viewer) {
            super(casino.plugin(), casino, viewer, 54,
                    CasinoText.legacy("&8\uD83C\uDF81 Mystery Chest", NamedTextColor.DARK_AQUA));
        }

        @Override
        protected void render() {
            inventory().clear();
            MenuStyle.decorate(inventory(), "\uD83C\uDF81 Mystery Chest", "\uD83C\uDF1F Prizes");
            inventory().setItem(4, MenuStyle.header("Mystery Chest", NamedTextColor.GOLD,
                    List.of("Every chest pays out",
                            casino().prizes().size() + " prizes in the pool")));
            inventory().setItem(OPEN_SLOT, openButton());
            inventory().setItem(CHEST_SLOT, chestItem());
            if (!result.isEmpty()) {
                inventory().setItem(RESULT_SLOT, item(Material.PAPER,
                        plain("Inside").color(NamedTextColor.WHITE),
                        List.of(CasinoText.legacy(result, lastPrize == null
                                ? NamedTextColor.GRAY : lastPrize.rarity().colour()))));
            }
            inventory().setItem(BACK_SLOT, backButton("Casino"));
            inventory().setItem(RECORD_SLOT, recordItem());
            inventory().setItem(CLOSE_SLOT, closeButton());
        }

        private ItemStack openButton() {
            List<String> lore = new ArrayList<>();
            lore.add("The chest always pays out");
            lore.add("Prizes are keepsakes, never items");
            lore.add("");
            lore.add(animating() ? "The lid is rattling\u2026" : "Click to open");
            return button(Material.TRIPWIRE_HOOK, "\uD83C\uDF81 Open the chest", lore.toArray(new String[0]));
        }

        /** The chest itself, or — once it has opened — what came out of it. */
        private ItemStack chestItem() {
            if (opened && lastPrize != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(CasinoText.legacy("&7" + lastPrize.rarity().label(),
                        lastPrize.rarity().colour()));
                for (String line : lastPrize.lore()) {
                    lore.add(grey(line));
                }
                lore.add(grey(""));
                lore.add(grey("Collected \u00D7" + casino().stats(viewerId()).rewardCount(lastPrize.id())));
                return item(lastPrize.icon(), lastPrize.displayName(), lore);
            }
            return item(animating() ? Material.TRAPPED_CHEST : Material.CHEST,
                    plain(animating() ? "\uD83C\uDF81 Rattling\u2026" : "\uD83C\uDF81 A closed chest")
                            .color(animating() ? NamedTextColor.YELLOW : NamedTextColor.AQUA),
                    List.of(grey(opened ? "Open it again" : "Something is in there")));
        }

        private ItemStack recordItem() {
            CasinoStats stats = casino().stats(viewerId());
            List<Component> lore = new ArrayList<>();
            lore.add(grey("Rounds: " + stats.plays(ID)));
            lore.add(grey("Prizes: " + stats.totalRewards()));
            lore.add(grey("Different: " + stats.rewards().size() + " / " + casino().prizes().size()));
            return item(Material.PAPER, plain("\uD83D\uDCCA Your cabinet").color(NamedTextColor.AQUA), lore);
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
                case OPEN_SLOT -> openChest(player);
                default -> {
                    // The frame and the read-outs do nothing.
                }
            }
        }

        private void openChest(Player player) {
            if (animating()) {
                casino().notice(player, "busy", "&7That round is still going \u2014 one moment.");
                return;
            }
            if (!casino().begin(player, ID)) {
                return;
            }
            CasinoFeedback.click(player);
            result = "";
            lastPrize = null;
            opened = false;
            animating(true);
            render();
            int[] shakes = {0};
            every(SHAKE_TICKS, SHAKE_TICKS, () -> {
                shakes[0]++;
                CasinoFeedback.tick(player, shakes[0]);
                render();
                if (shakes[0] >= SHAKES) {
                    stopTasks();
                    reveal(player);
                }
            });
        }

        /** Opens the lid, files the round and shows the prize. */
        private void reveal(Player player) {
            lastPrize = casino().grant(player, ID);
            opened = true;
            // The chest cannot be lost, so it is filed as a round that was
            // played to the end rather than as a win or a loss.
            casino().draw(player, ID);
            result = lastPrize == null
                    ? "&7The chest was empty \u2014 no prizes are configured."
                    : "&7You found a " + lastPrize.rarity().label().toLowerCase(java.util.Locale.ROOT)
                            + " prize: " + lastPrize.name() + "&7.";
            animating(false);
            render();
        }
    }
}
