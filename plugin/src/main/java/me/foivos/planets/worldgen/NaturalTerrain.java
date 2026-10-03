package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

/**
 * The engine's main terrain module: large-scale geography, medium-scale features
 * and small-scale detail, added up into one height per column.
 * <p>
 * The sum is deliberately explicit rather than one clever formula, because each
 * term is something an admin can switch off and see the difference:
 *
 * <pre>
 * height = base
 *        + Σ layer noise          (continentalness, erosion, weirdness, custom)
 *        + mountains              (ridged, domain-warped)
 *        − valleys
 *        − canyons
 *        ± cliffs                 (stepped)
 *        + plateaus               (flat-topped)
 *        + islands                (circular falloff, for ocean worlds)
 *        + detail
 * </pre>
 *
 * Everything is sampled in the column's <em>distorted</em> coordinates, so the
 * whole landscape warps together instead of features sliding over each other.
 * <p>
 * One thing this module deliberately does not do: build the surface. It writes
 * solid rock up to the height it computed and reports the height; the surface
 * pass decides what the top blocks are, which is what lets a single terrain
 * shape carry grass, sand, snow or basalt depending on the biome.
 */
public final class NaturalTerrain implements TerrainModule {

    /** How far below the surface the module fills: deeper is wasted work. */
    private static final int COLUMN_DEPTH = 96;

    @Override
    public GenerationStyle style() {
        return GenerationStyle.NATURAL;
    }

    @Override
    public Column generate(GenerationContext context, ChunkGenerator.ChunkData data,
                           int x, int z, int localX, int localZ) {
        TerrainSettings terrain = context.profile().terrain();
        double height = heightAt(context, x, z, terrain);
        int surfaceY = (int) Math.floor(height);
        if (surfaceY < context.minHeight()) {
            return Column.empty();
        }
        surfaceY = Math.min(surfaceY, context.roof());

        Material ground = context.profile().palette().get("stone", Material.STONE);
        BlockData stone = ground.createBlockData();
        int bottom = Math.max(context.floor(), surfaceY - COLUMN_DEPTH);
        TerrainModule.fill(data, localX, localZ, bottom, surfaceY, stone);
        return Column.of(surfaceY, ground, null, surfaceY < context.seaLevel(), null);
    }

    /**
     * The terrain height at a world coordinate. Public because the rest of the
     * pipeline needs it too: the surface pass, caves that follow the ground,
     * structure placement and {@code getBaseHeight} all ask the same function,
     * which is what keeps them consistent with each other.
     */
    public static double heightAt(GenerationContext context, int x, int z, TerrainSettings terrain) {
        double sampleX = x;
        double sampleZ = z;
        if (context.distortionX() != null) {
            sampleX += context.distortionX().sample(x, z) * terrain.distortionStrength();
            sampleZ += context.distortionZ().sample(x, z) * terrain.distortionStrength();
        }
        double horizontal = terrain.horizontalScale() <= 0 ? 1 : terrain.horizontalScale();
        double sx = sampleX * horizontal;
        double sz = sampleZ * horizontal;

        double height = terrain.clampedBase();
        for (GenerationContext.Layer layer : context.layers()) {
            height += layer.sampler().sample(sx, sz) * layer.sampler().settings().amplitude();
        }

        height += feature(context.mountains(), sx, sz, terrain.mountains());
        height -= feature(context.valleys(), sx, sz, terrain.valleys());
        height -= canyon(context.canyons(), sx, sz, terrain.canyons());
        height += cliffs(context.cliffs(), sx, sz, terrain.cliffs());
        height += plateaus(context.plateaus(), sx, sz, terrain.plateaus());
        height += islandHeight(context, sampleX, sampleZ, terrain);

        if (context.detail() != null) {
            height += context.detail().sample(sx, sz) * terrain.detailStrength();
        }
        height *= terrain.verticalScale();

        return Math.max(terrain.minHeight(), Math.min(terrain.maxHeight(), height));
    }

    /** A soft, shaped feature: mountains and valleys both read this. */
    private static double feature(NoiseSampler sampler, double x, double z, TerrainSettings.Feature feature) {
        if (sampler == null || !feature.enabled()) {
            return 0;
        }
        double value = sampler.sample(x, z);
        double shaped = shape(value, feature.sharpness());
        return shaped * feature.strength();
    }

    /**
     * Canyons: narrow, deep, and only where the canyon field crosses its band.
     * The {@code width} is the band width in noise units, so a small width means a
     * thin crack rather than a wide valley.
     */
    private static double canyon(NoiseSampler sampler, double x, double z, TerrainSettings.Feature feature) {
        if (sampler == null || !feature.enabled()) {
            return 0;
        }
        double value = Math.abs(sampler.sample(x, z));
        double band = Math.max(1e-4, feature.width() / Math.max(1.0, feature.scale()));
        if (value > band) {
            return 0;
        }
        double depth = 1.0 - (value / band);
        return Math.pow(depth, 1.0 + feature.sharpness() * 2) * feature.strength();
    }

    /**
     * Cliffs: the terrain is quantised into steps, which is what turns a slope
     * into a scarp. The step size is {@code width}, so a wider width is a taller
     * cliff rather than a longer one.
     */
    private static double cliffs(NoiseSampler sampler, double x, double z, TerrainSettings.Feature feature) {
        if (sampler == null || !feature.enabled()) {
            return 0;
        }
        double value = sampler.sample(x, z);
        double step = Math.max(1.0, feature.width());
        double stepped = Math.round(value * feature.strength() / step) * step;
        double blend = feature.sharpness();
        return stepped * blend + value * feature.strength() * (1 - blend);
    }

    /** Plateaus: a mesa-like flattening wherever the plateau field is high. */
    private static double plateaus(NoiseSampler sampler, double x, double z, TerrainSettings.Feature feature) {
        if (sampler == null || !feature.enabled()) {
            return 0;
        }
        double value = sampler.sample(x, z);
        double threshold = 0.25;
        if (value < threshold) {
            return 0;
        }
        double rise = (value - threshold) / (1 - threshold);
        double sharpened = Math.pow(rise, 1 + (1 - feature.sharpness()) * 3);
        return sharpened * feature.strength();
    }

    /**
     * Islands: a field of circular humps with a falloff, used by ocean and
     * archipelago worlds. The falloff is what decides whether an island has a
     * beach shelf or drops straight into deep water.
     */
    private static double islandHeight(GenerationContext context, double x, double z,
                                       TerrainSettings terrain) {
        NoiseSampler sampler = context.islands();
        if (sampler == null || !terrain.islands().enabled()) {
            return 0;
        }
        TerrainSettings.IslandSettings islands = terrain.islands();
        // The island field is sampled on its own coarse lattice, so each lattice
        // cell is one island. distance is 0 at the cell's jittered centre and
        // reaches 1 at its edge, which is what gives every island a shore.
        double cellX = Math.floor(x * islands.frequency());
        double cellZ = Math.floor(z * islands.frequency());
        double centreX = (cellX + 0.5 + 0.35 * (sampler.sample(cellX * 91.7, cellZ * 17.3))) / islands.frequency();
        double centreZ = (cellZ + 0.5 + 0.35 * (sampler.sample(cellX * 23.1 + 512, cellZ * 57.9))) / islands.frequency();
        double radius = Math.max(8.0, islands.size());
        double dx = x - centreX;
        double dz = z - centreZ;
        double distance = Math.sqrt(dx * dx + dz * dz) / radius;
        if (distance >= 1.0) {
            return 0;
        }
        // A shelf near the rim, rising to a rounded middle; "falloff" decides how
        // quickly the shelf gives way to open water.
        double falloff = Math.pow(1.0 - distance, 1 + islands.falloff() * 3);
        double variation = 0.75 + 0.25 * sampler.sample(x + 4096, z - 4096);
        return falloff * islands.height() * variation;
    }

    /** Shapes a raw noise value for a feature's sharpness. */
    private static double shape(double value, double sharpness) {
        if (sharpness <= 0) {
            return value;
        }
        double sign = Math.signum(value);
        double magnitude = Math.pow(Math.abs(value), 1 + (1 - sharpness) * 3);
        return sign * magnitude;
    }

    @Override
    public String describe(GenerationContext context) {
        TerrainSettings terrain = context.profile().terrain();
        return "natural: " + context.layers().size() + " layer(s), "
                + terrain.minHeight() + ".." + terrain.maxHeight() + ", sea " + terrain.seaLevel();
    }
}
