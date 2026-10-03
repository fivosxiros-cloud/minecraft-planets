package me.foivos.planets.worldgen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where generation styles find their implementation.
 * <p>
 * The engine ships one module per built-in {@link GenerationStyle}; a developer
 * adds another with {@link #register} and a profile can then name it, without
 * anything in the pipeline changing. A style with no module falls back to
 * {@link NaturalTerrain}, so a profile can never produce an empty world by
 * accident.
 */
public final class TerrainModules {

    private static final Map<GenerationStyle, TerrainModule> MODULES = new LinkedHashMap<>();

    private TerrainModules() {
    }

    static {
        register(new NaturalTerrain());
        register(new ShapeTerrain.FloatingIslands());
        register(new ShapeTerrain.Cavern());
        register(new ShapeTerrain.Void());
        register(new BackroomsTerrain());
    }

    /** Registers a module, replacing any earlier one for the same style. */
    public static void register(TerrainModule module) {
        MODULES.put(module.style(), module);
    }

    /** The module for a style, or the natural one when the style has none. */
    public static TerrainModule forStyle(GenerationStyle style) {
        TerrainModule module = MODULES.get(style);
        return module != null ? module : MODULES.get(GenerationStyle.NATURAL);
    }

    /** Whether a style has a module of its own. */
    public static boolean has(GenerationStyle style) {
        return MODULES.containsKey(style);
    }

    /** Every registered style, for the debug command. */
    public static List<GenerationStyle> styles() {
        return List.copyOf(MODULES.keySet());
    }

    /** Human-readable list of the registered styles. */
    public static String describe() {
        StringBuilder text = new StringBuilder();
        for (GenerationStyle style : MODULES.keySet()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(style.name().toLowerCase(Locale.ROOT));
        }
        return text.toString();
    }
}
