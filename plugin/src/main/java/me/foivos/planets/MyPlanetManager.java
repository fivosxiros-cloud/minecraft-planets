package me.foivos.planets;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Manages all planet ownership data. Reads from and writes to
 * {@code my-planets.yml} in the plugin data folder. Provides lookup,
 * creation and persistence of {@link MyPlanetData} instances.
 *
 * <p>Also tracks permanently retired planet names: once a planet is deleted
 * its name can never be issued again through the "Buy a Planet" flow.
 */
public final class MyPlanetManager {

    private final Planets plugin;
    private final File dataFile;
    private YamlConfiguration config;

    /** worldName (lowercase) -> owned data. */
    private final Map<String, MyPlanetData> planets = new HashMap<>();

    /** Planet world names (lowercase) that were deleted — they can never be bought again. */
    private final Set<String> retiredPlanets = new HashSet<>();

    public MyPlanetManager(Planets plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "my-planets.yml");
        load();
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /** Load or create the data file. */
    public void load() {
        planets.clear();
        if (!dataFile.exists()) {
            try {
                dataFile.getParentFile().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not create my-planets.yml", e);
            }
            config = new YamlConfiguration();
            return;
        }
        config = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = config.getConfigurationSection("my-planets");
        if (root == null) return;
        for (String worldName : root.getKeys(false)) {
            MyPlanetData data = MyPlanetData.load(worldName, root.getConfigurationSection(worldName));
            if (data != null) {
                planets.put(worldName.toLowerCase(Locale.ROOT), data);
            }
        }
        for (String name : config.getStringList("retired-planets")) {
            if (name != null && !name.isBlank()) {
                retiredPlanets.add(name.toLowerCase(Locale.ROOT));
            }
        }
        plugin.getLogger().info("Loaded " + planets.size() + " owned planet(s) and "
                + retiredPlanets.size() + " retired planet name(s) from my-planets.yml.");
    }

    /** Save all data to disk. */
    public void save() {
        if (config == null) {
            config = new YamlConfiguration();
        }
        // Rebuild from scratch for a clean write.
        config.createSection("my-planets");
        for (Map.Entry<String, MyPlanetData> entry : planets.entrySet()) {
            String path = "my-planets." + entry.getKey();
            config.createSection(path);
            entry.getValue().save(Objects.requireNonNull(config.getConfigurationSection(path)));
        }
        // Retired (deleted) planet names that can never be bought again.
        config.set("retired-planets", retiredPlanets.stream().sorted().toList());
        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save my-planets.yml", e);
        }
    }

    // ── Lookups ─────────────────────────────────────────────────────────

    /** Get the data for a world, or null if it is not owned. */
    public MyPlanetData get(String worldName) {
        return planets.get(worldName.toLowerCase(Locale.ROOT));
    }

    /** Whether the given world belongs to a player (any owner). */
    public boolean isOwnedWorld(String worldName) {
        return worldName != null && planets.containsKey(worldName.toLowerCase(Locale.ROOT));
    }

    /** Get the data for a world, or null if it is not owned. */
    public MyPlanetData get(String worldName, Locale locale) {
        return planets.get(worldName.toLowerCase(locale));
    }

    /** All owned planets. */
    public Collection<MyPlanetData> allPlanets() {
        return Collections.unmodifiableCollection(planets.values());
    }

    /** All worlds owned by a player (any role). */
    public List<MyPlanetData> ownedBy(UUID playerUuid) {
        return planets.values().stream()
                .filter(p -> p.ownerUuid().equals(playerUuid))
                .toList();
    }

    /** All planets where the player is a member (any role). */
    public List<MyPlanetData> memberOf(UUID playerUuid) {
        return planets.values().stream()
                .filter(p -> p.isMember(playerUuid))
                .toList();
    }

    /** Planets that are For Sale. */
    public List<MyPlanetData> forSale() {
        return planets.values().stream()
                .filter(MyPlanetData::forSale)
                .toList();
    }

    /** How many planets this player owns. */
    public int ownedCount(UUID playerUuid) {
        return (int) planets.values().stream()
                .filter(p -> p.ownerUuid().equals(playerUuid))
                .count();
    }

    /** Max planets a player may own (from config, default 2). */
    public int maxPlanetsPerPlayer() {
        return plugin.getConfig().getInt("my-planet.max-per-player", 2);
    }

    /** Cost in money to buy a brand-new default planet (from config, default 1000). */
    public double buyCost() {
        return plugin.getConfig().getDouble("my-planet.buy-cost", 1000);
    }

    // ── Mutations ───────────────────────────────────────────────────────

    /** Claim a world for a player. Returns the new data, or null if the player
     *  has reached their limit. */
    public MyPlanetData claim(String worldName, UUID ownerUuid) {
        String key = worldName.toLowerCase(Locale.ROOT);
        if (planets.containsKey(key)) return null;
        if (ownedCount(ownerUuid) >= maxPlanetsPerPlayer()) return null;
        MyPlanetData data = new MyPlanetData(worldName, ownerUuid);
        planets.put(key, data);
        save();
        return data;
    }

    /** Remove a planet from the ownership system. */
    public void remove(String worldName) {
        planets.remove(worldName.toLowerCase(Locale.ROOT));
        save();
    }

    /** Whether this world name belonged to a deleted planet and can never be bought again. */
    public boolean isRetired(String worldName) {
        return worldName != null && retiredPlanets.contains(worldName.toLowerCase(Locale.ROOT));
    }

    /** Permanently retires a deleted planet's name so it can never be bought again. */
    public void retire(String worldName) {
        if (worldName == null || worldName.isBlank()) return;
        if (retiredPlanets.add(worldName.toLowerCase(Locale.ROOT))) {
            save();
        }
    }

    /** All retired planet names (lowercase, unmodifiable). */
    public Set<String> retiredPlanets() {
        return Collections.unmodifiableSet(retiredPlanets);
    }

    /** Notify that data changed; persists to disk. */
    public void touch(MyPlanetData data) {
        save();
    }

    // ── Global settings ─────────────────────────────────────────────────

    /** Claim cost in experience levels (from config, default 5). */
    public int claimCostLevels() {
        return plugin.getConfig().getInt("my-planet.claim-cost-levels", 5);
    }

    /** Whether unclaimed worlds are claimable by players (default true). */
    public boolean claimEnabled() {
        return plugin.getConfig().getBoolean("my-planet.claim-enabled", true);
    }

    /** Whether the buy/sell system is enabled (default true). */
    public boolean economyEnabled() {
        return plugin.getConfig().getBoolean("my-planet.economy-enabled", true);
    }

    /** Default sale price when putting a planet on sale (from config, default 1000). */
    public double defaultSalePrice() {
        return plugin.getConfig().getDouble("my-planet.default-sale-price", 1000);
    }

    /** Maximum upgrade level for all planet upgrades (from config, default 10). */
    public int maxUpgradeLevel() {
        return plugin.getConfig().getInt("my-planet.max-upgrade-level", 10);
    }
}
