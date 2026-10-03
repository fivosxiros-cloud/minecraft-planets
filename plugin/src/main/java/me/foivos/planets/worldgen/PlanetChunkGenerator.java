package me.foivos.planets.worldgen;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Bukkit entry point of the generation engine.
 * <p>
 * Everything expensive is built once per world and then reused; this class only
 * routes a chunk through the pipeline:
 *
 * <pre>
 *  1. terrain module   solid rock up to a height (or a maze, or floating blobs)
 *  2. biome engine     which planet biome each column is in
 *  3. surface pass     what the top blocks are
 *  4. cave pass        what gets carved out
 *  5. liquid pass      what the oceans are filled with
 *  6. structure pass   ruins, fossils, landmarks
 *  7. decoration pass  grass, trees, crystals
 * </pre>
 *
 * It is <b>parallel capable</b>: the {@link GenerationContext} is immutable, every
 * sampler is stateless and nothing here reads or writes world state, so Paper may
 * run several chunks through it at once.
 * <p>
 * Vanilla's own stages are switched off explicitly rather than left to default, so
 * a planet can never be quietly overwritten with overworld terrain — the only
 * things vanilla still contributes are the biome registry and the world's own
 * dimension type.
 */
public final class PlanetChunkGenerator extends ChunkGenerator {

    private final PlanetProfile profile;
    private final BiomeEngine biomeEngine;
    private final SurfaceGenerator surface;
    private final CaveGenerator caves;
    private final StructureGenerator structures;
    private final DecorationGenerator decorations;
    private final TerrainModule module;
    /** world name -> built context. */
    private final Map<String, GenerationContext> contexts = new ConcurrentHashMap<>();

    public PlanetChunkGenerator(PlanetProfile profile) {
        this.profile = profile;
        this.biomeEngine = new BiomeEngine(profile);
        this.surface = new SurfaceGenerator(profile);
        this.caves = new CaveGenerator(profile);
        this.structures = new StructureGenerator(profile);
        this.decorations = new DecorationGenerator(profile);
        this.module = TerrainModules.forStyle(profile.style());
    }

    public PlanetProfile profile() {
        return profile;
    }

    /** The built context for a world, created on first use. */
    public GenerationContext context(WorldInfo world) {
        return contexts.computeIfAbsent(world.getName(),
                name -> new GenerationContext(profile, world.getSeed(), world));
    }

    /** Forgets a world's context (used when a world is unloaded). */
    public void forget(String worldName) {
        contexts.remove(worldName);
    }

    // ── The pipeline ─────────────────────────────────────────────────────

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random,
                              int chunkX, int chunkZ, @NotNull ChunkData data) {
        GenerationContext context = context(worldInfo);

        // 1. Terrain, column by column.
        TerrainModule.Column[] columns = new TerrainModule.Column[256];
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int x = (chunkX << 4) + localX;
                int z = (chunkZ << 4) + localZ;
                columns[(localX << 4) | localZ] =
                        module.generate(context, data, x, z, localX, localZ);
            }
        }
        module.finishChunk(context, data, chunkX, chunkZ);

        // 2-5. Biome, surface, caves and liquid for each column that has ground.
        Material liquid = module.fillsLiquid() ? context.liquid() : null;
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                TerrainModule.Column column = columns[(localX << 4) | localZ];
                if (column == null || column.isEmpty()) {
                    continue;
                }
                int x = (chunkX << 4) + localX;
                int z = (chunkZ << 4) + localZ;
                int surfaceY = column.surfaceY();

                BiomeEngine.Climate climate = biomeEngine.climate(context, x, z, surfaceY);
                BiomeProfile biome = biomeEngine.biomeFor(climate);
                int seaLevel = biome.seaLevel() != null ? biome.seaLevel() : context.seaLevel();
                Material columnLiquid = biome.liquid() == null ? liquid
                        : profile.palette().get(biome.liquid(), liquid);

                boolean underwater = columnLiquid != null && surfaceY < seaLevel;
                surface.build(context, data, localX, localZ, surfaceY, biome, climate, underwater);
                caves.carve(context, data, x, z, localX, localZ, surfaceY, biome.id());

                if (columnLiquid != null) {
                    BlockData block = columnLiquid.createBlockData();
                    for (int y = surfaceY + 1; y <= seaLevel; y++) {
                        data.setBlock(localX, y, localZ, block);
                    }
                }
            }
        }

        // 6-7. Structures and decoration, which work in world coordinates and
        // discard whatever falls outside this chunk.
        if (profile.structures().any()) {
            structures.generate(context, data, chunkX, chunkZ,
                    (x, y, z) -> planetBiomeAt(context, x, y, z));
        }
        if (profile.decorations().any()) {
            decorations.generate(context, data, chunkX, chunkZ,
                    (x, y, z) -> planetBiomeAt(context, x, y, z));
        }
    }

    @Override
    public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                int chunkX, int chunkZ, @NotNull ChunkData data) {
        // Folded into generateNoise, where the column heights are known exactly once.
    }

    @Override
    public void generateCaves(@NotNull WorldInfo worldInfo, @NotNull Random random,
                              int chunkX, int chunkZ, @NotNull ChunkData data) {
        // Likewise: carving needs the column heights the terrain pass produced.
    }

    @Override
    public void generateBedrock(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                int chunkX, int chunkZ, @NotNull ChunkData data) {
        if (!profile.layoutFlag("bedrock-floor", true)) {
            return;
        }
        BlockData bedrock = Material.BEDROCK.createBlockData();
        int floor = data.getMinHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                data.setBlock(x, floor, z, bedrock);
            }
        }
    }

    // ── What vanilla is allowed to do ────────────────────────────────────

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public boolean isParallelCapable() {
        return true;
    }

    @Override
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of();
    }

    @Override
    public int getBaseHeight(@NotNull WorldInfo worldInfo, @NotNull Random random,
                             int x, int z, @NotNull HeightMap heightMap) {
        GenerationContext context = context(worldInfo);
        int y = TerrainProbe.surfaceY(context, x, z);
        return Math.max(worldInfo.getMinHeight(), Math.min(worldInfo.getMaxHeight() - 1, y));
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return new PlanetBiomeProvider(profile, biomeEngine);
    }

    /** The Minecraft biome the client is told about at a position. */
    Biome minecraftBiomeAt(GenerationContext context, int x, int y, int z) {
        return biomeEngine.minecraftBiome(planetBiome(context, x, y, z));
    }

    /** The planet biome at a position, for structures and decorations. */
    String planetBiomeAt(GenerationContext context, int x, int y, int z) {
        return planetBiome(context, x, y, z).id();
    }

    private BiomeProfile planetBiome(GenerationContext context, int x, int y, int z) {
        int surface = TerrainProbe.surfaceY(context, x, z);
        return biomeEngine.biomeFor(biomeEngine.climate(context, x, z, surface));
    }
}
