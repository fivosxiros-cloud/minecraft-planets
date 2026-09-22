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
 * Generation picker for one planet, opened from {@link AdminPlanetPanelMenu}.
 * Lists every generation the server can build (the built-in archetypes, "void"
 * and any preset from {@code generation-presets}) and applies the chosen one to
 * the planet's recorded terrain.
 */
public final class AdminTerrainMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int MAX_OPTIONS = 28;      // 4 rows x 7 columns
    private static final int BACK_SLOT = 40;

    private final Planets plugin;
    private final Player viewer;
    private final String worldName;
    private final List<Planets.Generation> generations;
    private final Inventory inventory;

    public AdminTerrainMenu(Planets plugin, Player viewer, String worldName, List<Planets.Generation> generations) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.worldName = worldName;
        this.generations = generations;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\u26CF Terrain: " + worldName).color(NamedTextColor.DARK_GREEN));
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
        if (slot == BACK_SLOT) {
            plugin.openAdminPlanet(player, worldName);
            return;
        }

        Planets.Generation generation = generationAt(slot);
        if (generation == null) {
            return;
        }
        if (plugin.applyTerrain(player, worldName, generation.spec())) {
            // Applying terrain is destructive, so show the panel again afterwards.
            plugin.openAdminPlanet(player, worldName);
        }
    }

    /** The generation shown in a slot, or null when the slot holds decoration. */
    private Planets.Generation generationAt(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        if (row > 3 || column == 0 || column == 8) {
            return null;
        }
        int index = row * 7 + (column - 1);
        return index >= 0 && index < Math.min(generations.size(), MAX_OPTIONS)
                ? generations.get(index)
                : null;
    }

    private void render() {
        inventory.clear();

        MenuStyle.decorate(inventory, "⛏ Generations", "🧱 Terrain");

        Planets.AdminPlanet planet = plugin.adminPlanet(worldName);
        String current = PlanetTerrain.describe(planet == null ? null : planet.terrain());

        int shown = Math.min(generations.size(), MAX_OPTIONS);
        for (int i = 0; i < shown; i++) {
            int row = i / 7;
            int column = (i % 7) + 1;
            inventory.setItem(row * 9 + column, generationItem(generations.get(i), current));
        }

        inventory.setItem(BACK_SLOT, backItem());
    }

    private ItemStack generationItem(Planets.Generation generation, String current) {
        boolean isCurrent = generation.spec() != null
                && PlanetTerrain.describe(generation.spec()).equals(current);

        ItemStack item = new ItemStack(generation.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(generation.displayName()
                        + (isCurrent ? " (current)" : ""))
                .color(isCurrent ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(generation.description()).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Ground: " + PlanetTerrain.describe(generation.spec()))
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Click to re-lay this planet's ground").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Only ground that doesn't match is replaced").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (generations.size() > MAX_OPTIONS) {
            lore.add(Component.text("(" + (generations.size() - MAX_OPTIONS)
                            + " more not shown — use /planets world " + worldName + " terrain <id>)")
                    .color(NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Back").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Back to the planet's actions").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack frameItem() {
        return MenuStyle.frame();
    }
}
