package me.foivos.planets.worldgen;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Where things get built, and how rarely.
 * <p>
 * Every structure is placed the same deterministic way, so it survives chunk
 * order, restarts and pregeneration: the world is divided into a coarse grid of
 * <em>cells</em>, each cell rolls once from its own hash of the world seed, and
 * a winning cell asks the terrain where its own ground is before building
 * anything. Nothing is ever placed at a spot the terrain would not support.
 *
 * @param enabled      whether structures generate on this planet
 * @param density      global multiplier on every structure's rarity (0 = none)
 * @param maxPerChunk  hard cap on structures placed in one chunk, so a dense
 *                     profile can never turn a chunk into a lag spike
 * @param minDistance  minimum spacing between two structures, in blocks
 * @param types        the structure list
 * @param landmarks    the rare landmarks (rarer than structures, and announced)
 */
public record StructureSettings(
        boolean enabled,
        double density,
        int maxPerChunk,
        int minDistance,
        List<StructureProfile> types,
        List<StructureProfile> landmarks) {

    /**
     * One structure type.
     *
     * @param id            name used in logs and {@code /planets profile}
     * @param type          the builder to use (ruin, tower, fossil, ...)
     * @param rarity        chance per grid cell, 0..1 (landmarks are far lower)
     * @param cellSize      the grid cell, in blocks: bigger = further apart
     * @param minDistance   extra spacing on top of the cell grid
     * @param biomes        planet-biome ids this may appear in; empty = any
     * @param minY          lowest ground it may sit on
     * @param maxY          highest ground it may sit on
     * @param rotation      allow a random (seeded) rotation
     * @param variation     allow the builder's own seeded variation
     * @param maxSlope      the steepest ground it will be placed on
     * @param announce      whether placing it tells the nearby players
     * @param palette       optional per-structure material overrides
     * @param scale         size multiplier
     * @param params        builder-specific extras (height, floors, room size...)
     */
    public record StructureProfile(
            String id,
            String type,
            double rarity,
            int cellSize,
            int minDistance,
            List<String> biomes,
            int minY,
            int maxY,
            boolean rotation,
            boolean variation,
            double maxSlope,
            boolean announce,
            Palette palette,
            double scale,
            java.util.Map<String, Object> params) {

        /** Whether this structure may appear in the given planet biome and altitude. */
        public boolean allows(String biomeId, int y) {
            if (y < minY || y > maxY) {
                return false;
            }
            if (biomes.isEmpty()) {
                return true;
            }
            return biomeId != null && biomes.contains(biomeId.toLowerCase(Locale.ROOT));
        }

        /** A numeric builder parameter, or the fallback. */
        public double number(String key, double fallback) {
            Object value = params.get(key);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            return fallback;
        }

        public int integer(String key, int fallback) {
            return (int) Math.round(number(key, fallback));
        }

        public String text(String key, String fallback) {
            Object value = params.get(key);
            return value == null ? fallback : String.valueOf(value);
        }

        public boolean flag(String key, boolean fallback) {
            Object value = params.get(key);
            if (value instanceof Boolean bool) {
                return bool;
            }
            return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
        }

        public String describe() {
            StringBuilder text = new StringBuilder(id).append(" (").append(type).append(')');
            text.append(" rarity ").append(Math.round(rarity * 10000.0) / 10000.0);
            text.append(", cell ").append(cellSize);
            if (!biomes.isEmpty()) {
                text.append(", biomes ").append(String.join("/", biomes));
            }
            if (minY != Integer.MIN_VALUE || maxY != Integer.MAX_VALUE) {
                text.append(", y ").append(minY).append("..").append(maxY);
            }
            return text.toString();
        }
    }

    public static StructureSettings none() {
        return new StructureSettings(false, 0, 0, 0, List.of(), List.of());
    }

    /** Everything switched off — no natural caves, no structures. */
    public boolean any() {
        return enabled && density > 0 && (!types.isEmpty() || !landmarks.isEmpty());
    }

    public static StructureSettings from(ConfigurationSection section, StructureSettings fallback,
                                        Consumer<String> warn) {
        if (section == null) {
            return fallback;
        }
        List<StructureProfile> types = new ArrayList<>();
        List<StructureProfile> landmarks = new ArrayList<>();
        // Both spellings are accepted: "structures:" and "landmarks:" hold the
        // same kind of entry, landmarks are simply much rarer by convention.
        read(section.getConfigurationSection("types"), types, warn, "structures");
        read(section.getConfigurationSection("structures"), types, warn, "structures");
        read(section.getConfigurationSection("landmarks"), landmarks, warn, "landmarks");

        return new StructureSettings(
                section.getBoolean("enabled", fallback.enabled() || !types.isEmpty() || !landmarks.isEmpty()),
                Math.max(0, section.getDouble("density", fallback.density())),
                Math.max(0, section.getInt("max-per-chunk", fallback.maxPerChunk())),
                Math.max(0, section.getInt("min-distance", fallback.minDistance())),
                List.copyOf(types), List.copyOf(landmarks));
    }

    private static void read(ConfigurationSection section, List<StructureProfile> into,
                             Consumer<String> warn, String path) {
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection data = section.getConfigurationSection(id);
            if (data == null) {
                warn.accept(path + "." + id + " is not a structure section — skipped");
                continue;
            }
            String type = data.getString("type", id).toLowerCase(Locale.ROOT);
            if (!StructureTypes.exists(type)) {
                warn.accept(path + "." + id + ": unknown structure type '" + type
                        + "' — try one of " + String.join(", ", StructureTypes.ids()));
                continue;
            }
            double rarity = data.getDouble("rarity", data.getDouble("chance", 0.0));
            if (rarity <= 0) {
                warn.accept(path + "." + id + " has no rarity — it will never appear");
            }
            List<String> biomes = new ArrayList<>();
            for (String biome : data.getStringList("biomes")) {
                biomes.add(biome.toLowerCase(Locale.ROOT));
            }
            java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
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
            into.add(new StructureProfile(id.toLowerCase(Locale.ROOT), type,
                    Math.max(0, Math.min(1, rarity)),
                    Math.max(16, data.getInt("cell-size", 160)),
                    Math.max(0, data.getInt("min-distance", 0)),
                    List.copyOf(biomes),
                    data.getInt("min-y", Integer.MIN_VALUE),
                    data.getInt("max-y", Integer.MAX_VALUE),
                    data.getBoolean("rotation", true),
                    data.getBoolean("variation", true),
                    data.getDouble("max-slope", 6.0),
                    data.getBoolean("announce", false),
                    Palette.from(data.getConfigurationSection("palette")),
                    Math.max(0.1, data.getDouble("scale", 1.0)),
                    java.util.Map.copyOf(params)));
        }
    }

    /** Keys that configure placement rather than the builder itself. */
    private static final java.util.Set<String> RESERVED = java.util.Set.of(
            "type", "rarity", "chance", "cell-size", "min-distance", "biomes",
            "min-y", "max-y", "rotation", "variation", "max-slope", "announce",
            "palette", "scale", "params");

    /** Debug lines. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (!enabled) {
            lines.add("disabled");
            return lines;
        }
        lines.add("density ×" + density + ", max " + maxPerChunk + "/chunk, spacing "
                + minDistance + " blocks");
        for (StructureProfile structure : types) {
            lines.add("structure " + structure.describe());
        }
        for (StructureProfile landmark : landmarks) {
            lines.add("landmark " + landmark.describe());
        }
        return lines;
    }
}
