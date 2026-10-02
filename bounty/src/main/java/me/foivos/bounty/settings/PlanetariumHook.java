package me.foivos.bounty.settings;

import me.foivos.bounty.BountyPlugin;
import me.foivos.planets.api.PlanetariumSettingsService;
import me.foivos.planets.api.SettingToggle;
import me.foivos.planets.api.SettingsPage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

/**
 * The bridge to the Planets plugin's {@code /settings} menu.
 *
 * <p>This class names Planets' classes, so it is only ever loaded when that
 * plugin is installed — {@link BountySettings} looks it up by name — and it
 * turns the three {@link BountySetting} switches into the toggles the menu
 * renders. Everything it registers is saved by Planets in the player's own
 * settings file, next to their other preferences.
 *
 * <p>Planets publishes the service while it enables, and this plugin
 * soft-depends on it, so the lookup here always finds it; the constructor still
 * refuses politely if it does not, and the caller falls back to config.yml.
 */
final class PlanetariumHook implements BountySettingsBackend {

    private final SettingsPage page;

    PlanetariumHook(BountyPlugin plugin) {
        RegisteredServiceProvider<PlanetariumSettingsService> registration =
                plugin.getServer().getServicesManager()
                        .getRegistration(PlanetariumSettingsService.class);
        if (registration == null || registration.getProvider() == null) {
            throw new IllegalStateException("Planets did not publish its settings service");
        }
        this.page = registration.getProvider().registerPage("Bounty", "notifications",
                "Bounties", "What the bounty board tells you",
                new ItemStack(Material.GOLD_INGOT));
        for (BountySetting setting : BountySetting.values()) {
            page.toggle(new SettingToggle(setting.key(), setting.displayName(), setting.icon(),
                    setting.description(), setting.onText(), setting.offText(), true));
        }
    }

    @Override
    public boolean get(UUID uuid, BountySetting setting) {
        return page.get(uuid, setting.key());
    }
}
