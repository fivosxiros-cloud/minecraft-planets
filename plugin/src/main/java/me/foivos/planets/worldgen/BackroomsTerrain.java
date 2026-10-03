package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

/**
 * The Backrooms, generated as architecture rather than as landscape.
 * <p>
 * The floor plan is deterministic and <em>absolute</em>: a global grid of walls
 * defined purely by world coordinates, never by the chunk being generated, so
 * the same walls come out whichever chunk is built first and no chunk boundary
 * is ever visible in the maze.
 * <p>
 * Walls come from a coarse noise field that is <em>dilated</em> before being
 * thresholded — the field is sampled at the block and at four points two blocks
 * away, and the maximum wins. That is what keeps walls at least three blocks
 * thick and corridors at least three blocks wide instead of degenerating into
 * one-block slits, which is the difference between "Backrooms" and "sponge".
 * A second, much coarser field opens whole regions into the large empty halls
 * that make a maze feel like a place rather than a puzzle.
 * <p>
 * Layout is configured under {@code layout:} in the profile:
 *
 * <pre>
 * layout:
 *   floor: 12
 *   ceiling-height: 5
 *   cell-size: 6
 *   wall-threshold: 0.42
 *   hall-scale: 9
 *   hall-threshold: 0.55
 * </pre>
 */
public final class BackroomsTerrain implements TerrainModule {

    /** How far the wall field is dilated, so walls can never be paper-thin. */
    private static final int DILATION = 2;

    @Override
    public GenerationStyle style() {
        return GenerationStyle.BACKROOMS;
    }

    @Override
    public Column generate(GenerationContext context, ChunkGenerator.ChunkData data,
                           int x, int z, int localX, int localZ) {
        PlanetProfile profile = context.profile();
        int floorY = clamp(profile.layoutInt("floor", profile.terrain().clampedBase()),
                context.floor() + 2, context.roof() - 14);
        int ceilingHeight = Math.max(3, profile.layoutInt("ceiling-height", 5));
        int floorThickness = Math.max(1, profile.layoutInt("floor-thickness", 2));
        int ceilingThickness = Math.max(1, profile.layoutInt("ceiling-thickness", 2));
        int ceilingY = floorY + ceilingHeight;

        Material floor = profile.palette().get("floor", Material.YELLOW_TERRACOTTA);
        Material wall = profile.palette().get("wall", Material.YELLOW_TERRACOTTA);
        Material accent = profile.palette().get("accent", Material.SMOOTH_SANDSTONE);
        Material ceiling = profile.palette().get("ceiling", Material.SMOOTH_SANDSTONE);

        BlockData floorData = floor.createBlockData();
        BlockData ceilingData = ceiling.createBlockData();
        TerrainModule.fill(data, localX, localZ, floorY - floorThickness + 1, floorY, floorData);
        TerrainModule.fill(data, localX, localZ, ceilingY, ceilingY + ceilingThickness - 1, ceilingData);

        if (isWall(context, x, z)) {
            TerrainModule.fill(data, localX, localZ, floorY + 1, ceilingY - 1, wall.createBlockData());
            // A skirting band top and bottom, the way a real hallway reads.
            data.setBlock(localX, floorY + 1, localZ, accent.createBlockData());
            data.setBlock(localX, ceilingY - 1, localZ, accent.createBlockData());
        } else if (profile.layoutFlag("lights", true) && isLight(context, x, z)) {
            // Ceiling lights: a deterministic scatter of lit blocks in the halls.
            data.setBlock(localX, ceilingY - 1, localZ, Material.LANTERN.createBlockData());
        }
        return Column.of(floorY, floor, null, false, null);
    }

    /** Whether this column is a wall. */
    private static boolean isWall(GenerationContext context, int x, int z) {
        PlanetProfile profile = context.profile();
        // Open halls: where the coarse field is high the plan has no walls at all.
        double hallScale = Math.max(4, profile.layoutDouble("hall-scale", 9));
        if (profile.layoutDouble("hall-threshold", 0.55) > 0
                && context.layout().sample(x / hallScale, z / hallScale)
                > profile.layoutDouble("hall-threshold", 0.55)) {
            return false;
        }
        double scale = Math.max(3, profile.layoutDouble("cell-size", 6) * 1.6);
        return wallField(context, x, z, scale) > profile.layoutDouble("wall-threshold", 0.42);
    }

    /**
     * The dilated wall field: the maximum of the layout noise at this block and at
     * four points {@link #DILATION} blocks away. Dilating before thresholding is
     * what guarantees a minimum wall thickness.
     */
    private static double wallField(GenerationContext context, int x, int z, double scale) {
        NoiseSampler layout = context.layout();
        double centre = layout.sample(x / scale, z / scale);
        double east = layout.sample((x + DILATION) / scale, z / scale);
        double west = layout.sample((x - DILATION) / scale, z / scale);
        double south = layout.sample(x / scale, (z + DILATION) / scale);
        double north = layout.sample(x / scale, (z - DILATION) / scale);
        return Math.max(Math.max(centre, Math.max(east, west)), Math.max(south, north));
    }

    /** A deterministic scatter of ceiling lights, on a grid of its own. */
    private static boolean isLight(GenerationContext context, int x, int z) {
        int spacing = 7;
        return Math.floorMod(x, spacing) == 3 && Math.floorMod(z, spacing) == 3;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean fillsLiquid() {
        return false;
    }

    @Override
    public String describe(GenerationContext context) {
        PlanetProfile profile = context.profile();
        return "backrooms: floor y " + profile.layoutInt("floor", profile.terrain().clampedBase())
                + ", ceiling +" + profile.layoutInt("ceiling-height", 5)
                + ", cell " + profile.layoutInt("cell-size", 6);
    }
}
