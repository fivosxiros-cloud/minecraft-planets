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
 * The {@code /cards} (alias {@code /c}) screen: the front page of the Entity
 * Card Hunt.
 *
 * <pre>
 *   🟦🟦🟦🟦🃏🟦🟦🟦🟦          🃏 Entity Card Hunt
 *   🟦▣▣▣▣▣▣▣🟦                 Unique Cards: 47 / 100
 *   🟦▣ ▣ 🃏 📖 ⏰ 🏆 ▣ ▣ 🟦      🃏 collection · 📖 info · ⏰ timer · 🏆 top
 *   🟦▣▣▣▣▣▣▣🟦
 *   🟦▣ ▣ ▣ 📊 ▣ ▣ ▣ 🟦           📊 your own progress
 *   🟦▣▣▣▣▣▣▣🟦
 *   🟦▣▣▣▣✖▣▣▣🟦                 ✖ close
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 * </pre>
 *
 * <p>The frame is the plugin's usual light-blue {@link MenuStyle} border, so the
 * event sits beside the planet menus rather than looking bolted on.
 */
public final class CardsMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int COLLECTION_SLOT = 20;
    private static final int INFO_BUTTON_SLOT = 21;
    private static final int TIMER_SLOT = 22;
    private static final int LEADERBOARD_SLOT = 23;
    private static final int PROGRESS_SLOT = 31;
    private static final int CLOSE_SLOT = 49;

    private final Planets plugin;
    private final Player viewer;
    private final CardService cards;
    private final Inventory inventory;

    public CardsMenu(Planets plugin, Player viewer, CardService cards) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.cards = cards;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text(cards.title("main", "\uD83C\uDCCF Entity Card Hunt"))
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

        switch (slot) {
            case CLOSE_SLOT -> player.closeInventory();
            case COLLECTION_SLOT -> {
                player.closeInventory();
                cards.openCollection(player);
            }
            case INFO_BUTTON_SLOT -> {
                player.closeInventory();
                cards.openInfo(player);
            }
            case LEADERBOARD_SLOT -> {
                player.closeInventory();
                cards.openLeaderboard(player);
            }
            case TIMER_SLOT -> {
                player.closeInventory();
                cards.printTimer(player);
            }
            case PROGRESS_SLOT -> {
                player.closeInventory();
                cards.openCollection(player);
            }
            default -> {
                // The frame and the unused middle slots do nothing.
            }
        }
        render();
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83C\uDCCF Event", "\uD83C\uDF10 Cards");

        inventory.setItem(INFO_SLOT, infoItem());
        inventory.setItem(COLLECTION_SLOT, collectionItem());
        inventory.setItem(INFO_BUTTON_SLOT, infoButton());
        inventory.setItem(TIMER_SLOT, timerItem());
        inventory.setItem(LEADERBOARD_SLOT, leaderboardItem());
        inventory.setItem(PROGRESS_SLOT, progressItem());

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta meta = close.getItemMeta();
        meta.displayName(Component.text("\u2716 Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        close.setItemMeta(meta);
        inventory.setItem(CLOSE_SLOT, close);
    }

    private ItemStack infoItem() {
        int unique = cards.unique(viewer.getUniqueId());
        int total = cards.total();
        int copies = cards.copies(viewer.getUniqueId());
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDCCF Entity Card Hunt").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Unique Cards: " + unique + " / " + total, NamedTextColor.AQUA));
        lore.add(line("Total Copies: " + copies, NamedTextColor.AQUA));
        lore.add(line(""));
        lore.add(line(cards.isActive()
                ? "The event is running \u2014 kill mobs to find cards."
                : "The event has ended. Your collection is safe.",
                cards.isActive() ? NamedTextColor.GREEN : NamedTextColor.RED));
        lore.add(line("Ends in " + cards.remainingShort(), NamedTextColor.GOLD));
        lore.add(line(""));
        lore.add(line(cards.total() + " cards to find", NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack collectionItem() {
        int unique = cards.unique(viewer.getUniqueId());
        int total = cards.total();
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDCCF Collection").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(unique + " / " + total + " discovered", NamedTextColor.GREEN));
        lore.add(line("Every card as its mob head"));
        lore.add(line("Copies, categories and progress"));
        lore.add(line(""));
        lore.add(Component.text("Click to open your collection")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoButton() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDCD6 Event Info").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("How the hunt works"));
        lore.add(line("Drop chances and the rules"));
        lore.add(line("The 64-copy cap and the leaderboard"));
        lore.add(line(""));
        lore.add(Component.text("Click to read the event page")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack timerItem() {
        boolean active = cards.isActive();
        ItemStack item = new ItemStack(active ? Material.CLOCK : Material.REDSTONE_TORCH);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(active ? "\u23F0 Event Timer" : "\u23F0 EVENT ENDED")
                .color(active ? NamedTextColor.AQUA : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Time Remaining:", NamedTextColor.GRAY));
        for (String part : cards.remainingParts()) {
            lore.add(line(part, active ? NamedTextColor.YELLOW : NamedTextColor.RED));
        }
        lore.add(line(""));
        lore.add(line("The timer survives restarts and reloads"));
        if (cards.hasEnd()) {
            lore.add(line("Ends " + Planets.formatDate(cards.endsAt()), NamedTextColor.DARK_GRAY));
        }
        lore.add(line(""));
        lore.add(Component.text("Click to print the countdown")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack leaderboardItem() {
        int position = cards.positionOf(viewer.getUniqueId());
        ItemStack item = new ItemStack(Material.GOLD_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDFC6 Leaderboards").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Ranked by unique cards only", NamedTextColor.YELLOW));
        lore.add(line("Copies never change a position"));
        lore.add(line(""));
        lore.add(line("Your Position: " + (position > 0 ? "#" + position : "unranked"),
                position > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        lore.add(line("Your Collection: " + cards.unique(viewer.getUniqueId()) + " Unique Cards"));
        lore.add(line(cards.participants() + " players collecting", NamedTextColor.DARK_GRAY));
        lore.add(line(""));
        lore.add(Component.text("Click to open the leaderboard")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack progressItem() {
        int unique = cards.unique(viewer.getUniqueId());
        int total = cards.total();
        int copies = cards.copies(viewer.getUniqueId());
        int percent = total == 0 ? 0 : (int) Math.round(unique * 100.0 / total);
        ItemStack item = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDCCA Your Progress").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Unique Cards: " + unique + " / " + total, NamedTextColor.AQUA));
        lore.add(line("Total Copies: " + copies, NamedTextColor.AQUA));
        lore.add(line("Completion: " + percent + "%", NamedTextColor.GOLD));
        lore.add(line(progressBar(percent), NamedTextColor.GREEN));
        lore.add(line(""));
        lore.add(Component.text("Click to open the collection")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** A small text bar, e.g. ▉▉▉▉▉▉▉░░░░░░░. */
    static String progressBar(int percent) {
        int filled = Math.max(0, Math.min(20, (int) Math.round(percent / 5.0)));
        return "\u2589".repeat(filled) + "\u2591".repeat(20 - filled);
    }

    private static Component line(String text) {
        return line(text, NamedTextColor.GRAY);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }
}
