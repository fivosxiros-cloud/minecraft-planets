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
 * Buy confirmation UI for the chosen planet type — a {@code null} archetype
 * means a normal (classic overworld) planet:
 * <ul>
 *   <li>27-slot chest with a gray glass pane frame.</li>
 *   <li>Slot 4: the archetype item (type, description, traits).</li>
 *   <li>Slot 11: Red stained glass pane — cancel (goes back to the type picker).</li>
 *   <li>Slot 13: Paper — price.</li>
 *   <li>Slot 15: Green stained glass pane — confirm purchase (creates the world).</li>
 * </ul>
 */
public final class BuyConfirmMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int INFO_SLOT = 4;
    private static final int CANCEL_SLOT = 11;
    private static final int PRICE_SLOT = 13;
    private static final int CONFIRM_SLOT = 15;

    private final Planets plugin;
    private final Player viewer;
    private final PlanetArchetypes.Archetype archetype;
    private final Inventory inventory;

    public BuyConfirmMenu(Planets plugin, Player viewer, PlanetArchetypes.Archetype archetype) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.archetype = archetype; // null = a normal planet
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Confirm " + typeName() + " Planet").color(NamedTextColor.GOLD));
        render();
    }

    /** The type shown in the title and items. */
    private String typeName() {
        return archetype == null ? "Normal" : archetype.displayName();
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

        if (slot == CONFIRM_SLOT) {
            // Warn the player about the price and whether they can actually buy.
            int limit = plugin.getMyPlanetManager().maxPlanetsPerPlayer();
            int owned = plugin.getMyPlanetManager().ownedCount(player.getUniqueId());
            double price = plugin.getMyPlanetManager().buyCost();

            if (owned >= limit) {
                player.sendMessage(Component.text("You already own the maximum of ").color(NamedTextColor.RED)
                        .append(Component.text(String.valueOf(limit)).color(NamedTextColor.YELLOW))
                        .append(Component.text(" planet(s). Abandon one first.").color(NamedTextColor.RED)));
                return;
            }

            // Custom generations are limited per player; normal planets are not.
            if (archetype != null && !plugin.canBuyCustomPlanet(player)) {
                return;
            }

            if (!plugin.hasEconomy()) {
                player.sendMessage(Component.text("No economy plugin found — cannot buy a planet.").color(NamedTextColor.RED));
                return;
            }

            double balance = plugin.getBalance(player);
            if (balance < 0) {
                player.sendMessage(Component.text("Could not check your balance.").color(NamedTextColor.RED));
                return;
            }

            if (balance < price) {
                player.sendMessage(Component.text("Not enough money! ").color(NamedTextColor.RED)
                        .append(Component.text("You have " + Planets.formatPrice(balance) + " VPL but ").color(NamedTextColor.YELLOW))
                        .append(Component.text("need " + Planets.formatPrice(price) + " VPL to buy a planet.").color(NamedTextColor.RED)));
                return;
            }

            // All good — create the planet (buyNewPlanet withdraws the money).
            player.closeInventory();
            plugin.buyNewPlanet(player, archetype == null ? null : archetype.id());
            return;
        }

        if (slot == CANCEL_SLOT) {
            // Go back to the buy UI
            player.closeInventory();
            new BuyPlanetMenu(plugin, player).open(player);
            return;
        }
    }

    private void render() {
        inventory.clear();

        // Ornate frame (gold corners + edges) around the crimson field.
        MenuStyle.decorate(inventory);

        // Type info item (slot 4).
        ItemStack info = new ItemStack(archetype == null ? Material.GRASS_BLOCK : archetype.icon());
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(Component.text(typeName() + " Planet").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        infoMeta.lore(List.of(
                Component.text(archetype == null
                                ? "Classic overworld terrain, generated naturally"
                                : archetype.description())
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(archetype == null ? "No tinted sky or planet effects" : archetype.traits())
                        .color(NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false),
                Component.text("Ground: " + PlanetTerrain.describe(
                                archetype == null ? null : archetype.terrain()))
                        .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Starter size: " + MyPlanetData.sizeName(0)).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        info.setItemMeta(infoMeta);
        inventory.setItem(INFO_SLOT, info);

        // Cancel button (slot 11) — red stained glass pane
        ItemStack cancel = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.displayName(Component.text("✖ Cancel").color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        cancelMeta.lore(List.of(Component.text("Back to the planet type picker").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        cancel.setItemMeta(cancelMeta);
        inventory.setItem(CANCEL_SLOT, cancel);

        // Price paper (slot 13).
        double price = plugin.getMyPlanetManager().buyCost();
        ItemStack priceItem = new ItemStack(Material.PAPER);
        ItemMeta priceMeta = priceItem.getItemMeta();
        priceMeta.displayName(Component.text(Planets.formatPrice(price) + " VPL")
                .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        priceMeta.lore(List.of(Component.text("You have " + Planets.formatPrice(plugin.getBalance(viewer)) + " VPL")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        priceItem.setItemMeta(priceMeta);
        inventory.setItem(PRICE_SLOT, priceItem);

        // Confirm button (slot 15) — green stained glass pane
        ItemStack confirm = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta confirmMeta = confirm.getItemMeta();
        confirmMeta.displayName(Component.text("✔ Confirm Purchase").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        confirmMeta.lore(List.of(Component.text("Creates your planet and teleports you there")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        confirm.setItemMeta(confirmMeta);
        inventory.setItem(CONFIRM_SLOT, confirm);
    }

    private static ItemStack fillerItem() {
        return MenuStyle.field();
    }
}