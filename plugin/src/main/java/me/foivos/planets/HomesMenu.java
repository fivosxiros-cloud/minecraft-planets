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
 * The {@code /home} screen: one bed per home slot, in the light-blue theme the
 * rest of the plugin's menus use.
 *
 * <pre>
 *   🟦🟦🟦🟦📘🟦🟦🟦🟦        📘 Your Homes (2/4)
 *   🟦 🛏 🛏 🛏 🛏 🟦…          four home slots, in the player's own bed colour
 *   🟦 ➕ 📖 🎨 🟦…            ➕ save one here, 📖 help, 🎨 pick the bed colour
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦 ✖
 * </pre>
 *
 * <p>On a saved home: <b>left-click teleports</b> (after a short countdown),
 * <b>right-click renames</b> (asked in chat) and <b>shift-click deletes</b>
 * after a second confirming click. An empty slot asks for a name and saves the
 * spot you are standing on, and the colour button opens a free picker for the
 * bed icons.
 */
public final class HomesMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int INFO_SLOT = 4;
    /** Where saved homes are drawn. More than four only ever exists for a legacy player. */
    private static final int[] HOME_SLOTS = {10, 11, 12, 13, 14, 15};
    private static final int NEW_SLOT = 20;
    private static final int HELP_SLOT = 22;
    private static final int COLOUR_SLOT = 24;
    private static final int CLOSE_SLOT = 40;

    private final Planets plugin;
    private final Player viewer;
    private final HomeManager homes;
    private final Inventory inventory;

    /** Name of the home waiting for a confirming shift-click, or null. */
    private String armedDelete;

    HomesMenu(Planets plugin, Player viewer, HomeManager homes) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.homes = homes;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83C\uDFE0 Your Homes").color(NamedTextColor.DARK_AQUA));
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

        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == NEW_SLOT) {
            if (homes.count(player.getUniqueId()) >= homes.slots()) {
                player.sendMessage(limitMessage());
                return;
            }
            plugin.promptNewHome(player, player.getLocation());
            return;
        }
        if (slot == HELP_SLOT) {
            plugin.printHomeHelp(player);
            return;
        }
        if (slot == COLOUR_SLOT) {
            plugin.openHomeColourMenu(player);
            return;
        }

        int index = -1;
        for (int i = 0; i < HOME_SLOTS.length; i++) {
            if (HOME_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }

        HomeManager.Home home = homes.at(player.getUniqueId(), index);
        if (home == null) {
            armedDelete = null;
            if (index >= homes.slots()) {
                return; // no slot to save into
            }
            if (homes.count(player.getUniqueId()) >= homes.slots()) {
                player.sendMessage(limitMessage());
                return;
            }
            plugin.promptNewHome(player, player.getLocation());
            return;
        }

        // Shift-right-click colours this one home; shift-left-click deletes it.
        if (event.isShiftClick() && event.isRightClick()) {
            armedDelete = null;
            plugin.openHomeColourMenu(player, home);
            return;
        }

        if (event.isShiftClick()) {
            if (armedDelete != null && armedDelete.equalsIgnoreCase(home.name())) {
                plugin.deleteHome(player, home.name());
                return;
            }
            armedDelete = home.name();
            render();
            player.sendMessage(Component.text("\uD83D\uDDD1 Shift-click ").color(NamedTextColor.YELLOW)
                    .append(Component.text(home.name()).color(NamedTextColor.AQUA))
                    .append(Component.text(" again to delete it (\"/delhome " + home.name()
                            + "\" works too).").color(NamedTextColor.YELLOW)));
            return;
        }

        armedDelete = null;
        if (event.isRightClick()) {
            plugin.promptRenameHome(player, home.name());
            return;
        }
        plugin.teleportHome(player, home);
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83C\uDFE0 Homes", "\uD83D\uDECF Slots");
        inventory.setItem(INFO_SLOT, infoItem());

        int slots = homes.slots();
        List<HomeManager.Home> saved = homes.homes(viewer.getUniqueId());
        for (int i = 0; i < HOME_SLOTS.length; i++) {
            HomeManager.Home home = i < saved.size() ? saved.get(i) : null;
            if (home != null) {
                // Legacy players may still hold more homes than they can now save;
                // keep those reachable so nothing is silently lost.
                inventory.setItem(HOME_SLOTS[i], homeItem(home, i, i >= slots));
            } else if (i < slots) {
                inventory.setItem(HOME_SLOTS[i], emptyItem(i));
            }
        }

        inventory.setItem(NEW_SLOT, newHomeItem());
        inventory.setItem(HELP_SLOT, helpItem());
        inventory.setItem(COLOUR_SLOT, colourItem());
        inventory.setItem(CLOSE_SLOT, closeItem());
    }

    private ItemStack infoItem() {
        int saved = homes.count(viewer.getUniqueId());
        int slots = homes.slots();
        ItemStack item = new ItemStack(homes.bedColour(viewer.getUniqueId()));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDFE0 Your Homes").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(saved + " / " + slots + " home slots used"));
        lore.add(line("Everyone has " + slots + " homes", NamedTextColor.DARK_GRAY));
        lore.add(line(""));
        lore.add(line("Left-click a home to teleport there"));
        lore.add(line("Right-click to rename it"));
        lore.add(line("Shift-click to delete it"));
        lore.add(line("Shift-right-click to recolour one"));
        lore.add(Component.text("Homes teleport in " + homes.teleportDelaySeconds() + "s")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack homeItem(HomeManager.Home home, int index, boolean overLimit) {
        boolean armed = home.name().equalsIgnoreCase(armedDelete);
        Material material = armed ? Material.RED_BED : homes.bedFor(viewer.getUniqueId(), home);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((armed ? "\uD83D\uDDD1 Delete " : "\uD83D\uDECF ") + home.name())
                .color(armed ? NamedTextColor.RED : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Slot " + (index + 1)));
        lore.add(line(home.describe()));
        lore.add(line("Bed colour: " + HomeManager.colourName(homes.bedFor(viewer.getUniqueId(), home))
                + (home.colour() == null ? " (your default)" : ""), NamedTextColor.GRAY));
        lore.add(line(""));
        if (armed) {
            lore.add(Component.text("Shift-click again to delete it for good")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            lore.add(line("Clicking anything else cancels"));
        } else {
            lore.add(Component.text("Left-click: teleport (" + homes.teleportDelaySeconds() + "s)")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Right-click: rename").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Shift-click: delete").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Shift-right-click: change this bed's colour")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            if (overLimit) {
                lore.add(line("Over your " + homes.slots() + " slots — delete it to free one",
                        NamedTextColor.GOLD));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyItem(int index) {
        ItemStack item = new ItemStack(Material.WHITE_BED);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDECF Empty slot " + (index + 1))
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("No home saved here yet"));
        lore.add(line(""));
        lore.add(Component.text("Click to save where you stand").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(line("You'll type a name in chat, like \"/sethome Base\""));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack newHomeItem() {
        int saved = homes.count(viewer.getUniqueId());
        int slots = homes.slots();
        boolean room = saved < slots;
        ItemStack item = new ItemStack(Material.BELL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2795 Set a Home Here").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(room
                ? "Saves where you stand into the next free slot"
                : "All " + slots + " of your slots are used"));
        lore.add(line("Also works as /sethome <name>"));
        if (room) {
            lore.add(Component.text("Click, then type a name in chat").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack helpItem() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDCD6 How Homes Work").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Click to print the commands in chat"));
        lore.add(line("/home — opens this menu"));
        lore.add(line("/home <name> — teleports straight there"));
        lore.add(line("/sethome <name> — saves this spot"));
        lore.add(line("/delhome <name> — removes a home"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack colourItem() {
        ItemStack item = new ItemStack(Material.PAINTING);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDFA8 Bed Colour").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Pick the colour for all of your home beds"));
        lore.add(line("Currently: " + HomeManager.colourName(homes.bedColour(viewer.getUniqueId())),
                NamedTextColor.YELLOW));
        lore.add(line("Free — no cost, no rank needed", NamedTextColor.GREEN));
        lore.add(line(""));
        lore.add(Component.text("Click to open the colour picker").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(line("Colour one home on its own with shift-right-click"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack closeItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2716 Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    // ── Chat messages ───────────────────────────────────────────────────

    private Component limitMessage() {
        return Component.text("\uD83C\uDFE0 You have all " + homes.slots() + " home slots used — ")
                .color(NamedTextColor.RED)
                .append(Component.text("/delhome <name>").color(NamedTextColor.YELLOW))
                .append(Component.text(" frees one.").color(NamedTextColor.RED));
    }

    private static Component line(String text) {
        return line(text, NamedTextColor.GRAY);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }
}
