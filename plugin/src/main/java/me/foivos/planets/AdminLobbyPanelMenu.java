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
 * Every admin action for one lobby in a single panel, opened from
 * {@link AdminLobbiesMenu}: enter it, rename it, change its menu icon, move it
 * in the menu, write its description, pin or clear its landing spot, lock the
 * weather, re-terrain it, toggle structures, pin its spawn, reset its border
 * and delete it. Rename, Menu Slot and Description ask in chat and reopen the
 * panel when answered. Each action's lore also names the equivalent
 * {@code /lobby} command. The destructive delete asks twice.
 *
 * <pre>
 *   ┌───────── summary (4) ─────────┐
 *   Enter(10) Rename(11) Icon(12) Slot(13) Landing(14) Desc(15) Clear(16)
 *   Weather(19)  Terrain(21)  Structures(23)  Music(24)  Spawn(25)
 *   Border(29)  Delete(31)  Commands(33)  Dashboard(34)  List(37)
 *   └────────────── Back (40) ──────────────┘
 * </pre>
 */
public final class AdminLobbyPanelMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int SUMMARY_SLOT = 4;
    private static final int ENTER_SLOT = 10;
    private static final int RENAME_SLOT = 11;
    private static final int ICON_SLOT = 12;
    private static final int SLOT_SLOT = 13;
    private static final int LANDING_SLOT = 14;
    private static final int DESC_SLOT = 15;
    private static final int CLEAR_SLOT = 16;
    private static final int WEATHER_SLOT = 19;
    private static final int TERRAIN_SLOT = 21;
    private static final int STRUCTURES_SLOT = 23;
    private static final int SPAWN_SLOT = 25;
    private static final int BORDER_SLOT = 29;
    private static final int DELETE_SLOT = 31;
    private static final int COMMANDS_SLOT = 33;
    private static final int DASHBOARD_SLOT = 34;
    private static final int MUSIC_SLOT = 24;
    private static final int LIST_SLOT = 37;
    private static final int BACK_SLOT = 40;

    private final Planets plugin;
    private final Player viewer;
    private final String lobbyId;
    private final Inventory inventory;
    private boolean confirmDelete;

    public AdminLobbyPanelMenu(Planets plugin, Player viewer, String lobbyId) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.lobbyId = lobbyId;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("⚙ Lobby: " + lobbyId).color(NamedTextColor.DARK_PURPLE));
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

        Planets.Lobby lobby = plugin.adminLobby(lobbyId);
        if (lobby == null) {
            player.closeInventory();
            player.sendMessage(Component.text("That lobby no longer exists.").color(NamedTextColor.RED));
            plugin.openAdminLobbies(player);
            return;
        }

        switch (slot) {
            case BACK_SLOT, LIST_SLOT -> {
                plugin.openAdminLobbies(player);
                return;
            }
            case DASHBOARD_SLOT -> {
                plugin.openAdminDashboard(player);
                return;
            }
            case ENTER_SLOT -> {
                confirmDelete = false;
                plugin.enterLobbyWorld(player, lobby);
                return;
            }
            case COMMANDS_SLOT -> {
                confirmDelete = false;
                printCommands(player, lobby);
            }
            case ICON_SLOT -> {
                confirmDelete = false;
                plugin.cycleLobbyIcon(player, lobby);
            }
            case RENAME_SLOT -> {
                confirmDelete = false;
                plugin.promptLobbyName(player, lobby);
                return;
            }
            case SLOT_SLOT -> {
                confirmDelete = false;
                plugin.promptLobbySlot(player, lobby);
                return;
            }
            case DESC_SLOT -> {
                confirmDelete = false;
                plugin.promptLobbyDescription(player, lobby);
                return;
            }
            case LANDING_SLOT -> {
                confirmDelete = false;
                plugin.setLobbyLandingHere(player, lobby);
            }
            case CLEAR_SLOT -> {
                confirmDelete = false;
                plugin.clearLobbyLanding(player, lobby);
            }
            case WEATHER_SLOT -> {
                confirmDelete = false;
                plugin.cycleWeather(player, lobby.worldName());
            }
            case STRUCTURES_SLOT -> {
                confirmDelete = false;
                plugin.setWorldStructures(player, lobby.worldName(),
                        !plugin.worldStructures(lobby.worldName()));
            }
            case SPAWN_SLOT -> {
                confirmDelete = false;
                plugin.setWorldSpawnHere(player, lobby.worldName());
            }
            case BORDER_SLOT -> {
                confirmDelete = false;
                plugin.resetPlanetBorder(player, lobby.worldName());
            }
            case TERRAIN_SLOT -> {
                confirmDelete = false;
                plugin.openAdminTerrainPicker(player, lobby.worldName());
                return;
            }
            case MUSIC_SLOT -> {
                confirmDelete = false;
                new AdminMusicMenu(plugin, player, lobby.worldName(), -1, lobby.id()).open(player);
                return;
            }
            case DELETE_SLOT -> {
                if (confirmDelete) {
                    confirmDelete = false;
                    if (plugin.deleteLobbyNow(player, lobby)) {
                        plugin.openAdminLobbies(player);
                        return;
                    }
                } else {
                    confirmDelete = true;
                }
            }
            default -> confirmDelete = false;
        }
        render();
    }

    /** The soundtrack button: the lobby world's own music, or the shared default list. */
    private ItemStack musicItem(Planets.Lobby lobby) {
        int own = plugin.planetMusic().ownTrackSpecs(lobby.worldName()).size();
        boolean shared = !plugin.planetMusic().hasOwnTracks(lobby.worldName());
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDFB5 Music").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(shared
                                ? "Now: the shared default list ("
                                + plugin.planetMusic().defaultTrackSpecs().size() + " track(s))"
                                : "Now: its own list of " + own + " track(s)")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("music.tracks." + lobby.worldName() + " in config.yml")
                        .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Click to edit, hear and reorder them in-game")
                        .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private void printCommands(Player player, Planets.Lobby lobby) {
        player.closeInventory();
        player.sendMessage(Component.text("⚙ Lobby commands — " + lobby.id()).color(NamedTextColor.DARK_PURPLE));
        for (String line : List.of(
                "/lobby — opens the LOBBIES menu",
                "/lobby " + lobby.id() + " — teleport to its landing spot",
                "/lobby list — every lobby and its landing spot",
                "/lobby create <name> [material] — build a new lobby world",
                "/lobby setlanding " + lobby.id() + " — pin your position as the landing",
                "/lobby icon " + lobby.id() + " <material|reset> — change the menu block",
                "/lobby rename " + lobby.id() + " <new name> — rename the lobby",
                "/lobby slot " + lobby.id() + " <0-25> — move it in the LOBBIES menu",
                "/lobby desc " + lobby.id() + " <text|none> — set its description",
                "Music: edit this lobby's soundtrack in the admin panel (the Music button)",
                "/lobby delete " + lobby.id() + " — remove the lobby and its world")) {
            player.sendMessage(Component.text("• ").color(NamedTextColor.GRAY)
                    .append(Component.text(line).color(NamedTextColor.YELLOW)));
        }
    }

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "🚪 Lobby", "🎛 Actions");

        Planets.Lobby lobby = plugin.adminLobby(lobbyId);
        if (lobby == null) {
            return;
        }

        inventory.setItem(SUMMARY_SLOT, summaryItem(lobby));
        inventory.setItem(ENTER_SLOT, commandItem(Material.ENDER_PEARL, "Enter Lobby",
                "/lobby " + lobby.id(),
                "Teleports you to the lobby's landing spot",
                "Loads the world if it isn't loaded"));
        inventory.setItem(RENAME_SLOT, commandItem(Material.NAME_TAG, "Rename Lobby",
                "/lobby rename " + lobby.id() + " <new name>",
                "Currently: " + lobby.name(),
                "Click to type a new name in chat"));
        inventory.setItem(ICON_SLOT, commandItem(lobby.icon(), "Menu Icon",
                "/lobby icon " + lobby.id() + " <material|reset>",
                "Currently: " + lobby.icon().name(),
                "Click to cycle through the admin palette"));
        inventory.setItem(SLOT_SLOT, commandItem(Material.ITEM_FRAME, "Menu Slot",
                "/lobby slot " + lobby.id() + " <0-25>",
                "Currently: " + (lobby.slot() == null ? "auto" : String.valueOf(lobby.slot())),
                "Click to type the slot in chat (0-25)"));
        inventory.setItem(DESC_SLOT, commandItem(Material.PAPER, "Description",
                "/lobby desc " + lobby.id() + " <text|none>",
                "Currently: " + (lobby.description() == null || lobby.description().isBlank()
                        ? "(none)" : lobby.description()),
                "Shown under the lobby's name in the LOBBIES menu",
                "Click to type one in chat"));
        inventory.setItem(LANDING_SLOT, commandItem(Material.LODESTONE, "Set Landing Here",
                "/lobby setlanding " + lobby.id(),
                "Pins your current position as the landing spot",
                lobby.hasLanding()
                        ? "Now: " + lobby.x().intValue() + ", " + lobby.y().intValue() + ", " + lobby.z().intValue()
                        : "Now: not set (spawn is used)"));
        inventory.setItem(CLEAR_SLOT, commandItem(Material.BARRIER, "Clear Landing",
                "/lobby setlanding " + lobby.id() + " (reset via config)",
                "Removes the pinned landing spot",
                lobby.hasLanding() ? "Players then land at the world spawn" : "Nothing is pinned right now"));
        inventory.setItem(WEATHER_SLOT, commandItem(Material.WATER_BUCKET, "Weather Lock",
                "/planets world " + lobby.worldName() + " weather clear|rain|thunder|off",
                "Currently: " + plugin.weatherLockName(lobby.worldName()),
                "Click to cycle the locked weather"));
        inventory.setItem(TERRAIN_SLOT, commandItem(Material.GRASS_BLOCK, "Terrain",
                "/planets world " + lobby.worldName() + " terrain <preset|off>",
                "Currently: " + PlanetTerrain.describe(
                        PlanetTerrain.load(plugin.getConfig(), lobby.worldName())),
                "Click to pick another generation"));
        inventory.setItem(MUSIC_SLOT, musicItem(lobby));
        inventory.setItem(STRUCTURES_SLOT, structuresItem(lobby));
        World world = Bukkit.getWorld(lobby.worldName());
        inventory.setItem(SPAWN_SLOT, commandItem(Material.COMPASS, "Set Spawn Here",
                "/planets world " + lobby.worldName() + " spawn",
                world == null ? "World not loaded" : "Click while standing inside " + lobby.worldName(),
                "Pins the world spawn to your position"));
        inventory.setItem(BORDER_SLOT, commandItem(Material.SCAFFOLDING, "Reset Border",
                "/planets world " + lobby.worldName() + " border <size>",
                "Puts the border back to the public-planet radius",
                world == null ? "World not loaded"
                        : "Now: " + (long) world.getWorldBorder().getSize() + " blocks"));
        inventory.setItem(DELETE_SLOT, confirmDelete
                ? confirmItem("Delete lobby " + lobby.name() + "?",
                        "The world folder is removed for good",
                        "Players inside are moved to the hub")
                : dangerItem("Delete Lobby", "/lobby delete " + lobby.id(),
                        "Removes the lobby from the menu and deletes its world",
                        "This cannot be undone"));
        inventory.setItem(COMMANDS_SLOT, commandItem(Material.WRITABLE_BOOK, "Command Reference",
                "/lobby list",
                "Every /lobby command that needs typed input",
                "Click to print them in chat"));
        inventory.setItem(DASHBOARD_SLOT, commandItem(Material.COMPARATOR, "Dashboard",
                "/planets admin",
                "Server-wide overview and cleanup"));
        inventory.setItem(LIST_SLOT, commandItem(Material.OAK_BOAT, "All Lobbies",
                "/lobby list",
                "Back to the lobby list"));
        inventory.setItem(BACK_SLOT, commandItem(Material.ARROW, "Back",
                "/lobby list",
                "Back to the lobby list"));
    }

    private ItemStack structuresItem(Planets.Lobby lobby) {
        boolean enabled = plugin.worldStructures(lobby.worldName());
        ItemStack item = new ItemStack(enabled ? Material.OAK_SAPLING : Material.DEAD_BUSH);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((enabled ? "✅ " : "❌ ") + "Structures")
                .color(enabled ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Currently: " + (enabled ? "ON" : "OFF")));
        lore.add(line(enabled
                ? "Villages, ruins and tree growth can appear"
                : "Nothing generates structures; tree growth is blocked"));
        lore.add(commandLine("/planets world " + lobby.worldName() + " structures "
                + (enabled ? "off" : "on")));
        lore.add(Component.text("Click to toggle").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack summaryItem(Planets.Lobby lobby) {
        ItemStack item = new ItemStack(lobby.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(lobby.name()).color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        World world = Bukkit.getWorld(lobby.worldName());
        List<Component> lore = new ArrayList<>();
        lore.add(line("Id: " + lobby.id()));
        lore.add(line("World: " + lobby.worldName()));
        lore.add(line("Landing: " + (lobby.hasLanding()
                ? lobby.x().intValue() + ", " + lobby.y().intValue() + ", " + lobby.z().intValue()
                : "world spawn")));
        lore.add(line("Menu slot: " + (lobby.slot() == null ? "auto" : String.valueOf(lobby.slot()))));
        lore.add(line("Structures: " + (plugin.worldStructures(lobby.worldName()) ? "on" : "off")
                + " · weather " + plugin.weatherLockName(lobby.worldName())));
        lore.add(world == null
                ? line("World not loaded", NamedTextColor.RED)
                : line(world.getPlayers().size() + " player(s) on it right now"));
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

    private static ItemStack commandItem(Material material, String name, String command, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String text : loreLines) {
            lore.add(line(text));
        }
        lore.add(commandLine(command));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack dangerItem(String name, String command, String description, String... more) {
        ItemStack item = new ItemStack(Material.TNT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(commandLine(command));
        lore.add(line(description));
        for (String text : more) {
            lore.add(line(text));
        }
        lore.add(Component.text("Click once, then confirm").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack confirmItem(String name, String warning, String... more) {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("✔ " + name).color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(warning));
        for (String text : more) {
            lore.add(line(text));
        }
        lore.add(Component.text("Click again to confirm — anything else cancels")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
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
