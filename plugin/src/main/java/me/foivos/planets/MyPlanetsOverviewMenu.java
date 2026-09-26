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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The /myp home screen: one head/icon per planet the player owns or is a
 * member of, click to open that planet's control panel. Pinned helpers:
 * a "Claim a planet" guide button and the player's own head (ownership
 * status: how many of the allowed planets they already own).
 */
public final class MyPlanetsOverviewMenu implements InventoryHolder {

    private static final int SIZE = 36;
    private final Planets plugin;
    private final Player viewer;
    private final List<MyPlanetData> planets;
    private final Inventory inventory;

    public MyPlanetsOverviewMenu(Planets plugin, Player viewer, List<MyPlanetData> planets) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.planets = planets;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("🪐 My Planets").color(NamedTextColor.DARK_PURPLE));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (slot == 31) { // back / close
            player.closeInventory();
            return;
        }
        if (slot == 29) { // refresh
            player.closeInventory();
            plugin.openMyPlanetSelect(player);
            return;
        }
        if (slot == 30) { // emerald -> open /p planets menu
            player.closeInventory();
            plugin.openPlanetsMenu(player);
            return;
        }
        if (slot == 35) { // planets for sale
            plugin.openForSaleMenu(player);
            return;
        }
        if (slot < 27 && planets.size() > slot) {
            MyPlanetData data = planets.get(slot);
            plugin.openMyPlanetMenu(player, data);
        }
    }

    private void render() {
        inventory.clear();

        int slot = 0;
        for (MyPlanetData data : planets) {
            if (slot >= 27) break;
            inventory.setItem(slot++, planetItem(data));
        }
        for (int i = slot; i < 27; i++) {
            inventory.setItem(i, filler());
        }

        // Bottom row: gold-framed "tab row" — status, planets, close, for-sale.
        for (int i = 27; i < SIZE; i++) {
            inventory.setItem(i, lightBlueFiller());
        }
        inventory.setItem(27, ownershipItem());
        inventory.setItem(29, refreshItem());
        inventory.setItem(30, planetsMenuItem());
        inventory.setItem(31, closeItem());
        inventory.setItem(35, forSaleItem());
        for (int i = 28; i < SIZE; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, lightBlueFiller());
        }
    }

    private ItemStack planetItem(MyPlanetData data) {
        boolean owner = data.ownerUuid().equals(viewer.getUniqueId());
        Material material = plugin.iconOverride(data.worldName());
        if (material == null) material = Material.GRASS_BLOCK;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(data.displayName())
                .color(owner ? NamedTextColor.GOLD : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        String ownerName = playerName(data.ownerUuid());
        org.bukkit.World world = Bukkit.getWorld(data.worldName());
        int liveCount = world != null ? world.getPlayers().size() : 0;
        meta.lore(List.of(
                Component.text(owner ? "Owned by you" : "Owner: " + ownerName).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Status: " + data.status().name()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Members: " + data.totalMembers() + "/" + data.memberCapacity()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Online: " + liveCount + " player(s)").color(liveCount > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to manage").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack ownershipItem() {
        int owned = plugin.getMyPlanetManager().ownedCount(viewer.getUniqueId());
        int max = plugin.getMyPlanetManager().maxPlanetsPerPlayer();
        ItemStack item = new ItemStack(Material.BEACON);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("🪐 " + owned + "/" + max + " planets owned")
                .color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Claim more in unclaimed worlds").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack planetsMenuItem() {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("🌍 Planets").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Open the /planets menu").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to browse & teleport").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack forSaleItem() {
        int count = plugin.getMyPlanetManager().forSale().size();
        ItemStack item = new ItemStack(Material.GRASS_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDCC8 Planets for Sale").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(count + " planet(s) on the market").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to browse").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack refreshItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u21BB Refresh").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Reloads the planet list").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack closeItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("✖ Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack lightBlueFiller() {
        return MenuStyle.frame();
    }

    private static ItemStack filler() {
        return MenuStyle.field();
    }

    private static String playerName(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }
}
