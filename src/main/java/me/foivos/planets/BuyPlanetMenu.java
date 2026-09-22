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
 * "Buy a Planet" UI: the first choice is a <b>Normal</b> planet (classic
 * overworld terrain), followed by the built-in archetypes — custom
 * generations with their own terrain and a unique identity (sky color,
 * particles, gravity/effects). A player may own limited custom ones
 * ({@code my-planet.max-custom-planets}, default 1); normal planets aren't
 * limited by that, only by {@code my-planet.max-planets-per-player}.
 *
 * <p>Layout (45 slots, 5 rows):
 * <ul>
 *   <li>Row 0: light-blue glass frame + price paper in the center.</li>
 *   <li>Rows 1-3: one archetype item per planet type.</li>
 *   <li>Row 4: frame + rules book (slot 40) and back arrow (slot 44).</li>
 * </ul>
 * Clicking an archetype opens {@link BuyConfirmMenu} for that type.
 */
public final class BuyPlanetMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int PRICE_SLOT = 4;
    private static final int RULES_SLOT = 40;
    private static final int CLOSE_SLOT = 44;

    private final Planets plugin;
    private final Player viewer;
    private final Inventory inventory;

    public BuyPlanetMenu(Planets plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("🛒 Choose a Planet Type").color(NamedTextColor.AQUA));
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

        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            plugin.openPlanetsMenu(player);
            return;
        }
        if (slot == RULES_SLOT) {
            player.sendMessage(Component.text("Every purchase creates a brand-new planet with fresh terrain,")
                    .color(NamedTextColor.GRAY));
            player.sendMessage(Component.text("a unique seed and its own look. Deleted or abandoned planet")
                    .color(NamedTextColor.GRAY));
            player.sendMessage(Component.text("names are retired forever and can never be bought again.")
                    .color(NamedTextColor.GRAY));
            return;
        }

        // Choice 0 is a normal planet; 1..n are the custom generations.
        List<PlanetArchetypes.Archetype> archetypes = PlanetArchetypes.all();
        int index = archetypeSlotIndex(slot);
        if (index < 0 || index > archetypes.size()) {
            return;
        }
        // Make sure the player hasn't hit the planet limit yet.
        if (plugin.getMyPlanetManager().ownedCount(player.getUniqueId())
                >= plugin.getMyPlanetManager().maxPlanetsPerPlayer()) {
            player.sendMessage(Component.text("You already own the maximum of ")
                    .color(NamedTextColor.RED)
                    .append(Component.text(String.valueOf(plugin.getMyPlanetManager().maxPlanetsPerPlayer()))
                            .color(NamedTextColor.YELLOW))
                    .append(Component.text(" planet(s).").color(NamedTextColor.RED)));
            return;
        }
        PlanetArchetypes.Archetype archetype = index == 0 ? null : archetypes.get(index - 1);
        if (archetype != null && !plugin.canBuyCustomPlanet(player)) {
            return; // the check already explained why
        }
        player.closeInventory();
        new BuyConfirmMenu(plugin, player, archetype).open(player);
    }

    /** Maps inventory slots to archetype indexes (rows 1-3, columns 1-7). */
    private static int archetypeSlotIndex(int slot) {
        int row = (slot / 9) - 1;   // rows 1..3 -> 0..2
        int col = slot % 9;         // columns 1..7 -> 1..7
        if (row < 0 || row > 2 || col < 1 || col > 7) {
            return -1;
        }
        return row * 7 + (col - 1);
    }

    private void render() {
        inventory.clear();

        // Ornate frame (gold corners + edges) around the crimson field.
        MenuStyle.decorate(inventory);
        int[] frame = {9, 17, 18, 26, 27, 35};
        for (int i : frame) {
            inventory.setItem(i, frameItem());
        }

        // Price paper in the top center.
        double price = plugin.getMyPlanetManager().buyCost();
        ItemStack priceItem = new ItemStack(Material.PAPER);
        ItemMeta priceMeta = priceItem.getItemMeta();
        priceMeta.displayName(Component.text(Planets.formatPrice(price) + " VPL per planet")
                .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        priceMeta.lore(List.of(
                Component.text("Same price for every planet type").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Starter size: " + MyPlanetData.sizeName(0)).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Grow it later under /myp → Upgrades").color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        priceItem.setItemMeta(priceMeta);
        inventory.setItem(PRICE_SLOT, priceItem);

        // A normal planet first, then the archetypes: rows 1-3, columns 1-7.
        List<PlanetArchetypes.Archetype> archetypes = PlanetArchetypes.all();
        inventory.setItem(10, normalItem());
        for (int i = 0; i < archetypes.size() && i < 20; i++) {
            int index = i + 1;
            int row = index / 7;
            int col = index % 7;
            inventory.setItem((row + 1) * 9 + col + 1, archetypeItem(archetypes.get(i)));
        }

        // Rules book + back arrow in the bottom frame.
        inventory.setItem(RULES_SLOT, rulesItem());
        inventory.setItem(CLOSE_SLOT, closeItem());
    }

    /** The classic overworld option — no custom terrain, no identity changes. */
    private ItemStack normalItem() {
        ItemStack item = new ItemStack(Material.GRASS_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Normal Planet").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Classic overworld terrain, generated naturally")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("No tinted sky, particles or planet effects")
                        .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Custom generations: " + customPlanetNote())
                        .color(NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false),
                Component.text("Click to buy a normal planet").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    /** How many custom planets the viewer has used up, as menu lore. */
    private String customPlanetNote() {
        int max = plugin.maxCustomPlanets();
        if (max == 0) {
            return "unlimited";
        }
        int owned = 0;
        for (MyPlanetData data : plugin.getMyPlanetManager().ownedBy(viewer.getUniqueId())) {
            if (data.archetypeId() != null && !data.archetypeId().isBlank()) {
                owned++;
            }
        }
        return owned + "/" + max + " owned";
    }

    private ItemStack archetypeItem(PlanetArchetypes.Archetype archetype) {
        ItemStack item = new ItemStack(archetype.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(archetype.displayName() + " Planet")
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(archetype.description()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(archetype.traits()).color(NamedTextColor.DARK_AQUA)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Ground: " + PlanetTerrain.describe(archetype.terrain()))
                        .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Click to buy this type").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    /** Rules book listing the planets that were deleted and can never be bought again. */
    private ItemStack rulesItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Planet Rules").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        java.util.List<String> retired = new java.util.ArrayList<>(plugin.getMyPlanetManager().retiredPlanets());
        java.util.Collections.sort(retired);
        meta.lore(List.of(
                Component.text("• Every planet gets fresh terrain").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("  and a unique seed — no recycled worlds.").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("• Retired names (" + retired.size() + ") can never")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  be bought again.").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack closeItem() {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("← Back to /planets").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack fillerItem() {
        return MenuStyle.field();
    }

    private static ItemStack frameItem() {
        return MenuStyle.frame();
    }
}
