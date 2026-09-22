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
 * The in-game editor for the Planet HUD lines: which lines exist, what they say
 * and which one players start on. Built for ops ({@code /planets hud}).
 *
 * <pre>
 *   📘 HUD Editor
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣        the configured lines — click one to edit it
 *   %…% %…% %…% %…% %…% %…% %…%   placeholders: click to append
 *   %…% %…% %…% %…% %…% %…% sep   …and the separator
 *   ➕ ⌫ 🗑 ↩ ⭐ 🗘 ✖      add, backspace, clear, reset, default, reload, close
 * </pre>
 *
 * <p>Every click saves immediately, flashes the line to the editor's own action
 * bar so it can be seen live, and redraws. Renaming a line and adding one ask
 * for the label in chat. Nothing here needs a config file — but the result is
 * written to {@code hud.modes}, so it can still be hand-edited afterwards.
 */
public final class HudEditorMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int[] LINE_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] PLACEHOLDER_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int NEW_SLOT = 37;
    private static final int BACKSPACE_SLOT = 38;
    private static final int CLEAR_SLOT = 39;
    private static final int RESET_SLOT = 40;
    private static final int DEFAULT_SLOT = 41;
    private static final int RELOAD_SLOT = 42;
    private static final int CLOSE_SLOT = 43;

    /** What a placeholder is called and where it sits, in click order. */
    private record Placeholder(String token, String description, Material icon) {
    }

    /** The tokens that can be dropped into a line, plus the separator button. */
    private static final List<Placeholder> PLACEHOLDERS = List.of(
            new Placeholder("%planet%", "The planet's name (or the world's, off-planet)", Material.GRASS_BLOCK),
            new Placeholder("%world%", "The raw world name", Material.MAP),
            new Placeholder("%balance%", "The player's VPL balance", Material.EMERALD),
            new Placeholder("%x%", "The player's X coordinate", Material.ARROW),
            new Placeholder("%y%", "The player's Y coordinate", Material.LADDER),
            new Placeholder("%z%", "The player's Z coordinate", Material.COMPASS),
            new Placeholder("%players%", "Players online on the server", Material.PLAYER_HEAD),
            new Placeholder("%visitors%", "Players standing in the same world", Material.ARMOR_STAND),
            new Placeholder("%members%", "Members of that planet (owned planets)", Material.SHIELD),
            new Placeholder("%blocks%", "Blocks placed on that planet", Material.BRICKS),
            new Placeholder("%block-limit%", "That planet's block limit", Material.BARRIER),
            new Placeholder("%size%", "That planet's size level", Material.OAK_FENCE),
            new Placeholder("%next-size%", "VPL price of the next size level", Material.GOLD_INGOT));

    /** What the last button in the placeholder rows appends. */
    private static final String SEPARATOR = "  \u00B7  ";

    private final Planets plugin;
    private final Player viewer;
    private final Inventory inventory;

    /** Which line is being edited, as an index into the configured lines. */
    private int selected;

    /** Line waiting for a confirming shift-click delete, or -1. */
    private int armedDelete = -1;

    HudEditorMenu(Planets plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.selected = plugin.hudDefaultIndex();
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83D\uDCF0 HUD Editor").color(NamedTextColor.DARK_AQUA));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    void open(Player player) { player.openInventory(inventory); }

    void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        List<Planets.HudMode> modes = plugin.hudModes();

        // ── The lines themselves ────────────────────────────────────────
        for (int i = 0; i < LINE_SLOTS.length; i++) {
            if (LINE_SLOTS[i] != slot || i >= modes.size()) {
                continue;
            }
            selected = i;
            if (event.isShiftClick()) {
                if (armedDelete == i) {
                    deleteLine(i);
                    return;
                }
                armedDelete = i;
                render();
                player.sendMessage(Component.text("\uD83D\uDDD1 Shift-click ").color(NamedTextColor.YELLOW)
                        .append(Component.text(modes.get(i).label()).color(NamedTextColor.AQUA))
                        .append(Component.text(" again to delete this line.").color(NamedTextColor.YELLOW)));
                return;
            }
            armedDelete = -1;
            if (event.isRightClick()) {
                plugin.promptHudLabel(player, i);
                return;
            }
            render();
            return;
        }

        // ── Placeholder buttons ─────────────────────────────────────────
        for (int i = 0; i < PLACEHOLDER_SLOTS.length; i++) {
            if (PLACEHOLDER_SLOTS[i] != slot) {
                continue;
            }
            String token = i < PLACEHOLDERS.size() ? PLACEHOLDERS.get(i).token() : SEPARATOR;
            // Right-click adds a space behind the token, so a line can be built
            // up in one click per piece.
            append(token + (event.isRightClick() && !token.endsWith(" ") ? " " : ""));
            return;
        }

        switch (slot) {
            case NEW_SLOT -> plugin.promptHudLabel(player, -1);
            case BACKSPACE_SLOT -> {
                armedDelete = -1;
                append(null);
            }
            case CLEAR_SLOT -> setTemplate("");
            case RESET_SLOT -> setTemplate(defaultTemplate(selected));
            case DEFAULT_SLOT -> {
                armedDelete = -1;
                plugin.setHudDefault(player, selected);
                render();
            }
            case RELOAD_SLOT -> {
                armedDelete = -1;
                plugin.reloadHudFromConfig(player);
                selected = Math.min(selected, Math.max(0, plugin.hudModes().size() - 1));
                render();
            }
            case CLOSE_SLOT -> player.closeInventory();
            default -> {
                // an empty slot: clicking anything else cancels a pending delete
                armedDelete = -1;
            }
        }
    }

    // ── Editing ─────────────────────────────────────────────────────────

    /** Appends a token to the line being edited, or removes the last one for null. */
    private void append(String token) {
        List<Planets.HudMode> modes = plugin.hudModes();
        if (selected < 0 || selected >= modes.size()) {
            return;
        }
        String current = modes.get(selected).template();
        String next = token == null ? removeLastToken(current) : current + token;
        if (next.equals(current)) {
            return;
        }
        setTemplate(next);
    }

    /** Strips the last placeholder — or the last character — off a template. */
    private static String removeLastToken(String template) {
        String trimmed = template.stripTrailing();
        int open = trimmed.lastIndexOf('%');
        if (open > 0 && trimmed.indexOf('%') != open) {
            int previous = trimmed.lastIndexOf('%', open - 1);
            if (previous >= 0) {
                return trimmed.substring(0, previous);
            }
        }
        return trimmed.isEmpty() ? "" : trimmed.substring(0, trimmed.length() - 1);
    }

    /** Replaces the selected line's template, saves it and previews it live. */
    private void setTemplate(String template) {
        List<Planets.HudMode> modes = plugin.hudModes();
        if (selected < 0 || selected >= modes.size()) {
            return;
        }
        List<Planets.HudMode> updated = new ArrayList<>(modes);
        updated.set(selected, new Planets.HudMode(modes.get(selected).label(), template));
        plugin.saveHudModes(updated);
        playerFeedback();
        plugin.flashHudPreview(viewer, template);
        render();
    }

    /** Removes a line, keeping the selection and the configured default valid. */
    private void deleteLine(int index) {
        List<Planets.HudMode> modes = new ArrayList<>(plugin.hudModes());
        if (index < 0 || index >= modes.size()) {
            return;
        }
        if (modes.size() <= 1) {
            // The HUD needs something to show; deleting the last line would leave
            // players with nothing at all.
            armedDelete = -1;
            viewer.sendMessage(Component.text("\uD83D\uDCF0 The HUD needs at least one line — ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("add another before deleting this one.")
                            .color(NamedTextColor.YELLOW)));
            render();
            return;
        }
        String label = modes.remove(index).label();
        armedDelete = -1;
        selected = Math.max(0, Math.min(selected >= index ? selected - 1 : selected, modes.size() - 1));
        plugin.removeHudMode(index);
        plugin.getLogger().info(viewer.getName() + " deleted the HUD line '" + label + "'.");
        viewer.sendMessage(Component.text("\uD83D\uDCF0 HUD line ").color(NamedTextColor.GREEN)
                .append(Component.text(label).color(NamedTextColor.AQUA))
                .append(Component.text(" deleted.").color(NamedTextColor.GREEN)));
        render();
    }

    private void playerFeedback() {
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.6f);
    }

    /** The shipped template for a position, used by the reset button. */
    private static String defaultTemplate(int index) {
        return switch (index) {
            case 0 -> "\uD83E\uDE90 %planet%";
            case 2 -> "\uD83E\uDE90 %planet%  \u00B7  \uD83D\uDCCD %x%, %y%, %z%";
            default -> "\uD83E\uDE90 %planet%  \u00B7  \uD83D\uDCB0 %balance% VPL";
        };
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83D\uDCF0 Lines", "\uD83E\uDDF1 Tokens");

        List<Planets.HudMode> modes = plugin.hudModes();
        inventory.setItem(INFO_SLOT, infoItem(modes));

        int defaultIndex = plugin.hudDefaultIndex();
        for (int i = 0; i < LINE_SLOTS.length && i < modes.size(); i++) {
            inventory.setItem(LINE_SLOTS[i], lineItem(i, modes.get(i), i == defaultIndex));
        }

        for (int i = 0; i < PLACEHOLDER_SLOTS.length; i++) {
            inventory.setItem(PLACEHOLDER_SLOTS[i], i < PLACEHOLDERS.size()
                    ? placeholderItem(PLACEHOLDERS.get(i))
                    : separatorItem());
        }

        inventory.setItem(NEW_SLOT, action(Material.NETHER_STAR, "\u2795 New Line",
                List.of("Type a label in chat for a new line",
                        "It starts as \uD83E\uDE90 %planet%"),
                NamedTextColor.AQUA));
        inventory.setItem(BACKSPACE_SLOT, action(Material.SHEARS, "\u232B Backspace",
                List.of("Removes the last placeholder",
                        "(or the last character) from the line"),
                NamedTextColor.YELLOW));
        inventory.setItem(CLEAR_SLOT, action(Material.BUCKET, "\uD83D\uDDD1 Clear Line",
                List.of("Empties the line — it shows nothing",
                        "until you add something back"),
                NamedTextColor.YELLOW));
        inventory.setItem(RESET_SLOT, action(Material.WRITABLE_BOOK, "\u21A9 Reset Line",
                List.of("Back to the shipped template",
                        "for this position"),
                NamedTextColor.YELLOW));
        inventory.setItem(DEFAULT_SLOT, action(Material.GOLDEN_HELMET, "\u2B50 Set as Default",
                List.of("New players start on this line",
                        "Currently: " + (modes.isEmpty() ? "?" : modes.get(
                                Math.min(Math.max(defaultIndex, 0), modes.size() - 1)).label())),
                NamedTextColor.GOLD));
        inventory.setItem(RELOAD_SLOT, action(Material.COMPARATOR, "\uD83D\uDDD8 Reload from config",
                List.of("Re-reads hud.modes from config.yml",
                        "Handy after editing it by hand"),
                NamedTextColor.AQUA));
        inventory.setItem(CLOSE_SLOT, action(Material.BARRIER, "\u2716 Close",
                List.of("Every change is already saved"), NamedTextColor.GRAY));
    }

    private ItemStack infoItem(List<Planets.HudMode> modes) {
        ItemStack item = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDCF0 Planet HUD Editor").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(modes.size() + " line(s) configured"));
        if (!modes.isEmpty()) {
            int index = Math.min(Math.max(selected, 0), modes.size() - 1);
            Planets.HudMode mode = modes.get(index);
            lore.add(line(""));
            lore.add(line("Editing: " + mode.label(), NamedTextColor.YELLOW));
            lore.add(line("Template: " + (mode.template().isBlank() ? "(empty)" : mode.template())));
            lore.add(line("Preview: " + plugin.renderHudTemplate(viewer, mode.template()),
                    NamedTextColor.AQUA));
        }
        if (modes.size() > LINE_SLOTS.length) {
            lore.add(line("+" + (modes.size() - LINE_SLOTS.length)
                    + " more line(s) — edit those in config.yml", NamedTextColor.GOLD));
        }
        lore.add(line(""));
        lore.add(line("Click a placeholder to append it", NamedTextColor.GRAY));
        lore.add(line("Right-click one to append it with a space", NamedTextColor.GRAY));
        lore.add(line("Left-click a line to edit, right-click to rename,"));
        lore.add(line("shift-click twice to delete it"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack lineItem(int index, Planets.HudMode mode, boolean isDefault) {
        boolean armed = armedDelete == index;
        boolean editing = index == selected;
        ItemStack item = new ItemStack(armed ? Material.RED_STAINED_GLASS_PANE
                : isDefault ? Material.GOLDEN_HELMET : editing ? Material.SPYGLASS : Material.ITEM_FRAME);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((armed ? "\uD83D\uDDD1 Delete " : editing ? "\u270F " : "\u25AB ")
                        + mode.label())
                .color(armed ? NamedTextColor.RED : editing ? NamedTextColor.AQUA : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (editing && !armed) {
            meta.setEnchantmentGlintOverride(true);
        }
        List<Component> lore = new ArrayList<>();
        if (isDefault) {
            lore.add(line("Default line for new players", NamedTextColor.GOLD));
        }
        lore.add(line("Template: " + (mode.template().isBlank() ? "(empty)" : mode.template())));
        lore.add(line("Preview: " + plugin.renderHudTemplate(viewer, mode.template()),
                NamedTextColor.AQUA));
        lore.add(line(""));
        if (armed) {
            lore.add(Component.text("Shift-click again to delete it").color(NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Click: edit this line").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Right-click: rename it").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Shift-click: delete it").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack placeholderItem(Placeholder placeholder) {
        ItemStack item = new ItemStack(placeholder.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(placeholder.token()).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(placeholder.description()));
        lore.add(line(""));
        lore.add(Component.text("Click: append " + placeholder.token()).color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Right-click: append it with a space").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack separatorItem() {
        ItemStack item = new ItemStack(Material.STICK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u00B7 Separator").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("A gap with a dot, the \"  \u00B7  \" between parts"));
        lore.add(line(""));
        lore.add(Component.text("Click: append the separator").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack action(Material material, String name, List<String> lines,
                                    NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String text : lines) {
            lore.add(line(text));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String text) {
        return line(text, NamedTextColor.GRAY);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }
}
