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
 * Your friends, on this planet's roster: {@code /myp → 🤝 Friends}.
 *
 * <p>Every friend is one head — green when they're already a member, yellow
 * while an invitation is waiting, aqua when they could be invited. Clicking an
 * aqua head sends them an invitation, so building a planet's member list from
 * people you actually play with is one pass through this screen.
 *
 * <pre>
 *   ▣ ▣ ▣ ▣ 👥 ▣ ▣ ▣ ▣
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣        up to 28 friends a page
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   ⬅ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▶          ⬅ page back, ▶ page on
 *   🪐 ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ✖        back to the planet, close
 * </pre>
 */
public final class MyPlanetFriendsMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int PREVIOUS_SLOT = 18;
    private static final int NEXT_SLOT = 26;
    private static final int BACK_SLOT = 45;
    private static final int CLOSE_SLOT = 53;
    private static final int PER_PAGE = 28;
    private static final int[] FRIEND_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final Inventory inventory;
    private int page;

    public MyPlanetFriendsMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83E\uDD1D " + data.displayName() + " \u2014 Friends")
                        .color(NamedTextColor.DARK_PURPLE));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    private List<UUID> rows() {
        if (plugin.friendSystem() == null) {
            return List.of();
        }
        return plugin.friendSystem().orderedFriends(viewer.getUniqueId(), FriendSystem.View.ALL);
    }

    private int maxPage() {
        return Math.max(0, (rows().size() - 1) / PER_PAGE);
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

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
        if (slot == BACK_SLOT) {
            player.closeInventory();
            new MyPlanetMenu(plugin, player, data).open(player);
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
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
        if (data.isMember(target)) {
            player.sendMessage(Component.text(MyPlanetPermissionsMenu.memberName(target))
                    .color(NamedTextColor.YELLOW)
                    .append(Component.text(" is already a member of ").color(NamedTextColor.RED))
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return;
        }
        if (data.isInvited(target)) {
            player.sendMessage(Component.text(MyPlanetPermissionsMenu.memberName(target))
                    .color(NamedTextColor.YELLOW)
                    .append(Component.text(" already has a pending invitation.").color(NamedTextColor.RED)));
            return;
        }
        // Shared with /myp invite and the invite picker: it checks the role, the
        // per-player Invite permission and the member capacity.
        plugin.inviteToPlanet(player, data, target, MyPlanetPermissionsMenu.memberName(target));
        render();
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, filler());
        }
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
        inventory.setItem(BACK_SLOT, actionItem(Material.ARROW, "\u2190 Back to the planet",
                "Returns to " + data.displayName()));
        inventory.setItem(CLOSE_SLOT, actionItem(Material.BARRIER, "\u2716 Close", "Closes the menu"));
    }

    private ItemStack infoItem() {
        int members = data.totalMembers();
        int friends = rows().size();
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(viewer);
        meta.displayName(Component.text("\uD83E\uDD1D " + data.displayName() + " \u2014 Friends")
                .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(friends + " friend(s) on your list").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(members + "/" + data.memberCapacity() + " member slots used")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Click a friend to invite them").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Manage them from /friends").color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack friendItem(UUID target) {
        boolean member = data.isMember(target);
        boolean invited = data.isInvited(target);
        String name = plugin.friendSystem() == null ? MyPlanetPermissionsMenu.memberName(target)
                : plugin.friendSystem().friends().nameOf(target);
        FriendPresenceService.Status status = plugin.friendSystem() == null
                ? FriendPresenceService.Status.OFFLINE
                : plugin.friendSystem().presence().statusOf(target, false);

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(target));
        NamedTextColor colour = member ? NamedTextColor.GREEN
                : invited ? NamedTextColor.YELLOW : NamedTextColor.AQUA;
        meta.displayName(Component.text(status.dot() + " " + name).color(colour)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(member ? "Member of this planet"
                        : invited ? "Invitation pending" : "Not on this planet yet")
                .color(colour).decoration(TextDecoration.ITALIC, false));
        MyPlanetData.Role role = data.roleOf(target);
        if (role != null) {
            lore.add(Component.text("Role: " + role.name()).color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("").decoration(TextDecoration.ITALIC, false));
        if (member) {
            lore.add(Component.text("Already in — nothing to send").color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        } else if (invited) {
            lore.add(Component.text("Waiting for them to accept").color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Click to invite them").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyItem() {
        ItemStack item = new ItemStack(Material.COBWEB);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("No friends on your list yet").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Add some with /friend <player>, then come back")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageItem(String name, boolean active, Material material) {
        ItemStack item = new ItemStack(active ? material : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)
                .color(active ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Page " + (page + 1) + "/" + (maxPage() + 1))
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack actionItem(Material material, String name, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(description).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        return MenuStyle.field();
    }
}
