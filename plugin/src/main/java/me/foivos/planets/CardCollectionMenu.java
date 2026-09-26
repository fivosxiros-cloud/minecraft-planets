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
 * The collection itself: one slot per card in the catalogue, drawn as that
 * mob's head.
 *
 * <p>It has two layouts, switched by the button on the top row (slot 8):
 *
 * <pre>
 *   detail — roomy, full lore on every card, 28 a page
 *   🟦🟦🟦🟦🃏🟦🟦🟦🔎          🃏 Card Collection
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦          rows 1-4: up to 28 cards a page
 *   ⬅ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ➡          ⬅ ➡ page back / on
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦↩ 🐑👹👑✨ ▣ 📑 ✖▣ 🟦       ↩ back · category tabs · 📑 filter · ✖ close
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 *
 *   album — compact, 36 a page, cards fill the sides of the frame
 *   🐑👹👑✨🃏🟦🟦🟦🔎          tabs and progress ride the top row
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣          four full-width rows of cards
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣
 *   🟦↩ ⬅ ➡ 🟦🟦🟦 📑 ✖ 🟦       ↩ back · ⬅ ➡ pages · 📑 filter · ✖ close
 * </pre>
 *
 * <p>A card the player owns shows its real head and its copy count. One they
 * have not found keeps the head hidden behind the configured unknown icon —
 * the slot is still there, so the collection reads as a Pokédex with gaps
 * rather than a list that grows mysteriously. Setting
 * {@code cards.reveal-undiscovered: true} turns the whole catalogue visible
 * instead, for servers that prefer that.
 *
 * <p>Whatever the player has filtered to is remembered (see
 * {@code cards.remember-filters}), so closing the menu and coming back — even
 * after a relog or a restart — lands on the same layout, category, filter and
 * page. The album layout is the fastest way to see the whole set at once: with
 * 36 cards a page most categories fit on a single screen, and the progress item
 * spells out how many gaps are left.
 */
public final class CardCollectionMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int PREVIOUS_SLOT = 18;
    private static final int NEXT_SLOT = 26;
    /** The card grid of the roomy "detail" layout. */
    private static final int[] CARD_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    /**
     * The card grid of the compact album: four full-width rows, with no room
     * taken out of the sides, so a page holds 36 cards instead of 28.
     */
    private static final int ALBUM_FIRST_SLOT = 9;
    private static final int ALBUM_PER_PAGE = 36;
    private static final int[] ALBUM_SLOTS = albumSlots();

    /** Switches between the two layouts. On the top row in both. */
    private static final int LAYOUT_SLOT = 8;
    /** Previous/next page: the detail layout's own slots, or the album's row. */
    private static final int ALBUM_PREVIOUS_SLOT = 46;
    private static final int ALBUM_NEXT_SLOT = 47;

    private static final int BACK_SLOT = 45;
    private static final int FIRST_CATEGORY_SLOT = 46;
    /** The album's tabs ride along the top row, before the progress item. */
    private static final int ALBUM_FIRST_CATEGORY_SLOT = 0;
    private static final int FILTER_SLOT = 52;
    private static final int CLOSE_SLOT = 53;

    /** The album grid, filled in at class-load time. */
    private static int[] albumSlots() {
        int[] slots = new int[ALBUM_PER_PAGE];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = ALBUM_FIRST_SLOT + i;
        }
        return slots;
    }

    /** Which cards a page shows. The key is what gets written to disk. */
    private enum Ownership {
        ALL("all", "All", Material.CHEST),
        OWNED("owned", "Owned", Material.LIME_DYE),
        MISSING("missing", "Missing", Material.GRAY_DYE);

        private final String key;
        private final String label;
        private final Material icon;

        Ownership(String key, String label, Material icon) {
            this.key = key;
            this.label = label;
            this.icon = icon;
        }

        Ownership next() {
            Ownership[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        /** A filter by its stored key, falling back when it is blank/unknown. */
        static Ownership byKey(String key, Ownership fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            for (Ownership value : values()) {
                if (value.key.equalsIgnoreCase(key.trim())) {
                    return value;
                }
            }
            return fallback;
        }
    }

    private final Planets plugin;
    private final Player viewer;
    private final CardService cards;
    private final Inventory inventory;

    private CardCategory category;
    private Ownership ownership = Ownership.ALL;
    private int page;
    private boolean album;

    // ── Layout, which differs between the two views ──────────────────────

    /** The slots cards are drawn into for the view currently showing. */
    private int[] gridSlots() { return album ? ALBUM_SLOTS : CARD_SLOTS; }

    /** How many cards one page of the current view holds. */
    private int perPage() { return album ? ALBUM_PER_PAGE : CARD_SLOTS.length; }

    private int previousSlot() { return album ? ALBUM_PREVIOUS_SLOT : PREVIOUS_SLOT; }

    private int nextSlot() { return album ? ALBUM_NEXT_SLOT : NEXT_SLOT; }

    private int firstTabSlot() { return album ? ALBUM_FIRST_CATEGORY_SLOT : FIRST_CATEGORY_SLOT; }

    /** The last slot a category tab may occupy without hitting a control. */
    private int lastTabSlot() { return album ? INFO_SLOT - 1 : FILTER_SLOT - 1; }

    public CardCollectionMenu(Planets plugin, Player viewer, CardService cards) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.cards = cards;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text(cards.title("collection", "\uD83C\uDCCF Card Collection"))
                        .color(NamedTextColor.DARK_AQUA));
        restoreView();
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    /**
     * Reopens the collection on the category, filter and page this player left
     * it on. A stored category that no longer exists — because the card list
     * changed — simply falls back to "All", and a page past the end of the
     * filtered results is pulled back into range.
     */
    private void restoreView() {
        if (!cards.rememberFilters()) {
            return;
        }
        CardStore.Record record = cards.store().peek(viewer.getUniqueId());
        if (record == null) {
            return;
        }
        String key = record.viewCategory();
        if (!key.isBlank()) {
            for (CardCategory value : cards.registry().categories()) {
                if (value.key().equalsIgnoreCase(key)) {
                    category = value;
                    break;
                }
            }
        }
        ownership = Ownership.byKey(record.viewFilter(), ownership);
        album = record.viewAlbum();
        page = Math.max(0, Math.min(record.viewPage(), maxPage()));
    }

    /** Stores the current layout, category, filter and page so they survive a relog. */
    private void rememberView() {
        if (!cards.rememberFilters()) {
            return;
        }
        cards.store().rememberView(viewer.getUniqueId(),
                category == null ? "" : category.key(), ownership.key, page, album);
    }

    /** The cards on this page, with the category and ownership filters applied. */
    private List<CardDefinition> rows() {
        List<CardDefinition> list = new ArrayList<>(category == null
                ? cards.registry().all() : cards.registry().byCategory(category));
        if (ownership == Ownership.OWNED) {
            list.removeIf(card -> !cards.has(viewer.getUniqueId(), card.id()));
        } else if (ownership == Ownership.MISSING) {
            list.removeIf(card -> cards.has(viewer.getUniqueId(), card.id()));
        }
        return list;
    }

    private int maxPage() {
        return Math.max(0, (rows().size() - 1) / perPage());
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == BACK_SLOT) {
            player.closeInventory();
            cards.openMain(player);
            return;
        }
        if (slot == previousSlot()) {
            if (page > 0) {
                page--;
                rememberView();
                render();
            }
            return;
        }
        if (slot == nextSlot()) {
            if (page < maxPage()) {
                page++;
                rememberView();
                render();
            }
            return;
        }
        if (slot == LAYOUT_SLOT) {
            // Switch between the compact album and the roomy detail view, and
            // start at the top: the two grids hold different cards per page, so
            // carrying the page number over would land somewhere unrelated.
            album = !album;
            page = 0;
            rememberView();
            render();
            return;
        }
        if (slot == FILTER_SLOT) {
            ownership = ownership.next();
            page = 0;
            rememberView();
            render();
            return;
        }
        // The category tabs sit in the order the registry reports them, running
        // right from whichever slot this layout starts them at.
        List<CardCategory> categories = cards.registry().categories();
        int tabIndex = slot - firstTabSlot();
        if (tabIndex >= 0 && tabIndex <= categories.size() && slot <= lastTabSlot()) {
            // Index 0 is the "All" tab, the rest are the categories themselves.
            category = tabIndex == 0 ? null : categories.get(tabIndex - 1);
            page = 0;
            rememberView();
            render();
            return;
        }

        ItemStack item = inventory.getItem(slot);
        if (item == null) {
            return;
        }
        int withinRow = slotIndex(slot);
        if (withinRow < 0) {
            return;
        }
        List<CardDefinition> rows = rows();
        int index = page * perPage() + withinRow;
        if (index < 0 || index >= rows.size()) {
            return;
        }
        describe(player, rows.get(index));
    }

    /** Position of a card slot within the current grid, or -1 when it is not one. */
    private int slotIndex(int slot) {
        int[] grid = gridSlots();
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    /** Clicking a card writes its details into chat. */
    private void describe(Player player, CardDefinition card) {
        int held = cards.count(player.getUniqueId(), card.id());
        player.sendMessage(Component.text("\uD83C\uDCCF ").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(held > 0 ? card.displayName() : cards.registry().unknownName())
                        .color(held > 0 ? card.rarity().color() : NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))
                .append(Component.text("  " + card.category().label()).color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        if (held > 0) {
            player.sendMessage(Component.text("  Copies: " + held + "x / " + card.maxCopies() + "x")
                    .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            player.sendMessage(Component.text("  Rarity: " + card.rarity().label())
                    .color(card.rarity().color()).decoration(TextDecoration.ITALIC, false));
            player.sendMessage(Component.text("  Drop chance: " + card.chanceLabel()
                            + " per kill of a " + card.displayName())
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            long first = cards.store().get(player.getUniqueId()).firstAt(card.id());
            if (first > 0) {
                player.sendMessage(Component.text("  First found: " + Planets.formatDate(first))
                        .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }
        } else {
            player.sendMessage(Component.text("  Status: NOT DISCOVERED")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            player.sendMessage(Component.text("  Keep hunting in " + card.category().label()
                            + " to find this one")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        // A live reload or a filter change can leave the page past the end.
        page = Math.min(page, maxPage());
        inventory.clear();
        if (album) {
            // The compact album fills the sides of the frame with cards, so it
            // keeps only the plain border and drops the two side tags.
            MenuStyle.decorate(inventory);
        } else {
            MenuStyle.decorate(inventory, "\uD83C\uDCCF Cards", "\uD83D\uDCD6 Pages");
        }
        inventory.setItem(INFO_SLOT, infoItem());
        inventory.setItem(LAYOUT_SLOT, layoutItem());

        int[] grid = gridSlots();
        List<CardDefinition> rows = rows();
        int start = page * perPage();
        for (int i = 0; i < grid.length && start + i < rows.size(); i++) {
            inventory.setItem(grid[i], cardItem(rows.get(start + i)));
        }
        if (rows.isEmpty()) {
            inventory.setItem(22, emptyItem());
        }

        inventory.setItem(previousSlot(), pageItem("Previous Page", page > 0));
        inventory.setItem(nextSlot(), pageItem("Next Page", page < maxPage()));

        inventory.setItem(BACK_SLOT, backendItem());

        // "All", then one tab per category this server actually has cards for.
        int firstTab = firstTabSlot();
        inventory.setItem(firstTab, MenuStyle.tab(Material.CHEST,
                "All Cards", category == null,
                "Every card in the event", rows().size() + " shown on this filter"));
        List<CardCategory> categories = cards.registry().categories();
        for (int i = 0; i < categories.size(); i++) {
            CardCategory value = categories.get(i);
            int slot = firstTab + 1 + i;
            if (slot > lastTabSlot()) {
                break;
            }
            inventory.setItem(slot, MenuStyle.tab(value.icon(), value.label(),
                    category == value,
                    cards.registry().byCategory(value).size() + " card(s)",
                    "Filter the collection"));
        }

        inventory.setItem(FILTER_SLOT, filterItem());

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.displayName(Component.text("\u2716 Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        close.setItemMeta(closeMeta);
        inventory.setItem(CLOSE_SLOT, close);
    }

    private ItemStack infoItem() {
        int unique = cards.unique(viewer.getUniqueId());
        int total = cards.total();
        int copies = cards.copies(viewer.getUniqueId());
        int gaps = Math.max(0, total - unique);
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDCCF Card Collection").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Unique Cards: " + unique + " / " + total, NamedTextColor.AQUA));
        lore.add(line("Total Copies: " + copies, NamedTextColor.AQUA));
        lore.add(line(gaps == 0
                        ? "No gaps left \u2014 every card found!"
                        : "Gaps Remaining: " + gaps + " card(s)",
                gaps == 0 ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.YELLOW));
        lore.add(line("Filter: " + ownership.label
                + (category == null ? " \u00B7 all categories" : " \u00B7 " + category.label()),
                NamedTextColor.YELLOW));
        lore.add(line(CardsMenu.progressBar(total == 0 ? 0
                : (int) Math.round(unique * 100.0 / total)), NamedTextColor.GREEN));
        lore.add(line("Page " + (page + 1) + "/" + (maxPage() + 1)
                + " \u00B7 " + perPage() + " per page", NamedTextColor.DARK_GRAY));
        lore.add(line(""));
        lore.add(line(ownership == Ownership.MISSING
                ? "These are the ones you still need"
                : "Click a card for its details", NamedTextColor.DARK_GRAY));
        lore.add(line(album
                ? "Click below for the roomy view with full card details"
                : "Click below to fit the whole set on fewer pages", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** The button that flips between the compact album and the detail view. */
    private ItemStack layoutItem() {
        ItemStack item = new ItemStack(album ? Material.BOOKSHELF : Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(album
                        ? "\uD83D\uDCD6 Detail view"
                        : "\uD83D\uDD0E Album view")
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(album
                ? "Back to the roomy grid, with full lore on every card"
                : "Every card packed into a small grid to scan the whole set"));
        lore.add(line(album
                ? perPage() + " cards a page"
                : (perPage() + " cards a page, up from " + CARD_SLOTS.length),
                NamedTextColor.DARK_GRAY));
        lore.add(line(""));
        lore.add(Component.text("Click to switch to the " + (album ? "detail" : "album") + " view")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack cardItem(CardDefinition card) {
        int held = cards.count(viewer.getUniqueId(), card.id());
        boolean discovered = held > 0;
        boolean hidden = !discovered && !cards.revealUndiscovered();

        ItemStack item = hidden ? new ItemStack(cards.registry().unknownIcon()) : card.iconStack();
        ItemMeta meta = item.getItemMeta();
        if (hidden) {
            meta.displayName(Component.text(cards.registry().unknownName())
                    .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(line("Status: NOT DISCOVERED", NamedTextColor.RED));
            lore.add(line("Category: " + card.category().label(), NamedTextColor.GRAY));
            // The hunted card is exactly where a dry streak is worth showing.
            Component streak = luckLine(card, false);
            if (streak != null) {
                lore.add(streak);
            }
            if (!album) {
                lore.add(line("Rarity: ???", NamedTextColor.DARK_GRAY));
                lore.add(line(""));
                lore.add(line("Keep killing mobs to find it", NamedTextColor.DARK_GRAY));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
            return item;
        }

        meta.displayName(Component.text(card.displayName()).color(card.rarity().color())
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (discovered) {
            boolean maxed = held >= card.maxCopies();
            lore.add(line("Copies: " + held + "x", NamedTextColor.AQUA));
            lore.add(line("Status: COLLECTED", NamedTextColor.GREEN));
            if (album) {
                // The album keeps each card down to the two facts worth
                // scanning for; the detail view is a click away for the rest.
                if (maxed) {
                    lore.add(line("MAXED OUT", NamedTextColor.LIGHT_PURPLE));
                }
            } else {
                lore.add(line(""));
                lore.add(line("Rarity: " + card.rarity().label(), card.rarity().color()));
                lore.add(line("Category: " + card.category().label(), NamedTextColor.GRAY));
                lore.add(line("Max copies: " + card.maxCopies() + "x", NamedTextColor.GRAY));
                if (maxed) {
                    lore.add(line("MAXED OUT", NamedTextColor.LIGHT_PURPLE));
                }
                long first = cards.store().get(viewer.getUniqueId()).firstAt(card.id());
                if (first > 0) {
                    lore.add(line("First found: " + Planets.formatDate(first), NamedTextColor.DARK_GRAY));
                }
            }
            Component streak = luckLine(card, true);
            if (streak != null) {
                lore.add(streak);
            }
            lore.add(line(""));
            lore.add(Component.text("Click for the full card details")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(line("Copies: 0x", NamedTextColor.GRAY));
            lore.add(line("Status: NOT DISCOVERED", NamedTextColor.RED));
            // This branch only runs when the mob is visible (owned is handled
            // above), so the odds it is being rolled at are not a secret.
            Component streak = luckLine(card, true);
            if (streak != null) {
                lore.add(streak);
            }
            if (!album) {
                lore.add(line(""));
                lore.add(line("Drop chance: " + card.chanceLabel(), NamedTextColor.YELLOW));
                lore.add(line("Category: " + card.category().label(), NamedTextColor.GRAY));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * A line about a running dry streak on this card, or null when there is
     * nothing worth saying — either because bad-luck protection is off, because
     * the card is not being hunted yet, or because the boost has not started.
     *
     * <p>This shows on undiscovered cards too, since those are the ones being
     * hunted — but only the <i>multiplier</i>, never the resulting percentage.
     * A percentage on an anonymous slot would give its rarity away, and the
     * multiplier says just as much about how close the boost is.
     *
     * @param withOdds true on a card the player can already see, so the boosted
     *                 chance itself can be named
     */
    private Component luckLine(CardDefinition card, boolean withOdds) {
        if (!cards.luck().enabled()) {
            return null;
        }
        int streak = cards.streak(viewer.getUniqueId(), card);
        if (streak <= 0) {
            return null;
        }
        double multiplier = cards.luck().multiplier(streak);
        int untilGuaranteed = cards.luck().remainingUntilGuaranteed(streak);
        if (multiplier <= 1.0 && untilGuaranteed > 2) {
            return null;
        }
        String boost = multiplier == Math.rint(multiplier)
                ? String.valueOf((long) multiplier)
                : String.valueOf(Math.round(multiplier * 10) / 10.0);
        if (untilGuaranteed == 1) {
            return line("Dry streak: " + streak + "  \u00B7  drops on the next kill!",
                    NamedTextColor.LIGHT_PURPLE);
        }
        String odds = !withOdds ? boost + "x"
                : CardService.percent(cards.effectiveChance(viewer.getUniqueId(), card) * 100.0)
                + " (" + boost + "x)";
        return line("Dry streak: " + streak + "  \u00B7  odds " + odds, NamedTextColor.LIGHT_PURPLE);
    }

    private ItemStack backendItem() {
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

    private ItemStack filterItem() {
        ItemStack item = new ItemStack(ownership.icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDCD1 Showing: " + ownership.label)
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("All \u00B7 Owned \u00B7 Missing"));
        lore.add(line(rows().size() + " card(s) match"));
        lore.add(line(""));
        lore.add(Component.text("Click to show " + ownership.next().label)
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyItem() {
        ItemStack item = new ItemStack(Material.COBWEB);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Nothing matches this filter")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Try another category or filter"));
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
                + " \u00B7 " + rows().size() + " card(s)", NamedTextColor.DARK_GRAY));
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
