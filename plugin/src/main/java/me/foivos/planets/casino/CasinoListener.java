package me.foivos.planets.casino;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * The two inventory events a casino screen cannot answer for itself.
 *
 * <p>Clicks are routed by the plugin's own handler, which recognises every
 * casino screen through {@link CasinoScreen}; these are the events that handler
 * never looks at. Closing matters because it is what stops a screen's
 * animations — without it, a menu closed mid-spin would keep ticking — and
 * dragging matters because it is the one way an item could be moved inside a
 * casino GUI at all.
 */
public final class CasinoListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof CasinoScreen screen
                && event.getPlayer() instanceof Player player) {
            screen.onClose(player);
        }
    }

    /** Nothing can be dragged into or out of a casino screen. */
    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof CasinoScreen) {
            event.setCancelled(true);
        }
    }
}
