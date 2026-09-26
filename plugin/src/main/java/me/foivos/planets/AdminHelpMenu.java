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
 * The help page, in two flavours sharing one screen and one paginated layout:
 * the <b>admin</b> page (op-only, opened from the dashboard's Help book or
 * {@code /planets admin help}) and the <b>all</b> page every player gets from
 * {@code /planets help}, which lists the player features and, for staff, the
 * admin actions on top. Each entry shows what the command or panel action does,
 * its exact syntax, the permission it needs and whether the viewer has it —
 * clicking an entry prints the line in chat so it can be copied.
 *
 * <p>Layout (45 slots, 5 rows, light-blue frame around a gray field): 28
 * entries per page in rows 0-3, columns 1-7, with the page arrows, a "print
 * this page" book, a search anvil and the page counter in the bottom row.
 *
 * <p>The anvil starts a search: an admin types a keyword ("lock", "terrain",
 * "delete", ...) and only the matching commands are listed. Extra aliases per
 * entry live in {@link Entry#keywords}, so flavour words like "regen" or "tp"
 * find the right action even though they appear in no command name.
 */
public final class AdminHelpMenu implements InventoryHolder {

    /**
     * One help line: a category, the usage, what it does, its permission and
     * the extra words that should match it in a search.
     */
    public record Entry(String category, Material icon, String usage, String description,
                        String permission, String keywords) {

        /** Everything a search keyword may match against. */
        String haystack() {
            return (category + " " + usage + " " + description + " " + permission + " " + keywords)
                    .toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final int SIZE = 45;
    private static final int PER_PAGE = 28;      // 4 rows x 7 columns
    private static final int PREVIOUS_SLOT = 37;
    private static final int PRINT_SLOT = 39;
    private static final int INFO_SLOT = 40;
    private static final int SEARCH_SLOT = 41;
    private static final int CLEAR_SLOT = 42;
    private static final int NEXT_SLOT = 43;

    private final Planets plugin;
    private final Player viewer;
    private final List<Entry> entries;
    private final String query;
    /** True for the op-only admin page, false for the page everyone gets. */
    private final boolean adminView;
    private final Inventory inventory;
    private int page;

    /** The op-only admin help page. */
    public AdminHelpMenu(Planets plugin, Player viewer, List<Entry> entries, String query) {
        this(plugin, viewer, entries, query, true);
    }

    /**
     * @param query      the active search, or null for the full help page.
     * @param adminView  true for the admin page, false for the all-players page.
     */
    public AdminHelpMenu(Planets plugin, Player viewer, List<Entry> entries, String query,
                         boolean adminView) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.entries = entries;
        this.query = query;
        this.adminView = adminView;
        String title = adminView ? "\u2753 Admin Help" : "\u2753 Help";
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text(query == null
                        ? title
                        : title + ": " + query)
                .color(adminView && query == null ? NamedTextColor.GOLD : NamedTextColor.AQUA));
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
        if (adminView && !plugin.canUseAdmin(player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }

        if (slot == PREVIOUS_SLOT && page > 0) {
            page--;
            render();
            return;
        }
        if (slot == NEXT_SLOT && page < maxPage()) {
            page++;
            render();
            return;
        }
        if (slot == PRINT_SLOT) {
            printPage(player);
            return;
        }
        if (slot == INFO_SLOT) {
            player.closeInventory();
            if (adminView) {
                plugin.openAdminDashboard(player);
            } else {
                plugin.openPlanetsMenu(player);
            }
            return;
        }
        if (slot == SEARCH_SLOT) {
            plugin.openHelpSearch(player, adminView);
            return;
        }
        if (slot == CLEAR_SLOT && query != null) {
            if (adminView) {
                plugin.openAdminHelp(player);
            } else {
                plugin.openHelp(player);
            }
            return;
        }

        Entry entry = entryAt(slot);
        if (entry == null) {
            return;
        }
        // Clicking prints the line, so it can be copied into chat.
        player.sendMessage(Component.text("\u2022 ").color(NamedTextColor.GRAY)
                .append(Component.text(entry.usage()).color(NamedTextColor.YELLOW))
                .append(Component.text(" — ").color(NamedTextColor.GRAY))
                .append(Component.text(entry.description()).color(NamedTextColor.GRAY)));
    }

    /** Prints every entry of the current page into chat. */
    private void printPage(Player player) {
        player.closeInventory();
        player.sendMessage(Component.text((adminView ? "\u2753 Admin help" : "\u2753 Help")
                        + (query == null ? "" : " [" + query + "]")
                        + " — page " + (page + 1) + "/" + (maxPage() + 1))
                .color(query == null && adminView ? NamedTextColor.GOLD : NamedTextColor.AQUA));
        int start = page * PER_PAGE;
        for (int i = start; i < Math.min(start + PER_PAGE, entries.size()); i++) {
            Entry entry = entries.get(i);
            player.sendMessage(Component.text("\u2022 ").color(NamedTextColor.GRAY)
                    .append(Component.text(entry.usage()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" — ").color(NamedTextColor.GRAY))
                    .append(Component.text(entry.description()).color(NamedTextColor.GRAY))
                    .append(Component.text(" [" + entry.permission() + "]").color(NamedTextColor.DARK_GRAY)));
        }
    }

    private Entry entryAt(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        if (row > 3 || column == 0 || column == 8) {
            return null;
        }
        int index = page * PER_PAGE + row * 7 + (column - 1);
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    private int maxPage() {
        return Math.max(0, (entries.size() - 1) / PER_PAGE);
    }

    private void render() {
        inventory.clear();

        // Ornate frame (gold corners + edges) around the crimson field.
        MenuStyle.decorate(inventory, adminView ? "📖 Admin help" : "📖 All features", "🔍 Search");

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < entries.size(); i++) {
            int row = i / 7;
            int column = (i % 7) + 1;
            inventory.setItem(row * 9 + column, entryItem(entries.get(start + i)));
        }

        inventory.setItem(PREVIOUS_SLOT, arrowItem("Previous Page",
                page > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(NEXT_SLOT, arrowItem("Next Page",
                page < maxPage() ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(PRINT_SLOT, printItem());
        inventory.setItem(INFO_SLOT, infoItem());
        inventory.setItem(SEARCH_SLOT, searchItem());
        if (query != null) {
            inventory.setItem(CLEAR_SLOT, clearItem());
        }
    }

    private static ItemStack searchItem() {
        ItemStack item = new ItemStack(Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2692 Search").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Type a keyword and see only what matches").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Try: lock, terrain, structures, delete, weather, tp, price...")
                        .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Click to search").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack clearItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2716 Clear Search").color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Shows the full admin help again").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to clear").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack entryItem(Entry entry) {
        ItemStack item = new ItemStack(entry.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(entry.usage()).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(entry.category().toUpperCase(java.util.Locale.ROOT))
                .color(NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(entry.description()).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Permission: " + entry.permission())
                .color(viewer.hasPermission(entry.permission()) ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(                Component.text(viewer.hasPermission(entry.permission())
                        ? "Click to print this line in chat"
                        : "Needs " + entry.permission() + " — ask an admin")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(query == null
                        ? (adminView ? "Admin Help" : "Help")
                        : "Search: " + query)
                .color(query == null && adminView ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        int first = entries.isEmpty() ? 0 : page * PER_PAGE + 1;
        int last = Math.min((page + 1) * PER_PAGE, entries.size());
        meta.lore(List.of(
                Component.text(query == null
                                ? entries.size() + (adminView
                                        ? " admin action(s) in this plugin"
                                        : " command(s) and panel(s) listed")
                                : entries.size() + " result(s) for '" + query + "'")
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(entries.isEmpty()
                                ? "Nothing matched — clear the search and look again"
                                : "Page " + (page + 1) + "/" + (maxPage() + 1) + " · entries " + first + "-" + last)
                        .color(entries.isEmpty() ? NamedTextColor.RED : NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(adminView
                                ? "Click to return to the Dashboard"
                                : "Click to return to /planets")
                        .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack printItem() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Print This Page").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Puts every entry of this page in chat").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Handy for copying a command somewhere else").color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack arrowItem(String name, NamedTextColor color) {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack fillerItem() {
        return MenuStyle.field();
    }

    private static ItemStack frameItem() {
        return MenuStyle.frame();
    }
}
