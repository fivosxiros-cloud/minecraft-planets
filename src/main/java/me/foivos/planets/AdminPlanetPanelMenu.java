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

/**
 * Per-planet admin actions, opened from {@link AdminPlanetsMenu}. Every admin
 * action for a planet is one click here: enter the world, regenerate its
 * terrain, lock/unlock it, change the landing mode, pick a new menu icon,
 * re-terrain it, turn structure generation on/off, edit its soundtrack, lock
 * the weather, pin the spawn, reset the world border and delete it. The two destructive actions
 * (regenerate, delete) ask for a second click before they run; the reference
 * book lists the commands that need typed input (create, preview, ...).
 *
 * <p>Layout (45 slots, 5 rows, light-blue frame around a gray field):
 * <pre>
 *   ┌───────── summary (4) ─────────┐
 *   Enter(10)  Regenerate(12)  Lock(14)  Landing(16)
 *   Icon(19)   Terrain(21)     Structures(23)  Music(24)  Weather(25)
 *   Spawn(29)  Border(31)      Delete(32)  Commands(33)  Dashboard(34)
 *   └────────────── Back (40) ──────────────┘
 * </pre>
 */
public final class AdminPlanetPanelMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int SUMMARY_SLOT = 4;
    private static final int ENTER_SLOT = 10;
    private static final int REGEN_SLOT = 12;
    private static final int LOCK_SLOT = 14;
    private static final int LANDING_SLOT = 16;
    private static final int ICON_SLOT = 19;
    private static final int TERRAIN_SLOT = 21;
    private static final int STRUCTURES_SLOT = 23;
    private static final int WEATHER_SLOT = 25;
    private static final int SPAWN_SLOT = 29;
    private static final int BORDER_SLOT = 31;
    private static final int DELETE_SLOT = 32;
    private static final int COMMANDS_SLOT = 33;
    private static final int DASHBOARD_SLOT = 34;
    private static final int MUSIC_SLOT = 24;
    private static final int LOBBY_SLOT = 37;
    private static final int BACK_SLOT = 40;

    /** A destructive action waiting for its confirmation click. */
    private enum Confirmation { NONE, REGENERATE, DELETE }

    private final Planets plugin;
    private final Player viewer;
    private final String worldName;
    private final Inventory inventory;
    private Confirmation confirmation = Confirmation.NONE;

    public AdminPlanetPanelMenu(Planets plugin, Player viewer, String worldName) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.worldName = worldName;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\u2699 " + worldName).color(NamedTextColor.DARK_PURPLE));
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

        Planets.AdminPlanet planet = plugin.adminPlanet(worldName);
        if (planet == null) {
            player.closeInventory();
            player.sendMessage(Component.text("That planet no longer exists.").color(NamedTextColor.RED));
            return;
        }

        switch (slot) {
            case BACK_SLOT -> {
                plugin.openAdminPlanetList(player);
                return;
            }
            case DASHBOARD_SLOT -> {
                confirmation = Confirmation.NONE;
                plugin.openAdminDashboard(player);
                return;
            }
            case ENTER_SLOT -> {
                confirmation = Confirmation.NONE;
                plugin.enterPlanetWorld(player, worldName);
                return;
            }
            case COMMANDS_SLOT -> {
                confirmation = Confirmation.NONE;
                printCommands(player);
            }
            case REGEN_SLOT -> {
                if (confirmation == Confirmation.REGENERATE) {
                    confirmation = Confirmation.NONE;
                    plugin.regeneratePlanet(player, worldName);
                } else {
                    confirmation = Confirmation.REGENERATE;
                }
            }
            case DELETE_SLOT -> {
                if (confirmation == Confirmation.DELETE) {
                    confirmation = Confirmation.NONE;
                    if (plugin.deletePlanetNow(player, worldName)) {
                        plugin.openAdminPlanetList(player);
                        return;
                    }
                } else {
                    confirmation = Confirmation.DELETE;
                }
            }
            case LOCK_SLOT -> {
                confirmation = Confirmation.NONE;
                plugin.setPlanetLock(player, worldName, !planet.locked());
            }
            case LANDING_SLOT -> {
                confirmation = Confirmation.NONE;
                plugin.cycleLandingMode(player, worldName);
            }
            case ICON_SLOT -> {
                confirmation = Confirmation.NONE;
                plugin.cyclePlanetIcon(player, worldName);
            }
            case STRUCTURES_SLOT -> {
                confirmation = Confirmation.NONE;
                plugin.setWorldStructures(player, worldName, !planet.structures());
            }
            case WEATHER_SLOT -> {
                confirmation = Confirmation.NONE;
                plugin.cycleWeather(player, worldName);
            }
            case SPAWN_SLOT -> {
                confirmation = Confirmation.NONE;
                plugin.setWorldSpawnHere(player, worldName);
            }
            case BORDER_SLOT -> {
                confirmation = Confirmation.NONE;
                plugin.resetPlanetBorder(player, worldName);
            }
            case TERRAIN_SLOT -> {
                confirmation = Confirmation.NONE;
                plugin.openAdminTerrainPicker(player, worldName);
                return;
            }
            case MUSIC_SLOT -> {
                confirmation = Confirmation.NONE;
                new AdminMusicMenu(plugin, player, worldName, -1).open(player);
                return;
            }
            case LOBBY_SLOT -> {
                confirmation = Confirmation.NONE;
                plugin.openAdminLobbies(player);
                return;
            }
            default -> confirmation = Confirmation.NONE;
        }
        render();
    }

    /** Prints the admin commands that need typed input (they can't be clicked). */
    private void printCommands(Player player) {
        player.closeInventory();
        player.sendMessage(Component.text("\u2699 Planet commands — " + worldName).color(NamedTextColor.DARK_PURPLE));
        for (String line : commandReference()) {
            player.sendMessage(Component.text("\u2022 ").color(NamedTextColor.GRAY)
                    .append(Component.text(line).color(NamedTextColor.YELLOW)));
        }
    }

    /** The chat-only admin commands, as a reusable list. */
    static List<String> commandReference() {
        return List.of(
                "/planets create <name> [type] [generation] — builds a new world with terrain",
                "/planets preview <generation|end> — look at a generation in a throwaway world",
                "/planets icon <planet> <material|reset> — set an exact menu icon",
                "/planets landing <planet> station|random|point — where players land",
                "/planets setlanding <planet> — pin the fixed landing point where you stand",
                "/planets lock <planet> | lock cancel <planet> — lock with a warning countdown",
                "/planets world <planet> status — every world property at a glance",
                "/planets world <planet> effect|sky|particles|atmosphere|time|gamerule|tick-speed|difficulty|monsters|animals|ambient|water|dimensions ...",
                "Music: the Music button edits music.tracks.<planet> in config.yml — add, hear, reorder, remove",
                "/planets delete <planet> — delete with a chat confirmation",
                "/planets buy <amount> | renameprice <prices...> — economy settings",
                "/planets reload — reload config.yml");
    }

    private void render() {
        inventory.clear();

        MenuStyle.decorate(inventory, "⚙ Planet", "🎛 Actions");

        Planets.AdminPlanet planet = plugin.adminPlanet(worldName);
        if (planet == null) {
            return;
        }

        inventory.setItem(SUMMARY_SLOT, summaryItem(planet));
        inventory.setItem(ENTER_SLOT, commandItem(Material.ENDER_PEARL, "Enter Planet",
                "/p " + worldName,
                "Teleports you to " + worldName + "'s spawn",
                "Loads the world first if it's unloaded"));
        inventory.setItem(REGEN_SLOT, confirmation == Confirmation.REGENERATE
                ? confirmItem("Regenerate " + worldName + "?",
                        "This wipes every build in the world",
                        "It is rebuilt with its own terrain " + terrainName(planet))
                : commandItem(Material.STRUCTURE_BLOCK, "Regenerate",
                        "/planets world " + worldName + " regen",
                        "Rebuilds the world from scratch with its terrain",
                        "Every build in it is lost"));
        inventory.setItem(LOCK_SLOT, lockItem(planet));
        inventory.setItem(LANDING_SLOT, commandItem(Material.LODESTONE, "Landing Mode",
                "/planets landing " + worldName + " station|random|point",
                "Currently: " + planet.landingMode() + " — click to cycle",
                "Pin a fixed point with /planets setlanding " + worldName));

        Material iconMaterial = plugin.iconOverride(worldName);
        inventory.setItem(ICON_SLOT, commandItem(iconMaterial == null ? Material.ITEM_FRAME : iconMaterial,
                "Menu Icon",
                "/planets icon " + worldName + " <material|reset>",
                "Currently: " + (iconMaterial == null ? "default" : iconMaterial.name()),
                "Click to cycle through a palette"));
        inventory.setItem(TERRAIN_SLOT, commandItem(Material.GRASS_BLOCK, "Terrain",
                "/planets world " + worldName + " terrain <preset|off>",
                "Currently: " + PlanetTerrain.describe(planet.terrain()),
                "Click to pick another generation"));
        inventory.setItem(MUSIC_SLOT, musicItem());
        inventory.setItem(STRUCTURES_SLOT, structuresItem(planet));
        inventory.setItem(WEATHER_SLOT, commandItem(Material.WATER_BUCKET, "Weather Lock",
                "/planets world " + worldName + " weather clear|rain|thunder|off",
                "Currently: " + weatherName(),
                "Click to cycle the locked weather"));

        World world = Bukkit.getWorld(worldName);
        inventory.setItem(SPAWN_SLOT, commandItem(Material.COMPASS, "Set Spawn Here",
                "/planets world " + worldName + " spawn",
                world == null ? "World not loaded" : "Click while standing inside " + worldName,
                "Pins the world spawn to your position"));
        inventory.setItem(BORDER_SLOT, commandItem(Material.SCAFFOLDING, "Reset Border",
                "/planets world " + worldName + " border <size>",
                "Puts the border back to the planet's size level",
                world == null ? "World not loaded"
                        : "Now: " + (long) world.getWorldBorder().getSize() + " blocks"));
        inventory.setItem(DELETE_SLOT, confirmation == Confirmation.DELETE
                ? confirmItem("Delete " + worldName + "?",
                        "The world folder is removed for good",
                        "Its name stays retired — nobody can buy it again")
                : dangerItem("Delete Planet", "/planets delete " + worldName,
                        "Removes the world, its folder and its ownership record",
                        "Owners are not refunded"));
        inventory.setItem(COMMANDS_SLOT, commandItem(Material.WRITABLE_BOOK, "Command Reference",
                "/planets help",
                "Every admin command that needs typed input",
                "Click to print them in chat"));
        inventory.setItem(DASHBOARD_SLOT, commandItem(Material.COMPARATOR, "Dashboard",
                "/planets admin",
                "Server-wide overview and cleanup"));

        inventory.setItem(LOBBY_SLOT, commandItem(Material.OAK_BOAT, "Lobby Admin",
                "/lobby | /lobby list",
                "Manage the server's lobbies",
                "Enter, icon, landing, weather, terrain, delete"));

        inventory.setItem(BACK_SLOT, commandItem(Material.ARROW, "Back",
                "/planets admin",
                "Back to the planet list"));
    }

    /** The soundtrack button: how many tracks this world has, and whether it shares the default list. */
    private ItemStack musicItem() {
        int own = plugin.planetMusic().ownTrackSpecs(worldName).size();
        boolean shared = !plugin.planetMusic().hasOwnTracks(worldName);
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDFB5 Music").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(shared
                        ? "Now: the shared default list ("
                        + plugin.planetMusic().defaultTrackSpecs().size() + " track(s))"
                        : "Now: its own list of " + own + " track(s)")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("in config.yml under music.tracks." + worldName)
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Click to edit, hear and reorder them in-game")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String terrainName(Planets.AdminPlanet planet) {
        return "(" + PlanetTerrain.describe(planet.terrain()) + ")";
    }

    private String weatherName() {
        String locked = plugin.getConfig().getString("weather-lock." + worldName);
        return locked == null || locked.isBlank() ? "unlocked (normal weather)" : locked;
    }

    private ItemStack structuresItem(Planets.AdminPlanet planet) {
        boolean enabled = planet.structures();
        ItemStack item = new ItemStack(enabled ? Material.OAK_SAPLING : Material.DEAD_BUSH);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((enabled ? "\u2705 " : "\u274C ") + "Structures")
                .color(enabled ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Currently: " + (enabled ? "ON" : "OFF")).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(enabled
                        ? "Villages, ruins and tree growth can appear"
                        : "Nothing generates structures; tree growth is blocked")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Newly generated terrain follows it at once")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(commandLine("/planets world " + planet.worldName() + " structures "
                + (enabled ? "off" : "on")));
        lore.add(Component.text("Click to toggle").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack summaryItem(Planets.AdminPlanet planet) {
        Material icon = plugin.iconOverride(worldName);
        if (icon == null) {
            icon = Material.GRASS_BLOCK;
        }
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(planet.displayName()).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));

        World world = Bukkit.getWorld(worldName);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("World: " + worldName).color(NamedTextColor.GRAY)
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
            lore.add(Component.text("Blocks placed: " + planet.owned().totalBlocksPlaced()
                            + "/" + planet.owned().blockLimit()).color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Type: " + planet.owned().archetypeDisplayName())
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Terrain: " + PlanetTerrain.describe(planet.terrain()))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Landing: " + planet.landingMode()
                        + " · structures " + (planet.structures() ? "on" : "off"))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Locked: " + (planet.locked() ? "yes" : "no")
                        + " · weather " + weatherName())
                .color(planet.locked() ? NamedTextColor.RED : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (world != null) {
            lore.add(Component.text("Seed: " + world.getSeed()).color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(world.getPlayers().size() + " player(s) on it right now")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("World not loaded").color(NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (planet.protectedWorld()) {
            lore.add(Component.text("Protected world — regenerating and deleting are refused")
                    .color(NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack lockItem(Planets.AdminPlanet planet) {
        ItemStack item = new ItemStack(planet.locked() ? Material.IRON_DOOR : Material.OAK_DOOR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(planet.locked() ? "Unlock Planet" : "Lock Planet")
                .color(planet.locked() ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(planet.locked()
                                ? "Only players with planets.tp." + planet.worldName().toLowerCase(Locale.ROOT)
                                + " can visit"
                                : "Everyone can visit right now")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(planet.locked()
                                ? "Click to open it to everyone"
                                : "Click to lock it (players on it are sent to the hub)")
                        .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                commandLine(planet.locked()
                        ? "/planets world " + planet.worldName() + " lock off"
                        : "/planets lock " + planet.worldName() + " (or /planets lock cancel)")));
        item.setItemMeta(meta);
        return item;
    }

    /** Lore line naming the chat command an action mirrors. */
    private static Component commandLine(String command) {
        return Component.text("Command: ").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(command).color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false));
    }

    /** Action item whose lore ends with the chat command that does the same thing. */
    private static ItemStack commandItem(Material material, String name, String command, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(Component.text(line).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(commandLine(command));
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

    private static ItemStack dangerItem(String name, String command, String description, String... more) {
        ItemStack item = new ItemStack(Material.TNT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(commandLine(command));
        lore.add(Component.text(description).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        for (String line : more) {
            lore.add(Component.text(line).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
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
        meta.displayName(Component.text("\u2714 " + name).color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(warning).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        for (String line : more) {
            lore.add(Component.text(line).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
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
