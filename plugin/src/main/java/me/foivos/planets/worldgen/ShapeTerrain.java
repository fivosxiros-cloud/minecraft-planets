package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

/**
 * The non-natural terrain modules: worlds whose shape is not a heightmap at all.
 * <p>
 * Keeping them in one file is deliberate — each one is short, and they all share
 * the same idea: decide which blocks exist, then report what the column's top
 * block is so the surface pass can dress it. A world with genuinely new
 * behaviour gets its own module class; these are the three that ship.
 */
public final class ShapeTerrain {

    private ShapeTerrain() {
    }

    /**
     * Disconnected terrain hanging in the sky.
     * <p>
     * Each floating blob is a 3D noise field evaluated around a lattice cell's
     * own centre: above the centre the field is thresholded into a bumpy top,
     * below it into a tapered root, and beyond it there is nothing at all. That
     * is what makes the islands read as islands rather than as hills with the
     * ground deleted.
     */
    public static final class FloatingIslands implements TerrainModule {

        @Override
        public GenerationStyle style() {
            return GenerationStyle.FLOATING_ISLANDS;
        }

        @Override
        public Column generate(GenerationContext context, ChunkGenerator.ChunkData data,
                               int x, int z, int localX, int localZ) {
            TerrainSettings.FloatingIslandSettings settings = context.profile().terrain().floatingIslands();
            NoiseSampler sampler = context.floatingIslands();
            if (sampler == null) {
                return Column.empty();
            }
            Material core = context.profile().palette().get(settings.core(), Material.STONE);
            Material shell = context.profile().palette().get(settings.shell(), Material.GRASS_BLOCK);
            Material underside = context.profile().palette().get(settings.underside(), Material.DEEPSLATE);
            BlockData coreData = core.createBlockData();
            BlockData shellData = shell.createBlockData();
            BlockData undersideData = underside.createBlockData();

            int top = Integer.MIN_VALUE;
            int bottom = Integer.MAX_VALUE;
            // The blob's own vertical band, so an island never reaches the world floor.
            for (int y = settings.maxY(); y >= settings.minY(); y--) {
                double density = densityAt(sampler, settings, x, y, z);
                if (density <= 0) {
                    continue;
                }
                BlockData block = coreData;
                if (top == Integer.MIN_VALUE) {
                    top = y;
                }
                if (y >= top - 2) {
                    block = shellData;
                } else if (y <= top - settings.undersideDepth()) {
                    block = undersideData;
                }
                bottom = y;
                data.setBlock(localX, y, localZ, block);
            }
            if (top == Integer.MIN_VALUE) {
                return Column.empty();
            }
            // Only the topmost island counts as this column's ground, so the
            // surface pass dresses the highest one and never the roots below it.
            return Column.of(top, shell, null, false, null);
        }

        /**
         * How solid the sky is at a point. Positive means inside a blob; the
         * magnitude is used to shape the root, so the underside tapers instead of
         * ending flat.
         */
        private static double densityAt(NoiseSampler sampler, TerrainSettings.FloatingIslandSettings settings,
                                        int x, int y, int z) {
            double vertical = (y - settings.minY()) / (double) settings.spanY();
            double centre = 0.45 + 0.2 * sampler.sample(x * 0.35, z * 0.35);
            double distance = Math.abs(vertical - centre);
            double threshold = settings.density();
            double noise = sampler.sample(x, y * 2.4, z);
            return noise * 0.6 + (threshold - distance) * 2.4;
        }

        @Override
        public boolean fillsLiquid() {
            return false;
        }
    }

    /**
     * A solid world with a cave network instead of a surface: you spawn inside
     * the rock and the walkable space is carved out of it.
     * <p>
     * The rock is filled up to a ceiling band, and the {@link CaveGenerator}
     * carves the navigable voids — the same carver every other planet uses, just
     * turned up and inverted in importance.
     */
    public static final class Cavern implements TerrainModule {

        @Override
        public GenerationStyle style() {
            return GenerationStyle.CAVERN_WORLD;
        }

        @Override
        public Column generate(GenerationContext context, ChunkGenerator.ChunkData data,
                               int x, int z, int localX, int localZ) {
            TerrainSettings terrain = context.profile().terrain();
            double height = NaturalTerrain.heightAt(context, x, z, terrain);
            int surfaceY = (int) Math.floor(Math.max(height, terrain.minHeight() + 8));
            Material rock = context.profile().palette().get("stone", Material.STONE);
            TerrainModule.fill(data, localX, localZ, context.floor(), surfaceY, rock.createBlockData());
            // The "surface" of a cavern world is its ceiling, so the surface pass
            // lines the roof of the world rather than a ground layer nobody sees.
            return Column.of(surfaceY, rock, null, false, null);
        }

        @Override
        public boolean fillsLiquid() {
            return false;
        }
    }

    /** Nothing but air. Used for stations and deliberate voids. */
    public static final class Void implements TerrainModule {

        @Override
        public GenerationStyle style() {
            return GenerationStyle.VOID_WORLD;
        }

        @Override
        public Column generate(GenerationContext context, ChunkGenerator.ChunkData data,
                               int x, int z, int localX, int localZ) {
            return Column.empty();
        }

        @Override
        public boolean fillsLiquid() {
            return false;
        }
    }
}
