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
 * One playlist's songs: play it, reorder it by removing, and add more from the
 * whole song catalogue.
 *
 * <pre>
 *   ♪ ♪ ♪ ♪ ♪ ♪ ♪ ♪ ♪        up to 45 songs a page
 *   ...
 *   ⬅ ▶ ⏭ ⏹ ♪ ➕ 🗑 ← ✖      play / next / stop, add songs, delete, back, close
 *   ⬅ ▶ ⏭ ⏹ ♪ ➕ 🗑 ✏ ← ✖    play / next / stop, add, delete, rename, back, close
 * </pre>
 *
 * <p>Left-clicking a song plays the playlist from there, right-clicking takes
 * the song out, and shift-clicking moves it up or down the list. Songs can also
 * be reordered, and the playlist renamed, from the buttons along the bottom.
 */
public final class MusicPlaylistEditMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int SONG_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int PLAY_SLOT = 46;
    private static final int NEXT_SONG_SLOT = 47;
    private static final int STOP_SLOT = 48;
    private static final int RENAME_SLOT = 49;
    private static final int ADD_SLOT = 50;
    private static final int DELETE_SLOT = 51;
    private static final int BACK_SLOT = 52;
    private static final int CLOSE_SLOT = 53;

    private final Planets plugin;
    private final Player viewer;
    private final PlayerMusic music;
    private final String playlistName;
    private final Inventory inventory;
    private int page;

    public MusicPlaylistEditMenu(Planets plugin, Player viewer, String playlistName) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.music = plugin.playerMusic();
        this.playlistName = playlistName;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83D\uDCD6 " + playlistName).color(NamedTextColor.DARK_PURPLE));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    private PlayerMusic.Playlist playlist() {
        return music.playlist(viewer.getUniqueId(), playlistName);
    }

    private List<String> songs() {
        PlayerMusic.Playlist playlist = playlist();
        return playlist == null ? List.of() : playlist.songs();
    }

    private int maxPage() {
        return Math.max(0, (songs().size() - 1) / SONG_SLOTS);
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        switch (slot) {
            case PREVIOUS_SLOT -> {
                if (page > 0) {
                    page--;
                    render();
                }
                return;
            }
            case PLAY_SLOT -> {
                if (!music.startPlaylist(player, playlistName, 0)) {
                    player.sendMessage(Component.text("This playlist has no songs yet — add some first.")
                            .color(NamedTextColor.RED));
                    return;
                }
                player.sendMessage(Component.text("\u25B6 Playing ").color(NamedTextColor.GREEN)
                        .append(Component.text(playlistName).color(NamedTextColor.YELLOW))
                        .append(Component.text(" (looping).").color(NamedTextColor.GREEN)));
                return;
            }
            case NEXT_SONG_SLOT -> {
                if (!music.next(player)) {
                    player.sendMessage(Component.text("Nothing is playing from this playlist right now.")
                            .color(NamedTextColor.RED));
                }
                return;
            }
            case STOP_SLOT -> {
                boolean wasPlaying = music.stop(player);
                player.sendMessage(Component.text("\u23F9 Music stopped.").color(NamedTextColor.GRAY));
                player.sendMessage(Component.text(wasPlaying
                        ? "The planet soundtrack stays quiet here until you move on or ask for music again."
                        : "Nothing of yours was playing - music is already quiet here.")
                        .color(NamedTextColor.DARK_GRAY));
                return;
            }
            case RENAME_SLOT -> {
                plugin.promptRenamePlaylist(player, playlistName);
                return;
            }
            case ADD_SLOT -> {
                player.closeInventory();
                new MusicMenu(plugin, player, playlistName).open(player);
                return;
            }
            case DELETE_SLOT -> {
                music.deletePlaylist(player.getUniqueId(), playlistName);
                player.sendMessage(Component.text("\uD83D\uDDD1 Deleted playlist ").color(NamedTextColor.RED)
                        .append(Component.text(playlistName).color(NamedTextColor.YELLOW))
                        .append(Component.text(".").color(NamedTextColor.RED)));
                player.closeInventory();
                new MusicPlaylistsMenu(plugin, player).open(player);
                return;
            }
            case BACK_SLOT -> {
                player.closeInventory();
                new MusicPlaylistsMenu(plugin, player).open(player);
                return;
            }
            case CLOSE_SLOT -> {
                player.closeInventory();
                return;
            }
            default -> {
            }
        }
        if (slot >= SONG_SLOTS) {
            return;
        }
        List<String> songs = songs();
        int index = page * SONG_SLOTS + slot;
        if (index < 0 || index >= songs.size()) {
            return;
        }
        String song = songs.get(index);
        if (event.isShiftClick()) {
            // Shift-click shuffles this song a place up or down the list.
            int to = event.isRightClick() ? index + 1 : index - 1;
            if (music.moveSong(player.getUniqueId(), playlistName, index, to)) {
                player.sendMessage(Component.text("\u2195 Moved ").color(NamedTextColor.GREEN)
                        .append(Component.text(PlayerMusic.pretty(song)).color(NamedTextColor.YELLOW))
                        .append(Component.text(" to position " + (to + 1) + ".")
                                .color(NamedTextColor.GREEN)));
                render();
            } else {
                player.sendMessage(Component.text("That song is already at the ")
                        .color(NamedTextColor.RED)
                        .append(Component.text(event.isRightClick() ? "end" : "start")
                                .color(NamedTextColor.YELLOW))
                        .append(Component.text(" of the playlist.").color(NamedTextColor.RED)));
            }
            return;
        }
        if (event.isRightClick()) {
            if (music.removeSong(player.getUniqueId(), playlistName, index)) {
                player.sendMessage(Component.text("\u2796 Removed ").color(NamedTextColor.RED)
                        .append(Component.text(PlayerMusic.pretty(song)).color(NamedTextColor.YELLOW))
                        .append(Component.text(" from " + playlistName + ".").color(NamedTextColor.RED)));
                render();
            }
            return;
        }
        if (music.startPlaylist(player, playlistName, index)) {
            player.sendMessage(Component.text("\u25B6 Playing ").color(NamedTextColor.GREEN)
                    .append(Component.text(playlistName).color(NamedTextColor.YELLOW))
                    .append(Component.text(" from " + PlayerMusic.pretty(song) + ".").color(NamedTextColor.GREEN)));
            render();
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        PlayerMusic.Playlist playlist = playlist();
        if (playlist == null) {
            // Deleted on another screen: fall back to the shelf.
            inventory.clear();
            return;
        }
        inventory.clear();
        List<String> songs = playlist.songs();
        for (int i = 0; i < SONG_SLOTS; i++) {
            inventory.setItem(i, filler());
        }
        int start = page * SONG_SLOTS;
        for (int i = 0; i < SONG_SLOTS && start + i < songs.size(); i++) {
            inventory.setItem(i, songItem(songs.get(start + i), start + i + 1));
        }
        if (songs.isEmpty()) {
            ItemStack empty = new ItemStack(Material.COBWEB);
            ItemMeta meta = empty.getItemMeta();
            meta.displayName(Component.text("This playlist is empty").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Use \u2795 Add Songs to fill it.").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            empty.setItemMeta(meta);
            inventory.setItem(22, empty);
        }

        inventory.setItem(PREVIOUS_SLOT, pageItem("Previous Page", page > 0));
        inventory.setItem(PLAY_SLOT, actionItem(Material.GREEN_DYE, "\u25B6 Play Playlist",
                "Plays from the first song and keeps going"));
        inventory.setItem(NEXT_SONG_SLOT, actionItem(Material.SPECTRAL_ARROW, "\u23ED Next Song",
                "Skip to the next song of this playlist"));
        inventory.setItem(STOP_SLOT, actionItem(Material.RED_DYE, "\u23F9 Stop",
                "Stops your personal music"));
        inventory.setItem(ADD_SLOT, actionItem(Material.EMERALD, "\u2795 Add Songs",
                "Pick from every song this server has"));
        inventory.setItem(RENAME_SLOT, actionItem(Material.NAME_TAG, "\u270F Rename Playlist",
                "Type the new name in chat - /music <name> then starts it"));
        inventory.setItem(DELETE_SLOT, actionItem(Material.LAVA_BUCKET, "\uD83D\uDDD1 Delete Playlist",
                "Removes this playlist for good"));
        inventory.setItem(BACK_SLOT, actionItem(Material.ARROW, "\u2190 Back",
                "Back to your playlists"));
        inventory.setItem(CLOSE_SLOT, actionItem(Material.BARRIER, "\u2716 Close", "Closes the menu"));
    }

    private ItemStack songItem(String soundName, int position) {
        boolean disc = soundName.startsWith("MUSIC_DISC") || soundName.startsWith("RECORD_");
        ItemStack item = new ItemStack(disc ? Material.MUSIC_DISC_CAT : Material.NOTE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(position + ". \u266A " + PlayerMusic.pretty(soundName))
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(soundName).color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Left-click: play from here").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Shift-left-click: move it up").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Shift-right-click: move it down").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click: remove from the playlist").color(NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageItem(String name, boolean active) {
        ItemStack item = new ItemStack(active ? Material.SPECTRAL_ARROW : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)
                .color(active ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Page " + (page + 1) + "/" + (maxPage() + 1))
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(songs().size() + "/" + music.maxSongs() + " songs")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
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

    private static ItemStack filler() {
        return MenuStyle.field();
    }
}
