package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One player's homes for an admin, opened from {@link AdminHomesMenu}: each
 * home is a bed that teleports the admin to it, shift-click deletes it (asking
 * twice), and a red button at the bottom wipes the player's homes completely.
 *
 * <pre>
 *   ┌──────── summary (4) ────────┐
 *   🛏 home 1 … up to six beds
 *   🗑 Delete one / Delete all …
 *   └──── Back to Homes (40) ─────┘
 * </pre>
 */
public final class AdminPlayerHomesMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int SUMMARY_SLOT = 4;
    private static final int[] HOME_SLOTS = {10, 11, 12, 13, 14, 15};
    private static final int HELP_SLOT = 21;
    private static final int DELETE_ALL_SLOT = 23;
    private static final int ALL_HOMES_SLOT = 37;
    private static final int DASHBOARD_SLOT = 39;
    private static final int BACK_SLOT = 40;

    private final Planets plugin;
    private final Player viewer;
    private final UUID ownerUuid;
    private final Inventory inventory;

    /** Name of the home waiting for a confirming shift-click, or null. */
    private String armedDelete;
    /** Whether the "delete all" button has been clicked once already. */
    private boolean confirmDeleteAll;

    public AdminPlayerHomesMenu(Planets plugin, Player viewer, UUID ownerUuid) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.ownerUuid = ownerUuid;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\u2699 Homes: " + plugin.homesOwnerName(ownerUuid))
                        .color(NamedTextColor.DARK_AQUA));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !plugin.canUseAdmin(player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }

        switch (slot) {
            case BACK_SLOT, ALL_HOMES_SLOT -> {
                plugin.openAdminHomes(player);
                return;
            }
            case DASHBOARD_SLOT -> {
                plugin.openAdminDashboard(player);
                return;
            }
            case HELP_SLOT -> {
                printCommands();
                return;
            }
            case DELETE_ALL_SLOT -> {
                armedDelete = null;
                if (!confirmDeleteAll) {
                    confirmDeleteAll = true;
                    render();
                    return;
                }
                confirmDeleteAll = false;
                plugin.deleteAllPlayerHomes(player, ownerUuid);
                render();
                return;
            }
            default -> {
            }
        }

        confirmDeleteAll = false;
        int index = -1;
        for (int i = 0; i < HOME_SLOTS.length; i++) {
            if (HOME_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        List<HomeManager.Home> homes = plugin.getHomeManager().homes(ownerUuid);
        if (index >= homes.size()) {
            return;
        }
        HomeManager.Home home = homes.get(index);

        if (event.isShiftClick()) {
            if (armedDelete != null && armedDelete.equalsIgnoreCase(home.name())) {
                armedDelete = null;
                plugin.deletePlayerHome(player, ownerUuid, home.name());
                render();
                return;
            }
            armedDelete = home.name();
            render();
            return;
        }
        armedDelete = null;
        plugin.teleportAdminToHome(player, ownerUuid, home);
    }

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83C\uDFE0 Homes", "\uD83D\uDD27 Admin");

        inventory.setItem(SUMMARY_SLOT, summaryItem());

        List<HomeManager.Home> homes = plugin.getHomeManager().homes(ownerUuid);
        for (int i = 0; i < HOME_SLOTS.length; i++) {
            HomeManager.Home home = i < homes.size() ? homes.get(i) : null;
            if (home != null) {
                inventory.setItem(HOME_SLOTS[i], homeItem(home));
            }
        }

        inventory.setItem(HELP_SLOT, actionItem(Material.WRITABLE_BOOK, "Command Reference",
                "Prints the equivalent commands in chat"));
        inventory.setItem(DELETE_ALL_SLOT, deleteAllItem(homes.isEmpty()));
        inventory.setItem(ALL_HOMES_SLOT, actionItem(Material.PLAYER_HEAD, "All Players",
                "Back to the list of everyone with homes"));
        inventory.setItem(DASHBOARD_SLOT, actionItem(Material.COMPARATOR, "Dashboard",
                "Server-wide overview, cleanup and the planet list"));
        inventory.setItem(BACK_SLOT, actionItem(Material.SPECTRAL_ARROW, "Back to Homes",
                "Leave this player's homes"));
    }

    private ItemStack summaryItem() {
        int count = plugin.getHomeManager().count(ownerUuid);
        Player online = Bukkit.getPlayer(ownerUuid);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(ownerUuid));
            item.setItemMeta(skull);
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(plugin.homesOwnerName(ownerUuid)).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(count + " home(s) saved"));
        lore.add(line(online != null ? "Online now" : "Offline",
                online != null ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        lore.add(line("Click a bed to teleport there"));
        lore.add(line("Shift-click a bed to delete it (asks twice)"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack homeItem(HomeManager.Home home) {
        boolean armed = home.name().equalsIgnoreCase(armedDelete);
        World world = Bukkit.getWorld(home.world());
        ItemStack item = new ItemStack(armed ? Material.RED_BED
                : plugin.getHomeManager().bedFor(ownerUuid, home));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((armed ? "\uD83D\uDDD1 Delete " : "\uD83D\uDECF ") + home.name())
                .color(armed ? NamedTextColor.RED : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(world == null ? "(world not loaded)" : home.describe(),
                world == null ? NamedTextColor.RED : NamedTextColor.GRAY));
        lore.add(line("Bed colour: " + HomeManager.colourName(
                plugin.getHomeManager().bedFor(ownerUuid, home))));
        lore.add(line(""));
        if (armed) {
            lore.add(Component.text("Shift-click again to delete it — the player loses it too")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Click: teleport here").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Shift-click: delete this home").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack deleteAllItem(boolean empty) {
        ItemStack item = new ItemStack(confirmDeleteAll ? Material.REDSTONE_BLOCK : Material.LAVA_BUCKET);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(confirmDeleteAll
                        ? "\uD83D\uDDD1 Click again to delete them all"
                        : "\uD83D\uDDD1 Delete All Homes")
                .color(confirmDeleteAll ? NamedTextColor.RED : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(empty ? "This player has no homes saved" : "Removes every home this player saved"));
        lore.add(line("They are told nothing — this is silent", NamedTextColor.DARK_GRAY));
        if (!empty) {
            lore.add(Component.text("Needs two clicks").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Prints the chat commands that do the same as this panel. */
    private void printCommands() {
        String name = plugin.homesOwnerName(ownerUuid);
        viewer.sendMessage(Component.text("\u2699 Homes of " + name + " — commands").color(NamedTextColor.AQUA));
        viewer.sendMessage(Component.text(" \u2022 ").color(NamedTextColor.DARK_GRAY)
                .append(Component.text("/home <name>").color(NamedTextColor.YELLOW))
                .append(Component.text(" — as the player, teleports to their own home").color(NamedTextColor.GRAY)));
        viewer.sendMessage(Component.text(" \u2022 ").color(NamedTextColor.DARK_GRAY)
                .append(Component.text("/delhome <name>").color(NamedTextColor.YELLOW))
                .append(Component.text(" — as the player, deletes one of their homes").color(NamedTextColor.GRAY)));
        for (HomeManager.Home home : plugin.getHomeManager().homes(ownerUuid)) {
            viewer.sendMessage(Component.text("   " + home.name() + ": ").color(NamedTextColor.AQUA)
                    .append(Component.text(home.describe()).color(NamedTextColor.GRAY)));
        }
        viewer.sendMessage(Component.text("Teleporting and deleting from this panel work without the player online.")
                .color(NamedTextColor.DARK_GRAY));
    }

    private static Component line(String text) {
        return line(text, NamedTextColor.GRAY);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack actionItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String text : loreLines) {
            lore.add(line(text));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
