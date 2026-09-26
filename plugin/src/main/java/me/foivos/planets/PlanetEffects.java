package me.foivos.planets;

import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies per-world potion effects (the closest Bukkit gets to custom planet
 * physics) to players in worlds listed under "effects" in config.yml. This is
 * how low gravity, fast running, stronger punches, faster swimming and endless
 * breath are simulated: the configured effects are refreshed periodically while
 * a player stays in the world and removed when they leave, so they behave like
 * the planet's environment rather than potions the player picked up.
 */
final class PlanetEffects {

    /** Effect duration, long enough to survive between refreshes. */
    private static final int EFFECT_DURATION_TICKS = 300; // 15 seconds
    /** How often the effects are re-applied, in ticks. */
    private static final long REFRESH_INTERVAL_TICKS = 100L; // every 5 seconds

    /** worldKey (lowercase) -> effect key (lowercase) -> amplifier. */
    private static final Map<String, Map<String, Integer>> WORLDS = new HashMap<>();
    /** Players currently affected, so leaving a world only strips these effects. */
    private static final Map<UUID, Set<PotionEffectType>> APPLIED = new HashMap<>();

    /** Friendly and real effect names offered by tab completion. */
    static final List<String> EFFECT_NAMES = List.of(
            "jump", "speed", "strength", "swim", "breath", "fall",
            "jump_boost", "slow_falling", "dolphins_grace", "water_breathing",
            "night_vision", "haste", "saturation", "resistance", "regeneration",
            "fire_resistance", "levitation", "luck", "invisibility", "glowing");

    private PlanetEffects() {
    }

    /** Re-reads the effects section (and the legacy gravity-worlds list) from config. */
    static void loadConfig(FileConfiguration config) {
        WORLDS.clear();
        ConfigurationSection section = config.getConfigurationSection("effects");
        if (section != null) {
            for (String worldName : section.getKeys(false)) {
                ConfigurationSection effects = section.getConfigurationSection(worldName);
                if (effects == null) {
                    continue;
                }
                Map<String, Integer> amplifiers = new HashMap<>();
                for (String effect : effects.getKeys(false)) {
                    amplifiers.put(effect.toLowerCase(Locale.ROOT), effects.getInt(effect, 0));
                }
                WORLDS.put(worldName.toLowerCase(Locale.ROOT), amplifiers);
            }
        }
        // Legacy "gravity-worlds" list still counts as jump boost + slow falling.
        for (String worldName : config.getStringList("gravity-worlds")) {
            Map<String, Integer> amplifiers = WORLDS.computeIfAbsent(worldName.toLowerCase(Locale.ROOT), k -> new HashMap<>());
            amplifiers.putIfAbsent("jump", 2);
            amplifiers.putIfAbsent("fall", 1);
        }
    }

    /** Whether the world has any configured effects. */
    static boolean hasEffects(String worldName) {
        Map<String, Integer> effects = WORLDS.get(worldName.toLowerCase(Locale.ROOT));
        return effects != null && !effects.isEmpty();
    }

    /** Copy of the configured effects for a world (effect key -> amplifier). */
    static Map<String, Integer> effectsFor(String worldName) {
        Map<String, Integer> effects = WORLDS.get(worldName.toLowerCase(Locale.ROOT));
        return effects == null ? Map.of() : Map.copyOf(effects);
    }

    /** Resolves a friendly or real effect name to a potion effect type, or null. */
    static PotionEffectType typeFor(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return switch (k) {
            case "jump", "jump_boost", "jumpboost", "leap" -> PotionEffectType.JUMP_BOOST;
            case "speed", "swiftness" -> PotionEffectType.SPEED;
            case "strength" -> PotionEffectType.STRENGTH;
            case "swim", "swimming", "dolphin", "dolphins_grace" -> PotionEffectType.DOLPHINS_GRACE;
            case "breath", "breathing", "water_breathing" -> PotionEffectType.WATER_BREATHING;
            case "fall", "falling", "slow_falling", "slowfalling" -> PotionEffectType.SLOW_FALLING;
            default -> {
                PotionEffectType byRegistry = Registry.POTION_EFFECT_TYPE.match(k);
                yield byRegistry != null ? byRegistry : PotionEffectType.getByName(k);
            }
        };
    }

    /** Starts the periodic refresh task that keeps the effects applied. */
    static void start(JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld() != null && hasEffects(player.getWorld().getName())) {
                    apply(player);
                }
            }
        }, 0L, REFRESH_INTERVAL_TICKS);
    }

    /** Applies (or refreshes) all configured effects for the player's world. */
    static void apply(Player player) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Map<String, Integer> effects = WORLDS.get(world.getName().toLowerCase(Locale.ROOT));
        if (effects == null || effects.isEmpty()) {
            return;
        }
        Set<PotionEffectType> applied = APPLIED.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        for (Map.Entry<String, Integer> entry : effects.entrySet()) {
            PotionEffectType type = typeFor(entry.getKey());
            if (type == null) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(type, EFFECT_DURATION_TICKS, entry.getValue(), false, false, true));
            applied.add(type);
        }
    }

    /** Removes the planet effects from a player (e.g. on leaving the world). */
    static void remove(Player player) {
        Set<PotionEffectType> applied = APPLIED.remove(player.getUniqueId());
        if (applied == null) {
            return;
        }
        for (PotionEffectType type : applied) {
            player.removePotionEffect(type);
        }
    }
}