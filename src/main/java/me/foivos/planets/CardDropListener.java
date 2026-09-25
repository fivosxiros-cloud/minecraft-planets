package me.foivos.planets;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Turns a kill into a card roll.
 *
 * <p>It runs at {@code MONITOR} — after every other plugin has had its say on
 * the death — and it only ever <i>reads</i> the event, so nothing another
 * plugin does to the drop can be disturbed by the card hunt. A death with no
 * player killer (a mob falling into lava, a pet finishing something off) is not
 * a kill and rolls nothing.
 */
final class CardDropListener implements Listener {

    private final CardService cards;

    CardDropListener(CardService cards) {
        this.cards = cards;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return; // nobody's kill, nobody's card
        }
        cards.handleKill(killer, event.getEntityType());
    }
}
