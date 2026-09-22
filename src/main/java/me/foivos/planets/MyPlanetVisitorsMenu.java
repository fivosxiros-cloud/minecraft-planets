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

/**
 * "Kick Visitors" UI for /myp → Kick Visitors. Lists every online player who
 * is inside the planet's world but is NOT a member (owner/mods/co-owners are
 * members by definition, so they never show up here). Clicking a player's
 * head instantly teleports them out of the planet; the "Kick Everyone" button
 * (TNT) removes all listed visitors at once.
 */
public final class MyPlanetVisitorsMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int KICK_ALL_SLOT = 49;
    private static final int BACK_SLOT = 53;

    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final Inventory inventory;

    public MyPlanetVisitorsMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("🦶 Kick Visitors — " + data.displayName()).color(NamedTextColor.DARK_RED));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    /** Online players inside this planet's world who are not members. */
    private List<Player> visitors() {
        List<Player> visitors = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(viewer.getUniqueId())) continue;
            if (!online.getWorld().getName().equalsIgnoreCase(data.worldName())) continue;
            if (data.isMember(online.getUniqueId())) continue;
            visitors.add(online);
        }
        return visitors;
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!data.canManage(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner or co-owner can kick visitors.").color(NamedTextColor.RED));
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (slot == BACK_SLOT) {
            player.closeInventory();
            new MyPlanetMenu(plugin, player, data).open(player);
            return;
        }
        if (slot == KICK_ALL_SLOT) {
            List<Player> everyone = visitors();
            if (everyone.isEmpty()) {
                player.sendMessage(Component.text("There are no visitors to kick.").color(NamedTextColor.GRAY));
                return;
            }
            for (Player visitor : everyone) {
                kick(player, visitor);
            }
            player.sendMessage(Component.text("Kicked " + everyone.size() + " visitor(s) from ")
                    .color(NamedTextColor.GREEN)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.GREEN)));
            render(); // refresh the list in place
            return;
        }

        // Player head slots: resolve the clicked visitor by name.
        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType() != Material.PLAYER_HEAD) return;
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta skull) || skull.getOwningPlayer() == null) return;
        Player visitor = Bukkit.getPlayer(skull.getOwningPlayer().getUniqueId());
        if (visitor == null) {
            render(); // visitor logged out; refresh
            return;
        }
        kick(player, visitor);
        render(); // refresh the list in place
    }

    /** Instantly teleports one visitor out of the planet. */
    private void kick(Player kicker, Player visitor) {
        plugin.kickVisitorFromPlanet(visitor, data);
        kicker.sendMessage(Component.text("Kicked ").color(NamedTextColor.GREEN)
                .append(Component.text(visitor.getName()).color(NamedTextColor.YELLOW))
                .append(Component.text(" from ").color(NamedTextColor.GREEN))
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    private void render() {
        inventory.clear();

        List<Player> visitors = visitors();
        int maxHeads = KICK_ALL_SLOT; // slots 0..48 available for heads
        for (int i = 0; i < visitors.size() && i < maxHeads; i++) {
            inventory.setItem(i, headItem(visitors.get(i)));
        }

        // Kick-everything button (always visible, disabled when nobody to kick).
        ItemStack kickAll = new ItemStack(visitors.isEmpty() ? Material.GUNPOWDER : Material.TNT);
        ItemMeta kickAllMeta = kickAll.getItemMeta();
        kickAllMeta.displayName(Component.text(visitors.isEmpty() ? "No visitors on the planet" : "🦶 Kick Everyone")
                .color(visitors.isEmpty() ? NamedTextColor.GRAY : NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        kickAllMeta.lore(List.of(
                Component.text("Visitors online: " + visitors.size()).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(visitors.isEmpty() ? "" : "Click to remove them all").color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)));
        kickAll.setItemMeta(kickAllMeta);
        inventory.setItem(KICK_ALL_SLOT, kickAll);

        // Back button.
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(Component.text("← Back").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        back.setItemMeta(backMeta);
        inventory.setItem(BACK_SLOT, back);

        // Filler everywhere else.
        for (int i = 0; i < SIZE; i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler());
        }
    }

    private ItemStack headItem(Player visitor) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(visitor);
        meta.displayName(Component.text(visitor.getName()).color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Currently visiting your planet").color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Click to kick them out").color(NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        return MenuStyle.field();
    }
}
