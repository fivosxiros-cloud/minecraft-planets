package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

/**
 * The small drawing surface structure and decoration builders use.
 * <p>
 * Builders work in <b>world</b> coordinates and this class does the arithmetic
 * back into the chunk being generated, discarding anything that falls outside it.
 * That is the trick that makes a 30-block ruin work on a 16-block chunk: the
 * builder is written once, as if the whole structure were visible, and the parts
 * that land in this chunk are placed while the rest simply wait for their own
 * chunk to ask for them.
 * <p>
 * The height the structure was planned against is captured here too, so a builder
 * that steps one block up per layer follows the ground it was placed on instead of
 * floating over it.
 */
public final class Build {

    private final ChunkGenerator.ChunkData data;
    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int maxY;

    public Build(ChunkGenerator.ChunkData data, int chunkX, int chunkZ) {
        this.data = data;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = data.getMinHeight();
        this.maxY = data.getMaxHeight();
    }

    /** Places a block at a world position, if it lands in this chunk. */
    public void set(int x, int y, int z, BlockData block) {
        if (block == null || y < minY || y >= maxY) {
            return;
        }
        int localX = x - (chunkX << 4);
        int localZ = z - (chunkZ << 4);
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
            return;
        }
        data.setBlock(localX, y, localZ, block);
    }

    public void set(int x, int y, int z, Material material) {
        if (material != null) {
            set(x, y, z, material.createBlockData());
        }
    }

    /** Places a block only where the current block is air, from a material. */
    public void setIfAir(int x, int y, int z, Material material) {
        if (material != null) {
            setIfAir(x, y, z, material.createBlockData());
        }
    }

    /** Places a block only where the current block is air, so builds never eat the ground. */
    public void setIfAir(int x, int y, int z, BlockData block) {
        if (block == null || y < minY || y >= maxY) {
            return;
        }
        int localX = x - (chunkX << 4);
        int localZ = z - (chunkZ << 4);
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
            return;
        }
        Material existing = data.getType(localX, y, localZ);
        if (existing != null && !existing.isAir()) {
            return;
        }
        data.setBlock(localX, y, localZ, block);
    }

    /** Places a block only where the current block is solid. */
    public void setIfSolid(int x, int y, int z, BlockData block) {
        if (block == null || y < minY || y >= maxY) {
            return;
        }
        int localX = x - (chunkX << 4);
        int localZ = z - (chunkZ << 4);
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
            return;
        }
        Material existing = data.getType(localX, y, localZ);
        if (existing == null || existing.isAir() || !existing.isSolid()) {
            return;
        }
        data.setBlock(localX, y, localZ, block);
    }

    /** Whether a world position falls inside this chunk. */
    public boolean contains(int x, int z) {
        return (x >> 4) == chunkX && (z >> 4) == chunkZ;
    }

    /** The material at a world position, or null when it is outside this chunk. */
    public Material typeAt(int x, int y, int z) {
        int localX = x - (chunkX << 4);
        int localZ = z - (chunkZ << 4);
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15 || y < minY || y >= maxY) {
            return null;
        }
        return data.getType(localX, y, localZ);
    }

    /** Fills a box in world coordinates. */
    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockData block) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.max(this.minY, Math.min(y1, y2));
        int maxY = Math.min(this.maxY - 1, Math.max(y1, y2));
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    set(x, y, z, block);
                }
            }
        }
    }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        if (material != null) {
            fill(x1, y1, z1, x2, y2, z2, material.createBlockData());
        }
    }

    /** The chunk this build writes into. */
    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }
}
