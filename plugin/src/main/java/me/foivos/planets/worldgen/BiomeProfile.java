package me.foivos.planets.worldgen;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A <b>planet</b> biome — deliberately not the same thing as a Minecraft biome.
 * <p>
 * A planet biome decides what the world <em>is</em> there: which Minecraft biome
 * the client is told about (for vanilla colors, mob spawning and weather), which
 * blocks the surface is made of, how much grows on it and how likely structures
 * are. Several planet biomes may share one Minecraft biome — an alien "glass
 * wastes" and an alien "spore forest" can both report {@code the_end} while
 * looking nothing alike.
 * <p>
 * Placement is by environment, never by dice: the biome is chosen from where the
 * column sits in the planet's temperature/humidity/altitude/weirdness maps, so
 * the same biome is always in the same kind of place and regions stay coherent.
 *
 * @param id                profile id, referenced by structures and decorations
 * @param biome             the Minecraft biome reported to clients
 * @param priority          higher wins when several biomes fit a column
 * @param temperature       inclusive temperature band, 0..1
 * @param humidity          inclusive humidity band, 0..1
 * @param altitude          inclusive altitude band, in blocks
 * @param weirdness         inclusive weirdness band, -1..1 (the "unusual" dial)
 * @param surface           surface layers, top → down; empty = planet default
 * @param decorations       decoration ids allowed here; empty = planet default
 * @param vegetationDensity multiplier on decoration density, 0 disables growth
 * @param structureDensity  multiplier on structure rarity
 * @param seaLevel          optional biome-specific sea level (oceans, lava lakes)
 * @param liquid            optional biome-specific liquid
 */
public record BiomeProfile(
        String id,
        String biome,
        int priority,
        Range temperature,
        Range humidity,
        AltitudeRange altitude,
        Range weirdness,
        List<SurfaceLayer> surface,
        List<String> decorations,
        double vegetationDensity,
        double structureDensity,
        Integer seaLevel,
        String liquid) {

    /** An inclusive numeric band. */
    public record Range(double min, double max) {

        public boolean contains(double value) {
            return value >= min && value <= max;
        }

        public static Range all() {
            return new Range(-1.0, 1.0);
        }

        public static Range from(ConfigurationSection section, Range fallback, double min, double max) {
            if (section == null) {
                return fallback;
            }
            double low = section.getDouble("min", fallback.min());
            double high = section.getDouble("max", fallback.max());
            if (low > high) {
                double swap = low;
                low = high;
                high = swap;
            }
            return new Range(Math.max(min, low), Math.min(max, high));
        }

        public String describe() {
            return "[" + round(min) + ".." + round(max) + "]";
        }

        private static String round(double value) {
            return String.valueOf(Math.round(value * 100.0) / 100.0);
        }
    }

    /** An altitude band in blocks, where {@link #all()} means "any height". */
    public record AltitudeRange(int min, int max) {

        public boolean contains(int y) {
            return y >= min && y <= max;
        }

        public static AltitudeRange all() {
            return new AltitudeRange(Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        public static AltitudeRange from(ConfigurationSection section, AltitudeRange fallback) {
            if (section == null) {
                return fallback;
            }
            return new AltitudeRange(section.getInt("min", fallback.min()),
                    section.getInt("max", fallback.max()));
        }

        public String describe() {
            if (min == Integer.MIN_VALUE) {
                return "any height";
            }
            return min + ".." + max;
        }
    }

    /**
     * One layer of the surface stack, with an optional condition so a single
     * biome can look different on a mountain top than in its valleys:
     *
     * <pre>
     * surface:
     *   - { block: SNOW_BLOCK, depth: 2, min-altitude: 150 }
     *   - { block: GRASS_BLOCK, depth: 1 }
     *   - { block: DIRT, depth: 4 }
     * </pre>
     *
     * Layers are listed top → down and the first matching one wins.
     */
    public record SurfaceLayer(String block, int depth, AltitudeRange altitude, Range temperature) {

        public static SurfaceLayer plain(String block, int depth) {
            return new SurfaceLayer(block, depth, AltitudeRange.all(), Range.all());
        }

        /** Whether this layer applies to a column with the given height and temperature. */
        public boolean matches(int y, double columnTemperature) {
            return altitude.contains(y) && temperature.contains(columnTemperature);
        }

        public static SurfaceLayer from(ConfigurationSection section, Consumer<String> warn) {
            String block = section.getString("block",
                    section.getString("material", section.getString("type")));
            if (block == null || !Palette.isValidBlock(block)) {
                warn.accept("surface layer block '" + block + "' is not a valid block — skipped");
                return null;
            }
            int depth = Math.max(1, section.getInt("depth", section.getInt("thickness", 1)));
            AltitudeRange altitude = AltitudeRange.from(section.getConfigurationSection("altitude"),
                    new AltitudeRange(
                            section.getInt("min-altitude", Integer.MIN_VALUE),
                            section.getInt("max-altitude", Integer.MAX_VALUE)));
            Range temperature = Range.from(section.getConfigurationSection("temperature"),
                    new Range(section.getDouble("min-temperature", -1),
                            section.getDouble("max-temperature", 1)), 0, 1);
            return new SurfaceLayer(block, depth, altitude, temperature);
        }
    }

    /** A biome that covers whatever is left, so a planet always has a fallback. */
    public static BiomeProfile fallback(String id, String biome) {
        return new BiomeProfile(id, biome, -100, Range.all(), Range.all(), AltitudeRange.all(), Range.all(),
                List.of(), List.of(), 1.0, 1.0, null, null);
    }

    /** Whether this biome fits a column's environment. */
    public boolean matches(double temperature, double humidity, int altitude, double weirdness) {
        return this.temperature.contains(temperature)
                && this.humidity.contains(humidity)
                && this.altitude.contains(altitude)
                && this.weirdness.contains(weirdness);
    }

    public static BiomeProfile from(String id, ConfigurationSection section, Consumer<String> warn) {
        // "biome" is the vanilla biome the client is told about; "id" would be
        // the planet biome's own name, which the profile id already carries.
        String biome = PlanetBiomes.resolve(section.getString("biome"));
        if (biome == null) {
            String requested = section.getString("biome");
            warn.accept("biomes." + id + ": '" + requested
                    + "' is not a Minecraft biome — using plains (try "
                    + String.join(", ", PlanetBiomes.common().subList(0, 6)) + "...)");
            biome = "plains";
        }
        List<SurfaceLayer> surface = new ArrayList<>();
        for (Object entry : list(section, "surface")) {
            SurfaceLayer layer = readLayer(entry, warn, "biomes." + id + ".surface");
            if (layer != null) {
                surface.add(layer);
            }
        }
        List<String> decorations = new ArrayList<>();
        for (String decoration : section.getStringList("decorations")) {
            decorations.add(decoration.toLowerCase(Locale.ROOT));
        }
        Integer seaLevel = section.isSet("sea-level") ? section.getInt("sea-level") : null;
        String liquid = section.getString("liquid", null);
        if (liquid != null && !Palette.isValidBlock(liquid)) {
            warn.accept("biomes." + id + ".liquid '" + liquid + "' is not a block — ignored");
            liquid = null;
        }
        double vegetation = section.getDouble("vegetation.density",
                section.getDouble("vegetation-density", 1.0));
        if (vegetation < 0) {
            warn.accept("biomes." + id + ".vegetation.density is negative — clamped to 0");
            vegetation = 0;
        }
        return new BiomeProfile(id, biome, section.getInt("priority", 0),
                Range.from(section.getConfigurationSection("temperature"),
                        new Range(0, 1), 0, 1),
                Range.from(section.getConfigurationSection("humidity"),
                        new Range(0, 1), 0, 1),
                AltitudeRange.from(section.getConfigurationSection("altitude"), AltitudeRange.all()),
                Range.from(section.getConfigurationSection("weirdness"),
                        new Range(-1, 1), -1, 1),
                List.copyOf(surface), List.copyOf(decorations),
                clamp(vegetation, 0, 6),
                clamp(section.getDouble("structures.density",
                        section.getDouble("structure-density", 1.0)), 0, 8),
                seaLevel, liquid);
    }

    /** Reads one surface-layer entry written as a map or as {@code "BLOCK:depth"}. */
    static SurfaceLayer readLayer(Object entry, Consumer<String> warn, String path) {
        if (entry instanceof ConfigurationSection section) {
            return SurfaceLayer.from(section, warn);
        }
        if (entry instanceof java.util.Map<?, ?> map) {
            Object blockValue = map.get("block") != null ? map.get("block")
                    : (map.get("material") != null ? map.get("material") : map.get("type"));
            String block = blockValue == null ? "" : String.valueOf(blockValue);
            Object depth = map.get("depth");
            int amount = depth instanceof Number number ? Math.max(1, number.intValue()) : 1;
            if (!Palette.isValidBlock(block)) {
                warn.accept(path + ": '" + block + "' is not a valid block — skipped");
                return null;
            }
            return SurfaceLayer.plain(block, amount);
        }
        if (entry != null) {
            String text = entry.toString().trim();
            int depth = 1;
            int colon = text.lastIndexOf(':');
            if (colon > 0) {
                try {
                    depth = Math.max(1, Integer.parseInt(text.substring(colon + 1).trim()));
                    text = text.substring(0, colon).trim();
                } catch (NumberFormatException ignored) {
                    // Not "BLOCK:depth" after all — treat the whole string as the block.
                }
            }
            if (!text.isEmpty()) {
                if (!Palette.isValidBlock(text)) {
                    warn.accept(path + ": '" + text + "' is not a valid block — skipped");
                    return null;
                }
                return SurfaceLayer.plain(text, depth);
            }
        }
        return null;
    }

    static List<?> list(ConfigurationSection section, String key) {
        List<?> raw = section.getList(key);
        return raw == null ? List.of() : raw;
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Debug lines for {@code /planets profile <id>}. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add(id + " -> " + biome + " (priority " + priority + ")");
        lines.add("  temp " + temperature.describe() + " humidity " + humidity.describe()
                + " weird " + weirdness.describe() + " altitude " + altitude.describe());
        StringBuilder stack = new StringBuilder();
        for (SurfaceLayer layer : surface) {
            if (stack.length() > 0) {
                stack.append(" → ");
            }
            stack.append(layer.block()).append('×').append(layer.depth());
            if (layer.altitude().min() != Integer.MIN_VALUE) {
                stack.append('@').append(layer.altitude().describe());
            }
        }
        lines.add("  surface " + (stack.length() == 0 ? "planet default" : stack.toString()));
        lines.add("  growth ×" + vegetationDensity + ", structures ×" + structureDensity
                + (decorations.isEmpty() ? "" : ", decorations " + String.join(",", decorations)));
        return lines;
    }
}
