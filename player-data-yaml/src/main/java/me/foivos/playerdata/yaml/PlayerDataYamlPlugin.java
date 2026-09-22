package me.foivos.playerdata.yaml;

import me.foivos.playerdata.IPlayerDataStore;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerDataYamlPlugin extends JavaPlugin {
    private PlayerDataStore store;

    @Override
    public void onEnable() {
        store = new PlayerDataStore(this);
        store.ensureFolder();
        getServer().getServicesManager().register(
                IPlayerDataStore.class, store, this, ServicePriority.Normal);
        getLogger().info("Registered IPlayerDataStore YAML service.");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}
