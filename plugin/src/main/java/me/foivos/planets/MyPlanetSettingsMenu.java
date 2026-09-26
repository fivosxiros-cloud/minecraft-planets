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

import java.util.ArrayList;
import java.util.List;

/**
 * Planet properties menu for /myp → Settings.
 * Toggleable properties: PvP, Build, Mob Spawning, Explosions, Fire Spread,
 * Visitor Access, Item Drops, Public/Private.
 */
public final class MyPlanetSettingsMenu implements InventoryHolder {

    private static final int SIZE = 27; // 3 rows
    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final Inventory inventory;

    public MyPlanetSettingsMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("⚙ " + data.displayName() + " Settings").color(NamedTextColor.DARK_PURPLE));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!data.canManage(player.getUniqueId())) {
            player.sendMessage(Component.text("Only owner or co-owner can change settings.").color(NamedTextColor.RED));
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        switch (slot) {
            case 10 -> toggle(player, "PvP", () -> data.pvpEnabled(!data.pvpEnabled()));
            case 11 -> toggle(player, "Build", () -> data.buildEnabled(!data.buildEnabled()));
            case 12 -> toggle(player, "Mob Spawning", () -> data.mobSpawning(!data.mobSpawning()));
            case 13 -> toggle(player, "Explosions", () -> data.explosions(!data.explosions()));
            case 14 -> toggle(player, "Fire Spread", () -> data.fireSpread(!data.fireSpread()));
            case 15 -> toggle(player, "Visitor Access", () -> data.visitorAccess(!data.visitorAccess()));
            case 16 -> toggle(player, "Chest & Door Access", () -> data.chestDoorAccess(!data.chestDoorAccess()));
            case 17 -> toggle(player, "Item Drops", () -> data.itemDrops(!data.itemDrops()));
            case 18 -> toggle(player, "Public", () -> data.isPublic(!data.isPublic()));
            case 21 -> toggle(player, "Structures", () -> data.structures(!data.structures()));
            case 22 -> handleWeather(player);
            case 23 -> handleSellClick(player);
            case 26 -> { // Back
                player.closeInventory();
                new MyPlanetMenu(plugin, player, data).open(player);
            }
        }
    }

    /** Cycles the planet's locked weather (normal → clear → rain → thunder → normal). */
    private void handleWeather(Player player) {
        plugin.cyclePlanetWeather(player, data);
        player.closeInventory();
        new MyPlanetSettingsMenu(plugin, player, data).open(player);
    }

    private void toggle(Player player, String label, Runnable action) {
        action.run();
        plugin.getMyPlanetManager().save();
        player.closeInventory();
        new MyPlanetSettingsMenu(plugin, player, data).open(player);
    }

    private void render() {
        inventory.clear();
        // Ornate frame (gold corners + edges) around the crimson field.
        MenuStyle.decorate(inventory, "⚙ Planet", "🎛 Toggles");
        // Row 1: toggles
        inventory.setItem(10, toggleItem(Material.IRON_SWORD, "PvP", data.pvpEnabled()));
        inventory.setItem(11, toggleItem(Material.IRON_PICKAXE, "Build", data.buildEnabled()));
        inventory.setItem(12, toggleItem(Material.ZOMBIE_HEAD, "Mob Spawning", data.mobSpawning()));
        inventory.setItem(13, toggleItem(Material.TNT, "Explosions", data.explosions()));
        inventory.setItem(14, toggleItem(Material.FLINT_AND_STEEL, "Fire Spread", data.fireSpread()));
        inventory.setItem(15, toggleItem(Material.OAK_DOOR, "Visitor Access", data.visitorAccess()));
        inventory.setItem(16, toggleItem(Material.CHEST, "Chest & Door Access", data.chestDoorAccess()));
        inventory.setItem(17, toggleItem(Material.SLIME_BALL, "Item Drops", data.itemDrops()));
        inventory.setItem(18, toggleItem(Material.ENDER_EYE, "Public", data.isPublic()));
        inventory.setItem(21, toggleItem(Material.OAK_SAPLING, "Structures", data.structures()));
        inventory.setItem(22, weatherItem());
        inventory.setItem(23, sellItem());
        inventory.setItem(26, backItem());
    }

    /** Planet weather: click to cycle the locked-in weather. */
    private ItemStack weatherItem() {
        String mode = plugin.effectivePlanetWeather(data);
        Material material = switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "clear" -> Material.SUNFLOWER;
            case "rain" -> Material.WATER_BUCKET;
            case "thunder" -> Material.LIGHTNING_ROD;
            default -> Material.CLOCK;
        };
        String next = switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "clear" -> "rain";
            case "rain" -> "thunder";
            case "thunder" -> "normal weather";
            default -> "clear";
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2601 Weather: " + Planets.weatherLabel(mode))
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Normal lets the weather change on its own")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Clear / rain / thunder hold one weather")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Click for: " + next).color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack toggleItem(Material material, String label, boolean enabled) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((enabled ? "\u2705 " : "\u274C ") + label)
                .color(enabled ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Current: " + (enabled ? "ON" : "OFF")).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to toggle").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        return MenuStyle.field();
    }

    private void handleSellClick(Player player) {
        if (!data.ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner can sell the planet.").color(NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        if (data.forSale()) {
            data.removeFromSale();
            plugin.getMyPlanetManager().save();
            player.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" removed from sale.").color(NamedTextColor.GREEN)));
        } else {
            player.sendMessage(Component.text("\u2014 \uD83D\uDCB0 Put ").color(NamedTextColor.GOLD)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" Up for Sale \u2014").color(NamedTextColor.GOLD)));
            player.sendMessage(Component.text("Enter a price in VPL (e.g. 500) or type \"cancel\" to abort.").color(NamedTextColor.GRAY));
            plugin.setPendingSell(player, data.worldName());
        }
    }

    private ItemStack sellItem() {
        boolean isOwner = data.ownerUuid().equals(viewer.getUniqueId());
        if (!isOwner) {
            ItemStack locked = new ItemStack(Material.GRAY_DYE);
            ItemMeta meta = locked.getItemMeta();
            meta.displayName(Component.text("Sell Planet").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Only the owner can sell").color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            locked.setItemMeta(meta);
            return locked;
        }
        if (data.forSale()) {
            ItemStack item = new ItemStack(Material.GREEN_TERRACOTTA);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text("\uD83D\uDCB0 For Sale: " + Planets.formatPrice(data.salePrice()) + " VPL")
                    .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Click to remove from sale").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
            return item;
        }
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDCB0 Put Up for Sale")
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Open seller prompt to set price").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("← Back").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
