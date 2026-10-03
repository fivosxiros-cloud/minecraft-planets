package me.foivos.planets.worldgen;

import org.bukkit.block.Biome;

import java.util.List;

/**
 * Chooses a planet biome for every column from the planet's environmental maps.
 * <p>
 * Nothing here rolls dice: the temperature, humidity and weirdness maps are
 * continuous noise fields, so neighbouring columns almost always land in the same
 * biome and regions come out as coherent patches with real borders between them.
 * A column's altitude comes from the terrain that was just generated, which is
 * what makes a planet's mountains snowy and its valleys swampy without a single
 * hard-coded rule.
 * <p>
 * The decision order is deliberate:
 * <ol>
 *   <li>Planet biomes whose ranges all match, best (highest) priority first.</li>
 *   <li>The planet's default biome.</li>
 * </ol>
 * Because a profile can leave a biome's ranges wide open, "one biome everywhere"
 * and "twenty biomes in tight bands" are both one file apart.
 */
public final class BiomeEngine {

    private final PlanetProfile profile;

    public BiomeEngine(PlanetProfile profile) {
        this.profile = profile;
    }

    /** The environment at one column, reused by surface and decoration passes. */
    public record Climate(double temperature, double humidity, double weirdness, int altitude) {
    }

    /** Samples the climate maps for a column. */
    public Climate climate(GenerationContext context, int x, int z, int altitude) {
        double temperature = normalize(context.biomeMap("temperature").sample(x, z));
        double humidity = normalize(context.biomeMap("humidity").sample(x, z));
        double weirdness = context.biomeMap("weirdness").sample(x, z);
        return new Climate(temperature, humidity, weirdness, altitude);
    }

    /** The planet biome for a climate, never null. */
    public BiomeProfile biomeFor(Climate climate) {
        BiomeProfile best = null;
        for (BiomeProfile biome : profile.biomes().values()) {
            if (!biome.matches(climate.temperature(), climate.humidity(),
                    climate.altitude(), climate.weirdness())) {
                continue;
            }
            if (best == null || biome.priority() > best.priority()) {
                best = biome;
            }
        }
        return best != null ? best : profile.fallbackBiome();
    }

    /** The Minecraft biome a planet biome reports to the client. */
    public Biome minecraftBiome(BiomeProfile biome) {
        Biome resolved = PlanetBiomes.biome(biome.biome());
        return resolved != null ? resolved : org.bukkit.block.Biome.PLAINS;
    }

    /** Every Minecraft biome this planet can report, for the biome provider. */
    public List<Biome> minecraftBiomes() {
        List<Biome> biomes = new java.util.ArrayList<>();
        for (BiomeProfile biome : profile.biomes().values()) {
            Biome resolved = PlanetBiomes.biome(biome.biome());
            if (resolved != null && !biomes.contains(resolved)) {
                biomes.add(resolved);
            }
        }
        if (biomes.isEmpty()) {
            biomes.add(org.bukkit.block.Biome.PLAINS);
        }
        return biomes;
    }

    /** Maps noise in {@code [-1, 1]} onto a 0..1 climate dial. */
    private static double normalize(double value) {
        return Math.max(0, Math.min(1, (value + 1.0) * 0.5));
    }
}
