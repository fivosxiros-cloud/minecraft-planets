package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The shared look of every menu: a light-blue frame around a soft field, with
 * a banner header and light-blue side labels, built from vanilla items so it
 * works with no resource pack.
 *
 * <p>Everything is configurable under {@code menu-style} in config.yml. The
 * palette is picked with {@code preset}: {@code light-blue} (the default),
 * {@code crimson} for the old gold/crimson look, or {@code custom} to use the
 * individual material keys. The default is the light-blue one:
 *
 * <pre>
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦     🟦 light-blue corners
 *   🟦🟧🟧🟧🟧🟧🟧🟧🟦     (corners and edges share the frame colour)
 *   🟦🟧🟩 ▣ ▣ ▣ 🟩🟧🟦     🟩 soft field behind the buttons
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 * </pre>
 *
 * Each decoration can also carry a {@code custom-model-data} value so a
 * resource pack can swap in real art when one is added later.
 */
public final class MenuStyle {

    /** Palette presets selectable with {@code menu-style.preset}. */
    private static final String PRESET_LIGHT_BLUE = "light-blue";
    private static final String PRESET_CRIMSON = "crimson";
    private static final String PRESET_CUSTOM = "custom";

    // The light-blue palette (the default) — a soft grey field inside a
    // light-blue frame, which is the look the menus had before the crimson one.
    private static final Material BLUE_FIELD = Material.GRAY_STAINED_GLASS_PANE;
    private static final Material BLUE_FRAME = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    private static final Material BLUE_CORNER = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    private static final Material BLUE_HEADER = Material.LIGHT_BLUE_BANNER;
    private static final Material BLUE_LABEL = Material.LIGHT_BLUE_DYE;

    // The crimson/gold palette, kept for servers that preferred it.
    private static final Material CRIMSON_FIELD = Material.RED_STAINED_GLASS_PANE;
    private static final Material CRIMSON_FRAME = Material.ORANGE_STAINED_GLASS_PANE;
    private static final Material CRIMSON_CORNER = Material.YELLOW_STAINED_GLASS_PANE;
    private static final Material CRIMSON_HEADER = Material.RED_BANNER;
    private static final Material CRIMSON_LABEL = Material.GOLD_BLOCK;

    private static Material fieldMaterial = BLUE_FIELD;
    private static Material frameMaterial = BLUE_FRAME;
    private static Material cornerMaterial = BLUE_CORNER;
    private static Material headerMaterial = BLUE_HEADER;
    private static Material labelMaterial = BLUE_LABEL;

    /** Set when a config was upgraded to the light-blue palette, so it can be saved. */
    private static boolean paletteMigrated;

    private static int fieldModel;
    private static int frameModel;
    private static int cornerModel;
    private static int headerModel;
    private static int labelModel;

    private MenuStyle() {
    }

    /**
     * Reads the palette from config.yml's {@code menu-style} section.
     *
     * <p>{@code preset} chooses the palette ({@code light-blue} by default,
     * {@code crimson}, or {@code custom} to read the material keys). A config
     * written before the presets existed is upgraded in place: if it still
     * holds the crimson materials the plugin used to ship, it is switched to
     * the light-blue default and marked for saving.
     *
     * @return whether the config was changed and should be saved
     */
    static boolean loadConfig(ConfigurationSection section) {
        paletteMigrated = false;
        if (section == null) {
            return false;
        }

        String preset = section.getString("preset");
        if (preset == null || preset.isBlank()) {
            // Pre-preset config: a palette that differs from the old crimson
            // default was tuned by hand, so keep it as a custom palette.
            if (looksCrimson(section)) {
                section.set("preset", PRESET_LIGHT_BLUE);
                writePreset(section, PRESET_LIGHT_BLUE);
            } else {
                section.set("preset", PRESET_CUSTOM);
            }
            paletteMigrated = true;
            preset = section.getString("preset", PRESET_LIGHT_BLUE);
        }

        switch (preset.toLowerCase(Locale.ROOT)) {
            case PRESET_CRIMSON -> applyPreset(section, PRESET_CRIMSON);
            case PRESET_CUSTOM -> {
                // keep the materials already in the file
            }
            default -> applyPreset(section, PRESET_LIGHT_BLUE);
        }

        fieldMaterial = material(section.getString("field-material"), fieldMaterial);
        frameMaterial = material(section.getString("frame-material"), frameMaterial);
        cornerMaterial = material(section.getString("corner-material"), cornerMaterial);
        headerMaterial = material(section.getString("header-material"), headerMaterial);
        labelMaterial = material(section.getString("label-material"), labelMaterial);
        fieldModel = section.getInt("custom-model-data.field", 0);
        frameModel = section.getInt("custom-model-data.frame", 0);
        cornerModel = section.getInt("custom-model-data.corner", 0);
        headerModel = section.getInt("custom-model-data.header", 0);
        labelModel = section.getInt("custom-model-data.label", 0);
        return paletteMigrated;
    }

    /** Writes a preset's materials into the config section. */
    private static void applyPreset(ConfigurationSection section, String preset) {
        writePreset(section, preset);
    }

    private static void writePreset(ConfigurationSection section, String preset) {
        boolean blue = !PRESET_CRIMSON.equals(preset);
        section.set("field-material", (blue ? BLUE_FIELD : CRIMSON_FIELD).name());
        section.set("frame-material", (blue ? BLUE_FRAME : CRIMSON_FRAME).name());
        section.set("corner-material", (blue ? BLUE_CORNER : CRIMSON_CORNER).name());
        section.set("header-material", (blue ? BLUE_HEADER : CRIMSON_HEADER).name());
        section.set("label-material", (blue ? BLUE_LABEL : CRIMSON_LABEL).name());
    }

    /** Whether the section still holds the crimson palette this plugin used to ship. */
    private static boolean looksCrimson(ConfigurationSection section) {
        return material(section.getString("field-material"), null) == CRIMSON_FIELD
                && material(section.getString("frame-material"), null) == CRIMSON_FRAME
                && material(section.getString("label-material"), null) == CRIMSON_LABEL;
    }

    private static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material parsed = Material.matchMaterial(name);
        return parsed == null || parsed == Material.AIR ? fallback : parsed;
    }

    // ── Frames and fields ───────────────────────────────────────────────

    /** The pane used for the field behind the buttons. */
    public static ItemStack field() {
        return pane(fieldMaterial, fieldModel);
    }

    /** The pane used for the frame's edges. */
    public static ItemStack frame() {
        return pane(frameMaterial, frameModel);
    }

    /** The pane used for the frame's corners. */
    public static ItemStack corner() {
        return pane(cornerMaterial, cornerModel);
    }

    private static ItemStack pane(Material material, int model) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        applyModel(meta, model);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Paints the whole frame into an inventory: corners, a ring around the
     * edge and the field everywhere inside. Any menu that then sets its own
     * buttons on top gets the theme for free.
     */
    public static void decorate(Inventory inventory) {
        int size = inventory.getSize();
        int lastRow = size / 9 - 1;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            boolean topOrBottom = row == 0 || row == lastRow;
            if (topOrBottom && (column == 0 || column == 8)) {
                inventory.setItem(slot, corner());
            } else if (topOrBottom || column == 0 || column == 8) {
                inventory.setItem(slot, frame());
            } else {
                inventory.setItem(slot, field());
            }
        }
    }

    /**
     * The full look: frame + field, plus the side tags on the left and right
     * of the frame. Menu buttons set afterwards overwrite them if a menu needs
     * those slots.
     */
    public static void decorate(Inventory inventory, String leftTag, String rightTag) {
        decorate(inventory);
        if (inventory.getSize() < 18) {
            return;
        }
        inventory.setItem(9, label(leftTag));
        inventory.setItem(17, label(rightTag));
    }

    // ── Header and side labels ──────────────────────────────────────────

    /** The banner pinned in the top row of a menu, as a title. */
    public static ItemStack header(String title) {
        return header(title, NamedTextColor.AQUA, List.of());
    }

    /** A banner header with subtitle lines underneath the title. */
    public static ItemStack header(String title, NamedTextColor color, List<String> loreLines) {
        ItemStack item = new ItemStack(headerMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(title).color(color)
                .decoration(TextDecoration.ITALIC, false));
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(Component.text(line).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
        }
        applyModel(meta, headerModel);
        item.setItemMeta(meta);
        return item;
    }

    /** A side label, like the small tags beside a frame. */
    public static ItemStack label(String text, String... loreLines) {
        ItemStack item = new ItemStack(labelMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(text).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(Component.text(line).color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        applyModel(meta, labelModel);
        item.setItemMeta(meta);
        return item;
    }

    /** A bottom-row tab, highlighted when it is the page/section you are on. */
    public static ItemStack tab(Material icon, String name, boolean selected, String... loreLines) {
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((selected ? "◆ " : "") + name)
                .color(selected ? NamedTextColor.AQUA : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(Component.text(line).color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (selected) {
            lore.add(Component.text("You are here").color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static void applyModel(ItemMeta meta, int model) {
        if (model > 0) {
            meta.setCustomModelData(model);
        }
    }
}
