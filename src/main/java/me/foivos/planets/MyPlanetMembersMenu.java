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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Member management menu for /myp → Members.
 * Shows all members by role and allows role changes.
 */
public final class MyPlanetMembersMenu implements InventoryHolder {

    private static final int SIZE = 36; // 4 rows
    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final Inventory inventory;

    public MyPlanetMembersMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("👥 " + data.displayName() + " Members").color(NamedTextColor.DARK_PURPLE));
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
        if (slot == 35) {
            player.closeInventory();
            new MyPlanetMenu(plugin, player, data).open(player);
        }
        // Clicking a member skull cycles their role: MEMBER -> MODERATOR -> CO_OWNER -> MEMBER
        if (slot >= 0 && slot < 35) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != Material.PLAYER_HEAD) return;
            if (!data.canManageMembers(player.getUniqueId())) {
                player.sendMessage(Component.text("Only owner, co-owner, or moderator can change roles.").color(NamedTextColor.RED));
                return;
            }
            // Extract the UUID from the skull meta
            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof SkullMeta skullMeta) || skullMeta.getOwningPlayer() == null) return;
            UUID target = skullMeta.getOwningPlayer().getUniqueId();
            if (target.equals(data.ownerUuid())) {
                player.sendMessage(Component.text("Can't change the owner's role.").color(NamedTextColor.RED));
                return;
            }
            MyPlanetData.Role current = data.roleOf(target);
            if (current == null) return;
            MyPlanetData.Role next = switch (current) {
                case MEMBER -> MyPlanetData.Role.MODERATOR;
                case MODERATOR -> MyPlanetData.Role.CO_OWNER;
                case CO_OWNER -> MyPlanetData.Role.MEMBER;
                case VISITOR -> MyPlanetData.Role.MEMBER;
                case OWNER -> current; // unreachable
            };
            data.changeRole(target, next);
            plugin.getMyPlanetManager().save();
            // Re-open
            new MyPlanetMembersMenu(plugin, player, data).open(player);
        }
    }

    private void render() {
        inventory.clear();
        // Show all members as player heads
        int slot = 0;
        for (Map.Entry<MyPlanetData.Role, java.util.Set<UUID>> entry :
                Map.of(
                        MyPlanetData.Role.OWNER, data.membersWithRole(MyPlanetData.Role.OWNER),
                        MyPlanetData.Role.CO_OWNER, data.membersWithRole(MyPlanetData.Role.CO_OWNER),
                        MyPlanetData.Role.MODERATOR, data.membersWithRole(MyPlanetData.Role.MODERATOR),
                        MyPlanetData.Role.MEMBER, data.membersWithRole(MyPlanetData.Role.MEMBER),
                        MyPlanetData.Role.VISITOR, data.membersWithRole(MyPlanetData.Role.VISITOR)
                ).entrySet()) {
            for (UUID uuid : entry.getValue()) {
                if (slot >= 35) break;
                inventory.setItem(slot, memberHead(uuid, entry.getKey()));
                slot++;
            }
            if (slot >= 35) break;
        }
        // Filler for remaining slots
        for (int i = slot; i < 35; i++) {
            inventory.setItem(i, filler());
        }
        inventory.setItem(35, backItem());
    }

    private static ItemStack memberHead(UUID uuid, MyPlanetData.Role role) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
        String name = meta.getOwningPlayer().getName();
        if (name == null) name = uuid.toString().substring(0, 8);
        meta.displayName(Component.text(name).color(switch (role) {
            case OWNER -> NamedTextColor.GOLD;
            case CO_OWNER -> NamedTextColor.AQUA;
            case MODERATOR -> NamedTextColor.GREEN;
            case MEMBER -> NamedTextColor.WHITE;
            case VISITOR -> NamedTextColor.GRAY;
        }).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Role: " + role.name()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to cycle role").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        return MenuStyle.field();
    }

    private static ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("← Back").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
