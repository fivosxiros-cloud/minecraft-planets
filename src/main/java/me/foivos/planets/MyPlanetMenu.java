package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The main "/myp" control panel. A light-blue glass frame wraps the menu;
 * the planet summary sits at the top and the actions are grouped by purpose:
 * <pre>
 *   Row 0: 🟦 light-blue frame  +  🪐 planet summary (slot 4)
 *   Row 1: 🌍 Overview | ⚙ Settings | 👥 Members | ✉ Invites | ⬆ Upgrades | 📊 Stats | 🚀 Enter
 *   Row 2: 🔒 Lock | ✏ Rename | 🎨 Icon | 💰 Sell | 🦶 Kick Visitors
 *   Row 3: 🟢 Live Visitors  |  ▣ Block Leaderboard
 *   Row 4: 🗑 Abandon Planet (danger zone)
 *   Row 5: 🟦 light-blue frame  +  ← Back (slot 49)
 * </pre>
 */
public final class MyPlanetMenu implements InventoryHolder {

    private static final int SIZE = 54; // 6 rows
    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final Inventory inventory;

    public MyPlanetMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("🪐 MY PLANET").color(NamedTextColor.DARK_PURPLE));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    // ── Click handler ─────────────────────────────────────────────────────

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        switch (slot) {
            // Row 1 — navigation
            case 10 -> openOverview(player);
            case 11 -> openSettings(player);
            case 12 -> openMembers(player);
            case 13 -> openInvites(player);
            case 14 -> openUpgrades(player);
            case 15 -> openStats(player);
            case 16 -> enterPlanet(player);
            // Row 2 — management
            case 20 -> toggleLock(player);
            case 21 -> startRename(player);
            case 22 -> openIconMenu(player);
            case 23 -> handleSellClick(player);
            case 24 -> openVisitorsMenu(player);
            // Row 4 — danger zone
            case 39 -> openAbandonMenu(player);
            // Row 5 — back
            case 49 -> plugin.openMyPlanetSelect(player);
        }
    }

    // ── Sub-menu openers ──────────────────────────────────────────────────

    private void openOverview(Player player) {
        player.closeInventory();
        sendOverviewChat(player);
    }

    private void openSettings(Player player) {
        player.closeInventory();
        new MyPlanetSettingsMenu(plugin, player, data).open(player);
    }

    private void openMembers(Player player) {
        player.closeInventory();
        new MyPlanetMembersMenu(plugin, player, data).open(player);
    }

    private void openInvites(Player player) {
        player.closeInventory();
        new MyPlanetInvitesMenu(plugin, player, data).open(player);
    }

    private void openUpgrades(Player player) {
        player.closeInventory();
        new MyPlanetUpgradesMenu(plugin, player, data).open(player);
    }

    private void openStats(Player player) {
        player.closeInventory();
        sendStatsChat(player);
    }

    /** Asks for a new display name in chat via the plugin's rename prompt. */
    private void startRename(Player player) {
        if (!data.canManage(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner or co-owner can rename the planet.").color(NamedTextColor.RED));
            return;
        }
        plugin.mypRename(player, new String[]{"rename", data.worldName()});
    }

    /** Opens the icon picker for this planet (owner/co-owner only). */
    private void openIconMenu(Player player) {
        if (!data.canManage(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner or co-owner can change the planet's icon.").color(NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        new MyPlanetIconMenu(plugin, player, data).open(player);
    }

    private void handleSellClick(Player player) {
        if (!data.ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner can sell the planet.").color(NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        if (data.forSale()) {
            data.removeFromSale();
            plugin.getMyPlanetManager().save();
            player.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" removed from sale.").color(NamedTextColor.GREEN)));
        } else {
            player.sendMessage(Component.text("\u2014 \uD83D\uDCB0 Put ").color(NamedTextColor.GOLD)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" Up for Sale \u2014").color(NamedTextColor.GOLD)));
            player.sendMessage(Component.text("Enter a price in VPL (e.g. 500) or type \"cancel\" to abort.").color(NamedTextColor.GRAY));
            plugin.setPendingSell(player, data.worldName());
        }
    }

    /** Opens the abandon confirmation (owner only). */
    private void openAbandonMenu(Player player) {
        if (!data.ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner can abandon the planet.").color(NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        new MyPlanetAbandonMenu(plugin, player, data).open(player);
    }

    /** Opens the Kick Visitors menu (owner or co-owner). */
    private void openVisitorsMenu(Player player) {
        if (!data.canManage(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner or co-owner can kick visitors.").color(NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        new MyPlanetVisitorsMenu(plugin, player, data).open(player);
    }

    private void enterPlanet(Player player) {
        World world = Bukkit.getWorld(data.worldName());
        if (world == null) {
            // World unloaded — load it on the main thread, pre-generate the
            // chunks around spawn, then teleport. The /myp UI is intentionally
            // left open so the player isn't kicked out of the menu while the
            // planet loads for the first time.
            player.sendMessage(Component.text("Loading your planet...").color(NamedTextColor.GRAY));
            World loaded = loadPlanetWorld();
            if (loaded == null) {
                player.sendMessage(Component.text("The planet's world couldn't be loaded.").color(NamedTextColor.RED));
                return;
            }
            enterLoadedPlanet(player, loaded);
            return;
        }
        enterLoadedPlanet(player, world);
    }

    /** Loads this planet's world (must be called on the main thread). */
    private World loadPlanetWorld() {
        try {
            return new WorldCreator(data.worldName()).createWorld();
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not load planet world '" + data.worldName() + "': " + ex.getMessage());
            return null;
        }
    }

    /** Pre-generates the chunks around the planet's spawn, then teleports. */
    private void enterLoadedPlanet(Player player, World world) {
        Location spawn = world.getSpawnLocation();
        int radius = 5; // 5-chunk radius = 11x11 chunks pre-generated
        int cx = spawn.getBlockX() >> 4;
        int cz = spawn.getBlockZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.getChunkAt(cx + dx, cz + dz);
            }
        }
        player.teleport(spawn);
        player.sendMessage(Component.text("Teleported to ").color(NamedTextColor.GREEN)
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    private void toggleLock(Player player) {
        player.closeInventory();
        if (!data.canManage(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner or co-owner can lock the planet.").color(NamedTextColor.RED));
            return;
        }
        if (data.status() == MyPlanetData.Status.LOCKED) {
            data.status(MyPlanetData.Status.OPEN);
            plugin.getMyPlanetManager().save();
            player.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" is now ").color(NamedTextColor.GREEN))
                    .append(Component.text("UNLOCKED").color(NamedTextColor.GREEN))
                    .append(Component.text(".").color(NamedTextColor.GREEN)));
        } else {
            data.status(MyPlanetData.Status.LOCKED);
            plugin.getMyPlanetManager().save();
            player.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" is now ").color(NamedTextColor.GREEN))
                    .append(Component.text("LOCKED").color(NamedTextColor.RED))
                    .append(Component.text(". Only members can visit.").color(NamedTextColor.GREEN)));
        }
    }

    // ── Chat sub-commands ─────────────────────────────────────────────────

    private void sendOverviewChat(Player player) {
        player.sendMessage(Component.text("— 🪐 " + data.displayName() + " —").color(NamedTextColor.GOLD));
        chatLine(player, "Owner", getOwnerName());
        chatLine(player, "Generation", "Normal");
        chatLine(player, "Status", data.status().name());
        chatLine(player, "Members", data.totalMembers() + "/" + data.memberCapacity());
        chatLine(player, "Visitors", String.valueOf(data.visitorCount()));
        chatLine(player, "Level", String.valueOf(data.planetLevel()));
        chatLine(player, "Description", data.description().isEmpty() ? "(none)" : data.description());
        chatLine(player, "PvP", data.pvpEnabled() ? "ON" : "OFF");
        chatLine(player, "Build", data.buildEnabled() ? "ON" : "OFF");
        chatLine(player, "Public", data.isPublic() ? "Yes" : "No");
        chatLine(player, "Mob Spawning", data.mobSpawning() ? "ON" : "OFF");
        chatLine(player, "Explosions", data.explosions() ? "ON" : "OFF");
        chatLine(player, "Fire Spread", data.fireSpread() ? "ON" : "OFF");
        chatLine(player, "Visitor Access", data.visitorAccess() ? "ON" : "OFF");
        chatLine(player, "Chest & Door Access", data.chestDoorAccess() ? "ON" : "OFF");
        chatLine(player, "Item Drops", data.itemDrops() ? "ON" : "OFF");
        player.sendMessage(Component.text("Use /myp to open the control panel.").color(NamedTextColor.GRAY));
    }

    private void sendStatsChat(Player player) {
        player.sendMessage(Component.text("— 📊 " + data.displayName() + " Statistics —").color(NamedTextColor.GOLD));
        chatLine(player, "Total Members", String.valueOf(data.totalMembers()));
        chatLine(player, "Total Visitors", String.valueOf(data.visitorCount()));
        chatLine(player, "Planet Level", String.valueOf(data.planetLevel()));
        chatLine(player, "Created", String.valueOf(data.createdTimestamp()));
        chatLine(player, "Last Modified", String.valueOf(data.lastModifiedTimestamp()));
        player.sendMessage(Component.text("Use /myp to open the control panel.").color(NamedTextColor.GRAY));
    }

    private static void chatLine(Player player, String label, String value) {
        player.sendMessage(Component.text("• ").color(NamedTextColor.GRAY)
                .append(Component.text(label + ": ").color(NamedTextColor.GRAY))
                .append(Component.text(value).color(NamedTextColor.YELLOW)));
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private void render() {
        inventory.clear();

        // Ornate frame (gold corners + edges) around the crimson field.
        MenuStyle.decorate(inventory, "🪐 Planet", "⚙ Actions");

        // Planet summary pinned in the top frame (slot 4).
        inventory.setItem(4, planetSummaryItem());

        // Row 1 — navigation
        inventory.setItem(10, actionItem(Material.ENDER_PEARL, "🌍 Overview",
                "View planet details and properties"));
        inventory.setItem(11, actionItem(Material.COMPARATOR, "⚙ Settings",
                "Change planet properties"));
        inventory.setItem(12, actionItem(Material.PLAYER_HEAD, "👥 Members",
                "Manage members and roles"));
        inventory.setItem(13, actionItem(Material.WRITABLE_BOOK, "✉ Invitations",
                "Send and manage invitations"));
        inventory.setItem(14, actionItem(Material.BEACON, "⬆ Upgrades",
                "Upgrade your planet"));
        inventory.setItem(15, actionItem(Material.BOOK, "📊 Statistics",
                "View planet statistics"));
        inventory.setItem(16, actionItem(Material.DIAMOND, "🚀 Enter Planet",
                "Teleport to your planet"));

        // Row 2 — management
        inventory.setItem(20, lockItem());
        inventory.setItem(21, renameButtonItem());
        inventory.setItem(22, iconButtonItem());
        inventory.setItem(23, sellButtonItem());
        inventory.setItem(24, actionItem(Material.LEAD, "🦶 Kick Visitors",
                "Remove unwanted players from your planet"));

        // Row 3 — live info
        inventory.setItem(30, liveVisitorsItem());
        inventory.setItem(32, blockStatsItem());

        // Row 4 — danger zone
        inventory.setItem(39, dangerItem(Material.TNT, "🗑 Abandon Planet",
                "Give up ownership (world is kept)"));

        // Row 5 — back (centered in the bottom frame)
        inventory.setItem(49, backItem());
    }

    /** Planet summary shown in the top frame: icon, owner, status, members. */
    private ItemStack planetSummaryItem() {
        Material material = plugin.iconOverride(data.worldName());
        if (material == null) material = Material.GRASS_BLOCK;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("🪐 " + data.displayName()).color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Type: " + data.archetypeDisplayName()).color(NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Owner: " + getOwnerName()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Status: " + data.status().name()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Members: " + data.totalMembers() + "/" + data.memberCapacity()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Size: " + MyPlanetData.sizeName(
                                data.upgradeLevel(MyPlanetData.Upgrade.PLANET_SIZE)))
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Level: " + data.planetLevel()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    /** Red-tinted action item for destructive actions. */
    private static ItemStack dangerItem(Material material, String name, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(description).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    /** Rename button showing what the next rename will cost. */
    private ItemStack renameButtonItem() {
        int price = plugin.renamePrice(data.renameCount() + 1);
        String priceLine = price > 0
                ? "Next rename costs " + Planets.formatPrice(price) + " VPL"
                : "Your next rename is free";
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("✏ Rename").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Give your planet a new name").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Renames so far: " + data.renameCount()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(priceLine).color(price > 0 ? NamedTextColor.GOLD : NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack sellButtonItem() {
        if (!data.ownerUuid().equals(viewer.getUniqueId())) {
            return disabledItem("Sell Planet", "Only the owner can sell");
        }
        if (data.forSale()) {
            return actionItem(Material.GREEN_TERRACOTTA, "💰 For Sale: " + Planets.formatPrice(data.salePrice()) + " VPL",
                    "Currently on sale — click to remove from sale");
        }
        return actionItem(Material.EMERALD, "💰 Put Up for Sale",
                "Set the price (opens chat prompt)");
    }

    private static ItemStack disabledItem(String name, String description) {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.GRAY));
        meta.lore(List.of(Component.text(description).color(NamedTextColor.DARK_GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack iconButtonItem() {
        Material current = plugin.iconOverride(data.worldName());
        return actionItem(Material.PAINTING, "🎨 Icon",
                "Menu icon: " + (current != null ? current.name() : "default"));
    }

    // ── Item builders ─────────────────────────────────────────────────────

    /** Shows a live count of who's currently inside the planet. */
    private ItemStack liveVisitorsItem() {
        World world = Bukkit.getWorld(data.worldName());
        List<Player> currentPlayers = world != null ? world.getPlayers() : List.of();
        int count = currentPlayers.size();
        Material mat = count > 0 ? Material.PLAYER_HEAD : Material.SKELETON_SKULL;
        List<Component> lore = new ArrayList<>();
        if (count == 0) {
            lore.add(Component.text("No players online").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            for (Player p : currentPlayers) {
                boolean isMember = data.isMember(p.getUniqueId());
                String tag = isMember ? " [member]" : " [visitor]";
                lore.add(Component.text(p.getName() + tag).color(isMember ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("🟢 Live Visitors: " + count).color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Block placement leaderboard: shows the planet-wide limit/usage and the
     * top block-placing players (the whole planet shares one limit).
     */
    private ItemStack blockStatsItem() {
        int limit = data.blockLimit();
        int total = data.totalBlocksPlaced();
        ItemStack item = new ItemStack(Material.STONE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u25A3 Block Leaderboard").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        String limitLine = total + "/" + limit + " blocks placed (planet-wide, incl. starter 20)";
        lore.add(Component.text(limitLine).color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        List<Map.Entry<java.util.UUID, Integer>> top = data.blockLeaderboard();
        if (top.isEmpty()) {
            lore.add(Component.text("No blocks placed yet").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("\u2014 Top Builders \u2014").color(NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            int rank = 1;
            for (Map.Entry<java.util.UUID, Integer> entry : top) {
                if (rank > 10) break; // show only the top 10
                String name = playerName(entry.getKey());
                lore.add(Component.text("#" + rank + " " + name + " \u2014 " + entry.getValue() + " blocks")
                        .color(switch (rank) {
                            case 1 -> NamedTextColor.GOLD;
                            case 2 -> NamedTextColor.GRAY;
                            case 3 -> NamedTextColor.YELLOW;
                            default -> NamedTextColor.WHITE;
                        }).decoration(TextDecoration.ITALIC, false));
                rank++;
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack actionItem(Material material, String name, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(description).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack lockItem() {
        boolean locked = data.status() == MyPlanetData.Status.LOCKED;
        Material mat = locked ? Material.REDSTONE_BLOCK : Material.LIME_DYE;
        String name = locked ? "🔓 Unlock Planet" : "🔒 Lock Planet";
        String desc = locked ? "Currently LOCKED — click to unlock" : "Currently OPEN — click to lock";
        return actionItem(mat, name, desc);
    }

    private static ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("← Back to My Planets").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack fillerItem() {
        return MenuStyle.field();
    }

    private static ItemStack lightBlueFillerItem() {
        return MenuStyle.frame();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String getOwnerName() {
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(data.ownerUuid());
        String name = offline.getName();
        return name != null ? name : data.ownerUuid().toString().substring(0, 8);
    }

    /** Resolves a player UUID to a name, falling back to a short uuid. */
    private static String playerName(java.util.UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }
}
