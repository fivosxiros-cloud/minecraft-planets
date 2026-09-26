package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Everything the friend system says to a player: friend requests with their
 * <b>clickable</b> Accept ✓ / Deny ✗ buttons, join and quit notes, gift notes
 * and the running commentary on a friend's activity.
 *
 * <p>Every method here checks the receiver's own switch for that kind of
 * message before sending anything, so a player who muted gift notifications is
 * never told — but the gift itself still arrives and still shows in the feed.
 * Requests and join/quit notes are sent through the existing
 * {@code /settings} toggles, never a separate per-system menu.
 */
final class FriendNotificationService {

    private final Planets plugin;
    private final FriendService friends;

    FriendNotificationService(Planets plugin, FriendService friends) {
        this.plugin = plugin;
        this.friends = friends;
    }

    /** Whether the player wants this kind of message. */
    boolean wants(UUID uuid, PlayerSettings.Setting setting) {
        PlayerSettings settings = plugin.getPlayerSettings();
        return settings == null || settings.get(uuid, setting);
    }

    private Player online(UUID uuid) {
        return uuid == null ? null : Bukkit.getPlayer(uuid);
    }

    // ── Requests ────────────────────────────────────────────────────────

    /**
     * The incoming-request message, with the two buttons directly in chat:
     * clicking ✔ runs {@code /friend accept <who>} and ✖ runs
     * {@code /friend deny <who>}, so answering is one click.
     */
    void request(UUID receiver, UUID requester) {
        Player target = online(receiver);
        if (target == null) {
            return; // they will be reminded when they next log in
        }
        String name = friends.nameOf(requester);
        target.sendMessage(Component.text("\uD83D\uDC65 ").color(NamedTextColor.AQUA)
                .append(Component.text(name).color(NamedTextColor.YELLOW))
                .append(Component.text(" wants to be your friend.").color(NamedTextColor.GRAY)));
        Component accept = Component.text("[ \u2714 Accept ]")
                .color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false)
                .clickEvent(ClickEvent.runCommand("/friend accept " + name))
                .hoverEvent(HoverEvent.showText(Component.text("Accept \u2014 become friends with " + name)
                        .color(NamedTextColor.GREEN)));
        Component deny = Component.text("[ \u2716 Deny ]")
                .color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false)
                .clickEvent(ClickEvent.runCommand("/friend deny " + name))
                .hoverEvent(HoverEvent.showText(Component.text("Deny the request from " + name)
                        .color(NamedTextColor.RED)));
        target.sendMessage(accept.append(Component.text("   ")).append(deny));
        target.sendMessage(Component.text("You can also review requests in ").color(NamedTextColor.DARK_GRAY)
                .append(Component.text("/friends").color(NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/friends")))
                .append(Component.text(" \u2192 Requests.").color(NamedTextColor.DARK_GRAY)));
        target.playSound(target.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.5f);
    }

    /** The reminder a player gets on login for requests that arrived while away. */
    void pendingRequests(Player player) {
        for (UUID requester : plugin.friendSystem().requests().incoming(player.getUniqueId())) {
            request(player.getUniqueId(), requester);
        }
    }

    /** Tells the requester their request was accepted (if they are still on). */
    void requestAccepted(UUID requester, UUID accepter) {
        Player target = online(requester);
        if (target == null) {
            return;
        }
        String name = friends.nameOf(accepter);
        target.sendMessage(Component.text(name).color(NamedTextColor.YELLOW)
                .append(Component.text(" accepted your friend request \u2014 you're friends now.")
                        .color(NamedTextColor.GREEN)));
        target.playSound(target.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
    }

    /** Tells the requester their request was declined. */
    void requestDenied(UUID requester, UUID denier) {
        Player target = online(requester);
        if (target == null) {
            return;
        }
        target.sendMessage(Component.text(friends.nameOf(denier)).color(NamedTextColor.YELLOW)
                .append(Component.text(" declined your friend request.").color(NamedTextColor.RED)));
    }

    /** Tells a player a friendship was ended by the other side. */
    void friendRemoved(UUID other, UUID remover) {
        Player target = online(other);
        if (target == null) {
            return;
        }
        target.sendMessage(Component.text(friends.nameOf(remover)).color(NamedTextColor.YELLOW)
                .append(Component.text(" removed you from their friends.").color(NamedTextColor.RED)));
        plugin.sidebarScoreboard().refresh(target);
    }

    // ── Presence ────────────────────────────────────────────────────────

    /** A friend came online: everyone who wants to know hears it once. */
    void join(UUID joiner, String joinerName) {
        for (UUID friend : friends.friendsOf(joiner)) {
            if (!wants(friend, PlayerSettings.Setting.FRIEND_JOIN_NOTIFICATIONS)) {
                continue;
            }
            Player listener = online(friend);
            if (listener == null) {
                continue;
            }
            listener.sendMessage(Component.text("\uD83D\uDFE2 ").color(NamedTextColor.GREEN)
                    .append(Component.text(joinerName).color(NamedTextColor.YELLOW))
                    .append(Component.text(" (friend) came online.").color(NamedTextColor.GRAY)));
            plugin.sidebarScoreboard().refresh(listener);
        }
    }

    /** A friend went offline. */
    void quit(UUID quitter, String quitterName) {
        for (UUID friend : friends.friendsOf(quitter)) {
            if (!wants(friend, PlayerSettings.Setting.FRIEND_QUIT_NOTIFICATIONS)) {
                continue;
            }
            Player listener = online(friend);
            if (listener == null) {
                continue;
            }
            listener.sendMessage(Component.text("\u26AA ").color(NamedTextColor.GRAY)
                    .append(Component.text(quitterName).color(NamedTextColor.YELLOW))
                    .append(Component.text(" (friend) went offline.").color(NamedTextColor.GRAY)));
        }
    }

    // ── Gifts and activity ──────────────────────────────────────────────

    /** Tells the receiver a gift landed, if they want gift notes. */
    void gift(UUID receiver, UUID sender, double amount) {
        Player target = online(receiver);
        if (target == null || !wants(receiver, PlayerSettings.Setting.GIFT_NOTIFICATIONS)) {
            return;
        }
        target.sendMessage(Component.text("\uD83C\uDF81 ").color(NamedTextColor.GOLD)
                .append(Component.text(friends.nameOf(sender)).color(NamedTextColor.YELLOW))
                .append(Component.text(" gifted you ").color(NamedTextColor.GRAY))
                .append(Component.text(Planets.formatPrice(amount) + " VPL").color(NamedTextColor.GOLD))
                .append(Component.text("!").color(NamedTextColor.GRAY)));
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
    }

    /** A quiet action-bar note about a friend's activity, if they want them. */
    void activity(UUID owner, FriendActivityService.Entry entry) {
        Player target = online(owner);
        if (target == null || !wants(owner, PlayerSettings.Setting.ACTIVITY_NOTIFICATIONS)) {
            return;
        }
        plugin.sendPriorityBar(target,
                plugin.friendSystem().activity().notification(entry, owner));
    }

    /** A friend messaged the player: a short ping in the action bar. */
    void friendMessage(UUID receiver, UUID sender) {
        Player target = online(receiver);
        if (target == null) {
            return;
        }
        plugin.sendPriorityBar(target, Component.text("\uD83D\uDCAC ")
                .color(NamedTextColor.AQUA)
                .append(Component.text(friends.nameOf(sender) + " (friend) messaged you")
                        .color(NamedTextColor.WHITE)));
    }
}
