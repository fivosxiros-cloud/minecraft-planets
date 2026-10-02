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
 * The playlist shelf behind {@code /music → 📚 Playlists}: every playlist a
 * player has, plus the button that makes a new one.
 *
 * <pre>
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   ▣ 📚📚📚📚📚 ▣      up to 7 playlists (the configured maximum)
 *   ▣ ▣ ▣ ▣ ▣ ➕ ▣ ◀
 * </pre>
 *
 * <p>Left-click a playlist to play it, right-click to edit it,
 * shift-left-click to rename it, and shift-right-click to delete it.
 * A playlist can also be started from chat with {@code /music <name>}.
 */
public final class MusicPlaylistsMenu implements InventoryHolder {

    private static final int SIZE = 27;
    private static final int[] PLAYLIST_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int CREATE_SLOT = 22;
    private static final int BACK_SLOT = 26;

    private final Planets plugin;
    private final Player viewer;
    private final PlayerMusic music;
    private final Inventory inventory;

    public MusicPlaylistsMenu(Planets plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.music = plugin.playerMusic();
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83D\uDCDA Your Playlists").color(NamedTextColor.DARK_PURPLE));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (slot == CREATE_SLOT) {
            PlayerMusic.Playlist created = music.createPlaylist(player.getUniqueId());
            if (created == null) {
                player.sendMessage(Component.text("You already have the maximum of ")
                        .color(NamedTextColor.RED)
                        .append(Component.text(music.maxPlaylists() + " playlists").color(NamedTextColor.YELLOW))
                        .append(Component.text(".").color(NamedTextColor.RED)));
                return;
            }
            player.closeInventory();
            new MusicPlaylistEditMenu(plugin, player, created.name()).open(player);
            return;
        }
        if (slot == BACK_SLOT) {
            player.closeInventory();
            new MusicMenu(plugin, player).open(player);
            return;
        }

        int playlistIndex = -1;
        for (int i = 0; i < PLAYLIST_SLOTS.length; i++) {
            if (PLAYLIST_SLOTS[i] == slot) {
                playlistIndex = i;
                break;
            }
        }
        if (playlistIndex < 0) {
            return;
        }
        List<PlayerMusic.Playlist> playlists = music.playlists(player.getUniqueId());
        if (playlistIndex >= playlists.size()) {
            return;
        }
        PlayerMusic.Playlist playlist = playlists.get(playlistIndex);
        if (event.isLeftClick() && event.isShiftClick()) {
            plugin.promptRenamePlaylist(player, playlist.name());
            return;
        }
        if (event.isRightClick() && event.isShiftClick()) {
            music.deletePlaylist(player.getUniqueId(), playlist.name());
            player.sendMessage(Component.text("\uD83D\uDDD1 Deleted playlist ").color(NamedTextColor.RED)
                    .append(Component.text(playlist.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            render();
            return;
        }
        if (event.isRightClick()) {
            player.closeInventory();
            new MusicPlaylistEditMenu(plugin, player, playlist.name()).open(player);
            return;
        }
        if (!music.startPlaylist(player, playlist.name(), 0)) {
            player.sendMessage(Component.text("That playlist is empty — right-click it to add songs.")
                    .color(NamedTextColor.RED));
            player.closeInventory();
            new MusicPlaylistEditMenu(plugin, player, playlist.name()).open(player);
            return;
        }
        player.sendMessage(Component.text("\u25B6 Playing ").color(NamedTextColor.GREEN)
                .append(Component.text(playlist.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" (" + playlist.songs().size() + " songs, looping).")
                        .color(NamedTextColor.GREEN)));
        render();
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, filler());
        }
        List<PlayerMusic.Playlist> playlists = music.playlists(viewer.getUniqueId());
        for (int i = 0; i < PLAYLIST_SLOTS.length && i < playlists.size(); i++) {
            inventory.setItem(PLAYLIST_SLOTS[i], playlistItem(playlists.get(i)));
        }
        if (playlists.isEmpty()) {
            ItemStack empty = new ItemStack(Material.COBWEB);
            ItemMeta meta = empty.getItemMeta();
            meta.displayName(Component.text("No playlists yet").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Use the button on the right to make one.").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            empty.setItemMeta(meta);
            inventory.setItem(13, empty);
        }
        inventory.setItem(CREATE_SLOT, createItem(playlists.size()));
        inventory.setItem(BACK_SLOT, backItem());
    }

    private ItemStack playlistItem(PlayerMusic.Playlist playlist) {
        String runningName = music.runningPlaylist(viewer.getUniqueId());
        boolean isRunning = runningName != null && playlist.name().equalsIgnoreCase(runningName);
        ItemStack item = new ItemStack(isRunning ? Material.WRITTEN_BOOK : Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((isRunning ? "\u25B6 " : "\uD83D\uDCD6 ") + playlist.name())
                .color(isRunning ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(playlist.songs().size() + "/" + music.maxSongs() + " songs")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        for (String song : playlist.songs()) {
            lore.add(Component.text("\u2022 " + PlayerMusic.pretty(song)).color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (playlist.songs().isEmpty()) {
            lore.add(Component.text("(empty)").color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Left-click: play it").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Right-click: edit the songs").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Shift-left-click: rename it").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Shift-right-click: delete it").color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("From chat: /music " + playlist.name()).color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createItem(int count) {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        boolean room = count < music.maxPlaylists();
        meta.displayName(Component.text("\u2795 New Playlist")
                .color(room ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(count + "/" + music.maxPlaylists() + " playlists used").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(room ? "Click to create an empty playlist" : "You've reached the maximum")
                        .color(room ? NamedTextColor.YELLOW : NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2190 Back to all songs").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        return MenuStyle.field();
    }
}
