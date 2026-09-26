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
 * The /myp home screen for a player who owns (and is a member of) no planets.
 * Every slot is either a light-blue glass pane (the frame) or one of four real
 * buttons: buy a planet, browse /planets, open the planets-for-sale list and
 * close. All clicks are cancelled in {@code Planets}' inventory listener.
 *
 * <pre>
 *   🟦🟦🟦🟦📘🟦🟦🟦🟦        📘 Getting your first planet (4)
 *   🟦 💰 Buy(10)  🧭 Browse(12)  🌍 For Sale(14)  ✖ Close(16) 🟦
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 * </pre>
 */
public final class MyPlanetIntroMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int INFO_SLOT = 4;
    private static final int BUY_SLOT = 10;
    private static final int BROWSE_SLOT = 12;
    private static final int SALE_SLOT = 14;
    private static final int CLOSE_SLOT = 16;

    private final Planets plugin;
    private final Player viewer;
    private final Inventory inventory;

    public MyPlanetIntroMenu(Planets plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("🪐 My Planets").color(NamedTextColor.DARK_PURPLE));
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
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }

        switch (slot) {
            case BUY_SLOT -> {
                player.closeInventory();
                new BuyPlanetMenu(plugin, player).open(player);
            }
            case BROWSE_SLOT -> {
                player.closeInventory();
                plugin.openPlanetsMenu(player);
            }
            case SALE_SLOT -> {
                player.closeInventory();
                plugin.openForSaleMenu(player);
            }
            case CLOSE_SLOT -> player.closeInventory();
            default -> {
                // frame / info item — nothing to do
            }
        }
    }

    private void render() {
        inventory.clear();

        // Ornate frame (gold corners + edges) around the crimson field.
        MenuStyle.decorate(inventory, "🪐 First planet", "💰 Options");

        inventory.setItem(INFO_SLOT, infoItem());
        inventory.setItem(BUY_SLOT, buyItem());
        inventory.setItem(BROWSE_SLOT, browseItem());
        inventory.setItem(SALE_SLOT, saleItem());
        inventory.setItem(CLOSE_SLOT, closeItem());
    }

    /** Explains the two ways to get a planet, so the screen is self-explanatory. */
    private ItemStack infoItem() {
        int max = plugin.getMyPlanetManager().maxPlanetsPerPlayer();
        int owned = plugin.getMyPlanetManager().ownedCount(viewer.getUniqueId());
        ItemStack item = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Getting your first planet").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("You don't own a planet yet."));
        lore.add(line("You've claimed " + owned + "/" + max + " planets."));
        lore.add(line(""));
        lore.add(Component.text("• Buy a brand-new one for ")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(Planets.formatPrice(plugin.getMyPlanetManager().buyCost()) + " VPL")
                        .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)));
        lore.add(line("• Or buy one from another player"));
        lore.add(line("  on the planets-for-sale list."));
        lore.add(line(""));
        lore.add(Component.text("Once you have one, /myp becomes your")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("planet's control panel.")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buyItem() {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("💰 Buy a Planet").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Pick a planet type and buy it"),
                Component.text("Costs " + Planets.formatPrice(plugin.getMyPlanetManager().buyCost()) + " VPL")
                        .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                Component.text("Click to open the shop").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack browseItem() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("🧭 Browse Planets").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Open the /planets menu"),
                line("Public planets you can visit right now"),
                Component.text("Click to browse & teleport").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack saleItem() {
        int count = plugin.getMyPlanetManager().forSale().size();
        ItemStack item = new ItemStack(Material.GRASS_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("🌍 Planets for Sale: " + count).color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Planets players put up for sale"),
                count == 0
                        ? Component.text("None on the market right now").color(NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false)
                        : Component.text(count + " planet(s) waiting for an owner").color(NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to browse").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack closeItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("✖ Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String text) {
        return Component.text(text).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack frameItem() {
        return MenuStyle.frame();
    }
}
