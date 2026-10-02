package me.foivos.planets;

import me.foivos.planets.api.SettingToggle;
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
 * One page of {@code /settings} contributed by another plugin (see
 * {@code me.foivos.planets.api.PlanetariumSettingsService}). It is drawn exactly
 * like the plugin's own settings pages — same frame, same ✅/❌ toggle items,
 * same bottom row — so a player cannot tell which plugin a switch belongs to.
 *
 * <pre>
 *   🟦🟦🟦🟦🧾🟦🟦🟦🟦           🧾 Bounties — what the board tells you
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦           up to 28 switches, in registration order
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦 ▣ ▣ ▣ ▣ ▣ ▣ ▣ 🟦
 *   🟦🟦🟦🟦↩🟦🟦✖🟦🟦           ↩ back to /settings, ✖ close
 * </pre>
 *
 * <p>Every click is handled here and saved through the page it came from, so a
 * choice is written to {@code player-settings.yml} the moment it is made.
 */
final class ExternalSettingsMenu implements InventoryHolder {

    private static final int SIZE = 54;
    private static final int INFO_SLOT = 4;
    private static final int BACK_SLOT = 49;
    private static final int CLOSE_SLOT = 51;
    /** Where the switches sit, in order: four rows of seven. */
    private static final int[] TOGGLE_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final Planets plugin;
    private final Player viewer;
    private final ExternalSettings.Page page;
    private final Inventory inventory;

    ExternalSettingsMenu(Planets plugin, Player viewer, ExternalSettings.Page page) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.page = page;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text(page.spec().displayName()).color(NamedTextColor.DARK_AQUA));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void open(Player player) {
        player.openInventory(inventory);
    }

    void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == BACK_SLOT) {
            PlayerSettings settings = plugin.getPlayerSettings();
            player.closeInventory();
            if (settings != null) {
                new PlayerSettingsMenu(plugin, player, settings).open(player);
            }
            return;
        }
        int index = -1;
        for (int i = 0; i < TOGGLE_SLOTS.length; i++) {
            if (TOGGLE_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        List<SettingToggle> toggles = page.toggles();
        if (index < 0 || index >= toggles.size()) {
            return;
        }
        SettingToggle toggle = toggles.get(index);
        boolean now = !page.value(player.getUniqueId(), toggle.key());
        page.setValue(player.getUniqueId(), toggle.key(), now);
        player.playSound(player.getLocation(),
                now ? Sound.BLOCK_NOTE_BLOCK_BIT : Sound.BLOCK_NOTE_BLOCK_BASS,
                0.7f, now ? 1.6f : 0.8f);
        render();
    }

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83E\uDDFE " + page.spec().displayName(),
                page.spec().pluginName());
        inventory.setItem(INFO_SLOT, headerItem());

        List<SettingToggle> toggles = page.toggles();
        for (int i = 0; i < toggles.size() && i < TOGGLE_SLOTS.length; i++) {
            inventory.setItem(TOGGLE_SLOTS[i], toggleItem(toggles.get(i)));
        }
        if (toggles.size() > TOGGLE_SLOTS.length) {
            plugin.getLogger().warning("The /settings page for " + page.spec().pluginName()
                    + " has " + toggles.size() + " switches but the page fits "
                    + TOGGLE_SLOTS.length + ".");
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(Component.text("\u21A9 Back to Settings").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        backMeta.lore(List.of(Component.text("Every page of your settings")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        back.setItemMeta(backMeta);
        inventory.setItem(BACK_SLOT, back);

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        closeMeta.displayName(Component.text("\u2716 Close").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        close.setItemMeta(closeMeta);
        inventory.setItem(CLOSE_SLOT, close);
    }

    private ItemStack headerItem() {
        ItemStack item = page.spec().icon().clone();
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83E\uDDFE " + page.spec().displayName())
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (!page.spec().description().isBlank()) {
            lore.add(line(page.spec().description()));
        }
        lore.add(line("Added by the " + page.spec().pluginName() + " plugin",
                NamedTextColor.DARK_GRAY));
        lore.add(line(""));
        int changed = 0;
        for (SettingToggle toggle : page.toggles()) {
            if (page.value(viewer.getUniqueId(), toggle.key()) != toggle.defaultOn()) {
                changed++;
            }
        }
        lore.add(line(page.toggles().size() + " setting(s) here", NamedTextColor.GRAY));
        lore.add(line(changed == 0
                        ? "All at their defaults"
                        : changed + " changed from the default",
                changed == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        lore.add(line(""));
        lore.add(Component.text("Click a toggle to switch it").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** One switch, drawn the same way the plugin's own settings draw theirs. */
    private ItemStack toggleItem(SettingToggle toggle) {
        boolean on = page.value(viewer.getUniqueId(), toggle.key());
        ItemStack item = toggle.icon().clone();
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((on ? "\u2705 " : "\u274C ") + toggle.displayName())
                .color(on ? NamedTextColor.AQUA : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Uses: " + toggle.description()));
        lore.add(line(""));
        lore.add(Component.text("Currently: ").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(on ? "ON" : "OFF")
                        .color(on ? NamedTextColor.GREEN : NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)));
        lore.add(line(on ? toggle.onText() : toggle.offText()));
        lore.add(line(""));
        lore.add(Component.text("Click to turn " + (on ? "OFF" : "ON"))
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
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
