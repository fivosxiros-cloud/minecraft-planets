package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

import java.util.List;

/**
 * Grows the vegetation and scatters the scenery.
 * <p>
 * Every column of a chunk gets its own deterministic roll, so a decoration is
 * placed the same way on every regeneration. The roll is filtered three times
 * before anything is placed — by the planet biome the column is in, by the block
 * the column's surface is made of, and by how flat the ground is — which is what
 * stops a tree growing out of a cliff face or a desert plant appearing in a swamp.
 */
public final class DecorationGenerator {

    /** Per-column rolls are cheap, but a column may only ever carry one decoration. */
    private static final int MAX_PER_COLUMN = 1;

    private final PlanetProfile profile;

    public DecorationGenerator(PlanetProfile profile) {
        this.profile = profile;
    }

    /**
     * Places decorations for a whole chunk.
     *
     * @param biomeAt the planet biome at a world position
     */
    public void generate(GenerationContext context, ChunkGenerator.ChunkData data,
                         int chunkX, int chunkZ, StructureGenerator.BiomeLookup biomeAt) {
        DecorationSettings settings = profile.decorations();
        if (!settings.any()) {
            return;
        }
        Build build = new Build(data, chunkX, chunkZ);
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (DecorationSettings.DecorationProfile decoration : settings.types()) {
            double perChunk = decoration.density() * settings.density();
            if (perChunk <= 0) {
                continue;
            }
            // One roll per column, compared against the decoration's expected count
            // per chunk (256 columns). A density of 12 therefore places about 12.
            double chance = Math.min(1.0, perChunk / 256.0);
            int placed = 0;
            int limit = (int) Math.max(1, Math.ceil(perChunk) * MAX_PER_COLUMN);
            for (int localX = 0; localX < 16 && placed < limit; localX++) {
                for (int localZ = 0; localZ < 16 && placed < limit; localZ++) {
                    int x = baseX + localX;
                    int z = baseZ + localZ;
                    if (GradientNoise.chance(context.seed() ^ decoration.id().hashCode(), x, z) >= chance) {
                        continue;
                    }
                    int surfaceY = TerrainProbe.surfaceY(context, x, z);
                    if (surfaceY <= context.minHeight() + 1) {
                        continue;
                    }
                    String biomeId = biomeAt.biomeAt(x, surfaceY, z);
                    if (!decoration.allows(biomeId, surfaceY)) {
                        continue;
                    }
                    Material ground = data.getType(localX, surfaceY, localZ);
                    if (!groundOk(decoration, ground)) {
                        continue;
                    }
                    if (!flatEnough(context, x, z, surfaceY, decoration.minSlope())) {
                        continue;
                    }
                    BiomeProfile biome = profile.biome(biomeId);
                    double growth = biome == null ? 1.0 : biome.vegetationDensity();
                    if (growth <= 0 || GradientNoise.chance(context.seed() + 991, x, z) > growth) {
                        continue;
                    }
                    if (place(decoration, context, build, x, surfaceY, z,
                            GradientNoise.hash(context.seed(), x, z))) {
                        placed++;
                    }
                }
            }
        }
    }

    /** Whether a decoration may grow on this block. */
    private boolean groundOk(DecorationSettings.DecorationProfile decoration, Material ground) {
        if (ground == null) {
            return false;
        }
        for (String avoid : decoration.avoidBlock()) {
            Material material = profile.palette().get(avoid, null);
            if (material != null && material == ground) {
                return false;
            }
        }
        if (decoration.onBlock().isEmpty()) {
            return ground.isSolid();
        }
        for (String allowed : decoration.onBlock()) {
            Material material = profile.palette().get(allowed, null);
            if (material != null && material == ground) {
                return true;
            }
        }
        return false;
    }

    /** Whether the ground around a decoration is flat enough for it. */
    private boolean flatEnough(GenerationContext context, int x, int z, int surfaceY, double minSlope) {
        if (minSlope <= 0) {
            return true;
        }
        int lowest = surfaceY;
        int highest = surfaceY;
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dz = -2; dz <= 2; dz += 2) {
                int height = TerrainProbe.surfaceY(context, x + dx, z + dz);
                lowest = Math.min(lowest, height);
                highest = Math.max(highest, height);
            }
        }
        return (highest - lowest) <= minSlope;
    }

    /**
     * Builds one decoration. Returns whether anything was actually placed, so the
     * per-chunk budget counts real features rather than attempts.
     */
    private boolean place(DecorationSettings.DecorationProfile decoration, GenerationContext context,
                          Build build, int x, int y, int z, long hash) {
        Palette planet = context.profile().palette();
        double scale = decoration.scale();
        switch (decoration.type()) {
            case "grass" -> {
                BlockData block = block(decoration, planet, "block", Material.SHORT_GRASS);
                int height = Math.max(1, decoration.integer("height", 1));
                build.set(x, y + 1, z, block);
                if (height > 1 && decoration.variation() && GradientNoise.chance(hash, x, z) > 0.7) {
                    build.set(x, y + 2, z, block);
                }
                return true;
            }
            case "flower", "mushroom" -> {
                List<String> options = list(decoration, "blocks");
                Material material = options.isEmpty()
                        ? (decoration.type().equals("flower") ? Material.POPPY : Material.RED_MUSHROOM)
                        : planet.get(options.get((int) Math.floorMod(hash, options.size())),
                        Material.POPPY);
                build.set(x, y + 1, z, material);
                return true;
            }
            case "bush" -> {
                BlockData leaves = block(decoration, planet, "leaves", Material.OAK_LEAVES);
                BlockData log = block(decoration, planet, "trunk", Material.OAK_LOG);
                int radius = Math.max(1, decoration.integer("radius", (int) Math.round(2 * scale)));
                int height = Math.max(1, decoration.integer("height", (int) Math.round(3 * scale)));
                for (int dy = 0; dy < height; dy++) {
                    build.set(x, y + dy, z, log);
                }
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        for (int dy = 0; dy < 2; dy++) {
                            if (Math.abs(dx) == radius && Math.abs(dz) == radius) {
                                continue;
                            }
                            build.setIfAir(x + dx, y + height + dy - 1, z + dz, leaves);
                        }
                    }
                }
                return true;
            }
            case "tree" -> {
                return tree(decoration, planet, build, x, y, z, hash, scale, false);
            }
            case "giant-tree" -> {
                return tree(decoration, planet, build, x, y, z, hash, scale, true);
            }
            case "dead-tree" -> {
                BlockData trunk = block(decoration, planet, "block", Material.DARK_OAK_LOG);
                int height = Math.max(2, decoration.integer("height", (int) Math.round(7 * scale)));
                for (int dy = 0; dy < height; dy++) {
                    build.set(x, y + dy, z, trunk);
                }
                build.setIfAir(x + 1, y + height - 1, z, trunk);
                return true;
            }
            case "crystal" -> {
                BlockData body = block(decoration, planet, "block", Material.AMETHYST_BLOCK);
                BlockData tip = block(decoration, planet, "tip", Material.BUDDING_AMETHYST);
                int count = Math.max(1, decoration.integer("count", 3));
                int height = Math.max(2, decoration.integer("height", (int) Math.round(5 * scale)));
                for (int i = 0; i < count; i++) {
                    int dx = PlacementGrid.pick(hash, i, x, z, 3) - 1;
                    int dz = PlacementGrid.pick(hash, i + 13, x, z, 3) - 1;
                    int spike = Math.max(1, height - PlacementGrid.pick(hash, i + 29, x, z, height / 2 + 1));
                    for (int dy = 0; dy < spike; dy++) {
                        build.set(x + dx, y + 1 + dy, z + dz, dy == spike - 1 ? tip : body);
                    }
                }
                return true;
            }
            case "rock" -> {
                BlockData block = block(decoration, planet, "block", Material.MOSSY_COBBLESTONE);
                int radius = Math.max(1, decoration.integer("radius", (int) Math.round(2 * scale)));
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        for (int dy = 0; dy <= radius; dy++) {
                            double distance = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.6);
                            if (distance <= radius) {
                                build.set(x + dx, y + dy, z + dz, block);
                            }
                        }
                    }
                }
                return true;
            }
            case "bones" -> {
                BlockData bone = block(decoration, planet, "block", Material.BONE_BLOCK);
                int radius = Math.max(1, decoration.integer("radius", (int) Math.round(3 * scale)));
                int count = radius * 2;
                for (int i = 0; i < count; i++) {
                    int dx = PlacementGrid.pick(hash, i, x, z, radius * 2 + 1) - radius;
                    int dz = PlacementGrid.pick(hash, i + 37, x, z, radius * 2 + 1) - radius;
                    build.setIfAir(x + dx, y + 1, z + dz, bone);
                }
                return true;
            }
            case "fossil" -> {
                BlockData bone = block(decoration, planet, "block", Material.BONE_BLOCK);
                int length = Math.max(3, decoration.integer("length", (int) Math.round(7 * scale)));
                int height = Math.max(1, decoration.integer("height", (int) Math.round(3 * scale)));
                int direction = PlacementGrid.pick(hash, 5, x, z, 2) == 0 ? 1 : -1;
                for (int i = 0; i < length; i++) {
                    int px = x + i * direction;
                    build.set(px, y, z, bone);
                    if (i % 2 == 0) {
                        for (int dz = 1; dz <= height; dz++) {
                            build.set(px, y, z + dz, bone);
                            build.set(px, y, z - dz, bone);
                        }
                    }
                }
                return true;
            }
            case "cactus" -> {
                BlockData block = block(decoration, planet, "block", Material.CACTUS);
                int height = Math.max(1, decoration.integer("height", (int) Math.round(3 * scale)));
                for (int dy = 0; dy < height; dy++) {
                    build.set(x, y + 1 + dy, z, block);
                }
                return true;
            }
            case "vine" -> {
                BlockData block = block(decoration, planet, "block", Material.VINE);
                int length = Math.max(1, decoration.integer("length", (int) Math.round(4 * scale)));
                for (int dy = 0; dy < length; dy++) {
                    build.setIfAir(x, y - dy, z, block);
                }
                return true;
            }
            case "lichen" -> {
                BlockData block = block(decoration, planet, "block", Material.MOSS_BLOCK);
                int radius = Math.max(1, decoration.integer("radius", (int) Math.round(3 * scale)));
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dz * dz > radius * radius) {
                            continue;
                        }
                        if (GradientNoise.chance(hash, x + dx, z + dz) < 0.5) {
                            continue;
                        }
                        build.set(x + dx, y, z + dz, block);
                    }
                }
                return true;
            }
            case "spore-pod" -> {
                BlockData pod = block(decoration, planet, "block", Material.SHROOMLIGHT);
                int height = Math.max(1, decoration.integer("height", 2));
                for (int dy = 0; dy < height; dy++) {
                    build.set(x, y + 1 + dy, z, pod);
                }
                build.setIfAir(x, y + height + 1, z,
                        block(decoration, planet, "light", Material.GLOWSTONE));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Shared tree builder: a trunk with a canopy whose shape is configurable. */
    private boolean tree(DecorationSettings.DecorationProfile decoration, Palette planet, Build build,
                         int x, int y, int z, long hash, double scale, boolean giant) {
        BlockData trunk = block(decoration, planet, "trunk",
                giant ? Material.JUNGLE_LOG : Material.OAK_LOG);
        BlockData leaves = block(decoration, planet, "leaves",
                giant ? Material.JUNGLE_LEAVES : Material.OAK_LEAVES);
        int baseHeight = giant ? 18 : 6;
        int height = Math.max(2, decoration.integer("height",
                (int) Math.round(baseHeight * scale)));
        int radius = Math.max(2, decoration.integer("canopy-radius",
                (int) Math.round((giant ? 7 : 3) * scale)));
        String canopy = decoration.text("canopy", "blob");

        for (int dy = 0; dy < height; dy++) {
            build.set(x, y + dy, z, trunk);
        }
        int canopyBase = y + height - (giant ? 3 : 2);
        switch (canopy) {
            case "conical" -> {
                for (int layer = 0; layer <= radius; layer++) {
                    int half = Math.max(0, radius - layer);
                    for (int dx = -half; dx <= half; dx++) {
                        for (int dz = -half; dz <= half; dz++) {
                            build.setIfAir(x + dx, canopyBase + layer, z + dz, leaves);
                        }
                    }
                }
            }
            case "flat" -> {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (dx * dx + dz * dz > radius * radius) {
                            continue;
                        }
                        build.setIfAir(x + dx, canopyBase + 1, z + dz, leaves);
                        build.setIfAir(x + dx, canopyBase, z + dz, leaves);
                    }
                }
            }
            default -> {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        for (int dy = -radius / 2; dy <= radius / 2; dy++) {
                            double distance = Math.sqrt(dx * dx + dz * dz + dy * dy * 2.0);
                            if (distance > radius) {
                                continue;
                            }
                            if (distance > radius - 1 && GradientNoise.chance(hash, x + dx, z + dz) < 0.4) {
                                continue;
                            }
                            build.setIfAir(x + dx, canopyBase + dy, z + dz, leaves);
                        }
                    }
                }
            }
        }
        return true;
    }

    private static BlockData block(DecorationSettings.DecorationProfile decoration, Palette planet,
                                   String key, Material fallback) {
        Material material = decoration.palette().get(key, null);
        if (material == null) {
            material = planet.get(key, fallback);
        }
        return material.createBlockData();
    }

    private static List<String> list(DecorationSettings.DecorationProfile decoration, String key) {
        Object value = decoration.params().get(key);
        if (value instanceof List<?> entries) {
            List<String> values = new java.util.ArrayList<>();
            for (Object entry : entries) {
                values.add(String.valueOf(entry));
            }
            return values;
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.split(","));
        }
        return List.of();
    }
}
