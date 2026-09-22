package me.foivos.planets;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Set;

/**
 * Enforces the per-planet settings toggles from /myp → Settings so they have
 * real in-game effects instead of only saving to my-planets.yml:
 * <ul>
 *   <li>{@code PvP} — players (and player-lit TNT / projectiles) can't hurt
 *       other players on the planet.</li>
 *   <li>{@code Build} — nobody can break or place blocks when OFF.</li>
 *   <li>{@code Mob Spawning} — natural/Spawner creature spawns are blocked
 *       only while the toggle is OFF; turning it ON works too.</li>
 *   <li>{@code Explosions} — entity AND block explosions don't damage blocks.</li>
 *   <li>{@code Fire Spread} — fire doesn't spread to other blocks.</li>
 *   <li>{@code Item Drops} — players can't drop/throw items (Q key, death
 *       drops kept in inventory) or pick items up when OFF.</li>
 *   <li>{@code Structures} — nothing grows into a structure (trees, giant
 *       mushrooms, ...) while OFF, and freshly generated terrain skips
 *       villages/ruins too.</li>
 *   <li>{@code Visitor Access} — players who aren't members can't teleport in.</li>
 *   <li>{@code Public} — only members can enter at all when set to private.</li>
 * </ul>
 * Ownership/planets data is read live on every event, so toggling a setting
 * takes effect immediately without a restart.
 */
public final class PlanetSettingsGuard implements Listener {

    private final Planets plugin;

    public PlanetSettingsGuard(Planets plugin) {
        this.plugin = plugin;
    }

    // ── PvP ────────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        MyPlanetData data = plugin.getMyPlanetManager().get(victim.getWorld().getName());
        if (data == null || data.pvpEnabled()) return;
        event.setCancelled(true);
        if (attacker.isOnline()) {
            attacker.sendMessage(net.kyori.adventure.text.Component.text("PvP is disabled on this planet.")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    /**
     * Block explosions (beds, respawn anchors...) respect the Explosions
     * toggle just like entity explosions do.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        MyPlanetData data = plugin.getMyPlanetManager().get(event.getBlock().getWorld().getName());
        if (data == null) return;
        if (!data.explosions() && !event.blockList().isEmpty()) {
            event.blockList().clear();
        }
    }

    /** Unwraps TNT and projectile shooters down to the responsible player. */
    private static Player resolveAttacker(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt) {
            return tnt.getSource() instanceof Player shooter ? shooter : null;
        }
        if (damager instanceof org.bukkit.entity.Projectile projectile) {
            return projectile.getShooter() instanceof Player shooter ? shooter : null;
        }
        return null;
    }

    // ── Build ──────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        MyPlanetData data = plugin.getMyPlanetManager().get(event.getBlock().getWorld().getName());
        if (data == null) return;
        if (!data.buildEnabled()) {
            // When building is OFF, nobody (not even members) may break blocks.
            event.setCancelled(true);
            player.sendMessage(net.kyori.adventure.text.Component.text("Building is disabled on this planet.")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        // Decrement block count when a placed block is broken.
        data.decrementBlockCount(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        MyPlanetData data = plugin.getMyPlanetManager().get(event.getBlock().getWorld().getName());
        if (data == null) return;
        if (!data.buildEnabled()) {
            // When building is OFF, nobody (not even members) may place blocks.
            event.setCancelled(true);
            player.sendMessage(net.kyori.adventure.text.Component.text("Building is disabled on this planet.")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        // Block Limitations: check the planet-wide placement cap (shared by all players).
        if (data.blockLimitReached()) {
            event.setCancelled(true);
            // Warn once per "trip" over the limit (in chat, planet-wide),
            // not on every blocked placement attempt.
            if (data.shouldWarnBlockLimit()) {
                int limit = data.blockLimit();
                String message = "\u26A0 Planet block limit reached! " + data.displayName()
                        + " allows at most " + limit + " placed blocks."
                        + " Break placed blocks, or the owner can reset counts with /myp resetblockcounts.";
                for (Player online : player.getWorld().getPlayers()) {
                    online.sendMessage(net.kyori.adventure.text.Component.text(message)
                            .color(net.kyori.adventure.text.format.NamedTextColor.RED));
                }
            }
            return;
        }
        // Increment block count on successful placement.
        data.incrementBlockCount(player.getUniqueId());
    }

    // ── Mob Spawning ───────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        MyPlanetData data = plugin.getMyPlanetManager().get(event.getEntity().getWorld().getName());
        if (data == null) return;
        if (data.mobSpawning()) return; // toggle ON → normal vanilla spawning
        // Toggle OFF — block natural gameplay spawns only, so commands,
        // spawn eggs and custom-plugin spawns keep working.
        switch (event.getSpawnReason()) {
            case NATURAL, REINFORCEMENTS, PATROL, RAID, SPAWNER, BREEDING -> event.setCancelled(true);
            default -> { }
        }
    }

    // ── Explosions ─────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        MyPlanetData data = plugin.getMyPlanetManager().get(event.getEntity() != null
                ? event.getEntity().getWorld().getName() : "");
        if (data == null) return;
        if (!data.explosions() && !event.blockList().isEmpty()) {
            event.blockList().clear(); // keep the explosion, protect the blocks
        }
    }

    // ── Fire Spread ────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        MyPlanetData data = plugin.getMyPlanetManager().get(event.getBlock().getWorld().getName());
        if (data == null || data.fireSpread()) return;
        if (event.getCause() == BlockIgniteEvent.IgniteCause.SPREAD
                || event.getCause() == BlockIgniteEvent.IgniteCause.LAVA) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFireSpread(BlockSpreadEvent event) {
        MyPlanetData data = plugin.getMyPlanetManager().get(event.getBlock().getWorld().getName());
        if (data == null || data.fireSpread()) return;
        if (event.getSource().getType() == org.bukkit.Material.FIRE) {
            event.setCancelled(true);
        }
    }

    // ── Item Drops ─────────────────────────────────────────────────────

    /**
     * When "Item Drops" is OFF, players on the planet can't drop or throw
     * items (Q key / dragging out of the inventory), can't pick items up,
     * and keep everything in their inventory on death (no death drops).
     */
    /** Structures OFF: no tree/mushroom growth (fresh terrain also skips them). */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onStructureGrow(org.bukkit.event.world.StructureGrowEvent event) {
        if (plugin.structuresEnabled(event.getWorld().getName())) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onItemDrop(PlayerDropItemEvent event) {
        MyPlanetData data = plugin.getMyPlanetManager().get(event.getPlayer().getWorld().getName());
        if (data == null || data.itemDrops()) return;
        event.setCancelled(true);
        event.getPlayer().updateInventory();
        event.getPlayer().sendMessage(net.kyori.adventure.text.Component.text("Item drops are disabled on this planet.")
                .color(net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    /** No picking items up off the ground while Item Drops is OFF. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        MyPlanetData data = plugin.getMyPlanetManager().get(player.getWorld().getName());
        if (data == null || data.itemDrops()) return;
        event.setCancelled(true);
    }

    /** No scattering items on the ground when the player dies. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDeathDrops(org.bukkit.event.entity.PlayerDeathEvent event) {
        MyPlanetData data = plugin.getMyPlanetManager().get(event.getEntity().getWorld().getName());
        if (data == null || data.itemDrops()) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        // Inventory is kept, so nothing is lost.
        event.setKeepInventory(true);
        event.setKeepLevel(true);
    }

    // ── Chest / Door Access ─────────────────────────────────────────

    /**
     * When "Chest & Door Access" is OFF, NOBODY (not even members) can open
     * chests, barrels, shulker boxes, trapped chests, ender chests, or use
     * doors, fence gates, trapdoors, or buttons on the planet.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        org.bukkit.block.Block block = event.getClickedBlock();
        if (block == null) return;
        MyPlanetData data = plugin.getMyPlanetManager().get(block.getWorld().getName());
        if (data == null) return;
        if (data.chestDoorAccess()) return; // toggle ON → everything allowed
        org.bukkit.Material type = block.getType();
        boolean isChestOrContainer = CHEST_MATERIALS.contains(type);
        boolean isDoorOrGate = DOOR_MATERIALS.contains(type);
        if (!isChestOrContainer && !isDoorOrGate) return;
        // Toggle OFF — block the interaction on both hands so it can't slip
        // through via the off-hand event.
        event.setCancelled(true);
        player.sendMessage(net.kyori.adventure.text.Component.text("Chest and door access is disabled on this planet.")
                .color(net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    /** Materials treated as containers (all chests, barrels, ender chests, shulkers). */
    private static final java.util.Set<org.bukkit.Material> CHEST_MATERIALS = java.util.Set.of(
            org.bukkit.Material.CHEST, org.bukkit.Material.TRAPPED_CHEST,
            org.bukkit.Material.ENDER_CHEST,
            org.bukkit.Material.BARREL,
            org.bukkit.Material.SHULKER_BOX,
            org.bukkit.Material.WHITE_SHULKER_BOX, org.bukkit.Material.ORANGE_SHULKER_BOX,
            org.bukkit.Material.MAGENTA_SHULKER_BOX, org.bukkit.Material.LIGHT_BLUE_SHULKER_BOX,
            org.bukkit.Material.YELLOW_SHULKER_BOX, org.bukkit.Material.LIME_SHULKER_BOX,
            org.bukkit.Material.PINK_SHULKER_BOX, org.bukkit.Material.GRAY_SHULKER_BOX,
            org.bukkit.Material.LIGHT_GRAY_SHULKER_BOX, org.bukkit.Material.CYAN_SHULKER_BOX,
            org.bukkit.Material.PURPLE_SHULKER_BOX, org.bukkit.Material.BLUE_SHULKER_BOX,
            org.bukkit.Material.BROWN_SHULKER_BOX, org.bukkit.Material.GREEN_SHULKER_BOX,
            org.bukkit.Material.RED_SHULKER_BOX, org.bukkit.Material.BLACK_SHULKER_BOX
    );

    /** Materials treated as doors, gates, trapdoors and buttons. */
    private static final java.util.Set<org.bukkit.Material> DOOR_MATERIALS = java.util.Set.of(
            org.bukkit.Material.IRON_DOOR,
            org.bukkit.Material.COPPER_DOOR,
            org.bukkit.Material.EXPOSED_COPPER_DOOR, org.bukkit.Material.WEATHERED_COPPER_DOOR,
            org.bukkit.Material.OXIDIZED_COPPER_DOOR,
            org.bukkit.Material.WAXED_COPPER_DOOR, org.bukkit.Material.WAXED_EXPOSED_COPPER_DOOR,
            org.bukkit.Material.WAXED_WEATHERED_COPPER_DOOR, org.bukkit.Material.WAXED_OXIDIZED_COPPER_DOOR,
            org.bukkit.Material.OAK_DOOR, org.bukkit.Material.SPRUCE_DOOR,
            org.bukkit.Material.BIRCH_DOOR, org.bukkit.Material.JUNGLE_DOOR,
            org.bukkit.Material.ACACIA_DOOR, org.bukkit.Material.DARK_OAK_DOOR,
            org.bukkit.Material.MANGROVE_DOOR, org.bukkit.Material.CHERRY_DOOR,
            org.bukkit.Material.BAMBOO_DOOR, org.bukkit.Material.CRIMSON_DOOR,
            org.bukkit.Material.WARPED_DOOR,
            org.bukkit.Material.OAK_FENCE_GATE, org.bukkit.Material.SPRUCE_FENCE_GATE,
            org.bukkit.Material.BIRCH_FENCE_GATE, org.bukkit.Material.JUNGLE_FENCE_GATE,
            org.bukkit.Material.ACACIA_FENCE_GATE, org.bukkit.Material.DARK_OAK_FENCE_GATE,
            org.bukkit.Material.MANGROVE_FENCE_GATE, org.bukkit.Material.CHERRY_FENCE_GATE,
            org.bukkit.Material.BAMBOO_FENCE_GATE, org.bukkit.Material.CRIMSON_FENCE_GATE,
            org.bukkit.Material.WARPED_FENCE_GATE,
            org.bukkit.Material.IRON_TRAPDOOR,
            org.bukkit.Material.COPPER_TRAPDOOR,
            org.bukkit.Material.EXPOSED_COPPER_TRAPDOOR, org.bukkit.Material.WEATHERED_COPPER_TRAPDOOR,
            org.bukkit.Material.OXIDIZED_COPPER_TRAPDOOR,
            org.bukkit.Material.WAXED_COPPER_TRAPDOOR, org.bukkit.Material.WAXED_EXPOSED_COPPER_TRAPDOOR,
            org.bukkit.Material.WAXED_WEATHERED_COPPER_TRAPDOOR, org.bukkit.Material.WAXED_OXIDIZED_COPPER_TRAPDOOR,
            org.bukkit.Material.OAK_TRAPDOOR, org.bukkit.Material.SPRUCE_TRAPDOOR,
            org.bukkit.Material.BIRCH_TRAPDOOR, org.bukkit.Material.JUNGLE_TRAPDOOR,
            org.bukkit.Material.ACACIA_TRAPDOOR, org.bukkit.Material.DARK_OAK_TRAPDOOR,
            org.bukkit.Material.MANGROVE_TRAPDOOR, org.bukkit.Material.CHERRY_TRAPDOOR,
            org.bukkit.Material.BAMBOO_TRAPDOOR, org.bukkit.Material.CRIMSON_TRAPDOOR,
            org.bukkit.Material.WARPED_TRAPDOOR,
            org.bukkit.Material.OAK_BUTTON, org.bukkit.Material.SPRUCE_BUTTON,
            org.bukkit.Material.BIRCH_BUTTON, org.bukkit.Material.JUNGLE_BUTTON,
            org.bukkit.Material.ACACIA_BUTTON, org.bukkit.Material.DARK_OAK_BUTTON,
            org.bukkit.Material.MANGROVE_BUTTON, org.bukkit.Material.CHERRY_BUTTON,
            org.bukkit.Material.BAMBOO_BUTTON, org.bukkit.Material.CRIMSON_BUTTON,
            org.bukkit.Material.WARPED_BUTTON, org.bukkit.Material.STONE_BUTTON,
            org.bukkit.Material.POLISHED_BLACKSTONE_BUTTON
    );

    // ── Visitor Access / Public ────────────────────────────────────────

    /**
     * Non-members can't teleport into a planet that has Visitor Access off
     * or is set to private. Members (any role) and the owner always get in.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onTeleportIn(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        World target = to.getWorld();
        if (target == null) return;
        if (!plugin.getMyPlanetManager().isOwnedWorld(target.getName())) return;
        MyPlanetData data = plugin.getMyPlanetManager().get(target.getName());
        if (data == null) return;
        Player player = event.getPlayer();
        if (data.isMember(player.getUniqueId())) return;
        if (data.visitorAccess() && data.isPublic()) return;
        if (player.hasPermission("planets.tp." + target.getName().toLowerCase(java.util.Locale.ROOT))) return;
        event.setCancelled(true);
        player.sendMessage(net.kyori.adventure.text.Component.text("This planet is private — only members can enter.")
                .color(net.kyori.adventure.text.format.NamedTextColor.RED));
    }
}
