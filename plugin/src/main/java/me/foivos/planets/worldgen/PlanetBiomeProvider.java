package me.foivos.planets.worldgen;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells the client which vanilla biome each position is, so a planet gets
 * vanilla's biome-driven behaviour — foliage tint, weather, mob spawn tables —
 * while the terrain itself comes entirely from the plugin.
 * <p>
 * This is the bridge between the two concepts the engine deliberately keeps
 * apart: a {@link BiomeProfile} decides what a planet region <em>is</em> (blocks,
 * growth, structures) and this class decides what the game is told it looks like.
 * Several planet biomes may report the same vanilla biome.
 * <p>
 * The samplers are cached per world seed rather than rebuilt per query: this hook
 * is called for every block column the client asks about, so allocating here would
 * be the single most expensive thing the engine does.
 */
public final class PlanetBiomeProvider extends BiomeProvider {

    private final PlanetProfile profile;
    private final BiomeEngine engine;
    private final List<Biome> biomes;
    /** seed -> the three climate samplers for that world. */
    private final Map<Long, Climate> climates = new ConcurrentHashMap<>();

    /** The climate samplers of one world. */
    private record Climate(NoiseSampler temperature, NoiseSampler humidity, NoiseSampler weirdness) {

        static Climate build(long seed) {
            return new Climate(
                    new NoiseSampler(NoiseSettings.defaults(NoiseType.PERLIN, 640, 1), seed),
                    new NoiseSampler(NoiseSettings.defaults(NoiseType.PERLIN, 520, 1), seed + 7919),
                    new NoiseSampler(NoiseSettings.defaults(NoiseType.PERLIN, 300, 1), seed + 104729));
        }
    }

    public PlanetBiomeProvider(PlanetProfile profile, BiomeEngine engine) {
        this.profile = profile;
        this.engine = engine;
        this.biomes = engine.minecraftBiomes();
    }

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        long seed = worldInfo == null ? 0L : worldInfo.getSeed();
        Climate climate = climates.computeIfAbsent(seed, Climate::build);
        // The same climate maps the generator used, so the biome the client is told
        // about always matches the blocks it can see. Only the altitude differs: the
        // generator knows the real surface height, while this hook is asked about an
        // arbitrary y, so it answers with the y it was given.
        double temperature = normalize(climate.temperature().sample(x, z));
        double humidity = normalize(climate.humidity().sample(x, z));
        double weirdness = climate.weirdness().sample(x, z);
        return engine.minecraftBiome(engine.biomeFor(
                new BiomeEngine.Climate(temperature, humidity, weirdness, y)));
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return biomes;
    }

    /** The planet this provider belongs to. */
    public PlanetProfile profile() {
        return profile;
    }

    private static double normalize(double value) {
        return Math.max(0, Math.min(1, (value + 1.0) * 0.5));
    }
}
