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
import java.util.Collections;
import java.util.List;

/**
 * The sidebar picker behind {@code /settings} → Sidebar (right-click).
 *
 * <p>The top area is the sidebar itself: one item per line, in the order they
 * appear on screen, each showing exactly the text that line will render. The
 * lower area holds the lines the player has hidden, ready to be added back.
 *
 * <pre>
 *   🟦🟦🟦🟦📘🟦🟦🟦🟦           your sidebar, top line first
 *   🟦 ▤ ▤ ▤ ▤ ▤ ▤ ▤ 🟦
 *   🟦 ▤ ▤ ▤ ▤ ▤ ▤ ▤ 🟦
 *   🟦 ▨ ▨ ▨ ▨ ▨ ▨ ▨ 🟦           lines you have hidden
 *   🟦 ▨ ▨ ▨ ▨ ▨ ▨ ▨ 🟦
 *   🟦🟦🟦🟦↺🟦🟦✖🟦🟦           ↺ show every line, ✖ close
 * </pre>
 *
 * <p>Controls, spelled out on every item: left-click hides (or adds) a line,
 * right-click moves it up, shift-right-click moves it down. Every click
 * redraws the real sidebar behind the menu, so the change is visible at once.
 */
public final class SidebarEditorMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int RESET_SLOT = 47;
    private static final int CLOSE_SLOT = 51;

    /** Where the chosen lines sit: two rows of seven, top line first. */
    private static final int[] LINE_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25
    };
    /** Where the lines the player has hidden sit: two rows of seven. */
    private static final int[] HIDDEN_SLOTS = {
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Planets plugin;
    private final Player viewer;
    private final SidebarScoreboard sidebar;
    private final Inventory inventory;

    public SidebarEditorMenu(Planets plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.sidebar = plugin.sidebarScoreboard();
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("☰ Sidebar").color(NamedTextColor.DARK_AQUA));
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
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == RESET_SLOT) {
            sidebar.chooseLines(player, idsOf(sidebar.lines()));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.4f);
            player.sendMessage(Component.text("☰ Your sidebar shows every line again.")
                    .color(NamedTextColor.AQUA));
            render();
            return;
        }

        List<SidebarScoreboard.Line> shown = sidebar.visibleLines(player);
        int lineSlot = indexOf(LINE_SLOTS, slot);
        if (lineSlot >= 0) {
            if (lineSlot >= shown.size()) {
                return;
            }
            List<String> ids = idsOf(shown);
            if (event.isRightClick()) {
                // Nudge the line one place up (or down with shift held).
                int target = event.isShiftClick() ? lineSlot + 1 : lineSlot - 1;
                if (target < 0 || target >= ids.size()) {
                    player.playSound(player.getLocation(),
                            Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                    return;
                }
                Collections.swap(ids, lineSlot, target);
                player.playSound(player.getLocation(),
                        Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, event.isShiftClick() ? 0.9f : 1.3f);
            } else {
                ids.remove(lineSlot);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.9f);
            }
            sidebar.chooseLines(player, ids);
            render();
            return;
        }

        int hiddenSlot = indexOf(HIDDEN_SLOTS, slot);
        if (hiddenSlot >= 0) {
            List<SidebarScoreboard.Line> hidden = hiddenLines(shown);
            if (hiddenSlot >= hidden.size()) {
                return;
            }
            if (shown.size() >= SidebarScoreboard.MAX_LINES) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.7f);
                player.sendMessage(Component.text("☰ A sidebar fits "
                                + SidebarScoreboard.MAX_LINES + " lines — hide one first.")
                        .color(NamedTextColor.RED));
                return;
            }
            List<String> ids = idsOf(shown);
            ids.add(hidden.get(hiddenSlot).id());
            sidebar.chooseLines(player, ids);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 0.7f, 1.6f);
            render();
        }
    }

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "☰ Sidebar", "👤 Yours");

        inventory.setItem(INFO_SLOT, infoItem());

        List<SidebarScoreboard.Line> shown = sidebar.visibleLines(viewer);
        for (int i = 0; i < LINE_SLOTS.length && i < shown.size(); i++) {
            inventory.setItem(LINE_SLOTS[i], shownItem(shown.get(i), i, shown.size()));
        }
        List<SidebarScoreboard.Line> hidden = hiddenLines(shown);
        for (int i = 0; i < HIDDEN_SLOTS.length && i < hidden.size(); i++) {
            inventory.setItem(HIDDEN_SLOTS[i], hiddenItem(hidden.get(i)));
        }

        inventory.setItem(RESET_SLOT, resetItem());
        inventory.setItem(CLOSE_SLOT, closeItem());
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("☰ Your Sidebar").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(sidebar.title(), NamedTextColor.GOLD));
        lore.add(line(""));
        lore.add(line(shownCount() + " of " + sidebar.lines().size() + " lines shown"));
        lore.add(line("It sits down the right of your screen"));
        lore.add(line("everywhere on the server.", NamedTextColor.DARK_GRAY));
        if (!sidebar.enabled()) {
            lore.add(line(""));
            lore.add(line("The sidebar is switched off on this server.", NamedTextColor.RED));
        }
        lore.add(line(""));
        lore.add(Component.text("Click a line below to change it").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private int shownCount() {
        return sidebar.visibleLines(viewer).size();
    }

    /** One line of the player's sidebar, previewing exactly what it renders. */
    private ItemStack shownItem(SidebarScoreboard.Line line, int index, int total) {
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(plugin.renderHudTemplate(viewer, line.template()))
                .color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(line("Line: " + line.label(), NamedTextColor.AQUA));
        lore.add(line("Position " + (index + 1) + " of " + total));
        lore.add(line(""));
        lore.add(Component.text("Left-click: hide this line").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Right-click: move it up").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Shift-right-click: move it down").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** One configured line this player has hidden, ready to be added back. */
    private ItemStack hiddenItem(SidebarScoreboard.Line line) {
        ItemStack item = new ItemStack(Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("❌ " + line.label()).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(line("Hidden — not on your sidebar"));
        lore.add(line("Would show: " + plugin.renderHudTemplate(viewer, line.template()),
                NamedTextColor.DARK_GRAY));
        lore.add(line(""));
        lore.add(Component.text("Left-click: add it back").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack resetItem() {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("↺ Show Every Line").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Back to all " + sidebar.lines().size() + " lines,"));
        lore.add(line("in the order the server lists them."));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack closeItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("✖ Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    /** The configured lines this player has not got on their sidebar. */
    private List<SidebarScoreboard.Line> hiddenLines(List<SidebarScoreboard.Line> shown) {
        List<SidebarScoreboard.Line> hidden = new ArrayList<>();
        for (SidebarScoreboard.Line line : sidebar.lines()) {
            boolean visible = false;
            for (SidebarScoreboard.Line candidate : shown) {
                if (candidate.id().equals(line.id())) {
                    visible = true;
                    break;
                }
            }
            if (!visible) {
                hidden.add(line);
            }
        }
        return hidden;
    }

    private static List<String> idsOf(List<SidebarScoreboard.Line> lines) {
        List<String> ids = new ArrayList<>();
        for (SidebarScoreboard.Line line : lines) {
            ids.add(line.id());
        }
        return ids;
    }

    private static int indexOf(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private static Component line(String text) {
        return line(text, NamedTextColor.GRAY);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }
}
