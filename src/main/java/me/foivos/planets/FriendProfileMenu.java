package me.foivos.planets;

import me.foivos.playerdata.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
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
 * One friend's profile: the same numbers the rest of the server already keeps
 * about them — balance and value from the economy, playtime and owned planets
 * from the player-data centre, deaths and kills from the game's own statistics
 * — plus where they are, when they were last around, and who the two of you
 * have in common.
 *
 * <pre>
 *   🟦🟦🟦🟦👤🟦🟦🟦🟦          👤 Alex (Online)
 *   🟦 💰 ▣ ⏱ ▣ ☠ ▣ 🪐 🟦        the numbers row
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦 🟢 ▣ 📍 ▣ 🕓 ▣ 🔗 🟦        status, where, last seen, mutual
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦 ↩ 💬 ★ 🎁 ▣ 🔗 ▣ ✖ ▣ 🟦    back, message, favourite, gift, mutual, remove, close
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 * </pre>
 *
 * <p>Profile privacy is theirs, not yours: if they switched it on and you are
 * not on their friends list, the personal numbers are replaced with a note
 * rather than shown.
 */
public final class FriendProfileMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int HEAD_SLOT = 4;
    private static final int BALANCE_SLOT = 10;
    private static final int PLAYTIME_SLOT = 12;
    private static final int STATS_SLOT = 14;
    private static final int PLANETS_SLOT = 16;
    private static final int STATUS_SLOT = 28;
    private static final int WHERE_SLOT = 30;
    private static final int LAST_SEEN_SLOT = 32;
    private static final int MUTUAL_SLOT = 34;

    private static final int BACK_SLOT = 45;
    private static final int MESSAGE_SLOT = 46;
    private static final int FAVORITE_SLOT = 47;
    private static final int GIFT_SLOT = 48;
    private static final int MUTUAL_ACTION_SLOT = 50;
    private static final int REMOVE_SLOT = 52;
    private static final int CLOSE_SLOT = 53;

    private final Planets plugin;
    private final Player viewer;
    private final FriendSystem system;
    private final UUID target;
    private final Inventory inventory;

    /** The remove button needs a second click, so a mis-click can't undo a friendship. */
    private boolean armedRemove;

    public FriendProfileMenu(Planets plugin, Player viewer, FriendSystem system, UUID target) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.system = system;
        this.target = target;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83D\uDC64 Friend Profile").color(NamedTextColor.DARK_AQUA));
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

        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == BACK_SLOT) {
            player.closeInventory();
            system.openFriends(player);
            return;
        }
        if (slot == MESSAGE_SLOT) {
            system.promptMessage(player, target);
            return;
        }
        if (slot == GIFT_SLOT) {
            system.promptGift(player, target);
            return;
        }
        if (slot == MUTUAL_ACTION_SLOT || slot == MUTUAL_SLOT) {
            player.closeInventory();
            system.openMutual(player, target);
            return;
        }
        if (slot == FAVORITE_SLOT) {
            Boolean now = system.friends().toggleFavorite(player.getUniqueId(), target);
            if (now == null) {
                player.sendMessage(Component.text("\uD83D\uDC65 They are not on your friends list yet.")
                        .color(NamedTextColor.RED));
                return;
            }
            player.sendMessage(Component.text("\uD83D\uDC65 ").color(NamedTextColor.AQUA)
                    .append(Component.text(system.friends().nameOf(target)).color(NamedTextColor.YELLOW))
                    .append(Component.text(now
                                    ? " is now pinned to the top of your friends."
                                    : " is no longer a favorite.")
                            .color(NamedTextColor.GRAY)));
            armedRemove = false;
            render();
            return;
        }
        if (slot == REMOVE_SLOT) {
            if (!system.friends().areFriends(player.getUniqueId(), target)) {
                player.sendMessage(Component.text("\uD83D\uDC65 They are not on your friends list.")
                        .color(NamedTextColor.RED));
                return;
            }
            if (!armedRemove) {
                armedRemove = true;
                render();
                return;
            }
            armedRemove = false;
            player.closeInventory();
            system.remove(player, target);
            return;
        }
        // Any other click cancels a pending removal.
        if (armedRemove) {
            armedRemove = false;
            render();
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83D\uDC64 Profile", "\uD83C\uDF10 Social");
        inventory.setItem(HEAD_SLOT, headItem());

        boolean isFriend = system.friends().areFriends(viewer.getUniqueId(), target);
        boolean detailsVisible = detailsVisible(isFriend);

        inventory.setItem(BALANCE_SLOT, detailsVisible
                ? balanceItem() : hiddenItem("Balance", "🪙"));
        inventory.setItem(PLAYTIME_SLOT, detailsVisible
                ? playtimeItem() : hiddenItem("Playtime", "⏱"));
        inventory.setItem(STATS_SLOT, detailsVisible
                ? statsItem() : hiddenItem("Stats", "☠"));
        inventory.setItem(PLANETS_SLOT, detailsVisible
                ? planetsItem() : hiddenItem("Planets", "🪐"));

        inventory.setItem(STATUS_SLOT, statusItem());
        inventory.setItem(WHERE_SLOT, whereItem());
        inventory.setItem(LAST_SEEN_SLOT, lastSeenItem());
        inventory.setItem(MUTUAL_SLOT, mutualItem());

        inventory.setItem(BACK_SLOT, button(Material.ARROW, "\u21A9 Back to Friends",
                NamedTextColor.AQUA, "Returns to your friends list"));
        inventory.setItem(MESSAGE_SLOT, button(isFriend ? Material.PAPER : Material.GRAY_DYE,
                "\uD83D\uDCAC Message",
                isFriend ? NamedTextColor.AQUA : NamedTextColor.GRAY,
                isFriend ? "Type a private message in chat"
                        : "You can only message friends"));
        inventory.setItem(FAVORITE_SLOT, favoriteItem(isFriend));
        inventory.setItem(GIFT_SLOT, button(isFriend && plugin.hasEconomy() ? Material.CHEST : Material.GRAY_DYE,
                "\uD83C\uDF81 Gift",
                isFriend && plugin.hasEconomy() ? NamedTextColor.GOLD : NamedTextColor.GRAY,
                isFriend
                        ? (plugin.hasEconomy() ? "Send them some of your VPL" : "The economy is unavailable")
                        : "You can only gift friends"));
        inventory.setItem(MUTUAL_ACTION_SLOT, button(Material.COMPARATOR,
                "\uD83D\uDD17 Mutual Friends",
                NamedTextColor.AQUA,
                "See everyone you both know"));
        inventory.setItem(REMOVE_SLOT, removeItem(isFriend));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "\u2716 Close",
                NamedTextColor.GRAY, "Back to the game"));
    }

    /** Whether the viewer may see the friend's personal numbers. */
    private boolean detailsVisible(boolean isFriend) {
        if (isFriend || target.equals(viewer.getUniqueId())) {
            return true;
        }
        PlayerSettings settings = plugin.getPlayerSettings();
        boolean privateProfile = settings != null
                && settings.get(target, PlayerSettings.Setting.FRIEND_PRIVACY);
        return !privateProfile;
    }

    private ItemStack headItem() {
        String name = system.friends().nameOf(target);
        FriendPresenceService.Status status =
                system.presence().statusOf(target, target.equals(viewer.getUniqueId()));
        boolean isFriend = system.friends().areFriends(viewer.getUniqueId(), target);
        boolean favorite = isFriend && system.friends().isFavorite(viewer.getUniqueId(), target);

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(target));
        meta.displayName(Component.text((favorite ? "\u2605 " : "")
                        + status.dot() + " " + name)
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(status.label(), status == FriendPresenceService.Status.ONLINE
                ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        lore.add(line(isFriend ? "On your friends list" : "Not a friend yet",
                isFriend ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        lore.add(line(""));
        lore.add(line("Mutual friends: " + system.friends().mutualCount(viewer.getUniqueId(), target),
                NamedTextColor.AQUA));
        lore.add(line("Gifts received: " + system.gifts().receivedCount(target)
                + " \u00B7 sent: " + system.gifts().sentCount(target)));
        if (!isFriend) {
            lore.add(line(""));
            lore.add(Component.text("Add them with /friend " + name)
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack balanceItem() {
        PlayerData data = plugin.playerDataOf(target);
        double balance = data == null ? 0 : data.balance();
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDCB0 Balance").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(Planets.formatPrice(balance) + " VPL", NamedTextColor.YELLOW));
        if (plugin.hasEconomy()) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
            try {
                lore.add(line("Live: " + Planets.formatPrice(plugin.getBalance(offline)) + " VPL",
                        NamedTextColor.DARK_GRAY));
            } catch (RuntimeException ignored) {
                // The economy would not answer for an offline player; the
                // stored snapshot above is still shown.
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack playtimeItem() {
        PlayerData data = plugin.playerDataOf(target);
        double hours = data == null ? 0 : data.playtimeHours();
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u23F1 Playtime").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(Planets.formatPlaytime(hours), NamedTextColor.YELLOW));
        if (data != null && data.firstJoinedMs() > 0) {
            lore.add(line("Joined " + Planets.formatDate(data.firstJoinedMs()), NamedTextColor.DARK_GRAY));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack statsItem() {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
        int deaths = statistic(offline, Statistic.DEATHS);
        int kills = statistic(offline, Statistic.PLAYER_KILLS);
        int mobKills = statistic(offline, Statistic.MOB_KILLS);
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\u2620 Stats").color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Deaths: " + deaths, NamedTextColor.GRAY));
        lore.add(line("Player kills: " + kills, NamedTextColor.GRAY));
        lore.add(line("Mob kills: " + mobKills, NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack planetsItem() {
        PlayerData data = plugin.playerDataOf(target);
        int planets = data == null ? 0 : data.planetsOwned();
        int homes = data == null ? 0 : data.homesCount();
        ItemStack item = new ItemStack(Material.GRASS_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83E\uDE90 Planets").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Planets owned: " + planets, NamedTextColor.GRAY));
        lore.add(line("Homes saved: " + homes, NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack statusItem() {
        FriendPresenceService.Status status =
                system.presence().statusOf(target, target.equals(viewer.getUniqueId()));
        ItemStack item = new ItemStack(status == FriendPresenceService.Status.ONLINE
                ? Material.LIME_DYE
                : status == FriendPresenceService.Status.AFK ? Material.YELLOW_DYE
                : status == FriendPresenceService.Status.HIDDEN ? Material.BLACK_DYE
                : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(status.dot() + " Status: " + status.label())
                .color(status == FriendPresenceService.Status.ONLINE
                        ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        switch (status) {
            case ONLINE -> lore.add(line("They are on the server right now", NamedTextColor.GREEN));
            case AFK -> {
                lore.add(line("They are online but haven't moved", NamedTextColor.YELLOW));
                lore.add(line("AFK after " + system.presence().afkSeconds() + "s idle",
                        NamedTextColor.DARK_GRAY));
            }
            case OFFLINE -> lore.add(line("Not on the server right now"));
            case HIDDEN -> lore.add(line("They keep their status private", NamedTextColor.DARK_GRAY));
        }
        int ping = system.presence().ping(target);
        if (ping >= 0) {
            lore.add(line("Ping: " + ping + "ms", NamedTextColor.DARK_GRAY));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack whereItem() {
        String planet = system.presence().locationLabel(target);
        String world = system.presence().worldName(target);
        ItemStack item = new ItemStack(planet == null ? Material.GRAY_DYE : Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83C\uDF10 Current server").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (planet == null) {
            lore.add(line("They are offline"));
        } else {
            lore.add(line("Server: " + planet, NamedTextColor.YELLOW));
            if (world != null && !world.equals(planet)) {
                lore.add(line("World: " + world, NamedTextColor.GRAY));
            }
            boolean together = system.presence().sameWorld(viewer.getUniqueId(), target);
            lore.add(line(together ? "They are with you right now" : "They are elsewhere",
                    together ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack lastSeenItem() {
        long seen = system.presence().lastSeen(target);
        boolean online = system.presence().isOnline(target);
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDD53 Last Seen").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(online
                ? "Online now"
                : seen > 0 ? Planets.formatDate(seen) : "Unknown", NamedTextColor.YELLOW));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack mutualItem() {
        List<UUID> mutual = new ArrayList<>(system.friends().mutualFriends(viewer.getUniqueId(), target));
        mutual.sort((left, right) -> system.friends().nameOf(left)
                .compareToIgnoreCase(system.friends().nameOf(right)));
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83D\uDD17 Mutual Friends (" + mutual.size() + ")")
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (mutual.isEmpty()) {
            lore.add(line("You don't share any friends yet"));
        } else {
            for (UUID id : mutual) {
                lore.add(line("\u2022 " + system.friends().nameOf(id), NamedTextColor.GREEN));
            }
        }
        lore.add(line(""));
        lore.add(Component.text("Click to open the mutual friends list")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack favoriteItem(boolean isFriend) {
        boolean favorite = isFriend && system.friends().isFavorite(viewer.getUniqueId(), target);
        ItemStack item = new ItemStack(favorite ? Material.NETHER_STAR : Material.GLASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((favorite ? "\u2605 Unfavorite" : "\u2606 Favorite"))
                .color(favorite ? NamedTextColor.GOLD : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (!isFriend) {
            lore.add(line("You can only favorite friends"));
        } else {
            lore.add(line(favorite
                    ? "Pinned to the top of your friends list"
                    : "Pin them to the top of your friends list"));
            lore.add(Component.text("Click to " + (favorite ? "unpin" : "pin"))
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack removeItem(boolean isFriend) {
        ItemStack item = new ItemStack(armedRemove ? Material.REDSTONE_BLOCK : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(armedRemove
                        ? "\uD83D\uDC94 Click again to confirm"
                        : "\uD83D\uDC94 Remove Friend")
                .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (!isFriend) {
            lore.add(line("They are not on your friends list"));
        } else {
            lore.add(line("Ends the friendship for both of you"));
            lore.add(Component.text(armedRemove ? "Click once more to do it" : "Click, then confirm")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack hiddenItem(String label, String dot) {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(dot + " " + label).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Kept private by this player", NamedTextColor.DARK_GRAY));
        lore.add(line("Only their friends can see it", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String name, NamedTextColor color, String loreLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(loreLine, color == NamedTextColor.GRAY ? NamedTextColor.DARK_GRAY : NamedTextColor.GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static int statistic(OfflinePlayer player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (RuntimeException | LinkageError unsupported) {
            return 0;
        }
    }

    private static Component line(String text) {
        return line(text, NamedTextColor.GRAY);
    }

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }
}
