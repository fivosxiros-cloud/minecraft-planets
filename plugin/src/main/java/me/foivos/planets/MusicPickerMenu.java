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
 * The "add a track" list of {@link AdminMusicMenu}: every music sound the server
 * knows, paged, soundtracks first and music discs after them. Clicking one adds
 * it to the planet's soundtrack with the plugin's default length and goes back
 * to the editor with it already picked and playing, so a wrong pick is obvious
 * straight away.
 */
public final class MusicPickerMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int PER_PAGE = 28;         // 4 rows x 7 columns
    private static final int PREV_SLOT = 47;
    private static final int PAGE_SLOT = 49;
    private static final int NEXT_SLOT = 51;
    private static final int BACK_SLOT = 53;

    private final Planets plugin;
    private final Player viewer;
    private final String worldName;
    /** The lobby this soundtrack belongs to, or null for a planet. */
    private final String lobbyId;
    private final List<String> sounds;
    private final Inventory inventory;
    private int page;

    public MusicPickerMenu(Planets plugin, Player viewer, String worldName, int page, String lobbyId) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.worldName = worldName;
        this.lobbyId = lobbyId;
        this.sounds = PlanetMusic.musicSoundNames();
        this.page = Math.max(0, Math.min(page, lastPage()));
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83C\uDFB5 Add music: " + worldName).color(NamedTextColor.DARK_AQUA));
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
        switch (slot) {
            case PREV_SLOT -> {
                if (page > 0) {
                    page--;
                    render();
                }
                return;
            }
            case NEXT_SLOT -> {
                if (page < lastPage()) {
                    page++;
                    render();
                }
                return;
            }
            case BACK_SLOT, PAGE_SLOT -> {
                new AdminMusicMenu(plugin, player, worldName, -1, lobbyId).open(player);
                return;
            }
            default -> {
                // fall through to the track grid below
            }
        }

        int index = indexAt(slot);
        if (index < 0) {
            return;
        }
        add(player, sounds.get(index));
    }

    /** Adds a sound to the planet's own list and reopens the editor on it. */
    private void add(Player player, String soundName) {
        List<String> specs = new ArrayList<>(plugin.planetMusic().ownTrackSpecs(worldName));
        boolean inheritsDefault = !plugin.planetMusic().hasOwnTracks(worldName);
        specs.add(soundName + ":" + PlanetMusic.defaultTrackSeconds());
        plugin.planetMusic().saveOwnTracks(worldName, specs);

        player.sendMessage(Component.text("\u271A Added ").color(NamedTextColor.GREEN)
                .append(Component.text(AdminMusicMenu.pretty(soundName)).color(NamedTextColor.YELLOW))
                .append(Component.text(" to " + worldName + "'s soundtrack."
                        + (inheritsDefault
                        ? " It now has its own list instead of the shared default one." : ""))
                        .color(NamedTextColor.GRAY)));
        // Opens the editor with the new track picked and playing.
        new AdminMusicMenu(plugin, player, worldName, specs.size() - 1, lobbyId).open(player);
    }

    private int lastPage() {
        return Math.max(0, (sounds.size() - 1) / PER_PAGE);
    }

    /** The sound index a slot holds, or -1 when the slot is not part of the grid. */
    private int indexAt(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        if (row > 3 || column == 0 || column == 8) {
            return -1;
        }
        int index = page * PER_PAGE + row * 7 + (column - 1);
        return index >= 0 && index < sounds.size() ? index : -1;
    }

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83C\uDFB5 Music", "\uD83C\uDFB5 Discs");

        if (sounds.isEmpty()) {
            inventory.setItem(22, messageItem("This server lists no music sounds",
                    "Config.yml can still name them by hand:", "music.tracks." + worldName));
        }

        int start = page * PER_PAGE;
        int shown = Math.min(PER_PAGE, sounds.size() - start);
        for (int i = 0; i < shown; i++) {
            int row = i / 7;
            int column = (i % 7) + 1;
            inventory.setItem(row * 9 + column, soundItem(sounds.get(start + i)));
        }

        inventory.setItem(PREV_SLOT, action(Material.SPECTRAL_ARROW, "\u25C0 Previous page",
                List.of("Page " + (page + 1) + " of " + (lastPage() + 1)), page > 0));
        inventory.setItem(PAGE_SLOT, messageItem("Page " + (page + 1) + " of " + (lastPage() + 1),
                "Click here to go back to the editor",
                sounds.size() + " music sound(s) available"));
        inventory.setItem(NEXT_SLOT, action(Material.SPECTRAL_ARROW, "Next page \u25B6",
                List.of("Page " + (page + 1) + " of " + (lastPage() + 1)), page < lastPage()));
        inventory.setItem(BACK_SLOT, action(Material.BARRIER, "\u21A9 Back to the editor",
                List.of("Nothing is added until you click a track"), true));
    }

    private ItemStack soundItem(String soundName) {
        Material material = Material.NOTE_BLOCK;
        if (soundName.startsWith("MUSIC_DISC")) {
            Material disc = Material.matchMaterial(soundName);
            if (disc != null) {
                material = disc;
            }
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(AdminMusicMenu.pretty(soundName)).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("Adds it to " + worldName + "'s soundtrack", NamedTextColor.GRAY),
                line("and plays it straight away", NamedTextColor.GRAY),
                line("", NamedTextColor.GRAY),
                line("Length starts at " + PlanetMusic.defaultTrackSeconds()
                        + "s — change it in the editor", NamedTextColor.DARK_GRAY),
                line("Sound id: " + soundName, NamedTextColor.DARK_GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack messageItem(String title, String... lines) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title).color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String text : lines) {
            lore.add(line(text, NamedTextColor.GRAY));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack action(Material material, String name, List<String> lines, boolean usable) {
        ItemStack item = new ItemStack(usable ? material : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)
                .color(usable ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String text : lines) {
            lore.add(line(text, usable ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }
}
