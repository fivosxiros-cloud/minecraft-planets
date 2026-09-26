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
import java.util.Locale;

/**
 * Soundtrack editor for one world, opened from the Music button of
 * {@link AdminPlanetPanelMenu} (or {@link AdminLobbyPanelMenu}): the music a
 * planet or lobby plays, in order, without touching config.yml by hand.
 *
 * <p>Left-click a track to pick it, right-click to hear it straight away. The
 * buttons under the list move the picked track up or down, play it again,
 * remove it or nudge how long it lasts — that length only decides when the next
 * track may start, so an approximation is fine. "Add a track" opens
 * {@link MusicPickerMenu}, and every change is written to
 * {@code music.tracks.<world>} and saved at once, so the planet changes its
 * music the moment the click lands.
 *
 * <pre>
 *   🟦🟦🟦🟦📙🟦🟦🟦🟦           📙 this world's soundtrack
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦           row 1: tracks 1-7
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦           row 2: tracks 8-14
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦           row 3: tracks 15-21
 *   🟦 ▣ ▲ ▼ ▶ ✚ ✖ ▣ 🟦           move up/down, hear, add, remove, shorter
 *   🟦 ▣ ⇩ ▣ ↺ ▣ ✖ ▣ 🟦           ⇩ copy the default list, ↺ forget it, ✖ close
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 * </pre>
 */
public final class AdminMusicMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int MAX_TRACKS = 21;       // 3 rows x 7 columns
    private static final int EMPTY_SLOT = 22;       // middle of the list area

    private static final int UP_SLOT = 37;
    private static final int DOWN_SLOT = 38;
    private static final int HEAR_SLOT = 39;
    private static final int ADD_SLOT = 40;
    private static final int REMOVE_SLOT = 41;
    private static final int SHORTER_SLOT = 42;
    private static final int LONGER_SLOT = 43;

    private static final int COPY_DEFAULT_SLOT = 47;
    private static final int FORGET_SLOT = 49;
    private static final int CLOSE_SLOT = 51;

    /** How much the length buttons change a track by, in seconds. */
    private static final long LENGTH_STEP_SECONDS = 30L;
    /** The shortest and longest length a track may be given, in seconds. */
    private static final long MIN_SECONDS = 10L;
    private static final long MAX_SECONDS = 1800L;

    private final Planets plugin;
    private final Player viewer;
    private final String worldName;
    private final Inventory inventory;

    /** The lobby this soundtrack belongs to, or null for a planet/lobby world. */
    private final String lobbyId;

    /** The world's own tracks, in play order (empty = the shared default). */
    private final List<String> specs;
    /** The track the action buttons work on, or -1 when nothing is picked. */
    private int selected = -1;

    /**
     * The soundtrack editor for a planet world, opened from the admin planet
     * panel. Closing it returns to that planet's panel.
     *
     * @param previewIndex a track to hear as soon as the menu opens (the one an
     *                     admin just added), or -1 for none
     */
    public AdminMusicMenu(Planets plugin, Player viewer, String worldName, int previewIndex) {
        this(plugin, viewer, worldName, previewIndex, null);
    }

    /**
     * The soundtrack editor for a lobby world, which returns to that lobby's own
     * admin panel instead.
     *
     * @param lobbyId the lobby to go back to, or null for a planet
     */
    public AdminMusicMenu(Planets plugin, Player viewer, String worldName, int previewIndex, String lobbyId) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.worldName = worldName;
        this.lobbyId = lobbyId;
        this.specs = new ArrayList<>(plugin.planetMusic().ownTrackSpecs(worldName));
        if (previewIndex >= 0 && previewIndex < specs.size()) {
            this.selected = previewIndex;
            plugin.planetMusic().preview(viewer, specs.get(previewIndex));
        }
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83C\uDFB5 Music: " + worldName).color(NamedTextColor.DARK_AQUA));
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

        int trackIndex = trackIndexAt(slot);
        if (trackIndex >= 0) {
            // Right-click hears the track, left-click picks it for the buttons.
            if (event.isRightClick()) {
                hear(player, trackIndex);
            } else {
                selected = trackIndex;
                render();
            }
            return;
        }

        switch (slot) {
            case UP_SLOT -> move(player, -1);
            case DOWN_SLOT -> move(player, 1);
            case HEAR_SLOT -> {
                if (selected >= 0 && selected < specs.size()) {
                    hear(player, selected);
                }
            }
            case ADD_SLOT -> new MusicPickerMenu(plugin, player, worldName, 0, lobbyId).open(player);
            case REMOVE_SLOT -> remove(player);
            case SHORTER_SLOT -> resize(player, -LENGTH_STEP_SECONDS);
            case LONGER_SLOT -> resize(player, LENGTH_STEP_SECONDS);
            case COPY_DEFAULT_SLOT -> copyDefault(player);
            case FORGET_SLOT -> forget(player);
            case CLOSE_SLOT -> back(player);
            default -> {
                // decoration — nothing to do
            }
        }
    }

    /** Leaves the editor for whichever panel opened it. */
    private void back(Player player) {
        if (lobbyId != null) {
            plugin.openAdminLobby(player, lobbyId);
        } else {
            plugin.openAdminPlanet(player, worldName);
        }
    }

    // ── Actions ─────────────────────────────────────────────────────────

    private void hear(Player player, int index) {
        if (plugin.planetMusic().preview(player, specs.get(index))) {
            player.sendMessage(Component.text("\u25B6 Playing ").color(NamedTextColor.AQUA)
                    .append(Component.text(pretty(soundOf(specs.get(index)))).color(NamedTextColor.YELLOW))
                    .append(Component.text(" — the tick keeps playing where it was.").color(NamedTextColor.GRAY)));
        }
    }

    private void move(Player player, int offset) {
        if (!hasSelection()) {
            warn(player, "Pick a track first (left-click it).");
            return;
        }
        int target = selected + offset;
        if (target < 0 || target >= specs.size()) {
            return; // already at the top or the bottom
        }
        String movedTrack = specs.remove(selected);
        specs.add(target, movedTrack);
        selected = target;
        apply();
        render();
    }

    private void remove(Player player) {
        if (!hasSelection()) {
            warn(player, "Pick a track first (left-click it).");
            return;
        }
        String removed = specs.remove(selected);
        selected = Math.min(selected, specs.size() - 1);
        apply();
        render();
        player.sendMessage(Component.text("\u2716 Removed ").color(NamedTextColor.RED)
                .append(Component.text(pretty(soundOf(removed))).color(NamedTextColor.YELLOW))
                .append(Component.text(specs.isEmpty()
                                ? " — this world is silent now. \"Copy the default list\" brings music back."
                                : " from this planet's soundtrack.").color(NamedTextColor.GRAY)));
    }

    private void resize(Player player, long delta) {
        if (!hasSelection()) {
            warn(player, "Pick a track first (left-click it).");
            return;
        }
        String spec = specs.get(selected);
        long seconds = Math.min(MAX_SECONDS, Math.max(MIN_SECONDS, secondsOf(spec) + delta));
        specs.set(selected, soundOf(spec) + ":" + seconds);
        apply();
        render();
    }

    private void copyDefault(Player player) {
        List<String> defaults = plugin.planetMusic().defaultTrackSpecs();
        if (defaults.isEmpty()) {
            warn(player, "config.yml lists no default tracks to copy.");
            return;
        }
        specs.clear();
        specs.addAll(defaults);
        selected = specs.isEmpty() ? -1 : 0;
        apply();
        render();
        player.sendMessage(Component.text("\u21E9 This planet now plays the default list — edit it freely. ")
                .color(NamedTextColor.GREEN)
                .append(Component.text("(\"Forget my list\" goes back to sharing it.)").color(NamedTextColor.GRAY)));
    }

    private void forget(Player player) {
        specs.clear();
        selected = -1;
        // Removing the world's own list hands it back to the shared default one,
        // which is different from leaving an empty list behind (that is silence).
        plugin.planetMusic().forgetOwnTracks(worldName);
        render();
        player.sendMessage(Component.text("\u21BA This planet plays the shared default list again. ")
                .color(NamedTextColor.GREEN)
                .append(Component.text("(The world's own list was removed from config.yml.)").color(NamedTextColor.GRAY)));
    }

    /** Writes the world's list to config.yml and reloads the soundtrack. */
    private void apply() {
        plugin.planetMusic().saveOwnTracks(worldName, specs);
    }

    private boolean hasSelection() {
        return selected >= 0 && selected < specs.size();
    }

    private static void warn(Player player, String text) {
        player.sendMessage(Component.text("\u26A0 " + text).color(NamedTextColor.YELLOW));
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83C\uDFB5 Tracks", "\uD83E\uDE90 " + worldName);

        inventory.setItem(INFO_SLOT, infoItem());

        if (specs.isEmpty()) {
            inventory.setItem(EMPTY_SLOT, emptyItem());
        } else {
            int shown = Math.min(specs.size(), MAX_TRACKS);
            for (int i = 0; i < shown; i++) {
                inventory.setItem(slotFor(i), trackItem(i));
            }
        }

        inventory.setItem(UP_SLOT, action(Material.SPECTRAL_ARROW, "\u25B2 Move up",
                List.of("Plays earlier in the rotation"), selected > 0));
        inventory.setItem(DOWN_SLOT, action(Material.SPECTRAL_ARROW, "\u25BC Move down",
                List.of("Plays later in the rotation"), hasSelection() && selected < specs.size() - 1));
        inventory.setItem(HEAR_SLOT, action(Material.JUKEBOX, "\u25B6 Hear it",
                List.of("Play the picked track for yourself",
                        "Right-clicking a track does the same"), hasSelection()));
        inventory.setItem(ADD_SLOT, action(Material.NOTE_BLOCK, "\u271A Add a track",
                List.of("Pick from every music sound",
                        "the server has"), true));
        inventory.setItem(REMOVE_SLOT, action(Material.LAVA_BUCKET, "\u2716 Remove",
                List.of(hasSelection()
                        ? "Takes it out of this planet's list"
                        : "Pick a track first"), hasSelection()));
        inventory.setItem(SHORTER_SLOT, action(Material.RED_DYE, "\u2212 30s shorter",
                List.of("The length only decides when",
                        "the next track may start"), hasSelection()));
        inventory.setItem(LONGER_SLOT, action(Material.LIME_DYE, "+30s longer",
                List.of("The length only decides when",
                        "the next track may start"), hasSelection()));

        inventory.setItem(COPY_DEFAULT_SLOT, action(Material.DROPPER, "\u21E9 Copy the default list",
                List.of("Starts this planet from the shared",
                        "default list, ready to edit"), true));
        inventory.setItem(FORGET_SLOT, action(Material.GRAY_DYE, "\u21BA Forget my list",
                List.of("Drops this world's own list",
                        "so it shares the default one again"), true));
        inventory.setItem(CLOSE_SLOT, action(Material.BARRIER, "\u21A9 Back",
                List.of("Every change is already saved",
                        lobbyId == null ? "Back to the planet's admin panel"
                                : "Back to the lobby's admin panel"), true));
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.JUKEBOX);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDFB5 " + worldName + "'s soundtrack")
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Plays while a player is on this planet", NamedTextColor.GRAY));
        lore.add(line("and has \"Planet Music\" switched on in /settings.", NamedTextColor.GRAY));
        lore.add(line(""));
        if (!plugin.planetMusic().enabled()) {
            lore.add(line("Music is switched off in config.yml —", NamedTextColor.YELLOW));
            lore.add(line("nothing plays until music.enabled is true.", NamedTextColor.YELLOW));
        }
        if (specs.isEmpty()) {
            lore.add(line("This world has no list of its own, so it", NamedTextColor.GRAY));
            lore.add(line("plays the shared default one:", NamedTextColor.GRAY));
            lore.add(line(String.valueOf(plugin.planetMusic().defaultTrackSpecs().size())
                    + " default track(s) in config.yml", NamedTextColor.YELLOW));
        } else {
            lore.add(line(specs.size() + " track(s) of its own", NamedTextColor.YELLOW));
            long total = 0;
            for (String spec : specs) {
                total += secondsOf(spec);
            }
            lore.add(line("About " + (total / 60) + " min of music per rotation", NamedTextColor.GRAY));
        }
        lore.add(line(""));
        lore.add(line("Left-click a track to pick it, right-click to hear it", NamedTextColor.DARK_GRAY));
        lore.add(line("Changes are saved the moment you click", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyItem() {
        ItemStack item = new ItemStack(Material.MUSIC_DISC_13);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("No tracks of its own").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                line("This planet plays the shared default list.", NamedTextColor.GRAY),
                line("\"Add a track\" gives it a list of its own,", NamedTextColor.GRAY),
                line("\"Copy the default list\" starts from the default one.", NamedTextColor.GRAY),
                line(""),
                line("Removing every track instead makes the world silent.", NamedTextColor.DARK_GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack trackItem(int index) {
        String spec = specs.get(index);
        String soundName = soundOf(spec);
        boolean picked = index == selected;

        ItemStack item = new ItemStack(materialFor(soundName));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((picked ? "\u25C6 " : "") + pretty(soundName))
                .color(picked ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Track " + (index + 1) + " of " + specs.size(), NamedTextColor.GRAY));
        lore.add(line("Plays for about " + secondsOf(spec) + "s", NamedTextColor.GRAY));
        lore.add(line(""));
        lore.add(line(picked ? "◆ Picked — the buttons below act on it" : "Left-click to pick it",
                picked ? NamedTextColor.GOLD : NamedTextColor.YELLOW));
        lore.add(line("Right-click to hear it now", NamedTextColor.YELLOW));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** A button that greys out (and does nothing) when it can't be used. */
    private static ItemStack action(Material material, String name, List<String> lines, boolean usable) {
        ItemStack item = new ItemStack(usable ? material : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)
                .color(usable ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(line(line, usable ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** The slot a track index sits in (three rows of seven, frame skipped). */
    private static int slotFor(int index) {
        int row = index / 7;
        int column = (index % 7) + 1;
        return row * 9 + column;
    }

    /** The track index a slot holds, or -1 when the slot is not a track. */
    private int trackIndexAt(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        if (row < 1 || row > 3 || column == 0 || column == 8) {
            return -1;
        }
        int index = (row - 1) * 7 + (column - 1);
        return index >= 0 && index < Math.min(specs.size(), MAX_TRACKS) ? index : -1;
    }

    // ── Small helpers ───────────────────────────────────────────────────

    private static String soundOf(String spec) {
        int colon = spec.indexOf(':');
        return colon < 0 ? spec : spec.substring(0, colon);
    }

    private static long secondsOf(String spec) {
        int colon = spec.indexOf(':');
        if (colon < 0) {
            return PlanetMusic.defaultTrackSeconds();
        }
        try {
            return Long.parseLong(spec.substring(colon + 1).trim());
        } catch (NumberFormatException ex) {
            return PlanetMusic.defaultTrackSeconds();
        }
    }

    /** The item a track is shown as: its own disc, or a note block for the rest. */
    private static Material materialFor(String soundName) {
        if (soundName.startsWith("MUSIC_DISC")) {
            Material disc = Material.matchMaterial(soundName);
            if (disc != null) {
                return disc;
            }
        }
        return Material.NOTE_BLOCK;
    }

    /** "MUSIC_OVERWORLD_DRIPSTONE_CAVES" -> "Overworld Dripstone Caves". */
    static String pretty(String soundName) {
        String text = soundName.startsWith("MUSIC_") ? soundName.substring("MUSIC_".length()) : soundName;
        String[] words = text.toLowerCase(Locale.ROOT).split("_");
        StringBuilder built = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (!built.isEmpty()) {
                built.append(' ');
            }
            built.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return built.isEmpty() ? soundName : built.toString();
    }

    private static Component line(String text) {
        return line(text, NamedTextColor.GRAY);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }
}
