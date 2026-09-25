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
 * The /settings screen: rows of toggles that apply to the player
 * everywhere on the server and stay as they were set until switched back,
 * with the reset and close buttons along the bottom row.
 *
 * <pre>
 *   🟦🟦🟦🟦📘🟦🟦🟦🟦           📘 Your settings (N)
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦           row 1: chat and other players
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦           row 2: money and planets
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦           row 3: comfort
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦           row 4: room for the next settings
 *   🟦 ▣ ♻ ▣ ▣ ▣ ✖ ▣ 🟦           ♻ reset, ✖ close
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 * </pre>
 *
 * <p>Clicking a toggle never prints anything: the item redraws with its new
 * state, and its description explains what the setting is for.
 */
public final class PlayerSettingsMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int RESET_SLOT = 47;
    /** Opens the Friends category page (the social toggles). */
    private static final int FRIENDS_SLOT = 49;
    private static final int CLOSE_SLOT = 51;
    /**
     * Where the toggles sit, in order: four rows of seven. The current settings
     * fill three of them, so the last row is free for the next ones.
     */
    private static final int[] TOGGLE_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    /** The toggles drawn on this page: everything outside the Friends category. */
    private static final PlayerSettings.Setting[] GENERAL_SETTINGS =
            PlayerSettings.Setting.of(PlayerSettings.Category.GENERAL);

    private final Planets plugin;
    private final Player viewer;
    private final PlayerSettings settings;
    private final Inventory inventory;

    /** Set after the first click on reset, so the reset needs confirming. */
    private boolean pendingReset;

    public PlayerSettingsMenu(Planets plugin, Player viewer, PlayerSettings settings) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.settings = settings;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("⚙ Your Settings").color(NamedTextColor.DARK_AQUA));
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
            pendingReset = false;
            player.closeInventory();
            return;
        }
        if (slot == FRIENDS_SLOT) {
            pendingReset = false;
            player.closeInventory();
            new FriendSettingsMenu(plugin, player, settings).open(player);
            return;
        }
        if (slot == RESET_SLOT) {
            if (!pendingReset) {
                pendingReset = true;
                render();
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.0f);
                return;
            }
            pendingReset = false;
            settings.reset(player.getUniqueId());
            player.sendMessage(Component.text("⚙ All settings restored to their defaults.")
                    .color(NamedTextColor.AQUA));
            // Settings that live outside config need to catch up with the
            // defaults right away: night vision is a potion effect, the sky
            // tint is a re-sent registry and the soundtrack is a running sound.
            plugin.applyNightVision(player);
            plugin.refreshSkyTint(player);
            plugin.planetMusic().stop(player);
            plugin.planetMusic().startNow(player);
            plugin.sidebarScoreboard().refresh(player);
            render();
            return;
        }

        int index = -1;
        for (int i = 0; i < TOGGLE_SLOTS.length; i++) {
            if (TOGGLE_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        PlayerSettings.Setting[] all = GENERAL_SETTINGS;
        if (index < 0 || index >= all.length) {
            return;
        }
        pendingReset = false; // clicking anywhere else cancels a pending reset
        PlayerSettings.Setting setting = all[index];
        // The HUD item is the one setting with several lines to pick from:
        // right-click cycles them, left-click still switches the HUD on and off.
        if (setting == PlayerSettings.Setting.PLANET_HUD && event.isRightClick()) {
            int modeCount = plugin.hudModes().size();
            if (modeCount > 1) {
                settings.cycleHudMode(player.getUniqueId(), modeCount);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.4f);
            }
            render();
            return;
        }
        // The sidebar item opens its line picker on a right-click, the same way
        // the Planet HUD item cycles its lines.
        if (setting == PlayerSettings.Setting.SIDEBAR && event.isRightClick()) {
            player.closeInventory();
            new SidebarEditorMenu(plugin, player).open(player);
            return;
        }
        // The menu redraws below with the new state and the same description, so
        // no chat line is needed — the item itself is the feedback.
        boolean now = settings.toggle(player.getUniqueId(), setting);
        player.playSound(player.getLocation(), now ? Sound.BLOCK_NOTE_BLOCK_BIT : Sound.BLOCK_NOTE_BLOCK_BASS,
                0.7f, now ? 1.6f : 0.8f);
        // A sky change only shows once the client is handed the world's
        // dimension registry again, so re-send it right away.
        if (setting == PlayerSettings.Setting.COLORED_SKY) {
            plugin.refreshSkyTint(player);
        }
        // Night vision is an actual potion effect, so it is applied or removed
        // the moment the toggle is clicked.
        if (setting == PlayerSettings.Setting.NIGHT_VISION) {
            plugin.applyNightVision(player);
        }
        // Music is played by the soundtrack, so it has to stop (or start) right
        // away rather than at the next pass of its scheduler.
        if (setting == PlayerSettings.Setting.MUSIC) {
            if (now) {
                plugin.planetMusic().startNow(player);
            } else {
                plugin.planetMusic().stop(player);
            }
        }
        render();
    }

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "⚙ Preferences", "🌍 Global");

        inventory.setItem(INFO_SLOT, infoItem());

        PlayerSettings.Setting[] all = GENERAL_SETTINGS;
        for (int i = 0; i < all.length && i < TOGGLE_SLOTS.length; i++) {
            inventory.setItem(TOGGLE_SLOTS[i], toggleItem(plugin, viewer, settings, all[i]));
        }
        if (all.length > TOGGLE_SLOTS.length) {
            plugin.getLogger().warning("The /settings menu has room for " + TOGGLE_SLOTS.length
                    + " toggles but " + all.length + " exist.");
        }

        inventory.setItem(FRIENDS_SLOT, friendsCategoryItem());
        inventory.setItem(RESET_SLOT, pendingReset ? resetConfirmItem() : resetItem());

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta meta = close.getItemMeta();
        meta.displayName(Component.text("✖ Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        close.setItemMeta(meta);
        inventory.setItem(CLOSE_SLOT, close);
    }

    private ItemStack infoItem() {
        int changed = settings.customCount(viewer.getUniqueId());
        ItemStack item = new ItemStack(Material.KNOWLEDGE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("⚙ Your Settings").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("These apply in every world and stay"));
        lore.add(line("set until you switch them back."));
        lore.add(line(""));
        lore.add(line(PlayerSettings.Setting.values().length + " settings available"));
        lore.add(line("Grouped into Global and 👥 Friends"));
        lore.add(line(changed == 0
                        ? "All settings are at their defaults"
                        : changed + " setting(s) changed from the default",
                changed == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        lore.add(Component.text("Click a toggle to switch it").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** The button that opens the Friends category of the settings. */
    private ItemStack friendsCategoryItem() {
        int total = PlayerSettings.Setting.of(PlayerSettings.Category.FRIENDS).length;
        int changed = 0;
        for (PlayerSettings.Setting setting : PlayerSettings.Setting.of(PlayerSettings.Category.FRIENDS)) {
            if (settings.get(viewer.getUniqueId(), setting) != setting.defaultOn()) {
                changed++;
            }
        }
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(viewer);
        meta.displayName(Component.text("\uD83D\uDC65 Friends").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Requests, join/quit notes, gifts and privacy"));
        lore.add(line(total + " social settings"));
        lore.add(line(changed == 0
                        ? "All at their defaults"
                        : changed + " changed from the default",
                changed == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        lore.add(line("Also reachable with /friends \u2192 Settings"));
        lore.add(line(""));
        lore.add(Component.text("Click to open the Friends settings")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * One setting drawn as a toggle. Shared with {@link FriendSettingsMenu}, so
     * both pages of {@code /settings} look and behave the same way.
     */
    static ItemStack toggleItem(Planets plugin, Player viewer, PlayerSettings settings,
                                PlayerSettings.Setting setting) {
        boolean on = settings.get(viewer.getUniqueId(), setting);
        ItemStack item = setting.icon();
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((on ? "✅ " : "❌ ") + setting.displayName())
                .color(on ? NamedTextColor.AQUA : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        // The setting's use lives here in the description instead of being sent
        // as a chat message every time the item is clicked.
        List<Component> lore = new ArrayList<>();
        lore.add(line("Uses: " + setting.description()));
        if (setting == PlayerSettings.Setting.PLANET_HUD) {
            Planets.HudMode mode = plugin.hudModeFor(viewer.getUniqueId());
            lore.add(line("Shows: " + mode.label(), NamedTextColor.YELLOW));
            lore.add(line("Preview: " + mode.template(), NamedTextColor.DARK_GRAY));
            if (plugin.hudModes().size() > 1) {
                lore.add(Component.text("Right-click to change what it shows")
                        .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            }
            lore.add(line("Only appears on planets and in lobbies", NamedTextColor.DARK_GRAY));
        }
        if (setting == PlayerSettings.Setting.MUSIC && !plugin.planetMusic().enabled()) {
            // The server switched the whole soundtrack off in config.yml.
            lore.add(line("Music is switched off on this server", NamedTextColor.YELLOW));
        }
        if (setting == PlayerSettings.Setting.SIDEBAR) {
            SidebarScoreboard bar = plugin.sidebarScoreboard();
            lore.add(line("Showing " + bar.visibleLines(viewer).size() + " of "
                    + bar.lines().size() + " lines", NamedTextColor.YELLOW));
            lore.add(Component.text("Right-click to pick the lines")
                    .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            if (!bar.enabled()) {
                lore.add(line("Switched off on this server", NamedTextColor.DARK_RED));
            }
        }
        lore.add(line(""));
        lore.add(Component.text("Currently: ").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(on ? "ON" : "OFF")
                        .color(on ? NamedTextColor.GREEN : NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)));
        lore.add(line(on ? setting.enabledText() : setting.disabledText()));
        lore.add(line(""));
        lore.add(Component.text("Click to turn " + (on ? "OFF" : "ON"))
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack resetItem() {
        boolean custom = settings.anyCustom(viewer.getUniqueId());
        ItemStack item = new ItemStack(custom ? Material.HOPPER : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("♻ Reset to Defaults").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line(custom
                ? "Turn every setting back to its default"
                : "Nothing to reset — you're on the defaults"));
        lore.add(Component.text("Click to reset (asks first)").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack resetConfirmItem() {
        ItemStack item = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("♻ Click again to confirm").color(NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("All your settings go back to their defaults."));
        lore.add(line("Click any other item to cancel."));
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
}
