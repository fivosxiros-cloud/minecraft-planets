package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The song browser behind {@code /music}: every vanilla music track and music
 * disc this server knows, one page at a time.
 *
 * <p>Left-clicking a song plays it right away. The menu also comes in an
 * "add" mode, opened from a playlist's editor: there, clicking a song appends
 * it to that playlist instead of playing it, and the title says so.
 *
 * <pre>
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣        up to 45 songs a page
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   ⬅ ◀ ▣ ♪ ▣ ⏹ ▣ ▶ ➡          ⬅ page back, 📚 playlists, ▶ page on, ⏹ stop
 * </pre>
 */
public final class MusicMenu implements InventoryHolder {

    private static final int SIZE = 54;
    /** Slots 0-44 hold songs (5 rows of 9). */
    private static final int SONG_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int PLAYLISTS_SLOT = 46;
    private static final int STOP_SLOT = 48;
    private static final int INFO_SLOT = 49;
    private static final int DONE_SLOT = 50;
    private static final int NEXT_SLOT = 53;

    private final Planets plugin;
    private final Player viewer;
    private final PlayerMusic music;
    private final List<String> songs;
    /** The playlist songs are appended to, or null when songs just play. */
    private final String addToPlaylist;

    private final Inventory inventory;
    private int page;

    /** The normal browser: clicking a song plays it. */
    public MusicMenu(Planets plugin, Player viewer) {
        this(plugin, viewer, null);
    }

    /** The "add to playlist" flavour: clicking a song appends it to the named playlist. */
    public MusicMenu(Planets plugin, Player viewer, String addToPlaylist) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.music = plugin.playerMusic();
        this.songs = PlanetMusic.musicSoundNames();
        this.addToPlaylist = addToPlaylist;
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text(addToPlaylist == null
                ? "\uD83C\uDFB5 Music"
                : "\uD83C\uDFB5 Add to " + addToPlaylist).color(NamedTextColor.DARK_PURPLE));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    private int maxPage() {
        return Math.max(0, (songs.size() - 1) / SONG_SLOTS);
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (slot == PREVIOUS_SLOT) {
            if (page > 0) {
                page--;
                render();
            }
            return;
        }
        if (slot == NEXT_SLOT) {
            if (page < maxPage()) {
                page++;
                render();
            }
            return;
        }
        if (slot == PLAYLISTS_SLOT) {
            player.closeInventory();
            new MusicPlaylistsMenu(plugin, player).open(player);
            return;
        }
        if (slot == STOP_SLOT) {
            boolean wasPlaying = music.stop(player);
            player.closeInventory();
            player.sendMessage(Component.text("\u23F9 Music stopped.").color(NamedTextColor.GRAY));
            player.sendMessage(Component.text(wasPlaying
                    ? "The planet soundtrack stays quiet here until you move on or ask for music again."
                    : "Nothing of yours was playing - music is already quiet here.")
                    .color(NamedTextColor.DARK_GRAY));
            return;
        }
        if (slot == DONE_SLOT && addToPlaylist != null) {
            player.closeInventory();
            new MusicPlaylistEditMenu(plugin, player, addToPlaylist).open(player);
            return;
        }
        if (slot >= SONG_SLOTS) {
            return;
        }
        // The clicked song is found from the page and the slot, which is how
        // the menu drew it in the first place.
        int index = page * SONG_SLOTS + slot;
        if (index < 0 || index >= songs.size()) {
            return;
        }
        String song = songs.get(index);
        Sound sound = PlanetMusic.sound(song);
        if (sound == null) {
            return;
        }
        if (addToPlaylist == null) {
            music.playNow(player, sound);
            player.sendMessage(Component.text("\u25B6 Now playing: ").color(NamedTextColor.GREEN)
                    .append(Component.text(PlayerMusic.pretty(song)).color(NamedTextColor.YELLOW)));
            render();
        } else {
            PlayerMusic.AddResult result = music.addSong(player.getUniqueId(), addToPlaylist, song);
            switch (result) {
                case OK -> {
                    player.sendMessage(Component.text("\u2795 Added ").color(NamedTextColor.GREEN)
                            .append(Component.text(PlayerMusic.pretty(song)).color(NamedTextColor.YELLOW))
                            .append(Component.text(" to " + addToPlaylist + ".").color(NamedTextColor.GREEN)));
                    render();
                }
                case FULL -> player.sendMessage(Component.text("This playlist is full (")
                        .color(NamedTextColor.RED)
                        .append(Component.text(music.maxSongs() + " songs").color(NamedTextColor.YELLOW))
                        .append(Component.text(" max).").color(NamedTextColor.RED)));
                case NO_PLAYLIST -> {
                    player.sendMessage(Component.text("That playlist no longer exists.").color(NamedTextColor.RED));
                    player.closeInventory();
                    new MusicPlaylistsMenu(plugin, player).open(player);
                }
            }
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        for (int i = 0; i < SONG_SLOTS; i++) {
            inventory.setItem(i, filler());
        }
        int start = page * SONG_SLOTS;
        for (int i = 0; i < SONG_SLOTS && start + i < songs.size(); i++) {
            inventory.setItem(i, songItem(songs.get(start + i)));
        }
        if (songs.isEmpty()) {
            inventory.setItem(22, emptyItem());
        }

        inventory.setItem(PREVIOUS_SLOT, pageItem("Previous Page", page > 0));
        inventory.setItem(NEXT_SLOT, pageItem("Next Page", page < maxPage()));
        inventory.setItem(STOP_SLOT, stopItem());
        inventory.setItem(INFO_SLOT, infoItem());
        if (addToPlaylist != null) {
            inventory.setItem(DONE_SLOT, doneItem());
        } else {
            inventory.setItem(PLAYLISTS_SLOT, playlistsItem());
        }
    }

    private ItemStack songItem(String soundName) {
        boolean disc = soundName.startsWith("MUSIC_DISC") || soundName.startsWith("RECORD_");
        ItemStack item = new ItemStack(disc ? Material.MUSIC_DISC_CAT : Material.NOTE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u266A " + PlayerMusic.pretty(soundName))
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(disc ? "Music disc" : "Music track").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(soundName).color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(addToPlaylist == null
                        ? "Click to play it now" : "Click to add it to " + addToPlaylist)
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        String now = music.isPlaying(viewer.getUniqueId()) ? music.nowPlaying(viewer.getUniqueId()) : null;
        meta.displayName(Component.text("\uD83C\uDFB5 Personal Music").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(songs.size() + " songs available, " + (maxPage() + 1) + " page(s)")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (now != null) {
            lore.add(Component.text("\u25B6 " + now).color(NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Nothing playing right now").color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Only you hear what you pick here").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack playlistsItem() {
        int count = music.playlists(viewer.getUniqueId()).size();
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDCDA Playlists").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(count + "/" + music.maxPlaylists() + " playlists").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Build small playlists that loop").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to open them").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Or start one from chat: /music <name>").color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack stopItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u23F9 Stop Music").color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Silences your personal music").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("and keeps the planet track quiet too").color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack doneItem() {
        ItemStack item = new ItemStack(Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2190 Done \u2014 back to the playlist").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("No songs available").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageItem(String name, boolean active) {
        ItemStack item = new ItemStack(active ? Material.SPECTRAL_ARROW : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)
                .color(active ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Page " + (page + 1) + "/" + (maxPage() + 1))
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        return MenuStyle.field();
    }
}
