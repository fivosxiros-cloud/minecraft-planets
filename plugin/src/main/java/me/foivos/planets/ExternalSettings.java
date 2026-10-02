package me.foivos.planets;

import me.foivos.planets.api.PlanetariumSettingsService;
import me.foivos.planets.api.SettingToggle;
import me.foivos.planets.api.SettingsPage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The registry behind {@link PlanetariumSettingsService}: the {@code /settings}
 * pages other plugins ask for, and the bridge that reads and writes their
 * switches through {@link PlayerSettings}.
 *
 * <p>A page is registered by a plugin while that plugin enables — Planets
 * registers the service before any plugin that soft-depends on it, so the
 * lookup always succeeds — and the pages are then drawn as their own screen,
 * one button per page in the last rows of {@code /settings}, each switch a
 * ✅/❌ toggle like the plugin's own. Choosing one is written to
 * {@code player-settings.yml} under {@code players.<uuid>.external}, so it is
 * the player's own choice on every server start, and it is cleared with
 * everything else by Reset to Defaults.
 *
 * <p>Nothing here is shown in the main {@code /settings} page until a plugin
 * registers a page, so a server running Planets alone never sees any of it.
 */
final class ExternalSettings implements PlanetariumSettingsService {

    /** What {@code /settings} shows for one registered page. */
    record PageSpec(String pluginName, String id, String displayName,
                    String description, ItemStack icon) {

        /** Where this page's switches are stored, e.g. {@code Bounty.notifications}. */
        String path() {
            return segment(pluginName) + "." + segment(id);
        }
    }

    private final Planets plugin;
    /** Every registered page, keyed by its storage path, in registration order. */
    private final Map<String, Page> pages = new LinkedHashMap<>();

    ExternalSettings(Planets plugin) {
        this.plugin = plugin;
    }

    @Override
    public SettingsPage registerPage(String pluginName, String id, String displayName,
                                     String description, ItemStack icon) {
        if (pluginName == null || pluginName.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException("a settings page needs a plugin name and an id");
        }
        if (pages.size() >= MAX_PAGES && !pages.containsKey(
                segment(pluginName) + "." + segment(id))) {
            plugin.getLogger().warning("Cannot register another /settings page for "
                    + pluginName + " — the menu fits " + MAX_PAGES + ".");
        }
        PageSpec spec = new PageSpec(pluginName, id,
                displayName == null || displayName.isBlank() ? id : displayName,
                description == null ? "" : description,
                icon == null ? new ItemStack(Material.PAPER) : icon);
        Page page = pages.computeIfAbsent(spec.path(), key -> new Page(spec));
        page.spec = spec;
        plugin.getLogger().info("Registered the /settings page \"" + spec.displayName()
                + "\" for " + pluginName + ".");
        return page;
    }

    /** Every registered page, in the order the plugins registered them. */
    List<Page> pages() {
        return new ArrayList<>(pages.values());
    }

    /** One registered page by its storage path, or null. */
    Page page(String path) {
        return pages.get(path);
    }

    /**
     * One page of switches, as its owning plugin sees it. Handing the same
     * object back on every registration keeps the toggles a plugin added during
     * its first enable.
     */
    final class Page implements SettingsPage {

        private PageSpec spec;
        private final List<SettingToggle> toggles = new ArrayList<>();

        private Page(PageSpec spec) {
            this.spec = spec;
        }

        PageSpec spec() {
            return spec;
        }

        @Override
        public String pluginName() {
            return spec.pluginName();
        }

        @Override
        public String id() {
            return spec.id();
        }

        @Override
        public List<SettingToggle> toggles() {
            return List.copyOf(toggles);
        }

        @Override
        public void toggle(SettingToggle toggle) {
            if (toggle == null) {
                return;
            }
            toggles.removeIf(existing -> existing.key().equals(toggle.key()));
            toggles.add(toggle);
        }

        @Override
        public boolean get(UUID uuid, String key) {
            return value(uuid, key);
        }

        /** Whether a player has one of this page's switches on. */
        boolean value(UUID uuid, String key) {
            SettingToggle toggle = find(key);
            boolean fallback = toggle == null || toggle.defaultOn();
            PlayerSettings settings = plugin.getPlayerSettings();
            return settings == null || settings.external(uuid, spec.path(), key, fallback);
        }

        @Override
        public void set(UUID uuid, String key, boolean value) {
            setValue(uuid, key, value);
        }

        /** Stores a player's choice, or forgets it when it is the default again. */
        void setValue(UUID uuid, String key, boolean value) {
            PlayerSettings settings = plugin.getPlayerSettings();
            if (settings == null) {
                return;
            }
            SettingToggle toggle = find(key);
            boolean isDefault = toggle != null && toggle.defaultOn() == value;
            settings.setExternal(uuid, spec.path(), key, isDefault ? null : value);
        }

        private SettingToggle find(String key) {
            for (SettingToggle toggle : toggles) {
                if (toggle.key().equals(key)) {
                    return toggle;
                }
            }
            return null;
        }
    }

    /** One part of a storage path: plugin names cannot smuggle in a folder. */
    private static String segment(String raw) {
        return raw.trim().replace('.', '_');
    }
}
