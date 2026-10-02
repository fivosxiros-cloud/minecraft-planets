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
import java.util.UUID;

/**
 * One member's permissions, opened from {@code /myp → Members} by
 * shift-clicking their head.
 *
 * <p>Each permission can be <b>Default</b> (whatever the member's role gives
 * them), <b>Allowed</b> or <b>Denied</b>; clicking cycles through the three.
 * Only the owner and co-owners can change them, and the owner themselves always
 * keeps every permission.
 *
 * <pre>
 *   ▣ ▣ ▣ ▣ 👤 ▣ ▣ ▣ ▣      the member
 *   ▣ ⛏ ▣ 🧰 ▣ 🚪 ▣ 📦     Build / Containers / Doors / Item Drops
 *   ▣ ✉ ▣ 🦶 ▣ ⚔ ▣ 💥     Invite / Kick Visitors / PvP / Explosions
 *   ▣ ▣ ▣ ▣ ▣ ♻ ▣ ▣ ▣
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ←     back to the member list
 * </pre>
 *
 * <p>The first four are restrictions — a member may always do them while the
 * planet-wide toggle allows it, and denying one takes it away from that member
 * alone. The last four are hand-outs: inviting and kicking follow the role,
 * and PvP and Explosions are off until the owner grants them per player.
 */
public final class MyPlanetPermissionsMenu implements InventoryHolder {

    private static final int SIZE = 36;
    private static final int HEAD_SLOT = 4;
    private static final int RESET_SLOT = 30;
    private static final int BACK_SLOT = 35;

    /** Where each permission's toggle is drawn, in enum order (two tidy rows). */
    private static final int[] PERMISSION_SLOTS = {10, 12, 14, 16, 19, 21, 23, 25};

    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final UUID target;
    private final Inventory inventory;

    public MyPlanetPermissionsMenu(Planets plugin, Player viewer, MyPlanetData data, UUID target) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.target = target;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83D\uDD11 " + memberName(target) + " \u2014 Permissions")
                        .color(NamedTextColor.DARK_PURPLE));
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
            new MyPlanetMembersMenu(plugin, player, data).open(player);
            return;
        }
        if (!data.canManage(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner or co-owner can change permissions.")
                    .color(NamedTextColor.RED));
            return;
        }
        if (target.equals(data.ownerUuid())) {
            player.sendMessage(Component.text("The owner always has every permission.").color(NamedTextColor.RED));
            return;
        }
        if (slot == RESET_SLOT) {
            data.clearPermissionOverrides(target);
            plugin.getMyPlanetManager().save();
            player.sendMessage(Component.text("\u267B " + memberName(target))
                    .color(NamedTextColor.GREEN)
                    .append(Component.text(" is back to their role's defaults.").color(NamedTextColor.GREEN)));
            render();
            return;
        }
        for (int i = 0; i < PERMISSION_SLOTS.length; i++) {
            if (PERMISSION_SLOTS[i] != slot || i >= MyPlanetData.Permission.values().length) {
                continue;
            }
            MyPlanetData.Permission permission = MyPlanetData.Permission.values()[i];
            Boolean current = data.permissionOverride(target, permission);
            // Default -> Allowed -> Denied -> Default
            Boolean next = current == null ? Boolean.TRUE : current ? Boolean.FALSE : null;
            data.permission(target, permission, next);
            plugin.getMyPlanetManager().save();
            player.sendMessage(Component.text(memberName(target) + " \u2014 " + permission.displayName() + ": ")
                    .color(NamedTextColor.GRAY)
                    .append(Component.text(next == null ? "Default" : next ? "Allowed" : "Denied")
                            .color(next == null ? NamedTextColor.YELLOW
                                    : next ? NamedTextColor.GREEN : NamedTextColor.RED)));
            render();
            return;
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, filler());
        }
        inventory.setItem(HEAD_SLOT, headItem());
        MyPlanetData.Permission[] permissions = MyPlanetData.Permission.values();
        for (int i = 0; i < PERMISSION_SLOTS.length && i < permissions.length; i++) {
            inventory.setItem(PERMISSION_SLOTS[i], permissionItem(permissions[i]));
        }
        inventory.setItem(RESET_SLOT, resetItem());
        inventory.setItem(BACK_SLOT, backItem());
    }

    private ItemStack headItem() {
        MyPlanetData.Role role = data.roleOf(target);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(target));
        meta.displayName(Component.text(memberName(target)).color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Role: " + (role == null ? "VISITOR" : role.name()))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(data.hasPermissionOverrides(target)
                        ? "Some permissions are set by hand" : "All permissions follow the role")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Click a permission to cycle:").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Default \u2192 Allowed \u2192 Denied")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Build/Containers/Doors/Item Drops restrict;")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("PvP/Invite/Kick/Explosions are granted.")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack permissionItem(MyPlanetData.Permission permission) {
        Boolean override = data.permissionOverride(target, permission);
        boolean effective = data.permission(target, permission);
        String state = override == null ? "Default" : override ? "Allowed" : "Denied";
        NamedTextColor colour = override == null ? NamedTextColor.YELLOW
                : override ? NamedTextColor.GREEN : NamedTextColor.RED;
        Material material = effective ? permission.icon() : Material.GRAY_DYE;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(permission.displayName() + ": " + state)
                .color(colour).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(permission.description()).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(permission.handOut()
                        ? "Handed out per player \u2014 off unless allowed"
                        : "Allowed for members unless denied")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Right now: " + (effective ? "allowed" : "denied"))
                .color(effective ? NamedTextColor.GREEN : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Click to switch to "
                        + (override == null ? "Allowed" : override ? "Denied" : "Default"))
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack resetItem() {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u267B Reset to Role Defaults").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Drops every hand-set permission for this member")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2190 Back to members").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        return MenuStyle.field();
    }

    /** A player's name, falling back to a short uuid. */
    static String memberName(UUID uuid) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }
}
