package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * Dresses the top of a column: grass over dirt over stone, sand over sandstone,
 * basalt over blackstone, snow above the snowline — whatever the profile says.
 * <p>
 * Layers are listed top → down and the first one whose conditions match wins, so
 * a single biome can look different at altitude or in the cold:
 *
 * <pre>
 * surface:
 *   layers:
 *     - { block: SNOW_BLOCK, depth: 2, min-altitude: 150 }
 *     - { block: GRASS_BLOCK, depth: 1 }
 *     - { block: DIRT, depth: 4 }
 *     - { block: STONE, depth: 24 }
 *     - { block: DEEPSLATE, depth: 999 }
 * </pre>
 *
 * The generator never invents a block: an unresolvable name was already replaced
 * by the profile loader, so what runs here is always real materials.
 */
public final class SurfaceGenerator {

    /** How deep the stack may go before the generator stops caring. */
    private static final int MAX_DEPTH = 64;

    private final PlanetProfile profile;

    public SurfaceGenerator(PlanetProfile profile) {
        this.profile = profile;
    }

    /**
     * Builds the surface for one column.
     *
     * @param biome the planet biome the column landed in
     * @return the y of the topmost block placed
     */
    public int build(GenerationContext context, ChunkGenerator.ChunkData data,
                     int localX, int localZ, int surfaceY, BiomeProfile biome,
                     BiomeEngine.Climate climate, boolean underwater) {
        if (surfaceY <= context.minHeight()) {
            return surfaceY;
        }
        List<BiomeProfile.SurfaceLayer> layers = biome.surface().isEmpty()
                ? profile.surface() : biome.surface();
        if (layers.isEmpty()) {
            return surfaceY;
        }
        int y = surfaceY;
        int placed = 0;
        for (BiomeProfile.SurfaceLayer layer : layers) {
            if (!layer.matches(surfaceY, climate.temperature())) {
                continue;
            }
            Material material = profile.palette().get(layer.block(), null);
            if (material == null) {
                continue;
            }
            BlockData block = material.createBlockData();
            for (int i = 0; i < layer.depth() && placed < MAX_DEPTH; i++, y--, placed++) {
                if (y <= context.minHeight()) {
                    return surfaceY;
                }
                data.setBlock(localX, y, localZ, block);
            }
            if (placed >= MAX_DEPTH) {
                break;
            }
        }
        // A submerged surface that was dressed as dry land reads as a mistake, so
        // the top block of any underwater column is swapped for the biome's own
        // wet-looking material when the profile gives one.
        if (underwater) {
            Material wet = wetSurface(context, biome);
            if (wet != null) {
                data.setBlock(localX, surfaceY, localZ, wet.createBlockData());
            }
        }
        return surfaceY;
    }

    /** The block an underwater surface uses, or null to leave the layers alone. */
    private Material wetSurface(GenerationContext context, BiomeProfile biome) {
        Material liquid = context.liquid();
        if (liquid == Material.LAVA) {
            // Lava seas scorch whatever they touch: a basalt rim reads better than
            // grass sitting happily under molten rock.
            return profile.palette().get("lava-shore", Material.BASALT);
        }
        Material sand = profile.palette().get("sand", Material.SAND);
        Material gravel = profile.palette().get("gravel", Material.GRAVEL);
        Material ice = profile.palette().get("ice", Material.PACKED_ICE);
        if (biome.temperature().max() <= 0.25) {
            return ice;
        }
        return biome.humidity().min() >= 0.6 ? gravel : sand;
    }
}
