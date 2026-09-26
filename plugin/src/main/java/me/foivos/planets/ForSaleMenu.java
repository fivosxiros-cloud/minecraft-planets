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
import java.util.Locale;

/**
 * Shows all planets that are currently for sale. Each planet is displayed with
 * its icon, name, owner, and price. Clicking a planet opens a sub-menu where
 * the player can choose to Visit (teleport) or Buy (purchase ownership).
 *
 * Layout (27-slot chest):
 * <pre>
 *   Row 0-1: for-sale planet items (up to 18)
 *   Row 2:   gray glass pane filler + close button (slot 26)
 * </pre>
 */
public final class ForSaleMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int CLOSE_SLOT = 26;

    private final Planets plugin;
    private final Player viewer;
    private final List<MyPlanetData> planets;
    private final Inventory inventory;

    public ForSaleMenu(Planets plugin, Player viewer, List<MyPlanetData> planets) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.planets = planets;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83D\uDCC8 Planets for Sale").color(NamedTextColor.GREEN));
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

        if (slot == CLOSE_SLOT) {
            plugin.openMyPlanetSelect(player);
            return;
        }

        if (slot < planets.size()) {
            MyPlanetData data = planets.get(slot);
            player.closeInventory();
            new ForSaleConfirmMenu(plugin, player, data).open(player);
        }
    }

    private void render() {
        inventory.clear();

        int slot = 0;
        for (MyPlanetData data : planets) {
            if (slot >= 18) break;
            inventory.setItem(slot++, planetItem(data));
        }

        // Fill remaining slots with gray glass panes
        for (int i = slot; i < SIZE; i++) {
            inventory.setItem(i, fillerItem());
        }

        // Close button
        inventory.setItem(CLOSE_SLOT, closeItem());
    }

    private ItemStack planetItem(MyPlanetData data) {
        Material material = plugin.iconOverride(data.worldName());
        if (material == null) material = Material.GRASS_BLOCK;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(data.displayName()).color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));

        String ownerName = playerName(data.ownerUuid());
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Owner: " + ownerName).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Price: " + Planets.formatPrice(data.salePrice()) + " VPL").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));

        World world = Bukkit.getWorld(data.worldName());
        if (world != null) {
            lore.add(Component.text(world.getPlayers().size() + " player(s) here").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("World not loaded").color(NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Click to visit or buy").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack closeItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2190 Back to My Planets").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack fillerItem() {
        return MenuStyle.field();
    }

    private static String playerName(java.util.UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }
}
