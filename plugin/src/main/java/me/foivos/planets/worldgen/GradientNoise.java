package me.foivos.planets.worldgen;

/**
 * The engine's only noise primitives: seeded, deterministic, allocation-free.
 * <p>
 * Everything procedural in a planet is built on top of these four samplers, so
 * one seeded {@link GradientNoise} instance (immutable after construction, safe
 * to share across the server's chunk-generation threads) is all the randomness
 * the terrain, caves, biomes and structures ever need. No external noise
 * library is used: the gradient noise is the classic improved-Perlin lattice
 * with a seed-shuffled permutation table.
 */
final class GradientNoise {

    /** Seed-shuffled permutation table, doubled so lookups never wrap with an if. */
    private final int[] perm = new int[512];
    /** A second table for the cellular sampler, so cells don't line up with lattice points. */
    private final int cellSeed;

    GradientNoise(long seed) {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            table[i] = i;
        }
        // A small, well-mixed generator so the same seed always shuffles the
        // same way — this is what makes chunk generation order-independent.
        long state = seed * 6364136223846793005L + 1442695040888963407L;
        for (int i = 255; i > 0; i--) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int pick = (int) Math.floorMod(state >>> 33, i + 1);
            int swap = table[i];
            table[i] = table[pick];
            table[pick] = swap;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = table[i & 255];
        }
        this.cellSeed = (int) (seed ^ (seed >>> 32));
    }

    // ── 2D gradient (Perlin) noise ───────────────────────────────────────

    /** Perlin noise in roughly {@code [-1, 1]}. */
    double perlin2(double x, double z) {
        int xi = fastFloor(x);
        int zi = fastFloor(z);
        double xf = x - xi;
        double zf = z - zi;
        double u = fade(xf);
        double v = fade(zf);
        int a = perm[xi & 255] + zi;
        int b = perm[(xi + 1) & 255] + zi;
        double n00 = grad2(perm[a & 255], xf, zf);
        double n10 = grad2(perm[b & 255], xf - 1, zf);
        double n01 = grad2(perm[(a + 1) & 255], xf, zf - 1);
        double n11 = grad2(perm[(b + 1) & 255], xf - 1, zf - 1);
        double low = lerp(v, n00, n10);
        double high = lerp(v, n01, n11);
        return lerp(u, low, high);
    }

    // ── 3D gradient noise (caves, overhangs, floating islands) ───────────

    /** Perlin noise in roughly {@code [-1, 1]}. */
    double perlin3(double x, double y, double z) {
        int xi = fastFloor(x);
        int yi = fastFloor(y);
        int zi = fastFloor(z);
        double xf = x - xi;
        double yf = y - yi;
        double zf = z - zi;
        double u = fade(xf);
        double v = fade(yf);
        double w = fade(zf);
        int a = perm[xi & 255] + yi;
        int aa = perm[a & 255] + zi;
        int ab = perm[(a + 1) & 255] + zi;
        int b = perm[(xi + 1) & 255] + yi;
        int ba = perm[b & 255] + zi;
        int bb = perm[(b + 1) & 255] + zi;
        double x1 = lerp(u, grad3(perm[aa & 255], xf, yf, zf),
                grad3(perm[ba & 255], xf - 1, yf, zf));
        double x2 = lerp(u, grad3(perm[ab & 255], xf, yf - 1, zf),
                grad3(perm[bb & 255], xf - 1, yf - 1, zf));
        double y1 = lerp(v, x1, x2);
        double x3 = lerp(u, grad3(perm[(aa + 1) & 255], xf, yf, zf - 1),
                grad3(perm[(ba + 1) & 255], xf - 1, yf, zf - 1));
        double x4 = lerp(u, grad3(perm[(ab + 1) & 255], xf, yf - 1, zf - 1),
                grad3(perm[(bb + 1) & 255], xf - 1, yf - 1, zf - 1));
        double y2 = lerp(v, x3, x4);
        return lerp(w, y1, y2);
    }

    // ── Value noise ──────────────────────────────────────────────────────

    /** Lattice value noise in roughly {@code [-1, 1]}: cheaper, harder edges. */
    double value2(double x, double z) {
        int xi = fastFloor(x);
        int zi = fastFloor(z);
        double xf = x - xi;
        double zf = z - zi;
        double u = smooth(xf);
        double v = smooth(zf);
        double n00 = lattice(xi, zi);
        double n10 = lattice(xi + 1, zi);
        double n01 = lattice(xi, zi + 1);
        double n11 = lattice(xi + 1, zi + 1);
        return lerp(u, lerp(v, n00, n10), lerp(v, n01, n11));
    }

    // ── Cellular (Worley) noise ──────────────────────────────────────────

    /** Distance to the nearest cell point, in roughly {@code [0, 1]}. */
    double cellular2(double x, double z) {
        int xi = fastFloor(x);
        int zi = fastFloor(z);
        double nearest = Double.MAX_VALUE;
        for (int cx = xi - 1; cx <= xi + 1; cx++) {
            for (int cz = zi - 1; cz <= zi + 1; cz++) {
                double px = cx + jitter(cx, cz, 0);
                double pz = cz + jitter(cx, cz, 1);
                double dx = px - x;
                double dz = pz - z;
                double d = dx * dx + dz * dz;
                if (d < nearest) {
                    nearest = d;
                }
            }
        }
        return Math.min(1.0, Math.sqrt(nearest));
    }

    // ── Deterministic randomness for placement (structures, decoration) ──

    /**
     * A stable hash of a cell coordinate: the source of every placement roll in
     * the engine. Two runs of the same world always roll the same numbers.
     */
    static long hash(long seed, int x, int z) {
        long h = seed * 0x9E3779B97F4A7C15L + (x * 0x2545F4914F6CDD1DL) + (z * 0xBF58476D1CE4E5B9L);
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    /** A hash-backed random in {@code [0, 1)} for a coordinate — deterministic, no allocation. */
    static double chance(long seed, int x, int z) {
        return (hash(seed, x, z) >>> 11) * 0x1.0p-53;
    }

    /** A hash-backed index in {@code [0, bound)} for a coordinate. */
    static int pick(long seed, int x, int z, int bound) {
        return bound <= 0 ? 0 : (int) Math.floorMod(hash(seed, x, z), bound);
    }

    // ── Internals ────────────────────────────────────────────────────────

    private double lattice(int x, int z) {
        return (perm[(perm[x & 255] + z) & 255] / 127.5) - 1.0;
    }

    /** One of two jittered per-cell offsets, derived from the cell's own hash. */
    private double jitter(int x, int z, int axis) {
        long h = hash(cellSeed, x, z * 31 + axis);
        return ((h >>> 12) & 0xFFFF) / 65535.0;
    }

    private static double grad2(int hash, double x, double y) {
        return switch (hash & 3) {
            case 0 -> x + y;
            case 1 -> x - y;
            case 2 -> -x + y;
            default -> -x - y;
        };
    }

    private static double grad3(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static int fastFloor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
