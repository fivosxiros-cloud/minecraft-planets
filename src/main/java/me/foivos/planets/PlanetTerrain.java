package me.foivos.planets;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A planet's terrain: the layer stack (ordered <b>bottom → top</b>) plus the
 * biome, as used by the flat world generator.
 * <p>
 * Two things matter here:
 * <ul>
 *   <li><b>Generating</b> — {@link #toJson(Spec)} builds the exact flat
 *       generator JSON the Bukkit/Paper API expects
 *       ({@code {"layers":[{"block":"stone","height":1}],"biome":"plains"}}).
 *       Block and biome names must be plain lowercase ids without the
 *       {@code minecraft:} namespace: an invalid string makes the flat
 *       generator produce a totally empty (void) world.</li>
 *   <li><b>Repairing</b> — {@link #ensureFloor(World, String, Spec)} checks the
 *       area inside the world border and places the layer stack wherever the
 *       terrain is missing, so a planet can never be a bottomless void even if
 *       a server version ignores the generator settings. The same pass runs when
 *       a planet grows, so newly unlocked ground gets its layers too.</li>
 * </ul>
 * The spec of every planet is stored in config.yml under
 * {@code planet-terrain.<world>}, which also lets admins hand-edit the terrain
 * of a planet.
 */
final class PlanetTerrain {

    private PlanetTerrain() {
    }

    /**
     * Hard cap on how far the repair pass reaches from the border center. A
     * freshly created world still carries the vanilla default border
     * (29999984 blocks), so without this an early call would try to walk
     * millions of columns.
     */
    private static final int MAX_FILL_RADIUS = 128;

    /** One terrain layer: a block and how many blocks tall it is. */
    public record Layer(String block, int height) {
    }

    /** A whole planet surface: a biome and the layers stacked on the world floor. */
    public record Spec(String biome, List<Layer> layers) {

        /** Total height of the layer stack, in blocks. */
        public int totalHeight() {
            int total = 0;
            for (Layer layer : layers) {
                total += Math.max(1, layer.height());
            }
            return total;
        }

        /** The topmost layer, or null for an empty stack. */
        public Layer top() {
            return layers.isEmpty() ? null : layers.get(layers.size() - 1);
        }

        /** Whether this terrain is intentionally empty (a void planet). */
        public boolean isVoid() {
            Layer top = top();
            return top == null || isAir(top.block());
        }

        /** Human-readable layer summary, e.g. {@code stone x3 → grass_block x2}. */
        public String summary() {
            if (isVoid()) {
                return "empty void";
            }
            StringBuilder text = new StringBuilder();
            for (Layer layer : layers) {
                if (text.length() > 0) {
                    text.append(" → ");
                }
                text.append(blockId(layer.block())).append(" x").append(Math.max(1, layer.height()));
            }
            return text.toString();
        }
    }

    // ── Name normalization ───────────────────────────────────────────────

    /** {@code "GRASS_BLOCK"} / {@code "minecraft:grass_block"} → {@code "grass_block"}. */
    static String blockId(String name) {
        String id = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("^minecraft:", "");
        return id.isEmpty() ? "stone" : id;
    }

    /** {@code "minecraft:forest"} / {@code "Forest"} → {@code "forest"}. */
    static String biomeId(String name) {
        String id = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("^minecraft:", "");
        return id.isEmpty() ? "plains" : id;
    }

    /** The Bukkit material for a layer block id, or null when it is unknown. */
    static Material material(String block) {
        String id = blockId(block);
        Material direct = Material.matchMaterial(id);
        if (direct != null) {
            return direct;
        }
        return Material.matchMaterial("minecraft:" + id);
    }

    private static boolean isAir(String block) {
        String id = blockId(block);
        return id.equals("air") || id.equals("cave_air") || id.equals("void_air");
    }

    // ── Building the flat-generator JSON ─────────────────────────────────

    /**
     * The flat-generator settings JSON for a terrain spec. Layer order is
     * bottom → top, exactly how the generator stacks them from the world floor.
     */
    static String toJson(Spec spec) {
        StringBuilder json = new StringBuilder("{\"layers\":[");
        boolean first = true;
        for (Layer layer : spec.layers()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"block\":\"").append(blockId(layer.block()))
                    .append("\",\"height\":").append(Math.max(1, layer.height())).append('}');
        }
        json.append("],\"biome\":\"").append(biomeId(spec.biome())).append("\"}");
        return json.toString();
    }

    /** A per-planet variation: every layer height is jittered by up to ±1 block. */
    static Spec jitter(Spec spec) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Layer> layers = new ArrayList<>();
        for (Layer layer : spec.layers()) {
            int jitter = random.nextInt(3) - 1; // -1, 0 or +1
            layers.add(new Layer(layer.block(), Math.max(1, layer.height() + jitter)));
        }
        return new Spec(spec.biome(), layers);
    }

    /** A planet that is deliberately nothing but air. */
    static Spec voidSpec() {
        return new Spec("plains", List.of(new Layer("air", 1)));
    }

    // ── Parsing (for hand-written config / raw JSON presets) ─────────────

    private static final Pattern LAYER_OBJECT = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern BLOCK_KEY = Pattern.compile("\"block\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HEIGHT_KEY = Pattern.compile("\"height\"\\s*:\\s*(\\d+)");
    private static final Pattern BIOME_KEY = Pattern.compile("\"biome\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * Parses a flat-generator JSON string into a spec. Tolerant by design: it
     * accepts either key order, extra keys and both namespaced and plain names,
     * so a hand-written {@code generation-presets} entry still works. Returns
     * null when the string holds no layers at all.
     */
    static Spec parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<Layer> layers = new ArrayList<>();
        Matcher objects = LAYER_OBJECT.matcher(raw);
        while (objects.find()) {
            String object = objects.group(1);
            Matcher block = BLOCK_KEY.matcher(object);
            Matcher height = HEIGHT_KEY.matcher(object);
            if (!block.find()) {
                continue;
            }
            int amount = 1;
            if (height.find()) {
                try {
                    amount = Math.max(1, Integer.parseInt(height.group(1)));
                } catch (NumberFormatException ignored) {
                }
            }
            layers.add(new Layer(block.group(1), amount));
        }
        if (layers.isEmpty()) {
            return null;
        }
        Matcher biome = BIOME_KEY.matcher(raw);
        return new Spec(biome.find() ? biome.group(1) : "plains", layers);
    }

    /** Reads {@code block:height} entries (bottom → top); also accepts bare block names. */
    static List<Layer> parseLayerList(List<String> entries) {
        List<Layer> layers = new ArrayList<>();
        if (entries == null) {
            return layers;
        }
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            int height = 1;
            int colon = entry.lastIndexOf(':');
            if (colon > 0) {
                try {
                    height = Math.max(1, Integer.parseInt(entry.substring(colon + 1).trim()));
                    entry = entry.substring(0, colon);
                } catch (NumberFormatException ignored) {
                }
            }
            layers.add(new Layer(entry, height));
        }
        return layers;
    }

    // ── Config persistence ───────────────────────────────────────────────

    /** Writes a planet's terrain spec under {@code planet-terrain.<world>}. */
    static void save(FileConfiguration config, String worldName, Spec spec) {
        String path = "planet-terrain." + worldName;
        config.set(path + ".biome", biomeId(spec.biome()));
        List<String> layers = new ArrayList<>();
        for (Layer layer : spec.layers()) {
            layers.add(blockId(layer.block()) + ":" + Math.max(1, layer.height()));
        }
        config.set(path + ".layers", layers);
        config.set(path + ".settings", toJson(spec));
    }

    /** Reads a planet's terrain spec, or null when the planet has none recorded. */
    static Spec load(FileConfiguration config, String worldName) {
        ConfigurationSection section = config.getConfigurationSection("planet-terrain." + worldName);
        if (section == null) {
            return null;
        }
        List<Layer> layers = parseLayerList(section.getStringList("layers"));
        if (layers.isEmpty()) {
            Spec parsed = parseJson(section.getString("settings"));
            if (parsed != null) {
                return parsed;
            }
            return null;
        }
        return new Spec(section.getString("biome", "plains"), layers);
    }

    // ── Terrain repair ───────────────────────────────────────────────────

    /**
     * Makes sure the ground inside the world border actually exists: empty
     * columns are laid with the spec's layer stack, while columns that already
     * hold something are left exactly as they are — player digs and builds must
     * never be bulldozed by a repair pass.
     *
     * @return how many columns had to be filled.
     */
    static int ensureFloor(World world, String worldName, Spec spec) {
        return ensureFloor(world, worldName, spec, false);
    }

    /**
     * The repair pass, optionally in <b>force</b> mode: every column is re-laid,
     * including ones that already hold different terrain (used when an admin
     * deliberately switches a planet to another generation).
     *
     * @return how many columns had to be filled.
     */
    static int ensureFloor(World world, String worldName, Spec spec, boolean force) {
        if (world == null || spec == null || spec.isVoid()) {
            return 0;
        }
        int minY = world.getMinHeight();
        int total = spec.totalHeight();
        int topY = minY + total - 1;
        Material surface = material(spec.top().block());
        if (surface == null) {
            return 0; // unknown block in a hand-written spec: never guess
        }

        Location center = world.getWorldBorder().getCenter();
        int centerX = center.getBlockX();
        int centerZ = center.getBlockZ();
        int half = Math.min(MAX_FILL_RADIUS,
                Math.max(2, (int) Math.round(world.getWorldBorder().getSize()) / 2));

        int repaired = 0;
        for (int x = centerX - half; x <= centerX + half; x++) {
            for (int z = centerZ - half; z <= centerZ + half; z++) {
                if (force) {
                    if (world.getBlockAt(x, topY, z).getType() == surface) {
                        continue; // already this generation
                    }
                } else if (!isColumnEmpty(world, x, z, minY, topY)) {
                    continue; // ground (or anything a player built) exists here
                }
                int y = minY;
                for (Layer layer : spec.layers()) {
                    Material block = material(layer.block());
                    for (int i = 0; i < Math.max(1, layer.height()); i++, y++) {
                        if (block == null) {
                            continue;
                        }
                        Block target = world.getBlockAt(x, y, z);
                        if (target.getType() != block) {
                            target.setType(block, false);
                        }
                    }
                }
                // Clear anything that would poke out above the new surface.
                for (int i = 1; i <= 3; i++) {
                    Block above = world.getBlockAt(x, topY + i, z);
                    if (above.getType().isAir()) {
                        break;
                    }
                    above.setType(Material.AIR, false);
                }
                repaired++;
            }
        }

        if (repaired > 0) {
            Location spawn = world.getSpawnLocation();
            Block spawnBlock = world.getBlockAt(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ());
            if (spawnBlock.getType().isAir()) {
                // A void world spawns players over nothing: move it onto the floor.
                world.setSpawnLocation(centerX, topY + 1, centerZ);
            }
            org.bukkit.Bukkit.getLogger().info("[planets] Re-laid " + repaired + " terrain column(s) on '"
                    + worldName + "' (" + spec.summary() + ").");
        }
        return repaired;
    }

    /** Whether a column inside the layer range holds nothing at all. */
    private static boolean isColumnEmpty(World world, int x, int z, int minY, int topY) {
        for (int y = topY; y >= minY; y--) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    /** Terrain summary for menus/logs: {@code stone x3 → grass_block x2 (plains)}. */
    static String describe(Spec spec) {
        if (spec == null) {
            return "default terrain";
        }
        return spec.summary() + " (" + biomeId(spec.biome()) + ")";
    }
}
