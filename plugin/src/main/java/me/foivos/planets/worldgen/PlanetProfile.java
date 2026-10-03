package me.foivos.planets.worldgen;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One planet, described entirely by data.
 * <p>
 * A profile is the unit an admin writes and the engine reads: pick a
 * {@link GenerationStyle}, tune the terrain, say which biomes exist, what grows,
 * what gets built and how the sky looks. Nothing in here is planet-specific
 * code — adding a planet means adding a file.
 * <p>
 * Profiles are immutable and shared across threads once loaded, so the generator
 * can hand the same instance to every chunk without copying anything.
 *
 * @param id             profile id, also the default world seed component
 * @param displayName    name shown in menus and command output
 * @param description    one-line description for the admin picker
 * @param icon           menu material name
 * @param style          the generation philosophy
 * @param seed           {@code "random"} (a persisted seed is generated once) or a number
 * @param seedSalt       extra salt mixed into the seed, for two planets from one profile
 * @param palette        named materials
 * @param terrain        the height stack and its features
 * @param biomes         planet biome profiles, by id
 * @param defaultBiome   planet biome used when nothing else fits
 * @param surface        planet-wide surface layers (used when a biome has none)
 * @param caves          the underground
 * @param structures     structures and landmarks
 * @param decorations    vegetation and scenery
 * @param atmosphere     sky, weather, particles, effects
 * @param features       feature switches ({@code features.dinosaurs: true})
 * @param gameplay       gameplay switches ({@code gameplay.custom-mob-spawning: true})
 * @param integrations   optional integration switches, e.g. a future client mod
 * @param resourcePack   an optional planet resource pack
 * @param applyAtmosphere whether the plugin writes this planet's atmosphere into
 *                       the existing config.yml sections (sky-colors, particles,
 *                       effects, weather-lock) so every system already built for
 *                       planets picks it up automatically
 * @param sourceFile     where it was loaded from, for error messages
 */
public record PlanetProfile(
        String id,
        String displayName,
        String description,
        String icon,
        GenerationStyle style,
        String seed,
        long seedSalt,
        Palette palette,
        TerrainSettings terrain,
        Map<String, BiomeProfile> biomes,
        String defaultBiome,
        List<BiomeProfile.SurfaceLayer> surface,
        CaveSettings caves,
        StructureSettings structures,
        DecorationSettings decorations,                AtmosphereSettings atmosphere,
                Map<String, Object> layout,
                Map<String, Boolean> features,
                Map<String, Boolean> gameplay,
                Map<String, Boolean> integrations,
                ResourcePack resourcePack,
                boolean applyAtmosphere,
                String sourceFile) {

    /** A numeric setting from the profile's {@code layout:} section. */
    public int layoutInt(String key, int fallback) {
        Object value = layout.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** A numeric setting from the profile's {@code layout:} section. */
    public double layoutDouble(String key, double fallback) {
        Object value = layout.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    /** A boolean setting from the profile's {@code layout:} section. */
    public boolean layoutFlag(String key, boolean fallback) {
        Object value = layout.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    /** A string setting from the profile's {@code layout:} section. */
    public String layoutText(String key, String fallback) {
        Object value = layout.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * An optional planet resource pack.
     * <p>
     * Client-side and therefore honest about its limits: a resource pack cannot be
     * swapped the instant a player steps onto a planet on these versions, so the
     * plugin sends it on join and when a player lands on the planet, and leaves the
     * server-wide pack in config.yml as the fallback.
     *
     * @param enabled  whether this planet has a pack at all
     * @param url      the download URL
     * @param sha1     the pack hash (optional but strongly recommended)
     * @param required whether the client must accept it
     * @param prompt   the message shown when offering it
     */
    public record ResourcePack(boolean enabled, String url, String sha1, boolean required, String prompt) {

        public static ResourcePack none() {
            return new ResourcePack(false, "", "", false, "");
        }

        public static ResourcePack from(ConfigurationSection section, Consumer<String> warn) {
            if (section == null) {
                return none();
            }
            boolean enabled = section.getBoolean("enabled", false);
            String url = section.getString("url", "");
            if (enabled && (url == null || url.isBlank())) {
                warn.accept("client.resource-pack is enabled but has no url — ignored");
                return none();
            }
            String sha1 = section.getString("sha1", "");
            if (enabled && (sha1 == null || sha1.isBlank())) {
                warn.accept("client.resource-pack has no sha1 — clients may not cache it correctly");
                sha1 = "";
            }
            return new ResourcePack(enabled, url, sha1, section.getBoolean("required", false),
                    section.getString("prompt", "This planet uses its own textures"));
        }

        public String describe() {
            return enabled ? url + (required ? " (required)" : " (optional)") : "none";
        }
    }

    /** The planet biome for an id, or null. */
    public BiomeProfile biome(String biomeId) {
        return biomeId == null ? null : biomes.get(biomeId.toLowerCase(Locale.ROOT));
    }

    /** The planet biome used when nothing matched. */
    public BiomeProfile fallbackBiome() {
        BiomeProfile fallback = biomes.get(defaultBiome);
        if (fallback != null) {
            return fallback;
        }
        for (BiomeProfile biome : biomes.values()) {
            return biome;
        }
        return BiomeProfile.fallback("default", "plains");
    }

    /** Whether a feature switch is on. Unknown switches are off. */
    public boolean feature(String name) {
        return Boolean.TRUE.equals(features.get(name == null ? "" : name.toLowerCase(Locale.ROOT)));
    }

    public boolean gameplay(String name) {
        return Boolean.TRUE.equals(gameplay.get(name == null ? "" : name.toLowerCase(Locale.ROOT)));
    }

    public boolean integration(String name) {
        return Boolean.TRUE.equals(integrations.get(name == null ? "" : name.toLowerCase(Locale.ROOT)));
    }

    /** The structure profiles that are not landmarks. */
    public List<StructureSettings.StructureProfile> structuresOnly() {
        return structures.types();
    }

    /** A short one-line summary for the admin picker and command output. */
    public String summary() {
        return displayName + " — " + style.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + ", " + biomes.size() + " biome(s), height " + terrain.minHeight() + ".." + terrain.maxHeight();
    }

    /** Debug lines for {@code /planets profile <id>}. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("id: " + id + " (" + displayName + ")");
        lines.add("style: " + style + ", seed: " + seed + ", salt: " + seedSalt);
        lines.add("biomes: " + String.join(", ", biomes.keySet()) + " (default " + defaultBiome + ")");
        lines.add("atmosphere applied to config.yml: " + applyAtmosphere);
        lines.add("resource pack: " + resourcePack.describe());
        if (!features.isEmpty()) {
            lines.add("features: " + features);
        }
        if (!gameplay.isEmpty()) {
            lines.add("gameplay: " + gameplay);
        }
        if (!integrations.isEmpty()) {
            lines.add("integrations: " + integrations);
        }
        return lines;
    }

    // ── Config parsing ───────────────────────────────────────────────────

    /** Reads a {@code planets/<id>.yml} file into a profile, reporting problems through {@code warn}. */
    public static PlanetProfile from(String id, ConfigurationSection root, String sourceFile,
                                     Consumer<String> warn) {
        GenerationStyle style = GenerationStyle.parse(root.getString("style",
                root.getString("generator", root.getString("archetype"))));
        if (style == null) {
            String requested = root.getString("style", root.getString("generator"));
            if (requested != null) {
                warn.accept("style '" + requested + "' is not a generation style — using NATURAL ("
                        + "try natural, mountain_world, ocean_world, archipelago, floating_islands, "
                        + "cavern_world, void_world, maze_world, backrooms, superflat_custom)");
            }
            style = GenerationStyle.NATURAL;
        }

        ConfigurationSection generation = root.getConfigurationSection("generation");
        ConfigurationSection terrainSection = firstSection(root, generation, "terrain");
        ConfigurationSection biomeSection = firstSection(root, generation, "biomes");
        ConfigurationSection caveSection = firstSection(root, generation, "caves");
        ConfigurationSection structureSection = firstSection(root, generation, "structures");
        ConfigurationSection decorationSection = firstSection(root, generation,
                "decorations", "vegetation");
        ConfigurationSection atmosphereSection = root.getConfigurationSection("atmosphere");

        TerrainSettings terrain = TerrainSettings.from(terrainSection, styleTerrain(style), warn);

        Map<String, BiomeProfile> biomes = new LinkedHashMap<>();
        if (biomeSection != null) {
            for (String biomeId : biomeSection.getKeys(false)) {
                ConfigurationSection data = biomeSection.getConfigurationSection(biomeId);
                if (data == null) {
                    warn.accept("biomes." + biomeId + " is not a biome section — skipped");
                    continue;
                }
                biomes.put(biomeId.toLowerCase(Locale.ROOT),
                        BiomeProfile.from(biomeId.toLowerCase(Locale.ROOT), data, warn));
            }
        }
        String defaultBiome = root.getString("default-biome", "default").toLowerCase(Locale.ROOT);
        if (biomes.isEmpty()) {
            warn.accept("no biomes defined — the planet will be a single plains biome");
            biomes.put("default", BiomeProfile.fallback("default", defaultBiome.equals("default")
                    ? "plains" : defaultBiome));
        } else if (!biomes.containsKey(defaultBiome)) {
            String firstId = biomes.keySet().iterator().next();
            warn.accept("default-biome '" + defaultBiome + "' is not one of the defined biomes ("
                    + String.join(", ", biomes.keySet()) + ") — using " + firstId);
            defaultBiome = firstId;
        }

        List<BiomeProfile.SurfaceLayer> surface = new ArrayList<>();
        ConfigurationSection surfaceSection = firstSection(root, generation, "surface");
        if (surfaceSection != null) {
            for (Object entry : BiomeProfile.list(surfaceSection, "layers")) {
                BiomeProfile.SurfaceLayer layer = BiomeProfile.readLayer(entry, warn, "surface");
                if (layer != null) {
                    surface.add(layer);
                }
            }
            if (surface.isEmpty()) {
                // "surface: [GRASS_BLOCK:1, DIRT:4]" without the "layers:" key.
                for (Object entry : BiomeProfile.list(surfaceSection, "stack")) {
                    BiomeProfile.SurfaceLayer layer = BiomeProfile.readLayer(entry, warn, "surface");
                    if (layer != null) {
                        surface.add(layer);
                    }
                }
            }
        }
        if (surface.isEmpty()) {
            surface = defaultSurface(style, terrain);
        }

        // Structures and decorations live either under generation/ or at the top
        // level; both are read, generation/ wins when both are present.
        StructureSettings structures = StructureSettings.from(structureSection,
                StructureSettings.none(), warn);
        DecorationSettings decorations = DecorationSettings.from(decorationSection,
                DecorationSettings.none(), warn);

        AtmosphereSettings atmosphere = AtmosphereSettings.from(atmosphereSection,
                AtmosphereSettings.none(), warn);
        // A top-level "particles:" section is merged in on top, so a profile can
        // keep its effects next to everything else if it prefers.
        ConfigurationSection particleSection = firstSection(root, generation, "particles");
        if (particleSection != null && particleSection != atmosphereSection) {
            atmosphere = AtmosphereSettings.from(particleSection, atmosphere, warn);
        }

        Map<String, Object> layout = new LinkedHashMap<>();
        ConfigurationSection layoutSection = root.getConfigurationSection("layout");
        if (layoutSection != null) {
            for (String key : layoutSection.getKeys(false)) {
                layout.put(key.toLowerCase(Locale.ROOT), layoutSection.get(key));
            }
        }

        Map<String, Boolean> features = switches(root.getConfigurationSection("features"));
        Map<String, Boolean> gameplay = switches(root.getConfigurationSection("gameplay"));
        Map<String, Boolean> integrations = switches(root.getConfigurationSection("integrations"));
        ConfigurationSection client = root.getConfigurationSection("client");
        ResourcePack pack = ResourcePack.from(client == null ? null
                : client.getConfigurationSection("resource-pack"), warn);
        if (!pack.enabled() && client != null && client.isConfigurationSection("resource-pack")) {
            warn.accept("client.resource-pack has enabled: false — this planet will use the "
                    + "server-wide pack from config.yml");
        }

        String seed = root.getString("seed", "random");
        if (seed != null && !seed.equalsIgnoreCase("random")) {
            try {
                Long.parseLong(seed);
            } catch (NumberFormatException ex) {
                warn.accept("seed '" + seed + "' is neither 'random' nor a number — using random");
                seed = "random";
            }
        }

        return new PlanetProfile(
                id.toLowerCase(Locale.ROOT),
                root.getString("display-name", root.getString("name", prettify(id))),
                root.getString("description", "A custom planet from " + sourceFile),
                root.getString("icon", "GRASS_BLOCK"),
                style,
                seed,
                root.getLong("seed-salt", 0L),
                Palette.from(root.getConfigurationSection("palette")),
                terrain,
                Map.copyOf(biomes),
                defaultBiome,
                List.copyOf(surface),
                CaveSettings.from(caveSection, styleCaves(style, terrain), warn),
                structures,
                decorations,
                atmosphere,
                Map.copyOf(layout),
                features, gameplay, integrations, pack,
                root.getBoolean("apply-atmosphere", true),
                sourceFile);
    }

    private static ConfigurationSection firstSection(ConfigurationSection root,
                                                     ConfigurationSection generation,
                                                     String key, String... alternatives) {
        if (generation != null) {
            ConfigurationSection found = generation.getConfigurationSection(key);
            if (found == null) {
                for (String alternative : alternatives) {
                    found = generation.getConfigurationSection(alternative);
                    if (found != null) {
                        break;
                    }
                }
            }
            if (found != null) {
                return found;
            }
        }
        ConfigurationSection found = root.getConfigurationSection(key);
        if (found == null) {
            for (String alternative : alternatives) {
                found = root.getConfigurationSection(alternative);
                if (found != null) {
                    break;
                }
            }
        }
        return found;
    }

    /** A sensible terrain baseline per style, before the profile's own overrides. */
    static TerrainSettings styleTerrain(GenerationStyle style) {
        TerrainSettings natural = TerrainSettings.natural();
        return switch (style) {
            case NATURAL -> natural;
            case MOUNTAIN_WORLD -> new TerrainSettings(96, 40, 300, 70, "water", 1.35, 0.85,
                    natural.layers(),
                    TerrainSettings.Feature.of(90, 300, 200, 0.85),
                    TerrainSettings.Feature.of(12, 240, 160, 0.25),
                    TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.of(9, 150, 24, 0.7),
                    TerrainSettings.Feature.of(24, 420, 60, 0.5),
                    TerrainSettings.IslandSettings.off(),
                    TerrainSettings.FloatingIslandSettings.off(),
                    0, 0, 4, 34);
            case OCEAN_WORLD -> new TerrainSettings(48, 18, 180, 66, "water", 0.8, 1.2,
                    List.of(new TerrainSettings.Layer("continentalness",
                                    NoiseSettings.defaults(NoiseType.PERLIN, 700, 14)),
                            new TerrainSettings.Layer("detail",
                                    NoiseSettings.defaults(NoiseType.PERLIN, 120, 5))),
                    TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.of(6, 300, 200, 0.3),
                    TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(),
                    new TerrainSettings.IslandSettings(true, 0.0022, 110, 30, 0.6),
                    TerrainSettings.FloatingIslandSettings.off(),
                    0, 0, 3, 50);
            case ARCHIPELAGO -> new TerrainSettings(50, 16, 170, 63, "water", 0.9, 1.1,
                    List.of(new TerrainSettings.Layer("continentalness",
                                    NoiseSettings.defaults(NoiseType.PERLIN, 320, 18)),
                            new TerrainSettings.Layer("detail",
                                    NoiseSettings.defaults(NoiseType.PERLIN, 90, 6))),
                    TerrainSettings.Feature.of(28, 200, 120, 0.6),
                    TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.of(4, 120, 18, 0.5),
                    TerrainSettings.Feature.off(),
                    new TerrainSettings.IslandSettings(true, 0.006, 70, 22, 0.5),
                    TerrainSettings.FloatingIslandSettings.off(),
                    0, 0, 4, 60);
            case FLOATING_ISLANDS -> new TerrainSettings(90, 20, 260, 0, null, 1.0, 1.0,
                    List.of(), TerrainSettings.Feature.off(), TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(), TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(), TerrainSettings.IslandSettings.off(),
                    new TerrainSettings.FloatingIslandSettings(true, 0.035, 8, 26, 80, 200, 0.06, 6,
                            "stone", "grass_block", "deepslate"),
                    0, 0, 0, 40);
            case CAVERN_WORLD -> new TerrainSettings(64, 0, 220, 0, null, 1.0, 1.0,
                    List.of(new TerrainSettings.Layer("continentalness",
                            NoiseSettings.defaults(NoiseType.PERLIN, 420, 10))),
                    TerrainSettings.Feature.off(), TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(), TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(), TerrainSettings.IslandSettings.off(),
                    TerrainSettings.FloatingIslandSettings.off(), 0, 0, 0, 40);
            case VOID_WORLD -> new TerrainSettings(0, 0, 1, 0, null, 0, 1,
                    List.of(), TerrainSettings.Feature.off(), TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(), TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(), TerrainSettings.IslandSettings.off(),
                    TerrainSettings.FloatingIslandSettings.off(), 0, 0, 0, 1);
            case MAZE_WORLD, BACKROOMS -> new TerrainSettings(24, 0, 40, 0, null, 0, 1,
                    List.of(), TerrainSettings.Feature.off(), TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(), TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(), TerrainSettings.IslandSettings.off(),
                    TerrainSettings.FloatingIslandSettings.off(), 0, 0, 0, 1);
            case SUPERFLAT_CUSTOM -> new TerrainSettings(62, 0, 120, 0, null, 0, 1,
                    List.of(), TerrainSettings.Feature.off(), TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(), TerrainSettings.Feature.off(),
                    TerrainSettings.Feature.off(), TerrainSettings.IslandSettings.off(),
                    TerrainSettings.FloatingIslandSettings.off(), 0, 0, 0, 1);
            case CUSTOM -> natural;
        };
    }

    /** Caves default per style: architectural worlds have none, cavern worlds have plenty. */
    static CaveSettings styleCaves(GenerationStyle style, TerrainSettings terrain) {
        int floor = Math.max(1, terrain.minHeight());
        int ceiling = Math.max(floor + 12, terrain.clampedBase() + 24);
        return switch (style) {
            case VOID_WORLD, SUPERFLAT_CUSTOM, MAZE_WORLD, BACKROOMS -> CaveSettings.none();
            case CAVERN_WORLD -> new CaveSettings(true,
                    CaveSettings.System.of(0.65, 120, 1.4, floor, ceiling),
                    CaveSettings.System.of(0.12, 240, 2.2, floor, ceiling - 20),
                    CaveSettings.Chambers.off(), CaveSettings.Chambers.off(), true, List.of());
            case FLOATING_ISLANDS -> CaveSettings.none();
            default -> CaveSettings.standard(floor, ceiling);
        };
    }

    /** A plain surface for styles that don't define one, so nothing is ever bare rock. */
    static List<BiomeProfile.SurfaceLayer> defaultSurface(GenerationStyle style, TerrainSettings terrain) {
        return switch (style) {
            case BACKROOMS, MAZE_WORLD -> List.of(
                    BiomeProfile.SurfaceLayer.plain("yellow_terracotta", 3),
                    BiomeProfile.SurfaceLayer.plain("smooth_sandstone", 6));
            case VOID_WORLD -> List.of();
            default -> List.of(
                    BiomeProfile.SurfaceLayer.plain("grass_block", 1),
                    BiomeProfile.SurfaceLayer.plain("dirt", 3),
                    BiomeProfile.SurfaceLayer.plain("stone", 24),
                    BiomeProfile.SurfaceLayer.plain("deepslate", 999));
        };
    }

    private static Map<String, Boolean> switches(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            values.put(key.toLowerCase(Locale.ROOT), section.getBoolean(key, false));
        }
        return Map.copyOf(values);
    }

    /** {@code "prehistoric_world"} → {@code "Prehistoric World"}. */
    static String prettify(String id) {
        StringBuilder text = new StringBuilder();
        for (String word : id.replace('-', '_').split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return text.length() == 0 ? id : text.toString();
    }
}
