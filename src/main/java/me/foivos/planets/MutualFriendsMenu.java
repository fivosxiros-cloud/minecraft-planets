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
 * The friends two players have in common, reached from a profile's Mutual
 * Friends button or from the Mutual tab of the friends list.
 *
 * <p>Clicking one of the shared friends opens <i>their</i> profile, so the menu
 * doubles as a way to hop sideways through the social graph rather than only
 * looking at it.
 */
public final class MutualFriendsMenu implements InventoryHolder {

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
    private final FriendSystem system;
    private final UUID target;
    private final List<UUID> mutual;
    private final Inventory inventory;
    private int page;

    public MutualFriendsMenu(Planets plugin, Player viewer, FriendSystem system, UUID target) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.system = system;
        this.target = target;
        this.mutual = new ArrayList<>(system.friends().mutualFriends(viewer.getUniqueId(), target));
        this.mutual.sort((left, right) -> system.friends().nameOf(left)
                .compareToIgnoreCase(system.friends().nameOf(right)));
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83D\uDD17 Mutual Friends").color(NamedTextColor.DARK_AQUA));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    private int maxPage() {
        return Math.max(0, (mutual.size() - 1) / PER_PAGE);
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
            system.openProfile(player, target);
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
        player.closeInventory();
        system.openProfile(player, skull.getOwningPlayer().getUniqueId());
    }

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83D\uDD17 Mutual", "\uD83D\uDC65 Friends");
        inventory.setItem(INFO_SLOT, infoItem());

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < mutual.size(); i++) {
            inventory.setItem(FRIEND_SLOTS[i], friendItem(mutual.get(start + i)));
        }
        if (mutual.isEmpty()) {
            inventory.setItem(22, emptyItem());
        }

        inventory.setItem(PREVIOUS_SLOT, pageItem("Previous Page", page > 0));
        inventory.setItem(NEXT_SLOT, pageItem("Next Page", page < maxPage()));
        inventory.setItem(BACK_SLOT, MenuStyle.tab(Material.PLAYER_HEAD, "Back", false,
                "Return to " + system.friends().nameOf(target) + "'s profile"));
        inventory.setItem(CLOSE_SLOT, MenuStyle.tab(Material.BARRIER, "Close", false,
                "Back to the game"));
    }

    private ItemStack infoItem() {
        String name = system.friends().nameOf(target);
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDD17 You & " + name).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(mutual.size() + " friend(s) in common", NamedTextColor.GREEN));
        lore.add(line("Both of you have these on your lists"));
        lore.add(line(""));
        lore.add(Component.text("Click a head to open their profile")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack friendItem(UUID friend) {
        FriendPresenceService.Status status = system.presence().statusOf(friend);
        String name = system.friends().nameOf(friend);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(friend));
        meta.displayName(Component.text(status.dot() + " " + name).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(status.label(), status == FriendPresenceService.Status.ONLINE
                ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        lore.add(line("Friend of both you and " + system.friends().nameOf(target)));
        lore.add(line(""));
        lore.add(Component.text("Click to open their profile")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyItem() {
        ItemStack item = new ItemStack(Material.COBWEB);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("No mutual friends yet").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("You and " + system.friends().nameOf(target)));
        lore.add(line("don't share any friends yet."));
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
        lore.add(line("Page " + (page + 1) + "/" + (maxPage() + 1), NamedTextColor.DARK_GRAY));
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
