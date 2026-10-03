package me.foivos.planets.worldgen;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The set of planet profiles this server knows, plus the world → profile mapping
 * that makes {@code /planets reload} meaningful: a planet already on disk keeps
 * the profile it was created from, so its identity (sky, particles, effects) can
 * be re-applied to config.yml after a reload.
 * <p>
 * Profiles are immutable and the map is swapped wholesale, so a reload is just a
 * parse plus an assignment — no half-loaded state is ever visible to a
 * generation thread.
 */
public final class PlanetRegistry {

    private final JavaPlugin plugin;
    /** profile id -> profile; replaced atomically on reload. */
    private volatile Map<String, PlanetProfile> profiles = Map.of();
    /** world name -> profile id, persisted in config.yml under planet-profiles. */
    private final Map<String, String> worlds = new java.util.concurrent.ConcurrentHashMap<>();

    public PlanetRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Re-reads every profile from disk and reloads the world mapping. */
    public int reload() {
        PlanetProfileLoader.installDefaults(plugin);
        Map<String, PlanetProfile> loaded = PlanetProfileLoader.loadAll(plugin);
        this.profiles = Map.copyOf(loaded);
        loadWorldMapping(plugin.getConfig());
        return loaded.size();
    }

    /** Reads the persisted world → profile mapping. */
    public void loadWorldMapping(FileConfiguration config) {
        worlds.clear();
        ConfigurationSection section = config.getConfigurationSection("planet-profiles");
        if (section == null) {
            return;
        }
        for (String world : section.getKeys(false)) {
            String profile = section.getString(world);
            if (profile != null && !profile.isBlank()) {
                worlds.put(world.toLowerCase(Locale.ROOT), profile.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Persists a world's profile id so its identity survives a restart. */
    public void bind(String worldName, String profileId, FileConfiguration config) {
        if (worldName == null || profileId == null) {
            return;
        }
        worlds.put(worldName.toLowerCase(Locale.ROOT), profileId.toLowerCase(Locale.ROOT));
        config.set("planet-profiles." + worldName, profileId.toLowerCase(Locale.ROOT));
    }

    /** Forgets a world's profile (when the world is deleted). */
    public void unbind(String worldName, FileConfiguration config) {
        if (worldName == null) {
            return;
        }
        worlds.remove(worldName.toLowerCase(Locale.ROOT));
        config.set("planet-profiles." + worldName, null);
    }

    /** The profile id a world was generated from, or null. */
    public String profileIdOf(String worldName) {
        return worldName == null ? null : worlds.get(worldName.toLowerCase(Locale.ROOT));
    }

    /** The profile a world was generated from, or null when it isn't a profile planet. */
    public PlanetProfile profileOf(String worldName) {
        return byId(profileIdOf(worldName));
    }

    /** The profile with this id, or null. */
    public PlanetProfile byId(String id) {
        if (id == null) {
            return null;
        }
        String key = id.toLowerCase(Locale.ROOT);
        PlanetProfile exact = profiles.get(key);
        if (exact != null) {
            return exact;
        }
        // World names often carry a suffix ("prehistoric_2"); accept the longest
        // profile id that the name starts with so a copied planet still resolves.
        PlanetProfile best = null;
        for (Map.Entry<String, PlanetProfile> entry : profiles.entrySet()) {
            if (key.startsWith(entry.getKey()) && (best == null
                    || entry.getKey().length() > best.id().length())) {
                best = entry.getValue();
            }
        }
        return best;
    }

    /** Every profile, in file-name order. */
    public List<PlanetProfile> all() {
        return List.copyOf(profiles.values());
    }

    /** Every profile id, in file-name order. */
    public List<String> ids() {
        return new ArrayList<>(profiles.keySet());
    }

    public boolean isEmpty() {
        return profiles.isEmpty();
    }

    public int size() {
        return profiles.size();
    }
}
