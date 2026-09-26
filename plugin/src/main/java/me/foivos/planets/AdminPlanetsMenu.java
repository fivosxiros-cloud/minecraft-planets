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

/**
 * The op-only admin control panel: every planet — player-owned and public —
 * as a clickable item. Clicking one opens {@link AdminPlanetPanelMenu} with the
 * actions for that planet (enter, regenerate, lock, landing, icon, terrain,
 * delete), so admins never have to remember the command syntax.
 *
 * <p>Layout (45 slots, 5 rows):
 * <ul>
 *   <li>Rows 0-3, columns 1-7: up to 28 planets per page.</li>
 *   <li>Column 0 and column 8 of those rows plus the whole bottom row: light
 *       blue stained glass panes, holding the arrows and the summary item.</li>
 * </ul>
 */
public final class AdminPlanetsMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int PER_PAGE = 28;         // 4 rows x 7 columns
    private static final int PREVIOUS_SLOT = 37;
    private static final int DASHBOARD_SLOT = 39;
    private static final int INFO_SLOT = 40;
    private static final int NEXT_SLOT = 43;

    private final Planets plugin;
    private final Player viewer;
    private final List<Planets.AdminPlanet> planets;
    private final Inventory inventory;
    private int page;

    public AdminPlanetsMenu(Planets plugin, Player viewer, List<Planets.AdminPlanet> planets) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.planets = planets;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\u2699 Planet Admin").color(NamedTextColor.DARK_PURPLE));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Opens the menu for the given player. */
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
        if (slot == PREVIOUS_SLOT && page > 0) {
            page--;
            render();
            return;
        }
        if (slot == NEXT_SLOT && page < maxPage()) {
            page++;
            render();
            return;
        }
        if (slot == DASHBOARD_SLOT) {
            plugin.openAdminDashboard(player);
            return;
        }

        Planets.AdminPlanet planet = planetAt(slot);
        if (planet == null) {
            return;
        }
        plugin.openAdminPlanet(player, planet.worldName());
    }

    /** The planet shown in a slot, or null when the slot holds decoration. */
    private Planets.AdminPlanet planetAt(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        if (row > 3 || column == 0 || column == 8) {
            return null;
        }
        int index = page * PER_PAGE + row * 7 + (column - 1);
        return index >= 0 && index < planets.size() ? planets.get(index) : null;
    }

    private int maxPage() {
        return Math.max(0, (planets.size() - 1) / PER_PAGE);
    }

    private void render() {
        inventory.clear();

        MenuStyle.decorate(inventory, "🌍 Planets", "🗂 Pages");

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < planets.size(); i++) {
            int row = i / 7;
            int column = (i % 7) + 1;
            inventory.setItem(row * 9 + column, planetItem(planets.get(start + i)));
        }

        inventory.setItem(PREVIOUS_SLOT, arrowItem("Previous Page",
                page > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(NEXT_SLOT, arrowItem("Next Page",
                page < maxPage() ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(INFO_SLOT, infoItem());
        inventory.setItem(DASHBOARD_SLOT, dashboardItem());
    }

    private static ItemStack dashboardItem() {
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Dashboard").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Server-wide counts, block usage and cleanup")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack planetItem(Planets.AdminPlanet planet) {
        Material icon = plugin.iconOverride(planet.worldName());
        if (icon == null) {
            World world = Bukkit.getWorld(planet.worldName());
            icon = world == null ? Material.GRASS_BLOCK : switch (world.getEnvironment()) {
                case NETHER -> Material.NETHERRACK;
                case THE_END -> Material.END_STONE;
                default -> Material.GRASS_BLOCK;
            };
        }

        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(planet.displayName()).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));

        World world = Bukkit.getWorld(planet.worldName());
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("World: " + planet.worldName()).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(planet.isOwned()
                ? Component.text("Owned by " + plugin.adminOwnerName(planet.owned())).color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false)
                : Component.text("Public planet (/p)").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
        if (planet.owned() != null) {
            lore.add(Component.text("Size " + MyPlanetData.sizeName(planet.owned()
                            .upgradeLevel(MyPlanetData.Upgrade.PLANET_SIZE))
                    + " · level " + planet.owned().planetLevel()).color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Terrain: " + PlanetTerrain.describe(planet.terrain()))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Landing: " + planet.landingMode()
                + " · " + (planet.locked() ? "LOCKED" : "unlocked")
                + " · structures " + (planet.structures() ? "on" : "off"))
                .color(planet.locked() ? NamedTextColor.RED : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(world == null
                        ? "World not loaded"
                        : world.getPlayers().size() + " player(s) on it right now")
                .color(world == null ? NamedTextColor.RED : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (planet.protectedWorld()) {
            lore.add(Component.text("Protected world — it can't be deleted").color(NamedTextColor.DARK_RED)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Click for actions").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem() {
        int owned = 0;
        for (Planets.AdminPlanet planet : planets) {
            if (planet.isOwned()) {
                owned++;
            }
        }
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Planet Admin").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(planets.size() + " planet(s) · " + owned + " player-owned")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Sorted: player-owned first, then public").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Use the Dashboard for server-wide numbers")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        int first = planets.isEmpty() ? 0 : page * PER_PAGE + 1;
        int last = Math.min((page + 1) * PER_PAGE, planets.size());
        lore.add(Component.text("Page " + (page + 1) + "/" + (maxPage() + 1) + " · planets "
                        + first + "-" + last + " of " + planets.size())
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack arrowItem(String name, NamedTextColor color) {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack frameItem() {
        return MenuStyle.frame();
    }
}
