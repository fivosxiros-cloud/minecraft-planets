package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.OfflinePlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The activity feed: a short, newest-first list of what happened between a
 * player and their friends — requests, new friendships, gifts and friends
 * coming and going.
 *
 * <p>Entries are stored on the owning player's record as a compact
 * {@code at|type|actor|extra} line, and only turned into text when the feed is
 * shown. That keeps the file small and means a friend who renames still reads
 * correctly in old entries.
 */
final class FriendActivityService {

    /** What kind of thing happened. */
    enum Type { REQUEST_SENT, REQUEST_ACCEPTED, REQUEST_DENIED, REQUEST_CANCELLED,
        FRIEND_ADDED, FRIEND_REMOVED, GIFT_SENT, GIFT_RECEIVED, FRIEND_JOINED, FRIEND_LEFT }

    /** One decoded feed row. */
    record Entry(long at, Type type, UUID actor, String extra) {
    }

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Planets plugin;
    private final FriendService friends;
    private final FriendStore store;

    FriendActivityService(Planets plugin, FriendStore store, FriendService friends) {
        this.plugin = plugin;
        this.store = store;
        this.friends = friends;
    }

    /** How many entries are kept per player (config: {@code friends.activity-size}). */
    int feedSize() {
        return Math.max(5, plugin.getConfig().getInt("friends.activity-size", 30));
    }

    // ── Writing ─────────────────────────────────────────────────────────

    /** Records an event on one player's feed. */
    void record(UUID owner, Type type, UUID actor, String extra) {
        if (owner == null || type == null) {
            return;
        }
        FriendData target = store.get(owner);
        target.addActivity(feedSize(),
                System.currentTimeMillis() + "|" + type.name() + "|"
                        + (actor == null ? "" : actor) + "|" + (extra == null ? "" : extra));
        store.markDirty();
    }

    // ── Reading ─────────────────────────────────────────────────────────

    /** The decoded feed for a player, newest first. */
    List<Entry> feed(UUID owner) {
        FriendData data = store.peek(owner);
        if (data == null) {
            return List.of();
        }
        List<Entry> result = new ArrayList<>();
        for (String raw : data.activity()) {
            Entry entry = decode(raw);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    /** The newest {@code limit} decoded rows. */
    List<Entry> feed(UUID owner, int limit) {
        List<Entry> all = feed(owner);
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    int size(UUID owner) {
        FriendData data = store.peek(owner);
        return data == null ? 0 : data.activity().size();
    }

    /** Drops the whole feed for one player. */
    void clear(UUID owner) {
        FriendData data = store.peek(owner);
        if (data != null && !data.activity().isEmpty()) {
            data.clearActivity();
            store.markDirty();
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    /** The chat line for one entry, written from {@code viewer}'s point of view. */
    Component render(Entry entry, UUID viewer) {
        String actor = entry.actor() == null ? "" : friends.nameOf(entry.actor());
        return switch (entry.type()) {
            case REQUEST_SENT -> line("You asked " + actor + " to be friends",
                    NamedTextColor.YELLOW);
            case REQUEST_ACCEPTED -> line("You became friends with " + actor + " \u2714",
                    NamedTextColor.GREEN);
            case REQUEST_DENIED -> line("You declined " + actor + "'s friend request",
                    NamedTextColor.RED);
            case REQUEST_CANCELLED -> line("You cancelled your request to " + actor,
                    NamedTextColor.GRAY);
            case FRIEND_ADDED -> line("You became friends with " + actor + " \u2714",
                    NamedTextColor.GREEN);
            case FRIEND_REMOVED -> line("You removed " + actor + " from your friends",
                    NamedTextColor.RED);
            case GIFT_SENT -> line("You gifted " + entry.extra() + " VPL to " + actor,
                    NamedTextColor.GOLD);
            case GIFT_RECEIVED -> line(actor + " gifted you " + entry.extra() + " VPL",
                    NamedTextColor.GOLD);
            case FRIEND_JOINED -> line(actor + " came online", NamedTextColor.GREEN);
            case FRIEND_LEFT -> line(actor + " went offline", NamedTextColor.GRAY);
        };
    }

    /** A single short line for the chat notification behind an entry. */
    Component notification(Entry entry, UUID viewer) {
        return render(entry, viewer);
    }

    /** "MM-dd HH:mm" for a feed row's timestamp. */
    static String when(long millis) {
        return WHEN.format(Instant.ofEpochMilli(millis));
    }

    /** A head-friendly name for an entry's actor. */
    String actorName(Entry entry) {
        return entry.actor() == null ? "?" : friends.nameOf(entry.actor());
    }

    OfflinePlayer actorPlayer(Entry entry) {
        return entry.actor() == null ? null : plugin.getServer().getOfflinePlayer(entry.actor());
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }

    // ── Encoding ────────────────────────────────────────────────────────

    private static Entry decode(String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split("\\|", 4);
        if (parts.length < 2) {
            return null;
        }
        long at;
        try {
            at = Long.parseLong(parts[0]);
        } catch (NumberFormatException notANumber) {
            return null;
        }
        Type type;
        try {
            type = Type.valueOf(parts[1]);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        UUID actor = null;
        if (parts.length > 2 && !parts[2].isBlank()) {
            try {
                actor = UUID.fromString(parts[2]);
            } catch (IllegalArgumentException notAUuid) {
                actor = null;
            }
        }
        String extra = parts.length > 3 ? parts[3] : "";
        return new Entry(at, type, actor, extra);
    }
}
