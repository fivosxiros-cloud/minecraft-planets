package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Confirmation sub-menu for a single for-sale planet. Shows the planet's name,
 * price, and two actions:
 * <pre>
 *   Slot 11: Blue stained glass — Visit (teleport to the planet).
 *   Slot 15: Green stained glass — Buy (purchase ownership).
 *   Slot 26: Barrier — close / go back.
 * </pre>
 */
public final class ForSaleConfirmMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int VISIT_SLOT = 11;
    private static final int BUY_SLOT = 15;
    private static final int CLOSE_SLOT = 26;

    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final Inventory inventory;

    public ForSaleConfirmMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text(data.displayName() + " — For Sale").color(NamedTextColor.GOLD));
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

        if (slot == VISIT_SLOT) {
            player.closeInventory();
            if (data.lastSeller() != null && data.lastSeller().equals(player.getUniqueId())) {
                player.sendMessage(Component.text("You can't visit a planet you sold.").color(NamedTextColor.RED));
                return;
            }
            if (data.ownerUuid().equals(player.getUniqueId())) {
                player.sendMessage(Component.text("You already own this planet.").color(NamedTextColor.RED));
                return;
            }
            visitPlanet(player);
            return;
        }

        if (slot == BUY_SLOT) {
            player.closeInventory();
            buyPlanet(player);
            return;
        }

        if (slot == CLOSE_SLOT) {
            plugin.openForSaleMenu(player);
        }
    }

    private void visitPlanet(Player player) {
        World world = Bukkit.getWorld(data.worldName());
        if (world == null) {
            player.sendMessage(Component.text("That planet's world isn't loaded right now.").color(NamedTextColor.RED));
            return;
        }
        // Owner and the player who sold it can never visit through this menu.
        if (data.ownerUuid().equals(player.getUniqueId())
                || (data.lastSeller() != null && data.lastSeller().equals(player.getUniqueId()))) {
            player.sendMessage(Component.text("You can't visit a planet you own or sold.").color(NamedTextColor.RED));
            return;
        }
        Location spawn = world.getSpawnLocation();
        int radius = 5;
        int cx = spawn.getBlockX() >> 4;
        int cz = spawn.getBlockZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.getChunkAt(cx + dx, cz + dz);
            }
        }
        player.teleport(spawn);
        player.sendMessage(Component.text("Teleported to ").color(NamedTextColor.GREEN)
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    private void buyPlanet(Player player) {
        plugin.buyPlanetFromSale(player, data);
    }

    private void render() {
        inventory.clear();

        // Ornate frame (gold corners + edges) around the crimson field.
        MenuStyle.decorate(inventory);

        // Info item in the center (slot 13)
        double price = data.salePrice();
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(Component.text(data.displayName()).color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        infoMeta.lore(List.of(
                Component.text("Price: " + Planets.formatPrice(price) + " VPL").color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Owner: " + playerName(data.ownerUuid())).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        info.setItemMeta(infoMeta);
        inventory.setItem(13, info);

        // Visit button (slot 11)
        ItemStack visit = new ItemStack(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        ItemMeta visitMeta = visit.getItemMeta();
        visitMeta.displayName(Component.text("\uD83D\uDEB6 Visit Planet").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        visitMeta.lore(List.of(Component.text("Teleport to this planet").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        visit.setItemMeta(visitMeta);
        inventory.setItem(VISIT_SLOT, visit);

        // Buy button (slot 15)
        ItemStack buy = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta buyMeta = buy.getItemMeta();
        buyMeta.displayName(Component.text("\uD83D\uDCB0 Buy Planet").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        buyMeta.lore(List.of(
                Component.text("Cost: " + Planets.formatPrice(price) + " VPL").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to purchase").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        buy.setItemMeta(buyMeta);
        inventory.setItem(BUY_SLOT, buy);

        // Close button (slot 26)
        inventory.setItem(CLOSE_SLOT, closeItem());
    }

    private static ItemStack closeItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2190 Back").color(NamedTextColor.GRAY)
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
