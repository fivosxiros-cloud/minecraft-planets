package me.foivos.planets.casino;

import me.foivos.planets.MenuStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The stake picker the casino's own games share.
 *
 * <p>Coin flip, dice, slots and the wheel can all be played for money: the
 * game's screen carries a stake item, clicking it opens this dialog, and the
 * pick lands back in the game. A stake of 0 is a real choice — it is the free
 * round the games always offered, cosmetic prizes and nothing at risk — so
 * betting is an upgrade on the old play, never a replacement for it.
 *
 * <p>The dialog never takes money itself; it only chooses the number. The
 * game takes the stake the moment the round actually starts (the same click
 * that records the round), so closing this dialog commits nothing.
 */
public final class CasinoStakeDialog extends CasinoScreenBase {

    /** The stakes on offer, in ascending order. */
    public static final double[] STAKES = {1, 5, 10, 25, 100, 500};

    private static final int SIZE = 27;
    private static final int HEADER_SLOT = 4;
    private static final int[] STAKE_SLOTS = {10, 11, 12, 14, 15, 16};
    private static final int FREE_SLOT = 21;
    private static final int CLOSE_SLOT = 25;

    /** What a chosen stake comes back to. */
    public interface Pick {
        void choose(double stake);
    }

    private final Pick pick;

    public CasinoStakeDialog(CasinoManager casino, Player viewer, Component title, Pick pick) {
        super(casino.plugin(), casino, viewer, SIZE, title);
        this.pick = pick;
    }

    @Override
    protected void render() {
        Player player = viewer();
        if (player == null) {
            return;
        }
        inventory().clear();
        MenuStyle.decorate(inventory(), "\uD83D\uDCB0 Choose a stake", "\uD83C\uDFB0 Your bet");
        inventory().setItem(HEADER_SLOT, MenuStyle.header("Choose a stake", NamedTextColor.GOLD,
                List.of("The stake is taken when the round starts,",
                        "not now — closing this commits nothing.",
                        "",
                        "Balance: " + CasinoWager.money(casino().wager().balance(player)) + " coins")));

        for (int i = 0; i < STAKE_SLOTS.length && i < STAKES.length; i++) {
            inventory().setItem(STAKE_SLOTS[i], stakeItem(player, STAKES[i]));
        }
        inventory().setItem(FREE_SLOT, item(Material.LIME_DYE,
                plain("\u2714 Play for free").color(NamedTextColor.GREEN),
                List.of(grey("No coins on the round —"),
                        grey("cosmetic prizes only, as always."))));
        inventory().setItem(CLOSE_SLOT, closeButton());
    }

    private ItemStack stakeItem(Player player, double stake) {
        boolean affordable = casino().wager().canAfford(player, stake);
        List<Component> lore = new ArrayList<>();
        lore.add(grey(affordable ? "Click to bet this on the next round"
                : "Not enough coins for this stake"));
        lore.add(grey("Taken when the round starts"));
        return item(affordable ? Material.GOLD_BLOCK : Material.GRAY_DYE,
                plain("\uD83D\uDCB0 " + CasinoWager.money(stake) + " coins")
                        .color(affordable ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY),
                lore);
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = clickedSlot(event);
        Player player = viewer();
        if (slot < 0 || player == null) {
            return;
        }
        if (slot == CLOSE_SLOT) {
            CasinoFeedback.back(player);
            player.closeInventory();
            return;
        }
        if (slot == FREE_SLOT) {
            CasinoFeedback.click(player);
            player.closeInventory();
            pick.choose(0);
            return;
        }
        for (int i = 0; i < STAKE_SLOTS.length && i < STAKES.length; i++) {
            if (STAKE_SLOTS[i] == slot) {
                double stake = STAKES[i];
                if (!casino().wager().canAfford(player, stake)) {
                    CasinoFeedback.deny(player);
                    casino().notice(player, "not-enough",
                            "&cYou need &e%amount%&c coins for that stake.",
                            "%amount%", CasinoWager.money(stake));
                    return;
                }
                CasinoFeedback.click(player);
                player.closeInventory();
                pick.choose(stake);
                return;
            }
        }
    }
}
