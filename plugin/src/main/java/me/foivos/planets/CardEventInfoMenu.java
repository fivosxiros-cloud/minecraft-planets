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
import java.util.Comparator;
import java.util.List;

/**
 * The event's instruction page: what the hunt is, how a card is found, the
 * rules that actually matter (chances, the 64-copy cap, unique-only ranking)
 * and the one exception nobody can earn.
 *
 * <p>It is built from the live {@link CardRegistry}, so the numbers here — how
 * many cards exist, which are rarest, how many are in each category — are
 * always the same numbers the collection is using, even after the config has
 * been edited.
 */
public final class CardEventInfoMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int BACK_SLOT = 45;
    private static final int CLOSE_SLOT = 53;

    /** Where the pages sit: five slots a row, four rows. */
    private static final int[] PAGE_SLOTS = {
            11, 12, 13, 14, 15,
            20, 21, 22, 23, 24,
            29, 30, 31, 32, 33,
            38, 39, 40, 41, 42
    };

    private final Planets plugin;
    private final Player viewer;
    private final CardService cards;
    private final Inventory inventory;

    public CardEventInfoMenu(Planets plugin, Player viewer, CardService cards) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.cards = cards;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text(cards.title("info", "\uD83D\uDCD6 Event Info"))
                        .color(NamedTextColor.DARK_AQUA));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

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
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83D\uDCD6 Event", "\uD83C\uDF10 Rules");
        inventory.setItem(INFO_SLOT, headerItem());

        List<ItemStack> pages = pages();
        for (int i = 0; i < pages.size() && i < PAGE_SLOTS.length; i++) {
            inventory.setItem(PAGE_SLOTS[i], pages.get(i));
        }

        inventory.setItem(BACK_SLOT, backItem());
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta meta = close.getItemMeta();
        meta.displayName(Component.text("\u2716 Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        close.setItemMeta(meta);
        inventory.setItem(CLOSE_SLOT, close);
    }

    /** Every info "page", in the order they are laid out. */
    private List<ItemStack> pages() {
        List<ItemStack> pages = new ArrayList<>();
        CardRegistry registry = cards.registry();

        pages.add(page(Material.WRITTEN_BOOK, "What is the Entity Card Hunt?",
                "Collect a digital card for every mob in the game.",
                "Kill an eligible mob and it may drop its card",
                "straight into your collection \u2014 nothing is ever",
                "put in your inventory.",
                "",
                registry.size() + " cards exist in this event.",
                registry.obtainableCount() + " of them can be found by killing."));

        // The odds this page describes depend on whether the owner has switched
        // bad-luck protection on, so it says which of the two is in force.
        List<String> how = new ArrayList<>(List.of(
                "1. Find an eligible mob and kill it yourself.",
                "2. That mob's own drop chance is rolled once.",
                "3. If it wins the roll, the card is added.",
                ""));
        CardLuckProtection luck = cards.luck();
        if (luck.enabled()) {
            how.add("Bad-luck protection is on: once one card has");
            how.add("failed " + luck.threshold() + " rolls in a row, its odds");
            how.add("creep up with every further failure.");
            int guaranteed = luck.remainingUntilGuaranteed(0);
            if (guaranteed > 0) {
                how.add("It is then guaranteed within " + guaranteed + " more.");
            }
            how.add("");
            how.add("Every card is streaked on its own, so");
            how.add("hunting one mob never helps another.");
        } else {
            how.add("There is no pity counter and no streak bonus,");
            how.add("so every kill has exactly the odds listed.");
        }
        pages.add(page(Material.IRON_SWORD, "How cards are obtained",
                how.toArray(new String[0])));

        pages.add(page(Material.SPAWNER, "Eligible entities",
                registry.size() + " mobs are in the hunt:",
                "animals, monsters and bosses.",
                "",
                "Only kills during the event count.",
                "Cards you already own stay forever."));

        pages.add(page(Material.CLOCK, "Event duration",
                "Time Remaining:",
                String.join("  ", cards.remainingParts()),
                "",
                cards.isActive()
                        ? "The timer keeps running across restarts."
                        : "EVENT ENDED \u2014 no new cards can drop.",
                "Your collection is kept either way."));

        pages.add(page(Material.CHEST, "Copy limit",
                "You can hold up to " + registry.maxCopies() + " copies",
                "of the same card.",
                "",
                "Extra drops of a maxed card are ignored."));

        pages.add(page(Material.GOLD_INGOT, "The leaderboard",
                "Ranked by UNIQUE cards only.",
                "",
                "64 Sheep cards still count as one card.",
                "Copies never change your position.",
                "",
                "Your Position: " + (cards.positionOf(viewer.getUniqueId()) > 0
                        ? "#" + cards.positionOf(viewer.getUniqueId()) : "unranked")));

        pages.add(page(Material.DRAGON_HEAD, "The one exception",
                "Ender Dragon",
                "Status: UNOBTAINABLE",
                "",
                "The Ender Dragon card sits in every collection",
                "but can never be earned from the event.",
                "It is there as a goal nobody reaches."));

        pages.add(dropChancePage());

        for (CardCategory category : registry.categories()) {
            List<CardDefinition> inCategory = registry.byCategory(category);
            int obtainable = 0;
            for (CardDefinition card : inCategory) {
                if (card.obtainable()) {
                    obtainable++;
                }
            }
            pages.add(page(category.icon(), category.displayName() + " (" + inCategory.size() + ")",
                    obtainable + " of them can drop",
                    "",
                    "Common drops: " + chanceRange(inCategory, true),
                    "Rarest drop: " + chanceRange(inCategory, false)));
        }

        List<String> extra = cards.infoLines();
        if (!extra.isEmpty()) {
            pages.add(page(Material.PAPER, "Extra rules", extra.toArray(new String[0])));
        }

        pages.add(page(Material.NETHER_STAR, "Good luck, collector \uD83C\uDF0C",
                "The collection grows one card at a time.",
                "",
                "Open /cards to see how far you are."));
        return pages;
    }

    /** One page listing every obtainable card's odds, rarest first. */
    private ItemStack dropChancePage() {
        List<CardDefinition> droppable = new ArrayList<>();
        for (CardDefinition card : cards.registry().all()) {
            if (card.drops()) {
                droppable.add(card);
            }
        }
        droppable.sort(Comparator.comparingDouble(CardDefinition::chancePercent));

        List<String> lines = new ArrayList<>();
        lines.add("Every mob has its own chance.");
        lines.add("These are per kill, and none of them");
        lines.add("change as you hunt. Rarest first:");
        lines.add("");
        int shown = 0;
        for (CardDefinition card : droppable) {
            lines.add(card.displayName() + " \u2014 " + card.chanceLabel());
            if (++shown >= 40) {
                lines.add("...and " + (droppable.size() - shown) + " more");
                break;
            }
        }
        return page(Material.EXPERIENCE_BOTTLE, "Drop chances (" + droppable.size() + ")",
                lines.toArray(new String[0]));
    }

    /** The highest (common) or lowest (rarest) chance among a category's cards. */
    private static String chanceRange(List<CardDefinition> category, boolean highest) {
        List<CardDefinition> droppable = new ArrayList<>();
        for (CardDefinition card : category) {
            if (card.drops()) {
                droppable.add(card);
            }
        }
        if (droppable.isEmpty()) {
            return "none";
        }
        droppable.sort(Comparator.comparingDouble(CardDefinition::chancePercent));
        CardDefinition pick = highest ? droppable.get(droppable.size() - 1) : droppable.get(0);
        return pick.chanceLabel() + " (" + pick.displayName() + ")";
    }

    private ItemStack headerItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDCD6 Entity Card Hunt").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("A collectible card for every mob", NamedTextColor.GRAY));
        lore.add(line(cards.registry().size() + " cards \u00B7 "
                + cards.registry().maxCopies() + " copies each", NamedTextColor.YELLOW));
        lore.add(line("Your progress: " + cards.uniqueLabel(viewer.getUniqueId())
                + " unique, " + cards.copies(viewer.getUniqueId()) + " copies", NamedTextColor.AQUA));
        lore.add(line(""));
        lore.add(line("Hover over a page for the details", NamedTextColor.DARK_GRAY));
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

    /** One page: a named item whose lore is the given lines. */
    private static ItemStack page(Material material, String name, String... lines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String entry : lines) {
            if (entry == null || entry.isEmpty()) {
                lore.add(Component.text(" "));
                continue;
            }
            lore.add(line(entry, NamedTextColor.GRAY));
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
