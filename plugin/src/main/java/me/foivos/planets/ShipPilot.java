package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Piloting the ship: the player actually flies it around {@link SpaceWorld}.
 *
 * <p>The ship is a real boat ({@link ShipModel}) and the pilot sits in it. A boat
 * is driven by its first passenger, so an invisible marker takes that seat: the
 * plugin flies the boat with the pilot's own input — look where you want to go
 * and hold the movement keys — and the configured {@code space.speed} is how
 * fast it cruises, with sprinting opening the throttle. The hull turns to face
 * the way the pilot looks, thrusters burn behind it, and the action bar reports
 * what is in range.
 *
 * <p>Docking is deliberately physical: fly into a planet's pad and sneak. The
 * landing then goes through {@link PlanetTravel}, so a planet that is locked
 * still refuses you at the door and the star chart's fog of war fills in as you
 * touch down. Nothing here overrides the planet rules — it only moves you.
 *
 * <p>Other players ride along: {@code /ship ride <pilot>} lifts a passenger into
 * space beside the ship and holds them there (only the position follows the
 * ship, so they are free to look around), and they land with the pilot wherever
 * the pilot docks. {@code /ship leave} steps off and puts them back where they
 * boarded, and the same happens if the ship comes down early, the pilot logs
 * out or the planet refuses docking.
 */
final class ShipPilot {

    /** How often the flight is nudged, in ticks: every tick, so the ship answers at once. */
    private static final long TICK_INTERVAL = 1L;
    /** How far below the ship a pilot may fall before being put back on it. */
    private static final double VOID_LEASH = 60;
    /**
     * Blocks a tick the ship covers for each unit of the configured speed. The
     * config numbers use the same units as {@code Player#setFlySpeed}, where 0.05
     * is ordinary creative flight (about 10.9 blocks a second), so the boat keeps
     * the pace the ship has always flown at.
     */
    private static final double SPEED_SCALE = 10.89;
    /** Passengers are held in place every tick, so the ride looks smooth. */
    private static final long RIDE_INTERVAL = 1L;
    /** Passengers never steer, so their own flight stays at a walking pace. */
    private static final float RIDE_SPEED = 0.05f;
    /** How far to the side of the pilot each seat sits (seat 0, 1, 2, ...). */
    private static final double[] SEAT_OFFSETS = {1.5, -1.5, 2.6, -2.6, 3.7, -3.7, 4.8, -4.8};

    private final Planets plugin;
    private final SpaceWorld space;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Passengers along for the ride, keyed by the passenger. */
    private final Map<UUID, Ride> rides = new ConcurrentHashMap<>();
    /**
     * Who is on the way down and where to (the pad label). A landing is only a
     * request: the ship keeps flying until the planet actually takes them, so an
     * interrupted docking leaves everyone in the air with the controls still theirs.
     */
    private final Map<UUID, String> landing = new ConcurrentHashMap<>();
    /** Live find-trails, one per pilot, cancelled when the pilot lands or leaves. */
    private final Map<UUID, BukkitTask> tasks = new ConcurrentHashMap<>();

    /** Counts flight ticks, so the engine hum keeps a slow beat. */
    private int engineTick;

    /** What a pilot had before they took off, so it can be handed back. */
    private record Session(String destination, Location returnTo, float flySpeed,
                           boolean couldFly, ShipModel ship) {
    }

    /** A passenger in somebody else's ship: which ship, which seat, what to hand back. */
    private record Ride(UUID pilot, String pilotName, int seat, Location returnTo,
                        float flySpeed, boolean couldFly) {
    }

    /** A passenger taken off a ship, together with what their ride remembered. */
    private record Rider(Player player, Ride ride) {
    }

    ShipPilot(Planets plugin, SpaceWorld space) {
        this.plugin = plugin;
        this.space = space;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
        Bukkit.getScheduler().runTaskTimer(plugin, this::followRiders, RIDE_INTERVAL, RIDE_INTERVAL);
    }

    boolean isPiloting(Player player) {
        return player != null && sessions.containsKey(player.getUniqueId());
    }

    /** Whether this player is flying right now (used to leave them alone). */
    boolean isPiloting(UUID player) {
        return sessions.containsKey(player);
    }

    /**
     * Restyles a ship that is flying right now: {@code /settings} can swap the
     * boat or the banner without landing and taking off again. Does nothing when
     * the player is not in the air.
     */
    void refreshBoat(Player player) {
        Session session = player == null ? null : sessions.get(player.getUniqueId());
        if (session == null || session.ship() == null) {
            return;
        }
        session.ship().restyle(plugin, player);
    }

    /** Whether this player is riding in somebody else's ship. */
    boolean isRiding(Player player) {
        return player != null && rides.containsKey(player.getUniqueId());
    }

    /**
     * Whether this player is on the way down to a planet's pad (asked by travel,
     * which lets a docking ship through but keeps everyone else flying).
     */
    boolean isLanding(Player player) {
        return player != null && landing.containsKey(player.getUniqueId());
    }

    /** Who a passenger is riding with, or null when they aren't riding. */
    String ridingWith(Player player) {
        Ride ride = player == null ? null : rides.get(player.getUniqueId());
        return ride == null ? null : ride.pilotName();
    }

    /** The names of everyone flying right now (for tab completion and hints). */
    List<String> pilotNames() {
        List<String> names = new ArrayList<>();
        for (UUID id : sessions.keySet()) {
            Player pilot = Bukkit.getPlayer(id);
            if (pilot != null && pilot.isOnline()) {
                names.add(pilot.getName());
            }
        }
        return names;
    }

    /** How many passengers are aboard a pilot's ship. */
    int riderCount(UUID pilotId) {
        int count = 0;
        for (Ride ride : rides.values()) {
            if (ride.pilot().equals(pilotId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Takes off: builds the space world if needed, puts the pilot on the ship
     * and hands them the controls.
     *
     * @param destination the planet they set course for, or null for a free flight
     * @return false when the space world could not be prepared (the caller then
     *         uses the star chart's own launch instead)
     */
    boolean start(Player player, String destination) {
        // A fight they are still in, or a flight they have only just come back
        // from, keeps the ship on the ground - and they are told which it is.
        SpaceTravel travel = plugin.spaceTravel();
        if (travel != null && !travel.readyForFlight(player)) {
            return true; // handled: nothing else should take off for them
        }
        World world;
        try {
            world = space.ensureReady();
        } catch (RuntimeException e) {
            // Never leave the player staring at a menu that did nothing: the
            // star chart's own launch is always there as a way to travel.
            plugin.getLogger().warning("Could not prepare the space world: " + e.getMessage());
            world = null;
        }
        if (world == null) {
            player.sendMessage(Component.text("Your ship's hangar isn't available - ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("using the quick launch instead.").color(NamedTextColor.RED)));
            return false;
        }
        Location start = space.shipSpawn();
        if (start == null) {
            return false;
        }
        stop(player, false); // a second take-off just resets the session
        stepOff(player);     // and nobody pilots a ship from somebody else's deck

        Location returnTo = player.getLocation().clone();
        float flySpeed = player.getFlySpeed();
        boolean couldFly = player.getAllowFlight();

        player.closeInventory();
        player.teleport(start);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setFlySpeed((float) space.cruiseSpeed());

        ShipModel ship = ShipModel.spawn(plugin, player, world, player.getName(), destination);
        if (ship.isEmpty()) {
            // The real boat is the flight: without one there is no ship to take up.
            ship.remove();
            player.setFlying(false);
            player.setAllowFlight(couldFly);
            player.setFlySpeed(flySpeed);
            plugin.getLogger().warning("Could not build the ship for " + player.getName()
                    + " - the take-off was called off.");
            player.sendMessage(Component.text("\uD83D\uDE80 Your ship wouldn't launch - ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("try again in a moment.").color(NamedTextColor.RED)));
            return true; // handled: there is nothing to fly in
        }
        sessions.put(player.getUniqueId(), new Session(destination, returnTo, flySpeed, couldFly, ship));
        if (travel != null) {
            travel.markFlight(player); // and the wait to the next flight starts here
        }

        player.showTitle(Title.title(
                Component.text("\uD83D\uDE80 Take off").color(NamedTextColor.AQUA),
                Component.text(destination == null
                        ? "Fly to a planet and sneak into its ring"
                        : "Course set - fly to the ring and sneak to dock").color(NamedTextColor.GRAY)));
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0f, 0.7f);
        player.sendMessage(Component.text("\uD83D\uDE80 Throttle: ").color(NamedTextColor.GRAY)
                .append(Component.text("look where you want to go").color(NamedTextColor.AQUA))
                .append(Component.text(", ").color(NamedTextColor.GRAY))
                .append(Component.text("sprint to go faster").color(NamedTextColor.AQUA))
                .append(Component.text(", ").color(NamedTextColor.GRAY))
                .append(Component.text("sneak in a planet's ring to dock").color(NamedTextColor.AQUA))
                .append(Component.text(". Type ").color(NamedTextColor.GRAY))
                .append(Component.text("/ship leave").color(NamedTextColor.AQUA))
                .append(Component.text(" to fly home.").color(NamedTextColor.GRAY)));
        return true;
    }

    /** Left the pilot's seat: flight handed back, optionally flown home. */
    void stop(Player player, boolean flyHome) {
        Session session = endSession(player, true);
        if (session == null) {
            return;
        }
        // Everyone aboard has lost their ride: they go back where they boarded.
        dismissRiders(player.getUniqueId(), "\uD83C\uDFC1 " + player.getName()
                + "'s ship came down - you're back where you boarded.");
        if (flyHome && session.returnTo() != null) {
            Location home = session.returnTo();
            if (home.getWorld() != null && Bukkit.getWorld(home.getWorld().getName()) != null) {
                player.teleport(home);
                player.sendMessage(Component.text("\uD83C\uDFE0 Flew home.").color(NamedTextColor.GRAY));
            } else {
                Location ship = space.shipSpawn();
                if (ship != null) {
                    player.teleport(ship);
                }
            }
        }
    }

    /**
     * Ends every flight when the plugin goes down, so no boat is left floating in
     * the sky and nobody keeps flight they were only lent for the trip.
     */
    void shutdown() {
        for (UUID id : List.copyOf(sessions.keySet())) {
            Session session = sessions.remove(id);
            if (session == null) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                player.setFlying(false);
                player.setAllowFlight(session.couldFly());
                player.setFlySpeed(session.flySpeed());
            }
            if (session.ship() != null) {
                session.ship().remove();
            }
        }
        for (Map.Entry<UUID, Ride> entry : List.copyOf(rides.entrySet())) {
            Player rider = Bukkit.getPlayer(entry.getKey());
            if (rider != null && rider.isOnline()) {
                release(new Rider(rider, entry.getValue()));
            }
        }
        rides.clear();
        landing.clear();
    }

    /**
     * Ends a flight. Flight is handed back unless the player is still on the way
     * down - a landing keeps them airborne until the planet has taken them.
     */
    private Session endSession(Player player, boolean restoreFlight) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return null;
        }
        if (session.ship() != null) {
            session.ship().remove();
        }
        if (restoreFlight) {
            player.setFlying(false);
            player.setAllowFlight(session.couldFly());
            player.setFlySpeed(session.flySpeed());
        }
        // The flight is over, so nothing is still waiting to land them.
        landing.remove(player.getUniqueId());
        PlanetTravel.cancelPendingTeleport(player);
        return session;
    }

    /** Flies home from the cockpit - or steps off, when they are only a passenger. */
    void flyHome(Player player) {
        if (isRiding(player)) {
            leaveRide(player);
            return;
        }
        if (!isPiloting(player)) {
            player.sendMessage(Component.text("You aren't flying right now.").color(NamedTextColor.GRAY));
            return;
        }
        stop(player, true);
    }

    // ── Riding along ────────────────────────────────────────────────────

    /**
     * Boards somebody else's ship: the passenger is lifted into space beside the
     * pilot and carried along until the pilot docks (or they step off).
     *
     * @param pilotName who to fly with, or null to board when only one ship is out
     * @return true when the passenger is aboard
     */
    boolean ride(Player passenger, String pilotName) {
        if (space == null || !space.enabled()) {
            passenger.sendMessage(Component.text("Space travel is switched off on this server.")
                    .color(NamedTextColor.RED));
            return false;
        }
        if (space.maxRiders() <= 0) {
            passenger.sendMessage(Component.text("Nobody rides along on this server - ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("everyone flies their own ship.").color(NamedTextColor.RED)));
            return false;
        }
        if (isPiloting(passenger)) {
            passenger.sendMessage(Component.text("You're flying your own ship - ")
                    .color(NamedTextColor.GRAY)
                    .append(Component.text("/ship leave").color(NamedTextColor.AQUA))
                    .append(Component.text(" first if you'd rather be a passenger.").color(NamedTextColor.GRAY)));
            return false;
        }
        Player pilot = resolvePilot(passenger, pilotName);
        if (pilot == null) {
            return false;
        }
        if (landing.containsKey(pilot.getUniqueId())) {
            passenger.sendMessage(Component.text(pilot.getName() + " is landing right now - ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("catch them on the next flight.").color(NamedTextColor.RED)));
            return false;
        }
        Ride current = rides.get(passenger.getUniqueId());
        if (current != null && current.pilot().equals(pilot.getUniqueId())) {
            passenger.sendMessage(Component.text("You're already aboard " + pilot.getName()
                    + "'s ship.").color(NamedTextColor.GRAY));
            return false;
        }
        int seat = freeSeat(pilot.getUniqueId());
        if (seat < 0) {
            passenger.sendMessage(Component.text(pilot.getName() + "'s ship is full - ")
                    .color(NamedTextColor.RED)
                    .append(Component.text(String.valueOf(space.maxRiders())).color(NamedTextColor.YELLOW))
                    .append(Component.text(" passenger(s) is the limit.").color(NamedTextColor.RED)));
            return false;
        }
        // Boarding is a trip into space too, so it answers to the same gate - last,
        // so the real reason for a refusal is the one they hear.
        SpaceTravel travel = plugin.spaceTravel();
        if (travel != null && !travel.readyForFlight(passenger)) {
            return false;
        }
        stepOff(passenger); // boarding a second ship just moves them across
        Ride ride = new Ride(pilot.getUniqueId(), pilot.getName(), seat,
                passenger.getLocation().clone(), passenger.getFlySpeed(), passenger.getAllowFlight());
        passenger.closeInventory();
        passenger.teleport(seatLocation(pilot, seat));
        passenger.setAllowFlight(true);
        passenger.setFlying(true);
        passenger.setFlySpeed(RIDE_SPEED);
        rides.put(passenger.getUniqueId(), ride);
        if (travel != null) {
            travel.markFlight(passenger);
        }

        passenger.showTitle(Title.title(
                Component.text("\uD83E\uDDD1\u200D\uD83D\uDE80 Aboard " + pilot.getName() + "'s ship")
                        .color(NamedTextColor.AQUA),
                Component.text("You land wherever they dock").color(NamedTextColor.GRAY)));
        passenger.playSound(passenger.getLocation(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 1.4f);
        passenger.sendMessage(Component.text("\uD83E\uDDD1\u200D\uD83D\uDE80 Riding with ").color(NamedTextColor.GRAY)
                .append(Component.text(pilot.getName()).color(NamedTextColor.AQUA))
                .append(Component.text(" - look around all you like, the ship does the flying. Type ")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("/ship leave").color(NamedTextColor.AQUA))
                .append(Component.text(" to step off.").color(NamedTextColor.GRAY)));
        notifyPilot(pilot.getUniqueId(), "\uD83E\uDDD1\u200D\uD83D\uDE80 " + passenger.getName()
                + " is riding with you - land wherever you like.");
        return true;
    }

    /** A passenger gets off: flight handed back and home to where they boarded. */
    void leaveRide(Player passenger) {
        Ride ride = rides.get(passenger.getUniqueId());
        if (ride == null) {
            passenger.sendMessage(Component.text("You're not riding in anyone's ship.")
                    .color(NamedTextColor.GRAY));
            return;
        }
        stepOff(passenger);
        sendRiderHome(passenger, ride, "\uD83C\uDFC1 Stepped off " + ride.pilotName()
                + "'s ship - back where you boarded.");
    }

    /**
     * Invites a player aboard: they get a line in chat with a button that boards
     * them. The button simply runs {@code /ship ride}, so everything is still
     * checked when it is clicked - a ship that has landed, filled up, or started
     * docking since the invitation says so rather than doing something odd.
     */
    boolean invite(Player pilot, String name) {
        if (!isPiloting(pilot)) {
            pilot.sendMessage(Component.text("You're not flying - ").color(NamedTextColor.GRAY)
                    .append(Component.text("/ship fly").color(NamedTextColor.AQUA))
                    .append(Component.text(" takes off, then you can invite riders.")
                            .color(NamedTextColor.GRAY)));
            return false;
        }
        if (landing.containsKey(pilot.getUniqueId())) {
            pilot.sendMessage(Component.text("You're docking right now - ").color(NamedTextColor.RED)
                    .append(Component.text("invite them on the next flight.").color(NamedTextColor.RED)));
            return false;
        }
        Player guest = name == null ? null : Bukkit.getPlayerExact(name);
        if (guest == null || !guest.isOnline()) {
            pilot.sendMessage(Component.text("Nobody called '").color(NamedTextColor.RED)
                    .append(Component.text(name == null ? "" : name).color(NamedTextColor.YELLOW))
                    .append(Component.text("' is online.").color(NamedTextColor.RED)));
            return false;
        }
        if (guest.getUniqueId().equals(pilot.getUniqueId())) {
            pilot.sendMessage(Component.text("That's you - you're the one flying it.")
                    .color(NamedTextColor.GRAY));
            return false;
        }
        if (isPiloting(guest)) {
            pilot.sendMessage(Component.text(guest.getName() + " is flying their own ship.")
                    .color(NamedTextColor.GRAY));
            return false;
        }
        if (isRiding(guest)) {
            pilot.sendMessage(Component.text(guest.getName() + " is already riding with someone.")
                    .color(NamedTextColor.GRAY));
            return false;
        }
        if (space.maxRiders() <= 0 || freeSeat(pilot.getUniqueId()) < 0) {
            pilot.sendMessage(Component.text("Your ship is full - ").color(NamedTextColor.RED)
                    .append(Component.text("someone has to step off before you can take another rider.")
                            .color(NamedTextColor.RED)));
            return false;
        }
        guest.sendMessage(Component.text("\uD83E\uDDD1\u200D\uD83D\uDE80 ").color(NamedTextColor.GRAY)
                .append(Component.text(pilot.getName()).color(NamedTextColor.AQUA))
                .append(Component.text(" invites you aboard their ship - you land wherever they dock.")
                        .color(NamedTextColor.GRAY)));
        guest.sendMessage(Component.text("   ")
                .append(Component.text("[\u25B6 Board the ship]").color(NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("Fly with " + pilot.getName())))
                        .clickEvent(ClickEvent.runCommand("/ship ride " + pilot.getName())))
                .append(Component.text("   or type ").color(NamedTextColor.DARK_GRAY))
                .append(Component.text("/ship ride " + pilot.getName()).color(NamedTextColor.AQUA)));
        guest.playSound(guest.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        pilot.sendMessage(Component.text("\uD83E\uDDD1\u200D\uD83D\uDE80 Invited ")
                .color(NamedTextColor.GRAY)
                .append(Component.text(guest.getName()).color(NamedTextColor.AQUA))
                .append(Component.text(" aboard - one click and they are on board.")
                        .color(NamedTextColor.GRAY)));
        return true;
    }

    /**
     * Finds who the passenger means: the named pilot, or - when no name is given -
     * the only ship that happens to be out.
     */
    private Player resolvePilot(Player passenger, String pilotName) {
        if (pilotName != null) {
            Player pilot = Bukkit.getPlayerExact(pilotName);
            if (pilot == null || !pilot.isOnline()) {
                passenger.sendMessage(Component.text("Nobody called '").color(NamedTextColor.RED)
                        .append(Component.text(pilotName).color(NamedTextColor.YELLOW))
                        .append(Component.text("' is online.").color(NamedTextColor.RED)));
                return null;
            }
            if (pilot.getUniqueId().equals(passenger.getUniqueId())) {
                passenger.sendMessage(Component.text("That's your own ship - ").color(NamedTextColor.GRAY)
                        .append(Component.text("/ship fly").color(NamedTextColor.AQUA))
                        .append(Component.text(" takes off in it.").color(NamedTextColor.GRAY)));
                return null;
            }
            if (!isPiloting(pilot)) {
                passenger.sendMessage(Component.text(pilot.getName() + " isn't flying right now - ")
                        .color(NamedTextColor.RED)
                        .append(Component.text("ask them to take off first.").color(NamedTextColor.RED)));
                return null;
            }
            return pilot;
        }
        List<Player> flying = new ArrayList<>();
        for (UUID id : sessions.keySet()) {
            Player out = Bukkit.getPlayer(id);
            if (out != null && out.isOnline() && !out.getUniqueId().equals(passenger.getUniqueId())) {
                flying.add(out);
            }
        }
        if (flying.isEmpty()) {
            passenger.sendMessage(Component.text("Nobody else is flying right now - ").color(NamedTextColor.GRAY)
                    .append(Component.text("/ship fly").color(NamedTextColor.AQUA))
                    .append(Component.text(" takes off in your own ship.").color(NamedTextColor.GRAY)));
            return null;
        }
        if (flying.size() > 1) {
            StringBuilder names = new StringBuilder();
            for (Player out : flying) {
                names.append(names.isEmpty() ? "" : ", ").append(out.getName());
            }
            passenger.sendMessage(Component.text("More than one ship is out: ").color(NamedTextColor.GRAY)
                    .append(Component.text(names.toString()).color(NamedTextColor.AQUA))
                    .append(Component.text(" - pick one with ").color(NamedTextColor.GRAY))
                    .append(Component.text("/ship ride <player>").color(NamedTextColor.AQUA))
                    .append(Component.text(".").color(NamedTextColor.GRAY)));
            return null;
        }
        return flying.get(0);
    }

    /** The first free seat beside a pilot, or -1 when the ship is full. */
    private int freeSeat(UUID pilotId) {
        boolean[] taken = new boolean[space.maxRiders()];
        for (Ride ride : rides.values()) {
            if (ride.pilot().equals(pilotId) && ride.seat() >= 0 && ride.seat() < taken.length) {
                taken[ride.seat()] = true;
            }
        }
        for (int seat = 0; seat < taken.length; seat++) {
            if (!taken[seat]) {
                return seat;
            }
        }
        return -1;
    }

    /** Where a seat sits: off to one side of the pilot, so everyone is visible. */
    private Location seatLocation(Player pilot, int seat) {
        Location spot = pilot.getLocation().clone();
        Vector forward = spot.getDirection().setY(0);
        if (forward.lengthSquared() < 1.0E-4) {
            forward = new Vector(0, 0, 1);
        }
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        double offset = SEAT_OFFSETS[Math.min(seat, SEAT_OFFSETS.length - 1)];
        return spot.add(side.multiply(offset)).add(0, 0.8, 0);
    }

    /** Holds every passenger beside their ship: the ride belongs to the pilot. */
    private void followRiders() {
        if (rides.isEmpty()) {
            return;
        }
        for (UUID id : List.copyOf(rides.keySet())) {
            Ride ride = rides.get(id);
            if (ride == null) {
                continue;
            }
            Player rider = Bukkit.getPlayer(id);
            if (rider == null || !rider.isOnline() || rider.isDead()) {
                rides.remove(id); // gone: there is nothing of theirs left to hand back
                continue;
            }
            Player pilot = Bukkit.getPlayer(ride.pilot());
            if (pilot == null || !pilot.isOnline() || !isPiloting(pilot)) {
                // The ship is gone (the pilot logged out or the flight ended):
                // step off safely rather than leave them hanging in the sky.
                rides.remove(id);
                release(new Rider(rider, ride));
                sendRiderHome(rider, ride, "\uD83C\uDFC1 The ship came down - you're back where you boarded.");
                continue;
            }
            if (!space.isSpaceWorld(rider.getWorld())) {
                // Something else moved them: the ride is over, they are somewhere real.
                stepOff(rider);
                continue;
            }
            Location seatSpot = seatLocation(pilot, ride.seat());
            Location where = rider.getLocation();
            // Only the position follows the ship: the passenger keeps their own view.
            seatSpot.setYaw(where.getYaw());
            seatSpot.setPitch(where.getPitch());
            if (where.distanceSquared(seatSpot) > 0.04) {
                rider.teleport(seatSpot);
            }
            if (!rider.getAllowFlight()) {
                rider.setAllowFlight(true);
            }
            if (!rider.isFlying()) {
                rider.setFlying(true);
            }
            rider.setFlySpeed(RIDE_SPEED);
            rider.sendActionBar(rideBar(pilot, ride));
        }
    }

    /** What a passenger sees while flying: who they are with, and where the ship is. */
    private Component rideBar(Player pilot, Ride ride) {
        Component bar = Component.text("\uD83E\uDDD1\u200D\uD83D\uDE80 Riding with " + ride.pilotName())
                .color(NamedTextColor.AQUA);
        SpaceWorld.Pad pad = nearestPad(pilot.getLocation());
        if (pad != null && pad.center().distance(pilot.getLocation()) > space.dockRadius()) {
            bar = bar.append(Component.text("  " + pad.label() + " ").color(NamedTextColor.GRAY))
                    .append(Component.text(Math.round(pad.center().distance(pilot.getLocation())) + "m")
                            .color(NamedTextColor.GRAY));
        }
        return bar.append(Component.text("   /ship leave").color(NamedTextColor.DARK_GRAY));
    }

    /**
     * Ends a ride quietly: the passenger is leaving under their own steam, so
     * there is nowhere to teleport them - just flight handed back and the pilot
     * told they are gone.
     */
    private void stepOff(Player passenger) {
        Ride ride = rides.remove(passenger.getUniqueId());
        if (ride == null) {
            return;
        }
        landing.remove(passenger.getUniqueId());
        PlanetTravel.cancelPendingTeleport(passenger);
        release(new Rider(passenger, ride));
        notifyPilot(ride.pilot(), "\uD83E\uDDD1\u200D\uD83D\uDE80 " + passenger.getName()
                + " stepped off your ship.");
    }

    /**
     * Takes every passenger off a pilot's ship, leaving them hovering where the
     * ship was: the caller decides where they end up and then releases them.
     */
    private List<Rider> detachRiders(UUID pilotId) {
        List<Rider> taken = new ArrayList<>();
        for (Map.Entry<UUID, Ride> entry : List.copyOf(rides.entrySet())) {
            Ride ride = entry.getValue();
            if (!ride.pilot().equals(pilotId)) {
                continue;
            }
            rides.remove(entry.getKey());
            Player rider = Bukkit.getPlayer(entry.getKey());
            if (rider == null || !rider.isOnline()) {
                continue;
            }
            // Whatever they were waiting to land on, they are not landing now.
            landing.remove(entry.getKey());
            PlanetTravel.cancelPendingTeleport(rider);
            rider.setAllowFlight(true);
            rider.setFlying(true);
            rider.setFlySpeed(RIDE_SPEED);
            taken.add(new Rider(rider, ride));
        }
        return taken;
    }

    /** Hands a passenger their own flight settings back. */
    private void release(Rider rider) {
        Player player = rider.player();
        if (!player.isOnline()) {
            return;
        }
        player.setFlying(false);
        player.setAllowFlight(rider.ride().couldFly());
        player.setFlySpeed(rider.ride().flySpeed());
    }

    /** Ends every ride on a pilot's ship, putting each passenger ashore. */
    private void dismissRiders(UUID pilotId, String reason) {
        for (Rider rider : detachRiders(pilotId)) {
            release(rider);
            if (space.isSpaceWorld(rider.player().getWorld())) {
                sendRiderHome(rider.player(), rider.ride(), reason);
            }
            // else: they were set down with the pilot - nothing to undo.
        }
    }

    /** Puts a passenger back where they boarded (main world if that world is gone). */
    private void sendRiderHome(Player rider, Ride ride, String reason) {
        if (!rider.isOnline()) {
            return;
        }
        Location home = ride.returnTo();
        if (home != null && home.getWorld() != null && Bukkit.getWorld(home.getWorld().getName()) != null) {
            rider.teleport(home);
        } else if (!Bukkit.getWorlds().isEmpty()) {
            rider.teleport(Bukkit.getWorlds().get(0).getSpawnLocation());
        }
        rider.sendMessage(Component.text(reason).color(NamedTextColor.GRAY));
    }

    private void notifyPilot(UUID pilotId, String message) {
        Player pilot = Bukkit.getPlayer(pilotId);
        if (pilot != null && pilot.isOnline()) {
            pilot.sendMessage(Component.text(message).color(NamedTextColor.GRAY));
        }
    }

    // ── Flight ──────────────────────────────────────────────────────────

    private void tick() {
        engineTick++;
        for (UUID id : List.copyOf(sessions.keySet())) {
            Session session = sessions.get(id);
            Player player = Bukkit.getPlayer(id);
            if (session == null || player == null || !player.isOnline() || player.isDead()) {
                // Logged out or worse: keep the session's flight settings tidy.
                if (player != null) {
                    player.setAllowFlight(session != null && session.couldFly());
                    player.setFlySpeed(session != null ? session.flySpeed() : 0.05f);
                }
                // The session goes first, so the ship is free to let its pilot
                // out of the seat it was holding them in.
                sessions.remove(id);
                if (session != null && session.ship() != null) {
                    session.ship().remove();
                }
                landing.remove(id);
                // The ship goes with the pilot, so the passengers are put ashore.
                dismissRiders(id, "\uD83C\uDFC1 The ship came down - you're back where you boarded.");
                continue;
            }
            World world = player.getWorld();
            if (!space.isSpaceWorld(world)) {
                // Something else moved them out (a teleport): quietly land the ship.
                stop(player, false);
                continue;
            }
            ShipModel model = session.ship();
            if (model != null) {
                model.ensureBoat(plugin, player); // a lost boat is rebuilt on the spot
            }
            Boat boat = model == null ? null : model.boat();
            if (boat != null) {
                model.seat(player); // a sneak can lift the pilot out; put them back
                // The void is not a trap: drop out of the sky and the boat comes back.
                Location mark = space.shipSpawn();
                if (mark != null && player.getLocation().getY() < mark.getY() - VOID_LEASH) {
                    Location back = mark.clone();
                    back.setYaw(player.getLocation().getYaw());
                    back.setPitch(0);
                    boat.setVelocity(new Vector());
                    boat.teleport(back);
                }
                fly(player, boat); // the throttle, the steering and the hull's heading
                model.follow();    // the plate and the banner ride with the boat
            } else {
                // No boat at all: the pilot keeps their own flight until it is back.
                player.setFlySpeed((float) (player.isSprinting()
                        ? space.sprintSpeed() : space.cruiseSpeed()));
            }

            if (engineTick % 2 == 0) { // every couple of ticks: the thrusters
                Location behind = player.getLocation().clone()
                        .subtract(player.getLocation().getDirection().multiply(1.6))
                        .add(0, 0.6, 0);
                player.getWorld().spawnParticle(Particle.CLOUD, behind, 6, 0.2, 0.2, 0.2, 0.02);
                player.getWorld().spawnParticle(Particle.END_ROD, behind, 4, 0.15, 0.15, 0.15, 0.01);
            }
            if (engineTick % 20 == 0) { // once a second: the engine hum
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 0.35f, 1.6f);
            }

            int riders = riderCount(id);
            String docking = landing.get(id);
            if (docking != null) {
                // On the way down: hold the sky and hold still until the planet lands us.
                player.sendActionBar(Component.text("\uD83D\uDE80 Docking at " + docking)
                        .color(NamedTextColor.AQUA)
                        .append(Component.text("  -  hold still to land").color(NamedTextColor.GREEN))
                        .append(riderBadge(riders))
                        .append(Component.text("   /ship leave").color(NamedTextColor.DARK_GRAY)));
                continue;
            }
            SpaceWorld.Pad pad = nearestPad(player.getLocation());
            if (pad == null) {
                player.sendActionBar(Component.text("\uD83D\uDE80 Cruising - fly towards a glowing pad")
                        .color(NamedTextColor.GRAY)
                        .append(riderBadge(riders))
                        .append(Component.text("   /ship leave").color(NamedTextColor.DARK_GRAY)));
                continue;
            }
            double distance = pad.center().distance(player.getLocation());
            if (distance > space.dockRadius()) {
                player.sendActionBar(Component.text("\uD83D\uDE80 " + pad.label()).color(NamedTextColor.AQUA)
                        .append(Component.text("  " + Math.round(distance) + "m  ").color(NamedTextColor.GRAY))
                        .append(Component.text(pad.sublabel()).color(NamedTextColor.DARK_GRAY))
                        .append(riderBadge(riders)));
                continue;
            }
            player.sendActionBar(Component.text("\uD83D\uDE80 Docking range: ").color(NamedTextColor.GREEN)
                    .append(Component.text(pad.label()).color(NamedTextColor.YELLOW))
                    .append(Component.text("  -  sneak to land").color(NamedTextColor.GREEN))
                    .append(riderBadge(riders))
                    .append(Component.text("   (" + pad.sublabel() + ")").color(NamedTextColor.DARK_GRAY)));
            if (isSneaking(player)) {
                dock(player, pad);
            }
        }
    }

    // ── The boat ────────────────────────────────────────────────────────

    /**
     * Flies the boat: the pilot's input steers it, the config sets the pace, and
     * the hull turns to face the way the pilot is looking. The boat's movement
     * belongs to its first passenger, which is the invisible marker - so the
     * plugin moves the ship itself, at the pace the config asks for rather than
     * at a rowboat's own speed.
     *
     * <p>The ship is flown by <b>teleporting</b> it one step a tick, not by
     * setting its velocity. A boat's own physics multiply every velocity it is
     * handed (its ground friction does not care that it is flying), so a
     * velocity-driven ship stuttered along at the client — fast, slow, fast —
     * exactly frame by frame, and its hull snapped rather than turned. A
     * teleport lands exactly where it should every tick, and with the boat's
     * teleport duration at one tick ({@code setTeleportDuration(1)}, set in
     * {@link ShipModel}) the client interpolates the step, which reads as one
     * smooth glide: exact speed, instant turning, no physics fighting back.
     */
    private void fly(Player player, Boat boat) {
        if (landing.containsKey(player.getUniqueId())) {
            // Docking: the ship holds still - that is what the planet is waiting for.
            boat.setVelocity(new Vector());
            return;
        }
        Input input = player.getCurrentInput();
        boolean sprinting = input != null && input.isSprint();
        double speed = sprinting ? space.sprintSpeed() : space.cruiseSpeed();
        // The pilot's own flight keeps the same pace, for the moment they step off.
        player.setFlySpeed((float) speed);
        Vector wish = wish(player, input);
        Location next;
        if (wish.lengthSquared() == 0) {
            // No keys held: the ship holds where it is (without gravity there
            // is nothing pulling it, and zeroing the velocity keeps it true).
            next = boat.getLocation();
        } else {
            next = boat.getLocation().add(wish.normalize().multiply(speed * SPEED_SCALE));
        }
        next.setYaw(player.getLocation().getYaw()); // the hull always faces the pilot's view
        next.setPitch(0f);
        boat.setVelocity(new Vector()); // the physics engine stays out of the way
        boat.teleport(next);
    }

    /** The direction the pilot is asking for, from the keys they are holding. */
    private Vector wish(Player player, Input input) {
        Vector wish = new Vector();
        if (input == null) {
            return wish;
        }
        Vector look = player.getLocation().getDirection();
        if (input.isForward()) {
            wish.add(look); // where they look is where the bow points: looking up climbs
        }
        if (input.isBackward()) {
            wish.subtract(look);
        }
        if (input.isLeft() || input.isRight()) {
            Vector level = look.clone().setY(0);
            if (level.lengthSquared() < 1.0E-4) {
                level = new Vector(0, 0, 1);
            }
            level.normalize();
            Vector side = new Vector(-level.getZ(), 0, level.getX());
            if (input.isLeft()) {
                wish.subtract(side);
            }
            if (input.isRight()) {
                wish.add(side);
            }
        }
        if (input.isJump()) {
            wish.add(new Vector(0, 1, 0)); // jump climbs straight up
        }
        return wish;
    }

    /** Whether the pilot is holding sneak, from the input their client last sent. */
    private boolean isSneaking(Player player) {
        Input input = player.getCurrentInput();
        return input != null && input.isSneak();
    }

    /** Shows the pilot how many passengers are aboard, or nothing when flying solo. */
    private Component riderBadge(int riders) {
        return riders <= 0
                ? Component.empty()
                : Component.text("   \uD83E\uDDD1\u200D\uD83D\uDE80 " + riders).color(NamedTextColor.AQUA);
    }

    private SpaceWorld.Pad nearestPad(Location location) {
        SpaceWorld.Pad nearest = null;
        double best = Double.MAX_VALUE;
        for (SpaceWorld.Pad pad : space.pads()) {
            if (pad.center().getWorld() == null || !pad.center().getWorld().equals(location.getWorld())) {
                continue;
            }
            double distance = pad.center().distanceSquared(location);
            if (distance < best) {
                best = distance;
                nearest = pad;
            }
        }
        return nearest;
    }

    /**
     * Docks at a pad: asks the planet to take the pilot - and everyone riding with
     * them - down. The flight is not over until it actually does, so a refusal or a
     * request cancelled by flying out of the ring leaves the ship in the air with
     * the throttle still in the pilot's hands, free to line up and sneak again.
     */
    private void dock(Player player, SpaceWorld.Pad pad) {
        if (sessions.get(player.getUniqueId()) == null || landing.containsKey(player.getUniqueId())) {
            return; // not flying, or already on the way down
        }
        List<Player> party = new ArrayList<>();
        party.add(player);
        for (Map.Entry<UUID, Ride> entry : List.copyOf(rides.entrySet())) {
            if (!entry.getValue().pilot().equals(player.getUniqueId())) {
                continue;
            }
            Player rider = Bukkit.getPlayer(entry.getKey());
            if (rider != null && rider.isOnline()) {
                party.add(rider);
            }
        }
        for (Player member : party) {
            landing.put(member.getUniqueId(), pad.label());
            member.closeInventory();
            member.showTitle(Title.title(
                    Component.text("Docking").color(NamedTextColor.AQUA),
                    Component.text(pad.label()).color(NamedTextColor.GRAY)));
            member.playSound(member.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1.0f, 0.7f);
        }
        player.sendMessage(Component.text("\uD83D\uDE80 Docking at ").color(NamedTextColor.GREEN)
                .append(Component.text(pad.label()).color(NamedTextColor.YELLOW))
                .append(Component.text(party.size() > 1
                                ? " - hold still and the planet takes you and "
                                        + (party.size() - 1) + " passenger(s)."
                                : " - hold still and the planet takes you.")
                        .color(NamedTextColor.GREEN)));
        for (Player member : party) {
            if (member.equals(player)) {
                continue;
            }
            member.sendMessage(Component.text("\uD83D\uDE80 Docking at ").color(NamedTextColor.GREEN)
                    .append(Component.text(pad.label()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" - landing with " + player.getName() + ".")
                            .color(NamedTextColor.GREEN)));
        }

        // Landing is the plugin's own travel: safe spot, cooldowns, combat and
        // the planet's access rules all still apply (a locked planet refuses).
        Planet target = new Planet(pad.label(), pad.icon(), pad.worldKey());
        long grace = PlanetTravel.teleportDelayTicks() + 5L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player member : party) {
                if (!member.isOnline() || !space.isSpaceWorld(member.getWorld())) {
                    continue; // they left the sky on their own: nothing to land
                }
                PlanetTravel.teleport(member, target);
                plugin.spaceTravel().watchLanding(member, target);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player member : party) {
                    if (landing.remove(member.getUniqueId()) == null) {
                        continue; // already resolved - they landed, or the ride ended
                    }
                    if (!member.isOnline() || !space.isSpaceWorld(member.getWorld())) {
                        continue; // the planet took them
                    }
                    // Still in the sky: the planet refused, or they flew out of the
                    // ring and cancelled it - either way they are flying again.
                    member.sendMessage(member.equals(player)
                            ? Component.text("\uD83D\uDE80 Docking didn't go through - ")
                                    .color(NamedTextColor.RED)
                                    .append(Component.text("you're still flying. Hold still in the ring and sneak again.")
                                            .color(NamedTextColor.RED))
                            : Component.text("\uD83D\uDE80 Docking didn't go through - ")
                                    .color(NamedTextColor.RED)
                                    .append(Component.text("still riding with " + player.getName() + ".")
                                            .color(NamedTextColor.RED)));
                }
            }, grace);
        }, 20L);
    }

    /**
     * Points a pilot at a planet's pad. Chat says which way it is and how far,
     * and a line of particles draws the route for the next ten seconds, so a
     * pad on the far side of the sky is findable without guessing. With no
     * name given, every pad is listed with its distance instead.
     *
     * @return true when a pad was named and the trail is drawn
     */
    boolean find(Player player, String query) {
        if (!isPiloting(player)) {
            player.sendMessage(Component.text("You aren't flying - ").color(NamedTextColor.GRAY)
                    .append(Component.text("/ship fly").color(NamedTextColor.AQUA))
                    .append(Component.text(" takes off first.").color(NamedTextColor.GRAY)));
            return false;
        }
        List<SpaceWorld.Pad> pads = space.pads();
        if (pads.isEmpty()) {
            player.sendMessage(Component.text("There are no planets to find yet.")
                    .color(NamedTextColor.RED));
            return false;
        }
        if (query == null || query.isBlank()) {
            player.sendMessage(Component.text("\uD83E\uDDED Planets in the sky:").color(NamedTextColor.AQUA));
            List<SpaceWorld.Pad> sorted = new ArrayList<>(pads);
            Location here = player.getLocation();
            sorted.sort(java.util.Comparator.comparingDouble(pad ->
                    pad.center().distanceSquared(here)));
            for (SpaceWorld.Pad pad : sorted) {
                double distance = pad.center().distance(here);
                player.sendMessage(Component.text("  \u2022 ").color(NamedTextColor.GRAY)
                        .append(Component.text(pad.label()).color(NamedTextColor.YELLOW))
                        .append(Component.text("  " + Math.round(distance) + "m")
                                .color(NamedTextColor.GRAY)));
            }
            player.sendMessage(Component.text("Aim one with ").color(NamedTextColor.GRAY)
                    .append(Component.text("/ship find <name>").color(NamedTextColor.AQUA))
                    .append(Component.text(" for a marked route.").color(NamedTextColor.GRAY)));
            return false;
        }
        SpaceWorld.Pad pad = matchingPad(pads, query);
        if (pad == null) {
            player.sendMessage(Component.text("No planet called '").color(NamedTextColor.RED)
                    .append(Component.text(query).color(NamedTextColor.YELLOW))
                    .append(Component.text("' is in the sky. ").color(NamedTextColor.RED))
                    .append(Component.text("/ship find").color(NamedTextColor.AQUA))
                    .append(Component.text(" lists them all.").color(NamedTextColor.GRAY)));
            return false;
        }
        Location here = player.getLocation();
        Location target = pad.center();
        double distance = here.distance(target);
        player.sendMessage(Component.text("\uD83E\uDDED ").color(NamedTextColor.AQUA)
                .append(Component.text(pad.label()).color(NamedTextColor.YELLOW))
                .append(Component.text(" is ").color(NamedTextColor.GRAY))
                .append(Component.text(Math.round(distance) + "m").color(NamedTextColor.AQUA))
                .append(Component.text(" away, ").color(NamedTextColor.GRAY))
                .append(Component.text(relativeDirection(here, target)).color(NamedTextColor.YELLOW))
                .append(Component.text(" — follow the trail.").color(NamedTextColor.GRAY)));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.6f);
        drawTrail(player, target);
        return true;
    }

    /** The pad whose label or world name starts with the query, ignoring case. */
    private static SpaceWorld.Pad matchingPad(List<SpaceWorld.Pad> pads, String query) {
        String wanted = query.trim().toLowerCase(Locale.ROOT);
        SpaceWorld.Pad prefix = null;
        for (SpaceWorld.Pad pad : pads) {
            String label = pad.label().toLowerCase(Locale.ROOT);
            String key = pad.worldKey().toLowerCase(Locale.ROOT);
            if (label.equals(wanted) || key.equals(wanted)) {
                return pad;
            }
            if ((label.startsWith(wanted) || key.startsWith(wanted)) && prefix == null) {
                prefix = pad;
            }
        }
        return prefix;
    }

    /** Where the pad sits relative to where the pilot is looking, in eighths. */
    private static String relativeDirection(Location from, Location to) {
        double yawToTarget = Math.toDegrees(Math.atan2(
                -(to.getX() - from.getX()), to.getZ() - from.getZ()));
        double relative = Math.floorMod(Math.round((yawToTarget - from.getYaw()) / 45.0), 8);
        return switch ((int) relative) {
            case 0 -> "straight ahead";
            case 1 -> "ahead, to your left";
            case 2 -> "to your left";
            case 3 -> "behind, to your left";
            case 4 -> "behind you";
            case 5 -> "behind, to your right";
            case 6 -> "to your right";
            default -> "ahead, to your right";
        };
    }

    /**
     * Draws a line of end-rod particles from the pilot to a pad, for the next
     * ten seconds or until they land — the route, marked in the sky.
     */
    private void drawTrail(Player player, Location target) {
        UUID id = player.getUniqueId();
        int[] ticks = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player pilot = Bukkit.getPlayer(id);
            if (pilot == null || !pilot.isOnline()
                    || !space.isSpaceWorld(pilot.getWorld())
                    || ticks[0] >= 100) {
                tasks.remove(id);
                return;
            }
            ticks[0]++;
            Location from = pilot.getLocation().add(0, 1, 0);
            Vector step = target.clone().toVector().subtract(from.toVector());
            if (step.lengthSquared() < 1) {
                return;
            }
            step.normalize().multiply(3);
            Location mark = from.clone();
            for (int i = 0; i < 20; i++) {
                pilot.getWorld().spawnParticle(Particle.END_ROD, mark, 1, 0, 0, 0, 0);
                mark = mark.add(step);
                if (!mark.getWorld().equals(target.getWorld())
                        || mark.distanceSquared(target) < 9) {
                    break;
                }
            }
        }, 1L, 2L);
        BukkitTask old = tasks.put(id, task);
        if (old != null) {
            old.cancel();
        }
    }

    /** How many players are flying right now. */
    int pilots() {
        return sessions.size();
    }

    /** The planet a pilot set course for, or null. */
    String destinationOf(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session == null || session.destination() == null
                ? null : session.destination().toLowerCase(Locale.ROOT);
    }
}
