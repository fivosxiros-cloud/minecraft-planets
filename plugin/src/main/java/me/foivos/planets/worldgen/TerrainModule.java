package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * A way of building a world, as opposed to a set of numbers.
 * <p>
 * This is the seam that lets {@code BACKROOMS} and {@code NATURAL} live in the
 * same engine without either knowing about the other: a module decides what a
 * column looks like, and the rest of the pipeline (surface, caves, decoration,
 * structures) works the same way on top of whatever it produced.
 * <p>
 * Modules are stateless singletons; everything world-specific arrives in the
 * {@link GenerationContext} and the per-column {@link Column}.
 */
public interface TerrainModule {

    /** The style this module implements. */
    GenerationStyle style();

    /**
     * Builds one column of terrain into {@code data}.
     *
     * @param context the world's built samplers and settings
     * @param data    the chunk being generated, with chunk-local coordinates
     * @param x       world x of the column
     * @param z       world z of the column
     * @param localX  x within the chunk (0..15)
     * @param localZ  z within the chunk (0..15)
     * @return what the column produced, so the rest of the pipeline can act on it
     */
    Column generate(GenerationContext context, ChunkGenerator.ChunkData data,
                    int x, int z, int localX, int localZ);

    /**
     * Extra per-chunk work a module needs: carving whole cave systems, hanging
     * floating islands, laying out a maze. Called after every column of the chunk
     * has been generated.
     */
    default void finishChunk(GenerationContext context, ChunkGenerator.ChunkData data, int chunkX, int chunkZ) {
    }

    /** Whether this module fills oceans with the planet's liquid. */
    default boolean fillsLiquid() {
        return true;
    }

    /** A one-line description for {@code /planets profile}. */
    default String describe(GenerationContext context) {
        return style().name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * What one generated column looks like, handed from the terrain module to the
     * surface, cave and decoration passes.
     *
     * @param surfaceY     the y of the top solid block
     * @param ground       the material the surface should be built from
     * @param biome        the planet biome id this column landed in
     * @param underwater   whether the column's surface is below the sea level
     * @param biomeOverride the Minecraft biome to report for this column
     */
    record Column(int surfaceY, Material ground, String biome, boolean underwater,
                  org.bukkit.block.Biome biomeOverride) {

        /** A column of solid ground. */
        public static Column of(int surfaceY, Material ground, String biome, boolean underwater,
                                org.bukkit.block.Biome biomeOverride) {
            return new Column(surfaceY, ground, biome, underwater, biomeOverride);
        }

        /** An empty column: nothing was generated here at all. */
        public static Column empty() {
            return new Column(Integer.MIN_VALUE, null, null, false, null);
        }

        public boolean isEmpty() {
            return surfaceY == Integer.MIN_VALUE;
        }
    }

    /**
     * Utility shared by modules: fills a vertical run of blocks. The {@code setRegion}
     * overload takes an inclusive-exclusive box, so the y range is stepped through
     * explicitly — which is also what lets the fill stop at the world ceiling.
     */
    static void fill(ChunkGenerator.ChunkData data, int localX, int localZ, int fromY, int toY,
                     BlockData block) {
        if (block == null) {
            return;
        }
        int start = Math.max(fromY, data.getMinHeight());
        int end = Math.min(toY, data.getMaxHeight() - 1);
        if (start > end) {
            return;
        }
        data.setRegion(localX, start, localZ, localX + 1, end + 1, localZ + 1, block);
    }

    /** A list of block materials for a decoration's random choice. */
    static Material pick(List<Material> options, long hash) {
        if (options.isEmpty()) {
            return Material.AIR;
        }
        return options.get((int) Math.floorMod(hash, options.size()));
    }
}
