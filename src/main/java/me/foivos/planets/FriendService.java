package me.foivos.planets;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The friendship graph: who is friends with whom, favourites, mutual friends
 * and the friend-count label.
 *
 * <p>Friendships are <b>bidirectional</b>: adding a friend writes the pair on
 * both records at once, so there is no such thing as a one-sided friendship
 * and no need to scan the file to answer "who are my friends".
 *
 * <p>This class owns the data only. Deciding what the player <i>sees</i> (order,
 * pages, status) lives in {@link FriendSystem} and the menus.
 */
final class FriendService {

    /** What happened when a friendship was added. */
    enum Result { OK, SELF, ALREADY_FRIENDS, AT_LIMIT }

    private final Planets plugin;
    private final FriendStore store;

    /**
     * uuid -> last known name. {@code Bukkit.getOfflinePlayer} is already
     * cached by the server, but this saves the map lookups when the friends
     * menu sorts a whole page of heads at once.
     */
    private final ConcurrentHashMap<UUID, String> nameCache = new ConcurrentHashMap<>();

    FriendService(Planets plugin, FriendStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /** The record for a player, creating a blank one on first use. */
    FriendData data(UUID uuid) {
        return store.get(uuid);
    }

    /** The record as it stands, or null when this player never had one. */
    FriendData peek(UUID uuid) {
        return store.peek(uuid);
    }

    // ── Names ───────────────────────────────────────────────────────────

    /** A player's current name (online if possible), cached for offline reads. */
    String nameOf(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            nameCache.put(uuid, online.getName());
            return online.getName();
        }
        String cached = nameCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        String name = null;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        if (offline != null) {
            name = offline.getName();
        }
        if (name == null || name.isBlank()) {
            name = uuid.toString().substring(0, 8);
        }
        nameCache.put(uuid, name);
        return name;
    }

    /** Refreshes a name when its owner is online (names can change). */
    void rememberName(Player player) {
        if (player != null) {
            nameCache.put(player.getUniqueId(), player.getName());
        }
    }

    /** Drops a cached name, so a rename or a fresh login is picked up. */
    void forgetName(UUID uuid) {
        nameCache.remove(uuid);
    }

    // ── The graph ───────────────────────────────────────────────────────

    /** Whether these two are friends. Never touches the disk or creates records. */
    boolean areFriends(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        FriendData data = store.peek(a);
        return data != null && data.isFriend(b);
    }

    /** How many friends a player has. */
    int friendCount(UUID uuid) {
        FriendData data = store.peek(uuid);
        return data == null ? 0 : data.friendCount();
    }

    /** A player's friends, unsorted. */
    Set<UUID> friendsOf(UUID uuid) {
        FriendData data = store.peek(uuid);
        return data == null ? Set.of() : data.friends();
    }

    /** The friends this player pinned to the top of their list. */
    Set<UUID> favoritesOf(UUID uuid) {
        FriendData data = store.peek(uuid);
        return data == null ? Set.of() : data.favorites();
    }

    boolean isFavorite(UUID uuid, UUID friend) {
        FriendData data = store.peek(uuid);
        return data != null && data.isFavorite(friend);
    }

    /**
     * Makes the two players friends. The pair is written on both records, so
     * the friendship exists from either side immediately. Any request the two
     * had between them is cleared as part of the same change.
     *
     * @return what happened, so the caller can explain it in chat
     */
    Result addFriend(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) {
            return Result.SELF;
        }
        if (areFriends(a, b)) {
            return Result.ALREADY_FRIENDS;
        }
        if (friendCount(a) >= maxFriends() || friendCount(b) >= maxFriends()) {
            return Result.AT_LIMIT;
        }
        FriendData left = store.get(a);
        FriendData right = store.get(b);
        left.addFriend(b);
        right.addFriend(a);
        // A pending request in either direction is answered by this.
        left.removeOutgoing(b);
        left.removeIncoming(b);
        right.removeOutgoing(a);
        right.removeIncoming(a);
        store.markDirty();
        return Result.OK;
    }

    /** Ends the friendship in both directions. */
    boolean removeFriend(UUID a, UUID b) {
        if (a == null || b == null) {
            return false;
        }
        FriendData left = store.get(a);
        FriendData right = store.get(b);
        boolean removed = left.removeFriend(b);
        right.removeFriend(a);
        if (removed) {
            store.markDirty();
        }
        return removed;
    }

    /** Pins or unpins a friend. Returns the new state, or null when not a friend. */
    Boolean toggleFavorite(UUID uuid, UUID friend) {
        FriendData data = store.get(uuid);
        if (!data.isFriend(friend)) {
            return null;
        }
        boolean next = !data.isFavorite(friend);
        data.setFavorite(friend, next);
        store.markDirty();
        return next;
    }

    /**
     * The friends both players share: their friend lists intersected. Built
     * from the two in-memory sets, so it costs nothing extra on disk.
     */
    Set<UUID> mutualFriends(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) {
            return Set.of();
        }
        Set<UUID> left = friendsOf(a);
        if (left.isEmpty()) {
            return Set.of();
        }
        Set<UUID> right = friendsOf(b);
        if (right.isEmpty()) {
            return Set.of();
        }
        Set<UUID> shared = new HashSet<>(left);
        shared.retainAll(right);
        return shared;
    }

    int mutualCount(UUID a, UUID b) {
        return mutualFriends(a, b).size();
    }

    /** How many friends a player may have (config: {@code friends.max-friends}). */
    int maxFriends() {
        return Math.max(1, plugin.getConfig().getInt("friends.max-friends", 100));
    }

    /** Every friend, sorted by name — used when a stable order is enough. */
    List<UUID> friendsByName(UUID uuid) {
        List<UUID> result = new ArrayList<>(friendsOf(uuid));
        result.sort((left, right) -> nameOf(left).compareToIgnoreCase(nameOf(right)));
        return result;
    }

    /** Writes any queued change straight away (used on shutdown). */
    void flush() {
        store.save();
    }
}
