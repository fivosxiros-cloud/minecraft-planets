package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * Places structures and landmarks, deterministically and only where the terrain
 * allows them.
 * <p>
 * The whole generator is <b>stateless</b>: for each chunk it looks at the grid
 * cells that could reach into that chunk, re-rolls them from the world seed, and
 * rebuilds whichever ones win. Nothing is stored between chunks, which is exactly
 * why the result is identical on a restart, in a different chunk order, and
 * during pregeneration.
 * <p>
 * The order of checks is what keeps the world believable: the terrain is asked
 * for its height and slope first, the biome second, and only then does anything
 * get built. A structure can therefore never hang over a cliff, sink into a
 * mountain or appear in a biome that forbids it.
 */
public final class StructureGenerator {

    private final PlanetProfile profile;

    public StructureGenerator(PlanetProfile profile) {
        this.profile = profile;
    }

    /** One structure that will be built into this chunk. */
    public record Placement(StructureSettings.StructureProfile structure, int x, int y, int z) {
    }

    /**
     * Builds every structure whose footprint reaches this chunk.
     *
     * @param context the world's samplers and settings
     * @param data    the chunk being generated
     * @param chunkX  chunk x
     * @param chunkZ  chunk z
     * @param biomeAt the planet biome at a world position, as the generator saw it
     * @return the structures that were placed, for the debug command and the log
     */
    public List<Placement> generate(GenerationContext context, ChunkGenerator.ChunkData data,
                                    int chunkX, int chunkZ, BiomeLookup biomeAt) {
        StructureSettings settings = profile.structures();
        if (!settings.any()) {
            return List.of();
        }
        List<Placement> placed = new java.util.ArrayList<>();
        Build build = new Build(data, chunkX, chunkZ);
        int budget = settings.maxPerChunk() <= 0 ? 3 : settings.maxPerChunk();

        for (StructureSettings.StructureProfile structure : all(settings)) {
            if (placed.size() >= budget) {
                break;
            }
            Placement placement = find(context, structure, chunkX, chunkZ, biomeAt);
            if (placement == null) {
                continue;
            }
            StructureBuilder.build(structure, context, build, placement.x(), placement.y(), placement.z());
            placed.add(placement);
        }
        return placed;
    }

    private static List<StructureSettings.StructureProfile> all(StructureSettings settings) {
        if (settings.landmarks().isEmpty()) {
            return settings.types();
        }
        if (settings.types().isEmpty()) {
            return settings.landmarks();
        }
        List<StructureSettings.StructureProfile> combined = new java.util.ArrayList<>(settings.types());
        combined.addAll(settings.landmarks());
        return combined;
    }

    /**
     * Whether this structure places anything in this chunk, and where. The cell
     * grid is scanned far enough out that a structure whose footprint reaches into
     * this chunk is still found from the neighbouring cell.
     */
    private Placement find(GenerationContext context, StructureSettings.StructureProfile structure,
                           int chunkX, int chunkZ, BiomeLookup biomeAt) {
        StructureSettings settings = profile.structures();
        int cellSize = structure.cellSize();
        int reach = 48; // the largest footprint the shipped builders make
        int minCellX = PlacementGrid.cell((chunkX << 4) - reach, cellSize);
        int maxCellX = PlacementGrid.cell((chunkX << 4) + 15 + reach, cellSize);
        int minCellZ = PlacementGrid.cell((chunkZ << 4) - reach, cellSize);
        int maxCellZ = PlacementGrid.cell((chunkZ << 4) + 15 + reach, cellSize);
        long salt = structure.id().hashCode();

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                double chance = structure.rarity() * settings.density();
                if (PlacementGrid.roll(context.seed(), salt, cellX, cellZ) >= chance) {
                    continue;
                }
                int x = PlacementGrid.offset(context.seed(), salt, cellX, cellSize);
                int z = PlacementGrid.offset(context.seed(), salt ^ 0x5DEECE66DL, cellZ, cellSize);
                // A structure is built by whichever chunk owns its anchor, so it is
                // never built twice from two overlapping neighbour searches.
                if ((x >> 4) != chunkX || (z >> 4) != chunkZ) {
                    continue;
                }
                int surfaceY = TerrainProbe.surfaceY(context, x, z);
                if (surfaceY <= context.minHeight() + 2) {
                    continue;
                }
                String biomeId = biomeAt.biomeAt(x, surfaceY, z);
                if (!structure.allows(biomeId, surfaceY)) {
                    continue;
                }
                if (!slopeOk(context, x, z, surfaceY, structure.maxSlope())) {
                    continue;
                }
                if (!farEnough(context, structure, x, z)) {
                    continue;
                }
                return new Placement(structure, x, surfaceY, z);
            }
        }
        return null;
    }

    /**
     * Whether the ground under a footprint is flat enough. A ruin dropped on a
     * mountainside is the single most obvious sign of random placement, so this is
     * checked before anything is built.
     */
    private boolean slopeOk(GenerationContext context, int x, int z, int surfaceY, double maxSlope) {
        int radius = 6;
        int lowest = surfaceY;
        int highest = surfaceY;
        for (int dx = -radius; dx <= radius; dx += radius) {
            for (int dz = -radius; dz <= radius; dz += radius) {
                int height = TerrainProbe.surfaceY(context, x + dx, z + dz);
                lowest = Math.min(lowest, height);
                highest = Math.max(highest, height);
            }
        }
        return (highest - lowest) <= Math.max(1.0, maxSlope);
    }

    /** Whether a placed structure is far enough from another structure of its type. */
    private boolean farEnough(GenerationContext context, StructureSettings.StructureProfile structure,
                              int x, int z) {
        int distance = Math.max(structure.minDistance(), profile.structures().minDistance());
        if (distance <= 0) {
            return true;
        }
        // Only the immediately neighbouring cells are checked: two structures closer
        // than a cell cannot both have been placed, so this is a cheap sanity net
        // rather than a full search.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int cellX = PlacementGrid.cell(x, structure.cellSize()) + dx;
                int cellZ = PlacementGrid.cell(z, structure.cellSize()) + dz;
                double chance = structure.rarity() * profile.structures().density();
                if (PlacementGrid.roll(context.seed(), structure.id().hashCode(), cellX, cellZ) >= chance) {
                    continue;
                }
                int otherX = PlacementGrid.offset(context.seed(), structure.id().hashCode(), cellX,
                        structure.cellSize());
                int otherZ = PlacementGrid.offset(context.seed(),
                        structure.id().hashCode() ^ 0x5DEECE66DL, cellZ, structure.cellSize());
                if (!PlacementGrid.farEnough(x, z, otherX, otherZ, distance)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Asks the engine where a world position's planet biome is. */
    @FunctionalInterface
    public interface BiomeLookup {
        String biomeAt(int x, int y, int z);
    }

}
