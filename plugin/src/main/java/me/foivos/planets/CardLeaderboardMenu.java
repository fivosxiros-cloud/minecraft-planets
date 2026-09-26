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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The unique-card leaderboard: the players with the widest collections, ranked
 * by <b>how many different cards</b> they hold.
 *
 * <p>Copies are shown for flavour but deliberately never affect the order — 64
 * Sheep cards are still worth exactly one card. The viewer's own row is always
 * shown at the bottom, so being 47th is still visible without scrolling through
 * pages.
 *
 * <p>The ranking is paged — {@code cards.leaderboard-size} players per page — so
 * a busy server can still be browsed past the top few. The page a player was
 * reading is remembered (see {@code cards.remember-filters}), so closing and
 * reopening the board, even after a relog, drops them back where they were
 * rather than at the top again.
 *
 * <p>An open board re-draws itself every few seconds
 * ({@code cards.leaderboard-live-refresh-seconds}), which is why the class keeps
 * a list of the boards currently on someone's screen: only those get redrawn.
 */
public final class CardLeaderboardMenu implements InventoryHolder {

    /** Boards currently open on a player's screen, for the live refresh. */
    private static final List<CardLeaderboardMenu> OPEN = new CopyOnWriteArrayList<>();

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int[] RANK_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int YOU_SLOT = 40;
    private static final int PREVIOUS_SLOT = 37;
    private static final int NEXT_SLOT = 43;
    private static final int BACK_SLOT = 45;
    private static final int REFRESH_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    private final Planets plugin;
    private final Player viewer;
    private final CardService cards;
    private final Inventory inventory;

    private int page;

    public CardLeaderboardMenu(Planets plugin, Player viewer, CardService cards) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.cards = cards;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text(cards.title("leaderboard", "\uD83C\uDFC6 Card Leaderboard"))
                        .color(NamedTextColor.DARK_AQUA));
        restorePage();
        render();
    }

    /** Players drawn per page — the configured size, clipped to the grid. */
    private int pageSize() {
        return Math.max(1, Math.min(cards.leaderboardSize(), RANK_SLOTS.length));
    }

    private int maxPage() {
        return Math.max(0, (cards.leaderboard().size() - 1) / pageSize());
    }

    /**
     * Reopens the board on the page this player was reading. A page that no
     * longer exists — because the ranking shrank — is pulled back into range.
     */
    private void restorePage() {
        if (!cards.rememberFilters()) {
            return;
        }
        CardStore.Record record = cards.store().peek(viewer.getUniqueId());
        if (record == null) {
            return;
        }
        page = Math.max(0, Math.min(record.viewBoardPage(), maxPage()));
    }

    /** Stores the page so it is still there next session. */
    private void rememberPage() {
        if (!cards.rememberFilters()) {
            return;
        }
        cards.store().rememberBoardPage(viewer.getUniqueId(), page);
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) {
        player.openInventory(inventory);
        if (!OPEN.contains(this)) {
            OPEN.add(this);
        }
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        if (slot == CLOSE_SLOT) {
            OPEN.remove(this);
            player.closeInventory();
            return;
        }
        if (slot == BACK_SLOT) {
            OPEN.remove(this);
            player.closeInventory();
            cards.openMain(player);
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            if (page > 0) {
                page--;
                rememberPage();
                render();
            }
            return;
        }
        if (slot == NEXT_SLOT) {
            if (page < maxPage()) {
                page++;
                rememberPage();
                render();
            }
            return;
        }
        if (slot == REFRESH_SLOT) {
            cards.invalidateLeaderboard();
            render();
            player.sendMessage(Component.text("\uD83C\uDFC6 Leaderboard refreshed.")
                    .color(NamedTextColor.GREEN));
        }
    }

    /** Redraws every board that is still on somebody's screen. */
    static void refreshOpen() {
        for (CardLeaderboardMenu menu : OPEN) {
            Player viewer = menu.viewer;
            if (viewer == null || !viewer.isOnline()
                    || viewer.getOpenInventory().getTopInventory().getHolder() != menu) {
                OPEN.remove(menu);
                continue;
            }
            menu.render();
        }
    }

    /** Called on shutdown so nothing is left holding a player. */
    static void clearOpen() {
        OPEN.clear();
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        // The live refresh can redraw the board while the ranking shrinks under
        // the viewer, so never draw a page that has just stopped existing.
        page = Math.min(page, maxPage());
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83C\uDFC6 Ranking", "\uD83C\uDCCF Cards");

        List<CardService.Ranked> ranked = cards.leaderboard();
        int perPage = pageSize();
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < ranked.size(); i++) {
            inventory.setItem(RANK_SLOTS[i], rankItem(ranked.get(start + i)));
        }
        inventory.setItem(INFO_SLOT, infoItem());
        inventory.setItem(YOU_SLOT, youItem());
        inventory.setItem(PREVIOUS_SLOT, pageItem("Previous Page", page > 0));
        inventory.setItem(NEXT_SLOT, pageItem("Next Page", page < maxPage()));
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(REFRESH_SLOT, refreshItem());

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta meta = close.getItemMeta();
        meta.displayName(Component.text("\u2716 Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        close.setItemMeta(meta);
        inventory.setItem(CLOSE_SLOT, close);
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.GOLD_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDFC6 ENTITY CARD LEADERBOARD")
                .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Ranked by unique cards collected", NamedTextColor.YELLOW));
        lore.add(line("Copies do not affect the ranking", NamedTextColor.GRAY));
        lore.add(line(""));
        lore.add(line(cards.participants() + " player(s) collecting", NamedTextColor.AQUA));
        lore.add(line("Page " + (page + 1) + "/" + (maxPage() + 1)
                + " \u00B7 " + pageSize() + " per page", NamedTextColor.DARK_GRAY));
        lore.add(line("Showing ranks " + (page * pageSize() + 1) + "\u2013"
                + Math.min((page + 1) * pageSize(), cards.leaderboard().size())
                + " of " + cards.leaderboard().size(), NamedTextColor.DARK_GRAY));
        lore.add(line("Updates every "
                + plugin.getConfig().getLong("cards.leaderboard-live-refresh-seconds", 20L)
                + "s while open", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack rankItem(CardService.Ranked entry) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(entry.uuid()));
        boolean self = entry.uuid().equals(viewer.getUniqueId());
        meta.displayName(Component.text("#" + entry.rank() + " " + entry.name())
                .color(self ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(entry.unique() + " Unique Cards", NamedTextColor.YELLOW));
        lore.add(line(entry.copies() + " total copies", NamedTextColor.GRAY));
        lore.add(line("of " + cards.total() + " cards in the event", NamedTextColor.DARK_GRAY));
        lore.add(line(CardsMenu.progressBar(cards.total() == 0 ? 0
                : (int) Math.round(entry.unique() * 100.0 / cards.total())), NamedTextColor.GREEN));
        if (self) {
            lore.add(line("That's you!", NamedTextColor.GREEN));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** The viewer's own row, always shown even when they are far down the list. */
    private ItemStack youItem() {
        UUID uuid = viewer.getUniqueId();
        int position = cards.positionOf(uuid);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(viewer);
        meta.displayName(Component.text("\uD83D\uDC64 Your Position: "
                        + (position > 0 ? "#" + position : "unranked"))
                .color(position > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Your Collection: " + cards.unique(uuid) + " Unique Cards",
                NamedTextColor.AQUA));
        lore.add(line("Total Copies: " + cards.copies(uuid), NamedTextColor.GRAY));
        lore.add(line(""));
        if (position <= 0) {
            lore.add(line("Find your first card to get ranked.", NamedTextColor.YELLOW));
        } else {
            int ahead = position - 1;
            CardService.Ranked next = ahead > 0 && ahead <= cards.leaderboard().size()
                    ? cards.leaderboard().get(ahead - 1) : null;
            if (next != null) {
                lore.add(line("Next up: #" + next.rank() + " " + next.name()
                        + " (" + next.unique() + ")", NamedTextColor.YELLOW));
                lore.add(line((next.unique() - cards.unique(uuid))
                        + " more unique card(s) to pass them", NamedTextColor.DARK_GRAY));
            } else {
                lore.add(line("You are #1 \u2014 keep it!", NamedTextColor.GOLD));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(cards.backLabel()).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Returns to the main /cards menu"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageItem(String name, boolean active) {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)
                .color(active ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Page " + (page + 1) + "/" + (maxPage() + 1)
                + " \u00B7 " + cards.leaderboard().size() + " player(s)", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack refreshItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u21BB Refresh").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Re-reads the latest ranking now"));
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
