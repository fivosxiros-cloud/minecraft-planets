package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

/**
 * Carves the underground.
 * <p>
 * Caves are cut from the noise fields directly rather than by walking a worm,
 * which means a chunk can be carved knowing nothing about its neighbours: the
 * field at a block is the same value whether the chunk next door exists yet or
 * not. That is what makes caves continuous across chunk borders with no
 * bookkeeping and no cascading chunk loads.
 * <p>
 * Three systems, each independently configurable:
 * <ul>
 *   <li><b>Tunnels</b> — the product of two independent fields. Multiplying them
 *       is what turns two blobby fields into long connected tubes: a tunnel exists
 *       only where <em>both</em> are near their threshold, which happens along
 *       lines rather than in patches.</li>
 *   <li><b>Caverns</b> — one wider field, so the result is a chamber rather than a
 *       tunnel.</li>
 *   <li><b>Chambers</b> — caverns with a floor of liquid (lava seas, toxic pools)
 *       and optionally walls lined with crystals.</li>
 * </ul>
 */
public final class CaveGenerator {

    /** Hard cap on how much of a column may be carved, so no chunk becomes a void. */
    private static final double MAX_CARVE_FRACTION = 0.72;

    private final PlanetProfile profile;

    public CaveGenerator(PlanetProfile profile) {
        this.profile = profile;
    }

    /**
     * Carves the cave systems for one column.
     *
     * @param surfaceY the column's top solid block, so caves never break the surface
     *                 unless the profile asks for surface entrances
     */
    public void carve(GenerationContext context, ChunkGenerator.ChunkData data,
                      int x, int z, int localX, int localZ, int surfaceY, String biomeId) {
        CaveSettings caves = profile.caves();
        if (!caves.carves() || surfaceY <= context.minHeight() + 2) {
            return;
        }
        CaveSettings.Override override = caves.overrideFor(biomeId);
        if (override != null && !override.enabled()) {
            return;
        }

        int low = Math.max(context.floor() + 1, context.minHeight() + 1);
        int high = Math.min(surfaceY - 1, context.roof());
        if (high - low < 3) {
            return;
        }
        // Caves are carved from the bottom up; the counter stops a pathological
        // profile from hollowing out a whole column.
        int budget = (int) Math.max(4, (high - low) * MAX_CARVE_FRACTION);

        Material lava = profile.palette().get("lava", Material.LAVA);
        Material water = context.liquid() == null ? Material.WATER : context.liquid();
        boolean floodLakes = caves.undergroundLakes() && context.liquid() != null;

        for (int y = low; y <= high && budget > 0; y++) {
            if (isTunnel(context, caves, override, x, y, z, surfaceY, low, high)) {
                data.setBlock(localX, y, localZ, Material.CAVE_AIR.createBlockData());
                budget--;
                continue;
            }
            if (isCavern(context, caves, override, x, y, z, low, high)) {
                data.setBlock(localX, y, localZ, Material.CAVE_AIR.createBlockData());
                budget--;
            }
        }
        fillChamber(context, caves, override, data, x, y(context), localX, localZ, low, high,
                lava, water, floodLakes);
    }

    /** The y the chamber fill looks at: the bottom of the carved band. */
    private static int y(GenerationContext context) {
        return context.minHeight();
    }

    /**
     * A tunnel exists where two independent fields are both near their threshold.
     * The fields are sampled at 2.2× the configured vertical scale so tunnels are
     * taller than they are wide, which is what stops them looking like drill holes.
     */
    private boolean isTunnel(GenerationContext context, CaveSettings caves,
                             CaveSettings.Override override, int x, int y, int z,
                             int surfaceY, int low, int high) {
        CaveSettings.System tunnels = caves.tunnels();
        if (tunnels == null || !tunnels.enabled()) {
            return false;
        }
        double density = override != null && override.tunnelDensity() != null
                ? override.tunnelDensity() : tunnels.density();
        if (density <= 0) {
            return false;
        }
        int top = Math.min(tunnels.high(context.maxHeight()), surfaceY - 2);
        int bottom = tunnels.low(context.minHeight());
        if (y < bottom || y > top) {
            return false;
        }
        double vertical = y * 2.2 * tunnels.size();
        double first = context.cavesPrimary().sample(x, vertical, z);
        double second = context.cavesSecondary().sample(x + 1024, vertical, z - 1024);
        // Both fields must be near zero at once: that intersection is a tube.
        double threshold = 0.09 * (0.4 + density);
        return Math.abs(first) < threshold && Math.abs(second) < threshold;
    }

    /** A cavern exists where one wide field is near its threshold. */
    private boolean isCavern(GenerationContext context, CaveSettings caves,
                             CaveSettings.Override override, int x, int y, int z,
                             int low, int high) {
        CaveSettings.System caverns = caves.caverns();
        if (caverns == null || !caverns.enabled() || context.cavesChambers() == null) {
            return false;
        }
        double density = override != null && override.cavernDensity() != null
                ? override.cavernDensity() : caverns.density();
        if (density <= 0) {
            return false;
        }
        int top = caverns.high(context.maxHeight());
        int bottom = caverns.low(context.minHeight());
        if (y < bottom || y > top) {
            return false;
        }
        double value = context.cavesChambers().sample(x, y * 1.4 * caverns.size(), z);
        double threshold = 0.28 * (0.2 + density * 2);
        return Math.abs(value) < threshold;
    }

    /**
     * Pools a liquid on the floor of any chamber the column is inside, and lines
     * the walls with the chamber's lining block. This runs per column because the
     * liquid level depends on where the chamber's floor is, which is only known
     * once the carving above has happened.
     */
    private void fillChamber(GenerationContext context, CaveSettings caves,
                             CaveSettings.Override override, ChunkGenerator.ChunkData data,
                             int x, int ignored, int localX, int localZ, int low, int high,
                             Material lava, Material water, boolean floodLakes) {
        CaveSettings.Chambers lavaChambers = caves.lavaChambers();
        boolean lavaOverride = override != null && override.lavaChambers();
        if (lavaChambers.enabled() || lavaOverride) {
            pool(data, localX, localZ, context, x, lavaChambers, high, lava, true);
        }
        CaveSettings.Chambers crystals = caves.crystalCaves();
        boolean crystalOverride = override != null && override.crystalCaves();
        if (crystals.enabled() || crystalOverride) {
            lineChamber(data, localX, localZ, context, x, crystals, high, low);
        }
        if (floodLakes) {
            pool(data, localX, localZ, context, x, caves.caverns() == null ? null : null, high, water, false);
        }
    }

    /** Fills the bottom of a chamber with a liquid, where the chamber covers this column. */
    private void pool(ChunkGenerator.ChunkData data, int localX, int localZ,
                      GenerationContext context, int x, CaveSettings.Chambers chambers,
                      int high, Material liquid, boolean requireChamber) {
        if (liquid == null || chambers == null) {
            return;
        }
        int bottom = chambers.low(context.minHeight());
        int top = chambers.high(context.maxHeight());
        int level = bottom + Math.max(1, chambers.liquidLevel());
        for (int y = bottom; y < Math.min(level, high); y++) {
            if (data.getType(localX, y, localZ) == Material.CAVE_AIR) {
                data.setBlock(localX, y, localZ, liquid.createBlockData());
            }
        }
    }

    /** Lines the walls of a crystal cave. */
    private void lineChamber(ChunkGenerator.ChunkData data, int localX, int localZ,
                             GenerationContext context, int x, CaveSettings.Chambers chambers,
                             int high, int low) {
        Material lining = profile.palette().get(chambers.lining(), Material.AMETHYST_BLOCK);
        BlockData block = lining.createBlockData();
        int bottom = chambers.low(context.minHeight());
        int top = chambers.high(context.maxHeight());
        for (int y = Math.max(bottom, low); y <= Math.min(top, high); y++) {
            if (data.getType(localX, y, localZ) != Material.CAVE_AIR) {
                continue;
            }
            // A shell only on the chamber's boundary: any solid neighbour means
            // this block is a wall, which is exactly where crystals grow.
            if (isSolid(data, localX + 1, y, localZ) || isSolid(data, localX - 1, y, localZ)
                    || isSolid(data, localX, y, localZ + 1) || isSolid(data, localX, y, localZ - 1)
                    || isSolid(data, localX, y + 1, localZ)) {
                if (chambers.liningDepth() <= 1 || (y & 1) == 0) {
                    data.setBlock(localX, y, localZ, block);
                }
            }
        }
    }

    private static boolean isSolid(ChunkGenerator.ChunkData data, int x, int y, int z) {
        Material type = data.getType(x, y, z);
        return type != null && type.isSolid() && type != Material.CAVE_AIR;
    }
}
