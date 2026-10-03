package me.foivos.planets.worldgen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The built-in structure builders, and the small parameter vocabulary each one
 * understands. Structures are data, not code: a profile picks a builder by name
 * and tunes it with {@code params}, so a new planet never needs a new class.
 * <p>
 * The registry is the extension point for developers too — {@link #register}
 * lets another module add a builder that profiles can then reference.
 *
 * @param id          the name used in profiles
 * @param description what it builds, shown by {@code /planets profile}
 * @param parameters  the params it reads, with a one-line meaning each
 */
public record StructureTypes(String id, String description, Map<String, String> parameters) {

    private static final Map<String, StructureTypes> BY_ID = new LinkedHashMap<>();

    private static void register(StructureTypes type) {
        BY_ID.put(type.id().toLowerCase(Locale.ROOT), type);
    }

    static {
        register(new StructureTypes("ruin", "A collapsed wall-and-column ruin", Map.of(
                "width", "footprint width in blocks",
                "height", "wall height",
                "pillars", "how many standing columns",
                "palette", "material palette key to build from")));
        register(new StructureTypes("tower", "A tapering tower with a lit top", Map.of(
                "height", "tower height",
                "base-width", "footprint width at the bottom")));
        register(new StructureTypes("temple", "A stepped platform with a central altar", Map.of(
                "size", "platform half-width",
                "steps", "how many steps up to the altar")));
        register(new StructureTypes("monument", "A tall spire of stacked blocks", Map.of(
                "height", "spire height",
                "shape", "spire | obelisk | crystal")));
        register(new StructureTypes("fossil", "A buried or exposed skeleton", Map.of(
                "length", "skeleton length in blocks",
                "height", "rib height",
                "buried", "true to sink it into the ground")));
        register(new StructureTypes("bones", "A scatter of bone blocks and ribs", Map.of(
                "radius", "scatter radius")));
        register(new StructureTypes("giant-tree", "A very large tree with a wide canopy", Map.of(
                "height", "trunk height",
                "radius", "canopy radius",
                "branches", "how many side branches")));
        register(new StructureTypes("dead-tree", "A bare, broken trunk", Map.of(
                "height", "trunk height")));
        register(new StructureTypes("crystal", "A cluster of crystal spikes", Map.of(
                "height", "tallest spike",
                "count", "how many spikes")));
        register(new StructureTypes("volcanic-vent", "A magma vent with a lava throat", Map.of(
                "radius", "vent radius",
                "height", "cone height")));
        register(new StructureTypes("crashed-ship", "A half-buried hull", Map.of(
                "length", "hull length",
                "tilt", "how much it leans into the ground")));
        register(new StructureTypes("nest", "A ring of eggs and bones", Map.of(
                "radius", "nest radius",
                "eggs", "how many eggs")));
        register(new StructureTypes("campsite", "A small abandoned camp", Map.of(
                "tents", "how many tents",
                "campfire", "whether the fire is still burning")));
        register(new StructureTypes("well", "A stone well with a roof", Map.of()));
        register(new StructureTypes("stone-circle", "A ring of standing stones", Map.of(
                "radius", "circle radius",
                "stones", "how many stones")));
        register(new StructureTypes("ice-spike", "A tall frozen spike", Map.of(
                "height", "spike height",
                "count", "how many spikes")));
        register(new StructureTypes("mushroom-cluster", "A cluster of giant mushrooms", Map.of(
                "count", "how many mushrooms",
                "height", "stem height")));
        register(new StructureTypes("platform", "A floating platform, for sky worlds", Map.of(
                "radius", "platform radius",
                "thickness", "platform thickness")));
    }

    /** Whether a builder of this name exists. */
    public static boolean exists(String id) {
        return id != null && BY_ID.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public static StructureTypes byId(String id) {
        return id == null ? null : BY_ID.get(id.toLowerCase(Locale.ROOT));
    }

    /** Every registered builder id, for validation messages. */
    public static List<String> ids() {
        return List.copyOf(BY_ID.keySet());
    }

    /** Every registered builder, for {@code /planets profile}. */
    public static List<StructureTypes> all() {
        return List.copyOf(BY_ID.values());
    }
}
