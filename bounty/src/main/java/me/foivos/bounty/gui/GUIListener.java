package me.foivos.bounty.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

/**
 * The one listener every bounty screen goes through, which is what makes them
 * safe: any click in a board or a bounty's screen is cancelled before the menu
 * sees it, so nothing can be taken out of one, put into one, shift-clicked into
 * one, dragged through one or double-clicked out of one. The menu then handles
 * the click as an action of its own.
 *
 * <p>A menu is recognised by the inventory's holder, not by its title, so a
 * chest renamed to look like the board is still just a chest.
 */
public final class GUIListener implements Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BountyHolder menu)) {
            return;
        }
        // Cancelling is what keeps the item where it is; DENY also stops the
        // client's own double-click "collect" from picking anything up.
        event.setCancelled(true);
        event.setResult(Result.DENY);
        if (event.getWhoClicked() instanceof Player player) {
            menu.handleClick(event, player);
        }
    }

    /** Nothing may be dragged through a bounty screen either. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BountyHolder) {
            event.setCancelled(true);
        }
    }

    /** And nothing may be hoppered out of one. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof BountyHolder) {
            event.setCancelled(true);
        }
    }
}
