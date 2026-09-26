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
 * Icon picker for /myp → Icon: lets the owner pick which block represents
 * their planet in the /myp overview. Saved in config.yml under "icons",
 * keyed by world name (the same store the admin /planets icon command uses).
 */
public final class MyPlanetIconMenu implements InventoryHolder {

    private static final int SIZE = 36;
    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final Inventory inventory;

    /** The palette offered to owners (menu-friendly blocks). */
    private static final List<Material> PALETTE = List.of(
            Material.GRASS_BLOCK, Material.STONE, Material.DIRT, Material.COBBLESTONE,
            Material.OAK_LOG, Material.SAND, Material.RED_SAND, Material.SNOW_BLOCK,
            Material.ICE, Material.PACKED_ICE, Material.OBSIDIAN, Material.NETHERRACK,
            Material.END_STONE, Material.AMETHYST_BLOCK, Material.DIAMOND_BLOCK, Material.GOLD_BLOCK,
            Material.IRON_BLOCK, Material.REDSTONE_BLOCK, Material.LAPIS_BLOCK, Material.EMERALD_BLOCK,
            Material.GLOWSTONE, Material.SEA_LANTERN, Material.MAGMA_BLOCK, Material.SLIME_BLOCK,
            Material.HONEY_BLOCK, Material.MOSS_BLOCK, Material.PODZOL, Material.MYCELIUM,
            Material.TERRACOTTA, Material.BLACKSTONE, Material.QUARTZ_BLOCK, Material.PRISMARINE);

    public MyPlanetIconMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("🎨 " + data.displayName() + " Icon").color(NamedTextColor.DARK_PURPLE));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!data.canManage(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner or co-owner can change the planet's icon.").color(NamedTextColor.RED));
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (slot == 27) { // reset to default
            plugin.getConfig().set("icons." + data.worldName(), null);
            plugin.saveConfigQuietly();
            player.sendMessage(Component.text("Icon reset to the default.").color(NamedTextColor.GREEN));
            new MyPlanetIconMenu(plugin, player, data).open(player);
            return;
        }
        if (slot == 31) { // back
            player.closeInventory();
            new MyPlanetMenu(plugin, player, data).open(player);
            return;
        }
        if (slot < PALETTE.size()) {
            Material choice = PALETTE.get(slot);
            plugin.getConfig().set("icons." + data.worldName(), choice.name());
            plugin.saveConfigQuietly();
            player.sendMessage(Component.text("Icon set to ").color(NamedTextColor.GREEN)
                    .append(Component.text(choice.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.GREEN)));
            new MyPlanetIconMenu(plugin, player, data).open(player);
        }
    }

    private void render() {
        inventory.clear();
        for (int i = 0; i < PALETTE.size() && i < SIZE; i++) {
            inventory.setItem(i, paletteItem(PALETTE.get(i)));
        }
        inventory.setItem(27, resetItem());
        inventory.setItem(31, backItem());
        for (int i = 28; i < SIZE; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler());
        }
    }

    private ItemStack paletteItem(Material material) {
        Material current = plugin.iconOverride(data.worldName());
        boolean selected = current == material;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(material.name())
                .color(selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(selected ? "Currently selected" : "Click to select")
                .color(selected ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack resetItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Reset to default").color(NamedTextColor.RED)
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
