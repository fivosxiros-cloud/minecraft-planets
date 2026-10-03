package me.foivos.planets.worldgen;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * The bridge between the planet plugin and the generation engine.
 * <p>
 * Everything the rest of the plugin needs to know about procedural planets goes
 * through here: which profiles exist, how to create a world from one, how to apply
 * a planet's atmosphere to the existing config.yml sections (so the sky, particles,
 * effects and weather systems already built for planets pick it up with no changes),
 * and the text behind {@code /planets list|profile|debug}.
 * <p>
 * It deliberately owns no generation logic: it builds a
 * {@link PlanetChunkGenerator} and lets it do the work.
 */
public final class PlanetGeneration {

    private final JavaPlugin plugin;
    private final PlanetRegistry registry;
    private final File dataFolder;

    public PlanetGeneration(JavaPlugin plugin) {
        this.plugin = plugin;
        this.registry = new PlanetRegistry(plugin);
        this.dataFolder = new File(plugin.getDataFolder(), "planets");
    }

    /** Re-reads every profile from disk. */
    public int reload() {
        int loaded = registry.reload();
        plugin.getLogger().info("[PlanetGenerator] Loaded " + loaded + " planet profile(s)"
                + (loaded == 0 ? " — no planets/*.yml files were found" : ""));
        return loaded;
    }

    public PlanetRegistry registry() {
        return registry;
    }

    /** The folder profiles are read from. */
    public File folder() {
        return dataFolder;
    }

    // ── World creation ───────────────────────────────────────────────────

    /**
     * Creates a world from a profile: the generator is attached at creation time,
     * which is the only moment Bukkit allows it, so the planet's terrain is real
     * from the first chunk.
     *
     * @param worldName the world to create (must not already exist on disk)
     * @param profile   the planet profile
     * @param seed      the seed to use, or null for a fresh random one
     * @return the created world, or null when creation failed
     */
    public World create(String worldName, PlanetProfile profile, Long seed) {
        if (new File(Bukkit.getWorldContainer(), worldName).isDirectory()) {
            plugin.getLogger().warning("[PlanetGenerator] Refused to create '" + worldName
                    + "': a folder with that name already exists on disk.");
            return null;
        }
        try {
            long actualSeed = seed != null ? seed
                    : java.util.concurrent.ThreadLocalRandom.current().nextLong();
            PlanetChunkGenerator generator = new PlanetChunkGenerator(profile);
            WorldCreator creator = new WorldCreator(worldName)
                    .environment(World.Environment.NORMAL)
                    .generator(generator)
                    .biomeProvider(new PlanetBiomeProvider(profile, new BiomeEngine(profile)))
                    .seed(actualSeed)
                    .generateStructures(false)
                    .type(org.bukkit.WorldType.NORMAL);
            World world = creator.createWorld();
            if (world == null) {
                plugin.getLogger().warning("[PlanetGenerator] The server did not hand back a world for '"
                        + worldName + "'.");
                return null;
            }
            plugin.getLogger().info("[PlanetGenerator] Created '" + worldName + "' from profile '"
                    + profile.id() + "' with seed " + actualSeed + ".");
            return world;
        } catch (RuntimeException | LinkageError ex) {
            plugin.getLogger().log(Level.WARNING, "[PlanetGenerator] Failed to create the world '"
                    + worldName + "' from profile '" + profile.id() + "'", ex);
            return null;
        }
    }

    /** The generator a world was created with, or null when it isn't a profile world. */
    public PlanetChunkGenerator generatorOf(World world) {
        ChunkGenerator generator = world.getGenerator();
        return generator instanceof PlanetChunkGenerator planet ? planet : null;
    }

    // ── Planet identity ──────────────────────────────────────────────────

    /**
     * Writes a planet's atmosphere and effects into the sections the plugin already
     * reads — {@code sky-colors}, {@code particles}, {@code effects},
     * {@code weather-lock}, {@code atmospheres} and {@code music} — so a procedural
     * planet looks and feels exactly like a hand-made one, and every existing
     * command and menu keeps working on it.
     */
    public void applyIdentity(String worldName, PlanetProfile profile, FileConfiguration config) {
        AtmosphereSettings atmosphere = profile.atmosphere();
        if (!profile.applyAtmosphere() || !atmosphere.any()) {
            return;
        }
        if (atmosphere.skyColor() != null) {
            config.set("sky-colors." + worldName + ".sky", atmosphere.skyColor());
            config.set("sky-colors." + worldName + ".fog",
                    atmosphere.fogColor() == null ? atmosphere.skyColor() : atmosphere.fogColor());
            if (atmosphere.waterColor() != null) {
                config.set("sky-colors." + worldName + ".water", atmosphere.waterColor());
            }
            config.set("sky-colors." + worldName + ".density",
                    atmosphere.hazeDensity() > 0 ? atmosphere.hazeDensity() : 8);
        } else if (atmosphere.hazeDensity() > 0) {
            config.set("sky-colors." + worldName + ".sky", "#FFFFFF");
            config.set("sky-colors." + worldName + ".density", atmosphere.hazeDensity());
        }
        // The first particle entry becomes the planet's named ambient effect, which
        // is what the existing particle system plays. The rest are recorded so a
        // future multi-effect renderer can pick them up without a config change.
        if (!atmosphere.particles().isEmpty()) {
            AtmosphereSettings.Particle primary = atmosphere.particles().get(0);
            config.set("particles." + worldName + ".effect", effectName(primary.type()));
            config.set("particles." + worldName + ".density", (int) Math.round(primary.density()));
            config.set("planets-procedural." + worldName + ".particles",
                    atmosphere.particles().stream().map(AtmosphereSettings.Particle::describe).toList());
        }
        if (atmosphere.weather() != null) {
            config.set("weather-lock." + worldName, atmosphere.weather());
        }
        for (Map.Entry<String, Integer> effect : atmosphere.effects().entrySet()) {
            config.set("effects." + worldName + "." + effect.getKey(), effect.getValue());
        }
        if (atmosphere.damagePerSecond() > 0) {
            config.set("atmospheres." + worldName + ".damage", atmosphere.damagePerSecond());
            config.set("atmospheres." + worldName + ".helmets", atmosphere.helmets());
        }
        if (!atmosphere.musicTracks().isEmpty()) {
            config.set("music.tracks." + worldName, atmosphere.musicTracks());
        }
    }

    /** Forgets everything a procedural planet wrote into config.yml. */
    public void clearIdentity(String worldName, FileConfiguration config) {
        config.set("planets-procedural." + worldName, null);
    }

    /**
     * Maps a profile's particle name onto one of the plugin's own ambient effects.
     * A profile may name a real Bukkit particle instead, in which case the name is
     * passed through and the particle system reports it as unknown rather than
     * silently dropping the planet's atmosphere.
     */
    private static String effectName(String type) {
        String name = type == null ? "dust" : type.trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "dust", "end_rod", "glow" -> "fireflies";
            case "small_flame", "flame" -> "embers";
            case "snowflake", "snow" -> "snow";
            case "ash", "white_ash" -> "ash";
            case "electric_spark", "spark" -> "sparks";
            case "bubble_pop", "bubble" -> "bubbles";
            case "enchant", "witch" -> "magic";
            case "cloud", "campfire_cosy_smoke" -> "smoke";
            case "sculk_soul" -> "sculk";
            case "dripping_lava", "lava" -> "lava";
            default -> name;
        };
    }

    // ── Commands ─────────────────────────────────────────────────────────

    /** {@code /planets list}: every profile with its style and biome count. */
    public List<String> listLines() {
        List<String> lines = new ArrayList<>();
        List<PlanetProfile> profiles = registry.all();
        if (profiles.isEmpty()) {
            lines.add("No planet profiles loaded. Put .yml files in "
                    + folder().getPath() + " and run /planets reload.");
            return lines;
        }
        lines.add("Planet profiles (" + profiles.size() + "):");
        for (PlanetProfile profile : profiles) {
            lines.add("  " + profile.id() + " — " + profile.summary());
        }
        lines.add("Create one with: /planets create <name> <profile>");
        return lines;
    }

    /** {@code /planets profile <id>}: the full settings dump. */
    public List<String> describe(PlanetProfile profile) {
        List<String> lines = new ArrayList<>(profile.describe());
        lines.add("terrain:");
        for (String line : profile.terrain().describe()) {
            lines.add("  " + line);
        }
        lines.add("biomes:");
        for (BiomeProfile biome : profile.biomes().values()) {
            lines.addAll(biome.describe().stream().map(line -> "  " + line).toList());
        }
        lines.add("caves:");
        for (String line : profile.caves().describe()) {
            lines.add("  " + line);
        }
        lines.add("structures:");
        for (String line : profile.structures().describe()) {
            lines.add("  " + line);
        }
        lines.add("decorations:");
        for (String line : profile.decorations().describe()) {
            lines.add("  " + line);
        }
        lines.add("atmosphere:");
        for (String line : profile.atmosphere().describe()) {
            lines.add("  " + line);
        }
        return lines;
    }

    /** {@code /planets debug <planet>}: what a world is actually running with. */
    public List<String> debug(World world) {
        List<String> lines = new ArrayList<>();
        lines.add("World: " + world.getName());
        lines.add("  seed: " + world.getSeed());
        lines.add("  environment: " + world.getEnvironment());
        lines.add("  height: " + world.getMinHeight() + ".." + world.getMaxHeight());
        PlanetChunkGenerator generator = generatorOf(world);
        if (generator == null) {
            String profileId = registry.profileIdOf(world.getName());
            lines.add("  generator: " + (profileId == null ? "vanilla" : "vanilla (profile '"
                    + profileId + "' is recorded but this world was not created with it)"));
            lines.add("  hint: a world only gets the engine's generator at creation time; "
                    + "use /planets create <name> <profile> for a new one.");
            return lines;
        }
        PlanetProfile profile = generator.profile();
        GenerationContext context = generator.context(world);
        lines.add("  generator: PlanetChunkGenerator (" + profile.id() + ")");
        lines.add("  style: " + profile.style() + " -> " + TerrainModules.forStyle(profile.style())
                .getClass().getSimpleName());
        lines.add("  profile file: " + profile.sourceFile());
        lines.add("  sea level: " + context.seaLevel()
                + (context.liquid() == null ? ", no liquid" : ", " + context.liquid()));
        lines.add("  height layers: " + context.layers().size());
        for (GenerationContext.Layer layer : context.layers()) {
            lines.add("    " + layer.id() + ": " + layer.sampler().settings().describe());
        }
        lines.add("  biome maps: temperature / humidity / weirdness (spatially coherent)");
        lines.add("  caves: " + (profile.caves().carves() ? "on" : "off")
                + ", structures: " + (profile.structures().any() ? "on" : "off")
                + ", decorations: " + (profile.decorations().any() ? "on" : "off"));
        lines.add("  sample terrain at (0,0): y " + TerrainProbe.surfaceY(context, 0, 0)
                + ", biome " + generator.planetBiomeAt(context, 0, 0, 0));
        lines.add("  sample terrain at (500,-500): y " + TerrainProbe.surfaceY(context, 500, -500)
                + ", biome " + generator.planetBiomeAt(context, 500, -500, 0));
        return lines;
    }

    /** {@code /planets debug profiles}: what the engine knows how to build. */
    public List<String> debugEngine() {
        List<String> lines = new ArrayList<>();
        lines.add("Generation styles: " + TerrainModules.describe());
        lines.add("Structure types: " + String.join(", ", StructureTypes.ids()));
        lines.add("Decoration types: " + String.join(", ", DecorationTypes.ids()));
        lines.add("Profiles folder: " + folder().getPath());
        lines.add("Loaded profiles: " + (registry.isEmpty() ? "none" : String.join(", ", registry.ids())));
        return lines;
    }

    /** Validates a raw profile section without loading it, for the reload message. */
    public List<String> validate(File file) {
        List<String> warnings = new ArrayList<>();
        ConfigurationSection section = PlanetProfileLoader.raw(file);
        if (section == null) {
            warnings.add("The file could not be parsed as YAML.");
            return warnings;
        }
        String id = file.getName().replace(".yml", "");
        PlanetProfile.from(id, section, file.getName(), warnings::add);
        return warnings;
    }

    /** The icon material a profile asks for, or a sensible fallback. */
    public Material iconOf(PlanetProfile profile) {
        Material material = Palette.resolve(profile.icon(), Material.GRASS_BLOCK);
        return material == null ? Material.GRASS_BLOCK : material;
    }
}
