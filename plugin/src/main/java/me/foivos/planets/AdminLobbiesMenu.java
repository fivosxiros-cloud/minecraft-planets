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
 * The lobby half of the admin panel, opened from the Dashboard or from a
 * planet panel's "Lobby Admin" button. Every configured lobby is one clickable
 * item; clicking one opens {@link AdminLobbyPanelMenu} with enter, icon,
 * landing, weather, terrain, structures, spawn and delete.
 *
 * <p>Layout mirrors {@link AdminPlanetsMenu}: 28 lobbies per page in the middle,
 * a light-blue frame with the arrows and the info item on the bottom row.
 */
public final class AdminLobbiesMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int PER_PAGE = 28;
    private static final int PREVIOUS_SLOT = 37;
    private static final int DASHBOARD_SLOT = 39;
    private static final int INFO_SLOT = 40;
    private static final int NEXT_SLOT = 43;

    private final Planets plugin;
    private final Player viewer;
    private final List<Planets.Lobby> lobbies;
    private final Inventory inventory;
    private int page;

    public AdminLobbiesMenu(Planets plugin, Player viewer, List<Planets.Lobby> lobbies) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.lobbies = lobbies;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("⚙ Lobby Admin").color(NamedTextColor.DARK_PURPLE));
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

        Planets.Lobby lobby = lobbyAt(slot);
        if (lobby == null) {
            return;
        }
        plugin.openAdminLobby(player, lobby.id());
    }

    private Planets.Lobby lobbyAt(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        if (row > 3 || column == 0 || column == 8) {
            return null;
        }
        int index = page * PER_PAGE + row * 7 + (column - 1);
        return index >= 0 && index < lobbies.size() ? lobbies.get(index) : null;
    }

    private int maxPage() {
        return Math.max(0, (lobbies.size() - 1) / PER_PAGE);
    }

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "🚪 Lobbies", "🗂 Pages");

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < lobbies.size(); i++) {
            int row = i / 7;
            int column = (i % 7) + 1;
            inventory.setItem(row * 9 + column, lobbyItem(lobbies.get(start + i)));
        }

        inventory.setItem(PREVIOUS_SLOT, arrowItem("Previous Page",
                page > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(NEXT_SLOT, arrowItem("Next Page",
                page < maxPage() ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(INFO_SLOT, infoItem());
        inventory.setItem(DASHBOARD_SLOT, actionItem(Material.COMPARATOR, "Dashboard",
                "Server-wide overview, cleanup and the planet list"));
    }

    private ItemStack lobbyItem(Planets.Lobby lobby) {
        ItemStack item = new ItemStack(lobby.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(lobby.name()).color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        World world = Bukkit.getWorld(lobby.worldName());
        List<Component> lore = new ArrayList<>();
        lore.add(line("Id: " + lobby.id() + " · world: " + lobby.worldName()));
        if (lobby.description() != null && !lobby.description().isBlank()) {
            lore.add(line(lobby.description(), NamedTextColor.WHITE));
        }
        lore.add(line("Landing: " + (lobby.hasLanding()
                ? lobby.x().intValue() + ", " + lobby.y().intValue() + ", " + lobby.z().intValue()
                : "not set (world spawn)")));
        lore.add(line("Menu slot: " + (lobby.slot() == null ? "auto" : String.valueOf(lobby.slot()))));
        lore.add(world == null
                ? line("World not loaded", NamedTextColor.RED)
                : line(world.getPlayers().size() + " player(s) here now",
                        world.getPlayers().isEmpty() ? NamedTextColor.GRAY : NamedTextColor.GREEN));
        lore.add(commandLine("/lobby " + lobby.id()));
        lore.add(Component.text("Click for actions").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.OAK_BOAT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Lobbies: " + lobbies.size()).color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Lobby worlds are hidden from /planets"));
        lore.add(line("Create one with /lobby create <name> [material]"));
        int first = lobbies.isEmpty() ? 0 : page * PER_PAGE + 1;
        int last = Math.min((page + 1) * PER_PAGE, lobbies.size());
        lore.add(line("Page " + (page + 1) + "/" + (maxPage() + 1) + " · lobbies "
                + first + "-" + last + " of " + lobbies.size(), NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String text) {
        return line(text, NamedTextColor.GRAY);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component commandLine(String command) {
        return Component.text("Command: ").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(command).color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false));
    }

    private static ItemStack actionItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String text : loreLines) {
            lore.add(line(text));
        }
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

    private static ItemStack fillerItem() {
        return MenuStyle.field();
    }

    private static ItemStack frameItem() {
        return MenuStyle.frame();
    }
}
