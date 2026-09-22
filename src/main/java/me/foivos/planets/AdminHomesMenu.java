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
 * The homes half of the admin panel: every player that has homes saved, as a
 * head showing how many they have and where they are. Clicking one opens
 * {@link AdminPlayerHomesMenu} where each home can be teleported to or deleted.
 *
 * <p>Layout mirrors {@link AdminLobbiesMenu}: 28 players per page, with the
 * paging arrows and the summary on the bottom row.
 */
public final class AdminHomesMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int PER_PAGE = 28;
    private static final int PREVIOUS_SLOT = 37;
    private static final int DASHBOARD_SLOT = 39;
    private static final int INFO_SLOT = 40;
    private static final int NEXT_SLOT = 43;

    /** One owner's homes, prepared for display. */
    record Owner(UUID uuid, String name, List<HomeManager.Home> homes) {
    }

    private final Planets plugin;
    private final Player viewer;
    private final List<Owner> owners;
    private final Inventory inventory;
    private int page;

    public AdminHomesMenu(Planets plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.owners = collect();
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\u2699 Homes").color(NamedTextColor.DARK_AQUA));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    /** Every player with homes, most homes first, then by name. */
    private List<Owner> collect() {
        List<Owner> list = new ArrayList<>();
        for (Map.Entry<UUID, List<HomeManager.Home>> entry : plugin.getHomeManager().allHomes().entrySet()) {
            String name = plugin.homesOwnerName(entry.getKey());
            list.add(new Owner(entry.getKey(), name, entry.getValue()));
        }
        list.sort(Comparator.comparingInt((Owner owner) -> owner.homes().size()).reversed()
                .thenComparing(owner -> owner.name().toLowerCase(java.util.Locale.ROOT)));
        return list;
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !plugin.canUseAdmin(player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        if (slot == PREVIOUS_SLOT && page > 0) {
            page--;
            render();
            return;
        }
        if (slot == NEXT_SLOT && page < maxPage()) {
            page++;
            render();
            return;
        }
        if (slot == DASHBOARD_SLOT) {
            plugin.openAdminDashboard(player);
            return;
        }

        Owner owner = ownerAt(slot);
        if (owner == null) {
            return;
        }
        plugin.openAdminPlayerHomes(player, owner.uuid());
    }

    private Owner ownerAt(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        if (row > 3 || column == 0 || column == 8) {
            return null;
        }
        int index = page * PER_PAGE + row * 7 + (column - 1);
        return index >= 0 && index < owners.size() ? owners.get(index) : null;
    }

    private int maxPage() {
        return Math.max(0, (owners.size() - 1) / PER_PAGE);
    }

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83C\uDFE0 Homes", "\uD83D\uDC65 Players");

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < owners.size(); i++) {
            int row = i / 7;
            int column = (i % 7) + 1;
            inventory.setItem(row * 9 + column, ownerItem(owners.get(start + i)));
        }

        inventory.setItem(PREVIOUS_SLOT, arrowItem("Previous Page",
                page > 0 ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(NEXT_SLOT, arrowItem("Next Page",
                page < maxPage() ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        inventory.setItem(INFO_SLOT, infoItem());
        inventory.setItem(DASHBOARD_SLOT, actionItem(Material.COMPARATOR, "Dashboard",
                "Server-wide overview, cleanup and the planet list"));
    }

    private ItemStack ownerItem(Owner owner) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(owner.uuid());
            skull.setOwningPlayer(offline);
            item.setItemMeta(skull);
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDFE0 " + owner.name()).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(owner.homes().size() + " home(s) saved"));
        lore.add(line(Bukkit.getPlayer(owner.uuid()) != null ? "Online now" : "Offline",
                Bukkit.getPlayer(owner.uuid()) != null ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        lore.add(line(""));
        for (HomeManager.Home home : owner.homes()) {
            lore.add(Component.text(" \u2022 ").color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(home.name()).color(NamedTextColor.AQUA)
                            .decoration(TextDecoration.ITALIC, false))
                    .append(Component.text(" — " + home.describe()).color(NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
        }
        lore.add(line(""));
        lore.add(Component.text("Click to teleport to or delete these homes")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack infoItem() {
        int total = owners.stream().mapToInt(owner -> owner.homes().size()).sum();
        ItemStack item = new ItemStack(Material.LIGHT_BLUE_BED);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Homes: " + total).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Saved by " + owners.size() + " player(s)"));
        lore.add(line("Every player may save " + plugin.getHomeManager().slots() + " homes"));
        lore.add(line("Stored in homes.yml", NamedTextColor.DARK_GRAY));
        int first = owners.isEmpty() ? 0 : page * PER_PAGE + 1;
        int last = Math.min((page + 1) * PER_PAGE, owners.size());
        lore.add(line("Page " + (page + 1) + "/" + (maxPage() + 1) + " \u00B7 players "
                + first + "-" + last + " of " + owners.size(), NamedTextColor.DARK_GRAY));
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

    private static ItemStack actionItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String text : loreLines) {
            lore.add(line(text));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack arrowItem(String name, NamedTextColor color) {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
