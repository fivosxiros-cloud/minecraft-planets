package me.foivos.planets.worldgen;

import org.bukkit.configuration.ConfigurationSection;

/**
 * One configurable noise layer: the reusable unit every part of the engine is
 * built from (terrain, mountains, caves, biome maps, decoration clusters...).
 * <p>
 * Only the parameters that make sense for all layers live here; a layer's
 * <em>effect</em> — how many blocks of height it contributes, which biome map it
 * feeds — is decided by whoever owns it.
 *
 * @param type           the underlying noise shape
 * @param seedOffset     mixed into the world seed, so two layers never correlate
 * @param scale          feature size in blocks (larger = wider, smoother shapes)
 * @param amplitude      how strongly this layer counts, in blocks
 * @param octaves        fractal detail passes (1 = one smooth pass)
 * @param persistence    how much each octave keeps of the previous one's strength
 * @param lacunarity     how much finer each octave is
 * @param warpStrength   domain-warping distance in blocks (0 = off)
 * @param warpFrequency  how quickly the warp field itself changes, per block
 */
public record NoiseSettings(
        NoiseType type,
        long seedOffset,
        double scale,
        double amplitude,
        int octaves,
        double persistence,
        double lacunarity,
        double warpStrength,
        double warpFrequency) {

    /** Sensible defaults for a named layer: smooth, 100-block features, 1× amplitude. */
    public static NoiseSettings defaults(NoiseType type, double scale, double amplitude) {
        return new NoiseSettings(type, 0L, scale, amplitude, 4, 0.5, 2.0, 0.0, 0.0);
    }

    public NoiseSettings withAmplitude(double newAmplitude) {
        return new NoiseSettings(type, seedOffset, scale, newAmplitude, octaves, persistence,
                lacunarity, warpStrength, warpFrequency);
    }

    public NoiseSettings withScale(double newScale) {
        return new NoiseSettings(type, seedOffset, newScale, amplitude, octaves, persistence,
                lacunarity, warpStrength, warpFrequency);
    }

    public NoiseSettings withSeedOffset(long newOffset) {
        return new NoiseSettings(type, newOffset, scale, amplitude, octaves, persistence,
                lacunarity, warpStrength, warpFrequency);
    }

    /** Whether this layer is the default of {@code other} (used to skip no-op layers). */
    public boolean isFlat() {
        return amplitude == 0;
    }

    /** Human-readable summary for {@code /planets profile <id>}. */
    public String describe() {
        StringBuilder text = new StringBuilder(type.name().toLowerCase(java.util.Locale.ROOT));
        text.append(" scale ").append(round(scale));
        text.append(" amp ").append(round(amplitude));
        if (octaves > 1) {
            text.append(" oct ").append(octaves)
                    .append(" pers ").append(round(persistence))
                    .append(" lac ").append(round(lacunarity));
        }
        if (warpStrength > 0) {
            text.append(" warp ").append(round(warpStrength)).append('@').append(round(warpFrequency));
        }
        return text.toString();
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 100.0) / 100.0);
    }

    // ── Config parsing ───────────────────────────────────────────────────

    /**
     * Reads a layer from a config section, falling back to {@code fallback} for
     * anything that isn't written. {@code scale} and {@code frequency} are
     * accepted as alternatives ({@code frequency: 0.01} means {@code scale: 100}),
     * so either habit works.
     */
    public static NoiseSettings from(ConfigurationSection section, NoiseSettings fallback) {
        if (section == null) {
            return fallback;
        }
        NoiseType type = NoiseType.parse(section.getString("type"));
        if (type == null) {
            type = fallback.type();
        }
        double scale = fallback.scale();
        if (section.isSet("scale")) {
            scale = section.getDouble("scale", scale);
        } else if (section.isSet("frequency")) {
            double frequency = section.getDouble("frequency", 1.0 / scale);
            scale = frequency > 0 ? 1.0 / frequency : scale;
        }
        double amplitude = section.getDouble("amplitude",
                section.getDouble("strength", fallback.amplitude()));
        int octaves = clamp(section.getInt("octaves", fallback.octaves()), 1, 8);
        double persistence = clamp(section.getDouble("persistence",
                section.getDouble("gain", fallback.persistence())), 0.0, 1.5);
        double lacunarity = clamp(section.getDouble("lacunarity", fallback.lacunarity()), 1.05, 8.0);
        double warpStrength = Math.max(0, section.getDouble("warp-strength",
                section.getDouble("warping-strength", fallback.warpStrength())));
        double warpFrequency = Math.max(0, section.getDouble("warp-frequency",
                section.getDouble("warping-frequency", fallback.warpFrequency())));
        long seedOffset = section.getLong("seed-offset", fallback.seedOffset());
        return new NoiseSettings(type, seedOffset, Math.max(1e-4, scale), Math.max(0, amplitude),
                octaves, persistence, lacunarity, warpStrength, warpFrequency);
    }

    /** Reads the layer at {@code path} under {@code parent}, or the fallback when absent. */
    public static NoiseSettings from(ConfigurationSection parent, String path, NoiseSettings fallback) {
        return parent == null ? fallback : from(parent.getConfigurationSection(path), fallback);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
