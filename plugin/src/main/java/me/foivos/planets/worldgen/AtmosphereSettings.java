package me.foivos.planets.worldgen;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * How a planet feels before you look at the ground: sky, fog, weather, particles,
 * locked time, physics effects and hostile-atmosphere damage.
 * <p>
 * Two things are worth being explicit about, because the split matters:
 * <ul>
 *   <li><b>Server-side, always available</b> — ambient particles, locked weather,
 *       locked time of day, per-planet potion effects and hostile-atmosphere
 *       damage. These are plain Bukkit calls the plugin already makes, so a planet
 *       gets them with no client mod and no extra plugin.</li>
 *   <li><b>Packet-level, best effort</b> — the real sky and fog colour. On these
 *       Paper versions the client renders sky/fog from the dimension type, so a
 *       true tint needs ProtocolLib ({@code PlanetSkyPackets}); without it the
 *       planet still gets the coloured particle haze. The colours are always kept
 *       here so {@code /planets world <planet> status} can report them.</li>
 * </ul>
 *
 * @param skyColor        dimension-type sky colour, or null
 * @param fogColor        dimension-type fog colour, or null
 * @param waterColor      water tint (informational on these versions)
 * @param hazeDensity     coloured haze particles per second per player (0 = none)
 * @param particles       ambient particle effects
 * @param weather         forced weather: "clear", "rain", "thunder" or null
 * @param precipitation   whether rain may fall here, or null to leave it alone
 * @param lockedTime      fixed time of day, or null
 * @param cloudHeight     cloud layer height, or null
 * @param effects         potion effects, friendly name -> amplifier
 * @param damagePerSecond hostile atmosphere damage, 0 for a breathable planet
 * @param helmets         helmet materials that protect; empty = any helmet
 * @param musicTracks     soundtrack entries for this planet ("SOUND:seconds")
 */
public record AtmosphereSettings(
        String skyColor,
        String fogColor,
        String waterColor,
        int hazeDensity,
        List<Particle> particles,
        String weather,
        Boolean precipitation,
        Integer lockedTime,
        Double cloudHeight,
        Map<String, Integer> effects,
        double damagePerSecond,
        List<String> helmets,
        List<String> musicTracks) {

    /**
     * One ambient particle effect.
     *
     * @param type      a Bukkit {@code Particle} name, or one of the plugin's own
     *                  effect aliases (embers, snow, rain, ash, bubbles, fireflies,
     *                  magic, smoke, glow, sculk, lava, spores, dust)
     * @param density   particles per second per player
     * @param radius    horizontal spawn radius, in blocks
     * @param heightMin lowest offset from the player's feet
     * @param heightMax highest offset from the player's feet
     * @param color     dust colour for colour-capable particles, or null
     */
    public record Particle(String type, double density, double radius,
                           double heightMin, double heightMax, String color) {

        public static Particle of(String type, double density) {
            return new Particle(type, density, 3.0, 0.4, 3.0, null);
        }

        public static Particle from(ConfigurationSection section) {
            return new Particle(section.getString("type", section.getString("particle", "dust")),
                    Math.max(0, section.getDouble("density", 6)),
                    Math.max(0.1, section.getDouble("radius", 3.0)),
                    section.getDouble("min-height", section.getDouble("height-min", 0.4)),
                    section.getDouble("max-height", section.getDouble("height-max", 3.0)),
                    section.getString("color"));
        }

        public String describe() {
            return type + " ×" + Math.round(density) + "/s r" + radius;
        }
    }

    public static AtmosphereSettings none() {
        return new AtmosphereSettings(null, null, null, 0, List.of(), null, null, null, null,
                Map.of(), 0, List.of(), List.of());
    }

    /** Whether this planet changes anything about the vanilla environment. */
    public boolean any() {
        return skyColor != null || fogColor != null || hazeDensity > 0 || !particles.isEmpty()
                || weather != null || precipitation != null || lockedTime != null
                || cloudHeight != null || !effects.isEmpty() || damagePerSecond > 0
                || !musicTracks.isEmpty();
    }

    /** The colours as Bukkit colors, for the packet layer and the status view. */
    public Color sky() {
        return color(skyColor);
    }

    public Color fog() {
        return color(fogColor);
    }

    /** Parses {@code #RRGGBB} / {@code 0xRRGGBB} / a Bukkit color name; null when invalid. */
    public static Color color(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        } else if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
        }
        if (text.length() == 6) {
            try {
                return Color.fromRGB(Integer.parseInt(text, 16));
            } catch (NumberFormatException ignored) {
                // fall through to the named colours
            }
        }
        for (java.lang.reflect.Field field : Color.class.getFields()) {
            if (field.getType() == Color.class && field.getName().equalsIgnoreCase(text)) {
                try {
                    return (Color) field.get(null);
                } catch (IllegalAccessException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Debug lines. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (skyColor != null) {
            lines.add("sky " + skyColor + (fogColor == null ? "" : ", fog " + fogColor)
                    + (waterColor == null ? "" : ", water " + waterColor)
                    + (hazeDensity > 0 ? ", haze " + hazeDensity + "/s" : ""));
        } else if (hazeDensity > 0) {
            lines.add("haze " + hazeDensity + "/s");
        }
        for (Particle particle : particles) {
            lines.add("particles " + particle.describe());
        }
        if (weather != null) {
            lines.add("weather locked to " + weather);
        }
        if (precipitation != null) {
            lines.add("precipitation " + (precipitation ? "on" : "off"));
        }
        if (lockedTime != null) {
            lines.add("time locked to " + lockedTime);
        }
        if (cloudHeight != null) {
            lines.add("clouds at y " + cloudHeight);
        }
        if (!effects.isEmpty()) {
            lines.add("effects " + effects);
        }
        if (damagePerSecond > 0) {
            lines.add("hostile atmosphere: " + damagePerSecond + " damage/s"
                    + (helmets.isEmpty() ? ", any helmet protects" : ", needs " + String.join("/", helmets)));
        }
        if (!musicTracks.isEmpty()) {
            lines.add("music " + musicTracks.size() + " track(s)");
        }
        if (lines.isEmpty()) {
            lines.add("vanilla environment");
        }
        return lines;
    }

    // ── Config parsing ───────────────────────────────────────────────────

    public static AtmosphereSettings from(ConfigurationSection section, AtmosphereSettings fallback,
                                          Consumer<String> warn) {
        if (section == null) {
            return fallback;
        }
        ConfigurationSection sky = section.getConfigurationSection("sky");
        String skyColor = first(section.getString("sky-color"),
                sky == null ? null : sky.getString("color"), fallback.skyColor());
        String fogColor = first(section.getString("fog-color"),
                sky == null ? null : sky.getString("fog"), fallback.fogColor());
        String waterColor = first(section.getString("water-color"),
                sky == null ? null : sky.getString("water"), fallback.waterColor());
        if (skyColor != null && color(skyColor) == null) {
            warn.accept("atmosphere sky color '" + skyColor + "' is not a colour like #FF8800 — ignored");
            skyColor = fallback.skyColor();
        }
        if (fogColor != null && color(fogColor) == null) {
            warn.accept("atmosphere fog color '" + fogColor + "' is not a colour — ignored");
            fogColor = fallback.fogColor();
        }
        int haze = Math.max(0, section.getInt("haze-density",
                section.getInt("haze", fallback.hazeDensity())));

        List<Particle> particles = new ArrayList<>();
        List<?> raw = section.getList("particles");
        if (raw != null) {
            for (Object entry : raw) {
                if (entry instanceof ConfigurationSection particle) {
                    particles.add(Particle.from(particle));
                } else if (entry instanceof Map<?, ?> map) {
                    Object typeValue = map.get("type") != null ? map.get("type") : map.get("particle");
                    String type = typeValue == null ? "dust" : String.valueOf(typeValue);
                    Object density = map.get("density");
                    particles.add(new Particle(type,
                            density instanceof Number number ? number.doubleValue() : 6,
                            map.get("radius") instanceof Number radius ? radius.doubleValue() : 3.0,
                            map.get("min-height") instanceof Number low ? low.doubleValue() : 0.4,
                            map.get("max-height") instanceof Number high ? high.doubleValue() : 3.0,
                            map.get("color") == null ? null : String.valueOf(map.get("color"))));
                } else if (entry != null) {
                    // "embers:10" shorthand.
                    String text = entry.toString().trim();
                    double density = 6;
                    int colon = text.lastIndexOf(':');
                    if (colon > 0) {
                        try {
                            density = Double.parseDouble(text.substring(colon + 1).trim());
                            text = text.substring(0, colon).trim();
                        } catch (NumberFormatException ignored) {
                            // keep the whole string as the effect name
                        }
                    }
                    if (!text.isEmpty()) {
                        particles.add(Particle.of(text.toLowerCase(Locale.ROOT), density));
                    }
                }
            }
        }

        String weather = section.getString("weather", fallback.weather());
        if (weather != null && !weather.equalsIgnoreCase("clear") && !weather.equalsIgnoreCase("rain")
                && !weather.equalsIgnoreCase("thunder") && !weather.equalsIgnoreCase("normal")) {
            warn.accept("atmosphere.weather '" + weather + "' is not clear/rain/thunder — left alone");
            weather = fallback.weather();
        }
        if ("normal".equalsIgnoreCase(weather)) {
            weather = null;
        }

        Map<String, Integer> effects = new LinkedHashMap<>(fallback.effects());
        ConfigurationSection effectSection = section.getConfigurationSection("effects");
        if (effectSection != null) {
            for (String key : effectSection.getKeys(false)) {
                String effect = key.toLowerCase(Locale.ROOT);
                Object value = effectSection.get(key);
                if (value instanceof Boolean enabled) {
                    // "jump: true" means the base level; "jump: false" removes it.
                    if (enabled) {
                        effects.put(effect, 0);
                    } else {
                        effects.remove(effect);
                    }
                } else if (value instanceof Number number) {
                    effects.put(effect, number.intValue());
                } else {
                    warn.accept("atmosphere.effects." + key + " must be a number (amplifier) — ignored");
                }
            }
        }

        List<String> helmets = new ArrayList<>();
        for (String helmet : section.getStringList("helmets")) {
            if (org.bukkit.Material.matchMaterial(helmet) == null) {
                warn.accept("atmosphere helmet '" + helmet + "' is not an item — ignored");
                continue;
            }
            helmets.add(helmet);
        }

        List<String> music = new ArrayList<>();
        ConfigurationSection musicSection = section.getConfigurationSection("music");
        List<String> rawMusic = musicSection == null
                ? section.getStringList("music")
                : musicSection.getStringList("tracks");
        for (String track : rawMusic) {
            music.add(track);
        }

        // The three boxed settings are parsed through locals: a ternary that mixes
        // a primitive branch with a boxed fallback unboxes the fallback and throws
        // when it is null (profiles are allowed to omit these keys entirely).
        Boolean precipitation = fallback.precipitation();
        if (section.isSet("precipitation")) {
            precipitation = section.getBoolean("precipitation");
        }
        Integer lockedTime = fallback.lockedTime();
        if (section.isSet("time")) {
            lockedTime = section.getInt("time");
        }
        Double cloudHeight = fallback.cloudHeight();
        if (section.isSet("cloud-height")) {
            cloudHeight = section.getDouble("cloud-height");
        }

        return new AtmosphereSettings(skyColor, fogColor, waterColor, haze,
                List.copyOf(particles), weather,
                precipitation,
                lockedTime,
                cloudHeight,
                Map.copyOf(effects),
                Math.max(0, section.getDouble("damage", section.getDouble("damage-per-second",
                        fallback.damagePerSecond()))),
                List.copyOf(helmets),
                List.copyOf(music));
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
