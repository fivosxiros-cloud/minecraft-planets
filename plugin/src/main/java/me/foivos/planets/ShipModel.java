package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.boat.OakBoat;
import org.bukkit.entity.boat.OakChestBoat;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The ship itself: a real Minecraft boat the pilot sits in, flying through space.
 *
 * <p>The hull is a real boat entity - an oak boat, or the oak chest boat when the
 * pilot picked that in {@code /settings} - so the pilot rides in its seat like in
 * any other boat and the boat is really there in the sky. A boat's movement
 * belongs to its first passenger, though, and a pilot flies this one with the
 * plugin's own controls (the configured pace, the throttle, docking), so an
 * invisible marker armor stand takes the driving seat and the pilot sits behind
 * it: nothing of the stand shows, it is only there to leave the flying to
 * {@link ShipPilot} instead of to the pilot's client.
 *
 * <p>A real boat has nowhere to hang a banner and nothing to put a name on, so
 * those two ride along as display entities: a {@link TextDisplay} plate above the
 * deck saying whose ship it is and where it is headed, and a banner
 * {@link BlockDisplay} at the stern while the pilot keeps that toggle on. They
 * are decoration only - the boat is what flies - and they follow it every tick.
 *
 * <p>The boat is never saved with the world and is removed when the flight ends.
 * Changing the boat or the banner in {@code /settings} restyles the ship where it
 * flies: {@link #restyle} swaps the boat under the pilot and takes the new one
 * up, without another take-off.
 */
final class ShipModel {

    /** Ticks the client smooths a display over: one flight tick. */
    private static final int SMOOTHING = 1;
    /** Full brightness, so the plate and banner are legible in a sky with nothing to light them. */
    private static final int LIGHT = 15;
    /** How high above the boat the name plate floats. */
    private static final float PLATE_HEIGHT = 2.8f;
    /** Where the banner sits at the stern, in the boat's own frame. */
    private static final Vector BANNER_AT = new Vector(0, 0.8, -1.05);
    private static final Vector BANNER_SIZE = new Vector(0.5, 0.85, 0.5);

    /** The boat the pilot rides in, or null when the server would not give us one. */
    private Boat boat;
    /**
     * The invisible hand on the wheel. A boat is driven by its first passenger;
     * putting this stand there means the boat is not driven by the pilot's own
     * client, so the plugin can fly it at the configured pace.
     */
    private ArmorStand driver;
    /** The banner at the stern, when the pilot flies one. */
    private BlockDisplay banner;
    /** The name plate above the deck. */
    private TextDisplay plate;
    /** Which boat the pilot picked, so a change in /settings can be noticed. */
    private PlayerSettings.BoatType type = PlayerSettings.BoatType.NORMAL;

    private ShipModel() {
    }

    /**
     * Builds the pilot's boat on the spot they are standing, in the shape they
     * picked in {@code /settings}, and seats them in it. The bow is the boat's +Z
     * end, the stern its -Z end.
     *
     * @param plugin      the plugin holding the player settings (nullable: a missing
     *                    settings store just means the default boat)
     * @param name        whose boat this is (the name plate says so)
     * @param destination the planet the pilot set course for, or null for a free flight
     * @return the boat - never null, but possibly without a boat at all if the
     *         server refused to spawn one ({@link #isEmpty()} says so)
     */
    static ShipModel spawn(Planets plugin, Player pilot, World world, String name, String destination) {
        PlayerSettings settings = plugin == null ? null : plugin.getPlayerSettings();
        ShipModel ship = new ShipModel();
        ship.type = settings == null
                ? PlayerSettings.BoatType.NORMAL : settings.boatType(pilot.getUniqueId());
        Location at = pilot.getLocation().clone();
        at.setPitch(0); // the ship stays level; the pilot's own view is free
        ship.plate = plate(world, at, name, destination);
        ship.boat = spawnBoat(world, at, ship.type);
        if (ship.boat != null) {
            ship.driver = driver(world, at);
            if (ship.driver != null) {
                // The driving seat is taken first, so the pilot's seat is never
                // the one the client thinks it controls.
                ship.boat.addPassenger(ship.driver);
            }
            ship.boat.addPassenger(pilot);
            if (bannerWanted(settings, pilot)) {
                ship.banner = banner(world, at);
            }
        }
        return ship;
    }

    /** The real boat, or null when it is gone (dead, or never spawned). */
    Boat boat() {
        return boat != null && boat.isValid() ? boat : null;
    }

    /** Whether the ship has no real boat at all (nothing for the pilot to fly in). */
    boolean isEmpty() {
        return boat() == null;
    }

    /**
     * Whether the boat is still a ship: there, and with the invisible driver
     * holding the driving seat. Anything else (a stray {@code /kill}, an admin
     * clearing the sky) is treated as a lost boat, so a flight never flies a
     * boat whose wheel is in the pilot's own hands.
     */
    private boolean isFlyable() {
        Boat boat = boat();
        return boat != null && driver != null && !driver.isDead()
                && boat.getPassengers().contains(driver);
    }

    /**
     * Puts the pilot in their seat if they are not in it: a boat asks its rider to
     * step out when they sneak (and sneak is the docking key up here), so the
     * flight tick seats them again - within the same server tick, before the
     * client is ever told they left.
     */
    void seat(Player pilot) {
        Boat boat = boat();
        if (boat == null || pilot == null || !pilot.isOnline()) {
            return;
        }
        if (pilot.getVehicle() != boat) {
            boat.addPassenger(pilot);
        }
    }

    /**
     * Makes sure there is still a flyable boat under the pilot, rebuilding one if
     * it went missing. Called on every flight tick, so it usually does nothing.
     */
    void ensureBoat(Planets plugin, Player pilot) {
        if (isFlyable() || pilot == null || !pilot.isOnline()) {
            return;
        }
        PlayerSettings settings = plugin == null ? null : plugin.getPlayerSettings();
        PlayerSettings.BoatType wanted = settings == null
                ? type : settings.boatType(pilot.getUniqueId());
        fitBoat(pilot, wanted);
    }

    /**
     * Restyles the ship where it flies: a boat or banner changed in
     * {@code /settings} is taken up at once, without landing and taking off again.
     */
    void restyle(Planets plugin, Player pilot) {
        PlayerSettings settings = plugin == null ? null : plugin.getPlayerSettings();
        if (settings == null || pilot == null) {
            return;
        }
        PlayerSettings.BoatType wanted = settings.boatType(pilot.getUniqueId());
        if (boat() == null || wanted != type) {
            fitBoat(pilot, wanted);
        }
        syncBanner(settings, pilot);
    }

    /**
     * Puts a boat of the wanted kind under the pilot, keeping whoever is aboard:
     * the old one is stepped out of and taken away, and the driver and pilot go
     * back in, in that order, so the driving seat stays the driver's.
     */
    private void fitBoat(Player pilot, PlayerSettings.BoatType wanted) {
        if (pilot == null || !pilot.isOnline()) {
            return;
        }
        Boat old = boat;
        Location at = old != null && !old.isDead() ? old.getLocation().clone() : pilot.getLocation().clone();
        at.setPitch(0);
        World world = at.getWorld() != null ? at.getWorld() : pilot.getWorld();
        if (world == null) {
            return;
        }
        Boat fresh = spawnBoat(world, at, wanted);
        if (fresh == null) {
            return; // the server won't give us one: keep what the pilot has
        }
        type = wanted;
        boat = fresh; // the pilot's seat is the new boat from here on
        if (old != null && !old.isDead()) {
            // The old boat is no longer *the* boat, so stepping out of it is
            // allowed - the pilot is released and then seated again below.
            old.remove();
        }
        if (driver == null || driver.isDead() || !driver.isValid()) {
            driver = driver(world, at);
        }
        if (driver != null) {
            fresh.addPassenger(driver);
        }
        fresh.addPassenger(pilot);
    }

    /** Adds or takes away the banner to match the pilot's setting. */
    private void syncBanner(PlayerSettings settings, Player pilot) {
        boolean wanted = bannerWanted(settings, pilot);
        if (wanted) {
            if (banner == null || banner.isDead()) {
                Boat boat = boat();
                World world = boat != null ? boat.getWorld() : pilot.getWorld();
                Location at = boat != null ? boat.getLocation() : pilot.getLocation();
                if (world != null) {
                    banner = banner(world, at);
                }
            }
            return;
        }
        if (banner != null) {
            if (!banner.isDead()) {
                banner.remove();
            }
            banner = null;
        }
    }

    /** Sends the name plate and the banner along with the boat they belong to. */
    void follow() {
        Boat boat = boat();
        if (boat == null) {
            return;
        }
        Location at = boat.getLocation();
        if (plate != null && plate.isValid()) {
            plate.teleport(at);
        }
        if (banner != null && banner.isValid()) {
            banner.teleport(at);
        }
    }

    /** Takes the whole ship out of the sky: the boat, the stand, the banner, the plate. */
    void remove() {
        Boat boat = this.boat;
        this.boat = null;
        if (boat != null && !boat.isDead()) {
            boat.eject(); // nobody rides a ship that is leaving
            boat.remove();
        }
        if (driver != null && !driver.isDead()) {
            driver.remove();
        }
        driver = null;
        if (banner != null && !banner.isDead()) {
            banner.remove();
        }
        banner = null;
        if (plate != null && !plate.isDead()) {
            plate.remove();
        }
        plate = null;
    }

    // ── Building ────────────────────────────────────────────────────────

    /** The pilot's own answer to "which boat": the plain one, or the chest boat. */
    private static boolean bannerWanted(PlayerSettings settings, Player pilot) {
        return settings == null || settings.get(pilot.getUniqueId(), PlayerSettings.Setting.SHIP_BANNER);
    }

    /**
     * Spawns the real boat as the entity the pilot picked. A boat the server
     * won't give us is reported as null - the caller then has no ship at all
     * rather than a ship with nothing to ride.
     */
    private static Boat spawnBoat(World world, Location at, PlayerSettings.BoatType type) {
        try {
            return switch (type) {
                case CHEST -> world.spawn(at, OakChestBoat.class, ShipModel::tune);
                default -> world.spawn(at, OakBoat.class, ShipModel::tune);
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Makes a freshly spawned boat into a ship: it does not fall, nothing can
     * break it, it is never saved, it does not splash or creak, and it glows
     * faintly so a pilot can find it against the dark sky.
     */
    private static void tune(Boat boat) {
        boat.setGravity(false);     // the ship is flown, not floated
        boat.setInvulnerable(true); // nothing up here gets to break it
        boat.setPersistent(false);  // never saved, never left behind
        boat.setSilent(true);
        boat.setGlowing(true);
        boat.setWorkOnLand(true);   // the whole of space is dry land
        boat.setVelocity(new Vector());
    }

    /**
     * The invisible stand in the driving seat: a boat is controlled by its first
     * passenger, and this is the stand that takes that seat so the plugin flies
     * the ship instead of the pilot's client. Nothing of it renders.
     */
    private static ArmorStand driver(World world, Location at) {
        try {
            return world.spawn(at, ArmorStand.class, stand -> {
                stand.setMarker(true);
                stand.setInvisible(true);
                stand.setSmall(true);
                stand.setBasePlate(false);
                stand.setArms(false);
                stand.setSilent(true);
                stand.setGravity(false);
                stand.setInvulnerable(true);
                stand.setPersistent(false);
            });
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The banner at the stern: a decorated block display riding with the boat. */
    private static BlockDisplay banner(World world, Location at) {
        try {
            return world.spawn(at, BlockDisplay.class, entity -> {
                entity.setBlock(Material.WHITE_BANNER.createBlockData());
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setPersistent(false); // never saved, never left behind
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setTeleportDuration(SMOOTHING);
                entity.setViewRange(2.0f);
                entity.setShadowRadius(0f);
                entity.setShadowStrength(0f);
                entity.setBrightness(new Display.Brightness(LIGHT, LIGHT));
                entity.setTransformation(box(BANNER_AT, BANNER_SIZE));
            });
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The name plate floating above the deck. */
    private static TextDisplay plate(World world, Location at, String name, String destination) {
        try {
            return world.spawn(at, TextDisplay.class, entity -> {
                Component text = Component.text("\uD83D\uDE80 " + name + "'s ship")
                        .color(NamedTextColor.WHITE);
                if (destination != null && !destination.isBlank()) {
                    text = text.append(Component.text("\nCourse: " + destination)
                            .color(NamedTextColor.GRAY));
                }
                entity.text(text);
                entity.setBillboard(Display.Billboard.CENTER);
                entity.setSeeThrough(true);
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setTeleportDuration(SMOOTHING);
                entity.setViewRange(2.0f);
                entity.setBrightness(new Display.Brightness(LIGHT, LIGHT));
                entity.setTransformation(new Transformation(
                        new Vector3f(0f, PLATE_HEIGHT, 0f), new Quaternionf(),
                        new Vector3f(0.85f, 0.85f, 0.85f), new Quaternionf()));
            });
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A block drawn as a box of {@code size} centred on {@code centre}. */
    private static Transformation box(Vector centre, Vector size) {
        return new Transformation(
                new Vector3f((float) (centre.getX() - size.getX() / 2.0),
                        (float) (centre.getY() - size.getY() / 2.0),
                        (float) (centre.getZ() - size.getZ() / 2.0)),
                new Quaternionf(),
                new Vector3f((float) size.getX(), (float) size.getY(), (float) size.getZ()),
                new Quaternionf());
    }
}
