package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the space world whole.
 *
 * <p>The ship, the planet pads and the labels that make them findable are the
 * plugin's own scenery, so players can't break, place, burn or blow any of it
 * up, and the ship's boat and the floating labels can't be mounted or damaged.
 * Nothing here stops a player from <em>flying</em>: the guard only refuses
 * changes to the world.
 *
 * <p>Editing has two scopes, because the pads and the ship are not the same kind
 * of thing. {@code /ship edit} is a working toggle - it lets an admin build on
 * and around the ship and in the open sky, but never on a planet's pad: those are
 * where everybody lands, so they stay strictly admin territory and need
 * {@code /ship pads} instead (or {@code space.admins-can-build: true} to lift the
 * protection for admins entirely). {@code /ship rebuild} is still the tidier way
 * to put everything back.
 */
public final class SpaceGuard implements Listener {

    /** How long between two "that's protected" notes, in millis. */
    private static final long WARN_INTERVAL = 2_500L;

    private final Planets plugin;
    private final Map<UUID, Long> lastWarning = new ConcurrentHashMap<>();

    /**
     * Admins who switched ship editing on with {@code /ship edit}. Space is solid
     * for everyone by default - including operators, who otherwise mine a hole in
     * a pad by accident while looking around - so editing is a deliberate toggle
     * that lasts until they switch it off again.
     */
    private final Set<UUID> editors = ConcurrentHashMap.newKeySet();

    /**
     * Admins who switched <em>pad</em> editing on with {@code /ship pads}. Kept
     * apart from {@link #editors} so an admin decorating the ship can't reshape a
     * planet's landing pad without meaning to.
     */
    private final Set<UUID> padEditors = ConcurrentHashMap.newKeySet();

    SpaceGuard(Planets plugin) {
        this.plugin = plugin;
    }

    /** Whether this world is the space world (and the feature is on). */
    private boolean inSpace(World world) {
        SpaceWorld space = plugin.spaceWorld();
        return space != null && space.enabled() && space.isSpaceWorld(world);
    }

    /**
     * Whether the player may change space at this spot right now. {@code /ship
     * pads} covers everything; {@code /ship edit} covers the ship and the open sky
     * but stops at a pad's edge.
     */
    private boolean mayEdit(Player player, Location where) {
        if (player == null) {
            return false;
        }
        UUID id = player.getUniqueId();
        if (padEditors.contains(id)) {
            return true; // an admin working on the pads
        }
        SpaceWorld space = plugin.spaceWorld();
        if (editors.contains(id)) {
            return space == null || !space.nearPad(where);
        }
        return space != null && space.adminsCanBuild() && plugin.canUseAdmin(player);
    }

    /** Switches ship editing on or off for an admin ({@code /ship edit}). */
    void toggleEditing(Player player) {
        if (editors.remove(player.getUniqueId())) {
            player.sendMessage(Component.text("\uD83D\uDE80 Space editing off - ")
                    .color(NamedTextColor.GRAY)
                    .append(Component.text("the ship, the pads and the sky are protected again.")
                            .color(NamedTextColor.GRAY)));
            return;
        }
        editors.add(player.getUniqueId());
        player.sendMessage(Component.text("\uD83D\uDE80 Space editing on - ")
                .color(NamedTextColor.GREEN)
                .append(Component.text("build on the ship and in the open sky until you run ")
                        .color(NamedTextColor.GREEN))
                .append(Component.text("/ship edit").color(NamedTextColor.AQUA))
                .append(Component.text(" again.").color(NamedTextColor.GREEN)));
        player.sendMessage(Component.text("\uD83D\uDE80 Planet pads stay protected - ")
                .color(NamedTextColor.GRAY)
                .append(Component.text("/ship pads").color(NamedTextColor.AQUA))
                .append(Component.text(" unlocks those.").color(NamedTextColor.GRAY)));
    }

    /** Switches planet-pad editing on or off for an admin ({@code /ship pads}). */
    void togglePadEditing(Player player) {
        if (padEditors.remove(player.getUniqueId())) {
            player.sendMessage(Component.text("\uD83D\uDE80 Pad editing off - ")
                    .color(NamedTextColor.GRAY)
                    .append(Component.text("every planet's pad is protected again.")
                            .color(NamedTextColor.GRAY)));
            return;
        }
        padEditors.add(player.getUniqueId());
        player.sendMessage(Component.text("\uD83D\uDE80 Pad editing on - ")
                .color(NamedTextColor.GREEN)
                .append(Component.text("you can change the landing pads until you run ")
                        .color(NamedTextColor.GREEN))
                .append(Component.text("/ship pads").color(NamedTextColor.AQUA))
                .append(Component.text(" again.").color(NamedTextColor.GREEN)));
    }

    /** Whether this admin is building around the ship right now. */
    boolean isEditing(Player player) {
        return player != null && editors.contains(player.getUniqueId());
    }

    /** Whether this admin is building on the pads right now. */
    boolean isEditingPads(Player player) {
        return player != null && padEditors.contains(player.getUniqueId());
    }

    /** Whether the player can be told off again yet (shared by the warnings). */
    private boolean mayWarn(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last != null && now - last < WARN_INTERVAL) {
            return false;
        }
        lastWarning.put(player.getUniqueId(), now);
        return true;
    }

    /** Tells a player space is protected, without spamming them. */
    private void warn(Player player) {
        if (!mayWarn(player)) {
            return;
        }
        Component line = Component.text("\uD83D\uDE80 Space is protected - ")
                .color(NamedTextColor.RED)
                .append(Component.text("fly it, don't dig it.").color(NamedTextColor.RED));
        if (plugin.canUseAdmin(player)) {
            line = line.append(Component.newline())
                    .append(Component.text("Admin: run ").color(NamedTextColor.DARK_GRAY))
                    .append(Component.text("/ship edit").color(NamedTextColor.AQUA))
                    .append(Component.text(" to build up here.").color(NamedTextColor.DARK_GRAY));
        }
        player.sendMessage(line);
    }

    /** Tells a player the pads are an admin's to change, without spamming them. */
    private void warnPads(Player player) {
        if (!mayWarn(player)) {
            return;
        }
        Component line = Component.text("\uD83D\uDE80 Planet pads are admin-only - ")
                .color(NamedTextColor.RED)
                .append(Component.text("everyone lands on these, so /ship edit stops at the pad's edge. ")
                        .color(NamedTextColor.RED));
        if (plugin.canUseAdmin(player)) {
            line = line.append(Component.newline())
                    .append(Component.text("Admin: run ").color(NamedTextColor.DARK_GRAY))
                    .append(Component.text("/ship pads").color(NamedTextColor.AQUA))
                    .append(Component.text(" to work on them.").color(NamedTextColor.DARK_GRAY));
        }
        player.sendMessage(line);
    }

    /** Warns about the right thing: a pad has its own reason for being locked. */
    private void warn(Player player, Location where) {
        SpaceWorld space = plugin.spaceWorld();
        if (space != null && space.nearPad(where)) {
            warnPads(player);
        } else {
            warn(player);
        }
    }

    // ── Building ────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Location where = event.getBlock().getLocation();
        if (!inSpace(where.getWorld()) || mayEdit(event.getPlayer(), where)) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer(), where);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Location where = event.getBlockPlaced().getLocation();
        if (!inSpace(where.getWorld()) || mayEdit(event.getPlayer(), where)) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer(), where);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Location where = event.getBlock().getLocation();
        if (!inSpace(where.getWorld()) || mayEdit(event.getPlayer(), where)) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer(), where);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Location where = event.getBlock().getLocation();
        if (!inSpace(where.getWorld()) || mayEdit(event.getPlayer(), where)) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer(), where);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Location where = event.getBlock().getLocation();
        if (!inSpace(where.getWorld()) || mayEdit(event.getPlayer(), where)) {
            return;
        }
        event.setCancelled(true);
        if (event.getPlayer() != null) {
            warn(event.getPlayer(), where);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Location where = event.getEntity().getLocation();
        if (!inSpace(where.getWorld()) || mayEdit(event.getPlayer(), where)) {
            return;
        }
        event.setCancelled(true);
        if (event.getPlayer() != null) {
            warn(event.getPlayer(), where);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Location where = event.getBlock().getLocation();
        if (!inSpace(where.getWorld())) {
            return;
        }
        if (event.getEntity() instanceof Player player && mayEdit(player, where)) {
            return; // an admin at work
        }
        event.setCancelled(true);
    }

    // ── Explosions ──────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (inSpace(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (inSpace(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    // ── The plugin's own things (pads, labels and the ships) ────────────

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        // The pads' labels are displays; a ship is a real boat, now. Neither is
        // a player's to right-click up here - riding along goes through /ship ride.
        if (!(entity instanceof Display) && !(entity instanceof Boat)) {
            return;
        }
        if (!inSpace(entity.getWorld())) {
            return;
        }
        if (!mayEdit(event.getPlayer(), entity.getLocation())) {
            event.setCancelled(true);
            warn(event.getPlayer(), entity.getLocation());
        }
    }

    // ── A sky full of players ───────────────────────────────────────────

    /**
     * Space is a place to fly, not to fight. Nothing up here takes damage - no
     * falls, no void, no drowning, no fire and no PvP - so a sky with several
     * ships in it stays friendly: nobody loses a ship, their things or their
     * evening to someone else's throttle. Cancelling it before any other plugin
     * looks at the damage also means nothing there starts a combat tag.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (inSpace(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    /** Nothing spawns in the sky either: it belongs to the pilots. */
    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (inSpace(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
    }
}
