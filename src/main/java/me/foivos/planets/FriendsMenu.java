package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The {@code /friends} screen: every friend as their own head, online ones
 * first and favourites above even those, with the count in the header written
 * the same simple way the sidebar does — {@code 2/8}.
 *
 * <pre>
 *   🟦🟦🟦🟦👥🟦🟦🟦🟦          👥 Friends (2/8)
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦          rows 1-4: up to 28 friends a page
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ ⬆◀          ⬆ page back, ▶ page on
 *   🟦 📋 ★ 🟢 🔗 🔍 ✉ 📜 ⚙ ✖ 🟦   tabs, search, requests, feed, settings, close
 * </pre>
 *
 * <p>The same class also draws search results, because a search hit may be
 * somebody who is <i>not</i> a friend yet: clicking a friend opens their
 * profile, clicking a stranger sends them a request.
 */
public final class FriendsMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int PREVIOUS_SLOT = 18;
    private static final int NEXT_SLOT = 26;
    private static final int PER_PAGE = 28;   // 4 rows x 7 columns
    private static final int[] FRIEND_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private static final int TAB_ALL = 45;
    private static final int TAB_FAVORITES = 46;
    private static final int TAB_ONLINE = 47;
    private static final int TAB_MUTUAL = 48;
    private static final int SEARCH_SLOT = 49;
    private static final int REQUESTS_SLOT = 50;
    private static final int ACTIVITY_SLOT = 51;
    private static final int SETTINGS_SLOT = 52;
    private static final int CLOSE_SLOT = 53;

    private final Planets plugin;
    private final Player viewer;
    private final FriendSystem system;
    private final Inventory inventory;

    /** Explicit rows to show (search results), or null for the player's own list. */
    private final List<UUID> results;
    private final String overrideTitle;

    private FriendSystem.View view = FriendSystem.View.ALL;
    private int page;

    public FriendsMenu(Planets plugin, Player viewer, FriendSystem system) {
        this(plugin, viewer, system, null, null);
    }

    /** The search-results flavour: an explicit list, no view tabs. */
    public FriendsMenu(Planets plugin, Player viewer, FriendSystem system,
                       List<UUID> results, String title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.system = system;
        this.results = results == null ? null : List.copyOf(results);
        this.overrideTitle = title;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83D\uDC65 Friends").color(NamedTextColor.DARK_AQUA));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    /** The rows currently being drawn, in the order they are shown. */
    private List<UUID> rows() {
        if (results != null) {
            return results;
        }
        return system.orderedFriends(viewer.getUniqueId(), view);
    }

    private int maxPage() {
        return Math.max(0, (rows().size() - 1) / PER_PAGE);
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            if (page > 0) {
                page--;
                render();
            }
            return;
        }
        if (slot == NEXT_SLOT) {
            if (page < maxPage()) {
                page++;
                render();
            }
            return;
        }
        if (results == null && (slot == TAB_ALL || slot == TAB_FAVORITES
                || slot == TAB_ONLINE || slot == TAB_MUTUAL)) {
            view = slot == TAB_ALL ? FriendSystem.View.ALL
                    : slot == TAB_FAVORITES ? FriendSystem.View.FAVORITES
                    : slot == TAB_ONLINE ? FriendSystem.View.ONLINE
                    : FriendSystem.View.MUTUAL;
            page = 0;
            render();
            return;
        }
        if (slot == SEARCH_SLOT) {
            system.promptSearch(player);
            return;
        }
        if (slot == REQUESTS_SLOT) {
            player.closeInventory();
            system.openRequests(player);
            return;
        }
        if (slot == ACTIVITY_SLOT) {
            player.closeInventory();
            system.printActivity(player);
            return;
        }
        if (slot == SETTINGS_SLOT) {
            player.closeInventory();
            system.openSettings(player);
            return;
        }

        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return;
        }
        if (!(item.getItemMeta() instanceof SkullMeta skull) || skull.getOwningPlayer() == null) {
            return;
        }
        UUID target = skull.getOwningPlayer().getUniqueId();
        if (system.friends().areFriends(player.getUniqueId(), target)) {
            if (results == null && view == FriendSystem.View.MUTUAL) {
                player.closeInventory();
                system.openMutual(player, target);
                return;
            }
            player.closeInventory();
            system.openProfile(player, target);
            return;
        }
        // A stranger from a search: clicking them asks them to be friends.
        system.request(player, target);
        render();
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83D\uDC65 Friends", "\uD83C\uDF10 Online");
        inventory.setItem(INFO_SLOT, infoItem());

        List<UUID> rows = rows();
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < rows.size(); i++) {
            inventory.setItem(FRIEND_SLOTS[i], friendItem(rows.get(start + i)));
        }
        if (rows.isEmpty()) {
            inventory.setItem(22, emptyItem());
        }

        inventory.setItem(PREVIOUS_SLOT, pageItem("Previous Page", page > 0, Material.SPECTRAL_ARROW));
        inventory.setItem(NEXT_SLOT, pageItem("Next Page", page < maxPage(), Material.SPECTRAL_ARROW));

        if (results == null) {
            inventory.setItem(TAB_ALL, MenuStyle.tab(Material.PLAYER_HEAD, "All",
                    view == FriendSystem.View.ALL, "Every friend you have"));
            inventory.setItem(TAB_FAVORITES, MenuStyle.tab(Material.NETHER_STAR, "Favorites",
                    view == FriendSystem.View.FAVORITES, "Only the friends you pinned"));
            inventory.setItem(TAB_ONLINE, MenuStyle.tab(Material.LIME_DYE, "Online",
                    view == FriendSystem.View.ONLINE, "Only friends who are on right now"));
            inventory.setItem(TAB_MUTUAL, MenuStyle.tab(Material.COMPARATOR, "Mutual",
                    view == FriendSystem.View.MUTUAL,
                    "Pick a friend to see who you have in common"));
        } else {
            inventory.setItem(TAB_ALL, MenuStyle.label("\uD83D\uDD0E Results",
                    rows.size() + " match(es)",
                    "Click a friend for their profile",
                    "Click anyone else to add them"));
        }

        inventory.setItem(SEARCH_SLOT, MenuStyle.tab(Material.SPYGLASS, "Search", false,
                "Type a name in chat to look someone up"));
        int pending = system.requests().incomingCount(viewer.getUniqueId());
        inventory.setItem(REQUESTS_SLOT, MenuStyle.tab(Material.WRITABLE_BOOK, "Requests", false,
                pending == 0 ? "No requests waiting" : pending + " request(s) waiting",
                "Accept \u2714 or deny \u2716 from here"));
        inventory.setItem(ACTIVITY_SLOT, MenuStyle.tab(Material.KNOWLEDGE_BOOK, "Activity", false,
                "What you and your friends have been up to",
                "Printed to chat"));
        inventory.setItem(SETTINGS_SLOT, MenuStyle.tab(Material.COMPARATOR, "Settings", false,
                "The Friends page of /settings"));

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.displayName(Component.text("\u2716 Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        close.setItemMeta(closeMeta);
        inventory.setItem(CLOSE_SLOT, close);
    }

    private ItemStack infoItem() {
        UUID viewerId = viewer.getUniqueId();
        int online = system.onlineCount(viewerId);
        int total = system.friends().friendCount(viewerId);
        int favorites = system.friends().favoritesOf(viewerId).size();
        int pending = system.requests().incomingCount(viewerId);
        int sent = system.requests().outgoingCount(viewerId);

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(viewer);
        meta.displayName(Component.text("\uD83D\uDC65 Friends " + online + "/" + total)
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(online + " online \u00B7 " + total + " total", NamedTextColor.GREEN));
        lore.add(line(favorites + " favorited", NamedTextColor.GOLD));
        lore.add(line(pending + " incoming \u00B7 " + sent + " sent", NamedTextColor.YELLOW));
        lore.add(line("Gifts received: " + system.gifts().receivedCount(viewerId)
                + " \u00B7 sent: " + system.gifts().sentCount(viewerId)));
        lore.add(line(""));
        lore.add(line("Online friends first, then the rest"));
        lore.add(line("Favorites stay pinned to the top"));
        lore.add(line(""));
        lore.add(Component.text("Left-click a head for their profile")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("/friend <player> works too")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** One friend's head: status first, then the numbers behind them. */
    private ItemStack friendItem(UUID target) {
        UUID viewerId = viewer.getUniqueId();
        boolean isFriend = system.friends().areFriends(viewerId, target);
        boolean favorite = isFriend && system.friends().isFavorite(viewerId, target);
        FriendPresenceService.Status status =
                system.presence().statusOf(target, target.equals(viewerId));
        int mutual = isFriend ? system.friends().mutualCount(viewerId, target) : 0;
        String name = system.friends().nameOf(target);

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(target));
        meta.displayName(Component.text((favorite ? "\u2605 " : "") + status.dot() + " " + name)
                .color(isFriend ? NamedTextColor.AQUA : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(line(status.label(), status == FriendPresenceService.Status.ONLINE
                ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        if (status == FriendPresenceService.Status.ONLINE
                || status == FriendPresenceService.Status.AFK) {
            String where = system.presence().locationLabel(target);
            if (where != null) {
                lore.add(line("On: " + where, NamedTextColor.YELLOW));
            }
        } else if (status == FriendPresenceService.Status.HIDDEN) {
            lore.add(line("Their status is private", NamedTextColor.DARK_GRAY));
        } else {
            long seen = system.presence().lastSeen(target);
            lore.add(line(seen > 0 ? "Last seen: " + Planets.formatDate(seen) : "Last seen: unknown",
                    NamedTextColor.DARK_GRAY));
        }
        lore.add(line(""));
        if (isFriend) {
            lore.add(line("Mutual friends: " + mutual, NamedTextColor.AQUA));
            lore.add(line(favorite ? "Favorited" : "Not favorited",
                    favorite ? NamedTextColor.GOLD : NamedTextColor.GRAY));
            lore.add(line(""));
            lore.add(view == FriendSystem.View.MUTUAL
                    ? Component.text("Click to see your mutual friends")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    : Component.text("Click to open their profile")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(line("Not on your friends list yet", NamedTextColor.GRAY));
            lore.add(line(""));
            lore.add(Component.text("Click to send a friend request \u2714")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyItem() {
        boolean filtered = results == null && view != FriendSystem.View.ALL;
        ItemStack item = new ItemStack(Material.COBWEB);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(filtered ? "Nothing in this view" : "No friends yet")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (filtered) {
            lore.add(line("Try the All tab, or search for someone"));
        } else {
            lore.add(line("Use /friend <player> or Search to"));
            lore.add(line("send somebody a friend request."));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageItem(String name, boolean active, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)
                .color(active ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        int total = rows().size();
        lore.add(line("Page " + (page + 1) + "/" + (maxPage() + 1)
                + " \u00B7 " + total + " friend(s)", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String text) {
        return line(text, NamedTextColor.GRAY);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }
}
