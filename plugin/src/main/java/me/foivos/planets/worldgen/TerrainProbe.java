package me.foivos.planets.worldgen;

/**
 * Asks the terrain where its surface is, without generating a chunk.
 * <p>
 * This is the one thing that must not diverge from the generator: if the surface
 * pass says one height and this says another, structures float or sink. So it
 * does not re-derive anything — it calls the same
 * {@link NaturalTerrain#heightAt} the generator used, and then applies the same
 * surface-stack depth so it reports the <em>visible</em> ground rather than the
 * raw noise height.
 */
public final class TerrainProbe {

    private TerrainProbe() {
    }

    /** The y of the top solid block at a world position, or the world floor when empty. */
    public static int surfaceY(GenerationContext context, int x, int z) {
        PlanetProfile profile = context.profile();
        GenerationStyle style = profile.style();
        if (style == GenerationStyle.BACKROOMS || style == GenerationStyle.MAZE_WORLD
                || style == GenerationStyle.SUPERFLAT_CUSTOM) {
            // Architectural worlds have one absolute floor; there is nothing to sample.
            return clamp(profile.layoutInt("floor", profile.terrain().clampedBase()),
                    context.floor() + 2, context.roof() - 14);
        }
        if (style == GenerationStyle.VOID_WORLD) {
            return context.minHeight();
        }
        if (style == GenerationStyle.CAVERN_WORLD) {
            double height = NaturalTerrain.heightAt(context, x, z, profile.terrain());
            return (int) Math.floor(Math.max(height, profile.terrain().minHeight() + 8));
        }
        if (style == GenerationStyle.FLOATING_ISLANDS) {
            return floatingTop(context, x, z);
        }
        return (int) Math.floor(NaturalTerrain.heightAt(context, x, z, profile.terrain()));
    }

    /**
     * The top of the highest floating blob over a column. This is only used for
     * placement checks (can a structure sit here?), so it can be a coarse scan:
     * the island module itself is what actually generates the blocks.
     */
    private static int floatingTop(GenerationContext context, int x, int z) {
        TerrainSettings.FloatingIslandSettings settings = context.profile().terrain().floatingIslands();
        NoiseSampler sampler = context.floatingIslands();
        if (sampler == null) {
            return context.minHeight();
        }
        for (int y = settings.maxY(); y >= settings.minY(); y--) {
            double vertical = (y - settings.minY()) / (double) settings.spanY();
            double centre = 0.45 + 0.2 * sampler.sample(x * 0.35, z * 0.35);
            double distance = Math.abs(vertical - centre);
            double density = sampler.sample(x, y * 2.4, z) * 0.6 + (settings.density() - distance) * 2.4;
            if (density > 0) {
                return y;
            }
        }
        return context.minHeight();
    }

    /** Whether a world position is inside the column's solid ground. */
    public static boolean isGround(GenerationContext context, int x, int y, int z) {
        int surface = surfaceY(context, x, z);
        return y <= surface && surface > context.minHeight();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
