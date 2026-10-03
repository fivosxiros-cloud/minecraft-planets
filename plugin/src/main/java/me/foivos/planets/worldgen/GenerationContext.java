package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.generator.WorldInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One world's built generation state: every sampler, every resolved material and
 * the derived limits the generators read.
 * <p>
 * Built once per world and then shared by every chunk, this is where the engine
 * spends its money on quality (building samplers) instead of per block. It is
 * immutable after construction and holds no mutable scratch state, so the server
 * can call it from several generation threads at once — which is what keeps a
 * planet with eight players wandering in eight directions from becoming a lag
 * spike.
 */
public final class GenerationContext {

    private final PlanetProfile profile;
    private final long seed;
    private final int minHeight;
    private final int maxHeight;
    private final int seaLevel;
    private final Material liquid;

    /** Named height-stack layers, in order. */
    private final List<Layer> layers;
    private final Map<String, NoiseSampler> biomeMaps;
    private final NoiseSampler distortionX;
    private final NoiseSampler distortionZ;
    private final NoiseSampler detail;
    private final NoiseSampler cavesPrimary;
    private final NoiseSampler cavesSecondary;
    private final NoiseSampler cavesChambers;
    private final NoiseSampler floatingIslands;
    private final NoiseSampler layout;
    private final NoiseSampler mountains;
    private final NoiseSampler valleys;
    private final NoiseSampler canyons;
    private final NoiseSampler cliffs;
    private final NoiseSampler plateaus;
    private final NoiseSampler islands;
    private final Map<String, Material> materials;

    /** A height-stack layer with its sampler already built. */
    public record Layer(String id, NoiseSampler sampler) {
    }

    public GenerationContext(PlanetProfile profile, long seed, WorldInfo world) {
        this.profile = profile;
        this.seed = seed;
        this.minHeight = world.getMinHeight();
        this.maxHeight = world.getMaxHeight();
        TerrainSettings terrain = profile.terrain();
        this.seaLevel = terrain.seaLevel();
        this.liquid = terrain.liquid() == null ? null
                : profile.palette().get(terrain.liquid(), Material.WATER);

        List<Layer> built = new java.util.ArrayList<>();
        for (TerrainSettings.Layer layer : terrain.layers()) {
            built.add(new Layer(layer.id(), new NoiseSampler(layer.noise(), seed)));
        }
        this.layers = List.copyOf(built);

        // The biome maps are the environmental dials every column is measured
        // against: temperature, humidity and weirdness, plus altitude from the
        // terrain itself. Same shape as vanilla's climate parameters, but entirely
        // configurable and per planet.
        Map<String, NoiseSampler> maps = new LinkedHashMap<>();
        maps.put("temperature", new NoiseSampler(NoiseSettings.defaults(NoiseType.PERLIN, 640, 1), seed));
        maps.put("humidity", new NoiseSampler(NoiseSettings.defaults(NoiseType.PERLIN, 520, 1), seed + 7919));
        maps.put("weirdness", new NoiseSampler(NoiseSettings.defaults(NoiseType.PERLIN, 300, 1), seed + 104729));
        this.biomeMaps = Map.copyOf(maps);

        this.distortionX = terrain.distortionStrength() > 0
                ? new NoiseSampler(new NoiseSettings(NoiseType.PERLIN, 4242, 1, 1, 1, 0.5, 2.0,
                terrain.distortionStrength(), terrain.distortionFrequency()), seed) : null;
        this.distortionZ = terrain.distortionStrength() > 0
                ? new NoiseSampler(new NoiseSettings(NoiseType.PERLIN, 8484, 1, 1, 1, 0.5, 2.0,
                terrain.distortionStrength(), terrain.distortionFrequency()), seed) : null;
        this.detail = terrain.detailStrength() > 0
                ? new NoiseSampler(new NoiseSettings(NoiseType.PERLIN, 31337, 1.0 / terrain.detailFrequency(),
                1, 2, 0.5, 2.0, 0.0, 0.0), seed) : null;

        this.mountains = sampler(terrain.mountains(), 11);
        this.valleys = sampler(terrain.valleys(), 23);
        this.canyons = sampler(terrain.canyons(), 37);
        this.cliffs = sampler(terrain.cliffs(), 53);
        this.plateaus = sampler(terrain.plateaus(), 71);
        this.islands = terrain.islands().enabled()
                ? new NoiseSampler(new NoiseSettings(NoiseType.PERLIN, 89, 1.0 / terrain.islands().frequency(),
                1, 2, 0.5, 2.0, 0.0, 0.0), seed) : null;
        this.floatingIslands = terrain.floatingIslands().enabled()
                ? new NoiseSampler(new NoiseSettings(NoiseType.PERLIN, 97, 1.0 / terrain.floatingIslands().noiseScale(),
                1.0, 3, 0.5, 2.2, 0.0, 0.0), seed) : null;
        // The architectural styles (maze, Backrooms) get their own layout field:
        // one coarse noise map that decides where walls are, sampled per block but
        // evaluated on a much larger lattice.
        this.layout = new NoiseSampler(new NoiseSettings(NoiseType.VALUE, 131,
                Math.max(4, profile.layoutDouble("cell-size", 6) * 1.6), 1, 3, 0.5, 2.0, 0.0, 0.0), seed);

        CaveSettings caves = profile.caves();
        this.cavesPrimary = caves.carves()
                ? new NoiseSampler(new NoiseSettings(NoiseType.PERLIN, 101, caves.tunnels().scale(),
                1, 2, 0.5, 2.4, 0.0, 0.0), seed) : null;
        this.cavesSecondary = caves.carves()
                ? new NoiseSampler(new NoiseSettings(NoiseType.PERLIN, 103,
                Math.max(8, caves.tunnels().scale() * 0.6), 1, 2, 0.5, 2.6, 0.0, 0.0), seed) : null;
        this.cavesChambers = caves.carves()
                ? new NoiseSampler(new NoiseSettings(NoiseType.PERLIN, 107,
                Math.max(8, caves.caverns().scale()), 1, 2, 0.55, 2.2, 0.0, 0.0), seed) : null;

        Map<String, Material> resolved = new LinkedHashMap<>();
        for (String key : profile.palette().keys()) {
            Material material = profile.palette().get(key, null);
            if (material != null) {
                resolved.put(key, material);
            }
        }
        this.materials = Map.copyOf(resolved);
    }

    /**
     * Builds a feature's sampler. Sharp features (high sharpness) get domain
     * warping, which is what turns a smooth hill into a ragged mountain face;
     * soft ones are left plain so valleys stay smooth.
     */
    private static NoiseSampler sampler(TerrainSettings.Feature feature, long offset) {
        if (!feature.enabled() || feature.strength() <= 0) {
            return null;
        }
        boolean sharp = feature.sharpness() >= 0.55;
        return new NoiseSampler(new NoiseSettings(NoiseType.PERLIN, offset, feature.scale(), 1, 4, 0.5, 2.0,
                sharp ? Math.max(8, feature.scale() * 0.12) : 0,
                sharp ? 1.0 / Math.max(8, feature.scale() * 0.4) : 0), offset);
    }

    public PlanetProfile profile() {
        return profile;
    }

    public long seed() {
        return seed;
    }

    public int minHeight() {
        return minHeight;
    }

    public int maxHeight() {
        return maxHeight;
    }

    public int seaLevel() {
        return seaLevel;
    }

    /** The liquid oceans are filled with, or null when the planet has no liquid. */
    public Material liquid() {
        return liquid;
    }

    public List<Layer> layers() {
        return layers;
    }

    public NoiseSampler biomeMap(String name) {
        return biomeMaps.get(name);
    }

    public NoiseSampler distortionX() {
        return distortionX;
    }

    public NoiseSampler distortionZ() {
        return distortionZ;
    }

    public NoiseSampler detail() {
        return detail;
    }

    public NoiseSampler cavesPrimary() {
        return cavesPrimary;
    }

    public NoiseSampler cavesSecondary() {
        return cavesSecondary;
    }

    public NoiseSampler cavesChambers() {
        return cavesChambers;
    }

    public NoiseSampler floatingIslands() {
        return floatingIslands;
    }

    /** The layout field used by the architectural styles (walls, corridors, halls). */
    public NoiseSampler layout() {
        return layout;
    }

    public NoiseSampler mountains() {
        return mountains;
    }

    public NoiseSampler valleys() {
        return valleys;
    }

    public NoiseSampler canyons() {
        return canyons;
    }

    public NoiseSampler cliffs() {
        return cliffs;
    }

    public NoiseSampler plateaus() {
        return plateaus;
    }

    public NoiseSampler islands() {
        return islands;
    }

    /** A resolved palette material by key, or null. */
    public Material material(String key) {
        return key == null ? null : materials.get(key.toLowerCase(java.util.Locale.ROOT));
    }

    /** The world's floor: the lowest y the generator may use. */
    public int floor() {
        return minHeight + 1;
    }

    /** The world's roof: the highest y the generator may use. */
    public int roof() {
        return maxHeight - 1;
    }
}
