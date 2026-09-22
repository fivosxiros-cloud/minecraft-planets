package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
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
import java.util.Arrays;
import java.util.List;

/**
 * The lobby selection chest, opened by {@code /lobby}, {@code /hub} and {@code /l}.
 * <p>
 * Every slot is filled with a gray stained-glass pane (background); each lobby
 * sits on top at the slot pinned by its config "slot" value (falling back to the
 * first free slot when unset), so the layout can match a fixed screenshot
 * exactly. The info item (config {@code lobbies-info}) is pinned bottom-right.
 * Clicking a lobby teleports the player to its landing spot; glass panes and the
 * info item do nothing. Locked worlds need the usual
 * {@code planets.tp.&lt;world&gt;} permission and render grayed out.
 */
public final class LobbiesMenu implements InventoryHolder {

    /** The orange theme of the lobby menu (title and item names). */
    private static final TextColor ORANGE = TextColor.color(0xFFA500);

    private static final int SIZE = 27;
    private static final int INFO_SLOT = 26; // bottom-right corner

    private final Planets plugin;
    private final List<Planets.Lobby> lobbies;
    private final Player viewer;
    private final Inventory inventory;
    /** The lobby sitting in each slot, or null for a glass pane / the info item. */
    private final Planets.Lobby[] placed = new Planets.Lobby[SIZE];

    public LobbiesMenu(Planets plugin, List<Planets.Lobby> lobbies, Player viewer) {
        this.plugin = plugin;
        this.lobbies = lobbies;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("LOBBIES").color(ORANGE));
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
        Planets.Lobby lobby = placed[slot];
        if (lobby == null) {
            return; // a gray background pane or the info item
        }
        if (!plugin.canVisit(player, lobby.asPlanet())) {
            player.sendMessage(Component.text("You don't have permission to visit this lobby.").color(NamedTextColor.RED));
            return;
        }
        plugin.teleportToLobby(player, lobby);
    }

    /** Whether this lobby should render grayed out for the menu's viewer. */
    private boolean isLocked(Planets.Lobby lobby) {
        return !plugin.canVisit(viewer, lobby.asPlanet());
    }

    private void render() {
        inventory.clear();
        Arrays.fill(placed, null);
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, fillerItem());
        }
        boolean[] used = new boolean[SIZE];
        for (Planets.Lobby lobby : lobbies) {
            int slot = resolveSlot(lobby, used);
            if (slot < 0) {
                continue;
            }
            used[slot] = true;
            placed[slot] = lobby;
            inventory.setItem(slot, lobbyItem(lobby, isLocked(lobby)));
        }
        Planets.LobbyInfo info = plugin.lobbyInfo();
        if (info != null) {
            inventory.setItem(INFO_SLOT, infoItem(info));
        }
    }

    /** The lobby's configured slot, or the first free slot when unset or taken. */
    private static int resolveSlot(Planets.Lobby lobby, boolean[] used) {
        Integer wanted = lobby.slot();
        if (wanted != null && wanted >= 0 && wanted < SIZE && wanted != INFO_SLOT && !used[wanted]) {
            return wanted;
        }
        for (int slot = 0; slot < SIZE; slot++) {
            if (slot != INFO_SLOT && !used[slot]) {
                return slot;
            }
        }
        return -1;
    }

    private ItemStack lobbyItem(Planets.Lobby lobby, boolean locked) {
        ItemStack item = new ItemStack(lobby.icon());
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        TextColor nameColor = ORANGE;
        if (locked) {
            nameColor = NamedTextColor.GRAY;
            lore.add(Component.text("Locked — needs planets.tp." + lobby.worldName().toLowerCase())
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }
        meta.displayName(Component.text(lobby.name()).color(nameColor)
                .decoration(TextDecoration.ITALIC, false));

        if (!locked) {
            if (lobby.description() != null && !lobby.description().isBlank()) {
                lore.add(Component.text(lobby.description()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.text("Click to teleport").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            World world = Bukkit.getWorld(lobby.worldName());
            if (world != null) {
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

    private static ItemStack infoItem(Planets.LobbyInfo info) {
        ItemStack item = new ItemStack(info.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(info.name()).color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, true));
        List<Component> lore = new ArrayList<>();
        for (String line : info.lore()) {
            lore.add(Component.text(line).color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * The gray background pane filling every empty slot. Keeps its default
     * "Gray Stained Glass Pane" name (like the screenshot's tooltip).
     */
    private static ItemStack fillerItem() {
        return MenuStyle.field();
    }
}