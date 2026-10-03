package me.foivos.planets.features;

import me.foivos.planets.worldgen.PlanetProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gives a planet its own resource pack — the textures, models and sounds its
 * profile declares under {@code client.resource-pack} — and swaps it back out
 * when the player leaves.
 * <p>
 * The push uses {@link Player#addResourcePack(UUID, String, byte[], String,
 * boolean)}, which <i>layers</i> the planet pack on top of whatever the server
 * already sent, without clearing it. On exit the planet pack is removed again
 * and the server-wide pack from {@code config.yml} ({@code resource-pack.*})
 * is re-sent, so menus and the sidebar get their textures back.
 * <p>
 * Honest about the client's limits: a pack cannot be swapped the instant a
 * player steps through a portal — the client downloads it first and shows its
 * own prompt. The enter/exit hooks fire at the right moments, but the visual
 * switch is the client's to make. This is why the planet pack must be hosted
 * at a stable URL with a SHA-1, so the client can cache it after the first
 * landing.
 */
public final class ResourcePackFeature implements PlanetFeature {

    /** The plugin, for config access and logging. */
    private final JavaPlugin plugin;
    /** player uuid -> the pack uuid this feature pushed and can withdraw. */
    private final Map<UUID, UUID> sentPacks = new ConcurrentHashMap<>();

    public ResourcePackFeature(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "resource-pack";
    }

    @Override
    public String description() {
        return "pushes the planet's own resource pack (client.resource-pack) on landing"
                + " and restores the server-wide pack on leaving";
    }

    /** Built-ins are configured by {@code client.resource-pack}, not {@code features:}. */
    @Override
    public boolean appliesTo(PlanetProfile profile) {
        return profile.resourcePack().enabled();
    }

    @Override
    public void onPlanetEnter(Player player, PlanetProfile profile) {
        PlanetProfile.ResourcePack pack = profile.resourcePack();
        if (!pack.enabled() || pack.url() == null || pack.url().isBlank()) {
            return;
        }
        UUID packId = UUID.randomUUID();
        try {
            player.addResourcePack(packId, pack.url(), sha1Bytes(pack.sha1()),
                    pack.prompt() == null ? "" : pack.prompt(), pack.required());
            sentPacks.put(player.getUniqueId(), packId);
            plugin.getLogger().info("[Feature:" + id() + "] Sent the '" + profile.id()
                    + "' resource pack to " + player.getName() + ".");
        } catch (Throwable ex) {
            plugin.getLogger().warning("[Feature:" + id() + "] Could not send the '"
                    + profile.id() + "' resource pack to " + player.getName() + ": " + ex);
        }
    }

    @Override
    public void onPlanetExit(Player player, PlanetProfile profile) {
        UUID packId = sentPacks.remove(player.getUniqueId());
        if (packId != null && player.isOnline()) {
            try {
                player.removeResourcePack(packId);
            } catch (Throwable ignored) {
                // The player may already be gone; nothing to clean up then.
            }
        }
        restoreServerPack(player);
    }

    @Override
    public void onPlanetChange(Player player, PlanetProfile from, PlanetProfile to) {
        // Landing on one planet directly from another is both an exit and an
        // enter: swap the packs in one step.
        onPlanetExit(player, from);
        onPlanetEnter(player, to);
    }

    /** Re-sends the server-wide pack from config.yml, so the menus keep their textures. */
    private void restoreServerPack(Player player) {
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", false)
                || !player.isOnline()) {
            return;
        }
        String url = plugin.getConfig().getString("resource-pack.url", "");
        if (url == null || url.isBlank()) {
            return;
        }
        String sha1 = plugin.getConfig().getString("resource-pack.sha1", "");
        boolean required = plugin.getConfig().getBoolean("resource-pack.required", false);
        String prompt = plugin.getConfig().getString("resource-pack.prompt",
                "Custom textures make the menus look right");
        try {
            player.setResourcePack(UUID.randomUUID(), url, sha1Bytes(sha1),
                    Component.text(prompt), required);
        } catch (Throwable ex) {
            plugin.getLogger().warning("[Feature:" + id() + "] Could not restore the "
                    + "server-wide resource pack for " + player.getName() + ": " + ex);
        }
    }

    /**
     * Decodes a 40-character SHA-1 hex string into the 20 bytes the packet
     * wants. Anything else (missing, malformed, not 40 chars) becomes an empty
     * array, which the client treats as "no hash" and re-downloads the pack.
     */
    private static byte[] sha1Bytes(String sha1) {
        if (sha1 == null || sha1.length() != 40) {
            return new byte[0];
        }
        byte[] bytes = new byte[20];
        for (int i = 0; i < 20; i++) {
            int high = Character.digit(sha1.charAt(i * 2), 16);
            int low = Character.digit(sha1.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return new byte[0];
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
}
