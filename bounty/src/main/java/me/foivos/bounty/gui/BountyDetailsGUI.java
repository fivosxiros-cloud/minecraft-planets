package me.foivos.bounty.gui;

import me.foivos.bounty.BountyPlugin;
import me.foivos.bounty.bounty.BountyPlacer;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One bounty's own screen, opened from the board:
 *
 * <pre>
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 *   🟦🟦🟦🟦👤🟦🟦🟦🟦        👤 whose bounty it is, and what it is worth
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 *   🟦🟦🟦🟦💰🟦🟦🟦🟦        💰 add to it: click to type an amount,
 *   🟦🟦🟦 ◀ 🟦 ✖ 🟦🟦🟦           right-click to add the quick amount
 * </pre>
 *
 * <p>Adding is the only thing a screen can do to somebody else's bounty, and it
 * is the same operation as {@code /bounty add}: money leaves the clicker's own
 * balance, so nothing here can create or destroy money.
 */
public final class BountyDetailsGUI implements InventoryHolder, BountyHolder {

    private static final int SIZE = 45;
    private static final int HEAD_SLOT = 13;
    private static final int ADD_SLOT = 31;
    private static final int BACK_SLOT = 39;
    private static final int CLOSE_SLOT = 41;

    private final BountyPlugin plugin;
    private final Player viewer;
    private final UUID target;
    private final Inventory inventory;

    public BountyDetailsGUI(BountyPlugin plugin, Player viewer, UUID target) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.target = target;
        this.inventory = Bukkit.createInventory(this, SIZE, title(plugin, target));
        render();
    }

    private static Component title(BountyPlugin plugin, UUID target) {
        String name = plugin.bounties().nameOf(target);
        return plugin.messages().of(plugin.messages().text("gui.details-title", Map.of("player", name)));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    private String targetName() {
        return plugin.bounties().nameOf(target);
    }

    /** Whether this player may add to this bounty right now. */
    private boolean canAdd() {
        if (!plugin.economy().available()) {
            return false;
        }
        boolean self = target.equals(viewer.getUniqueId());
        return !self || plugin.bountyConfig().allowSelfBounty();
    }

    @Override
    public void handleClick(InventoryClickEvent event, Player player) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == BACK_SLOT) {
            player.closeInventory();
            new BountyGUI(plugin, player).open(player);
            return;
        }
        if (slot != ADD_SLOT) {
            return;
        }
        if (!plugin.economy().available()) {
            plugin.messages().send(player, "economy-missing", Map.of());
            return;
        }
        if (target.equals(player.getUniqueId()) && !plugin.bountyConfig().allowSelfBounty()) {
            plugin.messages().send(player, "self-not-allowed", Map.of());
            return;
        }
        if (event.isRightClick()) {
            // The quick add: no typing, straight from the balance.
            double quick = plugin.bountyConfig().quickAddAmount();
            BountyPlacer.Attempt attempt = plugin.placer().place(player, Bukkit.getOfflinePlayer(target),
                    targetName(), quick);
            plugin.feedback().placement(player, attempt, plugin.bountyConfig().format(quick));
            if (attempt.placed()) {
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                render();
            }
            return;
        }
        // Left-click: type the amount into a dialog instead of into chat.
        AmountDialog.open(plugin, player, target);
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        ItemStack frame = frameItem();
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, frame);
        }
        inventory.setItem(HEAD_SLOT, headItem());
        inventory.setItem(ADD_SLOT, addItem());
        inventory.setItem(BACK_SLOT, button(Material.ARROW, "gui.back-name", NamedTextColor.AQUA));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "gui.close-name", NamedTextColor.RED));
    }

    private ItemStack frameItem() {
        ItemStack item = new ItemStack(plugin.bountyConfig().frameMaterial());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack headItem() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(target));
        Map<String, String> placeholders = Map.of(
                "player", targetName(),
                "amount", plugin.bountyConfig().format(plugin.bounties().total(target)));
        meta.displayName(plugin.messages().component("gui.head-name", placeholders)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(plugin.messages().lore("gui.details-head-lore", placeholders));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack addItem() {
        boolean can = canAdd();
        ItemStack item = new ItemStack(can ? Material.GOLD_INGOT : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().component("gui.add-name", Map.of())
                .colorIfAbsent(can ? NamedTextColor.GOLD : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>(plugin.messages().lore("gui.add-lore", Map.of(
                "quick", plugin.bountyConfig().format(plugin.bountyConfig().quickAddAmount()),
                "player", targetName())));
        lore.add(Component.text(""));
        lore.add(plugin.messages().of("&7Left-click: type an amount"));
        lore.add(plugin.messages().of("&7Right-click: quick add &f"
                + plugin.bountyConfig().format(plugin.bountyConfig().quickAddAmount())));
        if (!plugin.economy().available()) {
            lore.add(Component.text("The server economy is unavailable")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        } else if (!can) {
            lore.add(Component.text("Bounties on yourself are switched off here")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String key, NamedTextColor colour) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().component(key, Map.of())
                .colorIfAbsent(colour)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
