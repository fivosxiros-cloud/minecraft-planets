package me.foivos.bounty.gui;

import me.foivos.bounty.BountyPlugin;
import me.foivos.bounty.bounty.Bounty;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The bounty board: {@code /bounty}, {@code /b} or {@code /bounties}.
 *
 * <pre>
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 *   🟦 ▣ ▣ ▣ 👤 ▣ ▣ ▣ 🟦        👤 your own bounty, always in the middle here
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦        21 bounty heads, biggest first by default
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦🟦🟦 ◀ 1/3 ⇅ ▶ 🟦🟦🟦     paging, sorting and close along the bottom
 * </pre>
 *
 * <p>Only the total on somebody's head is ever drawn. Who paid what, and how
 * much a bounty's owner put on themselves, are not on the board at all — that
 * is the one privacy rule this screen has.
 */
public final class BountyGUI implements InventoryHolder, BountyHolder {

    private static final int SIZE = 54;
    /** 21 slots, three rows of seven, straight out of the design. */
    private static final int[] ENTRY_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final BountyPlugin plugin;
    private final Player viewer;
    private final Inventory inventory;
    private BountySort sort;
    private int page;

    public BountyGUI(BountyPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.sort = plugin.sortOf(viewer.getUniqueId());
        this.inventory = Bukkit.createInventory(this, SIZE,
                plugin.messages().of(plugin.bountyConfig().guiTitle()));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    // ── The rows ────────────────────────────────────────────────────────

    /** Every bounty except the viewer's own, in the order the board is sorting. */
    private List<Bounty> entries() {
        List<Bounty> all = plugin.bounties().sorted(sort.highestFirst());
        all.removeIf(bounty -> bounty.target().equals(viewer.getUniqueId()));
        return all;
    }

    private int perPage() {
        return Math.max(1, Math.min(plugin.bountyConfig().entriesPerPage(), ENTRY_SLOTS.length));
    }

    private int maxPage(int entryCount) {
        return Math.max(0, (entryCount - 1) / perPage());
    }

    // ── Clicks ──────────────────────────────────────────────────────────

    @Override
    public void handleClick(InventoryClickEvent event, Player player) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        if (slot == plugin.bountyConfig().closeSlot()) {
            player.closeInventory();
            return;
        }
        if (slot == plugin.bountyConfig().sortingSlot()) {
            sort = sort.next();
            plugin.rememberSort(player.getUniqueId(), sort);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.3f);
            render();
            return;
        }
        if (slot == plugin.bountyConfig().previousSlot()) {
            if (page > 0) {
                page--;
                render();
            }
            return;
        }
        if (slot == plugin.bountyConfig().nextSlot()) {
            if (page < maxPage(entries().size())) {
                page++;
                render();
            }
            return;
        }
        if (slot == plugin.bountyConfig().selfSlot()) {
            if (!plugin.bountyConfig().allowSelfBounty()) {
                plugin.messages().send(player, "self-not-allowed", Map.of());
                return;
            }
            openDetails(player, player.getUniqueId());
            return;
        }
        int index = -1;
        for (int i = 0; i < ENTRY_SLOTS.length; i++) {
            if (ENTRY_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        List<Bounty> entries = entries();
        int at = page * perPage() + index;
        if (at < 0 || at >= entries.size()) {
            return;
        }
        openDetails(player, entries.get(at).target());
    }

    private void openDetails(Player player, UUID target) {
        player.closeInventory();
        new BountyDetailsGUI(plugin, player, target).open(player);
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        ItemStack frame = frameItem();
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, frame);
        }

        inventory.setItem(plugin.bountyConfig().selfSlot(), selfItem());

        List<Bounty> entries = entries();
        int perPage = perPage();
        page = Math.min(page, maxPage(entries.size()));
        int start = page * perPage;
        for (int i = 0; i < perPage && i < ENTRY_SLOTS.length && start + i < entries.size(); i++) {
            inventory.setItem(ENTRY_SLOTS[i], bountyItem(entries.get(start + i)));
        }
        if (entries.isEmpty()) {
            inventory.setItem(22, emptyItem());
        }

        inventory.setItem(plugin.bountyConfig().sortingSlot(), sortingItem());
        inventory.setItem(plugin.bountyConfig().pageSlot(), pageItem(entries.size(), perPage));
        inventory.setItem(plugin.bountyConfig().previousSlot(),
                arrowItem("gui.previous-name", page > 0));
        inventory.setItem(plugin.bountyConfig().nextSlot(),
                arrowItem("gui.next-name", page < maxPage(entries.size())));
        inventory.setItem(plugin.bountyConfig().closeSlot(), bareItem(Material.BARRIER,
                "gui.close-name", NamedTextColor.RED));
    }

    private ItemStack frameItem() {
        ItemStack item = new ItemStack(plugin.bountyConfig().frameMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    /** The viewer's own head, always in the same slot whatever the sorting is. */
    private ItemStack selfItem() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(viewer);
        double total = plugin.bounties().total(viewer.getUniqueId());
        Map<String, String> placeholders = Map.of(
                "player", viewer.getName(),
                "amount", plugin.bountyConfig().format(total));
        meta.displayName(plugin.messages().component("gui.self-name", placeholders)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(plugin.messages().lore("gui.self-lore", placeholders));
        item.setItemMeta(meta);
        return item;
    }

    /** One head on the board: the name and the total, and nothing else. */
    private ItemStack bountyItem(Bounty bounty) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(bounty.target()));
        Map<String, String> placeholders = Map.of(
                "player", bounty.targetName(),
                "amount", plugin.bountyConfig().format(bounty.total()));
        meta.displayName(plugin.messages().component("gui.head-name", placeholders)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(plugin.messages().lore("gui.head-lore", placeholders));
        item.setItemMeta(meta);
        return item;
    }

    /** The hint shown in the middle of an empty board. */
    private ItemStack emptyItem() {
        ItemStack item = new ItemStack(Material.COBWEB);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().component("gui.empty-name", Map.of())
                .colorIfAbsent(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(plugin.messages().lore("gui.empty-lore", Map.of()));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack sortingItem() {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        String mode = plugin.messages().first(sort.messageKey());
        meta.displayName(plugin.messages().component("gui.sorting-name", Map.of())
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(plugin.messages().lore("gui.sorting-lore", Map.of("mode", mode)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageItem(int entryCount, int perPage) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        int pages = maxPage(entryCount) + 1;
        Map<String, String> placeholders = Map.of(
                "page", String.valueOf(page + 1),
                "pages", String.valueOf(pages),
                "count", String.valueOf(entryCount));
        meta.displayName(plugin.messages().component("gui.page-name", placeholders)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(plugin.messages().lore("gui.page-lore", placeholders));
        item.setItemMeta(meta);
        return item;
    }

    /** A paging arrow: live when there is a page that way, a dead dye when not. */
    private ItemStack arrowItem(String key, boolean active) {
        if (active) {
            return bareItem(Material.SPECTRAL_ARROW, key, NamedTextColor.AQUA);
        }
        ItemStack item = new ItemStack(Material.LIGHT_GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().component(key, Map.of())
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Nothing that way").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    /** A control drawn from one config message, in the given colour if it names none. */
    private ItemStack bareItem(Material material, String key, NamedTextColor colour) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().component(key, Map.of())
                .colorIfAbsent(colour)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
