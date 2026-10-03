package me.foivos.planets.worldgen;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What grows on the planet, and how thickly.
 * <p>
 * A decoration is a small piece of scenery placed per column: grass, flowers,
 * mushrooms, crystals, boulders, bushes, trees, bones. Placement is by density
 * per chunk with a per-column hash roll, so it is deterministic and cheap —
 * and it is filtered by the planet biome the column landed in, which is what
 * lets the same decoration cover a planet's plains but never its swamps.
 *
 * @param enabled   whether anything is placed at all
 * @param density   global multiplier on every decoration's own density
 * @param types     the decoration list
 */
public record DecorationSettings(boolean enabled, double density, List<DecorationProfile> types) {

    /**
     * One decoration type.
     *
     * @param id          name used in logs and biome references
     * @param type        the builder to use (grass, tree, crystal, ...)
     * @param density     how many of these per chunk at full strength
     * @param biomes      planet-biome ids this grows in; empty = any
     * @param minY        lowest ground it may sit on
     * @param maxY        highest ground it may sit on
     * @param minSlope    flatness gate: 0 = only flat ground, higher = any
     * @param onBlock     only grow on these blocks (palette keys or names); empty = any solid
     * @param avoidBlock  never grow on these blocks
     * @param rotation    allow a seeded random rotation
     * @param variation   allow the builder's own seeded variation
     * @param scale       size multiplier
     * @param palette     per-decoration material overrides
     * @param params      builder-specific extras
     */
    public record DecorationProfile(
            String id,
            String type,
            double density,
            List<String> biomes,
            int minY,
            int maxY,
            double minSlope,
            List<String> onBlock,
            List<String> avoidBlock,
            boolean rotation,
            boolean variation,
            double scale,
            Palette palette,
            Map<String, Object> params) {

        /** Whether this decoration may grow in the given planet biome and height. */
        public boolean allows(String biomeId, int y) {
            if (y < minY || y > maxY) {
                return false;
            }
            return biomes.isEmpty() || (biomeId != null && biomes.contains(biomeId.toLowerCase(Locale.ROOT)));
        }

        public double number(String key, double fallback) {
            Object value = params.get(key);
            return value instanceof Number number ? number.doubleValue() : fallback;
        }

        public int integer(String key, int fallback) {
            return (int) Math.round(number(key, fallback));
        }

        public String text(String key, String fallback) {
            Object value = params.get(key);
            return value == null ? fallback : String.valueOf(value);
        }

        public String describe() {
            return id + " (" + type + ") density " + Math.round(density * 100.0) / 100.0
                    + (biomes.isEmpty() ? "" : ", biomes " + String.join("/", biomes));
        }
    }

    public static DecorationSettings none() {
        return new DecorationSettings(false, 0, List.of());
    }

    /** A planet with nothing growing on it. */
    public boolean any() {
        return enabled && density > 0 && !types.isEmpty();
    }

    public static DecorationSettings from(ConfigurationSection section, DecorationSettings fallback,
                                          Consumer<String> warn) {
        if (section == null) {
            return fallback;
        }
        Map<String, DecorationProfile> types = new LinkedHashMap<>();
        read(section.getConfigurationSection("types"), types, warn);
        read(section.getConfigurationSection("decorations"), types, warn);
        // A shorthand: "grass: 12" means "a grass decoration at density 12".
        for (String key : section.getKeys(false)) {
            if (section.isConfigurationSection(key) || section.isList(key)) {
                continue;
            }
            double density = section.getDouble(key, 0);
            if (density <= 0) {
                continue;
            }
            if (!DecorationTypes.exists(key)) {
                warn.accept("decorations." + key + ": unknown decoration type '" + key
                        + "' — try one of " + String.join(", ", DecorationTypes.ids()));
                continue;
            }
            types.putIfAbsent(key.toLowerCase(Locale.ROOT), new DecorationProfile(
                    key.toLowerCase(Locale.ROOT), key.toLowerCase(Locale.ROOT), density,
                    List.of(), Integer.MIN_VALUE, Integer.MAX_VALUE, 0,
                    List.of(), List.of(), true, true, 1.0, Palette.empty(), Map.of()));
        }
        return new DecorationSettings(
                section.getBoolean("enabled", fallback.enabled() || !types.isEmpty()),
                Math.max(0, section.getDouble("density", fallback.density())),
                List.copyOf(types.values()));
    }

    private static void read(ConfigurationSection section, Map<String, DecorationProfile> into,
                             Consumer<String> warn) {
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection data = section.getConfigurationSection(id);
            if (data == null) {
                continue;
            }
            String type = data.getString("type", id).toLowerCase(Locale.ROOT);
            if (!DecorationTypes.exists(type)) {
                warn.accept("decorations." + id + ": unknown decoration type '" + type
                        + "' — try one of " + String.join(", ", DecorationTypes.ids()));
                continue;
            }
            List<String> biomes = new ArrayList<>();
            for (String biome : data.getStringList("biomes")) {
                biomes.add(biome.toLowerCase(Locale.ROOT));
            }
            List<String> onBlock = new ArrayList<>();
            for (String block : data.getStringList("on")) {
                onBlock.add(block);
            }
            List<String> avoid = new ArrayList<>();
            for (String block : data.getStringList("avoid")) {
                avoid.add(block);
            }
            Map<String, Object> params = new LinkedHashMap<>();
            ConfigurationSection paramsSection = data.getConfigurationSection("params");
            if (paramsSection == null) {
                paramsSection = data;
            }
            for (String key : paramsSection.getKeys(false)) {
                if (RESERVED.contains(key)) {
                    continue;
                }
                params.put(key, paramsSection.get(key));
            }
            into.put(id.toLowerCase(Locale.ROOT), new DecorationProfile(id.toLowerCase(Locale.ROOT), type,
                    Math.max(0, data.getDouble("density", 0)),
                    List.copyOf(biomes),
                    data.getInt("min-y", Integer.MIN_VALUE),
                    data.getInt("max-y", Integer.MAX_VALUE),
                    Math.max(0, data.getDouble("max-slope", 0)),
                    List.copyOf(onBlock), List.copyOf(avoid),
                    data.getBoolean("rotation", true),
                    data.getBoolean("variation", true),
                    Math.max(0.1, data.getDouble("scale", 1.0)),
                    Palette.from(data.getConfigurationSection("palette")),
                    Map.copyOf(params)));
        }
    }

    private static final java.util.Set<String> RESERVED = java.util.Set.of(
            "type", "density", "biomes", "min-y", "max-y", "max-slope", "on", "avoid",
            "rotation", "variation", "scale", "palette", "params");

    /** Debug lines. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (!enabled) {
            lines.add("disabled");
            return lines;
        }
        lines.add("density ×" + density + ", " + types.size() + " decoration(s)");
        for (DecorationProfile decoration : types) {
            lines.add("decoration " + decoration.describe());
        }
        return lines;
    }
}
