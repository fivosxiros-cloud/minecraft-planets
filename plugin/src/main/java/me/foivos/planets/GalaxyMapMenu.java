package me.foivos.planets;

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

import java.util.ArrayList;
import java.util.List;

/**
 * The star chart: the cockpit screen behind {@code /ship}.
 *
 * <pre>
 *   ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣ ▣        planets (??? until this player charts them)
 *   ...
 *   ◀ ▣ ▣ ✖ ◎ ▣ ▣ ▣ ▶          page / close / ship's log
 * </pre>
 *
 * <p>Clicking a planet launches the ship. A planet its owner has locked reads as
 * invite-only and refuses politely unless the player is a member or was invited
 * — the same rule the planet itself enforces.
 */
public final class GalaxyMapMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int DEST_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int CLOSE_SLOT = 48;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final Planets plugin;
    private final Player viewer;
    private final SpaceTravel space;
    private final Inventory inventory;
    private final List<SpaceTravel.Destination> destinations;
    private int page;

    public GalaxyMapMenu(Planets plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.space = plugin.spaceTravel();
        this.destinations = space.destinations(viewer);
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83C\uDF0C Star Chart").color(NamedTextColor.DARK_AQUA));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player player) { player.openInventory(inventory); }

    private int maxPage() {
        return Math.max(0, (destinations.size() - 1) / DEST_SLOTS);
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (slot == PREVIOUS_SLOT) {
            if (page > 0) {
                page--;
                render();
            }
            return;
        }
        if (slot == NEXT_SLOT) {
            if (page < maxPage()) {
                page++;
                render();
            }
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot >= DEST_SLOTS) {
            return;
        }
        int index = page * DEST_SLOTS + slot;
        if (index < 0 || index >= destinations.size()) {
            return;
        }
        SpaceTravel.Destination destination = destinations.get(index);
        // Right-click is the quick launch; left-click flies the ship yourself.
        boolean quick = event.isRightClick();

        // An invitation waiting for you is accepted by flying: that is what makes
        // landing legal, and it is the same thing /myp accept does.
        if (destination.invited() && !destination.ownOrMember()) {
            if (!plugin.acceptPlanetInvite(player, destination.planet().worldName())) {
                return; // they were told why (capacity, or no invitation any more)
            }
            travel(player, destination, quick);
            return;
        }
        if (destination.inviteOnly() && !destination.ownOrMember()) {
            if (destination.requested()) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1.0f);
                player.sendMessage(Component.text("\uD83D\uDCE8 You already asked to visit ").color(NamedTextColor.GRAY)
                        .append(Component.text(destination.planet().name()).color(NamedTextColor.YELLOW))
                        .append(Component.text(" - " + destination.ownerName()
                                + " hasn't answered yet.").color(NamedTextColor.GRAY)));
                return;
            }
            if (event.isShiftClick()) {
                if (plugin.requestPlanetVisit(player, destination.planet().worldName())) {
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                    render();
                }
                return;
            }
            // The planet is locked: the owner has to let this player in first.
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            player.sendMessage(Component.text("\uD83D\uDD12 Invite only: ").color(NamedTextColor.RED)
                    .append(Component.text(destination.ownerName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" keeps this planet to invited players - ")
                            .color(NamedTextColor.RED))
                    .append(Component.text("shift-click to ask them for an invite.")
                            .color(NamedTextColor.YELLOW)));
            return;
        }
        travel(player, destination, quick);
    }

    /**
     * Goes to a planet: the ship itself unless the player asked for the quick
     * launch, and the quick launch when the space world isn't available.
     */
    private void travel(Player player, SpaceTravel.Destination destination, boolean quick) {
        ShipPilot pilot = plugin.shipPilot();
        if (!quick && pilot != null && pilot.start(player, destination.planet().worldName())) {
            return; // they are flying it themselves now
        }
        space.launch(player, destination);
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, filler());
        }
        int start = page * DEST_SLOTS;
        for (int i = 0; i < DEST_SLOTS && start + i < destinations.size(); i++) {
            inventory.setItem(i, destinationItem(destinations.get(start + i)));
        }
        if (destinations.isEmpty()) {
            inventory.setItem(22, emptyItem());
        }
        inventory.setItem(PREVIOUS_SLOT, pageItem("Previous Page", page > 0));
        inventory.setItem(NEXT_SLOT, pageItem("Next Page", page < maxPage()));
        inventory.setItem(CLOSE_SLOT, actionItem(Material.BARRIER, "\u2716 Close", "Leave the cockpit"));
        inventory.setItem(INFO_SLOT, logItem());
    }

    private ItemStack destinationItem(SpaceTravel.Destination destination) {
        boolean charted = destination.charted();
        boolean locked = destination.inviteOnly() && !destination.ownOrMember();
        ItemStack item = new ItemStack(charted ? destination.planet().icon() : Material.ENDER_EYE);
        ItemMeta meta = item.getItemMeta();
        String label = charted ? destination.planet().name() : "??? Uncharted planet";
        meta.displayName(Component.text((locked ? "\uD83D\uDD12 " : "") + label)
                .color(charted ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        if (charted) {
            lore.add(Component.text("Owner: " + destination.ownerName()).color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Nobody has charted this planet yet.")
                    .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Fly there to find out what it is.")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text(destination.ownOrMember()
                        ? "You belong here"
                        : destination.inviteOnly() ? "Access: invite only" : "Access: open to visitors")
                .color(destination.inviteOnly() && !destination.ownOrMember()
                        ? NamedTextColor.RED : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (destination.ownOrMember() || !destination.inviteOnly()) {
            lore.add(Component.text("Left-click: take the ship there yourself")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Right-click: quick launch (no flying)")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        } else if (destination.invited()) {
            lore.add(Component.text("You're invited - click to accept and fly")
                    .color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        } else if (destination.requested()) {
            lore.add(Component.text("Visit requested - waiting for " + destination.ownerName())
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Shift-click to ask " + destination.ownerName() + " for an invite")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** The ship's log: how much of the galaxy this player has seen. */
    private ItemStack logItem() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDF0C Ship's log").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(space.chartedCount(viewer.getUniqueId()) + "/" + destinations.size()
                        + " planets charted").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (space.takeoffCooldownSeconds() > 0) {
            lore.add(Component.text("Ship cooldown: " + Math.round(space.takeoffCooldownSeconds())
                            + "s between flights")
                    .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Invite-only: shift-click to ask the owner")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Left-click a planet to fly your ship there")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Charted planets keep their name and owner here")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        // Anyone in the air is somebody to ride with instead of flying alone.
        List<String> flying = new ArrayList<>();
        if (plugin.shipPilot() != null) {
            for (String name : plugin.shipPilot().pilotNames()) {
                if (!name.equalsIgnoreCase(viewer.getName())) {
                    flying.add(name);
                }
            }
        }
        if (!flying.isEmpty()) {
            lore.add(Component.text("Riding along: " + String.join(", ", flying) + " - /ship ride <player>")
                    .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("No planets to fly to").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("This server has no planet worlds loaded right now.")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack pageItem(String name, boolean active) {
        ItemStack item = new ItemStack(active ? Material.SPECTRAL_ARROW : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)
                .color(active ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Page " + (page + 1) + "/" + (maxPage() + 1))
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack actionItem(Material material, String name, String description) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(description).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        return MenuStyle.field();
    }
}
