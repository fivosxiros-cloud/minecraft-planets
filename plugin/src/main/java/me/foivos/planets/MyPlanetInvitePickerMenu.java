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
 * "Who should I invite?" — a page of online players' heads, opened from the
 * invitations menu. Clicking a head sends that player an invitation to the
 * planet and takes the inviter back to the invitations screen. Offline players
 * can still be invited with {@code /myp invite <name>}.
 */
public final class MyPlanetInvitePickerMenu implements InventoryHolder {

    private static final int SIZE = 36; // 4 rows: 27 heads + a framed bottom row
    private static final int BACK_SLOT = 31;
    private static final int MAX_HEADS = 27;

    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final List<Player> candidates;
    private final Inventory inventory;

    public MyPlanetInvitePickerMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.candidates = new ArrayList<>(Bukkit.getOnlinePlayers());
        this.candidates.removeIf(p -> p.getUniqueId().equals(viewer.getUniqueId()));
        this.candidates.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("✉ Invite to " + data.displayName()).color(NamedTextColor.DARK_PURPLE));
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
            new MyPlanetInvitesMenu(plugin, player, data).open(player);
            return;
        }
        if (slot >= MAX_HEADS) return;

        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType() != Material.PLAYER_HEAD) return;
        if (!(item.getItemMeta() instanceof SkullMeta meta) || meta.getOwningPlayer() == null) return;

        Player target = meta.getOwningPlayer().getPlayer();
        if (target == null) {
            player.sendMessage(Component.text("That player just went offline — invite them by name " +
                    "with /myp invite <name> instead.").color(NamedTextColor.RED));
            player.closeInventory();
            new MyPlanetInvitePickerMenu(plugin, player, data).open(player);
            return;
        }
        plugin.inviteToPlanet(player, data, target.getUniqueId(), target.getName());
        player.closeInventory();
        new MyPlanetInvitesMenu(plugin, player, data).open(player);
    }

    private void render() {
        inventory.clear();
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, i >= MAX_HEADS ? frame() : filler());
        }
        for (int i = 0; i < MAX_HEADS && i < candidates.size(); i++) {
            inventory.setItem(i, head(candidates.get(i)));
        }
        if (candidates.isEmpty()) {
            ItemStack none = new ItemStack(Material.BARRIER);
            ItemMeta meta = none.getItemMeta();
            meta.displayName(Component.text("Nobody else is online").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Invite an offline player with ").color(NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)
                            .append(Component.text("/myp invite <name>").color(NamedTextColor.AQUA)
                                    .decoration(TextDecoration.ITALIC, false))));
            none.setItemMeta(meta);
            inventory.setItem(13, none);
        }
        inventory.setItem(BACK_SLOT, backItem());
    }

    private ItemStack head(Player target) {
        boolean member = data.isMember(target.getUniqueId());
        boolean invited = data.isInvited(target.getUniqueId());
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(target);
        meta.displayName(Component.text(target.getName())
                .color(member ? NamedTextColor.GRAY : invited ? NamedTextColor.YELLOW : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (member) {
            lore.add(Component.text("Already a member").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        } else if (invited) {
            lore.add(Component.text("Already invited — waiting for them").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Click to invite them").color(NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("← Back to invitations").color(NamedTextColor.GRAY)
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
