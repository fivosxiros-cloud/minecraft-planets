package me.foivos.planets.worldgen;

import java.util.Locale;

/**
 * The generation philosophy of a planet.
 * <p>
 * A style is not a preset you are stuck with: it chooses which
 * {@link TerrainModule} builds the world and what a missing setting defaults
 * to. A planet can pick {@code NATURAL} and then override every individual
 * number, or pick {@code BACKROOMS} and get an architectural maze the noise
 * engine never touches.
 */
public enum GenerationStyle {

    /** Large-scale geography: continents, mountains, valleys, rivers, oceans. */
    NATURAL,

    /** A world that is essentially one mountain range after another. */
    MOUNTAIN_WORLD,

    /** Mostly liquid with a scatter of islands. */
    OCEAN_WORLD,

    /** Shallow seas with many small islands — no big landmasses. */
    ARCHIPELAGO,

    /** Disconnected terrain hanging in the sky, over a void. */
    FLOATING_ISLANDS,

    /** Underground-first: a solid shell of rock with a walkable cavern world inside. */
    CAVERN_WORLD,

    /** Nothing but air — used for stations and deliberate voids. */
    VOID_WORLD,

    /** Corridors and rooms, deterministic from the seed. */
    MAZE_WORLD,

    /** The Backrooms: level-0 rooms, halls, ceiling and carpet. */
    BACKROOMS,

    /** A stack of layers at a fixed height, with no noise at all. */
    SUPERFLAT_CUSTOM,

    /** A style added by another developer through the module registry. */
    CUSTOM;

    /** Parses a config value like {@code "floating-islands"}; null when unknown. */
    public static GenerationStyle parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "natural", "normal", "terrain", "normal_terrain" -> NATURAL;
            case "mountain", "mountains", "mountain_world" -> MOUNTAIN_WORLD;
            case "ocean", "ocean_world" -> OCEAN_WORLD;
            case "archipelago", "islands" -> ARCHIPELAGO;
            case "floating", "floating_islands", "sky" -> FLOATING_ISLANDS;
            case "cavern", "cavern_world", "underground", "cave" -> CAVERN_WORLD;
            case "void", "empty", "void_world" -> VOID_WORLD;
            case "maze", "maze_world", "labyrinth" -> MAZE_WORLD;
            case "backrooms", "backrooms_level_0", "level_0" -> BACKROOMS;
            case "superflat", "superflat_custom", "flat" -> SUPERFLAT_CUSTOM;
            case "custom" -> CUSTOM;
            default -> null;
        };
    }
}
