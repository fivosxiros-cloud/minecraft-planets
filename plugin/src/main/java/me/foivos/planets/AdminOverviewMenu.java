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
 * The server-wide side of the admin panel, opened by {@code /planets admin}
 * (and by the Dashboard button inside the planet panel). It answers "how
 * healthy is this server?" at a glance:
 *
 * <ul>
 *   <li><b>Counts</b> — planets, player-owned vs public, loaded vs unloaded
 *       worlds, locked planets, retired names, leftover world folders, preview
 *       worlds and total placed blocks.</li>
 *   <li><b>Biggest block usage</b> — the planets with the most placed blocks,
 *       read from the planet-wide block counters.</li>
 *   <li><b>Cleanup</b> — one two-click action that discards every leftover
 *       preview world and every world folder that belongs to nothing.</li>
 * </ul>
 *
 * <p>Layout (45 slots, 5 rows, light-blue frame around a gray field):
 * <pre>
 *   ┌────────────┤ server health (4) ├────────────┐
 *   Planets(10) Owned(11) Public(12) Loaded(13) Unloaded(14) Locked(15) Retired(16)
 *   Blocks(19)  Top(20)   Previews(21) Leftovers(22) Empty(23)
 *   └─ Browse(29) ─ Lobby Admin(32) ─ Clean up(31) ─ Rescan(33) ─ Back to /planets(43) ─┘
 * </pre>
 */
public final class AdminOverviewMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int TITLE_SLOT = 4;

    private static final int PLANETS_SLOT = 10;
    private static final int OWNED_SLOT = 11;
    private static final int PUBLIC_SLOT = 12;
    private static final int LOADED_SLOT = 13;
    private static final int UNLOADED_SLOT = 14;
    private static final int LOCKED_SLOT = 15;
    private static final int RETIRED_SLOT = 16;

    private static final int BLOCKS_SLOT = 19;
    private static final int TOP_SLOT = 20;
    private static final int PREVIEWS_SLOT = 21;
    private static final int LEFTOVERS_SLOT = 22;
    private static final int EMPTY_SLOT = 23;

    private static final int BROWSE_SLOT = 29;
    private static final int LOBBY_SLOT = 32;
    private static final int HELP_SLOT = 30;
    private static final int CLEANUP_SLOT = 31;
    private static final int RESCAN_SLOT = 33;
    private static final int BORDERS_SLOT = 34;
    private static final int HOMES_SLOT = 24;
    private static final int HUD_SLOT = 25;
    private static final int BACK_SLOT = 40;

    private final Planets plugin;
    private final Player viewer;
    private final Planets.ServerStats stats;
    private final Inventory inventory;
    private boolean confirmCleanup;

    public AdminOverviewMenu(Planets plugin, Player viewer, Planets.ServerStats stats) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.stats = stats;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\u2699 Planet Admin").color(NamedTextColor.DARK_PURPLE));
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

        switch (slot) {
            case BROWSE_SLOT -> {
                plugin.openAdminPlanetList(player);
                return;
            }
            case LOBBY_SLOT -> {
                plugin.openAdminLobbies(player);
                return;
            }
            case RESCAN_SLOT -> {
                // Re-read the numbers instead of re-rendering the cached snapshot.
                plugin.openAdminDashboard(player);
                return;
            }
            case BORDERS_SLOT -> {
                int fixed = plugin.restorePlanetBorders();
                player.sendMessage(Component.text("\uD83C\uDF10 Restored the border of " + fixed + " planet(s).")
                        .color(NamedTextColor.GREEN));
                player.sendMessage(Component.text("To give every public planet the same size use ")
                        .color(NamedTextColor.GRAY)
                        .append(Component.text("/planets borders set <blocks|NxN>")
                                .color(NamedTextColor.YELLOW))
                        .append(Component.text(", e.g. 6x6 for six chunks a side.")
                                .color(NamedTextColor.GRAY)));
                plugin.openAdminDashboard(player);
                return;
            }
            case BACK_SLOT -> {
                plugin.openPlanetsMenu(player);
                return;
            }
            case HELP_SLOT -> {
                plugin.openAdminHelp(player);
                return;
            }
            case HOMES_SLOT -> {
                plugin.openAdminHomes(player);
                return;
            }
            case HUD_SLOT -> {
                plugin.openHudEditor(player);
                return;
            }
            case CLEANUP_SLOT -> {
                if (!confirmCleanup) {
                    // First click arms it; the item shows exactly what would go.
                    confirmCleanup = stats.cleanable() > 0;
                    if (!confirmCleanup) {
                        player.sendMessage(Component.text("Nothing to clean up — no leftover or preview worlds.")
                                .color(NamedTextColor.GRAY));
                    }
                } else {
                    confirmCleanup = false;
                    player.closeInventory();
                    int removed = plugin.cleanUpWorlds(player);
                    player.sendMessage(Component.text("\uD83E\uDDF9 Cleanup removed " + removed + " world(s).")
                            .color(removed > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY));
                    if (removed == 0) {
                        player.sendMessage(Component.text("The folders were probably locked by the server — "
                                + "try again after a restart.").color(NamedTextColor.YELLOW));
                    }
                    plugin.openAdminDashboard(player);
                    return;
                }
            }
            default -> confirmCleanup = false;
        }
        render();
    }

    private void render() {
        inventory.clear();

        MenuStyle.decorate(inventory, "📊 Server health", "🧹 Cleanup");

        inventory.setItem(TITLE_SLOT, titleItem());

        inventory.setItem(PLANETS_SLOT, statItem(Material.ENDER_EYE, "Planets",
                String.valueOf(stats.planets()), NamedTextColor.AQUA,
                "Player-owned and public planets together"));
        inventory.setItem(OWNED_SLOT, statItem(Material.PLAYER_HEAD, "Player-owned",
                String.valueOf(stats.owned()), NamedTextColor.GOLD,
                "Planets players bought and manage with /myp"));
        inventory.setItem(PUBLIC_SLOT, statItem(Material.GRASS_BLOCK, "Public planets",
                String.valueOf(stats.publicPlanets()), NamedTextColor.GREEN,
                "Planets listed in /p for everyone"));
        inventory.setItem(LOADED_SLOT, statItem(Material.LODESTONE, "Worlds loaded",
                String.valueOf(stats.loadedWorlds()), NamedTextColor.GREEN,
                "Planet worlds currently loaded by the server"));
        inventory.setItem(UNLOADED_SLOT, statItem(Material.BARRIER, "Worlds unloaded",
                String.valueOf(stats.unloadedWorlds()),
                stats.unloadedWorlds() > 0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY,
                "Planets with a folder on disk that Multiverse hasn't loaded",
                "Click 'Enter Planet' on one to load it"));
        inventory.setItem(LOCKED_SLOT, statItem(Material.IRON_DOOR, "Locked planets",
                String.valueOf(stats.locked()), stats.locked() > 0 ? NamedTextColor.RED : NamedTextColor.GRAY,
                "Only players with planets.tp.<world> may visit these"));
        inventory.setItem(RETIRED_SLOT, statItem(Material.NAME_TAG, "Retired names",
                String.valueOf(stats.retired()), NamedTextColor.LIGHT_PURPLE,
                "World names that can never be bought again",
                "(deleted and abandoned planets)"));

        long average = stats.owned() == 0 ? 0 : stats.totalBlocks() / stats.owned();
        inventory.setItem(BLOCKS_SLOT, statItem(Material.BRICKS, "Blocks placed",
                String.valueOf(stats.totalBlocks()), NamedTextColor.AQUA,
                "Planet-wide placed-block counters",
                "Average per owned planet: " + average,
                "Hardest limit in play: " + MyPlanetData.blockLimitAtLevel(
                        MyPlanetData.BLOCK_LIMIT_MAX_LEVEL) + " blocks"));
        inventory.setItem(TOP_SLOT, topItem());
        inventory.setItem(PREVIEWS_SLOT, statItem(Material.SPYGLASS, "Preview worlds",
                String.valueOf(stats.previews()), stats.previews() > 0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY,
                "Throwaway worlds from /planets preview",
                stats.previews() > 0 ? "The cleanup removes these" : "None lying around"));
        inventory.setItem(LEFTOVERS_SLOT, leftoverItem());
        inventory.setItem(EMPTY_SLOT, statItem(Material.STRUCTURE_VOID, "Empty worlds",
                String.valueOf(stats.emptyWorlds().size()),
                stats.emptyWorlds().isEmpty() ? NamedTextColor.GRAY : NamedTextColor.YELLOW,
                "Leftover worlds with no players on them",
                "These are the ones the cleanup deletes"));

        inventory.setItem(BROWSE_SLOT, actionItem(Material.CHEST, "Browse Planets",
                "Every planet, with enter/regenerate/lock/landing/icon/terrain/delete",
                "Same as the planet list in /planets admin"));
        inventory.setItem(HELP_SLOT, actionItem(Material.KNOWLEDGE_BOOK, "Admin Help",
                "Every panel action and command, with permissions",
                "Same as /planets admin help"));
        inventory.setItem(LOBBY_SLOT, actionItem(Material.OAK_BOAT, "Lobby Admin",
                "Every lobby in one panel",
                "Enter, icon, landing, weather, terrain, structures, delete"));
        inventory.setItem(CLEANUP_SLOT, cleanupItem());
        inventory.setItem(HOMES_SLOT, actionItem(Material.LIGHT_BLUE_BED, "Homes",
                "Every player's saved homes",
                "Teleport to any home, or delete one (or all of them)"
                , "Stored in homes.yml"));
        inventory.setItem(HUD_SLOT, actionItem(Material.SPYGLASS, "HUD Editor",
                "The Planet HUD's action-bar lines",
                "Add, rename, reorder and preview them live",
                "Same as /planets hud"));
        inventory.setItem(BORDERS_SLOT, actionItem(Material.SCAFFOLDING, "Restore Borders",
                "Puts every planet back to its own border size",
                "Owned planets: their Planet Size level",
                "Public planets: the size recorded for them",
                "Bulk change: /planets borders set <blocks|NxN>"));
        inventory.setItem(RESCAN_SLOT, actionItem(Material.SPYGLASS, "Rescan",
                "Reads the counts again (worlds, blocks, retired names)",
                "Use it after changing things by hand",
                "Refreshes this page"));
        inventory.setItem(BACK_SLOT, actionItem(Material.SPECTRAL_ARROW, "Back to /planets",
                "Leave the admin panel"));
    }

    private ItemStack titleItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2699 Planet Admin").color(NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(stats.planets() + " planet(s) · " + stats.owned() + " owned · "
                        + stats.publicPlanets() + " public")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Worlds: " + stats.loadedWorlds() + " loaded, "
                        + stats.unloadedWorlds() + " unloaded")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Servers: " + Bukkit.getWorlds().size() + " world(s) loaded in total")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(stats.cleanable() == 0
                        ? "No leftovers — the server is tidy"
                        : stats.cleanable() + " world(s) can be cleaned up")
                .color(stats.cleanable() == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** The planets with the most placed blocks, biggest first. */
    private ItemStack topItem() {
        List<MyPlanetData> planets = new ArrayList<>(plugin.getMyPlanetManager().allPlanets());
        planets.sort((a, b) -> Integer.compare(b.totalBlocksPlaced(), a.totalBlocksPlaced()));

        ItemStack item = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Biggest block usage").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        if (planets.isEmpty()) {
            lore.add(Component.text("No planet has recorded blocks yet").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        for (int i = 0; i < Math.min(8, planets.size()); i++) {
            MyPlanetData data = planets.get(i);
            int limit = data.blockLimit();
            int placed = data.totalBlocksPlaced();
            lore.add(Component.text("#" + (i + 1) + " " + data.displayName())
                    .color(i == 0 ? NamedTextColor.GOLD : NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(" — " + placed + "/" + limit + " blocks"
                                    + (limit > 0 ? " (" + (placed * 100 / Math.max(1, limit)) + "%)" : ""))
                            .color(NamedTextColor.DARK_GRAY)));
        }
        if (planets.size() > 8) {
            lore.add(Component.text("... and " + (planets.size() - 8) + " more")
                    .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** The cleanable folders by name, so an admin knows what would be removed. */
    private ItemStack leftoverItem() {
        boolean any = !stats.leftovers().isEmpty();
        ItemStack item = new ItemStack(any ? Material.BARREL : Material.LIGHT_GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Leftover worlds: " + stats.leftovers().size())
                .color(any ? NamedTextColor.YELLOW : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Folders that belong to no planet, lobby or world")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (!any) {
            lore.add(Component.text("Nothing to clean up here").color(NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
        }
        for (int i = 0; i < Math.min(10, stats.leftovers().size()); i++) {
            lore.add(Component.text("• " + stats.leftovers().get(i)).color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (stats.leftovers().size() > 10) {
            lore.add(Component.text("... and " + (stats.leftovers().size() - 10) + " more")
                    .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack cleanupItem() {
        int cleanable = stats.cleanable();
        ItemStack item = new ItemStack(confirmCleanup ? Material.RED_STAINED_GLASS_PANE : Material.LAVA_BUCKET);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(confirmCleanup
                        ? "\u2714 Confirm cleanup (" + cleanable + " world" + (cleanable == 1 ? "" : "s") + ")"
                        : "\uD83E\uDDF9 Clean Up Server")
                .color(confirmCleanup ? NamedTextColor.RED : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Removes " + stats.previews() + " preview world(s) and "
                        + stats.leftovers().size() + " leftover folder(s)")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Loaded worlds and real planets are never touched")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Anyone standing in a removed world is moved to the hub")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(cleanable == 0
                        ? "Nothing to clean up right now"
                        : confirmCleanup
                        ? "Click again to delete them for good"
                        : "Click once, then confirm")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack statItem(Material material, String label, String value,
                                      NamedTextColor color, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label + ": " + value).color(color)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(Component.text(line).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack actionItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(Component.text(line).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
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
