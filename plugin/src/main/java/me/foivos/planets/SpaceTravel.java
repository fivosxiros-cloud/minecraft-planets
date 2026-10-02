package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Space travel: the ship, the star chart and the flight between planets.
 *
 * <p>The star chart ({@link GalaxyMapMenu}, opened with {@code /ship}) lists
 * every destination this server has — the public planets and every player's own
 * planet. A planet nobody has landed on yet reads as {@code ???} on that
 * player's chart and is filled in the moment they set foot on it (the fog of
 * war), one chart per player, remembered in {@code space.yml}.
 *
 * <p>Picking a destination plays a short launch — engine, warp particles,
 * countdown — and then hands over to {@link PlanetTravel}, which owns landing,
 * cooldowns, combat tags and the access rules: a planet that is private can only
 * be entered by its members and by the players its owner invited, so flying to
 * one without an invite bounces you off.
 *
 * <p>{@code /ship} takes off straight into the sky; the cockpit - the star chart
 * itself - opens where the player stands with {@code /ship chart}. Two things
 * stand between a player and the sky: a fight they are still in, and a short
 * cooldown after their last flight, so the ship is a ride rather than a lift.
 */
final class SpaceTravel {

    /** Fallback values for when the config keys are missing. */
    private static final double DEFAULT_FLIGHT_SECONDS = 3.0;
    private static final int MAX_FLIGHT_SECONDS = 10;
    /** How long between two trips into space by default, and the ceiling for it. */
    private static final double DEFAULT_TAKEOFF_COOLDOWN_SECONDS = 30.0;
    private static final double MAX_TAKEOFF_COOLDOWN_SECONDS = 3600.0;
    /** How long the arrival watcher waits for a teleport to land, in ticks. */
    private static final int ARRIVAL_TIMEOUT_TICKS = 20 * 25;

    private final Planets plugin;
    private final File dataFile;
    private YamlConfiguration config;

    /** Player -> the worlds they have landed on (lowercase), their star chart. */
    private final Map<UUID, Set<String>> charted = new HashMap<>();

    private boolean enabled = true;
    private double flightSeconds = DEFAULT_FLIGHT_SECONDS;
    private double takeoffCooldownSeconds = DEFAULT_TAKEOFF_COOLDOWN_SECONDS;

    /** Player -> when they last went up, so nobody can live in the sky. */
    private final Map<UUID, Long> lastFlights = new ConcurrentHashMap<>();

    SpaceTravel(Planets plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "space.yml");
        load();
    }

    /**
     * One entry on the star chart: where it goes, who owns it, whether this
     * player may land there, and whether they have charted it yet.
     */
    record Destination(Planet planet, String ownerName, boolean ownOrMember,
                       boolean inviteOnly, boolean charted, boolean invited,
                       boolean requested) {
    }

    // ── Configuration ───────────────────────────────────────────────────

    /** Re-reads the {@code space-travel} section of config.yml. */
    void loadConfig(FileConfiguration config) {
        enabled = true;
        flightSeconds = DEFAULT_FLIGHT_SECONDS;
        takeoffCooldownSeconds = DEFAULT_TAKEOFF_COOLDOWN_SECONDS;
        ConfigurationSection section = config.getConfigurationSection("space-travel");
        if (section != null) {
            enabled = section.getBoolean("enabled", true);
            flightSeconds = Math.max(0.0,
                    Math.min(MAX_FLIGHT_SECONDS, section.getDouble("flight-seconds", DEFAULT_FLIGHT_SECONDS)));
            takeoffCooldownSeconds = Math.max(0.0, Math.min(MAX_TAKEOFF_COOLDOWN_SECONDS,
                    section.getDouble("takeoff-cooldown-seconds", DEFAULT_TAKEOFF_COOLDOWN_SECONDS)));
        }
    }

    boolean enabled() {
        return enabled;
    }

    // ── Heading up ──────────────────────────────────────────────────────

    /**
     * How long a player must wait between two trips into space, in seconds (0
     * turns the cooldown off).
     */
    double takeoffCooldownSeconds() {
        return takeoffCooldownSeconds;
    }

    /**
     * Whether the player may head into space right now: not from a fight, and not
     * straight off another flight. They are always told why not, so the ship never
     * just silently refuses.
     */
    boolean readyForFlight(Player player) {
        if (PlanetTravel.isInCombat(player)) {
            player.sendMessage(Component.text("\uD83D\uDE80 You can't take off while in combat - ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("settle it first.").color(NamedTextColor.RED)));
            return false;
        }
        if (takeoffCooldownSeconds <= 0) {
            return true;
        }
        Long last = lastFlights.get(player.getUniqueId());
        if (last == null) {
            return true;
        }
        double remaining = takeoffCooldownSeconds - (System.currentTimeMillis() - last) / 1000.0;
        if (remaining <= 0) {
            lastFlights.remove(player.getUniqueId());
            return true;
        }
        player.sendMessage(Component.text("\uD83D\uDE80 The ship needs ")
                .color(NamedTextColor.RED)
                .append(Component.text(String.format(Locale.ROOT, "%.0f", Math.ceil(remaining))
                        + " more second(s)").color(NamedTextColor.YELLOW))
                .append(Component.text(" before it can fly again.").color(NamedTextColor.RED)));
        return false;
    }

    /** Starts a player's cooldown: they have just gone up. */
    void markFlight(Player player) {
        lastFlights.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /**
     * Opens the cockpit: the star chart, right where the player stands. The ship
     * itself is flown with {@code /ship} - this is the map you read first.
     */
    void openStarChart(Player player) {
        if (!enabled) {
            player.sendMessage(Component.text("Space travel is switched off on this server.")
                    .color(NamedTextColor.RED));
            return;
        }
        // The star chart is a ground tool: up in the sky the only way on is to fly.
        if (plugin.refuseWhileFlying(player)) {
            return;
        }
        if (PlanetTravel.isInCombat(player)) {
            player.sendMessage(Component.text("You can't read the chart while in combat.")
                    .color(NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1.0f, 0.8f);
        new GalaxyMapMenu(plugin, player).open(player);
    }

    // ── The star chart ──────────────────────────────────────────────────

    /**
     * Every planet this server has, with what this player knows about each one.
     * Their own planets and the ones they belong to are always charted; the rest
     * of the galaxy fills in as they land on it.
     */
    List<Destination> destinations(Player player) {
        UUID id = player.getUniqueId();
        Set<String> chart = charted.getOrDefault(id, Set.of());
        List<Destination> list = new ArrayList<>();
        Set<String> added = new HashSet<>();

        if (plugin.getMyPlanetManager() != null) {
            for (MyPlanetData data : plugin.getMyPlanetManager().allPlanets()) {
                String key = data.worldName().toLowerCase(Locale.ROOT);
                added.add(key);
                boolean member = data.isMember(id);
                list.add(new Destination(
                        new Planet(data.displayName(), iconFor(data.worldName()), data.worldName()),
                        plugin.adminOwnerName(data),
                        member,
                        !data.isPublic() || !data.visitorAccess(),
                        member || chart.contains(key),
                        data.isInvited(id),
                        data.hasVisitRequest(id)));
            }
        }
        for (Planet planet : plugin.availablePlanets()) {
            String key = planet.worldName().toLowerCase(Locale.ROOT);
            if (!added.add(key)) {
                continue; // a player's planet is already on the chart once
            }
            list.add(new Destination(planet, "the server", false, false, chart.contains(key), false, false));
        }
        return list;
    }

    /** The icon shown for a planet: its configured one, or a sensible default. */
    private org.bukkit.Material iconFor(String worldName) {
        return plugin.planetIcon(worldName, org.bukkit.Material.GRASS_BLOCK);
    }

    /** Whether this player has landed on a world. */
    boolean hasCharted(UUID player, String worldName) {
        Set<String> chart = charted.get(player);
        return chart != null && chart.contains(worldName.toLowerCase(Locale.ROOT));
    }

    /** How many planets this player has charted. */
    int chartedCount(UUID player) {
        Set<String> chart = charted.get(player);
        return chart == null ? 0 : chart.size();
    }

    /**
     * Writes a world onto a player's chart.
     *
     * @return true when it wasn't on the chart before (a discovery)
     */
    boolean chart(UUID player, String worldName) {
        String key = worldName.toLowerCase(Locale.ROOT);
        Set<String> chart = charted.computeIfAbsent(player, k -> new HashSet<>());
        if (!chart.add(key)) {
            return false;
        }
        save();
        return true;
    }

    // ── Launching ───────────────────────────────────────────────────────

    /**
     * Flies a player to a destination: a short launch, then the planet travel
     * this plugin already uses (landing mode, safe spot, cooldown, combat tag
     * and the planet's own access rules all still apply).
     */
    void launch(Player player, Destination destination) {
        Planet target = destination.planet();
        player.closeInventory();
        if (!enabled || flightSeconds <= 0) {
            PlanetTravel.teleport(player, target);
            watchArrival(player, target);
            return;
        }

        player.showTitle(Title.title(
                Component.text("\uD83D\uDE80 Launching").color(NamedTextColor.AQUA),
                Component.text(destination.charted()
                        ? "Setting course for " + target.name()
                        : "Course set for an uncharted planet").color(NamedTextColor.GRAY)));
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.8f);

        // Warp streaks and engine noise for as long as the flight lasts.
        BukkitRunnable warp = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                Location eye = player.getEyeLocation();
                player.spawnParticle(Particle.END_ROD, eye, 12, 1.4, 0.7, 1.4, 0.02);
                player.spawnParticle(Particle.PORTAL, eye.clone().add(0, -0.6, 0), 10, 1.0, 0.4, 1.0, 0.05);
            }
        };
        warp.runTaskTimer(plugin, 0L, 4L);

        long ticks = Math.round(flightSeconds * 20.0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            warp.cancel();
            if (!player.isOnline()) {
                return;
            }
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
            PlanetTravel.teleport(player, target);
            watchArrival(player, target);
        }, ticks);
    }

    /**
     * Watches for the player actually turning up on the destination world — the
     * teleport itself waits a moment, and can still be refused — and fills in
     * their star chart when they land.
     */
    private void watchArrival(Player player, Planet target) {
        new BukkitRunnable() {
            private int waited;

            @Override
            public void run() {
                if (!player.isOnline() || waited >= ARRIVAL_TIMEOUT_TICKS) {
                    cancel();
                    return;
                }
                waited += 10;
                World world = player.getWorld();
                if (world == null || !world.getName().equalsIgnoreCase(target.worldName())) {
                    return;
                }
                boolean discovery = chart(player.getUniqueId(), target.worldName());
                announceArrival(player, target.name(), discovery);
                cancel();
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    /**
     * Watches a landing somebody else started — the ship's own docking — so the
     * chart fills in and the arrival is announced the same way as a launch.
     */
    void watchLanding(Player player, Planet target) {
        watchArrival(player, target);
    }

    /** The moment a player touches down: a title, a chime and the chart line. */
    private void announceArrival(Player player, String name, boolean discovery) {
        player.showTitle(Title.title(
                Component.text(name).color(NamedTextColor.AQUA),
                Component.text(discovery ? "New planet charted" : "Safe landing")
                        .color(NamedTextColor.GRAY)));
        player.playSound(player.getLocation(),
                discovery ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.0f);
        if (discovery) {
            player.sendMessage(Component.text("\uD83C\uDF0C Charted ").color(NamedTextColor.GREEN)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text(" - " + chartedCount(player.getUniqueId())
                            + " planet(s) on your chart.").color(NamedTextColor.GREEN)));
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private void load() {
        charted.clear();
        if (!dataFile.exists()) {
            config = new YamlConfiguration();
            return;
        }
        config = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = config.getConfigurationSection("charted");
        if (root == null) {
            return;
        }
        for (String playerKey : root.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(playerKey);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            Set<String> chart = new HashSet<>();
            for (String worldName : root.getStringList(playerKey)) {
                chart.add(worldName.toLowerCase(Locale.ROOT));
            }
            if (!chart.isEmpty()) {
                charted.put(uuid, chart);
            }
        }
    }

    /** Writes every player's star chart back to space.yml. */
    void save() {
        if (config == null) {
            config = new YamlConfiguration();
        }
        config.set("charted", null);
        ConfigurationSection root = config.createSection("charted");
        for (Map.Entry<UUID, Set<String>> entry : charted.entrySet()) {
            root.set(entry.getKey().toString(), new ArrayList<>(entry.getValue()));
        }
        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save space.yml", e);
        }
    }
}
