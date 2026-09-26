package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Teleports players to a planet. Used both when clicking a planet in the menu
 * and when using {@code /planets <name>} directly.
 * <p>
 * Every teleport waits {@code teleport.delay-seconds} (configurable, 1.5 by
 * default) before actually moving the player; requesting another teleport in
 * the meantime replaces the pending one, and moving, taking damage or entering
 * combat during the wait cancels it. While the countdown runs, portal particles
 * and a charge-up sound play above the player. Players tagged as in combat
 * ({@code teleport.combat-tag-seconds}, 10s by default) cannot request a
 * teleport at all.
 * <p>
 * How players land on a world is decided by its landing mode (set with
 * {@code /planets landing <planet> station|random|point} or in config.yml):
 * <ul>
 *   <li>{@code random} (default) — a fresh random safe spot inside the world
 *       border. Candidates need a few solid blocks of support underneath, so
 *       thin crusts over caves are rejected, and they are never on top of or
 *       below the bedrock layers.</li>
 *   <li>{@code station} — the world spawn (e.g. a space station build).</li>
 *   <li>{@code point} — a fixed spot pinned in-game with
 *       {@code /planets setlanding <planet>}.</li>
 * </ul>
 * Before the player arrives, a small radius of chunks around the landing spot
 * is force-loaded ({@code teleport.preload-radius}, 2 by default) so nobody
 * falls through ungenerated terrain.
 */
public final class PlanetTravel {

    /** Defaults used when the config keys are missing. */
    private static final double DEFAULT_MAX_SPREAD = 3000;
    private static final double DEFAULT_MIN_SPOT_DISTANCE = 40;
    private static final double DEFAULT_DELAY_SECONDS = 1.5;
    private static final double DEFAULT_PRELOAD_RADIUS = 2;
    private static final double DEFAULT_COMBAT_TAG_SECONDS = 10;
    private static final double DEFAULT_COOLDOWN_SECONDS = 0;
    private static final double DEFAULT_END_EXCLUDE_RADIUS = 350;
    private static final int ATTEMPTS_PER_TIER = 20;
    /** Ring attempts for the End outer-island search (fewer: each ring is targeted). */
    private static final int END_OUTER_ATTEMPTS_PER_TIER = 12;

    /** Solid opaque blocks of support required underneath the surface. */
    private static final int MIN_GROUND_DEPTH = 2;

    /** Landing modes. */
    private static final String MODE_RANDOM = "random";
    private static final String MODE_STATION = "station";
    private static final String MODE_POINT = "point";

    /** How often the charge-up visuals refresh while waiting, in ticks (5 = 4x/sec). */
    private static final long EFFECT_INTERVAL_TICKS = 5L;

    /** Plugin instance used to schedule the delayed teleports; set in {@link #init}. */
    private static JavaPlugin plugin;

    /** How far (in blocks) from the world border center landings may be rolled. */
    private static double maxSpread = DEFAULT_MAX_SPREAD;
    /** Minimum distance (in blocks) from the player's previous landing spot. */
    private static double minSpotDistance = DEFAULT_MIN_SPOT_DISTANCE;
    /** Seconds to wait between requesting a teleport and actually moving the player. */
    private static double delaySeconds = DEFAULT_DELAY_SECONDS;
    /** Chunks around the landing spot to force-load before the player arrives. */
    private static double preloadRadius = DEFAULT_PRELOAD_RADIUS;
    /** Seconds after taking/dealing damage that a player cannot request a teleport. */
    private static double combatTagSeconds = DEFAULT_COMBAT_TAG_SECONDS;
    /** Minimum seconds between two planet teleports of the same player (0 = off). */
    private static double cooldownSeconds = DEFAULT_COOLDOWN_SECONDS;
    /** Radius around the End world origin that landings avoid (the dragon-island zone). */
    private static double endExcludeRadius = DEFAULT_END_EXCLUDE_RADIUS;

    /** Landing mode per world (lowercase world name → station|random|point). */
    private static final Map<String, String> LANDING_MODES = new HashMap<>();
    /** Fixed landing spots for "point" mode, keyed by lowercase world name. */
    private static final Map<String, Location> LANDING_SPOTS = new HashMap<>();
    /** Legacy worlds whose players always land at the world spawn (station mode). */
    private static final Set<String> STATION_WORLDS = new HashSet<>();

    /** Last planet landing spot per player, so each teleport lands somewhere fresh. */
    private static final Map<UUID, Location> LAST_LANDINGS = new HashMap<>();

    /** Time (epoch millis) each player last completed a planet teleport, for the cooldown. */
    private static final Map<UUID, Long> LAST_TELEPORTS = new HashMap<>();

    /** Pending delayed teleports per player, so a new request replaces an older one. */
    private static final Map<UUID, BukkitTask> PENDING_TELEPORTS = new HashMap<>();

    /** Target world name for each player's pending teleport, used to cancel teleports when a world is deleted. */
    private static final Map<UUID, String> PENDING_WORLDS = new HashMap<>();

    /** Charge-up visual tasks per player, stopped when the teleport fires or is canceled. */
    private static final Map<UUID, BukkitTask> CHARGE_EFFECTS = new HashMap<>();

    /** Time (epoch millis) each player last took or dealt damage, for the combat tag. */
    private static final Map<UUID, Long> COMBAT_TAGS = new HashMap<>();

    private PlanetTravel() {
    }

    /** Called from {@code onEnable}; needed to schedule the teleport delay. */
    public static void init(JavaPlugin instance) {
        plugin = instance;
    }

    /**
     * Reads the teleport tuning from the plugin config, falling back to the
     * defaults. Also reloads the landing modes and fixed landing spots.
     */
    public static void loadConfig(FileConfiguration config) {
        maxSpread = Math.max(1, config.getDouble("teleport.max-spread", DEFAULT_MAX_SPREAD));
        minSpotDistance = Math.max(0, config.getDouble("teleport.min-spot-distance", DEFAULT_MIN_SPOT_DISTANCE));
        delaySeconds = Math.max(0, config.getDouble("teleport.delay-seconds", DEFAULT_DELAY_SECONDS));
        preloadRadius = Math.max(0, config.getDouble("teleport.preload-radius", DEFAULT_PRELOAD_RADIUS));
        combatTagSeconds = Math.max(0, config.getDouble("teleport.combat-tag-seconds", DEFAULT_COMBAT_TAG_SECONDS));
        cooldownSeconds = Math.max(0, config.getDouble("teleport.cooldown-seconds", DEFAULT_COOLDOWN_SECONDS));
        endExcludeRadius = Math.max(0,
                config.getDouble("teleport.end-landing-exclude-radius", DEFAULT_END_EXCLUDE_RADIUS));

        LANDING_MODES.clear();
        ConfigurationSection modes = config.getConfigurationSection("landing-modes");
        if (modes != null) {
            for (String worldName : modes.getKeys(false)) {
                String mode = modes.getString(worldName, MODE_RANDOM).toLowerCase(Locale.ROOT);
                if (mode.equals(MODE_RANDOM) || mode.equals(MODE_STATION) || mode.equals(MODE_POINT)) {
                    LANDING_MODES.put(worldName.toLowerCase(Locale.ROOT), mode);
                }
            }
        }

        LANDING_SPOTS.clear();
        ConfigurationSection spots = config.getConfigurationSection("landing-spots");
        if (spots != null) {
            for (String worldName : spots.getKeys(false)) {
                ConfigurationSection spot = spots.getConfigurationSection(worldName);
                if (spot == null || !spot.contains("x") || !spot.contains("y") || !spot.contains("z")) {
                    continue;
                }
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    continue; // world not loaded; the spot is re-read on reload
                }
                LANDING_SPOTS.put(worldName.toLowerCase(Locale.ROOT),
                        new Location(world, spot.getDouble("x"), spot.getDouble("y"), spot.getDouble("z"),
                                (float) spot.getDouble("yaw", 0), (float) spot.getDouble("pitch", 0)));
            }
        }

        STATION_WORLDS.clear();
        ConfigurationSection station = config.getConfigurationSection("station-worlds");
        if (station != null) {
            for (String worldName : station.getKeys(false)) {
                if (station.getBoolean(worldName, false)) {
                    STATION_WORLDS.add(worldName.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    /** Tags the player as recently in combat (unless combat tagging is disabled). */
    public static void tagCombat(Player player) {
        if (combatTagSeconds > 0) {
            COMBAT_TAGS.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    /** Whether the player is still inside the combat-tag window. */
    public static boolean isInCombat(Player player) {
        if (combatTagSeconds <= 0) {
            return false;
        }
        Long since = COMBAT_TAGS.get(player.getUniqueId());
        if (since == null) {
            return false;
        }
        if (System.currentTimeMillis() - since >= combatTagSeconds * 1000) {
            COMBAT_TAGS.remove(player.getUniqueId()); // lazy expiry
            return false;
        }
        return true;
    }

    /**
     * Requests a teleport to a planet. The player is told to wait and actually
     * moved {@code delay-seconds} later, so any pending teleport is replaced.
     * A charge-up effect plays above the player while the countdown runs.
     * Players still in combat are refused outright.
     */
    public static void teleport(Player player, Planet planet) {
        if (isInCombat(player)) {
            player.sendMessage(Component.text("You can't teleport to a planet while in combat.").color(NamedTextColor.RED));
            return;
        }
        // Per-planet access toggles (/myp → Settings): Visitor Access or
        // Public make the planet members-only. Members always get in.
        if (plugin instanceof Planets planetsPlugin) {
            MyPlanetData data = planetsPlugin.getMyPlanetManager().get(planet.worldName());
            if (data != null && !data.isMember(player.getUniqueId())) {
                if (!data.visitorAccess() || !data.isPublic()) {
                    player.sendMessage(Component.text("This planet is private — only members can enter.")
                            .color(NamedTextColor.RED));
                    return;
                }
            }
        }
        if (cooldownSeconds > 0) {
            Long last = LAST_TELEPORTS.get(player.getUniqueId());
            if (last != null) {
                double remaining = cooldownSeconds - (System.currentTimeMillis() - last) / 1000.0;
                if (remaining > 0) {
                    player.sendMessage(Component.text("You must wait ").color(NamedTextColor.RED)
                            .append(Component.text(String.format(Locale.ROOT, "%.1f", remaining)).color(NamedTextColor.YELLOW))
                            .append(Component.text(" seconds before teleporting again.").color(NamedTextColor.RED)));
                    return;
                }
            }
        }
        if (plugin == null) {
            doTeleport(player, planet); // not initialized; teleport instantly rather than drop the request
            return;
        }

        clearPending(player.getUniqueId());
        player.closeInventory();

        long ticks = Math.round(delaySeconds * 20.0);
        if (ticks <= 0) {
            doTeleport(player, planet);
            return;
        }

        if (PlayerSettings.on(plugin, player.getUniqueId(), PlayerSettings.Setting.TRAVEL_MESSAGES)) {
            player.sendMessage(
                    Component.text("Teleporting to ").color(NamedTextColor.GRAY)
                            .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                            .append(Component.text(" in " + secondsText(ticks) + " seconds...").color(NamedTextColor.GRAY))
            );
        }

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PENDING_TELEPORTS.remove(player.getUniqueId());
            PENDING_WORLDS.remove(player.getUniqueId());
            stopChargeEffect(player.getUniqueId());
            if (!player.isOnline()) {
                return; // logged out while waiting
            }
            doTeleport(player, planet);
        }, ticks);
        PENDING_TELEPORTS.put(player.getUniqueId(), task);
        PENDING_WORLDS.put(player.getUniqueId(), planet.worldName());

        // Charge-up visuals above the player while the countdown runs.
        BukkitTask effect = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                stopChargeEffect(player.getUniqueId());
                return;
            }
            playChargeEffect(player);
        }, 0L, EFFECT_INTERVAL_TICKS);
        CHARGE_EFFECTS.put(player.getUniqueId(), effect);
    }

    /** Whether the player has a delayed teleport waiting to fire. */
    public static boolean hasPendingTeleport(Player player) {
        return PENDING_TELEPORTS.containsKey(player.getUniqueId());
    }

    /**
     * Cancels the player's pending teleport (e.g. they moved or took damage
     * during the countdown). Returns whether there was one to cancel.
     */
    public static boolean cancelPendingTeleport(Player player) {
        if (!PENDING_TELEPORTS.containsKey(player.getUniqueId())) {
            return false;
        }
        clearPending(player.getUniqueId());
        return true;
    }

    /**
     * Cancels every pending teleport that targets the given world name.
     * Called when a world is deleted so players aren't teleported into the void.
     */
    public static void cancelPendingTeleportsForWorld(String worldName) {
        String lower = worldName.toLowerCase(Locale.ROOT);
        PENDING_WORLDS.entrySet().removeIf(entry -> {
            if (entry.getValue().equalsIgnoreCase(worldName)) {
                clearPending(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /** Cancels the pending teleport and its charge-up effect, if any. */
    private static void clearPending(UUID playerId) {
        BukkitTask task = PENDING_TELEPORTS.remove(playerId);
        if (task != null) {
            task.cancel();
        }
        PENDING_WORLDS.remove(playerId);
        stopChargeEffect(playerId);
    }

    /** Stops the charge-up visuals for a player, if running. */
    private static void stopChargeEffect(UUID playerId) {
        BukkitTask effect = CHARGE_EFFECTS.remove(playerId);
        if (effect != null) {
            effect.cancel();
        }
    }

    /** Portal particles above the player plus a soft ambient hum. */
    private static void playChargeEffect(Player player) {
        Location location = player.getLocation();
        player.getWorld().spawnParticle(Particle.PORTAL, location.clone().add(0, 2.2, 0), 30, 0.5, 0.4, 0.5, 0.05);
        player.playSound(location, Sound.BLOCK_PORTAL_AMBIENT, 0.5f, 1.4f);
    }

    /** Performs the actual teleport. */
    private static void doTeleport(Player player, Planet planet) {
        World world = Bukkit.getWorld(planet.worldName());
        if (world == null) {
            player.sendMessage(
                    Component.text("The world of ").color(NamedTextColor.RED)
                            .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                            .append(Component.text(" isn't loaded right now.").color(NamedTextColor.RED))
            );
            return;
        }

        Location location = null;
        Location previous = LAST_LANDINGS.get(player.getUniqueId());
        String mode = landingMode(planet.worldName());
        if (MODE_POINT.equals(mode)) {
            // Fixed landing point set with /planets setlanding; fall back to the spawn if missing.
            location = LANDING_SPOTS.get(planet.worldName().toLowerCase(Locale.ROOT));
            if (location == null || location.getWorld() == null) {
                location = world.getSpawnLocation();
            }
        } else if (MODE_STATION.equals(mode)) {
            // Station worlds (space stations, builds at spawn) always land at the spawn.
            location = world.getSpawnLocation();
        }
        if (location == null) {
            location = findSafeLocation(world, previous);
            if (location == null) {
                location = world.getSpawnLocation(); // fallback if no safe spot was found
            }
            LAST_LANDINGS.put(player.getUniqueId(), location);
        }

        // The End's main island — home of the Ender Dragon — is never a landing
        // spot. Any location resolved on or near it (the spawn obsidian platform,
        // a pinned point, a rolled or fallback spot) is replaced by a safe spot
        // on an outer island, searched outward from the island. If no such spot
        // exists (tiny world border, custom void world...), the teleport is
        // cancelled rather than dropping the player on the island.
        if (world.getEnvironment() == World.Environment.THE_END && endExcludeRadius > 0
                && onEndMainIsland(location)) {
            Location outer = findEndSafeLocation(world, previous);
            if (outer == null) {
                player.sendMessage(Component.text("No safe landing spot could be found outside ")
                        .color(NamedTextColor.RED)
                        .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                        .append(Component.text("'s dragon island — try again.").color(NamedTextColor.RED)));
                return;
            }
            location = outer;
            LAST_LANDINGS.put(player.getUniqueId(), location);
        }

        // Make sure the terrain around the landing spot exists before the player
        // arrives, so they can't fall through ungenerated chunks.
        preloadChunks(location);

        player.teleport(location);
        playArrivalEffect(player);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        if (PlayerSettings.on(plugin, player.getUniqueId(), PlayerSettings.Setting.TRAVEL_MESSAGES)) {
            player.sendMessage(
                    Component.text("Teleported to ").color(NamedTextColor.GREEN)
                            .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                            .append(Component.text(".").color(NamedTextColor.GREEN))
            );
        }
        LAST_TELEPORTS.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /** A portal burst around the player to mark the arrival at the destination. */
    private static void playArrivalEffect(Player player) {
        Location location = player.getLocation();
        player.getWorld().spawnParticle(Particle.PORTAL, location.clone().add(0, 1, 0), 150, 1.2, 1.2, 1.2, 0.4);
    }

    private static String secondsText(long ticks) {
        double seconds = ticks / 20.0;
        if (seconds == Math.rint(seconds)) {
            return String.valueOf((long) seconds);
        }
        return String.format(Locale.ROOT, "%.1f", seconds);
    }

    /** Space-station rescue behaviour: which damage causes are blocked. */
    private static final String MODE_RESCUE_VOID_ONLY = "void-only";
    private static final String MODE_RESCUE_ALL_LETHAL = "all-lethal";
    private static final String MODE_RESCUE_VOID_FALL_PVP = "void-fall-pvp";

    /**
     * How players land on this world: point, station, or random (default).
     */
    private static String landingMode(String worldName) {
        String key = worldName.toLowerCase(Locale.ROOT);
        String mode = LANDING_MODES.get(key);
        if (mode != null) {
            return mode;
        }
        if (STATION_WORLDS.contains(key)) {
            return MODE_STATION; // legacy "station-worlds" config section
        }
        return MODE_RANDOM;
    }

    /** Whether a world is a "space station" (players always land at its spawn). */
    static boolean isStation(String worldName) {
        return MODE_STATION.equals(landingMode(worldName));
    }

    /**
     * Which damage causes a station blocks for the given world.
     * Reads the per-world {@code station-rescue-mode} node, falling back to
     * {@code station-rescue-mode.default} (and then to {@code all-lethal}) when
     * the world has no explicit setting.
     *
     * @return one of {@code void-only}, {@code void-fall-pvp} or {@code all-lethal}.
     */
    static String stationRescueMode(String worldName) {
        ConfigurationSection section = plugin == null
                ? null
                : plugin.getConfig().getConfigurationSection("station-rescue-mode");
        if (section != null) {
            String worldMode = section.getString(worldName.toLowerCase(Locale.ROOT));
            if (worldMode != null && !worldMode.isBlank()) {
                return worldMode.toLowerCase(Locale.ROOT);
            }
        }
        String def = plugin == null
                ? null
                : plugin.getConfig().getString("station-rescue-mode.default");
        return (def != null && !def.isBlank())
                ? def.toLowerCase(Locale.ROOT)
                : MODE_RESCUE_ALL_LETHAL;
    }

    /**
     * Whether a station in the given world blocks player-vs-player damage. When
     * true, a player dealt lethal damage by another player is pulled back to the
     * landing spot instead of dying — PvP is effectively off on that world.
     */
    static boolean stationRespectsPvp(String worldName) {
        String mode = stationRescueMode(worldName);
        return MODE_RESCUE_VOID_FALL_PVP.equals(mode);
    }

    /**
     * Whether the station would block the given damage event under the world's
     * current rescue mode.
     * <ul>
     *   <li>Void/fall is always rescued on any station.</li>
     *   <li>In {@code all-lethal} mode, any otherwise-fatal hit is rescued.</li>
     *   <li>In {@code void-fall-pvp} mode, only void/fall and player-vs-player
     *       lethal damage are rescued — mobs and environmental damage still kill.</li>
     * </ul>
     */
    static boolean shouldRescueDamage(EntityDamageEvent event, String worldName) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean fallingOut = cause == EntityDamageEvent.DamageCause.VOID;
        if (fallingOut) {
            return true;
        }
        String mode = stationRescueMode(worldName);
        if (MODE_RESCUE_ALL_LETHAL.equals(mode)) {
            return true;
        }
        if (MODE_RESCUE_VOID_FALL_PVP.equals(mode)) {
            // PvP rescue: only rescue when the damager is another player.
            // EntityDamageEvent has no getDamager(); relay to the caller with
            // the player that dealt the hit.
            return event instanceof EntityDamageByEntityEvent EBE
                    && EBE.getDamager() instanceof Player
                    ? true
                    : false;
        }
        return false;
    }

    /**
     * The pinned landing spot of a world ({@code /planets setlanding}), or null
     * when it has none. The loaded cache is used first and config.yml itself is
     * the fallback, so a world that was loaded (or had its spot set) after the
     * config was read is still found.
     */
    static Location landingSpot(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        Location cached = LANDING_SPOTS.get(worldName.toLowerCase(Locale.ROOT));
        if (cached != null && cached.getWorld() != null) {
            return cached.clone();
        }
        if (plugin == null) {
            return null;
        }
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("landing-spots." + worldName);
        if (section == null || !section.contains("x") || !section.contains("y") || !section.contains("z")) {
            return null;
        }
        return new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw", 0.0), (float) section.getDouble("pitch", 0.0));
    }

    /**
     * Where a station puts a player back when it saves them: its pinned landing
     * spot when one is set, otherwise the world spawn. Null when the world
     * isn't loaded.
     */
    static Location stationLandingSpot(String worldName) {
        Location spot = landingSpot(worldName);
        if (spot != null) {
            return spot;
        }
        World world = Bukkit.getWorld(worldName);
        return world == null ? null : world.getSpawnLocation();
    }

    /** Force-loads a square of chunks around the location (destructive: generates terrain). */
    private static void preloadChunks(Location location) {
        if (preloadRadius <= 0 || location.getWorld() == null) {
            return;
        }
        int radius = (int) Math.ceil(preloadRadius);
        int centerX = location.getBlockX() >> 4;
        int centerZ = location.getBlockZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                location.getWorld().getChunkAt(centerX + dx, centerZ + dz);
            }
        }
    }

    /**
     * Finds a random spot on solid ground inside the world border, or {@code null}
     * if no safe spot was found. Candidates are only accepted when the surface
     * block is an opaque block (stone, grass, sand, ...) resting on a few more
     * opaque blocks, which keeps players off the nether roof, floating crusts,
     * thin crusts over caves and the void, and never on top of or below the
     * bedrock layers. The spot is also kept at least {@code min-spot-distance}
     * (configurable) away from the {@code previous} landing so repeat teleports
     * always land somewhere new.
     */
    private static Location findSafeLocation(World world, Location previous) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        WorldBorder border = world.getWorldBorder();
        double centerX = border.getCenter().getX();
        double centerZ = border.getCenter().getZ();
        double maxBorderSpread = border.getSize() / 2.0;

        // Widest configured spread first, then progressively tighter rings to keep
        // the hit rate up on sparse worlds (the End, ocean worlds...).
        double[] tiers = {maxSpread, Math.min(maxSpread, 1000), Math.min(maxSpread, 400)};
        for (double spread : tiers) {
            double radius = Math.min(spread, maxBorderSpread);
            attempts:
            for (int attempt = 0; attempt < ATTEMPTS_PER_TIER; attempt++) {
                // Uniform roll anywhere inside the border's square.
                int x = (int) Math.round(centerX + (random.nextDouble() * 2 - 1) * radius);
                int z = (int) Math.round(centerZ + (random.nextDouble() * 2 - 1) * radius);
                world.getChunkAt(x >> 4, z >> 4); // make sure the terrain exists before inspecting it

                for (int y = surfaceScanStart(world, x, z); y > world.getMinHeight(); y--) {
                    if (!isSafeGround(world, x, y, z)) {
                        continue;
                    }
                    Location spot = new Location(world, x + 0.5, y + 1, z + 0.5, random.nextFloat() * 360f, 0f);
                    if (tooCloseToPrevious(previous, spot)) {
                        continue attempts; // roll a whole new spot instead
                    }
                    return spot;
                }
            }
        }
        return null;
    }

    /**
     * Whether the location sits inside the End's main (dragon) island zone: the
     * ring within {@code endExcludeRadius} of the world origin. The island (and
     * the vanilla spawn obsidian platform) is always generated around x=0, z=0
     * in an End world, regardless of where the world border or spawn was moved.
     */
    private static boolean onEndMainIsland(Location location) {
        World world = location.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.THE_END || endExcludeRadius <= 0) {
            return false;
        }
        int x = location.getBlockX();
        int z = location.getBlockZ();
        double r = endExcludeRadius;
        return (double) x * x + (double) z * z < r * r;
    }

    /**
     * Finds a safe landing spot on an End world's outer islands, away from the
     * dragon. Instead of rolling uniformly inside a big square (which mostly
     * hits the void gap that surrounds the main island), it walks outward in
     * growing distance rings with a few angles per ring, mirroring the actual
     * layout of vanilla End generation (the nearest islands usually sit around
     * a thousand blocks out). Candidates pass the same ground checks as
     * {@link #findSafeLocation}; returns {@code null} if nothing was found.
     */
    private static Location findEndSafeLocation(World world, Location previous) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        WorldBorder border = world.getWorldBorder();
        double centerX = border.getCenter().getX();
        double centerZ = border.getCenter().getZ();
        double half = border.getSize() / 2.0;

        double minDistance = Math.max(500, endExcludeRadius + 150);
        double maxDistance = Math.min(maxSpread, Math.min(half * 1.4, 6000));
        if (maxDistance < minDistance) {
            return null; // the world border already ends inside the dragon-island zone
        }
        for (int ring = 0, distance = (int) minDistance;
                ring < 12 && distance <= maxDistance;
                ring++, distance = (int) (distance * 1.4)) {
            attempts:
            for (int attempt = 0; attempt < END_OUTER_ATTEMPTS_PER_TIER; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2;
                int x = (int) Math.round(distance * Math.cos(angle));
                int z = (int) Math.round(distance * Math.sin(angle));
                if (Math.abs(x - centerX) > half || Math.abs(z - centerZ) > half) {
                    continue; // outside the world border (square corners)
                }
                world.getChunkAt(x >> 4, z >> 4); // make sure the terrain exists before inspecting it
                for (int y = surfaceScanStart(world, x, z); y > world.getMinHeight(); y--) {
                    if (!isSafeGround(world, x, y, z)) {
                        continue;
                    }
                    Location spot = new Location(world, x + 0.5, y + 1, z + 0.5, random.nextFloat() * 360f, 0f);
                    if (tooCloseToPrevious(previous, spot)) {
                        continue attempts; // roll a fresh spot on this ring instead
                    }
                    return spot;
                }
            }
        }
        return null;
    }

    private static boolean tooCloseToPrevious(Location previous, Location spot) {
        if (minSpotDistance <= 0 || previous == null || previous.getWorld() == null
                || !previous.getWorld().equals(spot.getWorld())) {
            return false;
        }
        return previous.distanceSquared(spot) < minSpotDistance * minSpotDistance;
    }

    /** Blocks of clear space required above a landing spot in roofed worlds. */
    private static final int REQUIRED_HEADROOM = 4;
    /** The first Y of the Nether's bedrock roof — never a landing spot. */
    private static final int NETHER_ROOF_Y = 127;

    /**
     * Where the downward ground scan starts for a column: the world's own
     * surface height (plus a little slack for snow layers and plants), instead
     * of the world's build limit. Starting at the build limit is what used to
     * put players on the Nether roof, and it let the scan accept a cave ceiling
     * long before it ever reached the real ground.
     */
    private static int surfaceScanStart(World world, int x, int z) {
        int highest = world.getHighestBlockYAt(x, z);
        int ceiling = world.getEnvironment() == World.Environment.NETHER
                ? Math.min(world.getMaxHeight() - 2, NETHER_ROOF_Y - 1)
                : world.getMaxHeight() - 2;
        return Math.max(world.getMinHeight() + 1, Math.min(highest + 2, ceiling));
    }

    /**
     * Whether a candidate spot is genuinely out in the open rather than inside
     * a cave or under an overhang. On normal (sky) worlds the landing block has
     * to be the highest block in its column, so the player always arrives under
     * open sky. Roofed worlds (Nether, End) have no sky to test, so they need a
     * few blocks of clear space overhead instead, and the Nether roof itself is
     * rejected outright.
     */
    private static boolean aboveGround(World world, int x, int y, int z) {
        if (world.getEnvironment() == World.Environment.NORMAL) {
            return world.getHighestBlockYAt(x, z) <= y;
        }
        if (world.getEnvironment() == World.Environment.NETHER && y >= NETHER_ROOF_Y) {
            return false;
        }
        for (int up = 1; up <= REQUIRED_HEADROOM; up++) {
            Material above = world.getBlockAt(x, y + up, z).getType();
            if (above.isSolid() || isLiquid(above)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeGround(World world, int x, int y, int z) {
        Material ground = world.getBlockAt(x, y, z).getType();
        Material below = world.getBlockAt(x, y - 1, z).getType();

        // Not on top of or under the bedrock layers, and not over lava/water.
        if (ground == Material.BEDROCK || below == Material.BEDROCK || !below.isOccluding()) {
            return false;
        }
        // Surface must be a solid opaque block (skips leaves, plants, slabs...).
        if (!ground.isOccluding()) {
            return false;
        }
        // A few solid blocks of support underneath, so thin crusts over caves are
        // rejected instead of dropping the player into a hole.
        for (int depth = 2; depth <= MIN_GROUND_DEPTH + 1; depth++) {
            Material support = world.getBlockAt(x, y - depth, z).getType();
            if (support == Material.BEDROCK || !support.isOccluding()) {
                return false;
            }
        }
        // Feet and head blocks must be clear of any solid or liquid.
        Material feet = world.getBlockAt(x, y + 1, z).getType();
        Material head = world.getBlockAt(x, y + 2, z).getType();
        if (feet.isSolid() || isLiquid(feet) || head.isSolid() || isLiquid(head)) {
            return false;
        }
        // Finally: the spot has to be above ground, not in a cave pocket.
        return aboveGround(world, x, y, z);
    }

    private static boolean isLiquid(Material material) {
        return material == Material.WATER || material == Material.LAVA;
    }
}