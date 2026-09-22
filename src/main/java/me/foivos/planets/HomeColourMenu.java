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
 * The free bed-colour picker behind {@code /home → 🎨 Bed Colour} (all homes)
 * and {@code /home → shift-right-click a home} (that one home).
 *
 * <pre>
 *   🟦🟦🟦🟦🎨🟦🟦🟦🟦        🎨 Bed Colour — click one, it is free
 *   🟦 🛏 🛏 🛏 🛏 🛏 🛏 🛏 🛏 🟦     eight colours
 *   🟦 🛏 🛏 🛏 🟏 🛏 🛏 🛏 🛏 🟦     eight more
 *   🟦⬅ 🟦…                     ⬅ back to your homes
 * </pre>
 *
 * <p>With no target the choice becomes the player's default <em>and</em> repaints
 * every home they already have; with a target only that home changes. Either
 * way it is free — no cost, cooldown, rank or permission — and it is remembered
 * in {@code homes.yml}.
 */
public final class HomeColourMenu implements InventoryHolder {

    private static final int SIZE = 45;
    private static final int INFO_SLOT = 4;
    private static final int BACK_SLOT = 40;
    /** Two rows of eight, skipping the frame's side columns. */
    private static final int[] COLOUR_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25, 26
    };

    private final Planets plugin;
    private final Player viewer;
    private final HomeManager homes;
    /** The one home being coloured, or null to colour every home. */
    private final HomeManager.Home target;
    private final Inventory inventory;

    HomeColourMenu(Planets plugin, Player viewer, HomeManager homes, HomeManager.Home target) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.homes = homes;
        this.target = target;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text(target == null ? "\uD83C\uDFA8 Bed Colour" : "\uD83C\uDFA8 " + target.name())
                        .color(NamedTextColor.DARK_AQUA));
        render();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    void open(Player player) { player.openInventory(inventory); }

    void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (slot == BACK_SLOT) {
            plugin.openHomesMenu(player);
            return;
        }

        int index = -1;
        for (int i = 0; i < COLOUR_SLOTS.length; i++) {
            if (COLOUR_SLOTS[i] == slot) {
                index = i;
                break;
            }
        }
        List<Material> colours = HomeManager.BED_COLOURS;
        if (index < 0 || index >= colours.size()) {
            return;
        }
        Material colour = colours.get(index);
        if (target == null) {
            homes.setBedColourForAll(player.getUniqueId(), colour);
        } else {
            // Picking the player's own default clears the per-home override, so
            // homes.yml only records colours that actually differ.
            Material stored = colour == homes.bedColour(player.getUniqueId()) ? null : colour;
            if (!homes.setHomeColour(player.getUniqueId(), target.name(), stored)) {
                player.sendMessage(Component.text("\uD83C\uDFA8 That home no longer exists.")
                        .color(NamedTextColor.RED));
                return;
            }
        }
        player.playSound(player.getLocation(), Sound.ITEM_DYE_USE, 1.0f, 1.2f);
        plugin.openHomesMenu(player);
    }

    private void render() {
        inventory.clear();
        MenuStyle.decorate(inventory, "\uD83C\uDFA8 Bed", "\uD83D\uDECF Free");
        inventory.setItem(INFO_SLOT, infoItem());

        Material current = target == null
                ? homes.bedColour(viewer.getUniqueId())
                : homes.bedFor(viewer.getUniqueId(), target);
        List<Material> colours = HomeManager.BED_COLOURS;
        for (int i = 0; i < COLOUR_SLOTS.length && i < colours.size(); i++) {
            inventory.setItem(COLOUR_SLOTS[i], colourBed(colours.get(i), colours.get(i) == current));
        }

        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta meta = back.getItemMeta();
        meta.displayName(Component.text("\u2B05 Back to Your Homes").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Nothing is applied until you click a bed"));
        meta.lore(lore);
        back.setItemMeta(meta);
        inventory.setItem(BACK_SLOT, back);
    }

    private ItemStack infoItem() {
        Material current = target == null
                ? homes.bedColour(viewer.getUniqueId())
                : homes.bedFor(viewer.getUniqueId(), target);
        ItemStack item = new ItemStack(current);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(target == null
                        ? "\uD83C\uDFA8 Your Bed Colour"
                        : "\uD83C\uDFA8 " + target.name())
                .color(NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("Currently: " + HomeManager.colourName(current), NamedTextColor.YELLOW));
        if (target == null) {
            lore.add(line("Sets your default and repaints"));
            lore.add(line("every home you have"));
        } else {
            lore.add(line("Only this home changes — your"));
            lore.add(line("other homes keep their colours"));
            lore.add(line("Picking your default colour resets it"));
        }
        lore.add(line(""));
        lore.add(line("Free for everyone", NamedTextColor.GREEN));
        lore.add(line("Click a bed below to use its colour"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack colourBed(Material material, boolean selected) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text((selected ? "\u2705 " : "\uD83D\uDECF ") + HomeManager.colourName(material))
                .color(selected ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        if (selected) {
            meta.setEnchantmentGlintOverride(true);
        }
        List<Component> lore = new ArrayList<>();
        if (selected) {
            lore.add(line("This is the colour in use right now", NamedTextColor.GREEN));
        } else {
            lore.add(Component.text("Click to use this colour").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(line("Free", NamedTextColor.DARK_GRAY));
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
