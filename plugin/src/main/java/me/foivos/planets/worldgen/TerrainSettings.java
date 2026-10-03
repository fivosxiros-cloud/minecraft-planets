package me.foivos.planets.worldgen;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Everything about a planet's shape — the largest part of a planet profile and
 * the part that decides whether two planets with the same block palette still
 * look like different worlds.
 * <p>
 * The height of every column is built as a sum of independent layers:
 *
 * <pre>
 * height = base-height
 *        + Σ (layer noise × layer amplitude)
 *        + mountains − valleys − canyons ± cliffs + plateaus
 *        + islands + detail
 * </pre>
 *
 * Each term is its own seeded noise field, so a planet can have broad
 * continental shapes, a mountain range on top of them and a canyon cut through
 * the middle without any of the three interfering with the others.
 *
 * @param baseHeight           the sea-level-ish baseline of the noise stack
 * @param minHeight            hard floor the shaper clamps to
 * @param maxHeight            hard ceiling the shaper clamps to
 * @param seaLevel             height the liquid fills up to
 * @param liquid               block the oceans are filled with (palette key or name)
 * @param verticalScale        multiplies every height contribution
 * @param horizontalScale      stretches the world horizontally (bigger = wider features)
 * @param layers               named noise layers added to the base height
 * @param mountains            ridged mountain ranges
 * @param valleys              broad smoothing depressions
 * @param canyons              narrow deep cuts
 * @param cliffs               stepped scarps
 * @param plateaus             flat-topped mesas
 * @param islands              circular island falloff, for ocean and archipelago worlds
 * @param floatingIslands      disconnected terrain hanging in the sky
 * @param distortionStrength   domain warp applied to the whole stack, in blocks
 * @param distortionFrequency  how quickly that warp changes, per block
 * @param detailStrength       extra small-scale roughness on the final height
 * @param detailFrequency      how small that roughness is
 */
public record TerrainSettings(
        int baseHeight,
        int minHeight,
        int maxHeight,
        int seaLevel,
        String liquid,
        double verticalScale,
        double horizontalScale,
        List<Layer> layers,
        Feature mountains,
        Feature valleys,
        Feature canyons,
        Feature cliffs,
        Feature plateaus,
        IslandSettings islands,
        FloatingIslandSettings floatingIslands,
        double distortionStrength,
        double distortionFrequency,
        double detailStrength,
        double detailFrequency) {

    /** A named noise layer of the height stack. */
    public record Layer(String id, NoiseSettings noise) {
    }

    /**
     * A terrain feature. The same five knobs describe every feature because they
     * all work the same way: a noise field, a strength in blocks, and a width
     * over which the feature fades in.
     *
     * @param enabled   whether the feature runs at all
     * @param strength  how many blocks tall/deep it is
     * @param scale     feature size in blocks
     * @param width     the band over which it fades in (or the step size for cliffs)
     * @param sharpness 0 = soft and rounded, 1 = hard-edged
     */
    public record Feature(boolean enabled, double strength, double scale, double width, double sharpness) {

        public static Feature off() {
            return new Feature(false, 0, 100, 50, 0);
        }

        public static Feature of(double strength, double scale, double width, double sharpness) {
            return new Feature(true, strength, scale, width, sharpness);
        }

        public Feature withStrength(double newStrength) {
            return new Feature(newStrength > 0, newStrength, scale, width, sharpness);
        }

        public static Feature from(ConfigurationSection section, Feature fallback) {
            if (section == null) {
                return fallback;
            }
            boolean enabled = section.getBoolean("enabled", fallback.enabled());
            double strength = Math.max(0, section.getDouble("strength",
                    section.getDouble("height", section.getDouble("depth", fallback.strength()))));
            double scale = fallback.scale();
            if (section.isSet("scale")) {
                scale = section.getDouble("scale", scale);
            } else if (section.isSet("frequency")) {
                double frequency = section.getDouble("frequency", 1.0 / scale);
                scale = frequency > 0 ? 1.0 / frequency : scale;
            }
            double width = Math.max(1, section.getDouble("width", fallback.width()));
            double sharpness = clamp(section.getDouble("sharpness", fallback.sharpness()), 0, 1);
            return new Feature(enabled && strength > 0, strength, Math.max(1, scale), width, sharpness);
        }
    }

    /** Island falloff for ocean/archipelago worlds. */
    public record IslandSettings(boolean enabled, double frequency, double size, int height, double falloff) {

        public static IslandSettings off() {
            return new IslandSettings(false, 0.0015, 90, 18, 0.55);
        }

        public static IslandSettings from(ConfigurationSection section, IslandSettings fallback) {
            if (section == null) {
                return fallback;
            }
            return new IslandSettings(
                    section.getBoolean("enabled", fallback.enabled()),
                    Math.max(1e-5, section.getDouble("frequency", fallback.frequency())),
                    Math.max(8, section.getDouble("size", fallback.size())),
                    Math.max(1, section.getInt("height", fallback.height())),
                    clamp(section.getDouble("falloff", fallback.falloff()), 0.05, 0.95));
        }
    }

    /**
     * Terrain that hangs in the sky. Each blob is a 3D noise density field
     * thresholded around its own centre, so the top is bumpy, the underside is a
     * tapered root, and there is genuine air below it.
     */
    public record FloatingIslandSettings(
            boolean enabled,
            double density,
            int minSize,
            int maxSize,
            int minY,
            int maxY,
            double noiseScale,
            int undersideDepth,
            String core,
            String shell,
            String underside) {

        public static FloatingIslandSettings off() {
            return new FloatingIslandSettings(false, 0.02, 6, 22, 90, 175, 0.07, 5,
                    "stone", "grass", "deepslate");
        }

        public static FloatingIslandSettings from(ConfigurationSection section,
                                                  FloatingIslandSettings fallback) {
            if (section == null) {
                return fallback;
            }
            return new FloatingIslandSettings(
                    section.getBoolean("enabled", fallback.enabled()),
                    clamp(section.getDouble("density", fallback.density()), 0, 1),
                    Math.max(2, section.getInt("min-size", fallback.minSize())),
                    Math.max(3, section.getInt("max-size", Math.max(fallback.maxSize(),
                            section.getInt("min-size", fallback.minSize()) + 1))),
                    section.getInt("min-y", fallback.minY()),
                    section.getInt("max-y", fallback.maxY()),
                    Math.max(1e-3, section.getDouble("noise-scale", fallback.noiseScale())),
                    Math.max(0, section.getInt("underside-depth", fallback.undersideDepth())),
                    section.getString("core", fallback.core()),
                    section.getString("shell", fallback.shell()),
                    section.getString("underside", fallback.underside()));
        }

        public int spanY() {
            return Math.max(1, maxY - minY);
        }
    }

    /** A reasonable natural world: gentle continents, real mountains, shallow seas. */
    public static TerrainSettings natural() {
        List<Layer> layers = new ArrayList<>();
        layers.add(new Layer("continentalness", NoiseSettings.defaults(NoiseType.PERLIN, 480, 26)));
        layers.add(new Layer("erosion", NoiseSettings.defaults(NoiseType.PERLIN, 220, 14)));
        layers.add(new Layer("weirdness", NoiseSettings.defaults(NoiseType.PERLIN, 150, 8)));
        return new TerrainSettings(70, 24, 250, 62, "water", 1.0, 1.0, layers,
                Feature.of(46, 340, 240, 0.7),   // mountains
                Feature.of(10, 260, 180, 0.2),   // valleys
                Feature.off(),                   // canyons
                Feature.of(5, 180, 20, 0.6),     // cliffs
                Feature.off(),                   // plateaus
                IslandSettings.off(),
                FloatingIslandSettings.off(),
                0, 0, 3, 40);
    }

    /** The midpoint of the configured height band, clamped inside the world. */
    public int clampedBase() {
        return Math.max(minHeight + 4, Math.min(maxHeight - 8, baseHeight));
    }

    /** Human-readable summary lines for the debug command. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("height " + minHeight + ".." + maxHeight + " (base " + baseHeight
                + ", sea " + seaLevel + (liquid == null ? ", no liquid" : ", " + liquid + ")"));
        lines.add("scale v" + verticalScale + " h" + horizontalScale
                + ", detail " + detailStrength + "@" + detailFrequency);
        StringBuilder features = new StringBuilder();
        appendFeature(features, "mountains", mountains);
        appendFeature(features, "valleys", valleys);
        appendFeature(features, "canyons", canyons);
        appendFeature(features, "cliffs", cliffs);
        appendFeature(features, "plateaus", plateaus);
        if (islands.enabled()) {
            features.append(features.length() > 0 ? ", " : "").append("islands ").append(islands.size());
        }
        if (floatingIslands.enabled()) {
            features.append(features.length() > 0 ? ", " : "").append("floating islands");
        }
        if (features.length() == 0) {
            features.append("no features");
        }
        lines.add("features: " + features);
        for (Layer layer : layers) {
            lines.add("layer " + layer.id() + ": " + layer.noise().describe());
        }
        return lines;
    }

    private static void appendFeature(StringBuilder text, String name, Feature feature) {
        if (!feature.enabled()) {
            return;
        }
        if (text.length() > 0) {
            text.append(", ");
        }
        text.append(name).append(' ').append(Math.round(feature.strength()))
                .append('@').append(Math.round(feature.scale()));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ── Config parsing ───────────────────────────────────────────────────

    /**
     * Reads a {@code terrain} section. Anything missing keeps the value of
     * {@code fallback}, so a profile can override three keys out of forty.
     */
    public static TerrainSettings from(ConfigurationSection section, TerrainSettings fallback,
                                      Consumer<String> warn) {
        if (section == null) {
            return fallback;
        }
        ConfigurationSection height = section.getConfigurationSection("height");
        int baseHeight = fallback.baseHeight();
        int minHeight = fallback.minHeight();
        int maxHeight = fallback.maxHeight();
        if (height != null) {
            baseHeight = height.getInt("base", height.getInt("base-height", baseHeight));
            minHeight = height.getInt("min", height.getInt("minimum", minHeight));
            maxHeight = height.getInt("max", height.getInt("maximum", maxHeight));
        } else {
            baseHeight = section.getInt("base-height", baseHeight);
            minHeight = section.getInt("min-height", minHeight);
            maxHeight = section.getInt("max-height", maxHeight);
        }
        if (minHeight >= maxHeight) {
            warn.accept("terrain.height: min (" + minHeight + ") is not below max (" + maxHeight
                    + ") — using the defaults " + fallback.minHeight() + ".." + fallback.maxHeight());
            minHeight = fallback.minHeight();
            maxHeight = fallback.maxHeight();
        }
        int seaLevel = section.getInt("sea-level", section.getInt("sea_level", fallback.seaLevel()));
        seaLevel = Math.max(minHeight, Math.min(maxHeight, seaLevel));
        String liquid = section.isSet("liquid") ? section.getString("liquid") : fallback.liquid();
        if (liquid != null && !Palette.isValidBlock(liquid)) {
            warn.accept("terrain.liquid '" + liquid + "' is not a block — the oceans will use "
                    + fallback.liquid());
            liquid = fallback.liquid();
        }
        if ("air".equalsIgnoreCase(String.valueOf(liquid))) {
            liquid = null;
        }

        List<Layer> layers = new ArrayList<>(fallback.layers());
        ConfigurationSection layerSection = section.getConfigurationSection("layers");
        if (layerSection != null) {
            layers.clear();
            for (String id : layerSection.getKeys(false)) {
                NoiseSettings defaults = NoiseSettings.defaults(NoiseType.PERLIN, 300, 0);
                layers.add(new Layer(id, NoiseSettings.from(layerSection.getConfigurationSection(id), defaults)));
            }
            if (layers.isEmpty()) {
                warn.accept("terrain.layers is empty — falling back to the standard continental/erosion layers");
                layers = new ArrayList<>(fallback.layers());
            }
        }
        for (Layer layer : layers) {
            if (layer.noise().amplitude() == 0) {
                warn.accept("terrain.layers." + layer.id() + " has no amplitude — it will not move the ground");
            }
        }

        return new TerrainSettings(
                baseHeight, minHeight, maxHeight, seaLevel, liquid,
                section.getDouble("vertical-scale", section.getDouble("vertical_scale", fallback.verticalScale())),
                section.getDouble("horizontal-scale", section.getDouble("horizontal_scale", fallback.horizontalScale())),
                List.copyOf(layers),
                Feature.from(section.getConfigurationSection("mountains"), fallback.mountains()),
                Feature.from(section.getConfigurationSection("valleys"), fallback.valleys()),
                Feature.from(section.getConfigurationSection("canyons"), fallback.canyons()),
                Feature.from(section.getConfigurationSection("cliffs"), fallback.cliffs()),
                Feature.from(section.getConfigurationSection("plateaus"), fallback.plateaus()),
                IslandSettings.from(section.getConfigurationSection("islands"), fallback.islands()),
                FloatingIslandSettings.from(section.getConfigurationSection("floating-islands"),
                        fallback.floatingIslands()),
                Math.max(0, section.getDouble("distortion-strength", section.getDouble("warping-strength",
                        section.getDouble("terrain-distortion", fallback.distortionStrength())))),
                Math.max(0, section.getDouble("distortion-frequency", section.getDouble("warping-frequency",
                        fallback.distortionFrequency()))),
                Math.max(0, section.getDouble("detail-strength", fallback.detailStrength())),
                Math.max(0.001, section.getDouble("detail-frequency", fallback.detailFrequency())));
    }
}
