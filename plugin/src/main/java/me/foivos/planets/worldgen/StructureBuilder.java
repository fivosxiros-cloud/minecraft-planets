package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * The builders behind {@link StructureTypes}: each one turns a
 * {@link StructureSettings.StructureProfile} into blocks.
 * <p>
 * Every builder reads its shape from the profile's {@code params} and falls back
 * to something sensible, so the same builder serves a small ruin on a moon and a
 * sprawling temple on an ancient world. Randomness is taken from the placement
 * hash, never from {@code Random}, which is what keeps a structure identical
 * across restarts.
 */
final class StructureBuilder {

    private StructureBuilder() {
    }

    /**
     * Resolves one material: the structure's own palette first, then the planet's,
     * then a sensible default. Builders therefore never deal with nulls and a
     * profile can theme a single structure without touching the planet's palette.
     */
    private static BlockData block(StructureSettings.StructureProfile structure, Palette planet,
                                   String key, Material fallback) {
        Material material = structure.palette().get(key, null);
        if (material == null) {
            material = planet.get(key, fallback);
        }
        return material.createBlockData();
    }

    static void build(StructureSettings.StructureProfile structure, GenerationContext context,
                      Build build, int x, int y, int z) {
        long hash = GradientNoise.hash(context.seed(), x, z);
        Palette planet = context.profile().palette();
        double scale = structure.scale();
        switch (structure.type()) {
            case "ruin" -> ruin(structure, planet, build, x, y, z, hash, scale);
            case "tower" -> tower(structure, planet, build, x, y, z, hash, scale);
            case "temple" -> temple(structure, planet, build, x, y, z, hash, scale);
            case "monument" -> monument(structure, planet, build, x, y, z, hash, scale);
            case "fossil" -> fossil(structure, planet, build, x, y, z, hash, scale);
            case "bones" -> bones(structure, planet, build, x, y, z, hash, scale);
            case "giant-tree" -> giantTree(structure, planet, build, x, y, z, hash, scale);
            case "dead-tree" -> deadTree(structure, planet, build, x, y, z, hash, scale);
            case "crystal" -> crystal(structure, planet, build, x, y, z, hash, scale);
            case "volcanic-vent" -> volcanicVent(structure, planet, build, x, y, z, hash, scale);
            case "crashed-ship" -> crashedShip(structure, planet, build, x, y, z, hash, scale);
            case "nest" -> nest(structure, planet, build, x, y, z, hash, scale);
            case "campsite" -> campsite(structure, planet, build, x, y, z, hash, scale);
            case "well" -> well(structure, planet, build, x, y, z, hash, scale);
            case "stone-circle" -> stoneCircle(structure, planet, build, x, y, z, hash, scale);
            case "ice-spike" -> iceSpike(structure, planet, build, x, y, z, hash, scale);
            case "mushroom-cluster" -> mushroomCluster(structure, planet, build, x, y, z, hash, scale);
            case "platform" -> platform(structure, planet, build, x, y, z, hash, scale);
            default -> {
                // An unknown type cannot get here: the profile loader rejects it.
            }
        }
    }

    // ── The builders ─────────────────────────────────────────────────────

    private static void ruin(StructureSettings.StructureProfile structure, Palette planet, Build build,
                             int x, int y, int z, long hash, double scale) {
        BlockData stone = block(structure, planet, "primary", Material.STONE_BRICKS);
        BlockData cracked = block(structure, planet, "secondary", Material.CRACKED_STONE_BRICKS);
        BlockData accent = block(structure, planet, "accent", Material.MOSSY_STONE_BRICKS);
        int width = structure.integer("width", (int) Math.round(9 * scale));
        int height = structure.integer("height", (int) Math.round(5 * scale));
        int pillars = structure.integer("pillars", 4);
        int half = Math.max(2, width / 2);

        // Floor slab and a broken perimeter wall, with gaps so it reads as ruined.
        build.fill(x - half, y, z - half, x + half, y, z + half, stone);
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                boolean edge = Math.abs(dx) == half || Math.abs(dz) == half;
                if (!edge) {
                    continue;
                }
                double noise = GradientNoise.chance(hash, x + dx, z + dz);
                int wallHeight = (int) Math.round(height * (0.35 + 0.65 * noise));
                for (int dy = 1; dy <= wallHeight; dy++) {
                    build.setIfAir(x + dx, y + dy, z + dz, noise > 0.6 ? cracked : stone);
                }
            }
        }
        // Standing columns at the corners and along the walls.
        for (int i = 0; i < pillars; i++) {
            int dx = PlacementGrid.pick(hash, i, x, z, width) - half;
            int dz = PlacementGrid.pick(hash, i + 31, x, z, width) - half;
            int columnHeight = (int) Math.round((height + 2) * (0.7 + 0.3 * GradientNoise.chance(hash, i, x + dz)));
            for (int dy = 1; dy <= columnHeight; dy++) {
                build.setIfAir(x + dx, y + dy, z + dz, dy == columnHeight ? accent : stone);
            }
        }
    }

    private static void tower(StructureSettings.StructureProfile structure, Palette planet, Build build,
                              int x, int y, int z, long hash, double scale) {
        BlockData body = block(structure, planet, "primary", Material.STONE_BRICKS);
        BlockData accent = block(structure, planet, "accent", Material.CHISELED_STONE_BRICKS);
        BlockData light = block(structure, planet, "light", Material.GLOWSTONE);
        int height = structure.integer("height", (int) Math.round(22 * scale));
        int baseWidth = Math.max(2, structure.integer("base-width", (int) Math.round(4 * scale)));
        for (int dy = 0; dy < height; dy++) {
            int half = Math.max(0, baseWidth - (dy * baseWidth / Math.max(1, height)));
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    boolean edge = Math.abs(dx) == half || Math.abs(dz) == half;
                    if (dy == 0 || edge) {
                        build.set(x + dx, y + dy, z + dz, (dy % 7 == 0 && edge) ? accent : body);
                    } else {
                        build.setIfAir(x + dx, y + dy, z + dz, Material.AIR);
                    }
                }
            }
        }
        // A lit room at the top: a small reward for climbing it.
        build.fill(x - 2, y + height, z - 2, x + 2, y + height, z + 2, body);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                    build.set(x + dx, y + height + 1, z + dz, body);
                }
            }
        }
        build.set(x, y + height + 2, z, light);
    }

    private static void temple(StructureSettings.StructureProfile structure, Palette planet, Build build,
                               int x, int y, int z, long hash, double scale) {
        BlockData body = block(structure, planet, "primary", Material.CHISELED_SANDSTONE);
        BlockData accent = block(structure, planet, "accent", Material.GOLD_BLOCK);
        BlockData pillar = block(structure, planet, "secondary", Material.SANDSTONE);
        int size = structure.integer("size", (int) Math.round(11 * scale));
        int steps = Math.max(1, structure.integer("steps", 3));
        for (int step = 0; step < steps; step++) {
            int half = Math.max(2, size - step * 2);
            build.fill(x - half, y + step, z - half, x + half, y + step, z + half, body);
        }
        int top = y + steps;
        // Four corner columns around the altar.
        int half = Math.max(2, size - (steps - 1) * 2);
        for (int dx : new int[]{-half, half}) {
            for (int dz : new int[]{-half, half}) {
                for (int dy = 1; dy <= 4; dy++) {
                    build.setIfAir(x + dx, top + dy, z + dz, pillar);
                }
                build.set(x + dx, top + 5, z + dz, accent);
            }
        }
        build.set(x, top + 1, z, accent);
        build.set(x, top + 2, z, Material.LANTERN);
    }

    private static void monument(StructureSettings.StructureProfile structure, Palette planet, Build build,
                                 int x, int y, int z, long hash, double scale) {
        String shape = structure.text("shape", "obelisk");
        int height = structure.integer("height", (int) Math.round(16 * scale));
        BlockData body = block(structure, planet, "primary", Material.DEEPSLATE_BRICKS);
        BlockData accent = block(structure, planet, "accent", Material.AMETHYST_BLOCK);
        int base = Math.max(1, height / 8);
        for (int dy = 0; dy < height; dy++) {
            int half = switch (shape) {
                case "crystal" -> Math.max(0, (dy % 4) < 2 ? base : base - 1);
                case "spire" -> Math.max(0, base - (dy * base / height));
                default -> Math.max(0, base - (dy >= height - 3 ? 1 : 0));
            };
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    build.set(x + dx, y + dy, z + dz, dy % 5 == 0 ? accent : body);
                }
            }
        }
        build.set(x, y + height, z, accent);
    }

    private static void fossil(StructureSettings.StructureProfile structure, Palette planet, Build build,
                               int x, int y, int z, long hash, double scale) {
        BlockData bone = block(structure, planet, "primary", Material.BONE_BLOCK);
        BlockData rib = block(structure, planet, "secondary", Material.BONE_BLOCK);
        int length = structure.integer("length", (int) Math.round(18 * scale));
        int height = structure.integer("height", (int) Math.round(5 * scale));
        boolean buried = structure.flag("buried", false);
        int spineY = buried ? y - 2 : y + 1;
        int direction = PlacementGrid.pick(hash, 1, x, z, 2) == 0 ? 1 : -1;

        // A spine with ribs, plus a skull at the head: the classic dinosaur read.
        for (int i = 0; i < length; i++) {
            int px = x + i * direction;
            build.set(px, spineY, z, bone);
            int ribHeight = (int) Math.round(height * (0.4 + 0.6 * GradientNoise.chance(hash, i, z)));
            for (int dz = 1; dz <= ribHeight; dz++) {
                build.set(px, spineY, z + dz, rib);
                build.set(px, spineY, z - dz, rib);
            }
            if (i % 3 == 0) {
                build.set(px, spineY - 1, z, bone);
            }
        }
        int headX = x - direction;
        build.fill(headX - 1, spineY - 1, z - 2, headX + 1, spineY + 1, z + 2, bone);
        build.set(headX, spineY + 2, z, bone);
    }

    private static void bones(StructureSettings.StructureProfile structure, Palette planet, Build build,
                              int x, int y, int z, long hash, double scale) {
        BlockData bone = block(structure, planet, "primary", Material.BONE_BLOCK);
        int radius = structure.integer("radius", (int) Math.round(6 * scale));
        int count = radius * 3;
        for (int i = 0; i < count; i++) {
            int dx = PlacementGrid.pick(hash, i, x, z, radius * 2 + 1) - radius;
            int dz = PlacementGrid.pick(hash, i + 53, x, z, radius * 2 + 1) - radius;
            int dy = PlacementGrid.pick(hash, i + 97, x, z, 3);
            build.setIfAir(x + dx, y + dy, z + dz, bone);
        }
    }

    private static void giantTree(StructureSettings.StructureProfile structure, Palette planet, Build build,
                                  int x, int y, int z, long hash, double scale) {
        BlockData trunk = block(structure, planet, "primary", Material.JUNGLE_LOG);
        BlockData leaves = block(structure, planet, "leaves", Material.JUNGLE_LEAVES);
        int height = structure.integer("height", (int) Math.round(26 * scale));
        int radius = Math.max(3, structure.integer("radius", (int) Math.round(9 * scale)));
        int branches = structure.integer("branches", 5);

        for (int dy = 0; dy < height; dy++) {
            int half = dy > height - 4 ? 1 : 0;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    build.set(x + dx, y + dy, z + dz, trunk);
                }
            }
        }
        // Side branches reaching out of the trunk, each with a leafy tip.
        for (int i = 0; i < branches; i++) {
            int angle = i * 360 / Math.max(1, branches);
            double radians = Math.toRadians(angle);
            int branchY = y + height - 4 - PlacementGrid.pick(hash, i, x, z, 5);
            int reach = (int) Math.round(radius * 0.7);
            for (int step = 1; step <= reach; step++) {
                int bx = x + (int) Math.round(Math.cos(radians) * step);
                int bz = z + (int) Math.round(Math.sin(radians) * step);
                build.setIfAir(bx, branchY + step / 3, bz, trunk);
            }
        }
        // Canopy: a squashed sphere of leaves.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius / 2; dy <= radius / 2; dy++) {
                    double distance = Math.sqrt(dx * dx + dz * dz + dy * dy * 2.2);
                    if (distance > radius) {
                        continue;
                    }
                    if (distance > radius - 1 && GradientNoise.chance(hash, x + dx, z + dz) < 0.35) {
                        continue; // ragged edge rather than a perfect ball
                    }
                    build.setIfAir(x + dx, y + height + dy, z + dz, leaves);
                }
            }
        }
    }

    private static void deadTree(StructureSettings.StructureProfile structure, Palette planet, Build build,
                                 int x, int y, int z, long hash, double scale) {
        BlockData trunk = block(structure, planet, "primary", Material.DARK_OAK_LOG);
        int height = structure.integer("height", (int) Math.round(8 * scale));
        for (int dy = 0; dy < height; dy++) {
            build.setIfAir(x, y + dy, z, trunk);
        }
        // A couple of broken limbs, so it doesn't read as a fence post.
        build.setIfAir(x + 1, y + height - 2, z, trunk);
        build.setIfAir(x + 2, y + height - 1, z, trunk);
        build.setIfAir(x, y + height - 3, z - 1, trunk);
    }

    private static void crystal(StructureSettings.StructureProfile structure, Palette planet, Build build,
                                int x, int y, int z, long hash, double scale) {
        BlockData body = block(structure, planet, "accent", Material.AMETHYST_BLOCK);
        BlockData tip = block(structure, planet, "rare", Material.BUDDING_AMETHYST);
        int height = structure.integer("height", (int) Math.round(9 * scale));
        int count = structure.integer("count", 5);
        for (int i = 0; i < count; i++) {
            int dx = PlacementGrid.pick(hash, i, x, z, 7) - 3;
            int dz = PlacementGrid.pick(hash, i + 41, x, z, 7) - 3;
            int spike = Math.max(2, height - PlacementGrid.pick(hash, i + 83, x, z, height / 2 + 1));
            for (int dy = 0; dy < spike; dy++) {
                int half = dy == 0 ? 1 : 0;
                for (int ox = -half; ox <= half; ox++) {
                    for (int oz = -half; oz <= half; oz++) {
                        build.set(x + dx + ox, y + dy, z + dz + oz,
                                dy == spike - 1 ? tip : body);
                    }
                }
            }
        }
    }

    private static void volcanicVent(StructureSettings.StructureProfile structure, Palette planet,
                                     Build build, int x, int y, int z, long hash, double scale) {
        BlockData rock = block(structure, planet, "primary", Material.BASALT);
        BlockData magma = block(structure, planet, "accent", Material.MAGMA_BLOCK);
        Material lava = planet.get("lava", Material.LAVA);
        int radius = Math.max(3, structure.integer("radius", (int) Math.round(8 * scale)));
        int height = Math.max(2, structure.integer("height", (int) Math.round(6 * scale)));
        for (int dy = 0; dy <= height; dy++) {
            int half = Math.max(1, radius - dy * radius / Math.max(1, height + 1));
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    double distance = Math.sqrt(dx * dx + dz * dz);
                    if (distance > half) {
                        continue;
                    }
                    if (distance < half - 1.5 && dy == height) {
                        build.set(x + dx, y + dy, z + dz, lava);
                    } else {
                        build.set(x + dx, y + dy, z + dz, distance > half - 1.5 ? rock : magma);
                    }
                }
            }
        }
    }

    private static void crashedShip(StructureSettings.StructureProfile structure, Palette planet, Build build,
                                    int x, int y, int z, long hash, double scale) {
        BlockData hull = block(structure, planet, "primary", Material.IRON_BLOCK);
        BlockData hullDark = block(structure, planet, "secondary", Material.DEEPSLATE_TILES);
        BlockData glass = block(structure, planet, "glass", Material.GRAY_STAINED_GLASS);
        int length = structure.integer("length", (int) Math.round(16 * scale));
        int direction = PlacementGrid.pick(hash, 3, x, z, 2) == 0 ? 1 : -1;
        for (int i = 0; i < length; i++) {
            int px = x + i * direction;
            // The hull leans into the ground: the tail is buried, the nose is up.
            int py = y + Math.min(4, i / 3);
            int half = Math.max(1, 3 - Math.abs(i - length / 2) / 4);
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    boolean shell = Math.abs(dx) == half || Math.abs(dz) == half;
                    if (shell) {
                        build.set(px, py, z + dz, dx == 0 && dz == 0 ? glass : hull);
                    } else if (i % 5 == 0) {
                        build.set(px, py, z + dz, hullDark);
                    }
                }
            }
        }
        // A debris trail, so it looks like it came down rather than landed.
        for (int i = 0; i < 6; i++) {
            int dx = PlacementGrid.pick(hash, i + 11, x, z, 12) - 6;
            int dz = PlacementGrid.pick(hash, i + 71, x, z, 12) - 6;
            build.setIfAir(x - direction * (length / 2 + 2) + dx, y, z + dz, hullDark);
        }
    }

    private static void nest(StructureSettings.StructureProfile structure, Palette planet, Build build,
                             int x, int y, int z, long hash, double scale) {
        BlockData nest = block(structure, planet, "primary", Material.BROWN_TERRACOTTA);
        BlockData bone = block(structure, planet, "secondary", Material.BONE_BLOCK);
        BlockData egg = block(structure, planet, "accent", Material.TURTLE_EGG);
        int radius = Math.max(3, structure.integer("radius", (int) Math.round(6 * scale)));
        int eggs = structure.integer("eggs", 5);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > radius) {
                    continue;
                }
                if (distance > radius - 1.4) {
                    build.set(x + dx, y, z + dz, nest);
                    build.set(x + dx, y + 1, z + dz, nest);
                } else {
                    build.set(x + dx, y - 1, z + dz, nest);
                }
            }
        }
        for (int i = 0; i < eggs; i++) {
            int dx = PlacementGrid.pick(hash, i, x, z, radius) - radius / 2;
            int dz = PlacementGrid.pick(hash, i + 29, x, z, radius) - radius / 2;
            build.set(x + dx, y, z + dz, egg);
        }
        // A few bones scattered around the rim.
        for (int i = 0; i < 4; i++) {
            int dx = PlacementGrid.pick(hash, i + 61, x, z, radius * 2 + 3) - radius - 1;
            int dz = PlacementGrid.pick(hash, i + 91, x, z, radius * 2 + 3) - radius - 1;
            build.setIfAir(x + dx, y, z + dz, bone);
        }
    }

    private static void campsite(StructureSettings.StructureProfile structure, Palette planet, Build build,
                                 int x, int y, int z, long hash, double scale) {
        BlockData tent = block(structure, planet, "primary", Material.WHITE_WOOL);
        BlockData post = block(structure, planet, "secondary", Material.OAK_FENCE);
        int tents = Math.max(1, structure.integer("tents", 2));
        for (int i = 0; i < tents; i++) {
            int dx = (i - tents / 2) * 6;
            build.fill(x + dx - 2, y + 1, z - 2, x + dx + 2, y + 2, z + 2, tent);
            build.fill(x + dx - 1, y + 3, z - 1, x + dx + 1, y + 3, z + 1, tent);
            build.set(x + dx, y + 4, z, tent);
            build.set(x + dx + 3, y + 1, z + 3, post);
            build.set(x + dx - 3, y + 1, z + 3, post);
        }
        if (structure.flag("campfire", true)) {
            build.set(x, y + 1, z, Material.CAMPFIRE);
        }
    }

    private static void well(StructureSettings.StructureProfile structure, Palette planet, Build build,
                             int x, int y, int z, long hash, double scale) {
        BlockData stone = block(structure, planet, "primary", Material.COBBLESTONE);
        BlockData roof = block(structure, planet, "secondary", Material.DARK_OAK_SLAB);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (edge) {
                    for (int dy = 1; dy <= 3; dy++) {
                        build.set(x + dx, y + dy, z + dz, stone);
                    }
                } else {
                    build.set(x + dx, y, z + dz, Material.WATER);
                }
            }
        }
        build.fill(x - 3, y + 4, z - 3, x + 3, y + 4, z + 3, roof);
    }

    private static void stoneCircle(StructureSettings.StructureProfile structure, Palette planet, Build build,
                                    int x, int y, int z, long hash, double scale) {
        BlockData stone = block(structure, planet, "primary", Material.STONE);
        int radius = Math.max(4, structure.integer("radius", (int) Math.round(9 * scale)));
        int stones = Math.max(4, structure.integer("stones", 8));
        for (int i = 0; i < stones; i++) {
            double radians = Math.toRadians(i * 360.0 / stones);
            int px = x + (int) Math.round(Math.cos(radians) * radius);
            int pz = z + (int) Math.round(Math.sin(radians) * radius);
            int height = 3 + PlacementGrid.pick(hash, i, x, z, 3);
            for (int dy = 0; dy < height; dy++) {
                build.set(px, y + dy, pz, stone);
            }
        }
    }

    private static void iceSpike(StructureSettings.StructureProfile structure, Palette planet, Build build,
                                 int x, int y, int z, long hash, double scale) {
        BlockData ice = block(structure, planet, "primary", Material.PACKED_ICE);
        BlockData tip = block(structure, planet, "accent", Material.BLUE_ICE);
        int height = structure.integer("height", (int) Math.round(12 * scale));
        int count = Math.max(1, structure.integer("count", 3));
        for (int i = 0; i < count; i++) {
            int dx = PlacementGrid.pick(hash, i, x, z, 5) - 2;
            int dz = PlacementGrid.pick(hash, i + 17, x, z, 5) - 2;
            int spike = Math.max(3, height - PlacementGrid.pick(hash, i + 43, x, z, height / 2 + 1));
            for (int dy = 0; dy < spike; dy++) {
                int half = dy < 2 ? 1 : 0;
                for (int ox = -half; ox <= half; ox++) {
                    for (int oz = -half; oz <= half; oz++) {
                        build.set(x + dx + ox, y + dy, z + dz + oz, dy > spike - 3 ? tip : ice);
                    }
                }
            }
        }
    }

    private static void mushroomCluster(StructureSettings.StructureProfile structure, Palette planet,
                                        Build build, int x, int y, int z, long hash, double scale) {
        BlockData stem = block(structure, planet, "primary", Material.MUSHROOM_STEM);
        BlockData cap = block(structure, planet, "accent", Material.RED_MUSHROOM_BLOCK);
        int count = Math.max(1, structure.integer("count", 4));
        int height = Math.max(3, structure.integer("height", (int) Math.round(7 * scale)));
        for (int i = 0; i < count; i++) {
            int dx = PlacementGrid.pick(hash, i, x, z, 7) - 3;
            int dz = PlacementGrid.pick(hash, i + 23, x, z, 7) - 3;
            int stemHeight = Math.max(2, height - PlacementGrid.pick(hash, i + 59, x, z, 3));
            for (int dy = 0; dy < stemHeight; dy++) {
                build.set(x + dx, y + dy, z + dz, stem);
            }
            int radius = 2 + PlacementGrid.pick(hash, i + 79, x, z, 2);
            for (int ox = -radius; ox <= radius; ox++) {
                for (int oz = -radius; oz <= radius; oz++) {
                    if (Math.abs(ox) == radius && Math.abs(oz) == radius) {
                        continue;
                    }
                    build.set(x + dx + ox, y + stemHeight, z + dz + oz, cap);
                }
            }
        }
    }

    private static void platform(StructureSettings.StructureProfile structure, Palette planet, Build build,
                                 int x, int y, int z, long hash, double scale) {
        BlockData body = block(structure, planet, "primary", Material.DEEPSLATE_TILES);
        BlockData edge = block(structure, planet, "accent", Material.CHISELED_DEEPSLATE);
        int radius = Math.max(3, structure.integer("radius", (int) Math.round(8 * scale)));
        int thickness = Math.max(1, structure.integer("thickness", 2));
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > radius) {
                    continue;
                }
                for (int dy = 0; dy < thickness; dy++) {
                    build.set(x + dx, y - dy, z + dz,
                            distance > radius - 1.5 ? edge : body);
                }
            }
        }
    }
}
