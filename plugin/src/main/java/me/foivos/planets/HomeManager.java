package me.foivos.planets;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Every player's homes, the ones behind {@code /home}, {@code /sethome} and
 * {@code /delhome}.
 *
 * <p>Everybody gets the same four home slots — there is no paid tier — and each
 * player may pick the colour of their own bed icons for free (see
 * {@link #bedColour(UUID)}). Homes are stored per player in {@code homes.yml}
 * so they survive restarts and can be edited by hand:
 *
 * <pre>
 * players:
 *   &lt;uuid&gt;:
 *     Base:
 *       world: world
 *       x: 32.5
 *       y: 68.0
 *       z: -14.5
 *       yaw: 90.0
 *       pitch: 0.0
 * bed-colours:
 *   &lt;uuid&gt;: LIGHT_BLUE_BED
 * </pre>
 */
public final class HomeManager {

    /** What happened when a home was created or renamed. */
    public enum Result {
        OK, AT_LIMIT, NAME_TAKEN, INVALID_NAME, NOT_FOUND
    }

    /**
     * One saved home. {@code colour} is the bed material this home was given,
     * or null when it follows the player's own default bed colour.
     */
    public record Home(String name, String world, double x, double y, double z, float yaw, float pitch,
                       Material colour) {

        /** The location this home points at, or null when its world is missing. */
        Location toLocation() {
            World world = Bukkit.getWorld(this.world);
            return world == null ? null : new Location(world, x, y, z, yaw, pitch);
        }

        /** A short "world x, y, z" line for the menu lore. */
        String describe() {
            return String.format(Locale.ROOT, "%s  %.0f, %.0f, %.0f", world, x, y, z);
        }

        static Home of(String name, Location location) {
            return new Home(name, location.getWorld().getName(), location.getX(), location.getY(),
                    location.getZ(), location.getYaw(), location.getPitch(), null);
        }

        /** The same home in a different colour (null = follow the player's default). */
        Home withColour(Material colour) {
            return new Home(name, world, x, y, z, yaw, pitch, colour);
        }
    }

    /** The four slots everyone has (config: {@code homes.max}). */
    private static final int DEFAULT_SLOTS = 4;
    private static final int NAME_LIMIT = 16;

    /** Seconds a player waits before a home teleport lands (config: {@code homes.teleport-delay}). */
    private static final int DEFAULT_TELEPORT_DELAY = 3;

    /** The colour a bed home icon starts out as; players may change it for free. */
    private static final Material DEFAULT_BED = Material.LIGHT_BLUE_BED;

    /** Every bed colour a player can pick from, in dye order. */
    static final List<Material> BED_COLOURS = List.of(
            Material.WHITE_BED, Material.ORANGE_BED, Material.MAGENTA_BED, Material.LIGHT_BLUE_BED,
            Material.YELLOW_BED, Material.LIME_BED, Material.PINK_BED, Material.GRAY_BED,
            Material.LIGHT_GRAY_BED, Material.CYAN_BED, Material.PURPLE_BED, Material.BLUE_BED,
            Material.BROWN_BED, Material.GREEN_BED, Material.RED_BED, Material.BLACK_BED);

    private final Planets plugin;
    private final File file;
    private YamlConfiguration config;

    /** uuid -> homes in the order they were saved (LinkedHashMap keeps the order). */
    private final Map<UUID, LinkedHashMap<String, Home>> homes = new ConcurrentHashMap<>();

    /** uuid -> the bed colour that player chose for their home icons. */
    private final Map<UUID, Material> bedColours = new ConcurrentHashMap<>();

    HomeManager(Planets plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "homes.yml");
        load();
    }

    // ── Slots ───────────────────────────────────────────────────────────

    /** How many home slots every player has (config: {@code homes.max}). */
    int slots() {
        return Math.max(1, plugin.getConfig().getInt("homes.max", DEFAULT_SLOTS));
    }

    /** How long the countdown before a home teleport lands, in seconds. */
    int teleportDelaySeconds() {
        return Math.max(0, plugin.getConfig().getInt("homes.teleport-delay", DEFAULT_TELEPORT_DELAY));
    }

    // ── Bed colours (free) ──────────────────────────────────────────────

    /** Whether a material is one of the pickable bed colours. */
    static boolean isBedColour(Material material) {
        return material != null && BED_COLOURS.contains(material);
    }

    /** The bed colour a player picked, or the default when they never picked one. */
    Material bedColour(UUID uuid) {
        Material chosen = bedColours.get(uuid);
        return chosen != null ? chosen : DEFAULT_BED;
    }

    /** Turns WHITE_BED into "White" for menu labels. */
    static String colourName(Material material) {
        if (material == null) {
            return "Default";
        }
        String name = material.name();
        if (name.endsWith("_BED")) {
            name = name.substring(0, name.length() - 4);
        }
        StringBuilder out = new StringBuilder();
        for (String word : name.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** The bed colour a player's own default maps to (null in storage terms). */
    private static Material storedColour(Material material) {
        return material == DEFAULT_BED ? null : material;
    }

    /** The bed colour one home draws in: its own if it has one, else the player's. */
    Material bedFor(UUID uuid, Home home) {
        return home != null && home.colour() != null ? home.colour() : bedColour(uuid);
    }

    /**
     * Gives one home its own colour, or clears it back to the player's default
     * when null is passed.
     */
    boolean setHomeColour(UUID uuid, String homeName, Material colour) {
        if (colour != null && !isBedColour(colour)) {
            return false;
        }
        LinkedHashMap<String, Home> map = homes.get(uuid);
        if (map == null || homeName == null) {
            return false;
        }
        String key = homeName.toLowerCase(Locale.ROOT);
        Home home = map.get(key);
        if (home == null) {
            return false;
        }
        map.put(key, home.withColour(storedColour(colour)));
        save();
        return true;
    }

    /**
     * Sets a player's default bed colour and repaints every home they already
     * have, so the picker in the main /home screen stays a one-click change.
     */
    void setBedColourForAll(UUID uuid, Material material) {
        if (!isBedColour(material)) {
            return;
        }
        bedColours.put(uuid, material);
        LinkedHashMap<String, Home> map = homes.get(uuid);
        if (map != null) {
            LinkedHashMap<String, Home> repainted = new LinkedHashMap<>();
            for (Map.Entry<String, Home> entry : map.entrySet()) {
                repainted.put(entry.getKey(), entry.getValue().withColour(storedColour(material)));
            }
            homes.put(uuid, repainted);
        }
        save();
    }

    // ── Reads ───────────────────────────────────────────────────────────

    /** This player's homes, oldest first. */
    public List<Home> homes(UUID uuid) {
        LinkedHashMap<String, Home> map = homes.get(uuid);
        return map == null ? List.of() : new ArrayList<>(map.values());
    }

    /**
     * Every player that has at least one home, as a copy keyed by uuid — used
     * by the admin panel, which must not be able to change the live data by
     * accident.
     */
    public Map<UUID, List<Home>> allHomes() {
        Map<UUID, List<Home>> copy = new LinkedHashMap<>();
        homes.forEach((uuid, map) -> copy.put(uuid, new ArrayList<>(map.values())));
        return copy;
    }

    /** How many homes the player has saved. */
    public int count(UUID uuid) {
        LinkedHashMap<String, Home> map = homes.get(uuid);
        return map == null ? 0 : map.size();
    }

    /** One of the player's homes by name (case-insensitive), or null. */
    public Home get(UUID uuid, String name) {
        LinkedHashMap<String, Home> map = homes.get(uuid);
        if (map == null || name == null) {
            return null;
        }
        return map.get(name.toLowerCase(Locale.ROOT));
    }

    /** The home at a given position in the menu, or null for an empty slot. */
    public Home at(UUID uuid, int index) {
        List<Home> list = homes(uuid);
        return index < 0 || index >= list.size() ? null : list.get(index);
    }

    /** The names of this player's homes, for tab completion. */
    public List<String> names(UUID uuid) {
        return homes(uuid).stream().map(Home::name).toList();
    }

    // ── Writes ──────────────────────────────────────────────────────────

    /**
     * Saves the player's current position as a home, replacing a home of the
     * same name.
     */
    public Result set(Player player, String name, Location location) {
        if (location == null || location.getWorld() == null) {
            return Result.INVALID_NAME;
        }
        String display = normalize(name);
        if (display == null) {
            return Result.INVALID_NAME;
        }
        UUID uuid = player.getUniqueId();
        LinkedHashMap<String, Home> map = homes.computeIfAbsent(uuid, k -> new LinkedHashMap<>());
        boolean replacing = map.containsKey(display.toLowerCase(Locale.ROOT));
        if (!replacing && map.size() >= slots()) {
            return Result.AT_LIMIT;
        }
        map.put(display.toLowerCase(Locale.ROOT), Home.of(display, location));
        save();
        return Result.OK;
    }

    /** Removes one of the player's homes. */
    public boolean delete(UUID uuid, String name) {
        LinkedHashMap<String, Home> map = homes.get(uuid);
        if (map == null || name == null) {
            return false;
        }
        Home removed = map.remove(name.toLowerCase(Locale.ROOT));
        if (removed == null) {
            return false;
        }
        if (map.isEmpty()) {
            homes.remove(uuid);
        }
        save();
        return true;
    }

    /** Renames a home, keeping its location. */
    public Result rename(UUID uuid, String oldName, String newName) {
        Home home = get(uuid, oldName);
        if (home == null) {
            return Result.NOT_FOUND;
        }
        String display = normalize(newName);
        if (display == null) {
            return Result.INVALID_NAME;
        }
        if (display.equalsIgnoreCase(home.name())) {
            return Result.OK;
        }
        if (get(uuid, display) != null) {
            return Result.NAME_TAKEN;
        }
        LinkedHashMap<String, Home> map = homes.get(uuid);
        // Rebuild so the renamed home keeps its position in the list.
        LinkedHashMap<String, Home> reordered = new LinkedHashMap<>();
        for (Map.Entry<String, Home> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(home.name())) {
                reordered.put(display.toLowerCase(Locale.ROOT),
                        new Home(display, home.world(), home.x(), home.y(), home.z(), home.yaw(), home.pitch(),
                                home.colour()));
            } else {
                reordered.put(entry.getKey(), entry.getValue());
            }
        }
        homes.put(uuid, reordered);
        save();
        return Result.OK;
    }

    /** Whether a name is usable for a home (1-16 letters, digits, - or _). */
    public static boolean isValidName(String name) {
        return normalize(name) != null;
    }

    /** Trims and validates a home name, or null when it can't be used. */
    private static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > NAME_LIMIT) {
            return null;
        }
        for (char c : trimmed.toCharArray()) {
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                return null;
            }
        }
        return trimmed;
    }

    // ── Persistence ─────────────────────────────────────────────────────

    public void load() {
        homes.clear();
        bedColours.clear();
        if (!file.exists()) {
            config = new YamlConfiguration();
            return;
        }
        config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = config.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                ConfigurationSection section = players.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                LinkedHashMap<String, Home> map = new LinkedHashMap<>();
                for (String homeName : section.getKeys(false)) {
                    ConfigurationSection entry = section.getConfigurationSection(homeName);
                    if (entry == null) {
                        continue;
                    }
                    String world = entry.getString("world");
                    if (world == null || world.isBlank()) {
                        continue;
                    }
                    Material colour = entry.getString("colour") == null
                            ? null : Material.matchMaterial(entry.getString("colour"));
                    map.put(homeName.toLowerCase(Locale.ROOT), new Home(homeName, world,
                            entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"),
                            (float) entry.getDouble("yaw"), (float) entry.getDouble("pitch"),
                            isBedColour(colour) ? colour : null));
                }
                if (!map.isEmpty()) {
                    homes.put(uuid, map);
                }
            }
        }
        ConfigurationSection colours = config.getConfigurationSection("bed-colours");
        if (colours != null) {
            for (String key : colours.getKeys(false)) {
                Material material = Material.matchMaterial(String.valueOf(colours.get(key)));
                if (material == null || !isBedColour(material)) {
                    continue;
                }
                try {
                    bedColours.put(UUID.fromString(key), material);
                } catch (IllegalArgumentException ignored) {
                    // not a uuid; ignore the entry
                }
            }
        }
        plugin.getLogger().info("Loaded homes for " + homes.size() + " player(s).");
    }

    public void save() {
        if (config == null) {
            config = new YamlConfiguration();
        }
        config.set("players", null);
        for (Map.Entry<UUID, LinkedHashMap<String, Home>> playerEntry : homes.entrySet()) {
            for (Home home : playerEntry.getValue().values()) {
                String path = "players." + playerEntry.getKey() + "." + home.name() + ".";
                config.set(path + "world", home.world());
                config.set(path + "x", home.x());
                config.set(path + "y", home.y());
                config.set(path + "z", home.z());
                config.set(path + "yaw", home.yaw());
                config.set(path + "pitch", home.pitch());
                if (home.colour() != null) {
                    config.set(path + "colour", home.colour().name());
                }
            }
        }
        config.set("bed-colours", null);
        for (Map.Entry<UUID, Material> entry : bedColours.entrySet()) {
            config.set("bed-colours." + entry.getKey(), entry.getValue().name());
        }
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save homes.yml", ex);
        }
    }
}
