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
 * The <b>Friends</b> page of {@code /settings}: the social switches, reached
 * from the Friends button on the main settings screen (or from
 * {@code /friends} → Settings). It is a real page of the same menu, not a
 * separate {@code /friends settings} command, so the plugin keeps exactly one
 * place where a player changes a preference.
 *
 * <p>The switches here are ordinary {@link PlayerSettings.Setting}s whose
 * category is {@link PlayerSettings.Category#FRIENDS}, which means they are
 * persisted, reset, and read back through the exact same
 * {@code player-settings.yml} code the rest of the menu already used.
 *
 * <pre>
 *   🟦🟦🟦🟦👥🟦🟦🟦🟦           👥 Friends settings (N)
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦           row 1: requests, notifications
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦           row 2: messages, gifts, activity
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦           row 3: privacy, online status
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦 ▣ ↩ ▣ ▣ ▣ ✖ ▣ 🟦           ↩ back to settings, ✖ close
 *   🟦🟦🟦🟦🟦🟦🟦🟦🟦
 * </pre>
 */
public final class FriendSettingsMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int BACK_SLOT = 47;
    private static final int CLOSE_SLOT = 51;

    /** Where the toggles sit, in order: four rows of seven. */
    private static final int[] TOGGLE_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    /** The social toggles, in declaration order. */
    private static final PlayerSettings.Setting[] FRIEND_SETTINGS =
            PlayerSettings.Setting.of(PlayerSettings.Category.FRIENDS);

    private final Planets plugin;
    private final Player viewer;
    private final PlayerSettings settings;
    private final Inventory inventory;

    public FriendSettingsMenu(Planets plugin, Player viewer, PlayerSettings settings) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.settings = settings;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83D\uDC65 Friends Settings").color(NamedTextColor.DARK_AQUA));
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
            new PlayerSettingsMenu(plugin, player, settings).open(player);
            return;
        }

        int index = -1;
        for (int i = 0; i < TOGGLE_SLOTS.length; i++) {
            if (TOGGLE_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        if (index < 0 || index >= FRIEND_SETTINGS.length) {
            return;
        }
        // The item itself redraws with its new state, so no chat line is needed.
        boolean now = settings.toggle(player.getUniqueId(), FRIEND_SETTINGS[index]);
        player.playSound(player.getLocation(),
                now ? Sound.BLOCK_NOTE_BLOCK_BIT : Sound.BLOCK_NOTE_BLOCK_BASS,
                0.7f, now ? 1.6f : 0.8f);
        render();
    }

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83D\uDC65 Friends", "\uD83C\uDF10 Social");
        inventory.setItem(INFO_SLOT, infoItem());

        for (int i = 0; i < FRIEND_SETTINGS.length && i < TOGGLE_SLOTS.length; i++) {
            inventory.setItem(TOGGLE_SLOTS[i],
                    PlayerSettingsMenu.toggleItem(plugin, viewer, settings, FRIEND_SETTINGS[i]));
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(Component.text("\u21A9 Back to Settings").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> backLore = new ArrayList<>();
        backLore.add(line("Returns to the main /settings page"));
        backMeta.lore(backLore);
        back.setItemMeta(backMeta);
        inventory.setItem(BACK_SLOT, back);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta meta = close.getItemMeta();
        meta.displayName(Component.text("\u2716 Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        close.setItemMeta(meta);
        inventory.setItem(CLOSE_SLOT, close);
    }

    private ItemStack infoItem() {
        int changed = 0;
        for (PlayerSettings.Setting setting : FRIEND_SETTINGS) {
            if (settings.get(viewer.getUniqueId(), setting) != setting.defaultOn()) {
                changed++;
            }
        }
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta =
                (org.bukkit.inventory.meta.SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(viewer);
        meta.displayName(Component.text("\uD83D\uDC65 Friends Settings").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("These control your friends and the"));
        lore.add(line("notifications you get from them."));
        lore.add(line(""));
        lore.add(line(FRIEND_SETTINGS.length + " social settings available"));
        lore.add(line(changed == 0
                        ? "All are at their defaults"
                        : changed + " changed from the default",
                changed == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        lore.add(Component.text("Click a switch to flip it").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
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
