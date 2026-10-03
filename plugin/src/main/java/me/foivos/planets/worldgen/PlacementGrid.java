package me.foivos.planets.worldgen;

/**
 * The placement rule every structure and landmark uses, in one place.
 * <p>
 * The world is divided into a coarse grid of cells. Each cell rolls exactly once
 * from a hash of {@code (world seed, profile id, structure id, cell coordinates)},
 * so:
 * <ul>
 *   <li>the roll is the same on every chunk generation, every restart and every
 *       pregeneration pass — nothing is remembered between chunks;</li>
 *   <li>a structure never appears twice, because only the cell's own chunk asks
 *       for it;</li>
 *   <li>structures are naturally spaced at least one cell apart, and a
 *       {@code min-distance} can widen that.</li>
 * </ul>
 * Placement candidates are only accepted where the terrain actually supports
 * them, which is why the generator asks the terrain first and the structure
 * second, never the other way round.
 */
public final class PlacementGrid {

    private PlacementGrid() {
    }

    /** The cell a world coordinate falls in. */
    public static int cell(long coordinate, int cellSize) {
        return Math.floorDiv((int) coordinate, Math.max(1, cellSize));
    }

    /**
     * A stable per-cell random in {@code [0, 1)}.
     *
     * @param seed      the world seed
     * @param salt      a per-structure salt, so two structures in the same cell
     *                  still roll independently
     * @param cellX     cell x
     * @param cellZ     cell z
     */
    public static double roll(long seed, long salt, int cellX, int cellZ) {
        return GradientNoise.chance(seed ^ (salt * 0x9E3779B97F4A7C15L), cellX, cellZ);
    }

    /** A stable per-cell integer in {@code [0, bound)}. */
    public static int pick(long seed, long salt, int cellX, int cellZ, int bound) {
        return GradientNoise.pick(seed ^ (salt * 0x9E3779B97F4A7C15L), cellX, cellZ, bound);
    }

    /**
     * The world position a cell places its structure at: a jittered point inside
     * the cell, so a whole row of structures never lines up in a visible lattice.
     */
    public static int offset(long seed, long salt, int cell, int cellSize) {
        int jitter = pick(seed, salt * 31 + 7, cell, salt == 0 ? 0 : (int) (salt & 0xFFFF),
                Math.max(1, cellSize - 8));
        return cell * cellSize + 4 + jitter;
    }

    /** Whether two placed positions are far enough apart. */
    public static boolean farEnough(int ax, int az, int bx, int bz, int minDistance) {
        if (minDistance <= 0) {
            return true;
        }
        long dx = ax - bx;
        long dz = az - bz;
        return dx * dx + dz * dz >= (long) minDistance * minDistance;
    }
}