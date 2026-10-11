package me.foivos.planets.worldgen;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The planet designer: a planet's shape is chosen <em>before</em> its world is
 * made, by combining named <b>filters</b> — mountains, rivers, hills, plateaus,
 * cliffs, seas, islands, roughness, warping and overall relief.
 * <p>
 * Each filter offers presets (none / gentle / normal / dramatic, …) and every
 * preset is a partial {@code terrain} section, exactly the one a
 * {@code planets/*.yml} profile uses. Applying a preset runs it through
 * {@link TerrainSettings#from}, so a preset can override any of the engine's
 * real knobs — a mountain preset genuinely changes the ridge strength, not a
 * label. Because it is the same parser a hand-written profile uses, a designer
 * planet and a profile planet can be mixed freely.
 * <p>
 * A <b>design</b> is a named bundle of filter choices, so a planet can be built
 * from one word ({@code /planets create Alps alpine}) while an admin who wants
 * something specific can name the filters directly
 * ({@code /planets design Alps mountains=dramatic rivers=wide seas=deep}).
 * <p>
 * Everything here reads config.yml only; the engine is untouched.
 */
public final class PlanetDesign {

    private PlanetDesign() {
    }

    /** One choice of a filter. */
    public record Preset(String id, ConfigurationSection terrain) {
    }

    /** One knob the designer exposes, with its presets in menu order. */
    public record Filter(
            String id,
            String label,
            String description,
            Material icon,
            List<Preset> presets) {

        /** The preset with this id, or {@code null}. Ids are matched without case. */
        public Preset preset(String presetId) {
            if (presetId == null) {
                return null;
            }
            for (Preset preset : presets) {
                if (preset.id().equalsIgnoreCase(presetId)) {
                    return preset;
                }
            }
            return null;
        }

        /** The preset ids, for menus, errors and tab completion. */
        public List<String> presetIds() {
            return presets.stream().map(Preset::id).toList();
        }
    }

    /** Every filter from config.yml, in file order. */
    public static List<Filter> load(FileConfiguration config, Consumer<String> warn) {
        ConfigurationSection section = config.getConfigurationSection("planet-design.filters");
        if (section == null) {
            return List.of();
        }
        List<Filter> filters = new ArrayList<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                warn.accept("planet-design.filters." + id + " is not a section — skipped");
                continue;
            }
            ConfigurationSection presets = entry.getConfigurationSection("presets");
            if (presets == null || presets.getKeys(false).isEmpty()) {
                warn.accept("planet-design.filters." + id
                        + " has no presets — a filter with nothing to choose is skipped");
                continue;
            }
            List<Preset> built = new ArrayList<>();
            for (String presetId : presets.getKeys(false)) {
                ConfigurationSection terrain = presets.getConfigurationSection(presetId);
                if (terrain == null) {
                    warn.accept("planet-design.filters." + id + ".presets." + presetId
                            + " is not a section — skipped");
                    continue;
                }
                built.add(new Preset(presetId.toLowerCase(Locale.ROOT), terrain));
            }
            if (built.isEmpty()) {
                continue;
            }
            String label = entry.getString("display-name",
                    id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1).replace('_', ' '));
            String iconName = entry.getString("icon", "GRASS_BLOCK");
            Material icon = Material.matchMaterial(iconName);
            if (icon == null || icon == Material.AIR) {
                warn.accept("planet-design.filters." + id + ": icon '" + iconName
                        + "' is not a material — using GRASS_BLOCK");
                icon = Material.GRASS_BLOCK;
            }
            filters.add(new Filter(id.toLowerCase(Locale.ROOT), label,
                    entry.getString("description", "A terrain filter"), icon, List.copyOf(built)));
        }
        return List.copyOf(filters);
    }

    /** The filter with this id, or {@code null}. Matched without case. */
    public static Filter filter(List<Filter> filters, String id) {
        if (id == null) {
            return null;
        }
        for (Filter filter : filters) {
            if (filter.id().equalsIgnoreCase(id)) {
                return filter;
            }
        }
        return null;
    }

    /** The baseline style the designer shapes from ({@code planet-design.style}). */
    public static GenerationStyle baselineStyle(FileConfiguration config, Consumer<String> warn) {
        String requested = config.getString("planet-design.style", "natural");
        GenerationStyle style = GenerationStyle.parse(requested);
        if (style == null) {
            warn.accept("planet-design.style '" + requested
                    + "' is not a generation style — using NATURAL");
            return GenerationStyle.NATURAL;
        }
        return style;
    }

    /**
     * Layers the chosen presets onto a baseline terrain and returns the resulting
     * shape. Filters are applied in the order they appear in config.yml, so the
     * same choices always produce the same terrain (a weaker filter cannot
     * silently undo a stronger one by being applied last at random).
     *
     * @param choices filter id → preset id; unknown pairs are reported and skipped
     */
    public static TerrainSettings shape(TerrainSettings baseline, List<Filter> filters,
                                        Map<String, String> choices, Consumer<String> warn) {
        TerrainSettings running = baseline;
        for (Filter filter : filters) {
            String chosen = null;
            for (Map.Entry<String, String> choice : choices.entrySet()) {
                if (choice.getKey().equalsIgnoreCase(filter.id())) {
                    chosen = choice.getValue();
                    break;
                }
            }
            if (chosen == null) {
                continue;
            }
            Preset preset = filter.preset(chosen);
            if (preset == null) {
                warn.accept("filter '" + filter.id() + "' has no preset '" + chosen
                        + "' — ignored (try " + String.join(", ", filter.presetIds()) + ")");
                continue;
            }
            running = TerrainSettings.from(preset.terrain(), running, warn);
        }
        return running;
    }

    /** The style a design is built from, or the designer's baseline. */
    public static GenerationStyle styleOf(ConfigurationSection design, GenerationStyle fallback,
                                          Consumer<String> warn) {
        if (design == null) {
            return fallback;
        }
        String requested = design.getString("style");
        if (requested == null) {
            return fallback;
        }
        GenerationStyle style = GenerationStyle.parse(requested);
        if (style == null) {
            warn.accept("style '" + requested + "' is not a generation style — using " + fallback);
            return fallback;
        }
        return style;
    }

    /**
     * Builds a planet profile for one combination of filter choices: the
     * baseline style's terrain, shaped by every chosen preset, dressed with that
     * style's palette, biomes, surface and caves.
     * <p>
     * Structures and decorations stay off — the shape is what is being designed —
     * and the created world can turn them back on per planet.
     *
     * @param design  the design's own section (icon/description), or {@code null}
     */
    public static PlanetProfile build(String id,
                                      String displayName,
                                      String description,
                                      ConfigurationSection design,
                                      GenerationStyle style,
                                      List<Filter> filters,
                                      Map<String, String> choices,
                                      Consumer<String> warn) {
        TerrainSettings baseline = PlanetProfile.styleTerrain(style);
        TerrainSettings terrain = shape(baseline, filters, choices, warn);

        Map<String, BiomeProfile> biomes = Map.of("default", BiomeProfile.fallback("default", "plains"));
        // A design picks its materials, not just its shape. The default surface stack
        // is written in palette-key names ("grass_block", "dirt", "stone"), and
        // Palette.get resolves a key through the palette before falling back to a real
        // material name — so an entry named grass_block is what turns a design's top
        // block into moon basalt, blackstone or terracotta. Without one, every design
        // came out looking like overworld stone and dirt.
        Palette palette = Palette.from(design == null ? null
                : design.getConfigurationSection("palette"));
        List<BiomeProfile.SurfaceLayer> surface = designedSurface(design, style, terrain, warn);
        CaveSettings caves = CaveSettings.from(null, PlanetProfile.styleCaves(style, terrain), warn);

        String iconName = design == null ? "GRASS_BLOCK" : design.getString("icon", "GRASS_BLOCK");
        if (Material.matchMaterial(iconName) == null) {
            warn.accept("design '" + id + "': icon '" + iconName + "' is not a material — using GRASS_BLOCK");
            iconName = "GRASS_BLOCK";
        }

        return new PlanetProfile(
                id,
                displayName,
                description,
                iconName,
                style,
                "random",
                0L,
                palette,
                terrain,
                biomes,
                "default",
                surface,
                caves,
                StructureSettings.none(),
                DecorationSettings.none(),
                AtmosphereSettings.none(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                PlanetProfile.ResourcePack.none(),
                true,
                "config.yml (planet-design)");
    }

    /**
     * A design's own surface stack when it writes one, else the style's default.
     * <p>
     * This is what lets one design read as a dry salt plain with green islands on it:
     * a surface layer may carry an altitude range, so the top block changes with the
     * height the terrain ended up at. Layers are listed top → down and the first match
     * wins, exactly as in a planet profile.
     */
    private static List<BiomeProfile.SurfaceLayer> designedSurface(ConfigurationSection design,
                                                                  GenerationStyle style,
                                                                  TerrainSettings terrain,
                                                                  Consumer<String> warn) {
        List<BiomeProfile.SurfaceLayer> fallback = PlanetProfile.defaultSurface(style, terrain);
        ConfigurationSection layers = design == null
                ? null : design.getConfigurationSection("surface.layers");
        if (layers == null) {
            return fallback;
        }
        List<BiomeProfile.SurfaceLayer> built = new ArrayList<>();
        for (String key : layers.getKeys(false)) {
            ConfigurationSection entry = layers.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            BiomeProfile.SurfaceLayer layer = BiomeProfile.SurfaceLayer.from(entry, warn);
            if (layer != null) {
                built.add(layer);
            }
        }
        return built.isEmpty() ? fallback : List.copyOf(built);
    }

    /**
     * The ready-made designs: {@code planet-design.designs.<id>.filters} names a
     * preset for some or all filters. A design with no choices at all is the
     * designer's plain baseline and is skipped, so an empty template section in
     * config.yml never turns into a planet.
     */
    public static Map<String, PlanetProfile> designs(FileConfiguration config, List<Filter> filters,
                                                     Consumer<String> warn) {
        return buildAll(config, config.getConfigurationSection("planet-design.designs"), filters, warn);
    }

    /**
     * Planets built with {@code /planets design}, saved back into config.yml so
     * they outlive a restart: their shape has to be known again when the server
     * asks for the world's generator.
     */
    public static Map<String, PlanetProfile> saved(FileConfiguration config, List<Filter> filters,
                                                   Consumer<String> warn) {
        return buildAll(config, config.getConfigurationSection("planet-design.designed"), filters, warn);
    }

    private static Map<String, PlanetProfile> buildAll(FileConfiguration config, ConfigurationSection section,
                                                       List<Filter> filters, Consumer<String> warn) {
        if (section == null || filters.isEmpty()) {
            return Map.of();
        }
        GenerationStyle baselineStyle = baselineStyle(config, warn);
        Map<String, PlanetProfile> built = new LinkedHashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection design = section.getConfigurationSection(id);
            if (design == null) {
                warn.accept(section.getCurrentPath() + "." + id + " is not a section — skipped");
                continue;
            }
            Map<String, String> choices = choicesOf(design, warn, id);
            if (choices.isEmpty()) {
                warn.accept(section.getCurrentPath() + "." + id + " picks no filters — skipped");
                continue;
            }
            GenerationStyle style = styleOf(design, baselineStyle, warn);
            String label = design.getString("display-name",
                    id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1).replace('_', ' '));
            String description = design.getString("description", describe(filters, choices));
            built.put(id.toLowerCase(Locale.ROOT),
                    build(id.toLowerCase(Locale.ROOT), label, description, design, style, filters, choices, warn));
        }
        return built;
    }

    /** Reads a design's {@code filters:} map ({@code mountains: dramatic}). */
    public static Map<String, String> choicesOf(ConfigurationSection design,
                                                Consumer<String> warn,
                                                String designId) {
        ConfigurationSection choices = design.getConfigurationSection("filters");
        if (choices == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String filterId : choices.getKeys(false)) {
            String presetId = choices.getString(filterId);
            if (presetId == null || presetId.isBlank()) {
                warn.accept("design '" + designId + "': filter '" + filterId + "' has no preset — ignored");
                continue;
            }
            result.put(filterId, presetId.trim());
        }
        return result;
    }

    /**
     * Parses {@code filter=preset} words from a command, so an admin can design a
     * planet without editing config.yml first.
     *
     * @return the choices, and fills {@code problems} with anything unusable
     */
    public static Map<String, String> parseChoices(String[] words, List<Filter> filters,
                                                   List<String> problems) {
        Map<String, String> choices = new LinkedHashMap<>();
        for (String word : words) {
            int eq = word.indexOf('=');
            if (eq <= 0 || eq == word.length() - 1) {
                problems.add("'" + word + "' is not a filter=preset pair");
                continue;
            }
            String filterId = word.substring(0, eq).trim();
            String presetId = word.substring(eq + 1).trim();
            Filter filter = filter(filters, filterId);
            if (filter == null) {
                problems.add("there is no filter '" + filterId + "' (try "
                        + filters.stream().map(Filter::id).reduce((a, b) -> a + ", " + b).orElse("none") + ")");
                continue;
            }
            if (filter.preset(presetId) == null) {
                problems.add("filter '" + filter.id() + "' has no preset '" + presetId + "' (try "
                        + String.join(", ", filter.presetIds()) + ")");
                continue;
            }
            choices.put(filter.id(), presetId.toLowerCase(Locale.ROOT));
        }
        return choices;
    }

    /** One line summarising a design's choices, for menus and messages. */
    public static String describe(List<Filter> filters, Map<String, String> choices) {
        if (choices.isEmpty()) {
            return "no filters — the plain baseline shape";
        }
        StringBuilder text = new StringBuilder();
        for (Filter filter : filters) {
            for (Map.Entry<String, String> choice : choices.entrySet()) {
                if (!choice.getKey().equalsIgnoreCase(filter.id())) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append(filter.label()).append(' ').append(choice.getValue());
            }
        }
        return text.length() == 0 ? "no filters" : text.toString();
    }

    /** A stable id for a hand-designed combination, so it can be registered. */
    public static String idFor(GenerationStyle style, Map<String, String> choices, List<Filter> filters) {
        StringBuilder id = new StringBuilder("design");
        for (Filter filter : filters) {
            for (Map.Entry<String, String> choice : choices.entrySet()) {
                if (choice.getKey().equalsIgnoreCase(filter.id())) {
                    id.append('-').append(filter.id()).append('-').append(choice.getValue());
                }
            }
        }
        if (choices.isEmpty()) {
            id.append('-').append(style.name().toLowerCase(Locale.ROOT));
        }
        return id.toString().toLowerCase(Locale.ROOT);
    }

    // ── Config migration ─────────────────────────────────────────────────

    /**
     * Copies the shipped designer sections into a config.yml that predates them.
     * <p>
     * {@code saveDefaultConfig()} only writes config.yml when the file does not
     * exist, and Bukkit's own defaults do not survive the nested lookups this class
     * makes: on a server whose config.yml was written before the designer existed,
     * {@code planet-design.filters} resolves to a section but yields no filters at
     * all, so every designer command reports "no terrain filters are configured" no
     * matter what the jar ships. Writing the sections into the server's own file is
     * what makes them real.
     * <p>
     * {@code planet-design.designed} is never touched: that section holds the planets
     * this server already built.
     *
     * @return how many filters the target config has afterwards
     */
    public static int seed(FileConfiguration target, FileConfiguration shipped) {
        return seed(target, shipped, null);
    }

    /**
     * @param existing the config.yml as it is on disk, with none of the jar's defaults
     *                 attached, so a key that exists only in the defaults counts as
     *                 missing — which is the whole reason the designer was invisible on
     *                 an older file. {@code null} means the file defines nothing at all.
     * @return how many settings were written
     */
    public static int seed(FileConfiguration target, FileConfiguration shipped,
                           FileConfiguration existing) {
        ConfigurationSection source = shipped == null ? null
                : shipped.getConfigurationSection("planet-design");
        if (source == null) {
            return 0;
        }
        int written = 0;
        for (String key : source.getKeys(false)) {
            if (key.equalsIgnoreCase("designed")) {
                continue; // this server's own designed planets
            }
            written += copyMissing(target, existing, source, key, "planet-design." + key);
        }
        return written;
    }

    /**
     * Writes one section's scalar leaves at {@code to}, but only the ones the file does not
     * already have: a filter or design an admin has edited is never overwritten, and running
     * this again after an update only adds what is genuinely new. Sections are skipped
     * because setting a leaf below one creates it, and copying a section object itself would
     * put a foreign ConfigurationSection into the target, which cannot be saved to YAML.
     */
    private static int copyMissing(FileConfiguration target, FileConfiguration existing,
                                   ConfigurationSection source, String from, String to) {
        ConfigurationSection section = source.getConfigurationSection(from);
        if (section == null) {
            Object value = source.get(from);
            if (value == null || defined(existing, to)) {
                return 0;
            }
            target.set(to, value);
            return 1;
        }
        int written = 0;
        for (String key : section.getKeys(true)) {
            if (section.isConfigurationSection(key)) {
                continue;
            }
            String path = to + "." + key;
            if (defined(existing, path)) {
                continue;
            }
            target.set(path, section.get(key));
            written++;
        }
        return written;
    }

    /** Whether the file itself defines a path, ignoring anything inherited from defaults. */
    private static boolean defined(FileConfiguration existing, String path) {
        return existing != null && existing.get(path, null) != null;
    }

}
