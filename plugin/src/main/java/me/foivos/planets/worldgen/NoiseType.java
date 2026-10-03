package me.foivos.planets.worldgen;

import java.util.Locale;

/**
 * The shape of a single noise layer, before fractal stacking.
 * <p>
 * Every one of these is seeded, pure and allocation-free once built, so the
 * same world seed always produces exactly the same terrain no matter in which
 * order chunks are generated.
 */
public enum NoiseType {

    /** Classic gradient noise: smooth rolling shapes. */
    PERLIN,

    /** Lattice (value) noise: cheaper, blockier — good for plateaus and scarps. */
    VALUE,

    /** Ridged fractal: sharp mountain crests following a ridge line. */
    RIDGED,

    /** Billow fractal: rounded blobs, good for dunes and cloud-like mesas. */
    BILLOW,

    /** Cellular (Worley) noise: honeycomb cells, good for canyons and cliffs. */
    CELLULAR;

    /** Parses a config value like {@code "simplex"} or {@code "ridged"}; null when unknown. */
    public static NoiseType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            // "simplex" is accepted as a friendlier name for the same gradient
            // family: the engine ships one gradient implementation, not three.
            case "perlin", "simplex", "simplex2", "opensimplex" -> PERLIN;
            case "value", "lattice" -> VALUE;
            case "ridged", "ridge" -> RIDGED;
            case "billow", "blob" -> BILLOW;
            case "cellular", "worley", "voronoi", "cell" -> CELLULAR;
            default -> null;
        };
    }
}
