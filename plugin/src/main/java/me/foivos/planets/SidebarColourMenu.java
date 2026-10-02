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
 * The colour picker behind {@code /settings} → Sidebar → shift-left-click a line.
 *
 * <pre>
 *   🟦🟦🟦🟦🎨🟦🟦🟦🟦        🎨 Line Colour — click one, it is free
 *   🟦 🎨 🎨 🎨 🎨 🎨 🎨 🎨 🟦     seven colours
 *   🟦 🎨 🎨 🎨 🎨 🎨 🎨 ↩ 🟦     six more, then "server colours"
 *   🟦🟦🟦🟦↩🟦🟦🟦🟦           ↩ back to your sidebar
 * </pre>
 *
 * <p>The colour a player picks is theirs alone: it is written to
 * {@code player-settings.yml} the moment it is clicked, so it is still there
 * after a restart, and it applies to that one line of their sidebar everywhere
 * on the server. Nothing here affects anybody else's screen, and nothing costs
 * anything.
 */
public final class SidebarColourMenu implements InventoryHolder {

    /** One colour a line can be drawn in. */
    private record Choice(String name, NamedTextColor colour, Material dye) {
    }

    /**
     * The colours on offer, in the order they are drawn. The name is the
     * spelling config.yml uses for {@code sidebar.title-color}, so that is
     * exactly what gets written to the player's file. Black is left out on
     * purpose: on the dark sidebar it would be invisible.
     */
    private static final List<Choice> COLOURS = List.of(
            new Choice("white", NamedTextColor.WHITE, Material.WHITE_DYE),
            new Choice("gray", NamedTextColor.GRAY, Material.LIGHT_GRAY_DYE),
            new Choice("dark_gray", NamedTextColor.DARK_GRAY, Material.GRAY_DYE),
            new Choice("red", NamedTextColor.RED, Material.RED_DYE),
            new Choice("gold", NamedTextColor.GOLD, Material.ORANGE_DYE),
            new Choice("yellow", NamedTextColor.YELLOW, Material.YELLOW_DYE),
            new Choice("green", NamedTextColor.GREEN, Material.LIME_DYE),
            new Choice("dark_green", NamedTextColor.DARK_GREEN, Material.GREEN_DYE),
            new Choice("aqua", NamedTextColor.AQUA, Material.LIGHT_BLUE_DYE),
            new Choice("dark_aqua", NamedTextColor.DARK_AQUA, Material.CYAN_DYE),
            new Choice("blue", NamedTextColor.BLUE, Material.BLUE_DYE),
            new Choice("light_purple", NamedTextColor.LIGHT_PURPLE, Material.MAGENTA_DYE),
            new Choice("dark_purple", NamedTextColor.DARK_PURPLE, Material.PURPLE_DYE));

    private static final int SIZE = 45;
    private static final int INFO_SLOT = 4;
    private static final int BACK_SLOT = 40;
    /** Two rows of seven: the thirteen colours, then "server colours" beside them. */
    private static final int[] COLOUR_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24
    };
    /** Hands the line back to the colours config.yml gives it. */
    private static final int DEFAULT_SLOT = 25;

    private final Planets plugin;
    private final Player viewer;
    private final SidebarScoreboard sidebar;
    private final SidebarScoreboard.Line line;
    private final Inventory inventory;

    SidebarColourMenu(Planets plugin, Player viewer, SidebarScoreboard.Line line) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.sidebar = plugin.sidebarScoreboard();
        this.line = line;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83C\uDFA8 Line Colour").color(NamedTextColor.DARK_AQUA));
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

        if (slot == BACK_SLOT) {
            player.closeInventory();
            new SidebarEditorMenu(plugin, player).open(player);
            return;
        }
        if (slot == DEFAULT_SLOT) {
            if (recolour(player, null)) {
                applied(player, "\u21A9 \"" + line.label() + "\" is back to the server's colours.");
            }
            return;
        }
        int index = -1;
        for (int i = 0; i < COLOUR_SLOTS.length; i++) {
            if (COLOUR_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        if (index < 0 || index >= COLOURS.size()) {
            return;
        }
        Choice choice = COLOURS.get(index);
        if (recolour(player, choice.name())) {
            applied(player, "\uD83C\uDFA8 \"" + line.label() + "\" now draws in "
                    + choice.name().replace('_', ' ') + ".");
        }
    }

    // ── Applying a choice ───────────────────────────────────────────────

    /**
     * Stores one colour for this line, or hands the line back to the config
     * when {@code colour} is null. False while the settings are still loading,
     * in which case nothing is remembered and the click is harmless.
     */
    private boolean recolour(Player player, String colour) {
        PlayerSettings settings = plugin.getPlayerSettings();
        if (settings == null) {
            player.sendMessage(Component.text("\uD83C\uDFA8 Your settings are still loading — try again in a moment.")
                    .color(NamedTextColor.RED));
            return false;
        }
        settings.setSidebarColour(player.getUniqueId(), line.id(), colour);
        return true;
    }

    /** Repaints the real sidebar and goes back to the line picker it came from. */
    private void applied(Player player, String message) {
        sidebar.refresh(player);
        player.playSound(player.getLocation(), Sound.ITEM_DYE_USE, 0.9f, 1.2f);
        player.sendMessage(Component.text(message).color(NamedTextColor.AQUA));
        player.closeInventory();
        new SidebarEditorMenu(plugin, player).open(player);
    }

    /** The colour this line is drawn in for this player, or null for the config's. */
    private String current() {
        PlayerSettings settings = plugin.getPlayerSettings();
        return settings == null ? null : settings.sidebarColour(viewer.getUniqueId(), line.id());
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83C\uDFA8 Colour", "\uD83D\uDC64 Yours");
        inventory.setItem(INFO_SLOT, infoItem());

        String current = current();
        for (int i = 0; i < COLOUR_SLOTS.length && i < COLOURS.size(); i++) {
            Choice choice = COLOURS.get(i);
            inventory.setItem(COLOUR_SLOTS[i], colourItem(choice, choice.name().equals(current)));
        }
        inventory.setItem(DEFAULT_SLOT, defaultItem(current == null));

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(Component.text("\u21A9 Back to Your Sidebar").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> backLore = new ArrayList<>();
        backLore.add(line("Nothing changes until you click a colour"));
        backMeta.lore(backLore);
        back.setItemMeta(backMeta);
        inventory.setItem(BACK_SLOT, back);
    }

    private ItemStack infoItem() {
        String current = current();
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDFA8 " + line.label()).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Currently: " + (current == null
                ? "the server's colours" : current.replace('_', ' ')), NamedTextColor.YELLOW));
        // The line as it will read on the sidebar, colours and all.
        lore.add(Component.text("On your sidebar: ")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(SidebarScoreboard.styled(
                        SidebarScoreboard.tidy(plugin.renderHudTemplate(viewer, line.template())),
                        sidebar.lineColour(),
                        SidebarScoreboard.colour(current, null))));
        lore.add(line(""));
        lore.add(line("A pick here recolours the whole"));
        lore.add(line("line — the label, the \"|\" and the"));
        lore.add(line("number change together."));
        lore.add(line(""));
        lore.add(line("Saved to your own settings straight away", NamedTextColor.GREEN));
        lore.add(Component.text("Click a colour to use it").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack colourItem(Choice choice, boolean selected) {
        ItemStack item = new ItemStack(choice.dye());
        ItemMeta meta = item.getItemMeta();
        // The name is drawn in its own colour, so the item shows what it does.
        meta.displayName(Component.text((selected ? "\u2705 " : "\uD83C\uDFA8 ")
                        + choice.name().replace('_', ' '))
                .color(choice.colour()).decoration(TextDecoration.ITALIC, false));
        if (selected) {
            meta.setEnchantmentGlintOverride(true);
        }
        List<Component> lore = new ArrayList<>();
        if (selected) {
            lore.add(line("This is the colour in use right now", NamedTextColor.GREEN));
        } else {
            lore.add(Component.text("Click to draw \"" + line.label() + "\" in it")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(line("Saves as you click \u00B7 free", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack defaultItem(boolean selected) {
        ItemStack item = new ItemStack(selected ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((selected ? "\u2705 " : "\u21A9 ") + "Server Colours")
                .color(selected ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Hands this line back to the colours"));
        lore.add(line("this server gives it in config.yml."));
        lore.add(line("That is where the label, the \"|\" and"));
        lore.add(line("the numbers get their own colours."));
        lore.add(line(""));
        lore.add(Component.text("Click to follow the server again")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
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
