package me.foivos.bounty.notification;

import me.foivos.bounty.BountyPlugin;
import me.foivos.bounty.settings.BountySetting;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Everything the board says to players, in one place: the personal notes and
 * the server-wide announcements.
 *
 * <p>The personal ones are the player's own choice — they live as switches in
 * the server's {@code /settings} menu (see {@link BountySetting}) and nothing is
 * sent when one is off. The broadcasts are the server's choice, from
 * {@code notifications.broadcast} in config.yml.
 *
 * <p>A player who is offline is simply not told: a bounty placed on somebody who
 * is not around waits on the board for them, and the notification is not queued
 * up to ambush them at their next login.
 */
public final class BountyNotificationManager {

    private final BountyPlugin plugin;

    public BountyNotificationManager(BountyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Someone added money to a bounty. */
    public void added(Player actor, OfflinePlayer target, String targetName,
                      double amount, double previous, double total) {
        Player online = target == null ? null : target.getPlayer();
        if (online != null && wants(online, BountySetting.BOUNTY_ADDED)) {
            plugin.messages().sendLines(online, "bounty-added.target", Map.of(
                    "actor", actor.getName(),
                    "player", targetName,
                    "amount", money(amount),
                    "previous", money(previous),
                    "total", money(total)));
        }
        if (plugin.bountyConfig().broadcastAdded()) {
            plugin.getServer().broadcast(plugin.messages().component("broadcast-added", Map.of(
                    "player", targetName,
                    "actor", actor.getName(),
                    "amount", money(amount),
                    "total", money(total))));
        }
    }

    /** A bounty was claimed: the killer is paid, and both ends are told. */
    public void claimed(Player killer, OfflinePlayer victim, String victimName,
                        double amount, double refund) {
        if (wants(killer, BountySetting.BOUNTY_RECEIVED)) {
            plugin.messages().sendLines(killer, "claim.killer", Map.of(
                    "player", victimName,
                    "killer", killer.getName(),
                    "amount", money(amount)));
        }
        Player online = victim == null ? null : victim.getPlayer();
        if (online != null && wants(online, BountySetting.BOUNTY_CLAIMED)) {
            plugin.messages().sendLines(online, "claim.target", Map.of(
                    "player", victimName,
                    "killer", killer.getName(),
                    "amount", money(amount)));
            if (refund > 0) {
                plugin.messages().send(online, "claim.refund",
                        Map.of("amount", money(refund)));
            }
        }
        if (plugin.bountyConfig().broadcastClaimed()) {
            plugin.getServer().broadcast(plugin.messages().component("claim.broadcast", Map.of(
                    "killer", killer.getName(),
                    "player", victimName,
                    "amount", money(amount))));
        }
    }

    private boolean wants(Player player, BountySetting setting) {
        return player != null && plugin.settings().get(player.getUniqueId(), setting);
    }

    private String money(double amount) {
        return plugin.bountyConfig().format(amount);
    }
}
