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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Paginated planet selection menu.
 * <p>
 * Layout (27 slots):
 * <ul>
 *   <li>Slots 0-17: planets of the current page.</li>
 *   <li>Slot 18 (down-left): previous page arrow.</li>
 *   <li>Slots 19-25: gray glass pane filler (slot 19 is a "Surprise Me"
 *       random-planet button, slot 22 shows a page counter when there is more
 *       than one page).</li>
 *   <li>Slot 26 (down-right): next page arrow.</li>
 * </ul>
 * Locked planets require the {@code planets.tp.&lt;world&gt;} permission: they render
 * grayed out and refuse clicks for players without it. The lock state is checked
 * live against the plugin config on every render and click, so locking/unlocking
 * a planet while the menu is open takes effect immediately.
 * Each slot shows the environment and how many players are currently on the world.
 */
public final class PlanetsMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int PLANET_SLOTS = 18; // slots 0-17
    private static final int PREVIOUS_SLOT = 18; // down-left
    private static final int NEXT_SLOT = 26; // down-right
    private static final int PAGE_INDICATOR_SLOT = 22; // middle of the bottom row
    private static final int SURPRISE_SLOT = 19; // random-planet button
    private static final int BUY_PLANET_SLOT = 22; // center bottom - knowledge book
    private static final int HELP_SLOT = 23; // the searchable help page
    private static final int ADMIN_SLOT = 25; // op-only: the planet admin panel

    private final Planets plugin;
    private final List<Planet> planets;
    private final Player viewer;
    private final Inventory inventory;
    private int page;

    public PlanetsMenu(Planets plugin, List<Planet> planets, Player viewer) {
        this.plugin = plugin;
        this.planets = planets;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text("Planets").color(NamedTextColor.DARK_GRAY));
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

    /** Handles every click inside this menu. */
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return; // clicked their own inventory
        }

        if (slot < PLANET_SLOTS) {
            int index = page * PLANET_SLOTS + slot;
            if (index < planets.size()) {
                Planet planet = planets.get(index);
                if (!plugin.canVisit(player, planet)) {
                    player.sendMessage(Component.text("You don't have permission to visit ").color(NamedTextColor.RED)
                            .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                            .append(Component.text(".").color(NamedTextColor.RED)));
                    return;
                }
                PlanetTravel.teleport(player, planet);
            }
            return;
        }

        if (slot == SURPRISE_SLOT) {
            teleportRandom(player);
            return;
        }

        if (slot == BUY_PLANET_SLOT) {
            openBuyPlanetMenu(player);
            return;
        }

        if (slot == HELP_SLOT) {
            plugin.openHelp(player);
            return;
        }

        if (slot == ADMIN_SLOT && plugin.canUseAdmin(player)) {
            plugin.openAdminMenu(player);
            return;
        }

        if (slot == PREVIOUS_SLOT && page > 0) {
            page--;
            render();
        } else if (slot == NEXT_SLOT && page < maxPage()) {
            page++;
            render();
        }
    }

    /** Teleports the player to a random planet they're allowed to visit. */
    private void teleportRandom(Player player) {
        List<Planet> candidates = planets.stream()
                .filter(planet -> plugin.canVisit(player, planet) && Bukkit.getWorld(planet.worldName()) != null)
                .toList();
        if (candidates.isEmpty()) {
            player.sendMessage(Component.text("No planets available to visit right now.").color(NamedTextColor.RED));
            return;
        }
        Planet pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        player.sendMessage(Component.text("Surprise! Teleporting to ").color(NamedTextColor.GRAY)
                .append(Component.text(pick.name()).color(NamedTextColor.YELLOW))
                .append(Component.text("...").color(NamedTextColor.GRAY)));
        PlanetTravel.teleport(player, pick);
    }

    /** Whether this planet should render grayed out for the menu's viewer. */
    private boolean isLocked(Planet planet) {
        return !plugin.canVisit(viewer, planet);
    }

    private void render() {
        inventory.clear();

        int start = page * PLANET_SLOTS;
        for (int i = 0; i < PLANET_SLOTS && start + i < planets.size(); i++) {
            Planet planet = planets.get(start + i);
            inventory.setItem(i, planetItem(planet, isLocked(planet), plugin.pendingLockInfo(planet.worldName())));
        }

        // Fill the bottom row with the styled field, keeping the corners for the
        // page arrows — the reference's bottom "tab row".
        for (int slot = PLANET_SLOTS + 1; slot < NEXT_SLOT; slot++) {
            inventory.setItem(slot, fillerItem());
        }

        if (maxPage() > 0) {
            inventory.setItem(PAGE_INDICATOR_SLOT, pageIndicator());
        } else {
            // No pagination needed — show the buy-a-planet button in the center slot
            inventory.setItem(BUY_PLANET_SLOT, buyPlanetButton());
        }
        inventory.setItem(SURPRISE_SLOT, surpriseItem());
        // The help page sits next to the buy button, so nobody has to guess that
        // /planets help exists.
        inventory.setItem(HELP_SLOT, helpItem());

        // Ops get the planet admin panel one click away from the planet browser.
        if (plugin.canUseAdmin(viewer)) {
            inventory.setItem(ADMIN_SLOT, adminItem());
        }

        // Buy a planet button (only when there are fewer planets than one page)
        if (planets.size() <= PLANET_SLOTS) {
            inventory.setItem(BUY_PLANET_SLOT, buyPlanetButton());
        }

        inventory.setItem(PREVIOUS_SLOT,
                arrowItem("Previous Page", page > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(NEXT_SLOT,
                arrowItem("Next Page", page < maxPage() ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
    }

    private int maxPage() {
        return Math.max(0, (planets.size() - 1) / PLANET_SLOTS);
    }

    private static ItemStack planetItem(Planet planet, boolean locked, Planets.PendingLockInfo pending) {
        ItemStack item = new ItemStack(planet.icon());
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        NamedTextColor nameColor = NamedTextColor.YELLOW;
        if (locked) {
            nameColor = NamedTextColor.GRAY;
            lore.add(Component.text("Locked — needs planets.tp." + planet.worldName().toLowerCase(Locale.ROOT))
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        } else if (pending != null) {
            // A lock countdown is running for this planet but hasn't fired yet.
            nameColor = NamedTextColor.GOLD;
            lore.add(Component.text("Locking in " + pending.remainingSeconds()
                            + (pending.remainingSeconds() == 1 ? " second" : " seconds")
                            + " — started by " + pending.actorName())
                    .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        }
        meta.displayName(Component.text(planet.name()).color(nameColor)
                .decoration(TextDecoration.ITALIC, false));
        if (!locked) {
            lore.add(Component.text("Click to teleport").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            World world = Bukkit.getWorld(planet.worldName());
            if (world != null) {
                lore.add(Component.text("Environment: " + environmentName(world.getEnvironment()))
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text(world.getPlayers().size() + " player(s) here")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("World not loaded").color(NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String environmentName(World.Environment environment) {
        return switch (environment) {
            case NETHER -> "Nether";
            case THE_END -> "The End";
            case CUSTOM -> "Custom";
            default -> "Overworld";
        };
    }

    private static ItemStack surpriseItem() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Surprise Me").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Teleports to a random planet").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageIndicator() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        int first = page * PLANET_SLOTS + 1;
        int last = Math.min((page + 1) * PLANET_SLOTS, planets.size());
        meta.displayName(Component.text("Page " + (page + 1) + "/" + (maxPage() + 1)).color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Showing " + first + "–" + last + " of " + planets.size() + " planets")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack fillerItem() {
        return MenuStyle.field();
    }

    private static ItemStack arrowItem(String name, NamedTextColor color) {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack helpItem() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2753 Help").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Every command and panel in one page").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Planets, /myp, homes, settings, money — searchable")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Same as /planets help").color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack adminItem() {
        ItemStack item = new ItemStack(Material.COMMAND_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2699 Planet Admin").color(NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Op-only control panel").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Every planet, with enter/regenerate/lock/landing/icon/terrain/delete")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Same as /planets admin").color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buyPlanetButton() {
        ItemStack item = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Buy a Planet").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Click to browse planets for sale").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private void openBuyPlanetMenu(Player player) {
        plugin.openBuyPlanetMenu(player);
    }
}