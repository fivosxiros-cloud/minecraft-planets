package me.foivos.planets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending friend requests, in both directions.
 *
 * <p>A request is one half of a friendship: it lives on the sender's record
 * under {@code requests-out} and on the receiver's under {@code requests-in},
 * both stamped with when it was sent. Requests expire after
 * {@code friends.request-expiry-minutes} so a stale one can never turn into a
 * surprise friendship weeks later — {@link #expire()} prunes them and runs with
 * the regular maintenance pass.
 */
final class FriendRequestService {

    /** What happened when a request was sent. */
    enum Result {
        /** The request is now pending. */
        SENT,
        /** The other player had already asked, so the two are friends now. */
        ACCEPTED,
        SELF,
        ALREADY_FRIENDS,
        /** A request between the two is already pending. */
        ALREADY_PENDING,
        /** The receiver already has an unanswered request from this player. */
        THEY_ASKED,
        /** The receiver turned friend requests off. */
        DISABLED,
        /** One of the two is at the friend limit. */
        AT_LIMIT
    }

    /** Fallback gap between a player's requests when the config asks for none. */
    private static final long DEFAULT_COOLDOWN_MILLIS = 3_000L;

    private final Planets plugin;
    private final FriendService friends;
    private final FriendStore store;
    /** uuid -> when that player last sent a request, for the send cooldown. */
    private final Map<UUID, Long> lastSentAt = new ConcurrentHashMap<>();

    FriendRequestService(Planets plugin, FriendStore store, FriendService friends) {
        this.plugin = plugin;
        this.store = store;
        this.friends = friends;
    }

    // ── Sending ─────────────────────────────────────────────────────────

    /**
     * Sends a friend request from one player to another.
     *
     * <p>If the target had already sent one, this is the answer to it and the
     * two become friends straight away — the usual "we both clicked" case
     * should never leave two crossed requests hanging.
     */
    Result send(UUID from, UUID to) {
        return send(from, to, false);
    }

    /**
     * Sends a friend request, unless the receiver turned them off.
     *
     * @param bypass true for a sender who holds the settings-bypass permission,
     *               which ignores the receiver's toggle the way a staff member
     *               can already override a private-message block
     */
    Result send(UUID from, UUID to, boolean bypass) {
        if (from == null || to == null || from.equals(to)) {
            return Result.SELF;
        }
        if (friends.areFriends(from, to)) {
            return Result.ALREADY_FRIENDS;
        }
        if (!bypass && !wantsRequests(to)) {
            return Result.DISABLED;
        }
        FriendData target = store.get(to);
        if (target.hasOutgoing(from)) {
            // They asked first: accept instead of letting the requests cross.
            return friends.addFriend(from, to) == FriendService.Result.OK
                    ? Result.ACCEPTED : Result.AT_LIMIT;
        }
        FriendData sender = store.get(from);
        if (sender.hasOutgoing(to) || target.hasIncoming(from)) {
            return Result.ALREADY_PENDING;
        }
        if (sender.hasIncoming(to)) {
            return Result.THEY_ASKED;
        }
        if (onCooldown(from)) {
            return Result.ALREADY_PENDING; // the caller explains the wait
        }
        if (friends.friendCount(from) >= friends.maxFriends()
                || friends.friendCount(to) >= friends.maxFriends()) {
            return Result.AT_LIMIT;
        }
        long now = System.currentTimeMillis();
        sender.putOutgoing(to, now);
        target.putIncoming(from, now);
        lastSentAt.put(from, now);
        store.markDirty();
        return Result.SENT;
    }

    /** Whether a player accepts incoming friend requests (their /settings switch). */
    private boolean wantsRequests(UUID uuid) {
        PlayerSettings settings = plugin.getPlayerSettings();
        return settings == null
                || settings.get(uuid, PlayerSettings.Setting.FRIEND_REQUESTS);
    }

    /** Whether this player is sending requests faster than the cooldown allows. */
    boolean onCooldown(UUID from) {
        Long last = lastSentAt.get(from);
        if (last == null) {
            return false;
        }
        return System.currentTimeMillis() - last < cooldownMillis();
    }

    // ── Answering ───────────────────────────────────────────────────────

    /** Accepts a request this player received. */
    boolean accept(UUID player, UUID requester) {
        if (player == null || requester == null || player.equals(requester)) {
            return false;
        }
        FriendData mine = store.peek(player);
        if (mine == null || !mine.hasIncoming(requester)) {
            return false;
        }
        return friends.addFriend(player, requester) == FriendService.Result.OK;
    }

    /** Denies a request this player received. */
    boolean deny(UUID player, UUID requester) {
        if (player == null || requester == null) {
            return false;
        }
        FriendData mine = store.peek(player);
        if (mine == null || !mine.removeIncoming(requester)) {
            return false;
        }
        FriendData theirs = store.peek(requester);
        if (theirs != null) {
            theirs.removeOutgoing(player);
        }
        store.markDirty();
        return true;
    }

    /** Withdraws a request this player sent. */
    boolean cancel(UUID player, UUID target) {
        if (player == null || target == null) {
            return false;
        }
        FriendData mine = store.peek(player);
        if (mine == null || !mine.removeOutgoing(target)) {
            return false;
        }
        FriendData theirs = store.peek(target);
        if (theirs != null) {
            theirs.removeIncoming(player);
        }
        store.markDirty();
        return true;
    }

    // ── Reads ───────────────────────────────────────────────────────────

    /** Requests waiting for this player, oldest first. */
    List<UUID> incoming(UUID player) {
        FriendData data = store.peek(player);
        if (data == null || data.incoming().isEmpty()) {
            return List.of();
        }
        List<UUID> result = new ArrayList<>(data.incoming().keySet());
        result.sort(Comparator.comparingLong(id -> data.incoming().getOrDefault(id, 0L)));
        return result;
    }

    /** Requests this player sent and is still waiting on, oldest first. */
    List<UUID> outgoing(UUID player) {
        FriendData data = store.peek(player);
        if (data == null || data.outgoing().isEmpty()) {
            return List.of();
        }
        List<UUID> result = new ArrayList<>(data.outgoing().keySet());
        result.sort(Comparator.comparingLong(id -> data.outgoing().getOrDefault(id, 0L)));
        return result;
    }

    int incomingCount(UUID player) {
        FriendData data = store.peek(player);
        return data == null ? 0 : data.incoming().size();
    }

    int outgoingCount(UUID player) {
        FriendData data = store.peek(player);
        return data == null ? 0 : data.outgoing().size();
    }

    /** When a still-pending request was sent (ms), or 0 when there is none. */
    long sentAt(UUID player, UUID target) {
        FriendData data = store.peek(player);
        return data == null ? 0L : data.outgoing().getOrDefault(target, 0L);
    }

    /** How long a request stays valid (config: {@code friends.request-expiry-minutes}). */
    long expiryMillis() {
        return Math.max(1L, plugin.getConfig().getLong("friends.request-expiry-minutes", 4320L))
                * 60_000L;
    }

    /** The gap a player must leave between requests (config, or the fallback). */
    private long cooldownMillis() {
        long configured = plugin.getConfig().getLong("friends.request-cooldown-seconds", 3L) * 1000L;
        return configured > 0 ? configured : DEFAULT_COOLDOWN_MILLIS;
    }

    // ── Maintenance ─────────────────────────────────────────────────────

    /** Drops requests that have aged out, answering them nowhere. */
    int expire() {
        long expiry = expiryMillis();
        long now = System.currentTimeMillis();
        int dropped = 0;
        for (UUID id : store.ids()) {
            FriendData data = store.peek(id);
            if (data == null) {
                continue;
            }
            for (Map.Entry<UUID, Long> entry : new ArrayList<>(data.outgoing().entrySet())) {
                if (now - entry.getValue() > expiry) {
                    if (data.removeOutgoing(entry.getKey())) {
                        dropped++;
                    }
                    FriendData other = store.peek(entry.getKey());
                    if (other != null) {
                        other.removeIncoming(id);
                    }
                }
            }
            for (Map.Entry<UUID, Long> entry : new ArrayList<>(data.incoming().entrySet())) {
                if (now - entry.getValue() > expiry) {
                    if (data.removeIncoming(entry.getKey())) {
                        dropped++;
                    }
                    FriendData other = store.peek(entry.getKey());
                    if (other != null) {
                        other.removeOutgoing(id);
                    }
                }
            }
        }
        if (dropped > 0) {
            store.markDirty();
        }
        return dropped;
    }
}
