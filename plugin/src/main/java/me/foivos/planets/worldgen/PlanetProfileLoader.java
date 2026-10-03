package me.foivos.planets.worldgen;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Reads {@code plugins/Planets/planets/*.yml} into {@link PlanetProfile}s.
 * <p>
 * This is the part that has to be forgiving: a profile is hand-written YAML by
 * an admin who is mid-experiment, so a bad block name or a nonsense range is
 * reported with the planet's id and the offending key and then replaced by a
 * working default. One broken file never stops the others from loading, and it
 * never stops the server.
 * <p>
 * The shipped presets live inside the jar under {@code planets/}; on first run
 * they are written out so they can be copied and edited.
 */
public final class PlanetProfileLoader {

    /** Where the shipped presets live inside the jar. */
    private static final String RESOURCE_ROOT = "planets";

    /** The presets that ship with the plugin, in menu order. */
    public static final List<String> PRESET_IDS = List.of(
            "prehistoric", "alien", "volcanic", "frozen", "desert", "ocean",
            "crystal", "mushroom", "toxic", "backrooms", "floating_islands", "cavern");

    private PlanetProfileLoader() {
    }

    /** The data folder profiles are read from. */
    public static File folder(JavaPlugin plugin) {
        return new File(plugin.getDataFolder(), "planets");
    }

    /**
     * Writes the shipped presets into the data folder, without ever overwriting a
     * file the admin has edited.
     */
    public static void installDefaults(JavaPlugin plugin) {
        File folder = folder(plugin);
        if (!folder.isDirectory() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create the planets folder at " + folder);
            return;
        }
        for (String id : PRESET_IDS) {
            File target = new File(folder, id + ".yml");
            if (target.isFile()) {
                continue; // the admin's own copy wins, always
            }
            try (InputStream stream = plugin.getResource(RESOURCE_ROOT + "/" + id + ".yml")) {
                if (stream == null) {
                    plugin.getLogger().warning("Preset '" + id + "' is missing from the jar.");
                    continue;
                }
                java.nio.file.Files.copy(stream, target.toPath());
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not write the preset '" + id + "'", ex);
            }
        }
    }

    /**
     * Loads every profile in the folder. Failures are logged and skipped; the map
     * that comes back only holds profiles that are usable.
     */
    public static Map<String, PlanetProfile> loadAll(JavaPlugin plugin) {
        Map<String, PlanetProfile> profiles = new LinkedHashMap<>();
        File folder = folder(plugin);
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return profiles;
        }
        java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
        for (File file : files) {
            String id = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
            PlanetProfile profile = load(plugin, file, id);
            if (profile != null) {
                profiles.put(id, profile);
            }
        }
        return profiles;
    }

    /** Loads one file, or null when it is unusable. */
    public static PlanetProfile load(JavaPlugin plugin, File file, String id) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().warning("Failed to load planet profile '" + id + "' from "
                    + file.getName() + ": " + ex.getMessage());
            return null;
        }
        List<String> warnings = new ArrayList<>();
        try {
            PlanetProfile profile = PlanetProfile.from(id, yaml, file.getName(), warnings::add);
            report(plugin, id, warnings);
            return profile;
        } catch (RuntimeException ex) {
            // A bug in the parsing code must degrade to "this profile is skipped",
            // not take the whole plugin down during onEnable.
            plugin.getLogger().log(Level.SEVERE, "Profile '" + id + "' could not be loaded ("
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ") — skipped", ex);
            return null;
        }
    }

    /** Loads a profile from a resource inside the jar (used for the built-in fallback). */
    public static PlanetProfile loadResource(JavaPlugin plugin, String resourcePath, String id) {
        try (InputStream stream = plugin.getResource(resourcePath)) {
            if (stream == null) {
                return null;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            List<String> warnings = new ArrayList<>();
            try {
                PlanetProfile profile = PlanetProfile.from(id, yaml, resourcePath, warnings::add);
                report(plugin, id, warnings);
                return profile;
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.SEVERE, "Built-in profile '" + resourcePath + "' could not be loaded ("
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ") — skipped", ex);
                return null;
            }
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().warning("Failed to read the built-in profile '" + resourcePath + "': "
                    + ex.getMessage());
            return null;
        }
    }

    /**
     * One grouped log line per profile instead of one per problem: an admin
     * tuning a big file wants to see all of it at once, and not four times.
     */
    private static void report(JavaPlugin plugin, String id, List<String> warnings) {
        if (warnings.isEmpty()) {
            return;
        }
        plugin.getLogger().warning("[PlanetGenerator] Profile '" + id + "' loaded with "
                + warnings.size() + " problem(s):");
        for (String warning : warnings) {
            plugin.getLogger().warning("[PlanetGenerator]   " + warning);
        }
    }

    /** The raw YAML of a profile, for the debug command. */
    public static ConfigurationSection raw(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            return null;
        }
        return yaml;
    }

    /** Applies {@code warn} to every problem in a section without loading a profile. */
    public static void validate(ConfigurationSection section, Consumer<String> warn) {
        if (section == null) {
            warn.accept("the file is empty");
        }
    }
}
