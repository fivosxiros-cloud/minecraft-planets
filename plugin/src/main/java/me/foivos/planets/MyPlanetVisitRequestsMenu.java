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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Visit requests for /myp → 📨 Visit Requests: the players who found this
 * planet on the star chart ({@code /ship}) and asked to be let in.
 *
 * <pre>
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 *   📨 head  📨 head  ...          (up to 27 people waiting)
 *   🟦 📋 summary(28) 🟦 🟦 ← Back(32) 🟦🟦🟦
 * </pre>
 *
 * <p>Left-click a head to approve — that sends the normal invitation, so the
 * person still accepts it with {@code /myp accept} — and right-click to turn
 * them away. Only the owner, co-owners and moderators can answer.
 */
public final class MyPlanetVisitRequestsMenu implements InventoryHolder {

    private static final int SIZE = 36;
    private static final int MAX_HEADS = 27;
    private static final int SUMMARY_SLOT = 28;
    private static final int BACK_SLOT = 32;

    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final Inventory inventory;

    public MyPlanetVisitRequestsMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83D\uDCE8 " + data.displayName() + " Visit Requests")
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
            new MyPlanetMenu(plugin, player, data).open(player);
            return;
        }
        if (slot >= MAX_HEADS) {
            return;
        }

        ItemStack item = inventory.getItem(slot);
        if (item == null || item.getType() != Material.PLAYER_HEAD) {
            return;
        }
        if (!data.canManageMembers(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner, co-owner or moderator can answer visit requests.")
                    .color(NamedTextColor.RED));
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta skullMeta) || skullMeta.getOwningPlayer() == null) {
            return;
        }
        UUID requester = skullMeta.getOwningPlayer().getUniqueId();
        String name = targetName(skullMeta.getOwningPlayer());

        if (event.isRightClick()) {
            data.clearVisitRequest(requester);
            plugin.getMyPlanetManager().save();
            player.sendMessage(Component.text("\u2716 Turned away ").color(NamedTextColor.RED)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            reopen(player);
            return;
        }

        // Approving is the ordinary invitation, so capacity, permissions and the
        // accept-by-/myp-accept flow all behave exactly like /myp invite.
        if (plugin.inviteToPlanet(player, data, requester, name)) {
            data.clearVisitRequest(requester);
            plugin.getMyPlanetManager().save();
            player.sendMessage(Component.text("\u2714 Approved - ").color(NamedTextColor.GREEN)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text(" can accept with ").color(NamedTextColor.GREEN))
                    .append(Component.text("/myp accept " + data.worldName()).color(NamedTextColor.AQUA))
                    .append(Component.text(".").color(NamedTextColor.GREEN)));
        }
        reopen(player);
    }

    private void reopen(Player player) {
        player.closeInventory();
        new MyPlanetVisitRequestsMenu(plugin, player, data).open(player);
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, i >= MAX_HEADS ? frame() : filler());
        }

        // Longest waiting first, so nobody is forgotten at the back of the queue.
        List<Map.Entry<UUID, Long>> waiting = new ArrayList<>(data.visitRequests().entrySet());
        waiting.sort(Comparator.comparingLong(Map.Entry::getValue));

        int slot = 0;
        for (Map.Entry<UUID, Long> request : waiting) {
            if (slot >= MAX_HEADS) break;
            inventory.setItem(slot, requestHead(request.getKey(), request.getValue()));
            slot++;
        }

        if (waiting.isEmpty()) {
            ItemStack none = new ItemStack(Material.PAPER);
            ItemMeta meta = none.getItemMeta();
            meta.displayName(Component.text("Nobody is asking to visit").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Requests arrive when a player picks this planet")
                            .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("on the /ship star chart and asks for an invite.")
                            .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
            none.setItemMeta(meta);
            inventory.setItem(13, none);
        }

        inventory.setItem(SUMMARY_SLOT, summaryItem(waiting.size()));
        inventory.setItem(BACK_SLOT, backItem());
    }

    private ItemStack summaryItem(int count) {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDCE8 Visit requests: " + count)
                .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Left-click a player to approve them")
                        .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click to turn them away")
                        .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                Component.text("Members: " + data.totalMembers() + "/" + data.memberCapacity())
                        .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack requestHead(UUID uuid, long askedAt) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        Player online = offline.getPlayer();
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(offline);
        meta.displayName(Component.text(targetName(offline))
                .color(online != null ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(online != null ? "Online now" : "Offline - the request keeps")
                .color(online != null ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Asked " + ago(askedAt) + " ago").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Left-click: approve (invite them)")
                .color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Right-click: turn them away")
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** "3 minutes", "2 hours" — how long someone has been waiting. */
    private static String ago(long sinceMillis) {
        long minutes = Math.max(0, (System.currentTimeMillis() - sinceMillis) / 60_000L);
        if (minutes < 1) {
            return "a moment";
        }
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + (hours == 1 ? " hour" : " hours");
        }
        long days = hours / 24;
        return days + (days == 1 ? " day" : " days");
    }

    private static String targetName(OfflinePlayer player) {
        String name = player.getName();
        return name != null ? name : player.getUniqueId().toString().substring(0, 8);
    }

    private static ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2190 Back").color(NamedTextColor.GRAY)
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
