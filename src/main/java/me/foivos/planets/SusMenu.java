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
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The "/sus list" (alias "/sl") menu for ops: every flagged player as a head.
 * Clicking the head of a player who is <b>online</b> puts the admin straight
 * into spectator mode and teleports them to that player; clicking an offline
 * head only reports that they are offline, because there is nobody to teleport
 * to. Heads are otherwise decoration — the empty space is made of light blue
 * glass panes, and the page arrows handle long lists.
 *
 * <p>Layout (45 slots, 5 rows):
 * <ul>
 *   <li>Rows 0-3, columns 1-7: up to 28 flagged players per page.</li>
 *   <li>Column 0 and column 8 of those rows plus the whole bottom row: light
 *       blue stained glass panes, holding the arrows and the summary item.</li>
 * </ul>
 */
public final class SusMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int PER_PAGE = 28;         // 4 rows x 7 columns
    private static final int PREVIOUS_SLOT = 37;
    private static final int INFO_SLOT = 40;
    private static final int NEXT_SLOT = 43;

    private static final DateTimeFormatter ADDED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Planets plugin;
    private final Player viewer;
    private final List<Planets.SusEntry> entries;
    private final Inventory inventory;
    private int page;

    public SusMenu(Planets plugin, Player viewer, List<Planets.SusEntry> entries) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.entries = entries;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83D\uDD75 SUS List").color(NamedTextColor.DARK_RED));
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

    /** Heads teleport to online players; the arrows page, the summary item clears spectator mode. */
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !plugin.canUseSus(player)) {
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
        if (slot == INFO_SLOT) {
            if (plugin.isSpectating(player)) {
                plugin.stopSpectating(player);
            } else {
                player.sendMessage(Component.text("Nothing to stop — you are not spectating anyone.")
                        .color(NamedTextColor.GRAY));
            }
            render();
            return;
        }

        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return; // frame, arrows or empty slot
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta skull) || skull.getOwningPlayer() == null) {
            return;
        }
        Planets.SusEntry entry = find(skull.getOwningPlayer().getUniqueId());
        if (entry == null) {
            return;
        }
        plugin.spectateSus(player, entry);
        render();
    }

    private Planets.SusEntry find(java.util.UUID uuid) {
        for (Planets.SusEntry entry : entries) {
            if (entry.uuid().equals(uuid)) {
                return entry;
            }
        }
        return null;
    }

    private int maxPage() {
        return Math.max(0, (entries.size() - 1) / PER_PAGE);
    }

    private void render() {
        inventory.clear();

        // Ornate frame (gold corners + edges) around the crimson field.
        MenuStyle.decorate(inventory, "⚠ Flagged", "👁 Spectate");

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < entries.size(); i++) {
            int row = i / 7;
            int column = (i % 7) + 1;
            inventory.setItem(row * 9 + column, headItem(entries.get(start + i)));
        }

        inventory.setItem(PREVIOUS_SLOT, arrowItem("Previous Page",
                page > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(NEXT_SLOT, arrowItem("Next Page",
                page < maxPage() ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(INFO_SLOT, infoItem());
    }

    /** A flagged player's head, clickable only while they are online. */
    private ItemStack headItem(Planets.SusEntry entry) {
        Player online = Bukkit.getPlayer(entry.uuid());
        boolean isOnline = online != null;

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.uuid()));
        meta.displayName(Component.text((isOnline ? "\u26A0 " : "\u2716 ") + entry.name())
                .color(isOnline ? NamedTextColor.RED : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(isOnline
                        ? "ONLINE — click to spectate and teleport to them"
                        : "OFFLINE — not online, cannot be teleported to")
                .color(isOnline ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        if (isOnline) {
            lore.add(Component.text("Current world: " + online.getWorld().getName())
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Flagged by: " + entry.addedBy()).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Flagged at: " + ADDED_FORMAT.format(Instant.ofEpochMilli(entry.addedAt())))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Remove with /sus remove " + entry.name()).color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Summary item: list size, online count and the "stop spectating" action. */
    private ItemStack infoItem() {
        int onlineCount = 0;
        for (Planets.SusEntry entry : entries) {
            if (Bukkit.getPlayer(entry.uuid()) != null) {
                onlineCount++;
            }
        }
        boolean spectating = plugin.isSpectating(viewer);

        ItemStack item = new ItemStack(spectating ? Material.ENDER_EYE : Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(spectating ? "\u25C9 Stop spectating" : "SUS players")
                .color(spectating ? NamedTextColor.AQUA : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(entries.size() + " flagged player(s) · " + onlineCount + " online")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Online players are listed first").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (spectating) {
            lore.add(Component.text("Click to leave spectator mode").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
        int first = entries.isEmpty() ? 0 : page * PER_PAGE + 1;
        int last = Math.min((page + 1) * PER_PAGE, entries.size());
        lore.add(Component.text("Page " + (page + 1) + "/" + (maxPage() + 1) + " · players "
                        + first + "-" + last + " of " + entries.size())
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
