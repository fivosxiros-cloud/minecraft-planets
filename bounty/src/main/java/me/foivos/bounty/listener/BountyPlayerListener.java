package me.foivos.bounty.listener;

import me.foivos.bounty.BountyPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps the bounties in step with the players: a name change is picked up the
 * next time somebody joins (the UUID never changes, so nothing else has to), and
 * a half-typed amount is dropped when its player leaves.
 */
public final class BountyPlayerListener implements Listener {

    private final BountyPlugin plugin;

    public BountyPlayerListener(BountyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.bounties().refreshName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.chat().clearPrompt(event.getPlayer().getUniqueId());
    }
}
