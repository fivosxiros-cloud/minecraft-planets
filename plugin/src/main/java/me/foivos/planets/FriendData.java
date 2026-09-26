package me.foivos.planets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the friend system knows about <b>one</b> player: who they are
 * friends with, which of them are pinned as favourites, the requests still
 * waiting in either direction, a short activity feed and how many gifts they
 * have sent and received.
 *
 * <p>This is the in-memory shape of a {@code friends.yml} entry. The only
 * writer is {@link FriendStore} — services ask the store to change things so
 * every change goes through one place and gets persisted once.
 *
 * <p>Friendships are kept on <b>both</b> players' records: adding a friend
 * writes it here for the sender and on the target's record too, so looking up
 * "my friends" is a single map read and never a scan of the whole file.
 */
public final class FriendData {

    /** Friends, in the order they were added. */
    private final Set<UUID> friends = Collections.synchronizedSet(new LinkedHashSet<>());
    /** The subset of {@link #friends} pinned above the rest. */
    private final Set<UUID> favorites = Collections.synchronizedSet(new LinkedHashSet<>());
    /** Requests this player sent: target uuid -> when it was sent (ms). */
    private final Map<UUID, Long> outgoing = new ConcurrentHashMap<>();
    /** Requests sent to this player: requester uuid -> when it arrived (ms). */
    private final Map<UUID, Long> incoming = new ConcurrentHashMap<>();
    /** Newest-first activity entries, each encoded as "at|type|uuid|extra". */
    private final List<String> activity = Collections.synchronizedList(new ArrayList<>());

    private int giftsSent;
    private int giftsReceived;

    // ── Friends ─────────────────────────────────────────────────────────

    /** A read-only view of this player's friends. */
    public Set<UUID> friends() {
        return Collections.unmodifiableSet(friends);
    }

    public boolean isFriend(UUID uuid) {
        return uuid != null && friends.contains(uuid);
    }

    public int friendCount() {
        return friends.size();
    }

    boolean addFriend(UUID uuid) {
        return uuid != null && friends.add(uuid);
    }

    boolean removeFriend(UUID uuid) {
        favorites.remove(uuid);
        return uuid != null && friends.remove(uuid);
    }

    // ── Favourites ──────────────────────────────────────────────────────

    public Set<UUID> favorites() {
        return Collections.unmodifiableSet(favorites);
    }

    public boolean isFavorite(UUID uuid) {
        return uuid != null && favorites.contains(uuid);
    }

    /** Pins or unpins a friend. Only friends can be favourites. */
    boolean setFavorite(UUID uuid, boolean favorite) {
        if (uuid == null || !friends.contains(uuid)) {
            return false;
        }
        return favorite ? favorites.add(uuid) : favorites.remove(uuid);
    }

    // ── Requests ────────────────────────────────────────────────────────

    /** Requests this player has sent and that have not been answered yet. */
    public Map<UUID, Long> outgoing() {
        return Collections.unmodifiableMap(outgoing);
    }

    /** Requests other players have sent to this player and not answered yet. */
    public Map<UUID, Long> incoming() {
        return Collections.unmodifiableMap(incoming);
    }

    public boolean hasOutgoing(UUID uuid) {
        return uuid != null && outgoing.containsKey(uuid);
    }

    public boolean hasIncoming(UUID uuid) {
        return uuid != null && incoming.containsKey(uuid);
    }

    void putOutgoing(UUID uuid, long at) {
        if (uuid != null) {
            outgoing.put(uuid, at);
        }
    }

    void putIncoming(UUID uuid, long at) {
        if (uuid != null) {
            incoming.put(uuid, at);
        }
    }

    boolean removeOutgoing(UUID uuid) {
        return uuid != null && outgoing.remove(uuid) != null;
    }

    boolean removeIncoming(UUID uuid) {
        return uuid != null && incoming.remove(uuid) != null;
    }

    // ── Activity feed ───────────────────────────────────────────────────

    /** The newest-first activity entries, most recent first. */
    public List<String> activity() {
        synchronized (activity) {
            return List.copyOf(activity);
        }
    }

    void addActivity(int limit, String entry) {
        synchronized (activity) {
            activity.add(0, entry);
            while (activity.size() > limit) {
                activity.remove(activity.size() - 1);
            }
        }
    }

    void clearActivity() {
        activity.clear();
    }

    // ── Gifts ───────────────────────────────────────────────────────────

    public int giftsSent() { return giftsSent; }
    public int giftsReceived() { return giftsReceived; }

    void addGiftSent() { giftsSent++; }
    void addGiftReceived() { giftsReceived++; }

    /** Whether there is anything worth writing to disk for this player. */
    boolean isEmpty() {
        return friends.isEmpty() && favorites.isEmpty() && outgoing.isEmpty()
                && incoming.isEmpty() && activity.isEmpty()
                && giftsSent == 0 && giftsReceived == 0;
    }
}
