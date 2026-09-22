package me.foivos.planets;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in planet archetypes for purchased planets. Each archetype defines:
 * <ul>
 *   <li><b>Terrain</b> — a biome plus a stack of layers ({@link PlanetTerrain},
 *       ordered bottom → top) written into the world at creation, so the ground
 *       itself is genuinely different per type.</li>
 *   <li><b>Identity</b> — sky/fog color, ambient particles, planet physics
 *       effects, locked weather and menu icon, applied to the plugin's config
 *       so the planet looks and feels different from every other one.</li>
 * </ul>
 * Layer heights are slightly randomized per planet at creation time
 * ({@link PlanetTerrain#jitter}), so even two planets of the same type never
 * look exactly the same.
 */
public final class PlanetArchetypes {

    private PlanetArchetypes() {
    }

    /** Fully describes one planet type. */
    public record Archetype(
            String id,
            String displayName,
            Material icon,
            String description,
            List<PlanetTerrain.Layer> layers,
            String biome,
            String skyColor,
            String fogColor,
            String particles,
            int particleDensity,
            Map<String, Integer> effects,
            String weather
    ) {
        /** The terrain spec this archetype generates (before per-planet jitter). */
        public PlanetTerrain.Spec terrain() {
            return new PlanetTerrain.Spec(biome, layers);
        }

        /** Short human-readable summary of the planet's unique traits (menu lore). */
        public String traits() {
            StringBuilder traits = new StringBuilder();
            if (skyColor != null) {
                traits.append("Tinted sky");
            }
            if (particles != null) {
                if (traits.length() > 0) {
                    traits.append(" · ");
                }
                traits.append(particles.substring(0, 1).toUpperCase(Locale.ROOT))
                        .append(particles.substring(1));
            }
            for (String effect : effects.keySet()) {
                if (traits.length() > 0) {
                    traits.append(" · ");
                }
                traits.append(switch (effect) {
                    case "jump" -> "Low gravity";
                    case "fall" -> "Floaty falls";
                    case "speed" -> "Fast feet";
                    case "swim" -> "Swift swimming";
                    case "breath" -> "No drowning";
                    case "fire_resistance" -> "Fireproof";
                    case "night_vision" -> "Night vision";
                    default -> effect.replace('_', ' ');
                });
            }
            if (weather != null) {
                if (traits.length() > 0) {
                    traits.append(" · ");
                }
                traits.append("Always ").append(weather);
            }
            return traits.length() > 0 ? traits.toString() : "Classic overworld";
        }
    }

    // ── The built-in archetypes ─────────────────────────────────────────

    private static final Map<String, Archetype> BY_ID = new LinkedHashMap<>();

    private static void register(Archetype a) {
        BY_ID.put(a.id().toLowerCase(Locale.ROOT), a);
    }

    /** Shorthand for a layer (bottom → top). */
    private static PlanetTerrain.Layer layer(String block, int height) {
        return new PlanetTerrain.Layer(block, height);
    }

    static {
        register(new Archetype(
                "terran", "Terran", Material.GRASS_BLOCK,
                "Lush green land with rich soil.",
                List.of(layer("stone", 2), layer("dirt", 3), layer("grass_block", 3)),
                "plains",
                null, null, null, 0, Map.of(), null));

        register(new Archetype(
                "desert", "Desert", Material.SAND,
                "Endless dunes under a blazing sun.",
                List.of(layer("sandstone", 4), layer("sand", 4)),
                "desert",
                "#FFD080", "#E0A050", "embers", 6, Map.of(), "clear"));

        register(new Archetype(
                "mars", "Mars", Material.RED_SAND,
                "A frozen red desert with dust storms.",
                List.of(layer("red_sandstone", 4), layer("red_sand", 4)),
                "desert",
                "#FF8844", "#AA5522", "ash", 10, Map.of(), "clear"));

        register(new Archetype(
                "moon", "Moon", Material.BONE_BLOCK,
                "Silent gray wastes with low gravity.",
                List.of(layer("stone", 3), layer("calcite", 3), layer("bone_block", 2)),
                "snowy_plains",
                "#D8E0E8", "#9098A0", "glow", 4,
                Map.of("jump", 2, "fall", 1), "clear"));

        register(new Archetype(
                "lava", "Lava", Material.MAGMA_BLOCK,
                "A molten world of fire and black stone.",
                List.of(layer("basalt", 2), layer("blackstone", 3), layer("magma_block", 1)),
                "basalt_deltas",
                "#FF6600", "#551100", "lava", 10,
                Map.of("fire_resistance", 1), "clear"));

        register(new Archetype(
                "ocean", "Ocean", Material.WATER_BUCKET,
                "A shallow water world with sandy floors.",
                List.of(layer("stone", 2), layer("sand", 3), layer("water", 2)),
                "deep_ocean",
                "#40A0FF", "#1050A0", "bubbles", 8, Map.of("swim", 1, "breath", 1), null));

        register(new Archetype(
                "crystal", "Crystal", Material.AMETHYST_BLOCK,
                "Geode fields under a violet alien sky.",
                List.of(layer("end_stone", 3), layer("purpur_block", 2), layer("amethyst_block", 1)),
                "the_end",
                "#C080FF", "#5020A0", "magic", 8,
                Map.of("night_vision", 1), "clear"));
    }

    /** All archetypes in menu order. */
    public static List<Archetype> all() {
        return List.copyOf(BY_ID.values());
    }

    /** The archetype with the given id, or null when unknown. */
    public static Archetype byId(String id) {
        if (id == null) {
            return null;
        }
        return BY_ID.get(id.toLowerCase(Locale.ROOT));
    }

    /** The archetype ids, for tab completion and validation. */
    public static List<String> ids() {
        return new ArrayList<>(BY_ID.keySet());
    }
}
