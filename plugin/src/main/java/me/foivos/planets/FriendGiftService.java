package me.foivos.planets;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Gifts: sending VPL to a friend.
 *
 * <p>The money is moved with the plugin's existing Vault economy, the same
 * {@code withdraw}/{@code deposit} pair the planet shop already uses, so a gift
 * is an ordinary balance transfer and nothing needs a second currency. Because
 * Vault handles offline players, a gift can be waiting for a friend who is
 * logged out — they simply find it in their balance when they return.
 */
final class FriendGiftService {

    /** What happened when a gift was sent. */
    enum Result { OK, NO_ECONOMY, SELF, NOT_FRIENDS, TOO_SMALL, TOO_LARGE, NOT_ENOUGH }

    private final Planets plugin;
    private final FriendService friends;
    private final FriendActivityService activity;
    private final FriendNotificationService notifications;

    FriendGiftService(Planets plugin, FriendService friends, FriendActivityService activity,
                      FriendNotificationService notifications) {
        this.plugin = plugin;
        this.friends = friends;
        this.activity = activity;
        this.notifications = notifications;
    }

    /** Smallest gift allowed (config: {@code friends.gifts.min}). */
    double minimum() {
        return Math.max(0.01, plugin.getConfig().getDouble("friends.gifts.min", 1.0));
    }

    /** Largest gift allowed (config: {@code friends.gifts.max}). */
    double maximum() {
        return Math.max(minimum(), plugin.getConfig().getDouble("friends.gifts.max", 100_000.0));
    }

    /**
     * Sends {@code amount} VPL from one player to a friend.
     *
     * <p>The sender's balance is debited first and only then credited, so a
     * failed withdrawal can never mint money. Both sides get a feed entry, and
     * the receiver is pinged when they asked for gift notifications.
     */
    Result send(Player from, UUID to, double amount) {
        if (from == null || to == null || from.getUniqueId().equals(to)) {
            return Result.SELF;
        }
        if (!friends.areFriends(from.getUniqueId(), to)) {
            return Result.NOT_FRIENDS;
        }
        if (!plugin.hasEconomy()) {
            return Result.NO_ECONOMY;
        }
        if (amount < minimum()) {
            return Result.TOO_SMALL;
        }
        if (amount > maximum()) {
            return Result.TOO_LARGE;
        }
        OfflinePlayer receiver = Bukkit.getOfflinePlayer(to);
        if (!plugin.withdraw(from, amount)) {
            return Result.NOT_ENOUGH;
        }
        if (!plugin.depositTo(receiver, amount)) {
            // Put it straight back rather than losing it.
            plugin.deposit(from, amount);
            return Result.NO_ECONOMY;
        }
        FriendData sender = friends.data(from.getUniqueId());
        FriendData target = friends.data(to);
        sender.addGiftSent();
        target.addGiftReceived();
        String amountText = Planets.formatPrice(amount);
        activity.record(from.getUniqueId(), FriendActivityService.Type.GIFT_SENT, to, amountText);
        activity.record(to, FriendActivityService.Type.GIFT_RECEIVED, from.getUniqueId(), amountText);
        notifications.gift(to, from.getUniqueId(), amount);
        notifications.activity(to,
                new FriendActivityService.Entry(System.currentTimeMillis(),
                        FriendActivityService.Type.GIFT_RECEIVED, from.getUniqueId(), amountText));
        return Result.OK;
    }

    /** How many gifts this player has sent. */
    int sentCount(UUID uuid) {
        FriendData data = friends.peek(uuid);
        return data == null ? 0 : data.giftsSent();
    }

    /** How many gifts this player has received. */
    int receivedCount(UUID uuid) {
        FriendData data = friends.peek(uuid);
        return data == null ? 0 : data.giftsReceived();
    }
}
