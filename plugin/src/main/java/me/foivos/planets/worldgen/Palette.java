package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A planet's named materials, so terrain, structures, vegetation and atmosphere
 * can all be themed from one place:
 *
 * <pre>
 * palette:
 *   primary:   STONE
 *   secondary: ANDESITE
 *   accent:    AMETHYST_BLOCK
 *   rare:      DIAMOND_BLOCK
 * </pre>
 *
 * Anything that asks for a material asks the palette first and falls back to a
 * plain Bukkit material name, then to a hard default — a typo can never leave a
 * hole in the world, it just logs once and uses the fallback.
 */
public final class Palette {

    /** Names every planet understands without writing a {@code palette} section. */
    private static final Map<String, Material> BASE = base();

    private final Map<String, String> entries;

    private Palette(Map<String, String> entries) {
        this.entries = entries;
    }

    public static Palette empty() {
        return new Palette(Map.of());
    }

    /** Reads a palette section; keys are lowercased, values are kept as written. */
    public static Palette from(ConfigurationSection section) {
        if (section == null) {
            return empty();
        }
        Map<String, String> entries = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value != null && !value.isBlank()) {
                entries.put(key.toLowerCase(Locale.ROOT), value);
            }
        }
        return new Palette(Map.copyOf(entries));
    }

    /** Whether a palette entry of this name exists. */
    public boolean has(String key) {
        return key != null && entries.containsKey(key.toLowerCase(Locale.ROOT));
    }

    /** The raw name a palette key maps to, or null when the key is not defined. */
    public String raw(String key) {
        return key == null ? null : entries.get(key.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves a material: a palette key first, then a real Bukkit material name,
     * then the supplied fallback (never null).
     */
    public Material get(String key, Material fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        String mapped = raw(key);
        if (mapped != null) {
            Material resolved = resolve(mapped, null);
            if (resolved != null) {
                return resolved;
            }
        }
        return resolve(key, fallback);
    }

    /** The material for a name (palette-independent), or the fallback when unknown. */
    public static Material resolve(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String trimmed = name.trim();
        Material base = BASE.get(trimmed.toLowerCase(Locale.ROOT));
        if (base != null) {
            return base;
        }
        Material direct = Material.matchMaterial(trimmed);
        if (direct != null && direct.isBlock()) {
            return direct;
        }
        String id = trimmed.toLowerCase(Locale.ROOT);
        if (!id.contains(":")) {
            Material namespaced = Material.matchMaterial("minecraft:" + id);
            if (namespaced != null && namespaced.isBlock()) {
                return namespaced;
            }
        }
        return fallback;
    }

    /** Whether a name resolves to a real block material. */
    public static boolean isValidBlock(String name) {
        return resolve(name, null) != null;
    }

    /** Unique palette keys, for the debug command. */
    public java.util.Set<String> keys() {
        return entries.keySet();
    }

    private static Map<String, Material> base() {
        Map<String, Material> materials = new LinkedHashMap<>();
        materials.put("air", Material.AIR);
        materials.put("water", Material.WATER);
        materials.put("lava", Material.LAVA);
        materials.put("stone", Material.STONE);
        materials.put("deepslate", Material.DEEPSLATE);
        materials.put("dirt", Material.DIRT);
        materials.put("grass", Material.GRASS_BLOCK);
        materials.put("sand", Material.SAND);
        materials.put("gravel", Material.GRAVEL);
        materials.put("ice", Material.PACKED_ICE);
        materials.put("snow", Material.SNOW_BLOCK);
        materials.put("bedrock", Material.BEDROCK);
        return Map.copyOf(materials);
    }
}
