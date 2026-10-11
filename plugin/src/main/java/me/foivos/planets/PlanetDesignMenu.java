package me.foivos.planets;

import me.foivos.planets.worldgen.PlanetDesign;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The planet designer as a click-through menu, so a planet's shape can be built
 * without typing {@code filter=preset} on the command line. Opened with
 * {@code /planets design &lt;name&gt;} — a bare name with no filters means "let me
 * click them" — and built by the same creation path as the command.
 *
 * <p>Every configured filter is one button: click it to step through its presets
 * and watch the summary line change. The ready-made designs at the bottom set
 * every filter at once, so the usual flow is "click a design, tweak two filters,
 * create". The choices are only turned into a world when "Create Planet" is
 * clicked, and that goes through exactly the same path as the command — the same
 * validation, the same config record and the same procedural terrain.
 *
 * <p>Layout (54 slots, 6 rows):
 * <pre>
 *   ┌────────── summary (4) ──────────┐
 *   Filters  (10..16, 19..25)
 *   Designs  (28..34)
 *   Reset(38)        Close(40)      Create(42)
 * </pre>
 */
public final class PlanetDesignMenu implements InventoryHolder {

    private static final int SIZE = 54;
    /** The filter buttons, in reading order: two rows of seven. */
    private static final int[] FILTER_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    /** The ready-made design buttons. */
    private static final int[] DESIGN_SLOTS = {28, 29, 30, 31, 32, 33, 34};
    private static final int SUMMARY_SLOT = 4;
    private static final int RESET_SLOT = 38;
    private static final int CLOSE_SLOT = 40;
    private static final int CREATE_SLOT = 42;

    private final Planets plugin;
    private final Player viewer;
    private final String worldName;
    private final Inventory inventory;
    /** filter id → chosen preset id. Only what the admin has picked shows here. */
    private final Map<String, String> choices = new LinkedHashMap<>();
    /** The ready-made design ids currently on screen, in button order. */
    private final List<String> designIds = new ArrayList<>();
    /** Preset names that mean "off", so a first click does something visible. */
    private static final List<String> OFF_PRESETS = List.of("none", "off", "flat", "disabled");

    public PlanetDesignMenu(Planets plugin, Player viewer, String worldName) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.worldName = worldName;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("\uD83E\uDDEA Design: " + worldName).color(NamedTextColor.DARK_AQUA));
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !plugin.canDesign(player)) {
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
        if (slot == RESET_SLOT) {
            choices.clear();
            render();
            return;
        }
        if (slot == CREATE_SLOT) {
            if (choices.isEmpty()) {
                player.sendMessage(Component.text("Pick at least one filter before creating the planet.")
                        .color(NamedTextColor.RED));
                return;
            }
            player.closeInventory();
            plugin.createDesignedPlanet(player, worldName, new LinkedHashMap<>(choices));
            return;
        }

        List<PlanetDesign.Filter> filters = plugin.designFilters();
        int filterIndex = indexOf(FILTER_SLOTS, slot);
        if (filterIndex >= 0 && filterIndex < filters.size()) {
            cycle(filters.get(filterIndex));
            render();
            return;
        }
        int designIndex = indexOf(DESIGN_SLOTS, slot);
        if (designIndex >= 0 && designIndex < designIds.size()) {
            applyDesign(designIds.get(designIndex));
            render();
        }
    }

    /** Steps one filter to its next preset, wrapping round at the end. */
    private void cycle(PlanetDesign.Filter filter) {
        List<String> presets = filter.presetIds();
        if (presets.isEmpty()) {
            return;
        }
        String current = choices.get(filter.id());
        if (current == null) {
            choices.put(filter.id(), firstChange(presets));
            return;
        }
        int at = indexOfIgnoreCase(presets, current);
        choices.put(filter.id(), presets.get(at < 0 ? 0 : (at + 1) % presets.size()));
    }

    /**
     * The preset the first click on a filter lands on. Every filter's list opens
     * with its "off" choice ({@code none} for mountains, {@code flat} for hills),
     * and picking that would leave the terrain exactly as the baseline had it — the
     * button would look broken. The off choice is still reachable by cycling past
     * the end. A filter made of nothing but off choices falls back to the first.
     */
    private static String firstChange(List<String> presets) {
        for (String preset : presets) {
            if (!isOffPreset(preset)) {
                return preset;
            }
        }
        return presets.get(0);
    }

    /** Whether a preset name means "turn this feature off". */
    private static boolean isOffPreset(String preset) {
        return OFF_PRESETS.stream().anyMatch(word -> word.equalsIgnoreCase(preset));
    }

    /** Replaces every choice with a ready-made design's. */
    private void applyDesign(String designId) {
        ConfigurationSection section = plugin.getConfig()
                .getConfigurationSection("planet-design.designs." + designId);
        if (section == null) {
            return;
        }
        Map<String, String> picked = PlanetDesign.choicesOf(section, plugin.getLogger()::warning, designId);
        choices.clear();
        choices.putAll(picked);
    }

    private void render() {
        inventory.clear();

        MenuStyle.decorate(inventory, "\uD83E\uDDEA Planet design", "\uD83C\uDF9B Filters");

        List<PlanetDesign.Filter> filters = plugin.designFilters();
        int shownFilters = Math.min(filters.size(), FILTER_SLOTS.length);
        for (int i = 0; i < shownFilters; i++) {
            inventory.setItem(FILTER_SLOTS[i], filterItem(filters.get(i)));
        }

        designIds.clear();
        ConfigurationSection designs = plugin.getConfig()
                .getConfigurationSection("planet-design.designs");
        if (designs != null) {
            for (String id : designs.getKeys(false)) {
                if (designIds.size() >= DESIGN_SLOTS.length) {
                    break;
                }
                designIds.add(id);
                inventory.setItem(DESIGN_SLOTS[designIds.size() - 1], designItem(id));
            }
        }

        inventory.setItem(SUMMARY_SLOT, summaryItem(filters, shownFilters));
        inventory.setItem(RESET_SLOT, actionItem(Material.BARRIER, "Reset",
                "Clear every filter and start again"));
        inventory.setItem(CLOSE_SLOT, actionItem(Material.ARROW, "Close",
                "Nothing is created"));
        inventory.setItem(CREATE_SLOT, createItem());
    }

    private ItemStack filterItem(PlanetDesign.Filter filter) {
        String current = choices.get(filter.id());
        ItemStack item = new ItemStack(filter.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(filter.label() + ": "
                        + (current == null ? "not set" : current))
                .color(current == null ? NamedTextColor.GRAY : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(filter.description()).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Presets:").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        for (String presetId : filter.presetIds()) {
            boolean chosen = current != null && presetId.equalsIgnoreCase(current);
            lore.add(Component.text((chosen ? " \u25B6 " : "    ") + presetId)
                    .color(chosen ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Click for the next preset")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack designItem(String designId) {
        ConfigurationSection section = plugin.getConfig()
                .getConfigurationSection("planet-design.designs." + designId);
        String label = section == null ? designId : section.getString("display-name", designId);
        String description = section == null ? null : section.getString("description");
        Material icon = section == null ? null
                : Material.matchMaterial(section.getString("icon", "GRASS_BLOCK"));
        if (icon == null || icon == Material.AIR) {
            icon = Material.GRASS_BLOCK;
        }

        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(label).color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (description != null) {
            lore.add(Component.text(description).color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (section != null) {
            Map<String, String> picked = PlanetDesign.choicesOf(section, plugin.getLogger()::warning, designId);
            lore.add(Component.text(PlanetDesign.describe(plugin.designFilters(), picked))
                    .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text("Click to use this design").color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack summaryItem(List<PlanetDesign.Filter> filters, int shownFilters) {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("\uD83E\uDDEA " + worldName).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("World: " + worldName).color(NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Shape: " + PlanetDesign.describe(filters, choices))
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(choices.size() + " filter(s) chosen").color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (filters.size() > shownFilters) {
            lore.add(Component.text("(" + (filters.size() - shownFilters)
                            + " more filter(s) — use /planets design <name> <filter>=<preset>)")
                    .color(NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createItem() {
        ItemStack item = new ItemStack(choices.isEmpty() ? Material.GRAY_DYE : Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Create Planet").color(NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Builds '" + worldName + "' with the filters on the left")
                .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Terrain is procedural: walking around generates it")
                .color(NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("The planet stays out of /p until you confirm it")
                .color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        if (choices.isEmpty()) {
            lore.add(Component.text("Pick at least one filter first")
                    .color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack actionItem(Material material, String name, String... loreLines) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name).color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(Component.text(line).color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static int indexOf(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfIgnoreCase(List<String> ids, String id) {
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i).equalsIgnoreCase(id)) {
                return i;
            }
        }
        return -1;
    }

}
