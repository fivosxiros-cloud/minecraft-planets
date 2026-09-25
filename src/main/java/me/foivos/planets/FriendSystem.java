package me.foivos.planets;

import me.foivos.playerdata.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The friend system's front door: it owns the store and the six services, and
 * is the one object the rest of the plugin talks to.
 *
 * <p>It exists so the pieces below stay independent of each other — the menus
 * ask this class for an ordered page of friends, and it is here (not inside the
 * store) that "favourites first, then online, then alphabetical" lives. The
 * layout logic never reaches into persistence, and the services never know a
 * menu exists.
 *
 * <p>It also owns the short-lived chat prompts (search, message, gift amount),
 * because a player typing an answer is a flow that spans the command, the chat
 * listener and a menu.
 */
final class FriendSystem {

    /** Which slice of the friend list a menu is showing. */
    enum View {
        ALL("All"),
        FAVORITES("Favorites"),
        ONLINE("Online"),
        /** Pick a friend to see who the two of you have in common. */
        MUTUAL("Mutual");

        private final String label;

        View(String label) {
            this.label = label;
        }

        public String label() { return label; }
    }

    /** A question waiting for the player's next chat message. */
    enum PromptKind { SEARCH, MESSAGE, GIFT_AMOUNT }

    private record Prompt(PromptKind kind, UUID target) {
    }

    /** How many results a search shows at once. */
    private static final int SEARCH_LIMIT = 28;

    private final Planets plugin;
    private final FriendStore store;
    private final FriendService friends;
    private final FriendRequestService requests;
    private final FriendPresenceService presence;
    private final FriendActivityService activity;
    private final FriendGiftService gifts;
    private final FriendNotificationService notifications;

    /**
     * uuid -> the question that player's next chat message answers. Concurrent
     * because a chat line is read on the async chat thread before it is handed
     * to the main thread to act on.
     */
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    FriendSystem(Planets plugin) {
        this.plugin = plugin;
        this.store = new FriendStore(plugin);
        this.friends = new FriendService(plugin, store);
        this.requests = new FriendRequestService(plugin, store, friends);
        this.activity = new FriendActivityService(plugin, store, friends);
        this.notifications = new FriendNotificationService(plugin, friends);
        this.gifts = new FriendGiftService(plugin, friends, activity, notifications);
        this.presence = new FriendPresenceService(plugin);
        store.load();
    }

    // ── Access ──────────────────────────────────────────────────────────

    FriendService friends() { return friends; }
    FriendRequestService requests() { return requests; }
    FriendPresenceService presence() { return presence; }
    FriendActivityService activity() { return activity; }
    FriendGiftService gifts() { return gifts; }
    FriendNotificationService notifications() { return notifications; }

    /** Registers the presence listener and the request-expiry pass. */
    void start() {
        plugin.getServer().getPluginManager().registerEvents(presence, plugin);
        long interval = Math.max(1L, plugin.getConfig().getLong("friends.maintenance-minutes", 10L))
                * 60L * 20L;
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int dropped = requests.expire();
            if (dropped > 0) {
                plugin.getLogger().info("Dropped " + dropped + " expired friend request(s).");
            }
        }, interval, interval);
    }

    /** Writes anything still queued (plugin shutdown). */
    void shutdown() {
        friends.flush();
    }

    // ── The friend list ─────────────────────────────────────────────────

    /** Whether a friend should be counted as online for display and ordering. */
    private boolean visibleOnline(UUID uuid) {
        return presence.isOnline(uuid) && presence.statusVisible(uuid);
    }

    /** How many of a player's friends are visibly online. */
    int onlineCount(UUID viewer) {
        int count = 0;
        for (UUID friend : friends.friendsOf(viewer)) {
            if (visibleOnline(friend)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The friend-count label the sidebar and menus show, e.g. {@code "2/8"}:
     * friends online now, then the total number of friends.
     */
    String countLabel(UUID viewer) {
        return onlineCount(viewer) + "/" + friends.friendCount(viewer);
    }

    /**
     * One page-worth of friends, ordered the way the menu promises: favourites
     * first, then whoever is online, then alphabetically. A favourite who is
     * online still comes first, so pinning a friend is always worth doing.
     */
    List<UUID> orderedFriends(UUID viewer, View view) {
        List<UUID> list = new ArrayList<>(friends.friendsOf(viewer));
        if (view == View.FAVORITES) {
            list.removeIf(id -> !friends.isFavorite(viewer, id));
        } else if (view == View.ONLINE) {
            list.removeIf(id -> !visibleOnline(id));
        }
        list.sort(friendOrder(viewer, view == View.ALL || view == View.MUTUAL));
        return list;
    }

    private Comparator<UUID> friendOrder(UUID viewer, boolean favoritesFirst) {
        return (left, right) -> {
            if (favoritesFirst) {
                boolean leftFav = friends.isFavorite(viewer, left);
                boolean rightFav = friends.isFavorite(viewer, right);
                if (leftFav != rightFav) {
                    return leftFav ? -1 : 1;
                }
            }
            boolean leftOnline = visibleOnline(left);
            boolean rightOnline = visibleOnline(right);
            if (leftOnline != rightOnline) {
                return leftOnline ? -1 : 1;
            }
            return friends.nameOf(left).compareToIgnoreCase(friends.nameOf(right));
        };
    }

    /**
     * Searches the people this server knows about: friends whose name matches
     * come first, then anybody else who has played here (so a request can be
     * sent to somebody who is not a friend yet).
     */
    List<UUID> search(Player viewer, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        Set<UUID> hits = new LinkedHashSet<>();
        List<UUID> mine = new ArrayList<>(friends.friendsOf(viewer.getUniqueId()));
        mine.sort(friendOrder(viewer.getUniqueId(), true));
        for (UUID friend : mine) {
            if (friends.nameOf(friend).toLowerCase(Locale.ROOT).startsWith(needle)) {
                hits.add(friend);
            }
        }
        for (PlayerData data : plugin.searchPlayers(query)) {
            if (hits.size() >= SEARCH_LIMIT) {
                break;
            }
            if (data.uuid() != null && !data.uuid().equals(viewer.getUniqueId())) {
                hits.add(data.uuid());
            }
        }
        List<UUID> result = new ArrayList<>(hits);
        return result.size() > SEARCH_LIMIT ? result.subList(0, SEARCH_LIMIT) : result;
    }

    // ── Lifecycle hooks ─────────────────────────────────────────────────

    /** Called from the plugin's join handler. */
    void onJoin(Player player) {
        presence.touch(player);
        friends.rememberName(player);
        // Requests that arrived while they were away are handed over now.
        notifications.pendingRequests(player);
        notifications.join(player.getUniqueId(), player.getName());
    }

    /** Called from the plugin's quit handler. */
    void onQuit(Player player) {
        UUID uuid = player.getUniqueId();
        notifications.quit(uuid, player.getName());
        prompts.remove(uuid);
        presence.forget(uuid);
    }

    // ── Chat prompts ────────────────────────────────────────────────────

    /** Whether this player is part-way through answering a friend prompt. */
    boolean hasPrompt(Player player) {
        return player != null && prompts.containsKey(player.getUniqueId());
    }

    void clearPrompt(UUID uuid) {
        prompts.remove(uuid);
    }

    /** Starts a search: the player's next chat line is the query. */
    void promptSearch(Player player) {
        prompts.put(player.getUniqueId(), new Prompt(PromptKind.SEARCH, null));
        player.closeInventory();
        player.sendMessage(hint("Type a name to search for",
                "or \"cancel\" to stop."));
    }

    /** Starts a private message to a friend. */
    void promptMessage(Player player, UUID target) {
        prompts.put(player.getUniqueId(), new Prompt(PromptKind.MESSAGE, target));
        player.closeInventory();
        player.sendMessage(hint("Type your message to " + friends.nameOf(target),
                "or \"cancel\" to stop."));
    }

    /** Starts a gift: the next chat line is the amount. */
    void promptGift(Player player, UUID target) {
        prompts.put(player.getUniqueId(), new Prompt(PromptKind.GIFT_AMOUNT, target));
        player.closeInventory();
        player.sendMessage(hint("Type how much VPL to gift " + friends.nameOf(target),
                "between " + Planets.formatPrice(gifts.minimum()) + " and "
                        + Planets.formatPrice(gifts.maximum()) + " VPL."));
    }

    /**
     * Answers the pending prompt from a chat line. Runs on the main thread, so
     * it is safe to open a menu here.
     *
     * @return true when the line was consumed by the friend system
     */
    boolean handleChatInput(Player player, String input) {
        Prompt prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) {
            return false;
        }
        String text = input == null ? "" : input.trim();
        if (text.isEmpty() || text.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("\uD83D\uDC65 Cancelled.").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            return true;
        }
        switch (prompt.kind()) {
            case SEARCH -> openSearch(player, text);
            case MESSAGE -> messageFriend(player, prompt.target(), text);
            case GIFT_AMOUNT -> gift(player, prompt.target(), text);
        }
        return true;
    }

    private void openSearch(Player player, String query) {
        List<UUID> hits = search(player, query);
        if (hits.isEmpty()) {
            player.sendMessage(Component.text("\uD83D\uDC65 Nobody matched \"").color(NamedTextColor.RED)
                    .append(Component.text(query).color(NamedTextColor.YELLOW))
                    .append(Component.text("\".").color(NamedTextColor.RED)));
            return;
        }
        new FriendsMenu(plugin, player, this, hits, "Search: " + query).open(player);
    }

    // ── Social actions ──────────────────────────────────────────────────

    /**
     * Sends a private message to a friend, through the plugin's existing
     * messaging path — so the receiver's ordinary Private Messages toggle still
     * applies, on top of the Friends one.
     */
    void messageFriend(Player from, UUID to, String message) {
        Player target = to == null ? null : Bukkit.getPlayer(to);
        if (target == null) {
            from.sendMessage(Component.text("\uD83D\uDC65 " + friends.nameOf(to)
                            + " is not online — friend messages need them in the game.")
                    .color(NamedTextColor.RED));
            return;
        }
        if (!notifications.wants(to, PlayerSettings.Setting.FRIEND_MESSAGES)
                && !from.hasPermission("planets.settings.bypass")) {
            from.sendMessage(Component.text("\uD83D\uDC65 ").color(NamedTextColor.RED)
                    .append(Component.text(target.getName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" has friend messages turned off.").color(NamedTextColor.RED)));
            return;
        }
        plugin.messagePlayer(from, target, message);
    }

    /** Gifts a friend some VPL and explains the outcome. */
    void gift(Player from, UUID to, String amountText) {
        double amount;
        try {
            amount = Double.parseDouble(amountText.replace(",", "").replace("_", "").trim());
        } catch (NumberFormatException notANumber) {
            from.sendMessage(Component.text("\uD83C\uDF81 \"" + amountText
                            + "\" is not a number — nothing was sent.").color(NamedTextColor.RED));
            return;
        }
        FriendGiftService.Result result = gifts.send(from, to, amount);
        String name = friends.nameOf(to);
        switch (result) {
            case OK -> {
                from.sendMessage(Component.text("\uD83C\uDF81 You gifted ").color(NamedTextColor.GREEN)
                        .append(Component.text(Planets.formatPrice(amount) + " VPL")
                                .color(NamedTextColor.GOLD))
                        .append(Component.text(" to " + name + ".").color(NamedTextColor.GREEN)));
                plugin.balanceNotice(from, Component.text("\uD83D\uDCB0 -"
                                + Planets.formatPrice(amount) + " VPL (gift to " + name + ")")
                        .color(NamedTextColor.GOLD));
            }
            case NOT_ENOUGH -> from.sendMessage(Component.text("\uD83C\uDF81 You don't have that much VPL.")
                    .color(NamedTextColor.RED));
            case NO_ECONOMY -> from.sendMessage(Component.text("\uD83C\uDF81 Gifts need the economy plugin, which isn't available.")
                    .color(NamedTextColor.RED));
            case TOO_SMALL -> from.sendMessage(Component.text("\uD83C\uDF81 The smallest gift is ")
                    .color(NamedTextColor.RED)
                    .append(Component.text(Planets.formatPrice(gifts.minimum()) + " VPL.")
                            .color(NamedTextColor.YELLOW)));
            case TOO_LARGE -> from.sendMessage(Component.text("\uD83C\uDF81 The largest gift is ")
                    .color(NamedTextColor.RED)
                    .append(Component.text(Planets.formatPrice(gifts.maximum()) + " VPL.")
                            .color(NamedTextColor.YELLOW)));
            case NOT_FRIENDS -> from.sendMessage(Component.text("\uD83C\uDF81 You can only gift your friends.")
                    .color(NamedTextColor.RED));
            case SELF -> from.sendMessage(Component.text("\uD83C\uDF81 You can't gift yourself.")
                    .color(NamedTextColor.RED));
        }
    }

    /** Sends a friend request and explains the outcome. */
    void request(Player from, UUID to) {
        String name = friends.nameOf(to);
        FriendRequestService.Result result = requests.send(from.getUniqueId(), to,
                from.hasPermission("planets.settings.bypass"));
        switch (result) {
            case SENT -> {
                from.sendMessage(Component.text("\uD83D\uDC65 Friend request sent to ").color(NamedTextColor.GREEN)
                        .append(Component.text(name).color(NamedTextColor.YELLOW))
                        .append(Component.text(".").color(NamedTextColor.GREEN)));
                activity.record(from.getUniqueId(), FriendActivityService.Type.REQUEST_SENT, to, null);
                notifications.request(to, from.getUniqueId());
            }
            case ACCEPTED -> {
                from.sendMessage(Component.text("\uD83D\uDC65 You and ").color(NamedTextColor.GREEN)
                        .append(Component.text(name).color(NamedTextColor.YELLOW))
                        .append(Component.text(" are now friends \u2014 they had already asked you!")
                                .color(NamedTextColor.GREEN)));
                activity.record(from.getUniqueId(), FriendActivityService.Type.FRIEND_ADDED, to, null);
                activity.record(to, FriendActivityService.Type.FRIEND_ADDED, from.getUniqueId(), null);
                notifications.requestAccepted(to, from.getUniqueId());
                refreshBars(from.getUniqueId());
                refreshBars(to);
            }
            case ALREADY_FRIENDS -> from.sendMessage(Component.text("\uD83D\uDC65 You and ")
                    .color(NamedTextColor.YELLOW)
                    .append(Component.text(name).color(NamedTextColor.AQUA))
                    .append(Component.text(" are already friends.").color(NamedTextColor.YELLOW)));
            case ALREADY_PENDING -> {
                if (requests.onCooldown(from.getUniqueId())) {
                    from.sendMessage(Component.text("\uD83D\uDC65 Slow down a moment before the next request.")
                            .color(NamedTextColor.RED));
                } else {
                    from.sendMessage(Component.text("\uD83D\uDC65 You already have a request waiting with ")
                            .color(NamedTextColor.YELLOW)
                            .append(Component.text(name).color(NamedTextColor.AQUA))
                            .append(Component.text(".").color(NamedTextColor.YELLOW)));
                }
            }
            case THEY_ASKED -> from.sendMessage(Component.text("\uD83D\uDC65 ").color(NamedTextColor.YELLOW)
                    .append(Component.text(name).color(NamedTextColor.AQUA))
                    .append(Component.text(" already asked you \u2014 answer it with ")
                            .color(NamedTextColor.YELLOW))
                    .append(Component.text("/friend accept " + name).color(NamedTextColor.AQUA))
                    .append(Component.text(".").color(NamedTextColor.YELLOW)));
            case DISABLED -> from.sendMessage(Component.text("\uD83D\uDC65 ").color(NamedTextColor.RED)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text(" has friend requests turned off.").color(NamedTextColor.RED)));
            case AT_LIMIT -> from.sendMessage(Component.text("\uD83D\uDC65 One of you has a full friend list (")
                    .color(NamedTextColor.RED)
                    .append(Component.text(friends.maxFriends() + " max").color(NamedTextColor.YELLOW))
                    .append(Component.text(").").color(NamedTextColor.RED)));
            case SELF -> from.sendMessage(Component.text("\uD83D\uDC65 You can't befriend yourself.")
                    .color(NamedTextColor.RED));
        }
    }

    /** Accepts a request and explains the outcome. */
    void accept(Player player, UUID requester) {
        String name = friends.nameOf(requester);
        if (!requests.accept(player.getUniqueId(), requester)) {
            player.sendMessage(Component.text("\uD83D\uDC65 There is no pending request from ")
                    .color(NamedTextColor.RED)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return;
        }
        player.sendMessage(Component.text("\uD83D\uDC65 You and ").color(NamedTextColor.GREEN)
                .append(Component.text(name).color(NamedTextColor.YELLOW))
                .append(Component.text(" are now friends \u2714").color(NamedTextColor.GREEN)));
        activity.record(player.getUniqueId(), FriendActivityService.Type.FRIEND_ADDED, requester, null);
        activity.record(requester, FriendActivityService.Type.FRIEND_ADDED, player.getUniqueId(), null);
        notifications.requestAccepted(requester, player.getUniqueId());
        refreshBars(player.getUniqueId());
        refreshBars(requester);
    }

    /** Denies a request. */
    void deny(Player player, UUID requester) {
        String name = friends.nameOf(requester);
        if (!requests.deny(player.getUniqueId(), requester)) {
            player.sendMessage(Component.text("\uD83D\uDC65 There is no pending request from ")
                    .color(NamedTextColor.RED)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return;
        }
        player.sendMessage(Component.text("\uD83D\uDC65 Declined the request from ")
                .color(NamedTextColor.YELLOW)
                .append(Component.text(name).color(NamedTextColor.AQUA))
                .append(Component.text(".").color(NamedTextColor.YELLOW)));
        activity.record(player.getUniqueId(), FriendActivityService.Type.REQUEST_DENIED, requester, null);
        notifications.requestDenied(requester, player.getUniqueId());
    }

    /** Cancels a request this player sent. */
    void cancel(Player player, UUID target) {
        String name = friends.nameOf(target);
        if (!requests.cancel(player.getUniqueId(), target)) {
            player.sendMessage(Component.text("\uD83D\uDC65 You have no outgoing request to ")
                    .color(NamedTextColor.RED)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return;
        }
        player.sendMessage(Component.text("\uD83D\uDC65 Cancelled your request to ")
                .color(NamedTextColor.YELLOW)
                .append(Component.text(name).color(NamedTextColor.AQUA))
                .append(Component.text(".").color(NamedTextColor.YELLOW)));
        activity.record(player.getUniqueId(), FriendActivityService.Type.REQUEST_CANCELLED, target, null);
    }

    /** Ends a friendship, from either side. */
    void remove(Player player, UUID other) {
        String name = friends.nameOf(other);
        if (!friends.removeFriend(player.getUniqueId(), other)) {
            player.sendMessage(Component.text("\uD83D\uDC65 ").color(NamedTextColor.RED)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text(" is not on your friends list.").color(NamedTextColor.RED)));
            return;
        }
        player.sendMessage(Component.text("\uD83D\uDC65 Removed ").color(NamedTextColor.YELLOW)
                .append(Component.text(name).color(NamedTextColor.AQUA))
                .append(Component.text(" from your friends.").color(NamedTextColor.YELLOW)));
        activity.record(player.getUniqueId(), FriendActivityService.Type.FRIEND_REMOVED, other, null);
        notifications.friendRemoved(other, player.getUniqueId());
        refreshBars(player.getUniqueId());
        refreshBars(other);
    }

    /** Redraws the sidebar of one player and of everyone who is friends with them. */
    private void refreshBars(UUID uuid) {
        Player player = uuid == null ? null : Bukkit.getPlayer(uuid);
        if (player != null) {
            plugin.sidebarScoreboard().refresh(player);
        }
        for (UUID friend : friends.friendsOf(uuid)) {
            Player online = Bukkit.getPlayer(friend);
            if (online != null) {
                plugin.sidebarScoreboard().refresh(online);
            }
        }
    }

    // ── Opening the menus ───────────────────────────────────────────────

    void openFriends(Player player) {
        new FriendsMenu(plugin, player, this).open(player);
    }

    void openProfile(Player player, UUID target) {
        new FriendProfileMenu(plugin, player, this, target).open(player);
    }

    void openRequests(Player player) {
        new FriendRequestsMenu(plugin, player, this).open(player);
    }

    void openMutual(Player player, UUID target) {
        new MutualFriendsMenu(plugin, player, this, target).open(player);
    }

    void openSettings(Player player) {
        PlayerSettings settings = plugin.getPlayerSettings();
        if (settings == null) {
            return;
        }
        new FriendSettingsMenu(plugin, player, settings).open(player);
    }

    /** Prints the activity feed to chat. */
    void printActivity(Player player) {
        List<FriendActivityService.Entry> feed = activity.feed(player.getUniqueId(), 12);
        player.sendMessage(Component.text("\uD83D\uDCDC Friend activity").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        if (feed.isEmpty()) {
            player.sendMessage(Component.text("  Nothing yet — requests, gifts and friends coming")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            player.sendMessage(Component.text("  online all show up here.")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            return;
        }
        for (FriendActivityService.Entry entry : feed) {
            player.sendMessage(Component.text("  \u2022 ").color(NamedTextColor.DARK_GRAY)
                    .append(activity.render(entry, player.getUniqueId()))
                    .append(Component.text("  " + FriendActivityService.when(entry.at()))
                            .color(NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
        }
    }

    private static Component hint(String first, String second) {
        return Component.text("\uD83D\uDC65 " + first + ", ").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(second).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
    }
}
