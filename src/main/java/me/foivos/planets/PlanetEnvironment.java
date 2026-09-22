package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Handles the continuous "environment" traits of planets:
 * <ul>
 *   <li><b>Locked weather</b> — worlds under "weather-lock" always show the
 *       chosen condition (clear/rain/thunder); it is re-applied every second so
 *       the normal weather cycle can't drift it away.</li>
 *   <li><b>Hostile atmospheres</b> — worlds under "atmospheres" damage players
 *       every second unless they wear a protective helmet (either any helmet,
 *       or only the listed materials).</li>
 *   <li><b>Disabled Nether/End dimensions</b> — worlds under
 *       "disabled-dimensions" get their portals cancelled and their linked
 *       {@code <world>_nether}/{@code <world>_the_end} worlds hidden from
 *       /planets, so those dimensions are only reachable as standalone planets
 *       through /planets.</li>
 * </ul>
 */
final class PlanetEnvironment implements Listener {

    private final Planets plugin;

    /** worldKey -> damage per second + the helmet materials that protect. */
    private final Map<String, Atmosphere> atmospheres = new HashMap<>();
    /** worldKey -> forced weather: "clear", "rain" or "thunder". */
    private final Map<String, String> weatherLock = new HashMap<>();
    /** worldKeys whose <world>_nether/<world>_the_end dimensions are disabled. */
    private final Set<String> disabledDimensions = new HashSet<>();
    /** worldKey -> visual atmosphere colors (the "sky" color drives a colored haze). */
    private final Map<String, SkyColors> skyColors = new HashMap<>();
    /** worldKey -> named ambient particle effect (embers, snow, colored rain...). */
    private final Map<String, AmbientParticles> ambientParticles = new HashMap<>();

    /**
     * A planet's visual atmosphere. The sky color drives the floating colored
     * haze particles; sky/fog are applied as real dimension colors when
     * ProtocolLib is present; water is stored for the status overview.
     */
    record SkyColors(Color sky, Color fog, Color water, int density) {
    }

    /** A named ambient particle effect and its density (particles per second). */
    record AmbientParticles(String effectName, int density) {
    }

    /** Effect names accepted by "/planets world <planet> particles <effect>". */
    static final List<String> PARTICLE_EFFECTS = List.of(
            "embers", "sparks", "snow", "rain", "ash", "bubbles",
            "fireflies", "magic", "smoke", "glow", "sculk", "lava");

    private record Atmosphere(double damagePerSecond, Set<Material> helmets) {
        /** Whether the given helmet protects against this atmosphere (null = no helmet). */
        boolean protects(ItemStack helmet) {
            if (helmet == null) {
                return false;
            }
            return helmets.isEmpty() || helmets.contains(helmet.getType());
        }
    }

    PlanetEnvironment(Planets plugin) {
        this.plugin = plugin;
    }

    /** Re-reads atmospheres, weather locks and disabled dimensions from config. */
    void loadConfig(FileConfiguration config) {
        atmospheres.clear();
        ConfigurationSection section = config.getConfigurationSection("atmospheres");
        if (section != null) {
            for (String worldName : section.getKeys(false)) {
                ConfigurationSection data = section.getConfigurationSection(worldName);
                if (data == null) {
                    continue;
                }
                double damage = data.getDouble("damage", 0);
                if (damage <= 0) {
                    continue;
                }
                Set<Material> helmets = new HashSet<>();
                for (String material : data.getStringList("helmets")) {
                    Material match = Material.matchMaterial(material);
                    if (match != null) {
                        helmets.add(match);
                    }
                }
                atmospheres.put(worldName.toLowerCase(Locale.ROOT), new Atmosphere(damage, helmets));
            }
        }

        weatherLock.clear();
        ConfigurationSection weather = config.getConfigurationSection("weather-lock");
        if (weather != null) {
            for (String worldName : weather.getKeys(false)) {
                String value = weather.getString(worldName, "").toLowerCase(Locale.ROOT);
                if (value.equals("clear") || value.equals("rain") || value.equals("thunder")) {
                    weatherLock.put(worldName.toLowerCase(Locale.ROOT), value);
                }
            }
        }

        disabledDimensions.clear();
        for (String worldName : config.getStringList("disabled-dimensions")) {
            disabledDimensions.add(worldName.toLowerCase(Locale.ROOT));
        }

        ambientParticles.clear();
        ConfigurationSection particleSection = config.getConfigurationSection("particles");
        if (particleSection != null) {
            for (String worldName : particleSection.getKeys(false)) {
                ConfigurationSection data = particleSection.getConfigurationSection(worldName);
                if (data == null) {
                    continue;
                }
                String effect = data.getString("effect", "").toLowerCase(Locale.ROOT);
                if (!PARTICLE_EFFECTS.contains(effect)) {
                    plugin.getLogger().warning("Ignoring unknown particle effect '" + effect
                            + "' for world '" + worldName + "'.");
                    continue;
                }
                int density = Math.max(0, Math.min(120, data.getInt("density", 8)));
                ambientParticles.put(worldName.toLowerCase(Locale.ROOT), new AmbientParticles(effect, density));
            }
        }

        skyColors.clear();
        ConfigurationSection colors = config.getConfigurationSection("sky-colors");
        if (colors != null) {
            for (String worldName : colors.getKeys(false)) {
                ConfigurationSection data = colors.getConfigurationSection(worldName);
                if (data == null) {
                    continue;
                }
                Color sky = parseColor(data.getString("sky", ""));
                if (sky == null) {
                    plugin.getLogger().warning("Ignoring invalid sky color for world '" + worldName
                            + "' in sky-colors (expected hex like #FF8800 or a name like orange).");
                    continue;
                }
                Color fog = parseColor(data.getString("fog", ""));
                Color water = parseColor(data.getString("water", ""));
                int density = Math.max(0, Math.min(60, data.getInt("density", 8)));
                skyColors.put(worldName.toLowerCase(Locale.ROOT), new SkyColors(sky, fog, water, density));
            }
        }
    }

    /**
     * Parses a color from hex (#RRGGBB, RRGGBB or 0xRRGGBB) or a Bukkit color
     * name (white, red, orange, sky, purple, ...). Returns null if invalid.
     */
    static Color parseColor(String arg) {
        String a = arg == null ? "" : arg.trim();
        if (a.startsWith("#")) {
            a = a.substring(1);
        } else if (a.startsWith("0x") || a.startsWith("0X")) {
            a = a.substring(2);
        }
        if (a.length() == 6) {
            try {
                return Color.fromRGB(Integer.parseInt(a, 16));
            } catch (NumberFormatException ignored) {
                // fall through to named colors
            }
        }
        for (Field field : Color.class.getFields()) {
            if (field.getType() == Color.class && field.getName().equalsIgnoreCase(a)) {
                try {
                    return (Color) field.get(null);
                } catch (IllegalAccessException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Formats a color as a readable #RRGGBB string for the config and status. */
    static String hex(Color color) {
        return String.format("#%06X", color.asRGB());
    }

    /** Whether a world's Nether/End dimensions are disabled. */
    boolean dimensionsDisabled(String worldName) {
        return disabledDimensions.contains(worldName.toLowerCase(Locale.ROOT));
    }

    /** Whether a world folder is the linked Nether/End of a dimension-disabled world. */
    boolean isDisabledDimensionWorld(String worldName) {
        String lower = worldName.toLowerCase(Locale.ROOT);
        for (String base : disabledDimensions) {
            if (lower.equals(base + "_nether") || lower.equals(base + "_the_end")) {
                return true;
            }
        }
        return false;
    }

    /** The locked weather for a world, or null if it follows the normal cycle. */
    String lockedWeather(String worldName) {
        return weatherLock.get(worldName.toLowerCase(Locale.ROOT));
    }

    /** The atmosphere damage per second for a world, or null if there is none. */
    Double atmosphereDamage(String worldName) {
        Atmosphere atmosphere = atmospheres.get(worldName.toLowerCase(Locale.ROOT));
        return atmosphere == null ? null : atmosphere.damagePerSecond();
    }

    /** The colors configured for a world, or null when it has no sky color. */
    SkyColors colorsFor(String worldName) {
        return skyColors.get(worldName.toLowerCase(Locale.ROOT));
    }

    /** The ambient particle effect configured for a world, or null. */
    String ambientParticlesDescription(String worldName) {
        AmbientParticles particles = ambientParticles.get(worldName.toLowerCase(Locale.ROOT));
        return particles == null ? null : particles.effectName() + " (" + particles.density() + "/s)";
    }

    /**
     * Human-readable summary of a world's sky/fog/water colors, or null when
     * the world has no sky color set. Used by the status overview.
     */
    String skyColorDescription(String worldName) {
        SkyColors colors = skyColors.get(worldName.toLowerCase(Locale.ROOT));
        if (colors == null) {
            return null;
        }
        StringBuilder description = new StringBuilder("sky " + hex(colors.sky()));
        if (colors.fog() != null) {
            description.append(", fog ").append(hex(colors.fog()));
        }
        if (colors.water() != null) {
            description.append(", water ").append(hex(colors.water()));
        }
        return description.toString();
    }

    /** Starts weather enforcement, atmosphere damage and ambient particles. */
    void start(JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 20L);
        // Ambient particle effects run faster than the rest so they look smooth.
        Bukkit.getScheduler().runTaskTimer(plugin, this::spawnAmbientParticles, 0L, 5L);
    }

    private void tick() {
        enforceWeather();
        applyAtmosphereDamage();
        spawnSkyParticles();
    }

    /**
     * Floating colored haze for planets with a sky color: low-density DUST
     * particles drift around each player so every planet looks different. This
     * is the visual atmosphere that works on every Paper version with no extra
     * plugins (true per-world sky color needs packet-level access, see the
     * plugin docs).
     */
    private void spawnSkyParticles() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            if (world == null) {
                continue;
            }
            if (!PlayerSettings.on(plugin, player.getUniqueId(), PlayerSettings.Setting.PLANET_ATMOSPHERE)) {
                continue; // this player hid the planet haze
            }
            SkyColors colors = skyColors.get(world.getName().toLowerCase(Locale.ROOT));
            if (colors == null || colors.density() <= 0) {
                continue;
            }
            Particle.DustOptions dust = new Particle.DustOptions(colors.sky(), 0.9f);
            Location loc = player.getLocation();
            world.spawnParticle(Particle.DUST,
                    loc.getX(), loc.getY() + 1.2, loc.getZ(),
                    colors.density(), 4.0, 2.5, 4.0, 0.01, dust);
        }
    }

    /**
     * Spawns each planet's configured ambient particle effect around every
     * player in the world (embers, snow, colored rain, ...). Runs four times
     * per second so the effect looks continuous; the density is the number of
     * particles per second, split across the bursts.
     */
    private void spawnAmbientParticles() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            if (world == null) {
                continue;
            }
            if (!PlayerSettings.on(plugin, player.getUniqueId(), PlayerSettings.Setting.PLANET_ATMOSPHERE)) {
                continue; // this player hid the planet's ambient particles
            }
            AmbientParticles particles = ambientParticles.get(world.getName().toLowerCase(Locale.ROOT));
            if (particles == null || particles.density() <= 0) {
                continue;
            }
            int perRun = Math.max(1, Math.round(particles.density() / 4.0f));
            Location loc = player.getLocation();
            switch (particles.effectName()) {
                case "embers" -> world.spawnParticle(Particle.SMALL_FLAME,
                        loc.getX(), loc.getY() + 0.5, loc.getZ(), perRun, 0.6, 0.4, 0.6, 0.02);
                case "sparks" -> world.spawnParticle(Particle.ELECTRIC_SPARK,
                        loc.getX(), loc.getY() + 1.0, loc.getZ(), perRun, 0.8, 0.6, 0.8, 0.05);
                case "snow" -> world.spawnParticle(Particle.SNOWFLAKE,
                        loc.getX(), loc.getY() + 3.0, loc.getZ(), perRun, 1.2, 0.8, 1.2, 0.3);
                case "ash" -> world.spawnParticle(Particle.ASH,
                        loc.getX(), loc.getY() + 2.5, loc.getZ(), perRun, 1.0, 0.8, 1.0, 0.1);
                case "bubbles" -> world.spawnParticle(Particle.BUBBLE_POP,
                        loc.getX(), loc.getY() + 0.5, loc.getZ(), perRun, 0.8, 0.4, 0.8, 0.2);
                case "fireflies" -> world.spawnParticle(Particle.END_ROD,
                        loc.getX(), loc.getY() + 1.0, loc.getZ(), perRun, 0.9, 0.7, 0.9, 0.0);
                case "magic" -> world.spawnParticle(Particle.ENCHANT,
                        loc.getX(), loc.getY() + 0.8, loc.getZ(), perRun, 0.9, 0.7, 0.9, 0.02);
                case "smoke" -> world.spawnParticle(Particle.CLOUD,
                        loc.getX(), loc.getY() + 0.6, loc.getZ(), perRun, 0.8, 0.5, 0.8, 0.01);
                case "glow" -> world.spawnParticle(Particle.GLOW,
                        loc.getX(), loc.getY() + 1.0, loc.getZ(), perRun, 1.0, 0.8, 1.0, 0.0);
                case "sculk" -> world.spawnParticle(Particle.SCULK_SOUL,
                        loc.getX(), loc.getY() + 0.5, loc.getZ(), perRun, 0.6, 0.4, 0.6, 0.02);
                case "lava" -> world.spawnParticle(Particle.DRIPPING_LAVA,
                        loc.getX(), loc.getY() + 2.0, loc.getZ(), perRun, 1.0, 0.5, 1.0, 0.0);
                case "rain" -> {
                    // Colored rain, tinted with the planet's sky color when set.
                    SkyColors colors = skyColors.get(world.getName().toLowerCase(Locale.ROOT));
                    Color rainColor = colors == null ? Color.AQUA : colors.sky();
                    Particle.DustOptions dust = new Particle.DustOptions(rainColor, 0.8f);
                    world.spawnParticle(Particle.DUST,
                            loc.getX(), loc.getY() + 4.0, loc.getZ(), perRun, 0.9, 0.15, 0.9, 0.7, dust);
                }
                default -> {
                }
            }
        }
    }

    /** Keeps the configured weather on each locked world. */
    private void enforceWeather() {
        for (Map.Entry<String, String> entry : weatherLock.entrySet()) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null) {
                continue;
            }
            switch (entry.getValue()) {
                case "rain" -> {
                    world.setStorm(true);
                    world.setThundering(false);
                    world.setWeatherDuration(12000);
                }
                case "thunder" -> {
                    world.setStorm(true);
                    world.setThundering(true);
                    world.setWeatherDuration(12000);
                    world.setThunderDuration(12000);
                }
                default -> {
                    world.setStorm(false);
                    world.setThundering(false);
                }
            }
        }
    }

    /** Damages unprotected players in atmosphere worlds once per second. */
    private void applyAtmosphereDamage() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            if (world == null) {
                continue;
            }
            Atmosphere atmosphere = atmospheres.get(world.getName().toLowerCase(Locale.ROOT));
            if (atmosphere == null) {
                continue;
            }
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                    || player.hasPermission("planets.atmosphere.bypass")) {
                continue;
            }
            if (atmosphere.protects(player.getInventory().getHelmet())) {
                continue;
            }
            // Plain damage() respects armor and invulnerability ticks; the source
            // doesn't matter to the players, the action bar tells them why.
            player.damage(atmosphere.damagePerSecond());
            // Sent as a priority bar so the optional Planet HUD steps aside for it.
            plugin.sendPriorityBar(player, Component.text(
                            "The atmosphere here is hostile — wear a protective helmet!")
                    .color(NamedTextColor.RED));
        }
    }

    /** Warns players who enter an atmosphere world without protection. */
    @EventHandler(ignoreCancelled = true)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        warnAboutAtmosphere(event.getPlayer());
    }

    /** Same warning for players who log in inside an atmosphere world. */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        warnAboutAtmosphere(event.getPlayer());
    }

    private void warnAboutAtmosphere(Player player) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Atmosphere atmosphere = atmospheres.get(world.getName().toLowerCase(Locale.ROOT));
        if (atmosphere == null || player.hasPermission("planets.atmosphere.bypass")) {
            return;
        }
        if (atmosphere.protects(player.getInventory().getHelmet())) {
            return;
        }
        player.sendMessage(Component.text("You're entering a hostile atmosphere — ").color(NamedTextColor.RED)
                .append(Component.text("wear a protective helmet or you'll take "
                        + atmosphere.damagePerSecond() + " damage per second!").color(NamedTextColor.YELLOW)));
    }

    /**
     * Blocks Nether/End portals. With the global "disable-portals" config
     * toggle on, EVERY portal is cancelled everywhere, so the only way to reach
     * the Nether/End planets is /planets. With it off, portals are still
     * cancelled for the specific worlds under "disabled-dimensions" (both
     * entering the linked dimension from the planet and returning from it).
     */
    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (plugin.getConfig().getBoolean("disable-portals", false)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Portals are disabled — ").color(NamedTextColor.RED)
                    .append(Component.text("use /planets (or /p <planet>) to travel between planets.").color(NamedTextColor.YELLOW)));
            return;
        }
        Location from = event.getFrom();
        World fromWorld = from.getWorld();
        if (fromWorld == null) {
            return;
        }
        String base = baseName(fromWorld.getName());
        if (base == null || !disabledDimensions.contains(base)) {
            return;
        }
        event.setCancelled(true);
        String dimension = event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                ? "the Nether" : "the End";
        event.getPlayer().sendMessage(Component.text("The " + dimension + " of this planet is disabled — ")
                .color(NamedTextColor.RED)
                .append(Component.text("use /planets to travel to other planets.").color(NamedTextColor.YELLOW)));
    }

    /**
     * With the global portal toggle on, mobs can't use portals either — this
     * stops farm/lag exploits and keeps the linked dimensions truly unused.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (plugin.getConfig().getBoolean("disable-portals", false)) {
            event.setCancelled(true);
        }
    }

    /** Strips _nether/_the_end suffixes to find the owning planet world. */
    private static String baseName(String worldName) {
        String lower = worldName.toLowerCase(Locale.ROOT);
        if (lower.endsWith("_nether")) {
            return lower.substring(0, lower.length() - "_nether".length());
        }
        if (lower.endsWith("_the_end")) {
            return lower.substring(0, lower.length() - "_the_end".length());
        }
        return lower;
    }
}