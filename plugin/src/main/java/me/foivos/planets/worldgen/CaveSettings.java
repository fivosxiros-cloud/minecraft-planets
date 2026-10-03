package me.foivos.planets.worldgen;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A planet's underground. Independent systems, so a world can have twisting
 * tunnels, a few huge glowing caverns, or (like the Backrooms) nothing at all:
 *
 * <pre>
 * caves:
 *   enabled: true
 *   tunnels:
 *     enabled: true
 *     density: 0.55
 *     scale: 90
 *     size: 1.0
 *     min-y: 12
 *     max-y: 60
 *   caverns:
 *     enabled: true
 *     density: 0.08
 *     scale: 190
 *     size: 1.6
 *   lava-chambers:
 *     enabled: true
 *     density: 0.03
 *     min-y: 5
 *     max-y: 28
 *     liquid: LAVA
 * </pre>
 *
 * Caves are carved by thresholding a 3D noise field inside the rock. Tunnels use
 * the product of two independent fields (which is what makes them long and
 * connected instead of a sponge), caverns use a single wider one.
 *
 * @param enabled          whether any carving happens at all
 * @param tunnels          winding tunnel network
 * @param caverns          large open chambers
 * @param lavaChambers     chambers deliberately filled with a liquid
 * @param crystalCaves     caverns lined with a crystal shell
 * @param undergroundLakes whether caverns below sea level are flooded
 * @param overrides        per-planet-biome overrides, applied on top of the above
 */
public record CaveSettings(
        boolean enabled,
        System tunnels,
        System caverns,
        Chambers lavaChambers,
        Chambers crystalCaves,
        boolean undergroundLakes,
        List<Override> overrides) {

    /**
     * One noise-carved void inside the rock.
     *
     * @param enabled whether this system runs
     * @param density how much of the band the system claims (0 = nothing, 1 = almost all)
     * @param scale   feature size in blocks (bigger = longer, wider voids)
     * @param size    multiplier on the void's thickness
     * @param minY    lowest block the system may carve
     * @param maxY    highest block the system may carve
     */
    public record System(boolean enabled, double density, double scale, double size, int minY, int maxY) {

        public static System off() {
            return new System(false, 0, 100, 1, 0, 0);
        }

        public static System of(double density, double scale, double size, int minY, int maxY) {
            return new System(true, density, scale, size, minY, maxY);
        }

        public static System from(ConfigurationSection section, System fallback) {
            if (section == null) {
                return fallback;
            }
            double density = clamp(section.getDouble("density", fallback.density()), 0, 1);
            boolean enabled = section.getBoolean("enabled", fallback.enabled()) && density > 0;
            return new System(enabled, density,
                    Math.max(4, section.getDouble("scale", fallback.scale())),
                    clamp(section.getDouble("size", section.getDouble("thickness", fallback.size())), 0.05, 8),
                    section.getInt("min-y", section.getInt("vertical-range.min", fallback.minY())),
                    section.getInt("max-y", section.getInt("vertical-range.max", fallback.maxY())));
        }

        /** The effective band, ordered and clamped to a world's height. */
        public int low(int worldMin) {
            return Math.max(worldMin + 1, Math.min(minY, maxY));
        }

        public int high(int worldMax) {
            return Math.min(worldMax - 1, Math.max(minY, maxY));
        }

        public String describe() {
            return (enabled ? "" : "off, ") + "density " + round(density)
                    + ", scale " + round(scale) + ", size " + round(size)
                    + ", y " + Math.min(minY, maxY) + ".." + Math.max(minY, maxY);
        }
    }

    /**
     * A chamber system that also fills its floor with a liquid or lines its walls
     * with crystals — used for lava planets and alien cave worlds.
     *
     * @param enabled   whether it runs
     * @param density   how much of the band the chambers claim
     * @param scale     chamber size in blocks
     * @param size      thickness multiplier
     * @param minY      lowest block
     * @param maxY      highest block
     * @param liquid    liquid that pools on the chamber floor, or null
     * @param liquidLevel how deep the pool is, in blocks
     * @param lining    block the chamber walls are lined with, or null
     * @param liningDepth how thick that lining is
     */
    public record Chambers(boolean enabled, double density, double scale, double size,
                           int minY, int maxY, String liquid, int liquidLevel,
                           String lining, int liningDepth) {

        public static Chambers off() {
            return new Chambers(false, 0, 150, 1.5, 0, 0, null, 0, null, 0);
        }

        public static Chambers from(ConfigurationSection section, Chambers fallback) {
            if (section == null) {
                return fallback;
            }
            double density = clamp(section.getDouble("density", fallback.density()), 0, 1);
            String liquid = section.getString("liquid", fallback.liquid());
            if (liquid != null && !Palette.isValidBlock(liquid)) {
                liquid = fallback.liquid();
            }
            String lining = section.getString("lining", fallback.lining());
            if (lining != null && !Palette.isValidBlock(lining)) {
                lining = fallback.lining();
            }
            return new Chambers(section.getBoolean("enabled", fallback.enabled()) && density > 0, density,
                    Math.max(8, section.getDouble("scale", fallback.scale())),
                    clamp(section.getDouble("size", fallback.size()), 0.05, 8),
                    section.getInt("min-y", fallback.minY()),
                    section.getInt("max-y", fallback.maxY()),
                    liquid,
                    Math.max(0, section.getInt("liquid-level", section.getInt("liquid-depth",
                            fallback.liquidLevel()))),
                    lining,
                    Math.max(0, section.getInt("lining-depth", fallback.liningDepth())));
        }

        public int low(int worldMin) {
            return Math.max(worldMin + 1, Math.min(minY, maxY));
        }

        public int high(int worldMax) {
            return Math.min(worldMax - 1, Math.max(minY, maxY));
        }

        public String describe() {
            StringBuilder text = new StringBuilder(enabled ? "" : "off, ");
            text.append("density ").append(round(density)).append(", scale ").append(round(scale));
            if (liquid != null) {
                text.append(", ").append(liquid);
            }
            if (lining != null) {
                text.append(", lined ").append(lining);
            }
            return text.toString();
        }
    }

    /**
     * A per-planet-biome cave override:
     *
     * <pre>
     * caves:
     *   overrides:
     *     volcanic_wastes: { tunnels: { density: 0.8 }, caverns: { enabled: false } }
     * </pre>
     */
    public record Override(String biomeId, boolean enabled, Double tunnelDensity,
                           Double cavernDensity, boolean lavaChambers, boolean crystalCaves) {
    }

    /** A planet with real cave systems. */
    public static CaveSettings standard(int minY, int maxY) {
        int tunnelTop = Math.max(minY + 8, maxY - 24);
        return new CaveSettings(true,
                System.of(0.55, 90, 1.0, minY + 2, tunnelTop),
                System.of(0.07, 190, 1.6, minY + 2, tunnelTop - 10),
                Chambers.off(),
                Chambers.off(),
                true,
                List.of());
    }

    /** A planet with no caves at all (the Backrooms, stations, voids). */
    public static CaveSettings none() {
        return new CaveSettings(false, System.off(), System.off(), Chambers.off(), Chambers.off(),
                false, List.of());
    }

    public static CaveSettings from(ConfigurationSection section, CaveSettings fallback,
                                    java.util.function.Consumer<String> warn) {
        if (section == null) {
            return fallback;
        }
        boolean enabled = section.getBoolean("enabled", fallback.enabled());
        List<Override> overrides = new ArrayList<>();
        ConfigurationSection overrideSection = section.getConfigurationSection("overrides");
        if (overrideSection != null) {
            for (String biomeId : overrideSection.getKeys(false)) {
                ConfigurationSection data = overrideSection.getConfigurationSection(biomeId);
                if (data == null) {
                    continue;
                }
                String id = biomeId.toLowerCase(Locale.ROOT);
                if (!data.isSet("enabled") && !data.isSet("tunnels") && !data.isSet("caverns")
                        && !data.isSet("lava-chambers") && !data.isSet("crystal-caves")) {
                    warn.accept("caves.overrides." + biomeId
                            + " sets nothing (try enabled, tunnels.density, caverns.density, ...)");
                }
                overrides.add(new Override(id,
                        data.getBoolean("enabled", true),
                        data.isSet("tunnels.density") ? data.getDouble("tunnels.density") : null,
                        data.isSet("caverns.density") ? data.getDouble("caverns.density") : null,
                        data.getBoolean("lava-chambers.enabled", false),
                        data.getBoolean("crystal-caves.enabled", false)));
            }
        }
        return new CaveSettings(enabled,
                System.from(section.getConfigurationSection("tunnels"), fallback.tunnels()),
                System.from(section.getConfigurationSection("caverns"), fallback.caverns()),
                Chambers.from(section.getConfigurationSection("lava-chambers"), fallback.lavaChambers()),
                Chambers.from(section.getConfigurationSection("crystal-caves"), fallback.crystalCaves()),
                section.getBoolean("underground-lakes", fallback.undergroundLakes()),
                List.copyOf(overrides));
    }

    /** The override for a planet biome, or null. */
    public Override overrideFor(String biomeId) {
        if (biomeId == null) {
            return null;
        }
        for (Override override : overrides) {
            if (override.biomeId().equals(biomeId.toLowerCase(Locale.ROOT))) {
                return override;
            }
        }
        return null;
    }

    /** Whether anything would actually be carved. */
    public boolean carves() {
        return enabled && (tunnels.enabled() || caverns.enabled()
                || lavaChambers.enabled() || crystalCaves.enabled());
    }

    /** Debug lines. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (!enabled) {
            lines.add("disabled (no natural caves)");
            return lines;
        }
        lines.add("tunnels: " + tunnels.describe());
        lines.add("caverns: " + caverns.describe());
        if (lavaChambers.enabled()) {
            lines.add("lava chambers: " + lavaChambers.describe());
        }
        if (crystalCaves.enabled()) {
            lines.add("crystal caves: " + crystalCaves.describe());
        }
        lines.add("underground lakes: " + undergroundLakes);
        for (Override override : overrides) {
            lines.add("override " + override.biomeId() + ": enabled " + override.enabled()
                    + (override.tunnelDensity() == null ? "" : ", tunnel density " + override.tunnelDensity())
                    + (override.cavernDensity() == null ? "" : ", cavern density " + override.cavernDensity())
                    + (override.lavaChambers() ? ", lava chambers" : "")
                    + (override.crystalCaves() ? ", crystal caves" : ""));
        }
        return lines;
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }
}
