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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The /leaderboards (alias /lb, /lead) menu: every known player as a head,
 * sorted from the richest to the poorest. Each head shows the player's name,
 * their VPL and their NEB — each in its own colour — and heads are not
 * clickable; the page arrows and the anvil filter in the middle of the bottom
 * row are the only interactive items.
 *
 * <p>The filter cycles through three orderings, always highest first:
 * <ol>
 *   <li>VPL balance</li>
 *   <li>NEB amount</li>
 *   <li>Days played</li>
 * </ol>
 *
 * <p>Layout (45 slots, 5 rows):
 * <ul>
 *   <li>Rows 0-3, columns 1-7: up to 28 players per page.</li>
 *   <li>Column 0 and column 8 of those rows plus the whole bottom row: light
 *       blue stained glass panes, holding the arrows and the filter.</li>
 * </ul>
 */
public final class LeaderboardsMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int PER_PAGE = 28;         // 4 rows x 7 columns
    private static final int PREVIOUS_SLOT = 37;
    private static final int REFRESH_SLOT = 39;    // left of the filter
    private static final int FILTER_SLOT = 40;      // middle of the bottom row
    private static final int NEXT_SLOT = 43;

    /** Named colours for the values shown on each head. */
    private static final NamedTextColor NAME_COLOR = NamedTextColor.AQUA;
    private static final NamedTextColor VPL_COLOR = NamedTextColor.GOLD;
    private static final NamedTextColor NEB_COLOR = NamedTextColor.LIGHT_PURPLE;
    private static final NamedTextColor PLAYTIME_COLOR = NamedTextColor.YELLOW;

    /** The orderings the anvil filter cycles through. */
    enum SortMode {
        VPL("VPL", NamedTextColor.GOLD, "most VPL first"),
        NEB("NEB", NamedTextColor.LIGHT_PURPLE, "most NEB first"),
        DAYS("Days played", NamedTextColor.YELLOW, "most days played first");

        private final String label;
        private final NamedTextColor color;
        private final String description;

        SortMode(String label, NamedTextColor color, String description) {
            this.label = label;
            this.color = color;
            this.description = description;
        }

        /** The next mode in the cycle (VPL → NEB → Days played → VPL). */
        SortMode next() {
            SortMode[] modes = values();
            return modes[(ordinal() + 1) % modes.length];
        }
    }

    private final Planets plugin;
    private final Player viewer;
    private final List<Planets.LeaderboardEntry> entries;
    private final Inventory inventory;
    private SortMode mode = SortMode.VPL;
    private int page;

    public LeaderboardsMenu(Planets plugin, Player viewer, List<Planets.LeaderboardEntry> entries) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.entries = entries;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83C\uDFC6 Leaderboards").color(NamedTextColor.GOLD));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Opens the menu for the given player. */
    public void open(Player player) {
        player.openInventory(inventory);
    }

    /**
     * Player heads are decoration only: every click is cancelled, and only the
     * arrows (paging) and the anvil (sorting) react.
     */
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        if (slot == PREVIOUS_SLOT && page > 0) {
            page--;
            render();
        } else if (slot == REFRESH_SLOT) {
            page = 0;
            render();
            player.sendMessage(Component.text("Leaderboards refreshed.").color(NamedTextColor.GREEN));
        } else if (slot == NEXT_SLOT && page < maxPage()) {
            page++;
            render();
        } else if (slot == FILTER_SLOT) {
            mode = mode.next();
            page = 0; // a new ordering starts back at the top
            render();
            player.sendMessage(Component.text("Leaderboard sorted by ").color(NamedTextColor.GRAY)
                    .append(Component.text(mode.label).color(mode.color))
                    .append(Component.text(" — " + mode.description + ".").color(NamedTextColor.GRAY)));
        }
    }

    private List<Planets.LeaderboardEntry> sorted() {
        List<Planets.LeaderboardEntry> list = new ArrayList<>(entries);
        // Stable sort: equal values keep the richest-by-VPL order of the source
        // list, so the ranking never shuffles between renders.
        switch (mode) {
            case VPL -> list.sort(Comparator.comparingDouble(Planets.LeaderboardEntry::vpl).reversed());
            case NEB -> list.sort(Comparator.comparingDouble(Planets.LeaderboardEntry::neb).reversed());
            case DAYS -> list.sort(Comparator.comparingDouble(
                    (Planets.LeaderboardEntry entry) -> plugin.daysPlayed(entry.uuid())).reversed());
        }
        return list;
    }

    private int maxPage() {
        return Math.max(0, (entries.size() - 1) / PER_PAGE);
    }

    private void render() {
        inventory.clear();

        // Ornate frame (gold corners + edges) around the crimson field.
        MenuStyle.decorate(inventory, "🏆 Ranking", "📄 Pages");

        List<Planets.LeaderboardEntry> ranked = sorted();
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < ranked.size(); i++) {
            int row = i / 7;
            int column = (i % 7) + 1;
            // Rank = position in the current ordering.
            inventory.setItem(row * 9 + column, headItem(ranked.get(start + i), start + i + 1));
        }

        inventory.setItem(PREVIOUS_SLOT, arrowItem("Previous Page",
                page > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(REFRESH_SLOT, refreshItem());
        inventory.setItem(NEXT_SLOT, arrowItem("Next Page",
                page < maxPage() ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(FILTER_SLOT, filterItem());
    }

    /** One unclickable player head: name, VPL, NEB (three colours) and playtime. */
    private ItemStack headItem(Planets.LeaderboardEntry entry, int rank) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.uuid()));

        boolean self = entry.uuid().equals(viewer.getUniqueId());
        meta.displayName(Component.text("#" + rank + " " + entry.name())
                .color(self ? NamedTextColor.GREEN : NAME_COLOR)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("VPL: " + Planets.formatPrice(entry.vpl()) + "\u20BE VPL")
                .color(VPL_COLOR).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("NEB: " + Planets.formatPrice(entry.neb()) + " NEB")
                .color(NEB_COLOR).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Playtime: " + Planets.formatHours(plugin.hoursPlayed(entry.uuid())))
                .color(PLAYTIME_COLOR).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("(" + Planets.formatDays(plugin.daysPlayed(entry.uuid())) + ")")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        if (self) {
            lore.add(Component.text("That's you!").color(NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** The anvil in the middle of the bottom row: shows and cycles the filter. */
    private ItemStack filterItem() {
        ItemStack item = new ItemStack(Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2692 Filter: " + mode.label).color(mode.color)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Sorted by " + mode.label + " — " + mode.description)
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Click to sort by " + mode.next().label).color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        int first = entries.isEmpty() ? 0 : page * PER_PAGE + 1;
        int last = Math.min((page + 1) * PER_PAGE, entries.size());
        lore.add(Component.text("Page " + (page + 1) + "/" + (maxPage() + 1) + " · players "
                        + first + "-" + last + " of " + entries.size())
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (!Placeholders.isPresent() || plugin.nebPlaceholderIsUnset()) {
            lore.add(Component.text("NEB needs PlaceholderAPI + leaderboards.neb-placeholder")
                    .color(NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
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

    private ItemStack refreshItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u21BB Refresh").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Re-reads the latest rankings").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack frameItem() {
        return MenuStyle.frame();
    }
}
