package me.foivos.planets.worldgen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The built-in decoration builders — the things that grow, sprout and scatter on
 * a planet's surface. Like {@link StructureTypes} this is a registry rather than
 * a switch statement, so a new planet only ever needs configuration and another
 * module can add builders without touching the engine.
 *
 * @param id          the name used in profiles
 * @param description what it places
 * @param parameters  the params it reads
 */
public record DecorationTypes(String id, String description, Map<String, String> parameters) {

    private static final Map<String, DecorationTypes> BY_ID = new LinkedHashMap<>();

    private static void register(DecorationTypes type) {
        BY_ID.put(type.id().toLowerCase(Locale.ROOT), type);
    }

    static {
        register(new DecorationTypes("grass", "Short ground cover", Map.of(
                "block", "the plant block (default SHORT_GRASS)",
                "height", "how tall the patch grows")));
        register(new DecorationTypes("flower", "Scattered flowers", Map.of(
                "blocks", "comma-separated flower blocks")));
        register(new DecorationTypes("mushroom", "Small mushrooms", Map.of(
                "blocks", "comma-separated mushroom blocks")));
        register(new DecorationTypes("bush", "A low leafy bush", Map.of(
                "radius", "bush radius",
                "height", "bush height")));
        register(new DecorationTypes("tree", "A vanilla-shaped tree, data-driven", Map.of(
                "trunk", "trunk block",
                "leaves", "leaf block",
                "height", "trunk height",
                "canopy", "canopy shape: blob | conical | flat",
                "canopy-radius", "canopy radius")));
        register(new DecorationTypes("giant-tree", "A very large tree", Map.of(
                "trunk", "trunk block",
                "leaves", "leaf block",
                "height", "trunk height",
                "radius", "canopy radius",
                "branches", "how many side branches")));
        register(new DecorationTypes("dead-tree", "A bare, broken trunk", Map.of(
                "block", "trunk block",
                "height", "trunk height")));
        register(new DecorationTypes("crystal", "A cluster of crystal spikes", Map.of(
                "block", "crystal block",
                "height", "tallest spike",
                "count", "how many spikes")));
        register(new DecorationTypes("rock", "A boulder", Map.of(
                "block", "boulder block",
                "radius", "boulder radius")));
        register(new DecorationTypes("bones", "Scattered bone blocks", Map.of(
                "block", "bone block",
                "radius", "scatter radius")));
        register(new DecorationTypes("fossil", "A small buried skeleton", Map.of(
                "length", "skeleton length",
                "height", "rib height")));
        register(new DecorationTypes("cactus", "A column cactus", Map.of(
                "block", "cactus block",
                "height", "cactus height")));
        register(new DecorationTypes("vine", "Hanging vines", Map.of(
                "block", "vine block",
                "length", "vine length")));
        register(new DecorationTypes("lichen", "Ground lichen and moss patches", Map.of(
                "block", "patch block",
                "radius", "patch radius")));
        register(new DecorationTypes("spore-pod", "A glowing alien pod", Map.of(
                "block", "pod block",
                "light", "light block placed above it")));
    }

    /** Whether a builder of this name exists. */
    public static boolean exists(String id) {
        return id != null && BY_ID.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public static DecorationTypes byId(String id) {
        return id == null ? null : BY_ID.get(id.toLowerCase(Locale.ROOT));
    }

    /** Every registered builder id, for validation messages. */
    public static List<String> ids() {
        return List.copyOf(BY_ID.keySet());
    }

    /** Every registered builder, for {@code /planets profile}. */
    public static List<DecorationTypes> all() {
        return List.copyOf(BY_ID.values());
    }
}
