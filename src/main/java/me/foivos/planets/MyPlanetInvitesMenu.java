package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Invitation management for /myp → Invitations.
 *
 * <pre>
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 *   ✉ head  ✉ head  ...           (up to 27 pending invitations)
 *   🟦 📋 summary(27) 🟦 ➕ Invite(29) 🟦 ← Back(31) 🟦🟦🟦
 * </pre>
 *
 * Clicking an invited player's head revokes their invitation; the ➕ book opens
 * a picker of online players. Offline players can be invited by name with
 * {@code /myp invite <player>}.
 */
public final class MyPlanetInvitesMenu implements InventoryHolder {

    private static final int SIZE = 36; // 3 rows of heads + a framed bottom row
    private static final int MAX_HEADS = 27;
    private static final int SUMMARY_SLOT = 28;
    private static final int INVITE_SLOT = 30;
    private static final int BACK_SLOT = 32;

    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final Inventory inventory;

    public MyPlanetInvitesMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("✉ " + data.displayName() + " Invitations").color(NamedTextColor.DARK_PURPLE));
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

        if (slot == BACK_SLOT) {
            player.closeInventory();
            new MyPlanetMenu(plugin, player, data).open(player);
            return;
        }
        if (slot == INVITE_SLOT) {
            if (!data.canManageMembers(player.getUniqueId())) {
                player.sendMessage(Component.text("Only owner, co-owner, or moderator can invite players.")
                        .color(NamedTextColor.RED));
                return;
            }
            player.closeInventory();
            new MyPlanetInvitePickerMenu(plugin, player, data).open(player);
            return;
        }

        // Clicking an invited player's head revokes the invitation.
        if (slot < MAX_HEADS) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != Material.PLAYER_HEAD) return;
            if (!data.canManageMembers(player.getUniqueId())) {
                player.sendMessage(Component.text("Only owner, co-owner, or moderator can manage invitations.")
                        .color(NamedTextColor.RED));
                return;
            }
            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof SkullMeta skullMeta) || skullMeta.getOwningPlayer() == null) return;
            UUID target = skullMeta.getOwningPlayer().getUniqueId();
            data.revokeInvitation(target);
            plugin.getMyPlanetManager().save();
            player.sendMessage(Component.text("Invitation revoked.").color(NamedTextColor.GREEN));
            new MyPlanetInvitesMenu(plugin, player, data).open(player);
        }
    }

    private void render() {
        inventory.clear();
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, i >= MAX_HEADS ? frame() : filler());
        }

        // Every invited player, deduplicated (a player invited by two members).
        Set<UUID> invited = new LinkedHashSet<>();
        for (Set<UUID> targets : data.invitations().values()) {
            invited.addAll(targets);
        }

        int slot = 0;
        for (UUID uuid : invited) {
            if (slot >= MAX_HEADS) break;
            inventory.setItem(slot, invitedHead(uuid));
            slot++;
        }
        if (invited.isEmpty()) {
            ItemStack none = new ItemStack(Material.PAPER);
            ItemMeta meta = none.getItemMeta();
            meta.displayName(Component.text("No pending invitations").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Use ➕ Invite a Player below").color(NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("or /myp invite <player>").color(NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
            none.setItemMeta(meta);
            inventory.setItem(13, none);
        }

        inventory.setItem(SUMMARY_SLOT, summaryItem(invited.size()));
        inventory.setItem(INVITE_SLOT, inviteItem());
        inventory.setItem(BACK_SLOT, backItem());
    }

    private ItemStack summaryItem(int count) {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("✉ Pending invitations: " + count).color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Members: " + data.totalMembers() + "/" + data.memberCapacity())
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Click an invited head to revoke it").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack inviteItem() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.displayName(Component.text("➕ Invite a Player").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Pick an online player to invite").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Offline players: /myp invite <name>").color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack invitedHead(UUID uuid) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        Player online = offline.getPlayer();
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(offline);
        String name = offline.getName();
        if (name == null) name = uuid.toString().substring(0, 8);
        meta.displayName(Component.text(name).color(online != null ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(online != null ? "Online now" : "Offline — invitation is waiting")
                .color(online != null ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Click to revoke invitation").color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("← Back").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        return MenuStyle.field();
    }

    private static ItemStack frame() {
        return MenuStyle.frame();
    }
}
