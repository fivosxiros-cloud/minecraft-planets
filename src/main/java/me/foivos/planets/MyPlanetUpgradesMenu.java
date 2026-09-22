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

import java.util.List;

/**
 * Planet upgrade menu for /myp → Upgrades.
 * Shows each upgrade category, its current level and a click to upgrade.
 */
public final class MyPlanetUpgradesMenu implements InventoryHolder {

    private static final int SIZE = 36;
    private final Planets plugin;
    private final Player viewer;
    private final MyPlanetData data;
    private final Inventory inventory;

    public MyPlanetUpgradesMenu(Planets plugin, Player viewer, MyPlanetData data) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.data = data;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("⬆ " + data.displayName() + " Upgrades").color(NamedTextColor.DARK_PURPLE));
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

        if (slot == SIZE - 1) {
            player.closeInventory();
            new MyPlanetMenu(plugin, player, data).open(player);
            return;
        }

        MyPlanetData.Upgrade[] upgrades = MyPlanetData.Upgrade.values();
        if (slot >= 0 && slot < upgrades.length) {
            MyPlanetData.Upgrade upgrade = upgrades[slot];
            int currentLevel = data.upgradeLevel(upgrade);
            if (currentLevel >= data.maxUpgradeLevel(upgrade)) {
                player.sendMessage(Component.text(upgrade.displayName() + " is already at max level!").color(NamedTextColor.RED));
                return;
            }
            // Each upgrade has its own cost ladder: Planet Size and Block
            // Limitations use fixed tables, the rest cost 100 VPL per level.
            double upgradeCost = MyPlanetData.upgradeCost(upgrade, currentLevel);
            if (upgradeCost < 0) {
                player.sendMessage(Component.text(upgrade.displayName() + " is already at max level!").color(NamedTextColor.RED));
                return;
            }
            if (!plugin.hasEconomy()) {
                player.sendMessage(Component.text("Upgrades require an economy plugin (Vault).").color(NamedTextColor.RED));
                return;
            }
            double balance = plugin.getBalance(player);
            if (balance < 0) {
                player.sendMessage(Component.text("Could not check your balance.").color(NamedTextColor.RED));
                return;
            }
            if (balance < upgradeCost) {
                player.sendMessage(Component.text("Not enough money! Need ").color(NamedTextColor.RED)
                        .append(Component.text(Planets.formatPrice(upgradeCost)).color(NamedTextColor.YELLOW))
                        .append(Component.text(" VPL for " + upgrade.displayName() + ".").color(NamedTextColor.RED)));
                return;
            }
            if (upgradeCost > 0 && !plugin.withdraw(player, upgradeCost)) {
                player.sendMessage(Component.text("Payment failed!").color(NamedTextColor.RED));
                return;
            }
            data.setUpgradeLevel(upgrade, currentLevel + 1);
            plugin.getMyPlanetManager().save();
            if (upgrade == MyPlanetData.Upgrade.PLANET_SIZE) {
                plugin.updateWorldBorder(data);
            }
            player.sendMessage(Component.text(upgrade.displayName() + " upgraded to level " + (currentLevel + 1) + "!").color(NamedTextColor.GREEN));
            new MyPlanetUpgradesMenu(plugin, player, data).open(player);
        }
    }

    private void render() {
        inventory.clear();
        MyPlanetData.Upgrade[] upgrades = MyPlanetData.Upgrade.values();
        for (int i = 0; i < upgrades.length && i < SIZE - 1; i++) {
            inventory.setItem(i, upgradeItem(upgrades[i]));
        }
        for (int i = upgrades.length; i < SIZE - 1; i++) {
            inventory.setItem(i, filler());
        }
        inventory.setItem(SIZE - 1, backItem());
    }

    private ItemStack upgradeItem(MyPlanetData.Upgrade upgrade) {
        int level = data.upgradeLevel(upgrade);
        int maxLevel = data.maxUpgradeLevel(upgrade);
        boolean maxed = level >= maxLevel;

        // Cost of the next level (each upgrade has its own ladder).
        double cost = MyPlanetData.upgradeCost(upgrade, level);

        ItemStack item = new ItemStack(upgrade.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(upgrade.displayName())
                .color(maxed ? NamedTextColor.GOLD : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));

        java.util.List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.text("Level: " + level + "/" + maxLevel).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));

        // Show current effect for Block Limitations
        if (upgrade == MyPlanetData.Upgrade.BLOCK_LIMITATIONS) {
            int currentLimit = data.blockLimit();
            lore.add(Component.text("Current: " + currentLimit + " blocks (planet-wide)").color(NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Placed so far: " + data.totalBlocksPlaced() + "/" + currentLimit)
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            if (!maxed) {
                int nextLimit = MyPlanetData.blockLimitAtLevel(level + 1);
                lore.add(Component.text("Next: " + nextLimit + " blocks (planet-wide)").color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false));
            }
        } else if (upgrade == MyPlanetData.Upgrade.PLANET_SIZE) {
            lore.add(Component.text("Current size: " + MyPlanetData.sizeName(level)).color(NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("The buildable area is a square around spawn")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            if (!maxed) {
                lore.add(Component.text("Next: " + MyPlanetData.sizeName(level + 1)).color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false));
            }
        } else if (upgrade == MyPlanetData.Upgrade.TP_COOLDOWN_DURATION) {
            lore.add(Component.text("Cooldown & duration of planet TP").color(NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
        }

        if (!maxed && cost >= 0) {
            lore.add(Component.text("Cost: " + Planets.formatPrice(cost) + " VPL").color(NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Click to upgrade").color(NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("MAX LEVEL").color(NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Fully upgraded").color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        return MenuStyle.field();
    }

    private static ItemStack backItem() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("← Back").color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }
}
