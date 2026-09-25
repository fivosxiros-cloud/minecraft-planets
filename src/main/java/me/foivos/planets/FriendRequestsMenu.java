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
 * The Requests section of the friend system: who is waiting on you, and who you
 * are waiting on.
 *
 * <p>Answering is a click, exactly like the buttons in chat. On a request that
 * came <b>in</b>, left-click is <b>✔ accept</b> and right-click is
 * <b>✖ deny</b>. On one you sent, left-click withdraws it. Incoming requests
 * are listed above outgoing ones, oldest first, so the one that has been waiting
 * longest is the first head in the menu.
 */
public final class FriendRequestsMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int PREVIOUS_SLOT = 18;
    private static final int NEXT_SLOT = 26;
    private static final int BACK_SLOT = 45;
    private static final int CLOSE_SLOT = 53;
    private static final int PER_PAGE = 28;
    private static final int[] REQUEST_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Planets plugin;
    private final Player viewer;
    private final FriendSystem system;
    private final Inventory inventory;
    private int page;

    public FriendRequestsMenu(Planets plugin, Player viewer, FriendSystem system) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.system = system;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\u2709 Friend Requests").color(NamedTextColor.DARK_AQUA));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    /** Incoming first (oldest first), then the requests this player sent. */
    private List<RequestRow> rows() {
        List<RequestRow> rows = new ArrayList<>();
        UUID viewerId = viewer.getUniqueId();
        for (UUID requester : system.requests().incoming(viewerId)) {
            rows.add(new RequestRow(requester, true));
        }
        for (UUID target : system.requests().outgoing(viewerId)) {
            rows.add(new RequestRow(target, false));
        }
        return rows;
    }

    private record RequestRow(UUID other, boolean incoming) {
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
        if (slot == BACK_SLOT) {
            player.closeInventory();
            system.openFriends(player);
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

        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return;
        }
        if (!(item.getItemMeta() instanceof SkullMeta skull) || skull.getOwningPlayer() == null) {
            return;
        }
        UUID other = skull.getOwningPlayer().getUniqueId();
        boolean incoming = system.requests().incoming(player.getUniqueId()).contains(other);
        if (incoming) {
            if (event.isRightClick()) {
                system.deny(player, other);
            } else {
                system.accept(player, other);
            }
        } else if (system.requests().outgoing(player.getUniqueId()).contains(other)) {
            system.cancel(player, other);
        }
        render();
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\u2709 Requests", "\uD83D\uDC65 Friends");
        inventory.setItem(INFO_SLOT, infoItem());

        List<RequestRow> rows = rows();
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < rows.size(); i++) {
            inventory.setItem(REQUEST_SLOTS[i], requestItem(rows.get(start + i)));
        }
        if (rows.isEmpty()) {
            inventory.setItem(22, emptyItem());
        }

        inventory.setItem(PREVIOUS_SLOT, pageItem("Previous Page", page > 0));
        inventory.setItem(NEXT_SLOT, pageItem("Next Page", page < maxPage()));
        inventory.setItem(BACK_SLOT, MenuStyle.tab(Material.PLAYER_HEAD, "Your Friends", false,
                "Back to the friends list"));
        inventory.setItem(CLOSE_SLOT, MenuStyle.tab(Material.BARRIER, "Close", false,
                "Back to the game"));
    }

    private ItemStack infoItem() {
        UUID viewerId = viewer.getUniqueId();
        int incoming = system.requests().incomingCount(viewerId);
        int outgoing = system.requests().outgoingCount(viewerId);
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2709 Friend Requests").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(incoming + " waiting for your answer", incoming > 0
                ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        lore.add(line(outgoing + " sent by you", NamedTextColor.GRAY));
        lore.add(line(""));
        lore.add(line("Left-click a request you received: accept \u2714"));
        lore.add(line("Right-click it: deny \u2716"));
        lore.add(line("Left-click one you sent: cancel it"));
        lore.add(line(""));
        lore.add(line("Requests expire after "
                + (system.requests().expiryMillis() / 3_600_000L) + "h", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack requestItem(RequestRow row) {
        UUID viewerId = viewer.getUniqueId();
        String name = system.friends().nameOf(row.other());
        FriendPresenceService.Status status = system.presence().statusOf(row.other());
        long at = row.incoming()
                ? 0L : system.requests().sentAt(viewerId, row.other());

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(row.other()));
        meta.displayName(Component.text((row.incoming() ? "\u2709 " : "\u23F3 ")
                        + name)
                .color(row.incoming() ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(row.incoming()
                ? "Wants to be your friend"
                : "Waiting for them to answer", row.incoming()
                ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        lore.add(line("Status: " + status.label(), NamedTextColor.GRAY));
        if (!row.incoming() && at > 0) {
            lore.add(line("Sent " + Planets.formatDate(at), NamedTextColor.DARK_GRAY));
        }
        lore.add(line(""));
        if (row.incoming()) {
            lore.add(Component.text("\u2714 Left-click to accept")
                    .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("\u2716 Right-click to deny")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("\u2716 Left-click to cancel the request")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyItem() {
        ItemStack item = new ItemStack(Material.COBWEB);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("No requests waiting").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Ask someone with /friend <player>"));
        lore.add(line("Their answer shows up here"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageItem(String name, boolean active) {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)
                .color(active ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Page " + (page + 1) + "/" + (maxPage() + 1)
                + " \u00B7 " + rows().size() + " request(s)", NamedTextColor.DARK_GRAY));
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
