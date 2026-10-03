package me.foivos.planets.worldgen;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a biome written in a profile into a real Minecraft biome.
 * <p>
 * A planet biome is not the same thing as a Minecraft biome (see
 * {@link BiomeProfile}), but every planet biome still has to tell the client
 * <em>some</em> vanilla biome so it gets the right colors, mob spawns and
 * weather. This is the resolver for that name, with a few forgiving aliases
 * ("end", "snow", "swamp") so hand-written profiles read naturally.
 */
public final class PlanetBiomes {

    private PlanetBiomes() {
    }

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("end", "the_end"),
            Map.entry("the_end", "the_end"),
            Map.entry("nether", "nether_wastes"),
            Map.entry("snow", "snowy_plains"),
            Map.entry("snowy", "snowy_plains"),
            Map.entry("ice", "snowy_plains"),
            Map.entry("frozen", "snowy_plains"),
            Map.entry("swamp", "swamp"),
            Map.entry("ocean", "ocean"),
            Map.entry("deep_ocean", "deep_ocean"),
            Map.entry("forest", "forest"),
            Map.entry("mushroom", "mushroom_fields"),
            Map.entry("badlands", "badlands"),
            Map.entry("mesa", "badlands"),
            Map.entry("jungle", "jungle"),
            Map.entry("lush", "lush_caves"),
            Map.entry("dripstone", "dripstone_caves"),
            Map.entry("deep_dark", "deep_dark"),
            Map.entry("volcanic", "basalt_deltas"),
            Map.entry("lava", "basalt_deltas"));

    /**
     * The canonical id of a biome name, or null when nothing matches. The name
     * may be a full key ({@code minecraft:snowy_plains}), a plain id or one of
     * the friendly aliases above.
     */
    public static String resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        String alias = ALIASES.get(name);
        if (alias != null && exists(alias)) {
            return alias;
        }
        if (exists(name)) {
            return name;
        }
        return null;
    }

    /** The Bukkit biome for a canonical id, or null. */
    public static Biome biome(String id) {
        return id == null ? null : Registry.BIOME.get(NamespacedKey.minecraft(id));
    }

    /** Whether the server knows a biome by this id. */
    public static boolean exists(String id) {
        return biome(id) != null;
    }

    /** A short suggestion list for validation warnings. */
    public static List<String> common() {
        List<String> ids = new ArrayList<>();
        for (String id : List.of("plains", "forest", "desert", "snowy_plains", "swamp",
                "jungle", "savanna", "taiga", "badlands", "ocean", "the_end",
                "nether_wastes", "basalt_deltas", "mushroom_fields", "deep_dark")) {
            ids.add(id);
        }
        return ids;
    }
}
