package me.foivos.planets.casino;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Any inventory the casino draws: the hub, the statistics screen and every
 * game's own GUI.
 *
 * <p>The plugin routes clicks by asking a screen whether the inventory belongs
 * to it, so this one interface is the whole of the casino's click plumbing.
 * A new game never touches the plugin's event handler: it extends
 * {@link CasinoScreenBase}, and its clicks arrive here.
 */
public interface CasinoScreen extends InventoryHolder {

    /**
     * Handles a click inside this screen. The screen is expected to cancel the
     * event (nothing can ever be taken out of a casino GUI) and to ignore
     * clicks that land outside its own inventory.
     */
    void handleClick(InventoryClickEvent event);

    /**
     * Called when the screen is closed — by the player, by pressing escape, or
     * by the game itself. It is where a screen stops whatever it is animating.
     */
    default void onClose(Player player) {
    }
}
