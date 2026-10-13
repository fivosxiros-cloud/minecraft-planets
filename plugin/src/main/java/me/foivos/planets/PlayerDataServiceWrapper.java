package me.foivos.planets;

import me.george.playerdata.api.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

public class PlayerDataServiceWrapper implements PlayerProfileListener {

    private final IPlayerDataService dataService;
    private final Planets planets;
    private final SidebarScoreboard sidebar;

    public PlayerDataServiceWrapper(Planets planets, RegisteredServiceProvider<IPlayerDataService> provider, SidebarScoreboard sidebar) {
        this.planets = planets;
        this.sidebar = sidebar;
        dataService = provider.getProvider();
        dataService.addPlayerProfileEventListener(this);
    }

    @Override
    public void onPlayerProfileChanged(UUID uuid, PlayerProfile playerProfile) {
        Player player = planets.getServer().getPlayer(uuid);
        if (player == null) {
            return;
        }

        sidebar.updatePlayerScoreboard(player, playerProfile.race(), playerProfile.role(), playerProfile.team());
    }

    public void onShutdown() {
        dataService.removePlayerProfileEventListener(this);
    }

    public String parsePlayersProfileTemplate(Player player, String template){
        var profile = dataService.getProfile(player.getUniqueId()).orElse(PlayerProfile.empty());

        return template.replace("%race%", profile.race())
                       .replace("%role%", profile.role())
                       .replace("%team%", profile.team());
    }
}
