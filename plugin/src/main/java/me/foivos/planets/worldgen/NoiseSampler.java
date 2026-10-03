package me.foivos.planets.worldgen;

/**
 * A built, immutable sampler for one {@link NoiseSettings} layer.
 * <p>
 * Samplers are created once per world (in {@link GenerationContext}) and shared
 * across chunks. They hold no per-sample state, so the server may call them from
 * several generation threads at once — which is what keeps a busy planet from
 * becoming a bottleneck.
 * <p>
 * Every sampler returns a normalized value: {@code [-1, 1]} for the bipolar
 * types, {@code [0, 1]} for {@link NoiseType#RIDGED}. The owner of the layer
 * scales it by {@link NoiseSettings#amplitude()} into blocks.
 */
public final class NoiseSampler {

    private final NoiseSettings settings;
    private final GradientNoise base;
    private final GradientNoise warpX;
    private final GradientNoise warpZ;
    /** 1/scale, so sampling is a multiply instead of a divide. */
    private final double inverseScale;
    private final boolean warped;

    public NoiseSampler(NoiseSettings settings, long seed) {
        this.settings = settings;
        long layerSeed = seed + settings.seedOffset() * 0x9E3779B97F4A7C15L;
        this.base = new GradientNoise(layerSeed);
        this.warpX = new GradientNoise(layerSeed ^ 0x5DEECE66DL);
        this.warpZ = new GradientNoise(layerSeed ^ 0x27BB2EE687B0B0FDL);
        this.inverseScale = 1.0 / Math.max(1e-4, settings.scale());
        this.warped = settings.warpStrength() > 0 && settings.warpFrequency() > 0;
    }

    public NoiseSettings settings() {
        return settings;
    }

    /** The layer's value in {@code [-1, 1]} (or {@code [0, 1]} for ridged), before amplitude. */
    public double sample(double x, double z) {
        double wx = x;
        double wz = z;
        if (warped) {
            double frequency = settings.warpFrequency();
            double strength = settings.warpStrength();
            wx += warpX.perlin2(x * frequency, z * frequency) * strength;
            wz += warpZ.perlin2(x * frequency + 13.7, z * frequency - 7.3) * strength;
        }
        double sx = wx * inverseScale;
        double sz = wz * inverseScale;
        return fractal(sx, sz, Double.NaN);
    }

    /** The layer's 3D value, for caves and floating formations. */
    public double sample(double x, double y, double z) {
        double wx = x;
        double wz = z;
        if (warped) {
            double frequency = settings.warpFrequency();
            double strength = settings.warpStrength();
            wx += warpX.perlin2(x * frequency, z * frequency) * strength;
            wz += warpZ.perlin2(x * frequency + 13.7, z * frequency - 7.3) * strength;
        }
        double scale = inverseScale;
        double amplitude = 1.0;
        double frequency = 1.0;
        double total = 0.0;
        double norm = 0.0;
        for (int octave = 0; octave < settings.octaves(); octave++) {
            double value = shape(base.perlin3(wx * scale * frequency, y * scale * frequency,
                    wz * scale * frequency));
            total += value * amplitude;
            norm += amplitude;
            amplitude *= settings.persistence();
            frequency *= settings.lacunarity();
        }
        return norm == 0 ? 0 : total / norm;
    }

    /**
     * One fractal sum of the layer's base noise. {@code y} is NaN for the 2D
     * case, which is the whole point: the 2D path never pays for 3D sampling.
     */
    private double fractal(double sx, double sz, double y) {
        double amplitude = 1.0;
        double frequency = 1.0;
        double total = 0.0;
        double norm = 0.0;
        boolean threeD = !Double.isNaN(y);
        for (int octave = 0; octave < settings.octaves(); octave++) {
            double value;
            if (threeD) {
                value = shape(base.perlin3(sx * frequency, y, sz * frequency));
            } else {
                value = raw(sx * frequency, sz * frequency);
            }
            total += value * amplitude;
            norm += amplitude;
            amplitude *= settings.persistence();
            frequency *= settings.lacunarity();
        }
        return norm == 0 ? 0 : total / norm;
    }

    /** The base noise of this layer's type, before fractal stacking. */
    private double raw(double x, double z) {
        return switch (settings.type()) {
            case PERLIN, RIDGED, BILLOW -> base.perlin2(x, z);
            case VALUE -> base.value2(x, z);
            case CELLULAR -> 1.0 - 2.0 * base.cellular2(x, z);
        };
    }

    /** Shapes one octave for the layer's type. */
    private double shape(double value) {
        return switch (settings.type()) {
            case RIDGED -> {
                double ridge = 1.0 - Math.abs(value);
                yield ridge * ridge; // sharpen the crest
            }
            case BILLOW -> 2.0 * Math.abs(value) - 1.0;
            case CELLULAR -> 1.0 - 2.0 * Math.abs(value);
            default -> value;
        };
    }
}
