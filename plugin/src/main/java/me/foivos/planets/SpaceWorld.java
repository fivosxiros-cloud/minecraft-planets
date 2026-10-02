package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The space layer: a void world the ship flies around in.
 *
 * <p>It holds two kinds of real, placed structures — the player's ship and one
 * landing pad per planet — so flying somewhere means flying somewhere. The
 * world is created the first time somebody takes off (a flat world of nothing
 * but air, so the sky is the star field), and it is deliberately kept out of
 * {@code /planets} and the star chart: it is the space between places, not a
 * place.
 *
 * <p>A pad's position comes from a hash of the planet's world name, so it never
 * moves when planets are added or removed (a planet keeps its spot in the sky
 * forever), and each pad carries a floating label naming the planet and its
 * owner. Because the pads are ordinary blocks and entities they are saved with
 * the world: they are built once and are simply there afterwards.
 */
final class SpaceWorld {

    private static final int SHIP_HALF = 4;     // the ship platform is 9x9
    private static final int PAD_HALF = 3;      // a pad is 7x7
    private static final int END_ROD_HEIGHT = 4;
    /** How far a pad's protection reaches from its centre (a pad is 7x7 wide). */
    private static final double PAD_REACH = 9.0;

    private final Planets plugin;

    private boolean enabled = true;
    private String worldName = "planets_space";
    private double shipY = 96;
    private double ringRadius = 120;
    private double dockRadius = 12;
    private double cruiseSpeed = 0.08;
    private double sprintSpeed = 0.16;
    /** Whether admins may build in space without asking (off by default). */
    private boolean adminsCanBuild;
    /** How many passengers may ride along in one ship (0 turns riding off). */
    private int maxRiders = 3;

    /** The pads currently built in space, keyed by lowercase planet world name. */
    private final List<Pad> pads = new ArrayList<>();
    /**
     * The pads already in the sky, keyed by lowercase planet world name with the
     * label each was built with. Kept in config, so a restart doesn't re-lay the
     * sky - and, more importantly, a take-off never paints over a pad an admin
     * has changed with {@code /ship pads}.
     */
    private final Map<String, String> builtPads = new LinkedHashMap<>();

    SpaceWorld(Planets plugin) {
        this.plugin = plugin;
    }

    /**
     * One planet's landing pad in space: where it is, what it is called and what
     * the ship should say about it on approach.
     */
    record Pad(String worldKey, String label, String sublabel, Material icon, Location center) {
    }

    /** Re-reads the {@code space-travel.space} section of config.yml. */
    void loadConfig(FileConfiguration config) {
        enabled = true;
        worldName = "planets_space";
        shipY = 96;
        ringRadius = 120;
        dockRadius = 12;
        cruiseSpeed = 0.08;
        sprintSpeed = 0.16;
        maxRiders = 3;
        ConfigurationSection section = config.getConfigurationSection("space-travel.space");
        if (section == null) {
            return;
        }
        enabled = section.getBoolean("enabled", true);
        String configured = section.getString("world", "planets_space");
        if (configured != null && !configured.isBlank()) {
            worldName = configured;
        }
        shipY = Math.max(-32, Math.min(256, section.getDouble("ship-y", 96)));
        ringRadius = Math.max(40, section.getDouble("ring-radius", 120));
        dockRadius = Math.max(4, section.getDouble("dock-radius", 12));
        cruiseSpeed = Math.max(0.02, Math.min(1.0, section.getDouble("speed", 0.08)));
        sprintSpeed = Math.max(cruiseSpeed, Math.min(1.0, section.getDouble("sprint-speed", 0.16)));
        adminsCanBuild = section.getBoolean("admins-can-build", false);
        maxRiders = Math.max(0, Math.min(8, section.getInt("max-riders", 3)));
        builtPads.clear();
        for (String entry : section.getStringList("built-pads")) {
            int split = entry.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String key = entry.substring(0, split).trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) {
                builtPads.put(key, entry.substring(split + 1).trim());
            }
        }
    }

    /**
     * Whether admins may edit space freely. Off by default: space is protected
     * for everyone, and an admin turns editing on for themselves with
     * {@code /ship edit} while they work on it.
     */
    int maxRiders() {
        return maxRiders;
    }

    boolean adminsCanBuild() {
        return adminsCanBuild;
    }

    /** Remembers which pads are already in the sky (survives a restart). */
    private void saveBuilt() {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : builtPads.entrySet()) {
            entries.add(entry.getKey() + "=" + entry.getValue());
        }
        plugin.getConfig().set("space-travel.space.built-pads", entries);
        plugin.saveConfigQuietly();
    }

    boolean enabled() {
        return enabled;
    }

    String worldName() {
        return worldName;
    }

    double dockRadius() {
        return dockRadius;
    }

    double cruiseSpeed() {
        return cruiseSpeed;
    }

    double sprintSpeed() {
        return sprintSpeed;
    }

    /** Whether a world name is the space world (which is never a destination). */
    boolean isSpaceWorld(String name) {
        return name != null && name.equalsIgnoreCase(worldName);
    }

    boolean isSpaceWorld(World world) {
        return world != null && isSpaceWorld(world.getName());
    }

    /** The pads currently built, newest planet set first. */
    List<Pad> pads() {
        return List.copyOf(pads);
    }

    /**
     * Whether a spot is on or beside a planet's pad. The pads are where every
     * pilot lands, so they are the one part of space that stays admin territory:
     * {@code /ship edit} covers the ship and the open sky, never these.
     */
    boolean nearPad(Location where) {
        if (where == null || where.getWorld() == null) {
            return false;
        }
        for (Pad pad : pads) {
            if (pad.center().getWorld() == null || !pad.center().getWorld().equals(where.getWorld())) {
                continue;
            }
            if (pad.center().distanceSquared(where) <= PAD_REACH * PAD_REACH) {
                return true;
            }
        }
        return false;
    }

    /** The pad for a planet, or null when it has none yet. */
    Pad padFor(String planetWorldName) {
        if (planetWorldName == null) {
            return null;
        }
        String key = planetWorldName.toLowerCase(Locale.ROOT);
        for (Pad pad : pads) {
            if (pad.worldKey().equals(key)) {
                return pad;
            }
        }
        return null;
    }

    // ── The world ───────────────────────────────────────────────────────

    /**
     * Creates the space world if this is the first flight, and makes sure the
     * ship and every planet's pad exist. Returns null when the world could not
     * be created (the caller then falls back to the star chart's own launch).
     */
    World ensureReady() {
        if (!enabled) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            world = create(); // creates it, or loads the folder if it already exists
        }
        if (world == null) {
            return null;
        }
        keepItPeaceful(world);
        if (!shipExists(world)) {
            buildShip(world); // a sky with no ship in it gets its ship back
        }
        syncPads(world, false);
        return world;
    }

    /**
     * Space is a shared sky, so it is kept harmless: no PvP, no fall, fire or
     * drowning damage, and nothing spawning to interrupt a flight. Applied on
     * every take-off, so a sky made before these rules still gets them, and a
     * sky full of pilots stays a place nobody can be hurt in.
     */
    private void keepItPeaceful(World world) {
        world.setGameRule(GameRule.PVP, false);
        world.setGameRule(GameRule.FALL_DAMAGE, false);
        world.setGameRule(GameRule.FIRE_DAMAGE, false);
        world.setGameRule(GameRule.DROWNING_DAMAGE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        if (world.getDifficulty() != Difficulty.PEACEFUL) {
            world.setDifficulty(Difficulty.PEACEFUL);
        }
    }

    /** Makes an empty world with a star field, no weather and no mobs. */
    private World create() {
        try {
            World world = new WorldCreator(worldName)
                    .type(WorldType.FLAT)
                    // Nothing but air, in the void biome: the sky is the stars.
                    .generatorSettings("{\"layers\":[{\"block\":\"air\",\"height\":1}],"
                            + "\"biome\":\"the_void\"}")
                    .generateStructures(false)
                    .environment(World.Environment.NORMAL)
                    .createWorld();
            if (world == null) {
                plugin.getLogger().warning("Could not create the space world '" + worldName + "'.");
                return null;
            }
            world.setDifficulty(Difficulty.PEACEFUL);
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
            world.setGameRule(GameRule.DO_FIRE_TICK, false);
            world.setGameRule(GameRule.KEEP_INVENTORY, true);
            world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
            world.setTime(18000L); // night: better stars to fly through
            world.setSpawnLocation(0, (int) shipY, 0);
            plugin.getLogger().info("Created the space world '" + worldName + "' for /ship.");
            return world;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not create the space world '" + worldName + "': " + e.getMessage());
            return null;
        }
    }

    /** Where a pilot starts: standing on the ship. */
    Location shipSpawn() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, 0.5, shipY + 1, 0.5);
    }

    /**
     * Whether the ship is already in the sky. Any piece of it counts: the deck is
     * admin-editable with {@code /ship edit}, so it is only laid down when there
     * is no ship there at all - never over one somebody has changed.
     */
    private boolean shipExists(World world) {
        int y = (int) shipY;
        int[][] markers = {{0, y, 0}, {0, y + 1, 0},
                {SHIP_HALF, y, SHIP_HALF}, {-SHIP_HALF, y, -SHIP_HALF}};
        for (int[] marker : markers) {
            if (!world.getBlockAt(marker[0], marker[1], marker[2]).getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    /** Lays the ship platform down (only called when there is no ship there yet). */
    private void buildShip(World world) {
        int y = (int) shipY;
        for (int x = -SHIP_HALF; x <= SHIP_HALF; x++) {
            for (int z = -SHIP_HALF; z <= SHIP_HALF; z++) {
                boolean edge = Math.abs(x) == SHIP_HALF || Math.abs(z) == SHIP_HALF;
                Material material = edge ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_TILES;
                world.getBlockAt(x, y, z).setType(material, false);
            }
        }
        // Hull trim, a cockpit block and four masts, so it reads as a ship.
        for (int i = -1; i <= 1; i++) {
            world.getBlockAt(i, y, -SHIP_HALF).setType(Material.COPPER_BLOCK, false);
            world.getBlockAt(i, y, SHIP_HALF).setType(Material.COPPER_BLOCK, false);
        }
        world.getBlockAt(0, y + 1, 0).setType(Material.LODESTONE, false);
        for (int[] corner : new int[][]{{-SHIP_HALF, -SHIP_HALF}, {-SHIP_HALF, SHIP_HALF},
                {SHIP_HALF, -SHIP_HALF}, {SHIP_HALF, SHIP_HALF}}) {
            for (int i = 0; i < END_ROD_HEIGHT; i++) {
                world.getBlockAt(corner[0], y + 1 + i, corner[1]).setType(Material.END_ROD, false);
            }
        }
        label(world, new Location(world, 0.5, shipY + 3.4, 0.5),
                Component.text("\uD83D\uDE80 Your ship").color(NamedTextColor.AQUA),
                Component.text("Sneak into a planet's ring to dock").color(NamedTextColor.GRAY));
    }

    /**
     * Makes the pads match the planets, without ever re-laying a pad that is
     * already in the sky. The planet set is not stable - a world that is not
     * loaded right now is not listed, and a rename changes a label - so a pad is
     * laid exactly once, when its planet first gets one, and from then on it is
     * scenery. {@code /ship rebuild} is the only thing that puts the blocks back.
     */
    private void syncPads(World world, boolean force) {
        List<Pad> desired = collectPads(world);
        pads.clear();
        pads.addAll(desired); // where the pads *are*, whether or not they're built
        boolean changed = force;
        for (Pad pad : desired) {
            String built = builtPads.get(pad.worldKey());
            if (!force && built != null) {
                if (!built.equals(pad.label())) {
                    // A renamed planet: its floating name changes, its blocks don't.
                    label(world, pad);
                    builtPads.put(pad.worldKey(), pad.label());
                    changed = true;
                }
                continue;
            }
            if (!force && padExists(world, pad)) {
                // No record of it, but the platform is there: adopt it as it is.
                // That keeps even the first flight after an update from re-laying
                // a sky full of pads (or anything built on one of them).
                builtPads.put(pad.worldKey(), pad.label());
                changed = true;
                continue;
            }
            try {
                buildPad(world, pad);
            } catch (RuntimeException e) {
                // One bad pad must never sink a flight: report it and fly on.
                plugin.getLogger().warning("Could not build the landing pad for '"
                        + pad.worldKey() + "': " + e.getMessage());
                continue;
            }
            builtPads.put(pad.worldKey(), pad.label());
            changed = true;
        }
        if (changed) {
            saveBuilt();
        }
    }

    /** Every planet this server has, as a pad in space. */
    private List<Pad> collectPads(World world) {
        List<Pad> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        if (plugin.getMyPlanetManager() != null) {
            for (MyPlanetData data : plugin.getMyPlanetManager().allPlanets()) {
                String key = data.worldName().toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    continue;
                }
                boolean locked = !data.isPublic() || !data.visitorAccess();
                list.add(new Pad(key, data.displayName(),
                        (locked ? "\uD83D\uDD12 Invite only - " : "Open to visitors - ")
                                + plugin.adminOwnerName(data),
                        plugin.planetIcon(data.worldName(), Material.GRASS_BLOCK),
                        padLocation(world, key)));
            }
        }
        for (Planet planet : plugin.availablePlanets()) {
            String key = planet.worldName().toLowerCase(Locale.ROOT);
            if (isSpaceWorld(key) || !seen.add(key)) {
                continue;
            }
            list.add(new Pad(key, planet.name(), "Open to visitors - the server",
                    planet.icon(), padLocation(world, key)));
        }
        return list;
    }

    /**
     * A planet's spot in the sky, derived from its name so it never moves: the
     * hash picks an angle and one of a few rings.
     */
    private Location padLocation(World world, String worldKey) {
        int hash = Math.abs(worldKey.hashCode());
        double angle = (hash % 3600) / 3600.0 * Math.PI * 2.0;
        double radius = ringRadius + (hash / 3600 % 4) * 40;
        double x = Math.cos(angle) * radius;
        double z = Math.sin(angle) * radius;
        return new Location(world, x, shipY, z);
    }

    /** Whether a pad's platform is already laid out in the sky. */
    private boolean padExists(World world, Pad pad) {
        int cx = pad.center().getBlockX();
        int cz = pad.center().getBlockZ();
        int y = (int) shipY;
        // Any solid piece of the platform counts as built.
        return !world.getBlockAt(cx, y, cz).getType().isAir()
                || !world.getBlockAt(cx - PAD_HALF, y, cz - PAD_HALF).getType().isAir()
                || !world.getBlockAt(cx + PAD_HALF, y, cz + PAD_HALF).getType().isAir();
    }

    /** Lays one pad down: a platform, a lit rim, masts and a floating label. */
    private void buildPad(World world, Pad pad) {
        int cx = pad.center().getBlockX();
        int cz = pad.center().getBlockZ();
        int y = (int) shipY;
        for (int x = -PAD_HALF; x <= PAD_HALF; x++) {
            for (int z = -PAD_HALF; z <= PAD_HALF; z++) {
                boolean edge = Math.abs(x) == PAD_HALF || Math.abs(z) == PAD_HALF;
                Material material = edge ? Material.POLISHED_BLACKSTONE : Material.SMOOTH_STONE;
                world.getBlockAt(cx + x, y, cz + z).setType(material, false);
            }
        }
        // The planet's own icon sits in the middle of its pad, so pads look
        // different from the air as well as on the chart. Menu icons are often
        // items (a diamond, an ingot) that no block can be made of, so only real
        // block materials are placed and the rest get the ship's marker block.
        Material centre = pad.icon() != null && pad.icon().isBlock()
                ? pad.icon() : Material.LODESTONE;
        world.getBlockAt(cx, y, cz).setType(centre, false);
        for (int[] corner : new int[][]{{-PAD_HALF, -PAD_HALF}, {-PAD_HALF, PAD_HALF},
                {PAD_HALF, -PAD_HALF}, {PAD_HALF, PAD_HALF}}) {
            for (int i = 0; i < END_ROD_HEIGHT; i++) {
                world.getBlockAt(cx + corner[0], y + 1 + i, cz + corner[1])
                        .setType(Material.END_ROD, false);
            }
        }
        label(world, pad);
    }

    /**
     * Rebuilds everything in space: the ship, one pad per planet, and takes away
     * the pads of planets that no longer exist. Returns how many pads were
     * cleared, or -1 when the space world isn't loaded.
     */
    int rebuild() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return -1;
        }
        Set<String> keep = new LinkedHashSet<>();
        for (Pad pad : collectPads(world)) {
            keep.add(pad.worldKey());
        }
        int cleared = 0;
        for (String key : new ArrayList<>(builtPads.keySet())) {
            if (!keep.contains(key)) {
                clearPad(world, key);
                builtPads.remove(key);
                cleared++;
            }
        }
        buildShip(world);
        syncPads(world, true); // forces every pad to be laid down again
        return cleared;
    }

    /** Takes a pad back out of the sky: platform to air, label removed. */
    private void clearPad(World world, String worldKey) {
        Location center = padLocation(world, worldKey);
        int cx = center.getBlockX();
        int cz = center.getBlockZ();
        int y = (int) shipY;
        for (int x = -PAD_HALF; x <= PAD_HALF; x++) {
            for (int z = -PAD_HALF; z <= PAD_HALF; z++) {
                world.getBlockAt(cx + x, y, cz + z).setType(Material.AIR, false);
            }
        }
        for (int[] corner : new int[][]{{-PAD_HALF, -PAD_HALF}, {-PAD_HALF, PAD_HALF},
                {PAD_HALF, -PAD_HALF}, {PAD_HALF, PAD_HALF}}) {
            for (int i = 0; i < END_ROD_HEIGHT; i++) {
                world.getBlockAt(cx + corner[0], y + 1 + i, cz + corner[1]).setType(Material.AIR, false);
            }
        }
        removeLabels(world, labelSpot(world, cx, cz));
    }

    /** Removes any floating label already in a spot, so text never stacks up. */
    private void removeLabels(World world, Location location) {
        for (Entity entity : world.getNearbyEntities(location, 2.5, 5.0, 2.5)) {
            if (entity instanceof TextDisplay) {
                entity.remove();
            }
        }
    }

    /** Where a pad's floating label sits, from the pad's own centre. */
    private Location labelSpot(World world, int cx, int cz) {
        return new Location(world, cx + 0.5, shipY + 3.4, cz + 0.5);
    }

    /** A pad's floating name, replacing any label already in that spot. */
    private void label(World world, Pad pad) {
        label(world, labelSpot(world, pad.center().getBlockX(), pad.center().getBlockZ()),
                Component.text(pad.label()).color(NamedTextColor.AQUA),
                Component.text(pad.sublabel()).color(NamedTextColor.GRAY));
    }

    /** A two-line floating label, replacing any label already in that spot. */
    private void label(World world, Location location, Component title, Component subtitle) {
        // Replace instead of stacking, so renamed planets don't leave old text.
        removeLabels(world, location);
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.text(title.append(Component.newline()).append(subtitle));
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setSeeThrough(true);
            entity.setViewRange(4.0f);
            entity.setPersistent(true);
            entity.setInvulnerable(true);
            entity.setGravity(false);
        });
        display.setDefaultBackground(false);
    }

    /** How many pads exist (used by the star chart to mention the galaxy). */
    int padCount() {
        return pads.size();
    }
}
