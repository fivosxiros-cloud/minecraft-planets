package me.foivos.bounty.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * A menu of this plugin. Both the board and a bounty's own screen are drawn in
 * inventories that only the plugin owns, and every click in one is handed to
 * its holder — the single listener in {@link GUIListener} finds it this way
 * instead of switching on inventory titles.
 *
 * <p>Clicks are always cancelled before they reach a menu: nothing can be taken
 * out of, put into, or dragged through a bounty screen, which is what makes
 * them screens rather than chests.
 */
public interface BountyHolder {

    /** Handles one already-cancelled click inside this menu. */
    void handleClick(InventoryClickEvent event, Player player);
}
