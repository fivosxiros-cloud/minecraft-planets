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

import java.util.List;

/**
 * "Abandon planet" confirmation for /myp → Abandon. The owner must click
 * the TNT button twice (with a short window between clicks) to release
 * ownership. The world itself is kept — only the ownership record goes.
 */
public final class MyPlanetAbandonMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final Inventory inventory;
    private boolean confirmed;

    public MyPlanetAbandonMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("🗑 Abandon " + data.displayName()).color(NamedTextColor.DARK_RED));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!data.ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner can abandon the planet.").color(NamedTextColor.RED));
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        switch (slot) {
            case 11 -> { // confirm
                if (confirmed) {
                    player.closeInventory();
                    plugin.abandonPlanet(player, data);
                } else {
                    confirmed = true;
                    render();
                    player.sendMessage(Component.text("Click the TNT again to permanently abandon ").color(NamedTextColor.RED)
                            .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                            .append(Component.text(".").color(NamedTextColor.RED)));
                }
            }
            case 15 -> { // cancel
                player.closeInventory();
                new MyPlanetMenu(plugin, player, data).open(player);
            }
            case 22 -> { // back
                player.closeInventory();
                new MyPlanetMenu(plugin, player, data).open(player);
            }
            default -> { }
        }
    }

    private void render() {
        inventory.clear();
        inventory.setItem(4, infoItem());
        inventory.setItem(11, confirmItem());
        inventory.setItem(15, cancelItem());
        inventory.setItem(22, backItem());
        for (int i = 0; i < SIZE; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler());
        }
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Abandon " + data.displayName()).color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("You give up ownership of this planet.").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("The world is NOT deleted.").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Members lose their roles.").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Its name is retired forever:").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("nobody can ever buy it again.").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack confirmItem() {
        ItemStack item = new ItemStack(Material.TNT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(confirmed ? "⚠ Click again to confirm!" : "Confirm abandon")
                .color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack cancelItem() {
        ItemStack item = new ItemStack(Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Keep my planet").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
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

    private static ItemStack filler() {
        return MenuStyle.field();
    }
}
