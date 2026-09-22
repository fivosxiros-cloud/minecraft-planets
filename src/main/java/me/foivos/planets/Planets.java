package me.foivos.planets;

import me.foivos.playerdata.IPlayerDataStore;
import me.foivos.playerdata.PlayerData;
import me.foivos.playerdata.PlayerDataContext;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Date;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class Planets extends JavaPlugin implements CommandExecutor, TabCompleter, PlayerDataContext {

    /** How long "/planets lock" waits (seconds) before a planet actually locks. */
    private static final int DEFAULT_LOCK_WARNING_SECONDS = 10;

    /** Default rename price ladder: 2nd rename=1k, 3rd=5k, 4th=10k, 5th+=20k. */
    private static final List<Integer> DEFAULT_RENAME_PRICES = List.of(1000, 5000, 10000, 20000);

    /** /planets sub-commands that require op. */
    private static final Set<String> PLANET_ADMIN_SUBS = Set.of(
            "create", "preview", "admin", "icon", "landing", "delete", "setlanding",
            "lock", "world", "portals", "renameprice", "buy"
    );

    /** Menu icons the admin panel's Icon action cycles through. */
    static final List<Material> ICON_PALETTE = List.of(
            Material.GRASS_BLOCK, Material.SAND, Material.RED_SAND, Material.BONE_BLOCK,
            Material.MAGMA_BLOCK, Material.WATER_BUCKET, Material.AMETHYST_BLOCK,
            Material.IRON_BLOCK, Material.NETHERRACK, Material.END_STONE,
            Material.SNOW_BLOCK, Material.PRISMARINE, Material.GLOWSTONE, Material.OBSIDIAN);

    /** Lock countdowns for planets whose lock is pending, keyed by lowercase world name. */
    private final Map<String, PendingLock> pendingLocks = new HashMap<>();

    /** Manages all player-owned planet data (my-planets.yml). */
    private MyPlanetManager myPlanetManager;

    /** Every player's personal preferences (player-settings.yml, /settings). */
    private PlayerSettings playerSettings;
    /** Every player's /home locations (homes.yml). */
    private HomeManager homeManager;
    /** uuid -> the /home chat prompt waiting for that player's next message. */
    private final Map<UUID, HomePrompt> pendingHomePrompts = new HashMap<>();
    /** uuid -> the HUD line whose label the player is typing in chat; -1 = a new line. */
    private final Map<UUID, Integer> pendingHudNames = new HashMap<>();

    /** A pending /home prompt: what the player's next chat message means. */
    private record HomePrompt(boolean rename, String homeName, Location location) {

        static HomePrompt rename(String homeName) {
            return new HomePrompt(true, homeName, null);
        }

        static HomePrompt create(Location location) {
            return new HomePrompt(false, null, location);
        }
    }

    /** uuid -> the /home teleport counting down for that player. */
    private final Map<UUID, PendingHomeTeleport> pendingHomeTeleports = new HashMap<>();

    /**
     * A /home teleport waiting out its countdown. Moving a block or taking
     * damage cancels it, exactly like a planet teleport.
     */
    private static final class PendingHomeTeleport {
        private final HomeManager.Home home;
        private int secondsLeft;
        private BukkitTask task;

        PendingHomeTeleport(HomeManager.Home home, int secondsLeft) {
            this.home = home;
            this.secondsLeft = secondsLeft;
        }
    }

    /** Personal settings: last chat time per player, for anti-chat-spam. */
    private final Map<UUID, Long> lastChatAt = new HashMap<>();
    /** uuid -> last "your name was mentioned" ping, so a spammer can't flood it. */
    private final Map<UUID, Long> lastMentionAt = new HashMap<>();
    /** uuid -> when a priority action bar (mention, planet lock, atmosphere) was sent. */
    private final Map<UUID, Long> lastPriorityBar = new HashMap<>();
    /** How long the optional Planet HUD stays quiet after a priority action bar (ms). */
    private static final long HUD_QUIET_MILLIS = 3000L;
    /** uuid -> when a space station last pulled that player back (ms). */
    private final Map<UUID, Long> stationRescues = new HashMap<>();
    /** A station only rescues a player this often, so a bad spot can't loop (ms). */
    private static final long STATION_RESCUE_COOLDOWN_MILLIS = 2000L;

    /** Anti-chat-spam: how long a player waits between messages (ms). */
    private static final long CHAT_SPAM_MILLIS = 1500;
    /** Shortest gap between two "you were mentioned" pings for one player. */
    private static final long MENTION_COOLDOWN_MILLIS = 3000;

    /** Mobs don't spawn within this many blocks of a player who turned mob spawning off. */
    private static final double MOB_SPAWN_SHIELD = 24.0;

    /** Last private-message partner per player, for /r. */
    private final Map<UUID, UUID> replyTargets = new HashMap<>();

    /** Pending teleport requests, keyed by the player who must answer them. */
    private final Map<UUID, TpaRequest> tpaRequests = new HashMap<>();

    /** How long a teleport request stays valid (ms). */
    private static final long TPA_EXPIRY_MILLIS = 60_000;

    /** One pending teleport request: who asked, and which way they want to move. */
    private record TpaRequest(UUID from, String fromName, boolean here, long at) {
    }

    /** Players with a pending /myp rename prompt: player UUID -> lowercase world name. */
    private final Map<UUID, String> pendingRenames = new HashMap<>();

    /** Players with a pending sell prompt: player UUID -> lowercase world name. */
    private final Map<UUID, String> pendingSells = new HashMap<>();

    /** Players with a pending planet-delete confirmation: player UUID -> world name. */
    private final Map<UUID, String> pendingDeletes = new HashMap<>();

    /** Players part-way through editing a lobby: player UUID -> the pending edit. */
    private final Map<UUID, PendingLobbyEdit> pendingLobbyEdits = new HashMap<>();

    /** Which lobby field a chat prompt is filling in. */
    private enum LobbyEditKind { NAME, DESCRIPTION, SLOT }

    /** A lobby edit waiting for its answer in chat. */
    private record PendingLobbyEdit(String lobbyId, LobbyEditKind kind) {
    }

    /** One entry of the LOBBIES menu (opened by /lobby, /hub and /l). */
    public record Lobby(String id, String name, Material icon, String worldName,
                        Integer slot,
                        Double x, Double y, Double z, Double yaw, Double pitch,
                        String description) {

        /** Whether the admin pinned a landing spot with /lobby setlanding. */
        public boolean hasLanding() {
            return x != null && y != null && z != null;
        }

        /** The destination: the pinned landing spot, or the world spawn when unset. */
        public Location location() {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            if (!hasLanding()) {
                return world.getSpawnLocation();
            }
            return new Location(world, x, y, z,
                    yaw == null ? 0f : yaw.floatValue(),
                    pitch == null ? 0f : pitch.floatValue());
        }

        Planet asPlanet() {
            return new Planet(name, icon, worldName);
        }
    }

    /** Decoration item pinned in the last slot of the LOBBIES menu (config "lobbies-info"). */
    public record LobbyInfo(String name, Material icon, List<String> lore) {
    }

    /**
     * One line the optional Planet HUD can show: what the /settings menu calls
     * it and the template that is filled in. Both come from {@code hud.modes}
     * in config.yml, so the whole cycle is configurable — owners can add lines
     * for visitor counts, block usage, the next upgrade price, and so on.
     */
    record HudMode(String label, String template) {
    }

    /** The HUD lines used when config.yml lists none (the shipped defaults). */
    private static final List<HudMode> DEFAULT_HUD_MODES = List.of(
            new HudMode("Planet only", "\uD83E\uDE90 %planet%"),
            new HudMode("Planet + balance", "\uD83E\uDE90 %planet%  \u00B7  \uD83D\uDCB0 %balance% VPL"),
            new HudMode("Planet + coordinates", "\uD83E\uDE90 %planet%  \u00B7  \uD83D\uDCCD %x%, %y%, %z%"));

    /** Continuous environment traits (weather lock, atmospheres, disabled dimensions). */
    private final PlanetEnvironment environment = new PlanetEnvironment(this);

    /** The planet soundtrack behind the "Planet Music" toggle in /settings. */
    private final PlanetMusic music = new PlanetMusic(this);

    /** Persistent per-player data center: balance, NEB, playtime, planets, homes, settings. */
    private IPlayerDataStore playerData;

    /** ProtocolLib-based true sky tinting, or null when ProtocolLib is absent. */
    private PlanetSkyPackets skyPackets;

    /** The HUD lines players can cycle through (config: {@code hud.modes}). */
    private List<HudMode> hudModes = DEFAULT_HUD_MODES;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        seedDefaultLobbies();
        loadHudModes();
        PlanetTravel.init(this);
        PlanetTravel.loadConfig(getConfig());
        PlanetEffects.loadConfig(getConfig());
        PlanetEffects.start(this);
        environment.loadConfig(getConfig());
        if (MenuStyle.loadConfig(getConfig().getConfigurationSection("menu-style"))) {
            saveConfig(); // write the upgraded palette back to config.yml
        }
        environment.start(this);
        getServer().getPluginManager().registerEvents(environment, this);

        // Initialize the planet ownership manager BEFORE registering guards that read from it.
        this.myPlanetManager = new MyPlanetManager(this);
        this.playerSettings = new PlayerSettings(this);
        this.homeManager = new HomeManager(this);
        // The optional action-bar HUD needs both the settings and the planet data.
        pushHudDefault();
        startPlanetHud();
        // The soundtrack needs the same planet and lobby lists the HUD uses.
        music.loadConfig(getConfig());
        music.start(this);

        // Worlds are only all in place once every plugin has enabled (Multiverse
        // loads its own), so the safety-net terrain pass runs a few seconds later.
        getServer().getScheduler().runTaskLater(this, this::repairPlanetTerrain, 100L);

        // Enforces /myp → Settings toggles (PvP, build, spawning, fire, access...).
        getServer().getPluginManager().registerEvents(new PlanetSettingsGuard(this), this);
        // Stops players running risky Essentials/Vault commands (gamemode, give,
        // tp, eco give, invsee...). Staff bypass it with the configured permission.
        getServer().getPluginManager().registerEvents(new CommandGuard(this), this);
        if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
            try {
                skyPackets = new PlanetSkyPackets(this, environment);
                getLogger().info("ProtocolLib detected — true per-planet sky/fog tinting enabled.");
            } catch (Throwable ex) {
                getLogger().warning("Could not start ProtocolLib sky tinting (" + ex
                        + ") — using the particle haze only.");
            }
        }
        Objects.requireNonNull(getCommand("planets")).setExecutor(this);
        Objects.requireNonNull(getCommand("planets")).setTabCompleter(this);
        // /lobby, /hub and /l all open the same LOBBIES chest menu.
        for (String commandName : List.of("lobby", "hub", "l")) {
            org.bukkit.command.PluginCommand lobbyCommand = getCommand(commandName);
            if (lobbyCommand != null) {
                lobbyCommand.setExecutor(this);
                lobbyCommand.setTabCompleter(this);
            }
        }
        // /myp opens the My Planet management menu.
        org.bukkit.command.PluginCommand mypCommand = getCommand("myp");
        if (mypCommand != null) {
            mypCommand.setExecutor(this);
            mypCommand.setTabCompleter(this);
        }
        // /bal (alias /balance), /leaderboards (aliases /lb, /lead) and the
        // op-only /sus + /sl suspicious-player list.
        for (String commandName : List.of("bal", "leaderboards", "sus", "sl")) {
            org.bukkit.command.PluginCommand extraCommand = getCommand(commandName);
            if (extraCommand != null) {
                extraCommand.setExecutor(this);
                extraCommand.setTabCompleter(this);
            }
        }
        // /settings plus the private-message and teleport-request commands.
        for (String commandName : List.of("settings", "msg", "r", "tpa", "tpahere",
                "tpaccept", "tpdeny")) {
            org.bukkit.command.PluginCommand extraCommand = getCommand(commandName);
            if (extraCommand != null) {
                extraCommand.setExecutor(this);
                extraCommand.setTabCompleter(this);
            }
        }
        // /home, /sethome and /delhome.
        for (String commandName : List.of("home", "sethome", "delhome")) {
            org.bukkit.command.PluginCommand extraCommand = getCommand(commandName);
            if (extraCommand != null) {
                extraCommand.setExecutor(this);
                extraCommand.setTabCompleter(this);
            }
        }

        // Persistent per-player data center for the whole plugin.
        this.playerData = new PlayerDataStore(this);
        playerData.ensureFolder();
        playerData.refreshOnlinePlayers(this);

        // Hook into Vault economy.
        setupEconomy();
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onInventoryClick(InventoryClickEvent event) {
                if (event.getInventory().getHolder() instanceof PlanetsMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof LobbiesMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MyPlanetMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MyPlanetSettingsMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MyPlanetMembersMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MyPlanetInvitesMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MyPlanetInvitePickerMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MyPlanetUpgradesMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MyPlanetsOverviewMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MyPlanetIntroMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MyPlanetIconMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MyPlanetAbandonMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MyPlanetVisitorsMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof BuyPlanetMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof BuyConfirmMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof ForSaleMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof ForSaleConfirmMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof LeaderboardsMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof SusMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof HudEditorMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof AdminOverviewMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof AdminHelpMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof AdminPlanetsMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof AdminPlanetPanelMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof AdminTerrainMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof AdminMusicMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof MusicPickerMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof AdminLobbiesMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof AdminLobbyPanelMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof AdminHomesMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof AdminPlayerHomesMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof PlayerSettingsMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof HomeColourMenu menu) {
                    menu.handleClick(event);
                } else if (event.getInventory().getHolder() instanceof HomesMenu menu) {
                    menu.handleClick(event);
                }
            }

            // Moving during either countdown (planet or /home) cancels it.
            @EventHandler(ignoreCancelled = true)
            public void onPlayerMove(PlayerMoveEvent event) {
                if (!(event.getPlayer() instanceof Player player)) {
                    return;
                }
                boolean planetPending = PlanetTravel.hasPendingTeleport(player);
                if (!planetPending && !pendingHomeTeleports.containsKey(player.getUniqueId())) {
                    return;
                }
                Location from = event.getFrom();
                Location to = event.getTo();
                if (to == null) {
                    return;
                }
                // Ignore pure head-rotation changes; only block-space movement counts.
                if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                        && from.getBlockZ() == to.getBlockZ()) {
                    return;
                }
                if (PlanetTravel.cancelPendingTeleport(player)) {
                    player.sendMessage(Component.text("Teleport cancelled — you moved.").color(NamedTextColor.RED));
                }
                cancelPendingHomeTeleport(player, true);
            }

            // Taking damage tags the player as in combat and cancels any pending teleport.
            @EventHandler(ignoreCancelled = true)
            public void onPlayerDamage(EntityDamageEvent event) {
                if (event.getEntity() instanceof Player player) {
                    PlanetTravel.tagCombat(player);
                    if (PlanetTravel.cancelPendingTeleport(player)) {
                        player.sendMessage(Component.text("Teleport cancelled — you took damage.").color(NamedTextColor.RED));
                    }
                    cancelPendingHomeTeleport(player, true);
                }
            }

            // Dealing damage tags the attacker too; a pending teleport can't be
            // kept while starting a fight.
            @EventHandler(ignoreCancelled = true)
            public void onPlayerAttack(EntityDamageByEntityEvent event) {
                if (event.getDamager() instanceof Player attacker) {
                    PlanetTravel.tagCombat(attacker);
                    if (PlanetTravel.cancelPendingTeleport(attacker)) {
                        attacker.sendMessage(Component.text("Teleport cancelled — you entered combat.").color(NamedTextColor.RED));
                    }
                    // A home teleport can't be used to escape a fight either.
                    cancelPendingHomeTeleport(attacker, true);
                }
                if (event.getEntity() instanceof Player victim) {
                    PlanetTravel.tagCombat(victim);
                }
            }

            // Apply/remove planet effects (gravity, speed, atmosphere buffs...) when
            // crossing world borders.
            @EventHandler(ignoreCancelled = true)
            public void onWorldChange(PlayerChangedWorldEvent event) {
                Player player = event.getPlayer();
                if (PlanetEffects.hasEffects(player.getWorld().getName())) {
                    PlanetEffects.apply(player);
                } else {
                    PlanetEffects.remove(player);
                }
                // Personal setting: a one-line "what this planet does to you"
                // summary, for players who want to know what they just walked into.
                sendEffectSummary(player);
                // Re-run the configuration phase so the dimension-type registry is
                // re-sent with the new planet's sky/fog colors (ProtocolLib only).
                if (skyPackets != null) {
                    Bukkit.getScheduler().runTaskLater(Planets.this, () -> skyPackets.refreshPlayer(player), 2L);
                }
                // Keep the endless night vision alive across worlds.
                applyNightVision(player);
            }

            // Night vision is re-applied after a death too, since respawning
            // clears every potion effect.
            @EventHandler
            public void onRespawnNightVision(org.bukkit.event.player.PlayerRespawnEvent event) {
                Bukkit.getScheduler().runTask(Planets.this, () -> applyNightVision(event.getPlayer()));
            }

            /**
             * A player who dies on a world with a pinned landing spot comes back
             * on that spot instead of the world spawn. A spawn point of their
             * own (bed or respawn anchor) always wins, and so does a world with
             * no landing spot set.
             */
            @EventHandler
            public void onPlanetRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
                Player player = event.getPlayer();
                World world = player.getWorld();
                if (world == null) {
                    return;
                }
                // Their own bed/anchor wins — but only while it still exists:
                // if the block was broken, vanilla drops them at the world
                // spawn, and the planet's landing spot is the better spot.
                if (!event.isMissingRespawnBlock() && player.getRespawnLocation() != null) {
                    return;
                }
                Location spot = PlanetTravel.landingSpot(world.getName());
                if (spot != null) {
                    event.setRespawnLocation(spot);
                }
            }

            /**
             * A space station never takes a life: falling out of the world (or
             * any other hit that would finish a player off) sends them back to
             * the station's landing spot — or its spawn — instead of killing
             * them.
             */
            @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
            public void onStationRescue(EntityDamageEvent event) {
                if (!(event.getEntity() instanceof Player player)) {
                    return;
                }
                World world = player.getWorld();
                if (world == null || !PlanetTravel.isStation(world.getName())) {
                    return;
                }
                // Only rescue a hit that would actually kill the player, or that
                // comes from the void (so a player can't fall past the station).
                boolean lethal = event.getFinalDamage() >= player.getHealth() + player.getAbsorptionAmount();
                if (!lethal && event.getCause() != EntityDamageEvent.DamageCause.VOID) {
                    return;
                }
                // Void/fall is always rescued; the rest depends on the world's mode.
                if (!PlanetTravel.shouldRescueDamage(event, world.getName())) {
                    return;
                }
                // Only once every couple of seconds, so a landing spot that is
                // itself unsafe can't bounce the player over and over.
                long now = System.currentTimeMillis();
                if (now - stationRescues.getOrDefault(player.getUniqueId(), 0L) < STATION_RESCUE_COOLDOWN_MILLIS) {
                    return;
                }
                Location spot = PlanetTravel.stationLandingSpot(world.getName());
                if (spot == null) {
                    return;
                }
                stationRescues.put(player.getUniqueId(), now);
                event.setCancelled(true);
                // Kill the fall first, so the player doesn't keep dropping past
                // the spot they are put back on.
                player.setFallDistance(0.0f);
                player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                player.teleport(spot);
                player.playSound(spot, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.4f);
                player.sendMessage(Component.text("⚑ The station pulled you back to its landing spot.")
                        .color(NamedTextColor.AQUA));
            }

            // Planet effect worlds also affect players who log in inside them.
            @EventHandler
            public void onPlayerJoin(PlayerJoinEvent event) {
                Player player = event.getPlayer();
                if (PlanetEffects.hasEffects(player.getWorld().getName())) {
                    PlanetEffects.apply(player);
                }
                relocateLockedOutPlayer(player);
                // Invitations that arrived while they were offline are shown now.
                sendPendingInvites(player);
                sendResourcePack(player);
                // Restore the player's endless night vision if they have it on.
                applyNightVision(player);

                // Snapshot the player into the data center on join so the store
                // always has a fresh copy of anyone currently online.
                if (playerData != null) {
                    playerData.refresh(event.getPlayer(), Planets.this);
                }
            }

            // Drop the tracked effect set so leaving a planet only ever strips the
                // effects this plugin applied (and doesn't leak entries for old logins).
            @EventHandler
            public void onPlayerQuit(PlayerQuitEvent event) {
                UUID quitter = event.getPlayer().getUniqueId();
                PlanetEffects.remove(event.getPlayer());
                // Drop any half-finished help-search prompt with the player.
                helpSigns.remove(quitter);
                helpChatSearch.remove(quitter);
                // Drop a half-finished lobby edit too.
                pendingLobbyEdits.remove(quitter);
                // Personal-setting bookkeeping.
                lastChatAt.remove(quitter);
                lastMentionAt.remove(quitter);
                // A half-finished /home prompt goes with the player.
                pendingHomePrompts.remove(quitter);
                // …and so does a half-finished HUD line label.
                pendingHudNames.remove(quitter);
                // …and so does a home teleport that was still counting down.
                PendingHomeTeleport pendingHome = pendingHomeTeleports.remove(quitter);
                if (pendingHome != null && pendingHome.task != null) {
                    pendingHome.task.cancel();
                }
                replyTargets.remove(quitter);
                tpaRequests.remove(quitter);
                tpaRequests.values().removeIf(request -> request.from().equals(quitter));
                lastPriorityBar.remove(quitter);
                stationRescues.remove(quitter);
                // Snapshot the departing player into the data center.
                if (playerData != null) {
                    playerData.refreshByUuid(quitter, Planets.this);
                }
            }

            // Personal setting: join and quit lines are broadcast one player at a
            // time, so players who switched them off never see them. These run
            // last, so whatever the server (or another plugin) set is what gets
            // filtered — and the console keeps its own copy below.
            @EventHandler(priority = EventPriority.HIGHEST)
            public void onJoinBroadcast(PlayerJoinEvent event) {
                Component line = event.joinMessage();
                if (line == null) {
                    return; // the server hides join lines already — leave it that way
                }
                event.joinMessage(null);
                broadcastStatus(event.getPlayer(), line);
            }

            @EventHandler(priority = EventPriority.HIGHEST)
            public void onQuitBroadcast(PlayerQuitEvent event) {
                Component line = event.quitMessage();
                if (line == null) {
                    return; // the server hides quit lines already — leave it that way
                }
                event.quitMessage(null);
                broadcastStatus(event.getPlayer(), line);
            }

            /** Sends a join/quit line to everyone who still wants to see it. */
            private void broadcastStatus(Player player, Component line) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (playerSettings.get(online.getUniqueId(),
                            PlayerSettings.Setting.JOIN_LEAVE_MESSAGES)) {
                        online.sendMessage(line);
                    }
                }
                // The vanilla broadcast is suppressed above, so keep the server
                // log in the loop ourselves.
                getLogger().info(player.getName() + " " + net.kyori.adventure.text.serializer.plain
                        .PlainTextComponentSerializer.plainText().serialize(line));
            }

            // ── Personal settings (/settings) ────────────────────────────

            // Anti chat spam: a short cooldown between messages. Answers to the
            // plugin's own chat prompts are never rate-limited.
            @EventHandler(ignoreCancelled = true)
            public void onPersonalChatSpam(AsyncChatEvent event) {
                Player player = event.getPlayer();
                if (hasPendingPrompt(player)
                        || !playerSettings.get(player.getUniqueId(), PlayerSettings.Setting.ANTI_CHAT_SPAM)) {
                    return;
                }
                long now = System.currentTimeMillis();
                Long last = lastChatAt.get(player.getUniqueId());
                if (last != null && now - last < CHAT_SPAM_MILLIS) {
                    event.setCancelled(true);
                    long millisLeft = CHAT_SPAM_MILLIS - (now - last);
                    player.sendMessage(Component.text("Slow down — you can chat again in ").color(NamedTextColor.RED)
                            .append(Component.text(String.format(Locale.ROOT, "%.1f", millisLeft / 1000.0) + "s")
                                    .color(NamedTextColor.YELLOW))
                            .append(Component.text(".").color(NamedTextColor.RED)));
                    return;
                }
                lastChatAt.put(player.getUniqueId(), now);
            }

            // Public chat can be hidden from a player who turned it off: they are
            // simply removed from the message's recipients.
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onPersonalPublicChat(AsyncChatEvent event) {
                event.viewers().removeIf(viewer -> viewer instanceof Player viewerPlayer
                        && !playerSettings.get(viewerPlayer.getUniqueId(), PlayerSettings.Setting.PUBLIC_CHAT));
            }

            // Name mentions: a player who kept the alert on gets a ping when
            // someone types their name in public chat. The chat event runs off
            // the main thread, so the ping itself is scheduled back onto it.
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onPersonalMention(AsyncChatEvent event) {
                String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(event.message()).toLowerCase(Locale.ROOT);
                if (text.length() < 3) {
                    return;
                }
                long now = System.currentTimeMillis();
                List<Player> mentioned = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.equals(event.getPlayer())
                            || !text.contains(online.getName().toLowerCase(Locale.ROOT))
                            || !mentions(text, online.getName().toLowerCase(Locale.ROOT))
                            || !playerSettings.get(online.getUniqueId(), PlayerSettings.Setting.MENTION_ALERTS)
                            || !playerSettings.get(online.getUniqueId(), PlayerSettings.Setting.PUBLIC_CHAT)) {
                        continue;
                    }
                    Long last = lastMentionAt.get(online.getUniqueId());
                    if (last != null && now - last < MENTION_COOLDOWN_MILLIS) {
                        continue;
                    }
                    lastMentionAt.put(online.getUniqueId(), now);
                    mentioned.add(online);
                }
                if (mentioned.isEmpty()) {
                    return;
                }
                String sender = event.getPlayer().getName();
                Bukkit.getScheduler().runTask(Planets.this, () -> {
                    for (Player listener : mentioned) {
                        if (!listener.isOnline()) {
                            continue;
                        }
                        listener.playSound(listener.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.7f);
                        sendPriorityBar(listener, Component.text("🔔 " + sender + " mentioned you in chat")
                                .color(NamedTextColor.AQUA));
                    }
                });
            }

            /**
             * Whether a lower-cased chat text names a player as a whole word, so
             * mentioning "sam" doesn't ping a player called "samuel".
             */
            private boolean mentions(String lowerText, String lowerName) {
                int from = 0;
                while (true) {
                    int at = lowerText.indexOf(lowerName, from);
                    if (at < 0) {
                        return false;
                    }
                    int end = at + lowerName.length();
                    boolean leftOk = at == 0 || !isNameChar(lowerText.charAt(at - 1));
                    boolean rightOk = end >= lowerText.length() || !isNameChar(lowerText.charAt(end));
                    if (leftOk && rightOk) {
                        return true;
                    }
                    from = at + 1;
                }
            }

            private boolean isNameChar(char c) {
                return Character.isLetterOrDigit(c) || c == '_';
            }

            // Death messages go only to the players who still want them, plus the
            // console (the server broadcast is replaced by our own).
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void onPersonalDeathMessage(PlayerDeathEvent event) {
                Component message = event.deathMessage();
                if (message == null) {
                    return;
                }
                event.deathMessage(null);
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (playerSettings.get(online.getUniqueId(), PlayerSettings.Setting.DEATH_MESSAGES)) {
                        online.sendMessage(message);
                    }
                }
                Bukkit.getConsoleSender().sendMessage(message);
            }

            // Player-vs-player: on a station with rescue mode
            // "void-fall-pvp", a lethal hit from another player is rescued
            // (teleported back) instead of killing them — PvP is effectively off.
            @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
            public void onStationRescuePvp(EntityDamageByEntityEvent event) {
                if (!(event.getEntity() instanceof Player player)) {
                    return;
                }
                World world = player.getWorld();
                if (world == null || !PlanetTravel.isStation(world.getName())) {
                    return;
                }
                if (!PlanetTravel.stationRespectsPvp(world.getName())) {
                    return;
                }
                // Only rescue a hit that would actually kill the player.
                boolean lethal = event.getFinalDamage() >= player.getHealth() + player.getAbsorptionAmount();
                if (!lethal) {
                    return;
                }
                // Once per cooldown, same as the void/fall rescue.
                long now = System.currentTimeMillis();
                if (now - stationRescues.getOrDefault(player.getUniqueId(), 0L) < STATION_RESCUE_COOLDOWN_MILLIS) {
                    return;
                }
                Location spot = PlanetTravel.stationLandingSpot(world.getName());
                if (spot == null) {
                    return;
                }
                stationRescues.put(player.getUniqueId(), now);
                event.setCancelled(true);
                player.setFallDistance(0.0f);
                player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                player.teleport(spot);
                player.playSound(spot, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.4f);
                player.sendMessage(Component.text("⚑ The station pulled you back to its landing spot.")
                        .color(NamedTextColor.AQUA));
            }

            // ...and they don't spawn right next to one either.
            @EventHandler(ignoreCancelled = true)
            public void onPersonalMobSpawn(CreatureSpawnEvent event) {
                if (!(event.getEntity() instanceof Monster)) {
                    return;
                }
                if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                        && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS
                        && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.DEFAULT) {
                    return;
                }
                Location at = event.getLocation();
                for (Player online : at.getWorld().getPlayers()) {
                    if (!playerSettings.get(online.getUniqueId(), PlayerSettings.Setting.MOB_SPAWNING)
                            && online.getLocation().distanceSquared(at) <= MOB_SPAWN_SHIELD * MOB_SPAWN_SHIELD) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }

            // The keyword an admin is typing for the admin-help search. This
            // is checked first: it is a short-lived prompt that should never be
            // swallowed by another one.
            @EventHandler
            public void onHelpSearchChat(AsyncChatEvent event) {
                Player player = event.getPlayer();
                Boolean adminSearch = helpChatSearch.remove(player.getUniqueId());
                if (adminSearch == null) {
                    return;
                }
                event.setCancelled(true);
                String input = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(event.message()).trim();
                if (input.isEmpty() || input.equalsIgnoreCase("cancel")) {
                    Bukkit.getScheduler().runTask(Planets.this,
                            () -> openHelp(player, null, adminSearch));
                    return;
                }
                Bukkit.getScheduler().runTask(Planets.this,
                        () -> showHelpSearch(player, input, adminSearch));
            }

            // The sign an admin typed a help-search keyword into.
            @EventHandler
            public void onSignChange(SignChangeEvent event) {
                handleHelpSign(event);
            }

            // Chat prompt for /myp rename: the next chat message becomes the
            // planet's display name (and cancels the prompt when it is).
            @EventHandler
            public void onAsyncChat(AsyncChatEvent event) {
                Player player = event.getPlayer();

                // /home prompts: naming a new home, or renaming an existing one.
                HomePrompt homePrompt = pendingHomePrompts.remove(player.getUniqueId());
                if (homePrompt != null) {
                    event.setCancelled(true);
                    String input = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(event.message()).trim();
                    Bukkit.getScheduler().runTask(Planets.this, () -> applyHomePrompt(player, homePrompt, input));
                    return;
                }

                // HUD editor prompts: the label of an existing line, or of a new one.
                if (pendingHudNames.containsKey(player.getUniqueId())) {
                    int hudIndex = pendingHudNames.remove(player.getUniqueId());
                    event.setCancelled(true);
                    String input = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(event.message()).trim();
                    Bukkit.getScheduler().runTask(Planets.this, () -> applyHudName(player, hudIndex, input));
                    return;
                }

                // Lobby editor prompts: name, description or menu slot.
                PendingLobbyEdit lobbyEdit = pendingLobbyEdits.remove(player.getUniqueId());
                if (lobbyEdit != null) {
                    event.setCancelled(true);
                    String input = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(event.message()).trim();
                    Bukkit.getScheduler().runTask(Planets.this, () -> applyLobbyEdit(player, lobbyEdit, input));
                    return;
                }

                // Handle sell prompt first
                String sellWorldName = pendingSells.remove(player.getUniqueId());
                if (sellWorldName != null) {
                    event.setCancelled(true);
                    String input = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(event.message()).trim();
                    Bukkit.getScheduler().runTask(Planets.this, () -> applySellPrice(player, sellWorldName, input));
                    return;
                }

                String worldName = pendingRenames.remove(player.getUniqueId());
                if (worldName == null) {
                    // Check for pending planet-delete confirmation.
                    String deleteWorldName = pendingDeletes.remove(player.getUniqueId());
                    if (deleteWorldName != null) {
                        event.setCancelled(true);
                        String input = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(event.message()).trim();
                        Bukkit.getScheduler().runTask(Planets.this,
                                () -> applyDelete(player, deleteWorldName, input));
                    }
                    return;
                }
                event.setCancelled(true);
                String newName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(event.message()).trim();
                Bukkit.getScheduler().runTask(Planets.this, () -> applyRename(player, worldName, newName));
            }
        }, this);
    }

    @Override
    public void onDisable() {
        if (skyPackets != null) {
            skyPackets.shutdown();
        }
        if (myPlanetManager != null) {
            myPlanetManager.save();
        }
        if (playerSettings != null) {
            playerSettings.save();
        }
        if (homeManager != null) {
            homeManager.save();
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        // /lobby, /hub and /l open the LOBBIES menu for any player with planets.use.
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("lobby") || commandName.equals("hub") || commandName.equals("l")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can use the lobby commands.").color(NamedTextColor.RED));
                return true;
            }
            handleLobbyCommand(player, args);
            return true;
        }

        // /myp opens the My Planet management menu.
        if (commandName.equals("myp")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can use /myp.").color(NamedTextColor.RED));
                return true;
            }
            handleMypCommand(player, args);
            return true;
        }

        // /bal <player> — replies with that player's VPL balance.
        if (commandName.equals("bal") || commandName.equals("balance")) {
            handleBalance(sender, args);
            return true;
        }

        // /home, /sethome, /delhome — a player's own saved locations.
        if (commandName.equals("home") || commandName.equals("sethome") || commandName.equals("delhome")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can use /home.").color(NamedTextColor.RED));
                return true;
            }
            handleHomeCommand(player, commandName, args);
            return true;
        }

        // /settings — the player's own preferences (global, persisted).
        if (commandName.equals("settings")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can open /settings.").color(NamedTextColor.RED));
                return true;
            }
            if (!player.hasPermission("planets.settings")) {
                player.sendMessage(Component.text("You don't have permission to change your settings.")
                        .color(NamedTextColor.RED));
                return true;
            }
            player.closeInventory();
            new PlayerSettingsMenu(this, player, playerSettings).open(player);
            return true;
        }

        // Private messages and teleport requests.
        if (commandName.equals("msg")) {
            handleMsgCommand(sender, args);
            return true;
        }
        if (commandName.equals("r")) {
            handleReplyCommand(sender, args);
            return true;
        }
        if (commandName.equals("tpa")) {
            handleTpaCommand(sender, args, false);
            return true;
        }
        if (commandName.equals("tpahere")) {
            handleTpaCommand(sender, args, true);
            return true;
        }
        if (commandName.equals("tpaccept")) {
            handleTpaAccept(sender, args);
            return true;
        }
        if (commandName.equals("tpdeny")) {
            handleTpaDeny(sender, args);
            return true;
        }

        // /sus <player> | /sus list | /sl — the op-only suspicious-player list.
        if (commandName.equals("sus") || commandName.equals("sl")) {
            handleSusCommand(sender, args);
            return true;
        }

        // /leaderboards, /lb and /lead — open the richest-players UI.
        if (commandName.equals("leaderboards") || commandName.equals("lb") || commandName.equals("lead")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Only players can open the leaderboards.").color(NamedTextColor.RED));
                return true;
            }
            openLeaderboards(player);
            return true;
        }

        // /planets borders — repair the world borders of the public planets
        // (the sizes a config reload used to stamp over). Available to console too.
        if (args.length > 0 && args[0].equalsIgnoreCase("borders")) {
            if (!sender.hasPermission("planets.world")) {
                sender.sendMessage(Component.text("You don't have permission to change world borders.")
                        .color(NamedTextColor.RED));
                return true;
            }
            handleBordersCommand(sender, args);
            return true;
        }

        // Reload is available to the console too.
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("planets.reload")) {
                sender.sendMessage(Component.text("You don't have permission to reload the planets config.").color(NamedTextColor.RED));
                return true;
            }
            try {
                reloadConfig();
                loadHudModes();
            } catch (RuntimeException ex) {
                sender.sendMessage(Component.text("Could not reload config.yml: ").color(NamedTextColor.RED)
                        .append(Component.text(ex.getMessage() == null ? ex.toString() : ex.getMessage()).color(NamedTextColor.YELLOW)));
                getLogger().warning("Failed to reload config.yml: " + ex);
                return true;
            }
            PlanetTravel.loadConfig(getConfig());
            PlanetEffects.loadConfig(getConfig());
            environment.loadConfig(getConfig());
            music.loadConfig(getConfig());
            if (MenuStyle.loadConfig(getConfig().getConfigurationSection("menu-style"))) {
                saveConfig();
            }
            applyWorldBordersToAllPlanets();
            sender.sendMessage(Component.text("Planets config reloaded.").color(NamedTextColor.GREEN));;
            return true;
        }

        // Help: players get the browsable page, the console gets the printed list.
        if (args.length > 0 && (args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("?"))) {
            if (sender instanceof Player helper) {
                openHelp(helper);
            } else {
                showHelp(sender);
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use the planet commands.").color(NamedTextColor.RED));
            return true;
        }

        // Admin sub-commands require op — except "/planets admin", which is
        // gated by the planets.admin permission inside openAdminMenu itself, so
        // a permission plugin can hand the panel to a trusted non-op.
        if (args.length > 0 && !args[0].equalsIgnoreCase("admin")
                && PLANET_ADMIN_SUBS.contains(args[0].toLowerCase(Locale.ROOT))) {
            if (!player.isOp()) {
                player.sendMessage(Component.text("You don't have permission to use this command.").color(NamedTextColor.RED));
                return true;
            }
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("create")) {
            createPlanet(player, args);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("preview")) {
            previewGeneration(player, args);
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            if (args.length > 1 && (args[1].equalsIgnoreCase("help")
                    || args[1].equalsIgnoreCase("?"))) {
                openAdminHelp(player);
            } else if (args.length > 1 && (args[1].equalsIgnoreCase("homes")
                    || args[1].equalsIgnoreCase("home"))) {
                openAdminHomes(player); // permission is checked inside
            } else if (args.length > 1 && args[1].equalsIgnoreCase("playerdata")) {
                handleAdminPlayerData(player, args);
            } else {
                openAdminMenu(player);
            }
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("hud")) {
            openHudEditor(player); // permission is checked inside
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("icon")) {
            setPlanetIcon(player, args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("landing")) {
            setPlanetLanding(player, args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("delete")) {
            deletePlanet(player, args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("setlanding")) {
            setLandingPoint(player, args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("lock")) {
            togglePlanetLock(player, args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("world")) {
            setWorldProperty(player, args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("portals")) {
            setPortalsEnabled(player, args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("renameprice")) {
            setRenamePrices(player, args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("buy")) {
            if (args.length >= 2) {
                // Admin command: /planets buy <amount> — set the price of a new planet in VPL.
                setPlanetPrice(player, args);
            } else {
                // Player command: /planets buy — open the buy-a-planet UI.
                openBuyPlanetMenu(player);
            }
            return true;
        }

        List<Planet> planets = availablePlanets();
        if (args.length == 0) {
            new PlanetsMenu(this, planets, player).open(player);
            return true;
        }

        // /planets <name> (or /p <name>) teleports straight there, e.g. "/p middle earth" or "/p end".
        String query = String.join(" ", args);
        Planet target = findPlanet(planets, query);
        if (target == null) {
            player.sendMessage(
                    Component.text("Unknown planet '").color(NamedTextColor.RED)
                            .append(Component.text(query).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Type /planets to see all the planets.").color(NamedTextColor.RED))
            );
            return true;
        }

        if (!canVisit(player, target)) {
            player.sendMessage(Component.text("You don't have permission to visit ").color(NamedTextColor.RED)
                    .append(Component.text(target.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return true;
        }

        PlanetTravel.teleport(player, target);
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player) || args.length == 0) {
            return List.of();
        }
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("lobby") || commandName.equals("hub") || commandName.equals("l")) {
            return lobbyTabComplete(player, args);
        }
        if (commandName.equals("myp")) {
            return mypTabComplete(player, args);
        }
        if (commandName.equals("home") || commandName.equals("delhome")) {
            // Suggest the player's own home names (and "list" for /home).
            String homePrefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>(homeManager.names(player.getUniqueId()));
            if (commandName.equals("home")) {
                suggestions.add("list");
                suggestions.add("help");
            }
            return suggestions.stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(homePrefix))
                    .toList();
        }
        if (commandName.equals("bal") || commandName.equals("balance")) {
            // Suggest online players by name.
            String namePrefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(namePrefix))
                    .toList();
        }
        if (commandName.equals("sus") || commandName.equals("sl")) {
            String susPrefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            if (args.length <= 1) {
                List<String> suggestions = new ArrayList<>(List.of("list", "remove", "stop", "help"));
                for (Player online : Bukkit.getOnlinePlayers()) {
                    suggestions.add(online.getName());
                }
                return suggestions.stream()
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(susPrefix))
                        .toList();
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
                return susList().stream()
                        .map(SusEntry::name)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(susPrefix))
                        .toList();
            }
            return List.of();
        }
        if (commandName.equals("tpaccept") || commandName.equals("tpdeny")) {
            // The optional argument names who asked to teleport.
            String namePrefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            return match(tpaRequesterNames(player), namePrefix);
        }
        if (commandName.equals("msg") || commandName.equals("tpa") || commandName.equals("tpahere")) {
            String namePrefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
            // Only the first argument is a player; the rest of /msg is free text.
            if (args.length <= 1) {
                return onlineNames(namePrefix);
            }
            return List.of();
        }
        // /settings, /r, /sethome and the leaderboards take no arguments (home
        // names are free text), so nothing is ever suggested for them.
        if (!commandName.equals("planets")) {
            return List.of();
        }

        if (args[0].equalsIgnoreCase("buy") && args.length == 2 && player.isOp()) {
            // Admin price setter: /planets buy <amount>
            String current = String.valueOf((long) myPlanetManager.buyCost());
            if (current.startsWith(args[1])) {
                return List.of(current);
            }
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);

        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(planetSubcommandSuggestions(player, prefix));
            for (Planet planet : availablePlanets()) {
                if (!canVisit(player, planet)) {
                    continue; // don't suggest locked planets the player can't visit
                }
                String name = planet.name();
                if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(name);
                }
                String worldName = planet.worldName();
                if (!worldName.equalsIgnoreCase(name) && worldName.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(worldName);
                }
            }
            return suggestions;
        }

        // "/planets admin help|homes|playerdata" — the admin pages an argument can pick.
        if (args[0].equalsIgnoreCase("admin") && args.length == 2) {
            return match(List.of("help", "homes", "playerdata"), prefix);
        }
        // "/planets admin playerdata <player>" — tab-complete player names.
        if (args[0].equalsIgnoreCase("admin") && args.length == 3
                && args[1].equalsIgnoreCase("playerdata")) {
            Player playerSender = Bukkit.getPlayer(sender.getName());
            if (playerSender == null || !canUseAdmin(playerSender)) {
                return List.of();
            }
            return playerData.search(prefix).stream()
                    .map(PlayerData::name)
                    .toList();
        }

        if (args[0].equalsIgnoreCase("borders") && player.hasPermission("planets.world")) {
            if (args.length == 2) {
                return match(List.of("list", "set", "restore"), prefix);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
                return match(List.of("1x1", "2x2", "3x3", "4x4", "5x5", "6x6", "16", "96"), prefix);
            }
            return List.of();
        }

        if (args[0].equalsIgnoreCase("create") && player.hasPermission("planets.create")) {
            if (args.length == 3) {
                // Type slot: a world type, or a generation straight away
                // ("/planets create Mars mars" is allowed).
                List<String> types = new ArrayList<>(List.of("normal", "nether", "the_end", "station"));
                types.addAll(generationIds());
                return types.stream()
                        .filter(type -> type.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            if (args.length == 4) {
                // Generation slot: only meaningful when a world type came first.
                if (isGeneration(args[2].toLowerCase(Locale.ROOT))) {
                    return List.of();
                }
                return generationIds().stream()
                        .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            return List.of();
        }

        if (args[0].equalsIgnoreCase("preview") && player.isOp()) {
            if (args.length == 2) {
                List<String> options = new ArrayList<>(generationIds());
                options.add("end");
                return options.stream()
                        .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            return List.of();
        }

        if (args[0].equalsIgnoreCase("icon") && player.hasPermission("planets.icon")) {
            if (args.length == 2) {
                return planetArgSuggestions(player, args, prefix);
            }
            // The material is the last argument, after a planet name that may
            // hold spaces — so both a continuation word and a material fit here.
            List<String> suggestions = new ArrayList<>(iconMaterialNames());
            suggestions.addAll(planetNameContinuations(player, args, prefix));
            return match(suggestions, prefix);
        }

        if (args[0].equalsIgnoreCase("delete") && player.hasPermission("planets.delete")) {
            // The name type"d again in chat confirms it, so only the name is asked for.
            return planetArgSuggestions(player, args, prefix);
        }

        if (args[0].equalsIgnoreCase("renameprice") && args.length == 2 && player.hasPermission("planets.world")) {
            return match(List.of("reset"), prefix);
        }

        if (args[0].equalsIgnoreCase("lock") && player.hasPermission("planets.lock")) {
            if (args.length == 2) {
                List<String> suggestions = new ArrayList<>();
                if ("cancel".startsWith(prefix)) {
                    suggestions.add("cancel");
                }
                suggestions.addAll(planetArgSuggestions(player, args, prefix));
                return suggestions;
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("cancel")) {
                return planetArgSuggestions(player, args, prefix);
            }
        }

        if (args[0].equalsIgnoreCase("world") && player.hasPermission("planets.world")) {
            if (args.length == 2) {
                return planetArgSuggestions(player, args, prefix);
            }
            // Planet names may contain spaces; find the property keyword among
            // the earlier arguments to know whether we're completing a value.
            for (int i = 1; i < args.length - 1; i++) {
                if (WORLD_PROPERTIES.contains(args[i].toLowerCase(Locale.ROOT))) {
                    return worldPropertyValues(args[i].toLowerCase(Locale.ROOT), prefix);
                }
            }
            List<String> suggestions = new ArrayList<>(planetNameContinuations(player, args, prefix));
            suggestions.addAll(match(WORLD_PROPERTIES, prefix));
            return suggestions;
        }

        if (args[0].equalsIgnoreCase("portals") && args.length == 2 && player.hasPermission("planets.world")) {
            return match(List.of("on", "off"), prefix);
        }

        if (args[0].equalsIgnoreCase("setlanding") && player.hasPermission("planets.landing")) {
            return planetArgSuggestions(player, args, prefix);
        }

        if (args[0].equalsIgnoreCase("landing") && player.hasPermission("planets.landing")) {
            if (args.length == 2) {
                return planetArgSuggestions(player, args, prefix);
            }
            List<String> modes = new ArrayList<>(List.of("station", "random", "point"));
            modes.addAll(planetNameContinuations(player, args, prefix));
            return match(modes, prefix);
        }

        if (args[0].equalsIgnoreCase("create") && player.hasPermission("planets.create")) {
            return List.of(); // the world name is free text
        }

        // Anything else is the start of a planet name, which may hold spaces.
        return planetNameContinuations(player, args, prefix);
    }

    /**
     * The "/planets" sub-commands a player can actually use, so tab completion
     * never offers a command that would answer "no permission". Help and the
     * planets themselves go to everyone; the admin sub-commands follow the same
     * op gate the commands use; borders and reload follow their permissions.
     */
    private List<String> planetSubcommandSuggestions(Player player, String prefix) {
        List<String> suggestions = new ArrayList<>();
        add(suggestions, prefix, "help");
        if (player.hasPermission("planets.buy")) {
            add(suggestions, prefix, "buy");
        }
        if (player.isOp()) {
            for (String sub : List.of("create", "preview", "icon", "landing", "setlanding",
                    "delete", "lock", "world", "portals", "renameprice")) {
                add(suggestions, prefix, sub);
            }
        }
        if (canUseAdmin(player)) {
            add(suggestions, prefix, "admin");
            add(suggestions, prefix, "hud");
        }
        if (player.hasPermission("planets.world")) {
            add(suggestions, prefix, "borders");
        }
        if (player.hasPermission("planets.reload")) {
            add(suggestions, prefix, "reload");
        }
        return suggestions;
    }

    /** Keeps the entries of a suggestion list that start with what's typed. */
    private static List<String> match(List<String> options, String lowerPrefix) {
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .toList();
    }

    /** Adds one option to a suggestion list when it starts with what's typed. */
    private static void add(List<String> suggestions, String lowerPrefix, String option) {
        if (option.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
            suggestions.add(option);
        }
    }

    /** Where a planet name is expected: the names themselves, plus the next word of a long one. */
    private List<String> planetArgSuggestions(Player player, String[] args, String prefix) {
        List<String> suggestions = new ArrayList<>(planetNameSuggestions(prefix));
        for (String continuation : planetNameContinuations(player, args, prefix)) {
            if (!suggestions.contains(continuation)) {
                suggestions.add(continuation);
            }
        }
        return suggestions;
    }

    /**
     * The next word of a multi-word planet name, so "/p middle " offers
     * "Earth" instead of nothing. Owned planets, lobbies and a planet's own
     * Nether/End are matched too, since the world commands accept them.
     */
    private List<String> planetNameContinuations(Player player, String[] args, String prefix) {
        String typedSoFar = String.join(" ", Arrays.copyOfRange(args, 0, args.length - 1));
        if (typedSoFar.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Planet planet : availablePlanets()) {
            if (canVisit(player, planet)) {
                names.add(planet.name());
                names.add(planet.worldName().replace('_', ' '));
            }
        }
        for (String worldName : pluginWorldNames()) {
            names.add(worldName.replace('_', ' '));
        }
        List<String> suggestions = new ArrayList<>();
        for (String name : names) {
            if (name.length() <= typedSoFar.length()
                    || !name.regionMatches(true, 0, typedSoFar, 0, typedSoFar.length())) {
                continue;
            }
            String rest = name.substring(typedSoFar.length()).trim();
            int space = rest.indexOf(' ');
            String nextWord = space < 0 ? rest : rest.substring(0, space);
            if (!nextWord.isEmpty() && nextWord.toLowerCase(Locale.ROOT).startsWith(prefix)
                    && !suggestions.contains(nextWord)) {
                suggestions.add(nextWord);
            }
        }
        return suggestions;
    }

    /** The names of everyone with a pending teleport request to this player. */
    private List<String> tpaRequesterNames(Player player) {
        TpaRequest request = tpaRequests.get(player.getUniqueId());
        return request == null ? List.of() : List.of(request.fromName());
    }

    /** Material suggestions for "/planets icon" (and the lobby icon commands). */
    static List<String> iconMaterialNames() {
        List<String> names = new ArrayList<>();
        names.add("reset");
        for (Material material : ICON_PALETTE) {
            names.add(material.name());
        }
        return names;
    }

    private @NotNull List<String> planetNameSuggestions(String lowerPrefix) {
        List<String> suggestions = new ArrayList<>();
        for (Planet planet : availablePlanets()) {
            String name = planet.name();
            if (name.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                suggestions.add(name);
            }
            String worldName = planet.worldName();
            if (!worldName.equalsIgnoreCase(name) && worldName.toLowerCase(Locale.ROOT).startsWith(lowerPrefix)) {
                suggestions.add(worldName);
            }
        }
        return suggestions;
    }

    private static Planet findPlanet(List<Planet> planets, String query) {
        return planets.stream()
                .filter(planet -> planet.name().equalsIgnoreCase(query)
                        || planet.worldName().equalsIgnoreCase(query))
                .findFirst()
                .orElse(null);
    }

    /**
     * Resolves a {@code /planets ... <planet>} argument to a loaded world: a
     * public planet from the menu first, then any other loaded world matching
     * the name. That is how commands reach worlds that aren't in the public
     * menu — player-owned planets, lobbies, and a planet's own Nether/End.
     * Null when nothing matches or the world isn't loaded.
     */
    private World findPlanetWorld(String query) {
        Planet planet = findPlanet(availablePlanets(), query);
        if (planet != null) {
            return Bukkit.getWorld(planet.worldName());
        }
        return findLoadedWorld(query);
    }

    /**
     * Any loaded world by name, treating {@code _} as a space, so
     * "Middle_earth" also answers to "middle earth".
     */
    private static World findLoadedWorld(String query) {
        for (World world : Bukkit.getWorlds()) {
            if (worldNameMatches(world.getName(), query)) {
                return world;
            }
        }
        return null;
    }

    /** Whether a world name matches a command argument ("_" and " " are the same). */
    private static boolean worldNameMatches(String worldName, String query) {
        String wanted = query.trim();
        return worldName.equalsIgnoreCase(wanted)
                || worldName.replace('_', ' ').equalsIgnoreCase(wanted.replace('_', ' '));
    }

    /**
     * A player-owned planet that exists on disk but isn't loaded, or null. Its
     * name is what {@link #loadWorldNow} needs to bring it back so commands can
     * be used on it again.
     */
    private String unloadedOwnedWorld(String query) {
        if (myPlanetManager == null) {
            return null;
        }
        for (MyPlanetData owned : myPlanetManager.allPlanets()) {
            String worldName = owned.worldName();
            if (worldNameMatches(worldName, query) && haveWorld(worldName)
                    && Bukkit.getWorld(worldName) == null) {
                return worldName;
            }
        }
        return null;
    }

    /**
     * Handles "/planets create <name> [type] [generation]": creates a brand new world
     * and teleports the creator to it. Types: normal, nether, the_end (lava planets
     * are nether-type worlds) or station (a flat world where players always land at
     * the spawn). Generations (normal type only) customize the terrain: the planet
     * archetypes (terran, desert, mars, moon, lava, ocean, crystal) each build their
     * own ground, "void" is an empty world, and any preset defined under
     * "generation-presets" in config.yml can be used by id — that is how admins
     * create new generations for the public /p planets.
     */
    private void createPlanet(Player player, String[] args) {
        if (!player.hasPermission("planets.create")) {
            player.sendMessage(Component.text("You don't have permission to create planets.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /planets create <name> [normal|nether|the_end|station] [generation]").color(NamedTextColor.YELLOW));
            return;
        }

        String name = args[1];
        if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
            player.sendMessage(Component.text("Invalid world name '").color(NamedTextColor.RED)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text("'. Use letters, numbers, underscores or dashes (no spaces).").color(NamedTextColor.RED)));
            return;
        }

        World.Environment environment = World.Environment.NORMAL;
        boolean station = false;
        String generation = "normal";
        if (args.length >= 3) {
            String third = args[2].toLowerCase(Locale.ROOT);
            if (isGeneration(third)) {
                // "mars", "void" or a custom preset id used directly in the type slot:
                // /planets create Mars mars
                if (args.length > 3) {
                    player.sendMessage(Component.text("When using a generation, it must be the last argument.").color(NamedTextColor.RED));
                    return;
                }
                generation = third;
            } else {
                switch (third) {
                    case "normal" -> {
                    }
                    case "nether" -> environment = World.Environment.NETHER;
                    case "end", "the_end" -> environment = World.Environment.THE_END;
                    case "station", "space" -> station = true;
                    default -> {
                        player.sendMessage(Component.text("Unknown type '").color(NamedTextColor.RED)
                                .append(Component.text(args[2]).color(NamedTextColor.YELLOW))
                                .append(Component.text("'. Use normal, nether, the_end or station, or a generation: ")
                                        .color(NamedTextColor.RED))
                                .append(Component.text(String.join(", ", generationIds())).color(NamedTextColor.YELLOW))
                                .append(Component.text(".").color(NamedTextColor.RED)));
                        return;
                    }
                }
                if (args.length >= 4) {
                    String fourth = args[3].toLowerCase(Locale.ROOT);
                    if (!isGeneration(fourth)) {
                        player.sendMessage(Component.text("Unknown generation '").color(NamedTextColor.RED)
                                .append(Component.text(args[3]).color(NamedTextColor.YELLOW))
                                .append(Component.text("'. Use " + String.join(", ", generationIds())
                                        + ", or a preset from generation-presets in config.yml.").color(NamedTextColor.RED)));
                        return;
                    }
                    generation = fourth;
                }
            }
        }

        for (World world : Bukkit.getWorlds()) {
            if (world.getName().equalsIgnoreCase(name)) {
                player.sendMessage(Component.text("A world named '").color(NamedTextColor.RED)
                        .append(Component.text(name).color(NamedTextColor.YELLOW))
                        .append(Component.text("' is already loaded.").color(NamedTextColor.RED)));
                return;
            }
        }
        if (!MultiverseHook.isPresent()) {
            player.sendMessage(Component.text("Multiverse-Core is required to create worlds.").color(NamedTextColor.RED));
            return;
        }

        // Custom generations (mars, void, config presets) cannot be expressed through
        // Multiverse, so they are created directly through the Bukkit WorldCreator API
        // with the world type set to FLAT and the flat generator settings JSON attached.
        // Multiverse adopts the world automatically when it loads.
        if (!generation.equals("normal")) {
            if (environment != World.Environment.NORMAL) {
                player.sendMessage(Component.text("Generations only apply to normal-type planets; create the nether/end planet separately.").color(NamedTextColor.RED));
                return;
            }
            PlanetTerrain.Spec spec = generationSpec(generation);
            if (spec == null) {
                player.sendMessage(Component.text("Unknown generation '").color(NamedTextColor.RED)
                        .append(Component.text(generation).color(NamedTextColor.YELLOW))
                        .append(Component.text("'.").color(NamedTextColor.RED)));
                return;
            }
            player.sendMessage(Component.text("Creating the world '").color(NamedTextColor.GRAY)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text("' with generation '").color(NamedTextColor.GRAY))
                    .append(Component.text(generation).color(NamedTextColor.YELLOW))
                    .append(Component.text(" (").color(NamedTextColor.GRAY))
                    .append(Component.text(PlanetTerrain.describe(spec)).color(NamedTextColor.YELLOW))
                    .append(Component.text(")...").color(NamedTextColor.GRAY)));
            World created = createPlanetWorld(name, spec);
            if (created != null) {
                onGenerationPlanetCreated(player, name, generation, spec);
            } else {
                player.sendMessage(Component.text("The world '").color(NamedTextColor.RED)
                        .append(Component.text(name).color(NamedTextColor.YELLOW))
                        .append(Component.text("' couldn't be created. Check the console — the folder may already exist.").color(NamedTextColor.RED)));
            }
            return;
        }

        // Effectively-final copies for the lambda below.
        World.Environment env = environment;
        boolean isStation = station;

        player.sendMessage(Component.text("Creating the world '").color(NamedTextColor.GRAY)
                .append(Component.text(name).color(NamedTextColor.YELLOW))
                .append(Component.text("'... this can take a few seconds.").color(NamedTextColor.GRAY)));

        // Station worlds are created as flat superflat so there is a clean,
        // buildable base at spawn (no ocean/terrain to clear first).
        // All new worlds use a 15-chunk radius (30-chunk diameter).
        String extra = isStation ? " --world-type flat --diameter 30" : " --diameter 30";
        boolean dispatched = getServer().dispatchCommand(getServer().getConsoleSender(),
                "mv create " + name + " " + env.name().toLowerCase(Locale.ROOT) + extra);
        if (!dispatched) {
            player.sendMessage(Component.text("Couldn't reach the Multiverse command; is Multiverse-Core enabled?").color(NamedTextColor.RED));
            return;
        }

        // Creation is synchronous, so the world should exist right away; double-check
        // shortly after in case Multiverse is still finishing spawn setup.
        World created = Bukkit.getWorld(name);
        if (created != null) {
            onPlanetCreated(player, name, env, isStation);
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (Bukkit.getWorld(name) != null) {
                onPlanetCreated(player, name, env, isStation);
            } else {
                player.sendMessage(Component.text("The world '").color(NamedTextColor.RED)
                        .append(Component.text(name).color(NamedTextColor.YELLOW))
                        .append(Component.text("' couldn't be created. Check the console — the folder may already exist.").color(NamedTextColor.RED)));
            }
        }, 100L);
    }

    private void onPlanetCreated(Player player, String name, World.Environment environment, boolean station) {
        if (station) {
            // Station worlds land players at the spawn and get a metal menu icon.
            getConfig().set("station-worlds." + name, true);
            getConfig().set("icons." + name, Material.IRON_BLOCK.name());
            saveConfigQuietly();
            PlanetTravel.loadConfig(getConfig());
        }
        // Set the world border for the new planet.
        setPlanetWorldBorder(name);
        player.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                .append(Component.text(name).color(NamedTextColor.YELLOW))
                .append(Component.text(" created! Teleporting you there...").color(NamedTextColor.GREEN)));
        PlanetTravel.teleport(player, new Planet(name, iconFor(environment), name));
    }

    private static Material iconFor(World.Environment environment) {
        return switch (environment) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
    }

    // ── Generation presets (mars, void, custom) ──────────────────────────

    /**
     * The terrain of a generation preset, or {@code null} when the id is
     * unknown. Custom presets from config.yml take priority, then the "void"
     * world, then the built-in planet archetypes — so
     * "/planets create &lt;name&gt; moon" builds a moon planet with exactly the
     * generation a buyer would get.
     */
    private PlanetTerrain.Spec generationSpec(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ConfigurationSection presets = getConfig().getConfigurationSection("generation-presets");
        if (presets != null) {
            ConfigurationSection section = presets.getConfigurationSection(id);
            if (section != null) {
                PlanetTerrain.Spec spec = specFromConfig(section);
                if (spec != null) {
                    return spec;
                }
            }
            String raw = presets.getString(id);
            if (raw != null) {
                PlanetTerrain.Spec spec = PlanetTerrain.parseJson(raw);
                if (spec != null) {
                    return spec;
                }
            }
        }
        if (id.equalsIgnoreCase("void")) {
            return PlanetTerrain.voidSpec();
        }
        PlanetArchetypes.Archetype archetype = PlanetArchetypes.byId(id);
        return archetype == null ? null : archetype.terrain();
    }

    /** Whether {@code id} is a generation this server can build (preset or archetype). */
    private boolean isGeneration(String id) {
        return generationSpec(id) != null;
    }

    /** Every generation id available, for tab completion and error messages. */
    private List<String> generationIds() {
        List<String> ids = new ArrayList<>(PlanetArchetypes.ids());
        ids.add("void");
        ConfigurationSection presets = getConfig().getConfigurationSection("generation-presets");
        if (presets != null) {
            ids.addAll(presets.getKeys(false));
        }
        return ids;
    }



    /**
     * Reads a generation preset from config.yml. Layers are always ordered
     * <b>bottom → top</b> and may be written either as sections
     * ({@code - {block: stone, height: 2}}) or as plain strings
     * ({@code - "stone:2"}). Block and biome names may be Bukkit names
     * ("GRASS_BLOCK") or plain vanilla ids ("grass_block"); the namespace is
     * stripped automatically, because a flat generator fed a name it cannot
     * parse silently produces an empty (void) world.
     */
    private PlanetTerrain.Spec specFromConfig(ConfigurationSection section) {
        String biome = section.getString("biome", "plains");
        List<PlanetTerrain.Layer> layers = new ArrayList<>();
        List<?> raw = section.getList("layers");
        if (raw != null) {
            for (Object entry : raw) {
                if (entry instanceof ConfigurationSection layer) {
                    layers.add(new PlanetTerrain.Layer(layer.getString("block", "stone"),
                            Math.max(1, layer.getInt("height", 1))));
                } else if (entry instanceof Map<?, ?> map) {
                    Object block = map.get("block");
                    Object height = map.get("height");
                    layers.add(new PlanetTerrain.Layer(block == null ? "stone" : block.toString(),
                            height instanceof Number n ? Math.max(1, n.intValue()) : 1));
                } else if (entry != null) {
                    layers.addAll(PlanetTerrain.parseLayerList(List.of(entry.toString())));
                }
            }
        }
        if (layers.isEmpty()) {
            // A raw JSON string works too ("settings" or "json").
            String json = section.getString("settings", section.getString("json"));
            return PlanetTerrain.parseJson(json);
        }
        return new PlanetTerrain.Spec(biome, layers);
    }

    /**
     * Builds a flat-generator JSON string from a config section with a
     * "layers" list and an optional "biome".
     */
    private String flatSettingsFromConfig(ConfigurationSection section) {
        PlanetTerrain.Spec spec = specFromConfig(section);
        return spec == null ? null : PlanetTerrain.toJson(spec);
    }

    /**
     * Creates a brand-new FLAT world whose generator settings carry the given
     * terrain, so the world keeps generating themed ground for every new chunk
     * and — because those settings live in the world's own level.dat — keeps it
     * after a restart.
     *
     * @return the new world, or {@code null} when creation fails (for example
     *         when a folder with that name is already on disk).
     */
    private World createPlanetWorld(String worldName, PlanetTerrain.Spec spec) {
        return createPlanetWorld(worldName, spec, structuresEnabled(worldName));
    }

    /**
     * Creates a FLAT (or normal) world, optionally turning structure generation
     * off for it. That flag lives in the world's own level.dat, so it survives
     * restarts and applies to every chunk generated later.
     */
    private World createPlanetWorld(String worldName, PlanetTerrain.Spec spec, boolean generateStructures) {
        // Never load an existing folder: this must always generate fresh terrain.
        if (worldFolderExists(worldName)) {
            getLogger().warning("Refused to create world '" + worldName
                    + "': a folder with that name already exists on disk.");
            return null;
        }
        try {
            WorldCreator creator = new WorldCreator(worldName);
            creator.environment(World.Environment.NORMAL);
            creator.generateStructures(generateStructures);
            if (spec == null) {
                creator.type(WorldType.NORMAL);
            } else {
                creator.type(WorldType.FLAT);
                // The flat generator only understands lowercase, namespace-free
                // block and biome ids — anything else silently yields an empty
                // (void) world, which PlanetTerrain.toJson guarantees not to emit.
                creator.generatorSettings(PlanetTerrain.toJson(spec));
            }
            creator.seed(java.util.concurrent.ThreadLocalRandom.current().nextLong());
            return creator.createWorld();
        } catch (Exception ex) {
            getLogger().warning("Failed to create world '" + worldName + "' with generation: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Post-creation callback for generation-based planets: saves the icon and
     * terrain, reloads configs, lays the ground inside the border and teleports
     * the admin into the fresh world.
     */
    private void onGenerationPlanetCreated(Player player, String name, String generation, PlanetTerrain.Spec spec) {
        // Archetype generations (mars, moon, lava, ...) apply their full unique
        // identity — icon, sky colors, particles, effects, weather.
        PlanetArchetypes.Archetype archetype = PlanetArchetypes.byId(generation);
        if (archetype != null) {
            applyArchetypeIdentity(name, archetype);
        } else {
            // Other generations get a themed icon.
            Material icon = switch (generation) {
                case "void" -> Material.BARRIER;
                default -> Material.GRASS_BLOCK;
            };
            getConfig().set("icons." + name, icon.name());
            saveConfigQuietly();
        }
        // Remember the terrain so the ground can be checked/repaired later.
        PlanetTerrain.save(getConfig(), name, spec);
        saveConfigQuietly();
        PlanetTravel.loadConfig(getConfig());
        // Set the world border for the new planet.
        setPlanetWorldBorder(name);
        PlanetTerrain.ensureFloor(Bukkit.getWorld(name), name, spec);
        player.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                .append(Component.text(name).color(NamedTextColor.YELLOW))
                .append(Component.text(" created with generation ").color(NamedTextColor.GREEN))
                .append(Component.text(generation).color(NamedTextColor.YELLOW))
                .append(Component.text(" — ").color(NamedTextColor.GRAY))
                .append(Component.text(PlanetTerrain.describe(spec)).color(NamedTextColor.YELLOW))
                .append(Component.text(". Teleporting you there...").color(NamedTextColor.GREEN)));
        PlanetTravel.teleport(player, new Planet(name,
                archetype != null ? archetype.icon() : Material.GRASS_BLOCK, name));
    }

    // ── Generation previews (throwaway worlds) ──────────────────────────

    /** Where each admin was standing before entering a preview world. */
    private final Map<UUID, Location> previewReturn = new HashMap<>();

    /** Preview world names, remembered so they stay invisible and get cleaned up. */
    private List<String> previewWorldNames() {
        return new ArrayList<>(getConfig().getStringList("preview-worlds"));
    }

    /** Whether a world is a temporary generation preview. */
    private boolean isPreviewWorld(String worldName) {
        for (String name : previewWorldNames()) {
            if (name.equalsIgnoreCase(worldName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handles "/planets preview &lt;generation&gt;" and "/planets preview end".
     * Previews are real worlds built with the generation's terrain and identity,
     * so an admin can walk around one before creating a planet with it; they are
     * hidden from /planets and thrown away on "/planets preview end" (or on the
     * next startup).
     */
    private void previewGeneration(Player player, String[] args) {
        if (!player.hasPermission("planets.create")) {
            player.sendMessage(Component.text("You don't have permission to preview generations.")
                    .color(NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /planets preview <generation|end>").color(NamedTextColor.YELLOW));
            player.sendMessage(Component.text("Generations: " + String.join(", ", generationIds())
                    + " (or a preset from generation-presets in config.yml)").color(NamedTextColor.GRAY));
            return;
        }

        String generation = args[1].toLowerCase(Locale.ROOT);
        if (generation.equals("end") || generation.equals("off")
                || generation.equals("discard") || generation.equals("clear")) {
            endPreviews(player);
            return;
        }

        PlanetTerrain.Spec spec = generationSpec(generation);
        if (spec == null) {
            player.sendMessage(Component.text("Unknown generation '").color(NamedTextColor.RED)
                    .append(Component.text(args[1]).color(NamedTextColor.YELLOW))
                    .append(Component.text("'. Try ").color(NamedTextColor.RED))
                    .append(Component.text(String.join(", ", generationIds())).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return;
        }

        // One preview world per generation: an older one is removed first so the
        // terrain is generated fresh each time.
        String worldName = "preview_" + generation.replaceAll("[^a-z0-9_]", "");
        if (worldFolderExists(worldName) || Bukkit.getWorld(worldName) != null || myPlanetManager.isRetired(worldName)) {
            clearWorldConfig(worldName);
            deleteWorldNow(worldName);
        }

        World world = createPlanetWorld(worldName, spec);
        if (world == null) {
            player.sendMessage(Component.text("Could not build a preview world for '").color(NamedTextColor.RED)
                    .append(Component.text(generation).color(NamedTextColor.YELLOW))
                    .append(Component.text("' — check the console. A leftover folder may still be locked.")
                            .color(NamedTextColor.RED)));
            return;
        }

        // Give the preview the same look a real planet of this type would have.
        PlanetArchetypes.Archetype archetype = PlanetArchetypes.byId(generation);
        if (archetype != null) {
            applyArchetypeIdentity(worldName, archetype);
        }
        PlanetTerrain.save(getConfig(), worldName, spec);
        List<String> previews = previewWorldNames();
        previews.removeIf(name -> name.equalsIgnoreCase(worldName));
        previews.add(worldName);
        getConfig().set("preview-worlds", previews);
        saveConfigQuietly();
        PlanetTravel.loadConfig(getConfig());

        // A visible, walkable area to judge the ground in.
        double radius = getConfig().getDouble("planet-world-border-radius", 15.0);
        setWorldBorderForPlanet(world, radius > 0 ? radius : 64);
        PlanetTerrain.ensureFloor(world, worldName, spec);

        previewReturn.putIfAbsent(player.getUniqueId(), player.getLocation());
        player.teleport(world.getSpawnLocation());
        player.sendMessage(Component.text("\uD83D\uDD0D Preview of ").color(NamedTextColor.AQUA)
                .append(Component.text(generation).color(NamedTextColor.YELLOW))
                .append(Component.text(" — ").color(NamedTextColor.GRAY))
                .append(Component.text(PlanetTerrain.describe(spec)).color(NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("World '").color(NamedTextColor.GRAY)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text("' is hidden from /planets. Run ").color(NamedTextColor.GRAY))
                .append(Component.text("/planets preview end").color(NamedTextColor.AQUA))
                .append(Component.text(" to discard it and come back.").color(NamedTextColor.GRAY)));
    }

    /** Deletes every preview world and sends the admin back where they started. */
    private void endPreviews(Player player) {
        List<String> previews = previewWorldNames();
        if (previews.isEmpty()) {
            player.sendMessage(Component.text("There are no preview worlds to discard.").color(NamedTextColor.GRAY));
            return;
        }
        if (isPreviewWorld(player.getWorld().getName())) {
            Location back = previewReturn.remove(player.getUniqueId());
            Location target = back != null && back.getWorld() != null
                    ? back
                    : hubLocation(new Planet(player.getWorld().getName(), Material.GRASS_BLOCK, player.getWorld().getName()));
            if (target != null) {
                player.teleport(target);
            }
        }
        int removed = 0;
        for (String name : previews) {
            clearWorldConfig(name);
            if (deleteWorldNow(name)) {
                removed++;
            }
        }
        getConfig().set("preview-worlds", null);
        saveConfigQuietly();
        // Drop the preview identity from the live caches (sky, particles, effects).
        PlanetTravel.loadConfig(getConfig());
        PlanetEffects.loadConfig(getConfig());
        environment.loadConfig(getConfig());
        player.sendMessage(Component.text("\uD83D\uDDD1 Discarded " + removed + " preview world(s).").color(NamedTextColor.GREEN));
    }

    /**
     * Unloads a world, deletes its folder and makes Multiverse forget it, moving
     * anyone inside to the hub first. Used for previews and duplicate worlds.
     */
    private boolean deleteWorldNow(String worldName) {
        PlanetTravel.cancelPendingTeleportsForWorld(worldName);
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) {
            Location hub = hubLocation(new Planet(worldName, Material.GRASS_BLOCK, worldName));
            for (Player inside : new ArrayList<>(loaded.getPlayers())) {
                if (hub != null) {
                    inside.teleport(hub);
                }
                inside.sendMessage(Component.text("The world you were in ('" + worldName
                        + "') has been removed — you were moved out.").color(NamedTextColor.YELLOW));
            }
        }
        getServer().dispatchCommand(getServer().getConsoleSender(), "mv unload " + worldName);
        File folder = new File(Bukkit.getWorldContainer(), worldName);
        if (folder.exists()) {
            deleteWorldFolder(folder);
        }
        if (MultiverseHook.isPresent()) {
            MultiverseHook.forget(worldName);
        }
        return Bukkit.getWorld(worldName) == null;
    }

    /** Forgets every per-world setting of a world that is being thrown away. */
    private void clearWorldConfig(String worldName) {
        getConfig().set("icons." + worldName, null);
        getConfig().set("station-worlds." + worldName, null);
        getConfig().set("landing-modes." + worldName, null);
        getConfig().set("landing-spots." + worldName, null);
        getConfig().set("planet-terrain." + worldName, null);
        getConfig().set("sky-colors." + worldName, null);
        getConfig().set("particles." + worldName, null);
        getConfig().set("effects." + worldName, null);
        getConfig().set("weather-lock." + worldName, null);
        getConfig().set("music.tracks." + worldName, null);
    }

    /**
     * Handles "/planets icon <planet> <material|reset>": changes the block shown in the
     * menu for a planet. Stored under "icons" in config.yml, keyed by world name.
     */
    private void setPlanetIcon(Player player, String[] args) {
        if (!player.hasPermission("planets.icon")) {
            player.sendMessage(Component.text("You don't have permission to change planet icons.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /planets icon <planet> <material|reset>").color(NamedTextColor.YELLOW));
            return;
        }

        // Planet name may contain spaces ("Middle Earth"), so the last argument is the material.
        String planetQuery = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
        String materialArg = args[args.length - 1];

        Planet planet = findPlanet(availablePlanets(), planetQuery);
        if (planet == null) {
            player.sendMessage(
                    Component.text("Unknown planet '").color(NamedTextColor.RED)
                            .append(Component.text(planetQuery).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Type /planets to see all the planets.").color(NamedTextColor.RED))
            );
            return;
        }

        String worldName = planet.worldName();
        if (materialArg.equalsIgnoreCase("reset") || materialArg.equals("-")) {
            getConfig().set("icons." + worldName, null);
            saveConfigQuietly();
            player.sendMessage(Component.text("Icon of ").color(NamedTextColor.GREEN)
                    .append(Component.text(planetQuery).color(NamedTextColor.YELLOW))
                    .append(Component.text(" reset to its default.").color(NamedTextColor.GREEN)));
            return;
        }

        Material material = Material.matchMaterial(materialArg);
        if (material == null || material == Material.AIR) {
            player.sendMessage(Component.text("Unknown material '").color(NamedTextColor.RED)
                    .append(Component.text(materialArg).color(NamedTextColor.YELLOW))
                    .append(Component.text("'.").color(NamedTextColor.RED)));
            return;
        }

        getConfig().set("icons." + worldName, material.name());
        saveConfigQuietly();
        player.sendMessage(Component.text("Icon of ").color(NamedTextColor.GREEN)
                .append(Component.text(planetQuery).color(NamedTextColor.YELLOW))
                .append(Component.text(" is now ").color(NamedTextColor.GREEN))
                .append(Component.text(material.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    /**
     * Handles "/planets landing <planet> station|random|point": decides where
     * players land on a planet — the world spawn (station), a fixed spot
     * (point), or a random safe spot. Stored under "landing-modes" in
     * config.yml; "point" spots live under "landing-spots".
     */
    private void setPlanetLanding(Player player, String[] args) {
        if (!player.hasPermission("planets.landing")) {
            player.sendMessage(Component.text("You don't have permission to change where players land.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /planets landing <planet> station|random|point").color(NamedTextColor.YELLOW));
            return;
        }

        // Planet name may contain spaces ("Middle Earth"), so the last argument is the mode.
        String planetQuery = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
        String modeArg = args[args.length - 1];

        String mode = switch (modeArg.toLowerCase(Locale.ROOT)) {
            case "station", "spawn" -> "station";
            case "random", "surface" -> "random";
            case "point", "fixed" -> "point";
            default -> {
                player.sendMessage(Component.text("Unknown landing mode '").color(NamedTextColor.RED)
                        .append(Component.text(modeArg).color(NamedTextColor.YELLOW))
                        .append(Component.text("'. Use station, random or point.").color(NamedTextColor.RED)));
                yield null;
            }
        };
        if (mode == null) {
            return;
        }

        World target = findPlanetWorld(planetQuery);
        if (target == null) {
            player.sendMessage(
                    Component.text("Unknown planet '").color(NamedTextColor.RED)
                            .append(Component.text(planetQuery).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Public planets are in /planets, owned ones in /myp.").color(NamedTextColor.RED))
            );
            return;
        }

        String worldName = target.getName();
        if (mode.equals("point") && !hasLandingSpot(worldName)) {
            // No fixed point set yet: pin the admin's current position as the spot.
            saveLandingSpot(worldName, player.getLocation());
        }
        getConfig().set("landing-modes." + worldName, mode);
        saveConfigQuietly();
        PlanetTravel.loadConfig(getConfig());
        player.sendMessage(Component.text("Players will now ").color(NamedTextColor.GREEN)
                .append(Component.text(switch (mode) {
                    case "station" -> "always land at the world spawn of ";
                    case "point" -> "land at the fixed landing point on ";
                    default -> "land at random safe spots on ";
                }).color(NamedTextColor.GREEN))
                .append(Component.text(planetQuery).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    /**
     * Handles "/planets portals on|off": globally enables or disables every
     * Nether/End portal. When off, no portal works anywhere (players and mobs),
     * so the Nether/End planets are only reachable through /planets.
     */
    private void setPortalsEnabled(Player player, String[] args) {
        if (!player.hasPermission("planets.world")) {
            player.sendMessage(Component.text("You don't have permission to change portal settings.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 2 || (!args[1].equalsIgnoreCase("on") && !args[1].equalsIgnoreCase("off"))) {
            player.sendMessage(Component.text("Usage: /planets portals on|off").color(NamedTextColor.YELLOW));
            return;
        }
        boolean enabled = args[1].equalsIgnoreCase("on");
        getConfig().set("disable-portals", !enabled);
        saveConfigQuietly();
        player.sendMessage(Component.text("Nether/End portals are now ").color(NamedTextColor.GREEN)
                .append(Component.text(enabled ? "enabled" : "disabled").color(NamedTextColor.YELLOW))
                .append(Component.text(enabled
                        ? " everywhere — players can use them again."
                        : " everywhere — no portal works, players must use /planets to reach the Nether/End planets.").color(NamedTextColor.GREEN)));
    }

    /**
     * Opens the buy-a-planet menu for a player.
     */
    void openBuyPlanetMenu(Player player) {
        player.closeInventory();
        new BuyPlanetMenu(this, player).open(player);
    }

    /**
     * Opens the "Planets for Sale" browser menu for a player.
     */
    void openForSaleMenu(Player player) {
        player.closeInventory();
        List<MyPlanetData> forSale = myPlanetManager.forSale();
        new ForSaleMenu(this, player, forSale).open(player);
    }

    /**
     * Completes a planet purchase from the for-sale market. Transfers ownership
     * from the seller to the buyer, charges the buyer the sale price, and pays
     * the seller.
     */
    void buyPlanetFromSale(Player player, MyPlanetData data) {
        if (!player.hasPermission("planets.buy")) {
            player.sendMessage(Component.text("You don't have permission to buy planets.").color(NamedTextColor.RED));
            return;
        }
        if (!hasEconomy()) {
            player.sendMessage(Component.text("No economy plugin found \u2014 cannot buy a planet.").color(NamedTextColor.RED));
            return;
        }
        if (myPlanetManager.ownedCount(player.getUniqueId()) >= myPlanetManager.maxPlanetsPerPlayer()) {
            player.sendMessage(Component.text("You already own the maximum of ")
                    .color(NamedTextColor.RED)
                    .append(Component.text(String.valueOf(myPlanetManager.maxPlanetsPerPlayer()))
                            .color(NamedTextColor.YELLOW))
                    .append(Component.text(" planet(s). Abandon one first.").color(NamedTextColor.RED)));
            return;
        }
        if (!data.forSale()) {
            player.sendMessage(Component.text("That planet is no longer for sale.").color(NamedTextColor.RED));
            return;
        }
        // The seller (and the current owner) can never buy back the planet.
        if (data.ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You already own this planet.").color(NamedTextColor.RED));
            return;
        }
        if (data.lastSeller() != null && data.lastSeller().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You can't buy back a planet you sold.").color(NamedTextColor.RED));
            return;
        }
        double price = data.salePrice();
        double balance = getBalance(player);
        if (balance < 0) {
            player.sendMessage(Component.text("Could not check your balance.").color(NamedTextColor.RED));
            return;
        }
        if (balance < price) {
            player.sendMessage(Component.text("Not enough money! ").color(NamedTextColor.RED)
                    .append(Component.text("You have " + formatPrice(balance) + " VPL but ").color(NamedTextColor.YELLOW))
                    .append(Component.text("need " + formatPrice(price) + " VPL.").color(NamedTextColor.RED)));
            return;
        }
        // Deduct from buyer first; refund on failure.
        if (!withdraw(player, price)) {
            player.sendMessage(Component.text("Purchase failed \u2014 could not deduct money.").color(NamedTextColor.RED));
            return;
        }
        // Pay the seller.
        java.util.UUID sellerUuid = data.ownerUuid();
        var sellerResponse = economy.depositPlayer(Bukkit.getOfflinePlayer(sellerUuid), price);
        if (!sellerResponse.transactionSuccess()) {
            deposit(player, price); // refund buyer
            player.sendMessage(Component.text("Purchase failed \u2014 could not pay the seller.").color(NamedTextColor.RED));
            return;
        }
        // Transfer ownership.
        data.transferOwnership(player.getUniqueId());
        myPlanetManager.save();
        player.sendMessage(Component.text("\uD83C\uDF8A You bought ").color(NamedTextColor.GREEN)
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(" for " + formatPrice(price) + " VPL!").color(NamedTextColor.GREEN)));
        player.sendMessage(Component.text("Use ").color(NamedTextColor.GRAY)
                .append(Component.text("/myp").color(NamedTextColor.AQUA))
                .append(Component.text(" to open its control panel.").color(NamedTextColor.GRAY)));
        // Notify the seller if online.
        Player seller = Bukkit.getPlayer(sellerUuid);
        if (seller != null) {
            balanceNotice(seller, Component.text("\uD83D\uDCB0 ").color(NamedTextColor.GREEN)
                    .append(Component.text(player.getName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" bought your planet ").color(NamedTextColor.GREEN))
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" for " + formatPrice(price) + " VPL!").color(NamedTextColor.GREEN)));
        }
    }

    /**
     * Completes a planet purchase: charges the configured price (VPL money),
     * creates a brand-new default world for the player and claims it, so the
     * world appears in the buyer's /myp panel. Called from the confirm menu.
     */
    /**
     * How many planets with a custom generation a player may own. 0 disables
     * the limit entirely; normal planets are never limited by it (only by
     * {@code my-planet.max-planets-per-player}).
     */
    int maxCustomPlanets() {
        return Math.max(0, getConfig().getInt("my-planet.max-custom-planets", 1));
    }

    /** Whether the player may still buy a custom-generation planet. */
    boolean canBuyCustomPlanet(Player player) {
        int max = maxCustomPlanets();
        if (max == 0) {
            return true;
        }
        int custom = 0;
        for (MyPlanetData owned : myPlanetManager.ownedBy(player.getUniqueId())) {
            if (owned.archetypeId() != null && !owned.archetypeId().isBlank()) {
                custom++;
            }
        }
        if (custom < max) {
            return true;
        }
        player.sendMessage(Component.text("You already have your custom-generation planet")
                .color(NamedTextColor.RED)
                .append(Component.text(max > 1 ? "s (max " + max + ")" : "").color(NamedTextColor.YELLOW))
                .append(Component.text(". You can still buy a ").color(NamedTextColor.RED))
                .append(Component.text("Normal planet").color(NamedTextColor.AQUA))
                .append(Component.text(", or pick another type for a planet you already own.")
                        .color(NamedTextColor.RED)));
        return false;
    }

    void buyNewPlanet(Player player, String archetypeId) {
        if (!player.hasPermission("planets.buy")) {
            player.sendMessage(Component.text("You don't have permission to buy planets.").color(NamedTextColor.RED));
            return;
        }
        if (!hasEconomy()) {
            player.sendMessage(Component.text("Buying a planet requires an economy plugin (Vault).").color(NamedTextColor.RED));
            return;
        }
        if (myPlanetManager.ownedCount(player.getUniqueId()) >= myPlanetManager.maxPlanetsPerPlayer()) {
            player.sendMessage(Component.text("You already own the maximum of ").color(NamedTextColor.RED)
                    .append(Component.text(String.valueOf(myPlanetManager.maxPlanetsPerPlayer())).color(NamedTextColor.YELLOW))
                    .append(Component.text(" planet(s).").color(NamedTextColor.RED)));
            return;
        }

        double price = myPlanetManager.buyCost();
        double balance = getBalance(player);
        if (balance < 0) {
            player.sendMessage(Component.text("Could not check your balance. Is the economy plugin working?").color(NamedTextColor.RED));
            return;
        }
        if (balance < price) {
            player.sendMessage(Component.text("Not enough money! ").color(NamedTextColor.RED)
                    .append(Component.text("You have " + formatPrice(balance) + " but ").color(NamedTextColor.YELLOW))
                    .append(Component.text("need " + formatPrice(price) + " VPL.").color(NamedTextColor.RED)));
            return;
        }

        // "normal" (or nothing) buys classic overworld terrain; anything else is a
        // custom generation. Players may own only a limited number of custom ones.
        boolean normal = archetypeId == null || archetypeId.isBlank()
                || archetypeId.equalsIgnoreCase("normal") || archetypeId.equalsIgnoreCase("classic");
        PlanetArchetypes.Archetype archetype = normal ? null : PlanetArchetypes.byId(archetypeId);
        if (!normal && archetype == null) {
            normal = true; // unknown type: a normal planet beats refusing the purchase
        }
        if (!normal && !canBuyCustomPlanet(player)) {
            return; // the message is sent by the check, before any money moves
        }

        // Deduct first; refund on failure.
        if (!withdraw(player, price)) {
            player.sendMessage(Component.text("Purchase failed — could not deduct money.").color(NamedTextColor.RED));
            return;
        }

        // This planet's terrain: the archetype's layers with a per-planet height
        // variation (or none at all for a normal planet). It is written to
        // config.yml so the ground can always be verified/repaired (and
        // hand-edited by admins) afterwards.
        PlanetTerrain.Spec terrain = normal ? null : PlanetTerrain.jitter(archetype.terrain());

        // Try a few free names before giving up: a refusal never hands the
        // buyer an existing world — it either lands on fresh terrain or refunds.
        World created = null;
        String worldName = null;
        for (int attempt = 0; attempt < 3 && created == null; attempt++) {
            worldName = newPlanetWorldName(player, worldName);
            created = createPlanetWorld(worldName, terrain);
        }
        if (created == null) {
            deposit(player, price); // refund
            player.sendMessage(Component.text("Could not create your planet — you were refunded.").color(NamedTextColor.RED));
            return;
        }

        MyPlanetData data = myPlanetManager.claim(worldName, player.getUniqueId());
        if (data == null) {
            deposit(player, price); // refund; claim failed (e.g. limit hit)
            player.sendMessage(Component.text("Could not claim your planet — you were refunded.").color(NamedTextColor.RED));
            return;
        }
        // A planet is bought at the starter size: a 5x5 block plot around spawn.
        // Upgrading Planet Size in /myp grows the border (and the ground) with it.
        data.setUpgradeLevel(MyPlanetData.Upgrade.PLANET_SIZE, 0);
        data.archetypeId(archetype == null ? null : archetype.id());
        if (terrain != null) {
            PlanetTerrain.save(getConfig(), worldName, terrain);
        }
        saveConfigQuietly();
        if (archetype != null) {
            applyArchetypeIdentity(worldName, archetype);
        }
        myPlanetManager.save();
        // Sets the starter border and lays the ground inside it (the world is
        // still unbordered until now, so the terrain pass must come after it).
        updateWorldBorder(data);
        player.teleport(created.getSpawnLocation());
        player.sendMessage(Component.text("Starter size: ").color(NamedTextColor.GRAY)
                .append(Component.text(MyPlanetData.sizeName(0)).color(NamedTextColor.YELLOW))
                .append(Component.text(" — grow it under ").color(NamedTextColor.GRAY))
                .append(Component.text("/myp → Upgrades").color(NamedTextColor.AQUA))
                .append(Component.text(".").color(NamedTextColor.GRAY)));
        player.sendMessage(Component.text("\uD83C\uDF8A You bought a ").color(NamedTextColor.GREEN)
                .append(Component.text(archetype == null
                        ? "normal planet" : archetype.displayName() + " planet").color(NamedTextColor.AQUA))
                .append(Component.text(" for ").color(NamedTextColor.GREEN))
                .append(Component.text(formatPrice(price) + " VPL").color(NamedTextColor.GREEN))
                .append(Component.text("!").color(NamedTextColor.GREEN)));
        player.sendMessage(Component.text("Use ").color(NamedTextColor.GRAY)
                .append(Component.text("/myp").color(NamedTextColor.AQUA))
                .append(Component.text(" to open its control panel.").color(NamedTextColor.GRAY)));
        sendBalance(player);
    }

    /**
     * Applies an archetype's unique identity to a freshly created planet:
     * menu icon, sky/fog colors, ambient particles, planet physics effects
     * and locked weather are written to config.yml so the planet looks and
     * feels different from every other one.
     */
    private void applyArchetypeIdentity(String worldName, PlanetArchetypes.Archetype archetype) {
        if (archetype.icon() != null) {
            getConfig().set("icons." + worldName, archetype.icon().name());
        }
        if (archetype.skyColor() != null) {
            getConfig().set("sky-colors." + worldName + ".sky", archetype.skyColor());
            getConfig().set("sky-colors." + worldName + ".fog", archetype.fogColor());
            getConfig().set("sky-colors." + worldName + ".density", 8);
        }
        if (archetype.particles() != null) {
            getConfig().set("particles." + worldName + ".effect", archetype.particles());
            getConfig().set("particles." + worldName + ".density", archetype.particleDensity());
        }
        for (Map.Entry<String, Integer> entry : archetype.effects().entrySet()) {
            getConfig().set("effects." + worldName + "." + entry.getKey(), entry.getValue());
        }
        if (archetype.weather() != null) {
            getConfig().set("weather-lock." + worldName, archetype.weather());
        }
        saveConfigQuietly();
        PlanetEffects.loadConfig(getConfig());
        environment.loadConfig(getConfig());
    }

    /**
     * Picks a free world name for a player's new planet (sanitized player
     * name, numbered). {@code after} is the previous candidate that failed to
     * create — the search resumes past it so the same name is not retried.
     * A name is free only when no loaded world, owned planet, retired name,
     * or on-disk folder (case-insensitive) uses it, so a buyer can never be
     * handed a previously used or deleted planet.
     */
    private String newPlanetWorldName(Player player, String after) {
        String base = player.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (base.isEmpty()) {
            base = "planet";
        }
        int n = 2;
        if (after == null) {
            return firstFreePlanetName(base, null);
        }
        // Resume numbering after the failed candidate (base, base_2, base_3...).
        // The exact failed name is skipped so a transient creation failure
        // can't make all retries collide with the same name.
        if (after.equalsIgnoreCase(base)) {
            return firstFreePlanetName(base, after);
        }
        if (after.toLowerCase(Locale.ROOT).startsWith(base + "_")) {
            String suffix = after.substring(base.length() + 1);
            try {
                n = Math.max(n, Integer.parseInt(suffix) + 1);
            } catch (NumberFormatException ignored) {
            }
        }
        String candidate = base + "_" + n;
        while (Bukkit.getWorld(candidate) != null || myPlanetManager.get(candidate) != null
                || myPlanetManager.isRetired(candidate)
                || worldFolderExists(candidate)) {
            candidate = base + "_" + (++n);
        }
        return candidate;
    }

    /** First free name for the base: "base", then "base_2", "base_3", ... */
    private String firstFreePlanetName(String base, String skipExact) {
        String candidate = base;
        int n = 2;
        while (Bukkit.getWorld(candidate) != null || myPlanetManager.get(candidate) != null
                || myPlanetManager.isRetired(candidate)
                || worldFolderExists(candidate)
                || (skipExact != null && candidate.equals(skipExact))) {
            candidate = base + "_" + n++;
        }
        return candidate;
    }

    /**
     * Whether a world folder with this name already exists on disk. Leftover
     * folders from deleted or abandoned planets must never be reused for a
     * brand-new purchase: {@code WorldCreator.createWorld()} would silently
     * load the old world — with all its previous builds — instead of
     * generating fresh terrain. The scan is case-insensitive (a "Fivos"
     * folder must block a "fivos" request) so no leftover folder can slip
     * through on case-sensitive filesystems.
     */
    private boolean worldFolderExists(String worldName) {
        if (new File(Bukkit.getWorldContainer(), worldName).isDirectory()) {
            return true;
        }
        File[] children = Bukkit.getWorldContainer().listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                if (child.getName().equalsIgnoreCase(worldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Creates a brand-new planet world with the archetype's unique terrain. */
    private World createDefaultPlanetWorld(String worldName, PlanetArchetypes.Archetype archetype) {
        return createPlanetWorld(worldName, archetype == null ? null : archetype.terrain());
    }

    /**
     * Handles "/planets setlanding <planet>": pins the admin's current position
     * as the planet's landing point and switches it to "point" mode, so every
     * arrival happens exactly there.
     */
    private void setLandingPoint(Player player, String[] args) {
        if (!player.hasPermission("planets.landing")) {
            player.sendMessage(Component.text("You don't have permission to change where players land.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /planets setlanding <planet> — stand where arrivals should land").color(NamedTextColor.YELLOW));
            return;
        }

        String planetQuery = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        World target = findPlanetWorld(planetQuery);
        if (target == null) {
            player.sendMessage(
                    Component.text("Unknown planet '").color(NamedTextColor.RED)
                            .append(Component.text(planetQuery).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Public planets are in /planets, owned ones in /myp.").color(NamedTextColor.RED))
            );
            return;
        }

        String worldName = target.getName();
        Location location = player.getLocation();
        saveLandingSpot(worldName, location);
        getConfig().set("landing-modes." + worldName, "point");
        saveConfigQuietly();
        PlanetTravel.loadConfig(getConfig());
        player.sendMessage(Component.text("Landing point of ").color(NamedTextColor.GREEN)
                .append(Component.text(planetQuery).color(NamedTextColor.YELLOW))
                .append(Component.text(" set to your position (").color(NamedTextColor.GREEN))
                .append(Component.text(location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ()).color(NamedTextColor.YELLOW))
                .append(Component.text("). Players will arrive here.").color(NamedTextColor.GREEN)));
    }

    /**
     * Handles "/planets delete <name>": unloads and deletes a world through
     * Multiverse's own {@code /mv delete} (which also removes the folder), then
     * confirms it. The main world and the pinned "Middle Earth" world are
     * protected. Worlds that aren't loaded can still be deleted by raw name.
     */
    private void deletePlanet(Player player, String[] args) {
        if (!player.hasPermission("planets.delete")) {
            player.sendMessage(Component.text("You don't have permission to delete planets.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /planets delete <name>").color(NamedTextColor.YELLOW));
            return;
        }

        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Planet planet = findPlanet(availablePlanets(), query);
        String worldName = planet != null ? planet.worldName() : query;

        World world = Bukkit.getWorld(worldName);
        if (world != null && world.equals(Bukkit.getWorlds().get(0))) {
            player.sendMessage(Component.text("The main world can't be deleted.").color(NamedTextColor.RED));
            return;
        }
        if (worldName.equalsIgnoreCase(getConfig().getString("middle-earth-world", "Middle_earth"))) {
            player.sendMessage(Component.text("Middle Earth is the plugin's home world and can't be deleted.").color(NamedTextColor.RED));
            return;
        }
        if (planet == null && world == null && !new File(Bukkit.getWorldContainer(), worldName).isDirectory()) {
            player.sendMessage(
                    Component.text("Unknown planet '").color(NamedTextColor.RED)
                            .append(Component.text(query).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Type /planets to see all the planets.").color(NamedTextColor.RED))
            );
            return;
        }
        if (!MultiverseHook.isPresent()) {
            player.sendMessage(Component.text("Multiverse-Core is required to delete worlds.").color(NamedTextColor.RED));
            return;
        }

        // Require the player to type the world name again to confirm.
        pendingDeletes.put(player.getUniqueId(), worldName);
        player.sendMessage(Component.text("To confirm deletion of '").color(NamedTextColor.YELLOW)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text("', type '").color(NamedTextColor.YELLOW))
                .append(Component.text(worldName).color(NamedTextColor.GREEN))
                .append(Component.text("' in chat. Type ").color(NamedTextColor.YELLOW))
                .append(Component.text("cancel").color(NamedTextColor.RED))                .append(Component.text("to abort.").color(NamedTextColor.YELLOW)));
    }

    /** Recursively deletes a world folder and all its children on disk. */
    private void deleteWorldFolder(File folder) {
        if (!folder.exists()) return;
        File[] children = folder.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteWorldFolder(child);
            }
        }
        folder.delete();
    }

    /** Stores a fixed landing spot for a world (used by "point" landing mode). */
    private void saveLandingSpot(String worldName, Location location) {
        getConfig().set("landing-spots." + worldName + ".x", location.getX());
        getConfig().set("landing-spots." + worldName + ".y", location.getY());
        getConfig().set("landing-spots." + worldName + ".z", location.getZ());
        getConfig().set("landing-spots." + worldName + ".yaw", (double) location.getYaw());
        getConfig().set("landing-spots." + worldName + ".pitch", (double) location.getPitch());
    }

    private boolean hasLandingSpot(String worldName) {
        ConfigurationSection spot = getConfig().getConfigurationSection("landing-spots." + worldName);
        return spot != null && spot.contains("x") && spot.contains("y") && spot.contains("z");
    }

    /** World names (lowercase) that require the planets.tp.&lt;world&gt; permission. */
    private Set<String> lockedWorlds() {
        Set<String> locked = new HashSet<>();
        for (String worldName : getConfig().getStringList("locked-worlds")) {
            locked.add(worldName.toLowerCase(Locale.ROOT));
        }
        return locked;
    }

    /** Whether the player may teleport to this planet (locked planets need an explicit permission). */
    boolean canVisit(Player player, Planet planet) {
        String key = planet.worldName().toLowerCase(Locale.ROOT);
        return !lockedWorlds().contains(key) || hasVisitPermission(player, key);
    }

    /**
     * Whether the player was explicitly granted the {@code planets.tp.<world>}
     * permission for a locked planet, or a covering wildcard (planets.tp.*,
     * planets.* or *). Bukkit hands ops every permission implicitly, so op
     * status alone does NOT count here: locked planets stay locked for ops too
     * unless a permission plugin (LuckPerms, PEX, ...) actually grants the node.
     */
    static boolean hasVisitPermission(Player player, String worldKey) {
        String key = worldKey.toLowerCase(Locale.ROOT);
        String node = "planets.tp." + key;
        // For non-ops a plain hasPermission check reflects real permission grants.
        if (!player.isOp() && player.hasPermission(node)) {
            return true;
        }
        // Explicit grants are visible as permission attachments, which is how
        // even ops can be recognized as truly allowed (ops get no attachments
        // from Bukkit's implicit "op has everything" shortcut).
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String granted = info.getPermission();
            if (granted.equalsIgnoreCase(node)
                    || granted.equalsIgnoreCase("planets.tp.*")
                    || granted.equalsIgnoreCase("planets.*")
                    || granted.equals("*")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handles "/planets lock <planet>": toggles whether visiting the planet
     * requires the planets.tp.&lt;world&gt; permission. Locked planets gray out
     * in the menu for players without it.
     */
    private void togglePlanetLock(Player player, String[] args) {
        if (!player.hasPermission("planets.lock")) {
            player.sendMessage(Component.text("You don't have permission to lock planets.").color(NamedTextColor.RED));
            return;
        }
        boolean cancelling = args.length >= 2 && args[1].equalsIgnoreCase("cancel");
        if (args.length < 2 || (cancelling && args.length < 3)) {
            player.sendMessage(Component.text(cancelling
                    ? "Usage: /planets lock cancel <planet>"
                    : "Usage: /planets lock <planet>").color(NamedTextColor.YELLOW));
            return;
        }

        String query = String.join(" ", Arrays.copyOfRange(args, cancelling ? 2 : 1, args.length));
        Planet planet = findPlanet(availablePlanets(), query);
        if (planet == null) {
            player.sendMessage(
                    Component.text("Unknown planet '").color(NamedTextColor.RED)
                            .append(Component.text(query).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Type /planets to see all the planets.").color(NamedTextColor.RED))
            );
            return;
        }

        String worldName = planet.worldName();
        String key = worldName.toLowerCase(Locale.ROOT);
        if (cancelling) {
            PendingLock pending = pendingLocks.remove(key);
            if (pending == null) {
                boolean alreadyLocked = getConfig().getStringList("locked-worlds").stream()
                        .anyMatch(name -> name.equalsIgnoreCase(worldName));
                if (alreadyLocked) {
                    player.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                            .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                            .append(Component.text(" is already locked — use /planets lock <planet> to unlock it.").color(NamedTextColor.GREEN)));
                } else {
                    player.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                            .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                            .append(Component.text(" has no pending lock — it isn't scheduled to be locked.").color(NamedTextColor.GREEN)));
                }
                return;
            }
            pending.cancel();
            player.sendMessage(Component.text("Lock of planet ").color(NamedTextColor.GREEN)
                    .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" (started by ").color(NamedTextColor.GREEN))
                    .append(Component.text(pending.actorName).color(NamedTextColor.YELLOW))
                    .append(Component.text(") cancelled — it stays unlocked for everyone.").color(NamedTextColor.GREEN)));
            return;
        }

        List<String> locked = new ArrayList<>(getConfig().getStringList("locked-worlds"));
        if (locked.removeIf(name -> name.equalsIgnoreCase(worldName))) {
            getConfig().set("locked-worlds", locked);
            saveConfigQuietly();
            player.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                    .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" is now unlocked for everyone.").color(NamedTextColor.GREEN)));
            announceLockChange(player, planet, false);
        } else {
            PendingLock pending = pendingLocks.get(key);
            if (pending != null) {
                // The planet is in the countdown but not locked yet, so a second
                // "/planets lock" toggles the pending lock off instead.
                pending.cancel();
                pendingLocks.remove(key);
                player.sendMessage(Component.text("Lock of planet ").color(NamedTextColor.GREEN)
                        .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                        .append(Component.text(" (started by ").color(NamedTextColor.GREEN))
                        .append(Component.text(pending.actorName).color(NamedTextColor.YELLOW))
                        .append(Component.text(") cancelled — it stays unlocked for everyone.").color(NamedTextColor.GREEN)));
                return;
            }
            int warningSeconds = Math.max(0, getConfig().getInt("lock-warning-seconds", DEFAULT_LOCK_WARNING_SECONDS));
            if (warningSeconds == 0) {
                lockPlanetNow(player, planet);
            } else {
                player.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                        .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                        .append(Component.text(" will be locked in " + warningSeconds
                                + (warningSeconds == 1 ? " second" : " seconds")
                                + " — players currently there are being warned.").color(NamedTextColor.GREEN)));
                schedulePlanetLock(player, planet, warningSeconds);
            }
        }
    }

    /** Applies the lock immediately: stores it, confirms, evicts and announces. */
    private void lockPlanetNow(Player actor, Planet planet) {
        List<String> locked = new ArrayList<>(getConfig().getStringList("locked-worlds"));
        String worldName = planet.worldName();
        if (locked.stream().anyMatch(name -> name.equalsIgnoreCase(worldName))) {
            return; // already locked (shouldn't happen, but never double-lock)
        }
        locked.add(worldName);
        getConfig().set("locked-worlds", locked);
        saveConfigQuietly();
        if (actor.isOnline()) {
            actor.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                    .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" is now locked. Players need the ").color(NamedTextColor.GREEN))
                    .append(Component.text("planets.tp." + worldName.toLowerCase(Locale.ROOT)).color(NamedTextColor.YELLOW))
                    .append(Component.text(" permission to visit it.").color(NamedTextColor.GREEN)));
        }
        evictLockedOutOccupants(planet);
        announceLockChange(actor, planet, true);
    }

    /**
     * Warns the players currently on the planet, then locks it once the
     * countdown runs out. The countdown can be aborted with
     * "/planets lock cancel <planet>" (or a plain "/planets lock <planet>" again).
     */
    private void schedulePlanetLock(Player actor, Planet planet, int seconds) {
        String key = planet.worldName().toLowerCase(Locale.ROOT);
        warnOccupants(planet, actor, seconds);
        PendingLock countdown = new PendingLock(this, actor, planet, seconds);
        pendingLocks.put(key, countdown);
        countdown.runTaskTimer(this, 20L, 20L);
    }

    /** Live snapshot of a running lock countdown, shown in the planets menu. */
    record PendingLockInfo(String actorName, int remainingSeconds) {
    }

    /** One planet's lock countdown: ticks down, then applies the lock. */
    private static final class PendingLock extends BukkitRunnable {
        private final Planets plugin;
        private final Player actor;
        private final String actorName;
        private final Planet planet;
        private final String worldKey;
        private int remaining;

        PendingLock(Planets plugin, Player actor, Planet planet, int seconds) {
            this.plugin = plugin;
            this.actor = actor;
            this.actorName = actor.getName();
            this.planet = planet;
            this.worldKey = planet.worldName().toLowerCase(Locale.ROOT);
            this.remaining = seconds;
        }

        @Override
        public void run() {
            remaining--;
            if (remaining <= 0) {
                cancel();
                plugin.pendingLocks.remove(worldKey);
                plugin.lockPlanetNow(actor, planet);
                return;
            }
            plugin.showCountdown(planet, remaining);
        }
    }

    /** Whether a lock countdown is running for a planet, and its details. */
    PendingLockInfo pendingLockInfo(String worldKey) {
        PendingLock pending = pendingLocks.get(worldKey.toLowerCase(Locale.ROOT));
        return pending == null ? null : new PendingLockInfo(pending.actorName, pending.remaining);
    }

    /** One-time chat warning to everyone currently standing on the planet. */
    private void warnOccupants(Planet planet, Player actor, int seconds) {
        World world = Bukkit.getWorld(planet.worldName());
        if (world == null) {
            return;
        }
        Component warning = Component.text("Planet ").color(NamedTextColor.RED)
                .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" will be locked by ").color(NamedTextColor.RED))
                .append(Component.text(actor.getName()).color(NamedTextColor.YELLOW))
                .append(Component.text(" in " + seconds + (seconds == 1 ? " second" : " seconds")
                        + " — leave now or you'll be moved to the hub.").color(NamedTextColor.RED));
        for (Player occupant : world.getPlayers()) {
            occupant.sendMessage(warning);
        }
    }

    /** Per-second action-bar countdown for everyone still on the planet. */
    private void showCountdown(Planet planet, int remaining) {
        World world = Bukkit.getWorld(planet.worldName());
        if (world == null) {
            return;
        }
        Component bar = Component.text(planet.name()).color(NamedTextColor.YELLOW)
                .append(Component.text(" locks in " + remaining + (remaining == 1 ? " second" : " seconds")
                        + " — leave now!").color(NamedTextColor.RED));
        for (Player occupant : world.getPlayers()) {
            sendPriorityBar(occupant, bar);
        }
    }

    /**
     * Tells everyone currently standing on the planet that an admin just locked
     * or unlocked it. The admin running the command is skipped: they already
     * received their own confirmation message above.
     */
    private void announceLockChange(Player actor, Planet planet, boolean locked) {
        World world = Bukkit.getWorld(planet.worldName());
        if (world == null) {
            return;
        }
        Component announcement = Component.text("Planet ").color(NamedTextColor.GOLD)
                .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(locked ? " has been locked by " : " has been unlocked by ").color(NamedTextColor.GOLD))
                .append(Component.text(actor.getName()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GOLD));
        for (Player occupant : world.getPlayers()) {
            if (!occupant.getUniqueId().equals(actor.getUniqueId())) {
                occupant.sendMessage(announcement);
            }
        }
    }

    /**
     * Moves every player standing on a freshly locked planet back to the hub,
     * including the admin who locked it, unless they hold the
     * planets.tp.&lt;world&gt; permission (or a covering wildcard).
     */
    private void evictLockedOutOccupants(Planet planet) {
        World world = Bukkit.getWorld(planet.worldName());
        if (world == null) {
            return;
        }
        for (Player occupant : List.copyOf(world.getPlayers())) {
            if (!canVisit(occupant, planet)) {
                relocate(occupant, planet, "was just locked");
            }
        }
    }

    /**
     * A player who logged out on a planet that was later locked can no longer
     * visit it, so logging back in drops them at the hub instead. Runs one tick
     * after the join so the world switch happens cleanly.
     */
    private void relocateLockedOutPlayer(Player player) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            Planet planet = findPlanet(availablePlanets(), player.getWorld().getName());
            if (planet != null && !canVisit(player, planet)) {
                relocate(player, planet, "is locked and you don't have permission to be there");
            }
        }, 1L);
    }

    /** Teleports the player to the hub and explains why. */
    private void relocate(Player player, Planet planet, String reason) {
        Location hub = hubLocation(planet);
        if (hub == null) {
            return;
        }
        player.teleport(hub);
        player.sendMessage(Component.text("Planet ").color(NamedTextColor.RED)
                .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" " + reason + " — you were moved to ").color(NamedTextColor.RED))
                .append(Component.text(hub.getWorld().getName()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.RED)));
        getLogger().info("Moved " + player.getName() + " out of " + planet.name() + " (" + planet.worldName()
                + ") to " + hub.getWorld().getName() + ": " + reason + ".");
    }

    /**
     * Where locked-out players are sent: the Middle Earth hub world when it is
     * loaded, otherwise the first overworld world that isn't the planet they
     * were kicked from (so nobody is "moved" to the very world they can't visit).
     */
    private Location hubLocation(Planet from) {
        String hubName = getConfig().getString("middle-earth-world", "Middle_earth");
        if (!hubName.equalsIgnoreCase(from.worldName())) {
            World hub = Bukkit.getWorld(hubName);
            if (hub != null && hub.getEnvironment() == World.Environment.NORMAL) {
                return hub.getSpawnLocation();
            }
        }
        for (World world : Bukkit.getWorlds()) {
            if (!world.getName().equalsIgnoreCase(from.worldName())
                    && world.getEnvironment() == World.Environment.NORMAL) {
                return world.getSpawnLocation();
            }
        }
        for (World world : Bukkit.getWorlds()) {
            if (!world.getName().equalsIgnoreCase(from.worldName())) {
                return world.getSpawnLocation();
            }
        }
        return null;
    }

    /** Property keywords accepted by "/planets world". */
    private static final List<String> WORLD_PROPERTIES = List.of(
            "time", "clear", "sun", "rain", "storm", "thunder",
            "tick-speed", "tickspeed", "tick_speed", "gamerule", "pvp", "difficulty",
            "monsters", "animals", "ambient", "water", "mobs",
            "border", "spawn", "seed", "regen", "gravity",
            "weather", "effect", "atmosphere", "dimensions", "sky", "particles",
            "day-cycle", "weather-cycle", "status", "terrain", "structures");

    /**
     * Handles "/planets world <planet> <property> [value...]": changes world
     * properties for ops — weather, time, random tick speed, gamerules, pvp,
     * difficulty, spawn limits, world border, spawn point, seed info, low
     * gravity and (via Multiverse) full regeneration.
     */
    @SuppressWarnings("unchecked")
    private void setWorldProperty(Player player, String[] args) {
        if (!player.hasPermission("planets.world")) {
            player.sendMessage(Component.text("You don't have permission to change world properties.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /planets world <planet> <property> [value...]").color(NamedTextColor.YELLOW));
            player.sendMessage(Component.text("Properties: time, weather, clear, rain, thunder, tick-speed, gamerule, pvp, difficulty, monsters, animals, ambient, water, mobs, border, spawn, seed, gravity, effect, atmosphere, dimensions, sky, particles, day-cycle, weather-cycle, status, terrain, regen").color(NamedTextColor.GRAY));
            return;
        }

        // Planet names may contain spaces ("Middle Earth"), so find the first
        // known property keyword to split planet from property.
        int propertyIndex = -1;
        for (int i = 1; i < args.length; i++) {
            if (WORLD_PROPERTIES.contains(args[i].toLowerCase(Locale.ROOT))) {
                propertyIndex = i;
                break;
            }
        }
        if (propertyIndex == -1) {
            player.sendMessage(Component.text("Unknown property. Try: time, weather, rain, tick-speed, gamerule, pvp, difficulty, mobs, border, spawn, seed, gravity, effect, atmosphere, dimensions, sky, particles, day-cycle, weather-cycle, status, terrain, regen").color(NamedTextColor.RED));
            return;
        }

        String planetQuery = String.join(" ", Arrays.copyOfRange(args, 1, propertyIndex));
        String property = args[propertyIndex].toLowerCase(Locale.ROOT);
        String[] values = Arrays.copyOfRange(args, propertyIndex + 1, args.length);

        Planet planet = findPlanet(availablePlanets(), planetQuery);
        // Public planets come from the menu; anything else (a player-owned
        // planet, a lobby, a planet's Nether/End) is matched by world name.
        World world = planet != null ? Bukkit.getWorld(planet.worldName()) : findLoadedWorld(planetQuery);
        if (world == null && planet != null) {
            player.sendMessage(Component.text("The world of ").color(NamedTextColor.RED)
                    .append(Component.text(planet.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" isn't loaded right now.").color(NamedTextColor.RED)));
            return;
        }
        if (world == null) {
            // A player-owned planet that Multiverse left unloaded: bring it back
            // and let the admin ask again once it is up.
            String unloaded = unloadedOwnedWorld(planetQuery);
            if (unloaded != null) {
                loadWorldNow(unloaded);
                player.sendMessage(Component.text("☄ Loading ").color(NamedTextColor.YELLOW)
                        .append(Component.text(unloaded).color(NamedTextColor.AQUA))
                        .append(Component.text(" — run the command again in a moment.").color(NamedTextColor.YELLOW)));
                return;
            }
            player.sendMessage(
                    Component.text("Unknown planet '").color(NamedTextColor.RED)
                            .append(Component.text(planetQuery).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Public planets are in /planets, owned ones in /myp.").color(NamedTextColor.RED))
            );
            return;
        }

        String planetName = planet != null ? planet.name() : world.getName();
        switch (property) {
            case "time" -> {
                if (values.length == 0) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> time <0-24000|day|noon|sunset|night|midnight>").color(NamedTextColor.YELLOW));
                    return;
                }
                long time;
                switch (values[0].toLowerCase(Locale.ROOT)) {
                    case "day" -> time = 1000;
                    case "noon" -> time = 6000;
                    case "sunset" -> time = 12000;
                    case "night" -> time = 13000;
                    case "midnight" -> time = 18000;
                    default -> {
                        try {
                            time = Long.parseLong(values[0]);
                        } catch (NumberFormatException ex) {
                            player.sendMessage(Component.text("Invalid time '").color(NamedTextColor.RED)
                                    .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                                    .append(Component.text("'. Use 0-24000 or day/noon/sunset/night/midnight.").color(NamedTextColor.RED)));
                            return;
                        }
                    }
                }
                world.setTime(Math.floorMod(time, 24000));
                player.sendMessage(Component.text("Time on ").color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(" set to " + Math.floorMod(time, 24000) + ".").color(NamedTextColor.GREEN)));
            }
            case "clear", "sun" -> {
                world.setStorm(false);
                world.setThundering(false);
                player.sendMessage(Component.text("Cleared the weather on ").color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(".").color(NamedTextColor.GREEN)));
            }
            case "rain" -> {
                world.setStorm(true);
                world.setThundering(false);
                world.setWeatherDuration(12000);
                player.sendMessage(Component.text("Rain started on ").color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(".").color(NamedTextColor.GREEN)));
            }
            case "storm", "thunder" -> {
                world.setStorm(true);
                world.setThundering(true);
                world.setWeatherDuration(12000);
                world.setThunderDuration(12000);
                player.sendMessage(Component.text("Thunderstorm started on ").color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(".").color(NamedTextColor.GREEN)));
            }
            case "tick-speed", "tickspeed", "tick_speed" -> {
                if (values.length == 0) {
                    // No value: report what the world is on right now.
                    Integer current = world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED);
                    player.sendMessage(Component.text("Random tick speed on ").color(NamedTextColor.GREEN)
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" is " + (current == null ? "0" : current)
                                    + ". Change it with /planets world <planet> tick-speed <count>.")
                                    .color(NamedTextColor.GREEN)));
                    return;
                }
                try {
                    int speed = Integer.parseInt(values[0]);
                    if (speed < 0) {
                        player.sendMessage(Component.text("Tick speed must be 0 or more.").color(NamedTextColor.RED));
                        return;
                    }
                    world.setGameRule(GameRule.RANDOM_TICK_SPEED, speed);
                    // Read it back, so the message always matches the world's
                    // real value (a gamerule that refused to change shows up).
                    Integer applied = world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED);
                    player.sendMessage(Component.text("Random tick speed on ").color(NamedTextColor.GREEN)
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" set to " + (applied == null ? speed : applied) + ".")
                                    .color(NamedTextColor.GREEN)));
                } catch (NumberFormatException ex) {
                    player.sendMessage(Component.text("Invalid tick speed '").color(NamedTextColor.RED)
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text("'.").color(NamedTextColor.RED)));
                }
            }
            case "gamerule" -> {
                if (values.length < 2) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> gamerule <name> <value>").color(NamedTextColor.YELLOW));
                    return;
                }
                GameRule<?> rule = GameRule.getByName(values[0]);
                if (rule == null) {
                    player.sendMessage(Component.text("Unknown gamerule '").color(NamedTextColor.RED)
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Try randomTickSpeed, doDaylightCycle, keepInventory, doMobSpawning...").color(NamedTextColor.RED)));
                    return;
                }
                try {
                    if (rule.getType() == Boolean.class) {
                        if (!values[1].equalsIgnoreCase("true") && !values[1].equalsIgnoreCase("false")) {
                            player.sendMessage(Component.text("Gamerule '").color(NamedTextColor.RED)
                                    .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                                    .append(Component.text("' takes true or false.").color(NamedTextColor.RED)));
                            return;
                        }
                        world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(values[1]));
                    } else if (rule.getType() == Integer.class) {
                        world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(values[1]));
                    } else {
                        player.sendMessage(Component.text("Unsupported gamerule type.").color(NamedTextColor.RED));
                        return;
                    }
                } catch (NumberFormatException ex) {
                    player.sendMessage(Component.text("'").color(NamedTextColor.RED)
                            .append(Component.text(values[1]).color(NamedTextColor.YELLOW))
                            .append(Component.text("' isn't a valid number for gamerule '").color(NamedTextColor.RED))
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text("'.").color(NamedTextColor.RED)));
                    return;
                }
                player.sendMessage(Component.text("Gamerule ").color(NamedTextColor.GREEN)
                        .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                        .append(Component.text(" on ").color(NamedTextColor.GREEN))
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(" set to " + values[1] + ".").color(NamedTextColor.GREEN)));
            }
            case "pvp" -> {
                if (values.length == 0 || (!values[0].equalsIgnoreCase("true") && !values[0].equalsIgnoreCase("false"))) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> pvp <true|false>").color(NamedTextColor.YELLOW));
                    return;
                }
                boolean pvp = Boolean.parseBoolean(values[0]);
                world.setPVP(pvp);
                player.sendMessage(Component.text("PvP on ").color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(" is now " + (pvp ? "enabled" : "disabled") + ".").color(NamedTextColor.GREEN)));
            }
            case "difficulty" -> {
                if (values.length == 0) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> difficulty <peaceful|easy|normal|hard>").color(NamedTextColor.YELLOW));
                    return;
                }
                try {
                    Difficulty difficulty = Difficulty.valueOf(values[0].toUpperCase(Locale.ROOT));
                    world.setDifficulty(difficulty);
                    player.sendMessage(Component.text("Difficulty on ").color(NamedTextColor.GREEN)
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" set to " + values[0].toLowerCase(Locale.ROOT) + ".").color(NamedTextColor.GREEN)));
                } catch (IllegalArgumentException ex) {
                    player.sendMessage(Component.text("Use peaceful, easy, normal or hard.").color(NamedTextColor.RED));
                }
            }
            case "monsters", "animals", "ambient", "water" -> {
                if (values.length == 0) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> " + property + " <count>").color(NamedTextColor.YELLOW));
                    return;
                }
                try {
                    int count = Integer.parseInt(values[0]);
                    if (count < 0) {
                        player.sendMessage(Component.text("Count must be 0 or more.").color(NamedTextColor.RED));
                        return;
                    }
                    switch (property) {
                        case "monsters" -> world.setMonsterSpawnLimit(count);
                        case "animals" -> world.setAnimalSpawnLimit(count);
                        case "ambient" -> world.setAmbientSpawnLimit(count);
                        default -> world.setWaterAnimalSpawnLimit(count);
                    }
                    String label = switch (property) {
                        case "monsters" -> "Monster";
                        case "animals" -> "Animal";
                        case "ambient" -> "Ambient";
                        default -> "Water creature";
                    };
                    player.sendMessage(Component.text(label + " spawn limit on ").color(NamedTextColor.GREEN)
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" set to " + count + ".").color(NamedTextColor.GREEN)));
                } catch (NumberFormatException ex) {
                    player.sendMessage(Component.text("Invalid count '").color(NamedTextColor.RED)
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text("'.").color(NamedTextColor.RED)));
                }
            }
            case "border" -> {
                if (values.length == 0) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> border <size>").color(NamedTextColor.YELLOW));
                    return;
                }
                // The main world and Middle Earth are protected: they are built to
                // be large and shrinking one can't be undone, so resizing them asks
                // for an explicit "confirm" instead of refusing outright.
                String protectedMiddleEarth = getConfig().getString("middle-earth-world", "Middle_earth");
                boolean protectedBorderWorld = isProtectedWorldName(world.getName(),
                        protectedMiddleEarth, mainWorldName());
                if (protectedBorderWorld
                        && !(values.length > 1 && values[1].equalsIgnoreCase("confirm"))) {
                    player.sendMessage(Component.text(planetName).color(NamedTextColor.YELLOW)
                            .append(Component.text(" is a protected world (the hub / Middle Earth).")
                                    .color(NamedTextColor.RED)));
                    player.sendMessage(Component.text("Shrinking it can't be undone. To do it anyway: ")
                            .color(NamedTextColor.GRAY)
                            .append(Component.text("/planets world " + planetName + " border "
                                    + values[0] + " confirm").color(NamedTextColor.YELLOW)));
                    return;
                }
                try {
                    double size = Double.parseDouble(values[0]);
                    if (size <= 0 || size > 60000000) {
                        player.sendMessage(Component.text("Size must be between 1 and 60000000.").color(NamedTextColor.RED));
                        return;
                    }
                    world.getWorldBorder().setSize(size);
                    // Remember it, so /planets borders restore can put it back.
                    recordBorder(world.getName(), size);
                    saveConfigQuietly();
                    player.sendMessage(Component.text("World border of ").color(NamedTextColor.GREEN)
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" set to " + size + " blocks.").color(NamedTextColor.GREEN)));
                } catch (NumberFormatException ex) {
                    player.sendMessage(Component.text("Invalid border size '").color(NamedTextColor.RED)
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text("'.").color(NamedTextColor.RED)));
                }
            }
            case "terrain" -> {
                // Lets admins give a public planet a generation: the preset's
                // layer stack is recorded for the planet and laid down inside
                // the world border right away.
                if (values.length == 0) {
                    statusLine(player, "Terrain", PlanetTerrain.describe(PlanetTerrain.load(getConfig(), world.getName())));
                    player.sendMessage(Component.text("Usage: /planets world <planet> terrain <generation|off>").color(NamedTextColor.YELLOW));
                    return;
                }
                String generation = values[0].toLowerCase(Locale.ROOT);
                if (generation.equals("off")) {
                    getConfig().set("planet-terrain." + world.getName(), null);
                    saveConfigQuietly();
                    player.sendMessage(Component.text("Terrain of ").color(NamedTextColor.GREEN)
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" is no longer tracked — the existing ground is kept.").color(NamedTextColor.GREEN)));
                    return;
                }
                PlanetTerrain.Spec spec = generationSpec(generation);
                if (spec == null) {
                    player.sendMessage(Component.text("Unknown generation '").color(NamedTextColor.RED)
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Try ").color(NamedTextColor.RED))
                            .append(Component.text(String.join(", ", PlanetArchetypes.ids()) + ", void").color(NamedTextColor.YELLOW))
                            .append(Component.text(" or a preset from generation-presets in config.yml.").color(NamedTextColor.RED)));
                    return;
                }
                // An explicit admin switch replaces the existing ground too.
                applyTerrain(player, world.getName(), spec);
            }
            case "structures" -> {
                if (values.length == 0) {
                    statusLine(player, "Structures", structuresEnabled(world.getName())
                            ? "ON (villages/ruins generate, trees can grow)"
                            : "OFF (nothing generates structures)");
                    player.sendMessage(Component.text("Usage: /planets world <planet> structures on|off")
                            .color(NamedTextColor.YELLOW));
                    return;
                }
                String value = values[0].toLowerCase(Locale.ROOT);
                if (!value.equals("on") && !value.equals("off")) {
                    player.sendMessage(Component.text("Use on or off.").color(NamedTextColor.RED));
                    return;
                }
                setWorldStructures(player, world.getName(), value.equals("on"));
            }
            case "spawn" -> {
                if (values.length < 3) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> spawn <x> <y> <z>").color(NamedTextColor.YELLOW));
                    return;
                }
                try {
                    int x = Integer.parseInt(values[0]);
                    int y = Integer.parseInt(values[1]);
                    int z = Integer.parseInt(values[2]);
                    world.setSpawnLocation(x, y, z);
                    player.sendMessage(Component.text("Spawn of ").color(NamedTextColor.GREEN)
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" set to (" + x + ", " + y + ", " + z + ").").color(NamedTextColor.GREEN)));
                } catch (NumberFormatException ex) {
                    player.sendMessage(Component.text("Invalid coordinates — use whole numbers.").color(NamedTextColor.RED));
                }
            }
            case "seed" -> player.sendMessage(Component.text("Seed of ").color(NamedTextColor.GREEN)
                    .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                    .append(Component.text(": " + world.getSeed()).color(NamedTextColor.GREEN)));
            case "weather" -> {
                if (values.length == 0) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> weather clear|rain|thunder|off").color(NamedTextColor.YELLOW));
                    return;
                }
                String value = values[0].toLowerCase(Locale.ROOT);
                if (!value.equals("clear") && !value.equals("rain") && !value.equals("thunder") && !value.equals("off")) {
                    player.sendMessage(Component.text("Use clear, rain, thunder or off.").color(NamedTextColor.RED));
                    return;
                }
                if (value.equals("off")) {
                    getConfig().set("weather-lock." + world.getName(), null);
                } else {
                    getConfig().set("weather-lock." + world.getName(), value);
                }
                saveConfigQuietly();
                environment.loadConfig(getConfig());
                switch (value) {
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
                    case "clear" -> {
                        world.setStorm(false);
                        world.setThundering(false);
                    }
                    default -> {
                    }
                }
                player.sendMessage(Component.text("Weather on ").color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(value.equals("off")
                                ? " is no longer locked — it follows the normal cycle."
                                : " is now locked to " + value + ".").color(NamedTextColor.GREEN)));
            }
            case "effect" -> {
                if (values.length < 2) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> effect <effect> <amplifier|off> — effects: jump, speed, strength, swim, breath, fall, ...").color(NamedTextColor.YELLOW));
                    return;
                }
                String effectKey = values[0].toLowerCase(Locale.ROOT);
                if (PlanetEffects.typeFor(effectKey) == null) {
                    player.sendMessage(Component.text("Unknown effect '").color(NamedTextColor.RED)
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Try jump, speed, strength, swim, breath or fall.").color(NamedTextColor.RED)));
                    return;
                }
                String valueArg = values[1].toLowerCase(Locale.ROOT);
                String effectsPath = "effects." + world.getName() + "." + effectKey;
                if (valueArg.equals("off") || valueArg.equals("remove") || valueArg.equals("reset")) {
                    getConfig().set(effectsPath, null);
                    ConfigurationSection worldEffects = getConfig().getConfigurationSection("effects." + world.getName());
                    if (worldEffects != null && worldEffects.getKeys(false).isEmpty()) {
                        getConfig().set("effects." + world.getName(), null);
                    }
                    saveConfigQuietly();
                    PlanetEffects.loadConfig(getConfig());
                    for (Player p : world.getPlayers()) {
                        PlanetEffects.remove(p);
                        PlanetEffects.apply(p);
                    }
                    player.sendMessage(Component.text("Effect ").color(NamedTextColor.GREEN)
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text(" removed from ").color(NamedTextColor.GREEN))
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(".").color(NamedTextColor.GREEN)));
                    return;
                }
                try {
                    int amplifier = Integer.parseInt(valueArg);
                    if (amplifier < 0) {
                        player.sendMessage(Component.text("Amplifier must be 0 or more.").color(NamedTextColor.RED));
                        return;
                    }
                    getConfig().set(effectsPath, amplifier);
                    saveConfigQuietly();
                    PlanetEffects.loadConfig(getConfig());
                    for (Player p : world.getPlayers()) {
                        PlanetEffects.remove(p);
                        PlanetEffects.apply(p);
                    }
                    player.sendMessage(Component.text("Effect ").color(NamedTextColor.GREEN)
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text(" on ").color(NamedTextColor.GREEN))
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" set to amplifier " + amplifier + ".").color(NamedTextColor.GREEN)));
                } catch (NumberFormatException ex) {
                    player.sendMessage(Component.text("Invalid amplifier '").color(NamedTextColor.RED)
                            .append(Component.text(valueArg).color(NamedTextColor.YELLOW))
                            .append(Component.text("' — use a number 0+ or 'off'.").color(NamedTextColor.RED)));
                }
            }
            case "mobs" -> {
                if (values.length == 0 || (!values[0].equalsIgnoreCase("on") && !values[0].equalsIgnoreCase("off"))) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> mobs on|off").color(NamedTextColor.YELLOW));
                    return;
                }
                boolean enabled = values[0].equalsIgnoreCase("on");
                world.setGameRule(GameRule.DO_MOB_SPAWNING, enabled);
                player.sendMessage(Component.text("Mob spawning on ").color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(" is now " + (enabled ? "enabled" : "disabled") + ".").color(NamedTextColor.GREEN)));
            }
            case "atmosphere" -> {
                if (values.length == 0) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> atmosphere <damage-per-second|off> [helmet material...]").color(NamedTextColor.YELLOW));
                    return;
                }
                if (values[0].equalsIgnoreCase("off") || values[0].equalsIgnoreCase("remove") || values[0].equalsIgnoreCase("reset")) {
                    getConfig().set("atmospheres." + world.getName(), null);
                    saveConfigQuietly();
                    environment.loadConfig(getConfig());
                    player.sendMessage(Component.text("Atmosphere of ").color(NamedTextColor.GREEN)
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" disabled — no more damage.").color(NamedTextColor.GREEN)));
                    return;
                }
                try {
                    double damage = Double.parseDouble(values[0]);
                    if (damage <= 0) {
                        player.sendMessage(Component.text("Damage must be more than 0 (use 'off' to disable).").color(NamedTextColor.RED));
                        return;
                    }
                    getConfig().set("atmospheres." + world.getName() + ".damage", damage);
                    List<String> helmets = new ArrayList<>();
                    for (int i = 1; i < values.length; i++) {
                        Material helmet = Material.matchMaterial(values[i]);
                        if (helmet == null) {
                            player.sendMessage(Component.text("Unknown material '").color(NamedTextColor.RED)
                                    .append(Component.text(values[i]).color(NamedTextColor.YELLOW))
                                    .append(Component.text("'.").color(NamedTextColor.RED)));
                            return;
                        }
                        helmets.add(helmet.name());
                    }
                    if (helmets.isEmpty()) {
                        getConfig().set("atmospheres." + world.getName() + ".helmets", null);
                    } else {
                        getConfig().set("atmospheres." + world.getName() + ".helmets", helmets);
                    }
                    saveConfigQuietly();
                    environment.loadConfig(getConfig());
                    player.sendMessage(Component.text("Atmosphere of ").color(NamedTextColor.GREEN)
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" now deals " + damage + " damage per second").color(NamedTextColor.GREEN))
                            .append(Component.text(helmets.isEmpty()
                                    ? " — any helmet protects."
                                    : " — only " + String.join(", ", helmets) + " protect.").color(NamedTextColor.YELLOW)));
                } catch (NumberFormatException ex) {
                    player.sendMessage(Component.text("Invalid damage '").color(NamedTextColor.RED)
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text("' — use a number or 'off'.").color(NamedTextColor.RED)));
                }
            }
            case "particles" -> {
                if (values.length == 0) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> particles <effect|off> [density] — effects: "
                            + String.join(", ", PlanetEnvironment.PARTICLE_EFFECTS)).color(NamedTextColor.YELLOW));
                    return;
                }
                if (values[0].equalsIgnoreCase("off") || values[0].equalsIgnoreCase("remove") || values[0].equalsIgnoreCase("reset")) {
                    getConfig().set("particles." + world.getName(), null);
                    saveConfigQuietly();
                    environment.loadConfig(getConfig());
                    player.sendMessage(Component.text("Ambient particles on ").color(NamedTextColor.GREEN)
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" removed.").color(NamedTextColor.GREEN)));
                    return;
                }
                String effect = values[0].toLowerCase(Locale.ROOT);
                if (!PlanetEnvironment.PARTICLE_EFFECTS.contains(effect)) {
                    player.sendMessage(Component.text("Unknown particle effect '").color(NamedTextColor.RED)
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Try: " + String.join(", ", PlanetEnvironment.PARTICLE_EFFECTS)).color(NamedTextColor.RED)));
                    return;
                }
                int density = 8;
                if (values.length >= 2) {
                    try {
                        density = Integer.parseInt(values[1]);
                    } catch (NumberFormatException ex) {
                        player.sendMessage(Component.text("Invalid density '").color(NamedTextColor.RED)
                                .append(Component.text(values[1]).color(NamedTextColor.YELLOW))
                                .append(Component.text("' — use a number of particles per second.").color(NamedTextColor.RED)));
                        return;
                    }
                    if (density < 0 || density > 120) {
                        player.sendMessage(Component.text("Density must be between 0 and 120.").color(NamedTextColor.RED));
                        return;
                    }
                }
                getConfig().set("particles." + world.getName() + ".effect", effect);
                getConfig().set("particles." + world.getName() + ".density", density);
                saveConfigQuietly();
                environment.loadConfig(getConfig());
                player.sendMessage(Component.text("Ambient particles on ").color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(" set to ").color(NamedTextColor.GREEN))
                        .append(Component.text(effect).color(NamedTextColor.YELLOW))
                        .append(Component.text(" (" + density + " particles per second).").color(NamedTextColor.GREEN)));
            }
            case "sky" -> {
                if (values.length == 0) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> sky <color> [fogColor] [waterColor] — hex like #FF8800 or names like orange; 'off' to remove").color(NamedTextColor.YELLOW));
                    return;
                }
                if (values[0].equalsIgnoreCase("off") || values[0].equalsIgnoreCase("remove") || values[0].equalsIgnoreCase("reset")) {
                    getConfig().set("sky-colors." + world.getName(), null);
                    saveConfigQuietly();
                    environment.loadConfig(getConfig());
                    player.sendMessage(Component.text("Sky colors of ").color(NamedTextColor.GREEN)
                            .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                            .append(Component.text(" removed — the planet looks normal again.").color(NamedTextColor.GREEN)));
                    return;
                }
                org.bukkit.Color sky = PlanetEnvironment.parseColor(values[0]);
                if (sky == null) {
                    player.sendMessage(Component.text("Invalid color '").color(NamedTextColor.RED)
                            .append(Component.text(values[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text("' — use hex like #FF8800 or a name like orange.").color(NamedTextColor.RED)));
                    return;
                }
                String skyPath = "sky-colors." + world.getName();
                getConfig().set(skyPath + ".sky", PlanetEnvironment.hex(sky));
                if (values.length >= 2) {
                    org.bukkit.Color fog = PlanetEnvironment.parseColor(values[1]);
                    if (fog == null) {
                        player.sendMessage(Component.text("Invalid fog color '").color(NamedTextColor.RED)
                                .append(Component.text(values[1]).color(NamedTextColor.YELLOW)).append(Component.text("'.").color(NamedTextColor.RED)));
                        return;
                    }
                    getConfig().set(skyPath + ".fog", PlanetEnvironment.hex(fog));
                }
                if (values.length >= 3) {
                    org.bukkit.Color water = PlanetEnvironment.parseColor(values[2]);
                    if (water == null) {
                        player.sendMessage(Component.text("Invalid water color '").color(NamedTextColor.RED)
                                .append(Component.text(values[2]).color(NamedTextColor.YELLOW)).append(Component.text("'.").color(NamedTextColor.RED)));
                        return;
                    }
                    getConfig().set(skyPath + ".water", PlanetEnvironment.hex(water));
                }
                saveConfigQuietly();
                environment.loadConfig(getConfig());
                player.sendMessage(Component.text("Sky of ").color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(" is now ").color(NamedTextColor.GREEN))
                        .append(Component.text(PlanetEnvironment.hex(sky)).color(NamedTextColor.YELLOW))
                        .append(Component.text(" — players see a colored haze there.").color(NamedTextColor.GREEN)));
            }
            case "dimensions" -> {
                if (values.length == 0 || (!values[0].equalsIgnoreCase("on") && !values[0].equalsIgnoreCase("off"))) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> dimensions on|off").color(NamedTextColor.YELLOW));
                    return;
                }
                boolean enable = values[0].equalsIgnoreCase("on");
                List<String> disabled = new ArrayList<>(getConfig().getStringList("disabled-dimensions"));
                disabled.removeIf(name -> name.equalsIgnoreCase(world.getName()));
                if (enable) {
                    disabled.add(world.getName());
                }
                getConfig().set("disabled-dimensions", disabled);
                saveConfigQuietly();
                environment.loadConfig(getConfig());
                player.sendMessage(Component.text("Nether/End dimensions of ").color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(enable
                                ? " are now disabled — portals won't work and they're hidden from /planets."
                                : " are enabled again.").color(NamedTextColor.GREEN)));
            }
            case "day-cycle", "weather-cycle" -> {
                if (values.length == 0 || (!values[0].equalsIgnoreCase("on") && !values[0].equalsIgnoreCase("off"))) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> " + property + " on|off").color(NamedTextColor.YELLOW));
                    return;
                }
                boolean enabled = values[0].equalsIgnoreCase("on");
                if (property.equals("day-cycle")) {
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, enabled);
                } else {
                    world.setGameRule(GameRule.DO_WEATHER_CYCLE, enabled);
                }
                player.sendMessage(Component.text("The " + (property.equals("day-cycle") ? "day/night cycle" : "weather cycle") + " on ")
                        .color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(" is now " + (enabled ? "on" : "off") + ".").color(NamedTextColor.GREEN)));
            }
            case "status" -> {
                String lockedWeather = environment.lockedWeather(world.getName());
                String weatherState = world.isThundering() ? "thunder" : (world.hasStorm() ? "rain" : "clear");
                player.sendMessage(Component.text("— " + planetName + " (" + world.getName() + ") —").color(NamedTextColor.GOLD));
                statusLine(player, "Time", String.valueOf(world.getTime()));
                statusLine(player, "Weather", weatherState + (lockedWeather != null ? " (locked: " + lockedWeather + ")" : ""));
                statusLine(player, "Difficulty", world.getDifficulty().name().toLowerCase(Locale.ROOT));
                statusLine(player, "PvP", world.getPVP() ? "enabled" : "disabled");
                statusLine(player, "Random tick speed", String.valueOf(world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED)));
                statusLine(player, "Daylight cycle", world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE) ? "on" : "off");
                statusLine(player, "Weather cycle", world.getGameRuleValue(GameRule.DO_WEATHER_CYCLE) ? "on" : "off");
                statusLine(player, "Mob spawning", world.getGameRuleValue(GameRule.DO_MOB_SPAWNING) ? "on" : "off");
                statusLine(player, "Spawn limits", "monsters " + world.getMonsterSpawnLimit()
                        + ", animals " + world.getAnimalSpawnLimit()
                        + ", ambient " + world.getAmbientSpawnLimit()
                        + ", water " + world.getWaterAnimalSpawnLimit());
                Map<String, Integer> effects = PlanetEffects.effectsFor(world.getName());
                statusLine(player, "Effects", effects.isEmpty() ? "none" : effects.entrySet().stream()
                        .map(entry -> entry.getKey() + " " + entry.getValue())
                        .sorted()
                        .collect(Collectors.joining(", ")));
                Double atmosphere = environment.atmosphereDamage(world.getName());
                statusLine(player, "Atmosphere", atmosphere == null ? "none" : atmosphere + " damage/sec");
                String skyInfo = environment.skyColorDescription(world.getName());
                statusLine(player, "Sky colors", skyInfo == null ? "default" : skyInfo);
                String particleInfo = environment.ambientParticlesDescription(world.getName());
                statusLine(player, "Particles", particleInfo == null ? "none" : particleInfo);
                statusLine(player, "Dimensions", environment.dimensionsDisabled(world.getName())
                        ? "nether/end disabled" : "linked normally");
                PlanetTerrain.Spec terrainSpec = PlanetTerrain.load(getConfig(), world.getName());
                statusLine(player, "Terrain", terrainSpec == null
                        ? "not tracked (set it with /planets world <planet> terrain <generation>)"
                        : PlanetTerrain.describe(terrainSpec));
            }
            case "gravity" -> {
                if (values.length == 0 || (!values[0].equalsIgnoreCase("on") && !values[0].equalsIgnoreCase("off")
                        && !values[0].equalsIgnoreCase("true") && !values[0].equalsIgnoreCase("false"))) {
                    player.sendMessage(Component.text("Usage: /planets world <planet> gravity on|off").color(NamedTextColor.YELLOW));
                    return;
                }
                boolean enable = values[0].equalsIgnoreCase("on") || values[0].equalsIgnoreCase("true");
                // Low gravity is just a shortcut for the per-world effects config:
                // stronger jumps + slow falling.
                String effectsPath = "effects." + world.getName() + ".";
                if (enable) {
                    getConfig().set(effectsPath + "jump", 2);
                    getConfig().set(effectsPath + "fall", 1);
                } else {
                    getConfig().set(effectsPath + "jump", null);
                    getConfig().set(effectsPath + "fall", null);
                    ConfigurationSection worldEffects = getConfig().getConfigurationSection("effects." + world.getName());
                    if (worldEffects != null && worldEffects.getKeys(false).isEmpty()) {
                        getConfig().set("effects." + world.getName(), null);
                    }
                }
                // Keep the legacy list in sync so old configs don't re-enable it.
                List<String> gravity = new ArrayList<>(getConfig().getStringList("gravity-worlds"));
                gravity.removeIf(name -> name.equalsIgnoreCase(world.getName()));
                if (enable) {
                    gravity.add(world.getName());
                }
                getConfig().set("gravity-worlds", gravity);
                saveConfigQuietly();
                PlanetEffects.loadConfig(getConfig());
                for (Player p : world.getPlayers()) {
                    PlanetEffects.remove(p);
                    if (enable) {
                        PlanetEffects.apply(p);
                    }
                }
                player.sendMessage(Component.text("Low gravity " + (enable ? "enabled" : "disabled") + " on ").color(NamedTextColor.GREEN)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text(".").color(NamedTextColor.GREEN)));
            }
            case "regen" -> {
                if (!MultiverseHook.isPresent()) {
                    player.sendMessage(Component.text("Multiverse-Core is required to regenerate worlds.").color(NamedTextColor.RED));
                    return;
                }
                String extra = "";
                if (values.length > 0) {
                    extra = values[0].equalsIgnoreCase("random") ? " --seed" : " --seed " + values[0];
                }
                player.sendMessage(Component.text("Regenerating ").color(NamedTextColor.GRAY)
                        .append(Component.text(planetName).color(NamedTextColor.YELLOW))
                        .append(Component.text("... this resets all terrain" + (extra.isEmpty() ? " (same seed)" : "") + ". Watch the console.").color(NamedTextColor.GRAY)));
                getServer().dispatchCommand(getServer().getConsoleSender(), "mv regen " + world.getName() + extra);
                getServer().dispatchCommand(getServer().getConsoleSender(), "mv confirm");
            }
            default -> player.sendMessage(Component.text("Unknown property '").color(NamedTextColor.RED)
                    .append(Component.text(property).color(NamedTextColor.YELLOW))
                    .append(Component.text("'.").color(NamedTextColor.RED)));
        }
    }

    /** Value suggestions for a given world property, for tab completion. */
    private static List<String> worldPropertyValues(String property, String prefix) {
        List<String> values = switch (property) {
            case "pvp" -> List.of("true", "false");
            case "difficulty" -> List.of("peaceful", "easy", "normal", "hard");
            case "time" -> List.of("day", "noon", "sunset", "night", "midnight");
            case "gravity" -> List.of("on", "off");
            case "regen" -> List.of("random");
            case "weather" -> List.of("clear", "rain", "thunder", "off");
            case "effect" -> PlanetEffects.EFFECT_NAMES;
            case "mobs" -> List.of("on", "off");
            case "structures" -> List.of("on", "off");
            case "dimensions" -> List.of("on", "off");
            case "terrain" -> {
                List<String> generations = new ArrayList<>(PlanetArchetypes.ids());
                generations.add("void");
                generations.add("off");
                yield generations;
            }
            case "day-cycle", "weather-cycle" -> List.of("on", "off");
            case "atmosphere" -> List.of("off");
            case "sky" -> List.of("off");
            case "particles" -> {
                List<String> suggestions = new ArrayList<>();
                suggestions.add("off");
                suggestions.addAll(PlanetEnvironment.PARTICLE_EFFECTS);
                yield suggestions;
            }
            case "tick-speed", "tickspeed", "tick_speed" -> List.of("0", "1", "2", "3", "5", "10", "20");
            case "gamerule" -> List.of("randomTickSpeed", "doDaylightCycle", "doWeatherCycle",
                    "doMobSpawning", "keepInventory", "mobGriefing", "doFireTick", "doTileDrops");
            default -> List.of();
        };
        return values.stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }

    /** One line of the "/planets world <planet> status" overview. */
    private void statusLine(Player player, String label, String value) {
        player.sendMessage(Component.text("• ").color(NamedTextColor.GRAY)
                .append(Component.text(label + ": ").color(NamedTextColor.GRAY))
                .append(Component.text(value).color(NamedTextColor.YELLOW)));
    }

    /** Prints an overview of all planet commands the sender may use. */
    private void showHelp(CommandSender sender) {
        sender.sendMessage(Component.text("— Planets commands —").color(NamedTextColor.GOLD));

        helpGroup(sender, "Travel", "planets.use");
        helpLine(sender, "planets.use", "/planets  or  /p", "opens the planet menu");
        helpLine(sender, "planets.use", "/p <planet>", "teleports straight to a planet (e.g. /p end)");
        helpLine(sender, "planets.use", "/lobby  or  /hub  or  /l", "opens the LOBBIES menu");
        helpLine(sender, "planets.use", "/lobby <name>", "teleports straight to a lobby's landing spot");
        helpLine(sender, "planets.use", "/lobby list", "lists all lobbies and their landing spots");
        helpLine(sender, "planets.use", "/p help  or  /p ?", "shows this help");

        helpGroup(sender, "My Planet", "planets.use");
        helpLine(sender, "planets.use", "/myp", "opens your planet management panel");
        helpLine(sender, "planets.use", "/myp <planet>", "opens the panel for a specific planet");
        helpLine(sender, "planets.use", "/myp invite <player> [planet]", "invites a player to your planet");
        helpLine(sender, "planets.use", "/myp uninvite <player> [planet]", "revokes a pending invitation");
        helpLine(sender, "planets.use", "/myp accept [planet]", "accepts a pending invitation");
        helpLine(sender, "planets.use", "/myp deny [planet]", "declines a pending invitation");
        helpLine(sender, "planets.use", "/myp sell [planet] <price>", "puts your planet up for sale");

        helpGroup(sender, "Homes", "planets.homes");
        helpLine(sender, "planets.homes", "/home [name]",
                "opens your homes menu, or teleports straight to one");
        helpLine(sender, "planets.homes", "/sethome <name>", "saves where you stand as a home");
        helpLine(sender, "planets.homes", "/delhome <name>", "deletes a saved home");

        helpGroup(sender, "Social & settings", "planets.settings");
        helpLine(sender, "planets.settings", "/settings",
                "your personal preferences: public chat, private messages, message ping, join/leave lines, "
                        + "death messages, tp requests, name mentions, invite alerts, anti chat spam, "
                        + "balance notices, teleport messages, Planet HUD, effect summary, colored sky, "
                        + "planet atmosphere, mob spawning, night vision");
        helpLine(sender, "planets.settings", "/msg <player> <message>",
                "sends a private message (also /tell, /w, /whisper, /pm)");
        helpLine(sender, "planets.settings", "/r <message>", "replies to the last private message");
        helpLine(sender, "planets.settings", "/tpa <player>  |  /tpahere <player>",
                "asks to teleport (they answer with /tpaccept or /tpdeny)");
        helpLine(sender, "planets.settings", "/tpaccept  |  /tpdeny", "answers the newest teleport request");

        helpGroup(sender, "Lock & portals", "planets.lock", "planets.world");
        helpLine(sender, "planets.lock", "/planets lock <planet>",
                "locks/unlocks a planet (10s warning countdown; \"lock cancel <planet>\" aborts; visitors need planets.tp.<world>)");
        helpLine(sender, "planets.world", "/planets portals on|off", "disables all Nether/End portals server-wide");

        helpGroup(sender, "Manage planets", "planets.admin", "planets.create", "planets.icon", "planets.landing", "planets.delete", "planets.buy");
        helpLine(sender, "planets.create", "/planets create <name> [type] [generation]",
                "creates a new planet (generations: " + String.join(", ", generationIds()) + ")");
        helpLine(sender, "planets.create", "/planets preview <generation|end>",
                "builds a throwaway world so you can look at a generation, then discards it");
        helpLine(sender, "planets.admin", "/planets hud",
                "edits the Planet HUD lines in-game: add, rename, reorder and preview them");
        helpLine(sender, "planets.admin", "/planets admin",
                "opens the admin panel: dashboard, then enter/regenerate/lock/landing/icon/terrain/structures/weather/delete for any planet");
        helpLine(sender, "planets.admin", "/planets admin help",
                "the full admin help page: every panel action and command, in one menu (searchable)");
        helpLine(sender, "planets.icon", "/planets icon <planet> <material|reset>", "changes the planet's menu block");
        helpLine(sender, "planets.landing", "/planets landing <planet> station|random|point",
                "sets where players land (the_end worlds always land outside the dragon island)");
        helpLine(sender, "planets.landing", "/planets setlanding <planet>", "pins your position as the landing point");
        helpLine(sender, "planets.delete", "/planets delete <name>", "deletes a planet and its world folder");
        helpLine(sender, "planets.buy", "/planets buy", "opens the buy-a-planet UI (creates a new default world)");
        helpLine(sender, "planets.world", "/planets buy <amount>", "sets the price of a new planet in VPL money");

        helpGroup(sender, "Lobbies", "planets.use", "planets.create", "planets.landing", "planets.icon", "planets.delete", "planets.admin");
        helpLine(sender, "planets.create", "/lobby create <name> [material]", "creates a new lobby world (hidden from /planets)");
        helpLine(sender, "planets.landing", "/lobby setlanding <name>", "pins your position as the lobby's landing spot");
        helpLine(sender, "planets.icon", "/lobby icon <name> <material|reset>", "changes the lobby's menu block");
        helpLine(sender, "planets.delete", "/lobby delete <name>", "removes a lobby from the menu");
        helpLine(sender, "planets.admin", "/lobby editor [lobby]",
                "opens the lobby panel: rename, menu slot, description, icon, landing, weather, terrain, structures, delete");
        helpLine(sender, "planets.admin", "/lobby rename <lobby> <new name>",
                "renames a lobby (its id and world stay the same)");
        helpLine(sender, "planets.admin", "/lobby slot <lobby> <0-25>", "moves a lobby in the LOBBIES menu");
        helpLine(sender, "planets.admin", "/lobby desc <lobby> <text|none>",
                "sets the description shown under the lobby's name");

        helpGroup(sender, "World properties", "planets.world");
        helpLine(sender, "planets.world", "/planets world <planet> status", "shows an overview of all properties");
        helpLine(sender, "planets.world", "/planets world <planet> weather clear|rain|thunder|off", "locks the weather");
        helpLine(sender, "planets.world", "/planets world <planet> time <0-24000|day|noon|sunset|night|midnight>", "sets the time");
        helpLine(sender, "planets.world", "/planets world <planet> day-cycle on|off", "turns the daylight cycle on/off");
        helpLine(sender, "planets.world", "/planets world <planet> weather-cycle on|off", "turns the weather cycle on/off");
        helpLine(sender, "planets.world", "/planets world <planet> tick-speed <count>", "random-tick speed (crops, growth, ...)");
        helpLine(sender, "planets.world", "/planets world <planet> effect <effect> <level|off>",
                "planet physics: jump, speed, strength, swim, breath, fall, night_vision, ...");
        helpLine(sender, "planets.world", "/planets world <planet> gravity on|off", "low-gravity shortcut (writes jump + fall effects)");
        helpLine(sender, "planets.world", "/planets world <planet> sky <color> [fog] [water]", "visual atmosphere color (e.g. #FF8844; 'off' removes)");
        helpLine(sender, "planets.world", "/planets world <planet> particles <effect|off> [density]", "ambient particles: embers, snow, rain, smoke, ...");
        helpLine(sender, "planets.world", "/planets world <planet> atmosphere <damage|off> [helmets...]", "hostile atmosphere damaging unprotected players");
        helpLine(sender, "planets.world", "/planets world <planet> mobs on|off", "toggles mob spawning");
        helpLine(sender, "planets.world", "/planets world <planet> monsters|animals|ambient|water <count>", "spawn limits per mob type");
        helpLine(sender, "planets.world", "/planets world <planet> pvp on|off", "toggles PvP");
        helpLine(sender, "planets.world", "/planets world <planet> difficulty peaceful|easy|normal|hard", "sets the difficulty");
        helpLine(sender, "planets.world", "/planets world <planet> border <size>", "sets the world border size");
        helpLine(sender, "planets.world", "/planets world <planet> spawn <x> <y> <z>", "sets the world spawn point");
        helpLine(sender, "planets.world", "/planets world <planet> structures on|off", "toggles structure generation (villages, ruins, tree growth)");
        helpLine(sender, "planets.world", "/planets world <planet> gamerule <name> <value>", "sets a game rule");
        helpLine(sender, "planets.world", "/planets world <planet> dimensions on|off", "disables the planet's linked Nether/End dimensions");

        helpGroup(sender, "Config", "planets.reload");
        helpLine(sender, "planets.reload", "/planets reload", "reloads the config from disk");
        helpLine(sender, "planets.world", "/planets borders [set <blocks|NxN>|restore]",
                "shows, sets or restores the world borders of the public planets");
    }

    /** Group heading in the help output, shown only if the sender holds one of the permissions. */
    private void helpGroup(CommandSender sender, String title, String... permissions) {
        for (String permission : permissions) {
            if (sender.hasPermission(permission)) {
                sender.sendMessage(Component.text("» " + title).color(NamedTextColor.DARK_GREEN));
                return;
            }
        }
    }

    /** One line of the help output, shown only to senders with the permission. */
    private void helpLine(CommandSender sender, String permission, String usage, String description) {
        if (!sender.hasPermission(permission)) {
            return;
        }
        sender.sendMessage(Component.text("• ", NamedTextColor.GRAY)
                .append(Component.text(usage).color(NamedTextColor.YELLOW))
                .append(Component.text(" — ").color(NamedTextColor.GRAY))
                .append(Component.text(description).color(NamedTextColor.GRAY)));
    }

    /** Saves config.yml, swallowing the (impossible on a loaded config) failure. */
    void saveConfigQuietly() {
        try {
            saveConfig();
        } catch (RuntimeException ex) {
            getLogger().warning("Could not save config.yml: " + ex.getMessage());
        }
    }

    /**
     * Fills in the default lobbies on servers whose config.yml predates the
     * lobby system (or whose "lobbies" section is empty), so the LOBBIES menu
     * is never empty out of the box. Runs once: after seeding, the
     * "lobbies-seeded" flag is set so deleting every lobby later is respected.
     * On servers that seeded before slot keys existed, the four known lobbies
     * are pinned to their screenshot slots when their slot is still unset.
     */
    private void seedDefaultLobbies() {
        boolean alreadySeeded = getConfig().getBoolean("lobbies-seeded", false);
        ConfigurationSection section = getConfig().getConfigurationSection("lobbies");
        boolean empty = section == null || section.getKeys(false).isEmpty();

        // Never re-seed a default lobby whose world was explicitly deleted — even if
        // the lobbies section somehow became empty again (config reset, backup restore…).
        Set<String> defaultIds = Set.of("main", "pirates", "solo_workers", "law_and_neutrals");
        boolean anyDefaultDeleted = defaultIds.stream().anyMatch(this::isDeletedWorld);

        if (!alreadySeeded && empty && !anyDefaultDeleted) {
            setDefaultLobby("main", "MAIN LOBBY available to everyone",
                    getConfig().getString("middle-earth-world", "Middle_earth"), "IRON_BLOCK", 11);
            setDefaultLobby("pirates", "Pirates TEAM Lobby", null, "STRIPPED_SPRUCE_LOG", 13);
            setDefaultLobby("solo_workers", "Solo Workers TEAM Lobby", null, "GRANITE", 14);
            setDefaultLobby("law_and_neutrals", "The Law & Neutrals TEAM Lobby", null, "STONE_BRICKS", 15);
            getLogger().info("Seeded the default LOBBIES menu (main, pirates, solo_workers, law_and_neutrals).");
        } else if (alreadySeeded && !anyDefaultDeleted) {
            // Migration: an earlier seed wrote the defaults without slot keys;
            // pin the four known lobbies to their screenshot slots if unset.
            ensureDefaultSlot("main", 11);
            ensureDefaultSlot("pirates", 13);
            ensureDefaultSlot("solo_workers", 14);
            ensureDefaultSlot("law_and_neutrals", 15);
        }
        // Fill a missing or partially written info item (e.g. an old hand-made
        // "lobbies-info" without a name).
        ConfigurationSection info = getConfig().getConfigurationSection("lobbies-info");
        if (info != null) {
            if (info.getString("name") == null) {
                getConfig().set("lobbies-info.name", "These are the SMP's Lobbies for each TEAM");
            }
            if (info.getString("icon") == null) {
                getConfig().set("lobbies-info.icon", "BOOK");
            }
        }
        getConfig().set("lobbies-seeded", true);
        saveConfigQuietly();
    }

    /** Writes one default lobby entry (with its pinned menu slot) into the config. */
    private void setDefaultLobby(String id, String name, String world, String icon, int slot) {
        String path = "lobbies." + id;
        getConfig().set(path + ".name", name);
        if (world != null) {
            getConfig().set(path + ".world", world);
        }
        if (icon != null) {
            getConfig().set(path + ".icon", icon);
        }
        getConfig().set(path + ".slot", slot);
    }

    /** Sets the menu slot for an existing default lobby, only if it has none yet. */
    private void ensureDefaultSlot(String id, int slot) {
        ConfigurationSection entry = getConfig().getConfigurationSection("lobbies." + id);
        if (entry != null && !entry.contains("slot")) {
            getConfig().set("lobbies." + id + ".slot", slot);
        }
    }

    /** Opens the LOBBIES menu for the player. */
    private void openLobbies(Player player) {
        List<Lobby> lobbies = availableLobbies();
        if (lobbies.isEmpty() && lobbyInfo() == null) {
            player.sendMessage(Component.text("No lobbies are configured yet. Add them under the \"lobbies\" ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("section of config.yml, or run /lobby create <name> [material].")
                            .color(NamedTextColor.YELLOW)));
            return;
        }
        new LobbiesMenu(this, lobbies, player).open(player);
    }

    /**
     * Builds the lobby list from the "lobbies" config section. Each key is the
     * lobby's id; its value is either a display name (string) or a block with
     * name/world/icon/description/x/y/z/yaw/pitch keys. Icons fall back to the
     * shared "icons" map and then to the world's environment.
     */
    private List<Lobby> availableLobbies() {
        List<Lobby> lobbies = new ArrayList<>();
        ConfigurationSection section = getConfig().getConfigurationSection("lobbies");
        if (section == null) {
            return lobbies;
        }
        Map<String, Material> icons = iconOverrides();
        for (String id : section.getKeys(false)) {
            String name = null;
            String world = null;
            String description = null;
            Material customIcon = null;
            Integer slot = null;
            Double x = null, y = null, z = null, yaw = null, pitch = null;
            Object value = section.get(id);
            if (value instanceof ConfigurationSection detail) {
                name = detail.getString("name");
                world = detail.getString("world");
                description = detail.getString("description");
                if (detail.contains("slot")) {
                    slot = detail.getInt("slot");
                }
                String iconName = detail.getString("icon");
                if (iconName != null) {
                    Material material = Material.matchMaterial(iconName);
                    if (material == null || material == Material.AIR) {
                        getLogger().warning("Ignoring unknown lobby icon '" + iconName + "' for lobby '" + id + "'.");
                    } else {
                        customIcon = material;
                    }
                }
                if (detail.contains("x") && detail.contains("y") && detail.contains("z")) {
                    x = detail.getDouble("x");
                    y = detail.getDouble("y");
                    z = detail.getDouble("z");
                    yaw = detail.getDouble("yaw", 0);
                    pitch = detail.getDouble("pitch", 0);
                }
            } else if (value instanceof String displayName) {
                name = displayName;
            }
            if (name == null || name.isBlank()) {
                name = humanizeWorldName(id);
            }
            if (world == null || world.isBlank()) {
                world = id;
            }
            Material icon = customIcon != null ? customIcon
                    : icons.getOrDefault(world.toLowerCase(Locale.ROOT), defaultLobbyIcon(world));
            lobbies.add(new Lobby(id, name, icon, world, slot, x, y, z, yaw, pitch, description));
        }
        return lobbies;
    }

    /** "Middle_earth" → "Middle Earth", "world" → "World". */
    private static String humanizeWorldName(String worldName) {
        StringBuilder result = new StringBuilder();
        for (String word : worldName.replace('_', ' ').split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    /** Icon for a lobby world with no explicit choice: block matching its environment. */
    private Material defaultLobbyIcon(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return Material.GRASS_BLOCK;
        }
        return switch (world.getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
    }

    /** Finds a lobby by its id, display name or world name. */
    private Lobby findLobby(String query) {
        for (Lobby lobby : availableLobbies()) {
            if (lobby.id().equalsIgnoreCase(query)
                    || lobby.name().equalsIgnoreCase(query)
                    || lobby.worldName().equalsIgnoreCase(query)) {
                return lobby;
            }
        }
        return null;
    }

    /** Decoration item pinned in the last slot of the LOBBIES menu. */
    LobbyInfo lobbyInfo() {
        ConfigurationSection info = getConfig().getConfigurationSection("lobbies-info");
        if (info == null) {
            return null;
        }
        String name = info.getString("name", "Lobbies");
        Material icon = Material.matchMaterial(info.getString("icon", "BOOK"));
        if (icon == null || icon == Material.AIR) {
            icon = Material.BOOK;
        }
        return new LobbyInfo(name, icon, info.getStringList("lore"));
    }

    /** Teleports the player to a lobby's landing spot (or its world spawn when unset). */
    void teleportToLobby(Player player, Lobby lobby) {
        Location spot = lobby.location();
        if (spot == null || spot.getWorld() == null) {
            player.sendMessage(Component.text("The world of \"").color(NamedTextColor.RED)
                    .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text("\" isn't loaded right now.").color(NamedTextColor.RED)));
            return;
        }
        PlanetTravel.cancelPendingTeleport(player);
        player.closeInventory();
        player.teleport(spot);
        spot.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().clone().add(0, 1, 0), 90, 1.0, 1.0, 1.0, 0.4);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        player.sendMessage(Component.text("Welcome to ").color(NamedTextColor.GREEN)
                .append(Component.text(lobby.name()).color(NamedTextColor.GOLD))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    /** Routes /lobby's subcommands; with no subcommand it opens the menu or teleports. */
    private void handleLobbyCommand(Player player, String[] args) {
        if (args.length == 0) {
            openLobbies(player);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        // Admin sub-commands require op.
        // Admin sub-commands: op, or the planets.admin permission the panel uses.
        // Each one still checks its own node inside (planets.create, planets.icon,
        // planets.landing, planets.delete), so this gate only decides who gets as far
        // as trying.
        if (Set.of("create", "setlanding", "icon", "delete", "list", "lobbies",
                "editor", "edit", "rename", "slot", "desc", "description").contains(sub)
                && !player.isOp() && !canUseAdmin(player)) {
            player.sendMessage(Component.text("You don't have permission to use this command.").color(NamedTextColor.RED));
            return;
        }
        switch (sub) {
            case "create" -> lobbyCreate(player, args);
            case "setlanding" -> lobbySetLanding(player, args);
            case "icon" -> lobbyIcon(player, args);
            case "delete" -> lobbyDelete(player, args);
            case "list", "lobbies" -> lobbyList(player);
            case "editor", "edit" -> lobbyEditor(player, args);
            case "rename" -> lobbyRename(player, args);
            case "slot" -> lobbySlot(player, args);
            case "desc", "description" -> lobbyDescription(player, args);
            default -> {
                String query = String.join(" ", args);
                Lobby lobby = findLobby(query);
                if (lobby == null) {
                    player.sendMessage(Component.text("Unknown lobby '").color(NamedTextColor.RED)
                            .append(Component.text(query).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Type /lobby to open the lobby menu.").color(NamedTextColor.RED)));
                } else if (!canVisit(player, lobby.asPlanet())) {
                    player.sendMessage(Component.text("You don't have permission to visit this lobby.").color(NamedTextColor.RED));
                } else {
                    teleportToLobby(player, lobby);
                }
            }
        }
    }

    /** Handles "/lobby create <name> [material]": creates a brand-new world for the lobby. */
    private void lobbyCreate(Player player, String[] args) {
        if (!player.hasPermission("planets.create")) {
            player.sendMessage(Component.text("You don't have permission to create lobbies.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /lobby create <name> [material]").color(NamedTextColor.YELLOW));
            return;
        }
        // The last argument is the icon material only when it matches one.
        Material icon = null;
        int nameEnd = args.length;
        if (args.length >= 3) {
            Material maybe = Material.matchMaterial(args[args.length - 1]);
            if (maybe != null && maybe != Material.AIR) {
                icon = maybe;
                nameEnd = args.length - 1;
            }
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, nameEnd)).trim();
        if (name.isEmpty() || name.length() > 64) {
            player.sendMessage(Component.text("Lobby names must be 1-64 characters.").color(NamedTextColor.RED));
            return;
        }
        String id = slug(name);
        for (Lobby existing : availableLobbies()) {
            if (existing.id().equalsIgnoreCase(id) || existing.name().equalsIgnoreCase(name)) {
                player.sendMessage(Component.text("A lobby named '").color(NamedTextColor.RED)
                        .append(Component.text(name).color(NamedTextColor.YELLOW))
                        .append(Component.text("' already exists.").color(NamedTextColor.RED)));
                return;
            }
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getName().equalsIgnoreCase(id)) {
                player.sendMessage(Component.text("A world named '").color(NamedTextColor.RED)
                        .append(Component.text(id).color(NamedTextColor.YELLOW))
                        .append(Component.text("' is already loaded — pick a different lobby name.").color(NamedTextColor.RED)));
                return;
            }
        }
        if (!MultiverseHook.isPresent()) {
            player.sendMessage(Component.text("Multiverse-Core is required to create lobby worlds.").color(NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("Creating the lobby world '").color(NamedTextColor.GRAY)
                .append(Component.text(id).color(NamedTextColor.YELLOW))
                .append(Component.text("'... this can take a few seconds.").color(NamedTextColor.GRAY)));
        boolean dispatched = getServer().dispatchCommand(getServer().getConsoleSender(),
                "mv create " + id + " normal");
        if (!dispatched) {
            player.sendMessage(Component.text("Couldn't reach the Multiverse command; is Multiverse-Core enabled?").color(NamedTextColor.RED));
            return;
        }

        // Creation is synchronous, so the world should exist right away; double-check
        // shortly after in case Multiverse is still finishing spawn setup.
        Material lobbyIcon = icon; // effectively-final copy for the lambda below
        World created = Bukkit.getWorld(id);
        if (created != null) {
            registerCreatedLobby(player, id, name, lobbyIcon);
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (Bukkit.getWorld(id) != null) {
                registerCreatedLobby(player, id, name, lobbyIcon);
            } else {
                player.sendMessage(Component.text("The lobby world '").color(NamedTextColor.RED)
                        .append(Component.text(id).color(NamedTextColor.YELLOW))
                        .append(Component.text("' couldn't be created. Check the console — the folder may already exist.").color(NamedTextColor.RED)));
            }
        }, 100L);
    }

    /** Registers a freshly created world as a lobby and teleports the admin into it. */
    private void registerCreatedLobby(Player player, String id, String name, Material icon) {
        String path = "lobbies." + id;
        getConfig().set(path + ".name", name);
        getConfig().set(path + ".world", id);
        // Marks this world as plugin-generated so it stays out of the /planets list.
        getConfig().set(path + ".created", true);
        if (icon == null) {
            getConfig().set(path + ".icon", null);
        } else {
            getConfig().set(path + ".icon", icon.name());
        }
        saveConfigQuietly();
        player.sendMessage(Component.text("Lobby '").color(NamedTextColor.GREEN)
                .append(Component.text(name).color(NamedTextColor.YELLOW))
                .append(Component.text("' created in its own new world '").color(NamedTextColor.GREEN))
                .append(Component.text(id).color(NamedTextColor.YELLOW))
                .append(Component.text("'. It's now in the /lobby menu and hidden from /planets. ")
                        .color(NamedTextColor.GREEN))
                .append(Component.text("Stand where arrivals should land and run /lobby setlanding " + id + ".")
                        .color(NamedTextColor.GREEN)));
        Lobby lobby = findLobby(id);
        if (lobby != null) {
            teleportToLobby(player, lobby);
        }
    }

    /** Handles "/lobby setlanding <name>": pins the player's position as the lobby's landing spot. */
    private void lobbySetLanding(Player player, String[] args) {
        if (!player.hasPermission("planets.landing")) {
            player.sendMessage(Component.text("You don't have permission to change where players land.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /lobby setlanding <name> — stand where arrivals should land").color(NamedTextColor.YELLOW));
            return;
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Lobby lobby = findLobby(query);
        if (lobby == null) {
            player.sendMessage(Component.text("Unknown lobby '").color(NamedTextColor.RED)
                    .append(Component.text(query).color(NamedTextColor.YELLOW))
                    .append(Component.text("'. Type /lobby to open the lobby menu.").color(NamedTextColor.RED)));
            return;
        }
        Location location = player.getLocation();
        String path = "lobbies." + lobby.id();
        getConfig().set(path + ".world", location.getWorld().getName());
        getConfig().set(path + ".x", location.getX());
        getConfig().set(path + ".y", location.getY());
        getConfig().set(path + ".z", location.getZ());
        getConfig().set(path + ".yaw", (double) location.getYaw());
        getConfig().set(path + ".pitch", (double) location.getPitch());
        saveConfigQuietly();
        player.sendMessage(Component.text("Landing of lobby '").color(NamedTextColor.GREEN)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text("' set to your position (").color(NamedTextColor.GREEN))
                .append(Component.text(location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ())
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(") in '").color(NamedTextColor.GREEN))
                .append(Component.text(location.getWorld().getName()).color(NamedTextColor.YELLOW))
                .append(Component.text("'. Players clicking it will arrive here.").color(NamedTextColor.GREEN)));
    }

    /** Handles "/lobby icon <name> <material|reset>": changes the lobby's menu block. */
    private void lobbyIcon(Player player, String[] args) {
        if (!player.hasPermission("planets.icon")) {
            player.sendMessage(Component.text("You don't have permission to change lobby icons.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /lobby icon <name> <material|reset>").color(NamedTextColor.YELLOW));
            return;
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
        String materialArg = args[args.length - 1];
        Lobby lobby = findLobby(query);
        if (lobby == null) {
            player.sendMessage(Component.text("Unknown lobby '").color(NamedTextColor.RED)
                    .append(Component.text(query).color(NamedTextColor.YELLOW))
                    .append(Component.text("'. Type /lobby to open the lobby menu.").color(NamedTextColor.RED)));
            return;
        }
        String path = "lobbies." + lobby.id() + ".icon";
        if (materialArg.equalsIgnoreCase("reset") || materialArg.equals("-")) {
            getConfig().set(path, null);
            saveConfigQuietly();
            player.sendMessage(Component.text("Icon of '").color(NamedTextColor.GREEN)
                    .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text("' reset to its default.").color(NamedTextColor.GREEN)));
            return;
        }
        Material material = Material.matchMaterial(materialArg);
        if (material == null || material == Material.AIR) {
            player.sendMessage(Component.text("Unknown material '").color(NamedTextColor.RED)
                    .append(Component.text(materialArg).color(NamedTextColor.YELLOW))
                    .append(Component.text("'. Use a material name or 'reset'.").color(NamedTextColor.RED)));
            return;
        }
        getConfig().set(path, material.name());
        saveConfigQuietly();
        player.sendMessage(Component.text("Icon of '").color(NamedTextColor.GREEN)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text("' is now ").color(NamedTextColor.GREEN))
                .append(Component.text(material.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    /** Handles "/lobby delete <name>": removes a lobby from the menu. */
    private void lobbyDelete(Player player, String[] args) {
        if (!player.hasPermission("planets.delete")) {
            player.sendMessage(Component.text("You don't have permission to delete lobbies.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /lobby delete <name>").color(NamedTextColor.YELLOW));
            return;
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Lobby lobby = findLobby(query);
        if (lobby == null) {
            player.sendMessage(Component.text("Unknown lobby '").color(NamedTextColor.RED)
                    .append(Component.text(query).color(NamedTextColor.YELLOW))
                    .append(Component.text("'. Type /lobby to open the lobby menu.").color(NamedTextColor.RED)));
            return;
        }
        // One deletion path, shared with the admin lobby panel.
        deleteLobbyNow(player, lobby);
    }

    /** Handles "/lobby list": prints every lobby and its landing spot. */
    private void lobbyList(Player player) {
        List<Lobby> lobbies = availableLobbies();
        if (lobbies.isEmpty()) {
            player.sendMessage(Component.text("No lobbies configured yet — use /lobby create <name> [material].")
                    .color(NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("— Lobbies —").color(NamedTextColor.GOLD));
        for (Lobby lobby : lobbies) {
            String landing = lobby.hasLanding()
                    ? lobby.x().intValue() + ", " + lobby.y().intValue() + ", " + lobby.z().intValue()
                    + " in " + lobby.worldName()
                    : "not set — lands at the spawn of " + lobby.worldName();
            statusLine(player, lobby.id(), lobby.name() + " — " + landing);
        }
    }

    /** "The Law & Neutrals TEAM Lobby" → "the_law_neutrals_team_lobby". */
    private static String slug(String name) {
        String slug = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\- ]", "").replace(' ', '_');
        return slug.isEmpty() ? "lobby" : slug;
    }

    /** Tab-completion for /lobby, /hub and /l. */
    private List<String> lobbyTabComplete(Player player, String[] args) {
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        // The admin sub-commands follow the same gate the commands themselves use.
        boolean admin = player.isOp() || canUseAdmin(player);
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            if (admin) {
                for (String sub : List.of("create", "setlanding", "icon", "delete", "list",
                        "editor", "rename", "slot", "desc", "lobbies", "edit", "description")) {
                    add(suggestions, prefix, sub);
                }
            }
            for (Lobby lobby : availableLobbies()) {
                if (lobby.id().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(lobby.id());
                } else if (lobby.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(lobby.name());
                }
            }
            return suggestions;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list") || sub.equals("lobbies") || sub.equals("help")) {
            return List.of();
        }
        if (!admin) {
            return List.of();
        }
        if (sub.equals("create")) {
            // "/lobby create <name> [material]" — the name is free text, the
            // material is chosen from the palette.
            return args.length <= 2 ? List.of() : match(iconMaterialNames(), prefix);
        }
        boolean nameNeeded = sub.equals("setlanding") || sub.equals("delete") || sub.equals("icon")
                || sub.equals("rename") || sub.equals("slot") || sub.equals("desc")
                || sub.equals("description") || sub.equals("editor") || sub.equals("edit");
        if (nameNeeded && args.length == 2) {
            List<String> suggestions = new ArrayList<>();
            for (Lobby lobby : availableLobbies()) {
                if (lobby.id().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(lobby.id());
                } else if (lobby.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(lobby.name());
                }
            }
            return suggestions;
        }
        if (sub.equals("slot") && args.length == 3) {
            List<String> slots = new ArrayList<>();
            for (int i = 0; i <= 25; i++) {
                if (String.valueOf(i).startsWith(prefix)) {
                    slots.add(String.valueOf(i));
                }
            }
            return slots;
        }
        if (sub.equals("icon") && args.length == 3) {
            return match(iconMaterialNames(), prefix);
        }
        if ((sub.equals("desc") || sub.equals("description")) && args.length == 3) {
            // "none" clears it; anything else is the description itself.
            return match(List.of("none"), prefix);
        }
        return List.of();
    }
    /** Builds the planet list: "Middle Earth" pinned first, then every loaded Multiverse world. */
    private List<Planet> availablePlanets() {
        String middleEarthWorld = getConfig().getString("middle-earth-world", "Middle_earth");
        Map<String, Material> overrides = iconOverrides();

        List<Planet> planets = new ArrayList<>();
        planets.add(new Planet("Middle Earth",
                overrides.getOrDefault(middleEarthWorld.toLowerCase(Locale.ROOT), Material.GRASS_BLOCK),
                middleEarthWorld));

        if (MultiverseHook.isPresent()) {
            for (Planet planet : MultiverseHook.planets()) {
                if (planet.worldName().equalsIgnoreCase(middleEarthWorld)) {
                    continue; // already pinned as Middle Earth
                }
                if (isDeletedWorld(planet.worldName()) || !haveWorld(planet.worldName())) {
                    continue; // a deleted world Multiverse still remembers — hide it
                }
                if (isPreviewWorld(planet.worldName())) {
                    continue; // temporary /planets preview worlds are never planets
                }
                if (environment.isDisabledDimensionWorld(planet.worldName())) {
                    continue; // linked Nether/End of a dimension-disabled world
                }
                if (isCreatedLobbyWorld(planet.worldName())) {
                    continue; // worlds generated by /lobby create belong to lobbies, not planets
                }
                if (myPlanetManager.get(planet.worldName()) != null) {
                    continue; // player-owned planets are managed through /myp, not the public /p menu
                }
                Material icon = overrides.getOrDefault(planet.worldName().toLowerCase(Locale.ROOT), planet.icon());
                planets.add(new Planet(planet.name(), icon, planet.worldName()));
            }
        } else {
            getLogger().warning("Multiverse-Core is not installed; only 'Middle Earth' will be listed. " +
                    "Install the Multiverse-Core plugin (modrinth.com/plugin/multiverse-core) to list your worlds.");
        }
        return planets;
    }

    /**
     * Whether a world still exists: loaded, or at least present on disk. Used to
     * keep worlds that were deleted (but that Multiverse still remembers until
     * the next cleanup) out of the menus.
     */
    private boolean haveWorld(String worldName) {
        return Bukkit.getWorld(worldName) != null || worldFolderExists(worldName);
    }

    /**
     * Whether this world name was deleted here. Deleted names stay on this list
     * forever, so even a world that another plugin (Multiverse re-importing its
     * config, a backup restore...) brings back to life stays out of the menus.
     */
    private boolean isDeletedWorld(String worldName) {
        for (String name : getConfig().getStringList("deleted-planet-worlds")) {
            if (name.equalsIgnoreCase(worldName)) {
                return true;
            }
        }
        return false;
    }

    /** Remembers a deleted world so it can never appear in the menus again. */
    private void rememberDeletedWorld(String worldName) {
        if (isDeletedWorld(worldName)) {
            return;
        }
        List<String> deleted = new ArrayList<>(getConfig().getStringList("deleted-planet-worlds"));
        deleted.add(worldName);
        getConfig().set("deleted-planet-worlds", deleted);
    }

    /** Whether a world was auto-created by "/lobby create" (a lobby world, not a planet). */
    private boolean isCreatedLobbyWorld(String worldName) {
        ConfigurationSection section = getConfig().getConfigurationSection("lobbies");
        if (section == null) {
            return false;
        }
        for (String id : section.getKeys(false)) {
            Object value = section.get(id);
            if (value instanceof ConfigurationSection detail
                    && detail.getBoolean("created", false)
                    && worldName.equalsIgnoreCase(detail.getString("world", ""))) {
                return true;
            }
        }
        return false;
    }

    /** Custom menu icons from config.yml's "icons" section, keyed by lowercase world name. */
    private Map<String, Material> iconOverrides() {
        Map<String, Material> overrides = new HashMap<>();
        ConfigurationSection section = getConfig().getConfigurationSection("icons");
        if (section == null) {
            return overrides;
        }
        for (String worldName : section.getKeys(false)) {
            String materialName = section.getString(worldName);
            Material material = materialName == null ? null : Material.matchMaterial(materialName);
            if (material == null || material == Material.AIR) {
                getLogger().warning("Ignoring unknown icon material '" + materialName + "' for world '" + worldName + "'.");
                continue;
            }
            overrides.put(worldName.toLowerCase(Locale.ROOT), material);
        }
        return overrides;
    }

    // ── Economy (Vault) helpers ──────────────────────────────────────────

    /** The Vault economy API, or null when Vault is absent. */
    private net.milkbowl.vault.economy.Economy economy = null;

    /** Whether any economy is available via Vault. */
    @Override
    public boolean hasEconomy() {
        return economy != null;
    }

    /** Get a player's money balance, or -1 when economy is not available. */
    @Override
    public double getBalance(Player player) {
        if (!hasEconomy()) return -1;
        return economy.getBalance(player);
    }

    /** Withdraw an amount from a player. Returns true on success. */
    boolean withdraw(Player player, double amount) {
        if (!hasEconomy()) return false;
        var response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    /** Deposit an amount to a player. Returns true on success. */
    boolean deposit(Player player, double amount) {
        if (!hasEconomy()) return false;
        var response = economy.depositPlayer(player, amount);
        return response.transactionSuccess();
    }

    // ── Balances & leaderboards ──────────────────────────────────────────

    /** One leaderboard row: a known player with their VPL balance and NEB value. */
    record LeaderboardEntry(UUID uuid, String name, double vpl, double neb) {
    }

    /** Last built leaderboard, and when it was built (rebuilding is not free). */
    private List<LeaderboardEntry> leaderboardCache = List.of();
    private long leaderboardCacheAt;

    /** How long a built leaderboard stays valid, in seconds (see config.yml). */
    private long leaderboardCacheSeconds() {
        return Math.max(0L, getConfig().getLong("leaderboards.cache-seconds", 60L));
    }

    /** The placeholder that supplies the NEB column (see config.yml). */
    public String nebPlaceholder() {
        return getConfig().getString("leaderboards.neb-placeholder", "%neb%");
    }

    /** Whether no NEB placeholder has been configured yet (shown as a hint). */
    public boolean nebPlaceholderIsUnset() {
        String placeholder = nebPlaceholder();
        return placeholder == null || placeholder.isBlank() || placeholder.equals("%neb%");
    }

    /** Memoized playtime per player, so the leaderboard never re-reads a data file. */
    private final Map<UUID, Double> daysPlayedCache = new HashMap<>();

    /** Returns the player's playtime in hours (fractional). Same source as {@link #daysPlayed(UUID)}. */
    @Override
    public double hoursPlayed(UUID uuid) {
        double days = daysPlayed(uuid);
        return days * 24.0;
    }

    /**
     * How many days the player has actually played (fractional). This is the
     * "play time" statistic, which is the value players expect from a
     * days-played column; when a player has no recorded play time at all (very
     * old accounts, statistics wiped), it falls back to how long the account has
     * existed on this server, so nobody renders as a flat 0.
     */
    double daysPlayed(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        Double cached = daysPlayedCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        double days = 0;
        try {
            long ticks = offline.getStatistic(Statistic.PLAY_ONE_MINUTE);
            if (ticks > 0) {
                days = ticks / (20.0 * 60 * 60 * 24); // 20 ticks per second
            }
        } catch (Throwable ignored) {
            // Statistic unavailable (never played, unsupported API) — fall through.
        }
        if (days <= 0) {
            long firstPlayed = offline.getFirstPlayed();
            if (firstPlayed > 0) {
                days = (System.currentTimeMillis() - firstPlayed) / 86_400_000.0;
            }
        }
        daysPlayedCache.put(uuid, days);
        return days;
    }

    /**
     * Every known player with their VPL balance and NEB value, richest first.
     * Built on demand and cached for a short while, since it reads a balance and
     * a placeholder for every player the server has ever seen.
     */
    List<LeaderboardEntry> leaderboard() {
        long now = System.currentTimeMillis();
        if (!leaderboardCache.isEmpty()
                && now - leaderboardCacheAt < leaderboardCacheSeconds() * 1000L) {
            return leaderboardCache;
        }
        String placeholder = nebPlaceholder();
        List<LeaderboardEntry> entries = new ArrayList<>();
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            String name = offline.getName();
            if (name == null || name.isBlank()) {
                continue; // nothing to show for a nameless profile
            }
            double vpl = hasEconomy() ? economy.getBalance(offline) : 0;
            double neb = Placeholders.readNumber(offline, placeholder, 0);
            entries.add(new LeaderboardEntry(offline.getUniqueId(), name, vpl, neb));
        }
        // Richest to poorest; equal balances fall back to name order so the list
        // doesn't shuffle between rebuilds.
        entries.sort((a, b) -> {
            int byVpl = Double.compare(b.vpl(), a.vpl());
            return byVpl != 0 ? byVpl : a.name().compareToIgnoreCase(b.name());
        });
        leaderboardCache = entries;
        leaderboardCacheAt = now;
        return entries;
    }

    /** Opens the /leaderboards UI for the player. */
    void openLeaderboards(Player player) {
        if (!player.hasPermission("planets.leaderboards")) {
            player.sendMessage(Component.text("You don't have permission to view the leaderboards.")
                    .color(NamedTextColor.RED));
            return;
        }
        List<LeaderboardEntry> entries = leaderboard();
        if (entries.isEmpty()) {
            player.sendMessage(Component.text("No players to rank yet — the leaderboard fills up as players join.")
                    .color(NamedTextColor.RED));
            return;
        }
        if (!hasEconomy()) {
            player.sendMessage(Component.text("No economy plugin (Vault) found — VPL balances show as 0.")
                    .color(NamedTextColor.YELLOW));
        }
        new LeaderboardsMenu(this, player, entries).open(player);
    }

    /**
     * Handles "/bal [player]": replies to the sender with
     * "&lt;player&gt; bal : &lt;amount&gt;₾ VPL !".
     */
    private void handleBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("planets.bal")) {
            sender.sendMessage(Component.text("You don't have permission to check balances.")
                    .color(NamedTextColor.RED));
            return;
        }
        if (!hasEconomy()) {
            sender.sendMessage(Component.text("Checking balances requires an economy plugin (Vault).")
                    .color(NamedTextColor.RED));
            return;
        }
        String query;
        if (args.length == 0) {
            if (sender instanceof Player player) {
                query = player.getName();
            } else {
                sender.sendMessage(Component.text("Usage: /bal <player>").color(NamedTextColor.YELLOW));
                return;
            }
        } else {
            query = String.join(" ", args);
        }

        OfflinePlayer target = findKnownPlayer(query);
        if (target == null) {
            sender.sendMessage(Component.text("Unknown player '").color(NamedTextColor.RED)
                    .append(Component.text(query).color(NamedTextColor.YELLOW))
                    .append(Component.text("'").color(NamedTextColor.RED)));
            return;
        }
        String name = target.getName() != null ? target.getName() : query;
        sender.sendMessage(Component.text(name).color(NamedTextColor.AQUA)
                .append(Component.text(" bal : ").color(NamedTextColor.GRAY))
                .append(Component.text(formatPrice(economy.getBalance(target)) + "\u20BE VPL")
                        .color(NamedTextColor.GOLD))
                .append(Component.text(" !").color(NamedTextColor.GRAY)));
    }

    /** Resolves an online player, or one who has played here before, by name. */
    private OfflinePlayer findKnownPlayer(String query) {
        Player online = Bukkit.getPlayerExact(query);
        if (online != null) {
            return online;
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            String name = offline.getName();
            if (name != null && name.equalsIgnoreCase(query)) {
                return offline;
            }
        }
        return null;
    }

    // ── SUS list (op only) ───────────────────────────────────────────────

    /** One flagged player, stored in config.yml under "sus-players". */
    record SusEntry(UUID uuid, String name, String addedBy, long addedAt) {
    }

    /** Admins in a /sus spectate session, with what to restore on the way out. */
    private final Map<UUID, GameMode> spectateReturnMode = new HashMap<>();
    private final Map<UUID, Location> spectateReturnSpot = new HashMap<>();

    /** Whether this sender may use the /sus tools (ops, or an explicit permission). */
    boolean canUseSus(CommandSender sender) {
        return sender.isOp() || sender.hasPermission("planets.sus");
    }

    /**
     * Every flagged player. Online ones come first — an admin opening the list
     * usually wants to spectate somebody right now — then the most recently
     * flagged ones.
     */
    List<SusEntry> susList() {
        List<SusEntry> entries = new ArrayList<>();
        ConfigurationSection section = getConfig().getConfigurationSection("sus-players");
        if (section == null) {
            return entries;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue; // hand-edited key that isn't a UUID
            }
            entries.add(new SusEntry(uuid,
                    entry.getString("name", uuid.toString().substring(0, 8)),
                    entry.getString("added-by", "unknown"),
                    entry.getLong("added-at", 0L)));
        }
        entries.sort((a, b) -> {
            boolean onlineA = Bukkit.getPlayer(a.uuid()) != null;
            boolean onlineB = Bukkit.getPlayer(b.uuid()) != null;
            if (onlineA != onlineB) {
                return onlineA ? -1 : 1;
            }
            return Long.compare(b.addedAt(), a.addedAt());
        });
        return entries;
    }

    /** Handles "/sus &lt;player&gt;", "/sus list"/"/sl", "/sus remove &lt;player&gt;" and "/sus stop". */
    private void handleSusCommand(CommandSender sender, String[] args) {
        if (!canUseSus(sender)) {
            sender.sendMessage(Component.text("Only operators can use /sus.").color(NamedTextColor.RED));
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            if (sender instanceof Player player) {
                openSusMenu(player);
            } else {
                sendSusListToChat(sender);
            }
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "remove", "unflag" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /sus remove <player>").color(NamedTextColor.YELLOW));
                    return;
                }
                removeSus(sender, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                return;
            }
            case "stop", "leave", "back" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Only players can stop spectating.").color(NamedTextColor.RED));
                    return;
                }
                if (isSpectating(player)) {
                    stopSpectating(player);
                } else {
                    sender.sendMessage(Component.text("You are not spectating anyone.").color(NamedTextColor.GRAY));
                }
                return;
            }
            case "help", "?" -> {
                sendSusUsage(sender);
                return;
            }
            default -> {
                // Anything else is a player name to flag.
            }
        }

        String query = String.join(" ", args);
        OfflinePlayer target = findKnownPlayer(query);
        if (target == null) {
            sender.sendMessage(Component.text("Unknown player '").color(NamedTextColor.RED)
                    .append(Component.text(query).color(NamedTextColor.YELLOW))
                    .append(Component.text("' — they must have played here before.").color(NamedTextColor.RED)));
            return;
        }
        if (addSus(sender, target) && sender instanceof Player player) {
            openSusMenu(player); // show the updated list right away
        }
    }

    /** Flags a player. Returns whether they were newly added. */
    private boolean addSus(CommandSender sender, OfflinePlayer target) {
        UUID uuid = target.getUniqueId();
        String name = target.getName() != null ? target.getName() : uuid.toString().substring(0, 8);
        String path = "sus-players." + uuid;
        if (getConfig().isConfigurationSection(path)) {
            sender.sendMessage(Component.text(name + " is already on the SUS list.").color(NamedTextColor.YELLOW));
            return false;
        }
        getConfig().set(path + ".name", name);
        getConfig().set(path + ".added-by", sender.getName());
        getConfig().set(path + ".added-at", System.currentTimeMillis());
        saveConfigQuietly();
        getLogger().info(sender.getName() + " flagged " + name + " as suspicious.");
        sender.sendMessage(Component.text("\uD83D\uDD75 ").color(NamedTextColor.DARK_RED)
                .append(Component.text(name).color(NamedTextColor.YELLOW))
                .append(Component.text(" was added to the SUS list.").color(NamedTextColor.GREEN)));
        return true;
    }

    /** Removes a flagged player by name, so the list can't only ever grow. */
    private void removeSus(CommandSender sender, String query) {
        for (SusEntry entry : susList()) {
            if (!entry.name().equalsIgnoreCase(query)) {
                continue;
            }
            getConfig().set("sus-players." + entry.uuid(), null);
            saveConfigQuietly();
            getLogger().info(sender.getName() + " removed " + entry.name() + " from the SUS list.");
            sender.sendMessage(Component.text(entry.name() + " was removed from the SUS list.")
                    .color(NamedTextColor.GREEN));
            return;
        }
        sender.sendMessage(Component.text("'" + query + "' is not on the SUS list.").color(NamedTextColor.RED));
    }

    /** Opens the /sus list UI for the admin. */
    void openSusMenu(Player player) {
        if (!canUseSus(player)) {
            player.sendMessage(Component.text("Only operators can open the SUS list.").color(NamedTextColor.RED));
            return;
        }
        List<SusEntry> entries = susList();
        if (entries.isEmpty()) {
            player.sendMessage(Component.text("Nobody is flagged yet — flag a player with ").color(NamedTextColor.GRAY)
                    .append(Component.text("/sus <player>").color(NamedTextColor.AQUA))
                    .append(Component.text(".").color(NamedTextColor.GRAY)));
            return;
        }
        new SusMenu(this, player, entries).open(player);
    }

    /**
     * Puts the admin into spectator mode and teleports them to a flagged player.
     * Only possible while that player is online; otherwise the admin is told so
     * and nothing changes.
     */
    void spectateSus(Player admin, SusEntry entry) {
        Player target = Bukkit.getPlayer(entry.uuid());
        if (target == null) {
            admin.sendMessage(Component.text("\u2716 ").color(NamedTextColor.DARK_RED)
                    .append(Component.text(entry.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" is not online — there is nobody to teleport to.").color(NamedTextColor.RED)));
            return;
        }
        if (target.equals(admin)) {
            admin.sendMessage(Component.text("You are looking at yourself — nothing to spectate.")
                    .color(NamedTextColor.YELLOW));
            return;
        }
        // Remember how to bring the admin back before switching modes.
        spectateReturnMode.putIfAbsent(admin.getUniqueId(), admin.getGameMode());
        spectateReturnSpot.putIfAbsent(admin.getUniqueId(), admin.getLocation());
        admin.setGameMode(GameMode.SPECTATOR);
        admin.teleport(target);
        admin.sendMessage(Component.text("\uD83D\uDD75 Now spectating ").color(NamedTextColor.AQUA)
                .append(Component.text(target.getName()).color(NamedTextColor.YELLOW))
                .append(Component.text(" in " + target.getWorld().getName()
                        + ". Run /sus stop to leave spectator mode.").color(NamedTextColor.GRAY)));
    }

    /** Whether this admin is in a /sus spectate session. */
    boolean isSpectating(Player admin) {
        return spectateReturnMode.containsKey(admin.getUniqueId());
    }

    /** Leaves spectator mode: restores the admin's previous game mode and spot. */
    void stopSpectating(Player admin) {
        GameMode previous = spectateReturnMode.remove(admin.getUniqueId());
        Location spot = spectateReturnSpot.remove(admin.getUniqueId());
        if (previous != null) {
            admin.setGameMode(previous);
        }
        if (spot != null && spot.getWorld() != null) {
            try {
                admin.teleport(spot);
            } catch (RuntimeException ex) {
                getLogger().warning("Could not return " + admin.getName() + " to " + spot + ": " + ex.getMessage());
            }
        }
        admin.sendMessage(Component.text("Stopped spectating — back to ")
                .color(NamedTextColor.GREEN)
                .append(Component.text(previous == null ? "your game mode" : previous.name().toLowerCase(Locale.ROOT))
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    /** Console-friendly view of the SUS list. */
    private void sendSusListToChat(CommandSender sender) {
        List<SusEntry> entries = susList();
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("Nobody is flagged yet — flag a player with /sus <player>.")
                    .color(NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("\uD83D\uDD75 SUS list (" + entries.size() + ")").color(NamedTextColor.DARK_RED));
        for (SusEntry entry : entries) {
            boolean online = Bukkit.getPlayer(entry.uuid()) != null;
            sender.sendMessage(Component.text("• ").color(NamedTextColor.GRAY)
                    .append(Component.text(entry.name()).color(online ? NamedTextColor.RED : NamedTextColor.DARK_GRAY))
                    .append(Component.text(online ? " (online)" : " (offline)").color(NamedTextColor.GRAY))
                    .append(Component.text(" — flagged by " + entry.addedBy()).color(NamedTextColor.DARK_GRAY)));
        }
    }

    /** Usage summary for the /sus command family. */
    private void sendSusUsage(CommandSender sender) {
        sender.sendMessage(Component.text("— SUS commands —").color(NamedTextColor.DARK_RED));
        sender.sendMessage(Component.text("/sus <player>").color(NamedTextColor.AQUA)
                .append(Component.text(" — flag a suspicious player").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/sus list").color(NamedTextColor.AQUA)
                .append(Component.text(" (or /sl) — open the SUS list UI").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/sus remove <player>").color(NamedTextColor.AQUA)
                .append(Component.text(" — unflag a player").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/sus stop").color(NamedTextColor.AQUA)
                .append(Component.text(" — leave spectator mode").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("Clicking an online player's head in the list spectates and teleports you to them.")
                .color(NamedTextColor.DARK_GRAY));
    }

    /** Hook into Vault's economy service at startup. */
    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found — economy features disabled.");
            return;
        }
        net.milkbowl.vault.economy.Economy e = (net.milkbowl.vault.economy.Economy)
                getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class).getProvider();
        if (e == null) {
            getLogger().warning("No economy provider found via Vault.");
            return;
        }
        economy = e;
        getLogger().info("Vault economy hooked — " + e.getName() + " is available.");
    }

    // ── /myp command ────────────────────────────────────────────────────

    /** Returns the my-planet manager. */
    MyPlanetManager getMyPlanetManager() {
        return myPlanetManager;
    }

    @Override
    public int planetsOwned(UUID uuid) {
        return myPlanetManager == null ? 0 : myPlanetManager.ownedCount(uuid);
    }

    /** Returns every player's personal preferences (/settings). */
    PlayerSettings getPlayerSettings() {
        return playerSettings;
    }

    @Override
    public Map<String, Boolean> settings(UUID uuid) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (playerSettings != null) {
            for (PlayerSettings.Setting setting : PlayerSettings.Setting.values()) {
                result.put(setting.name(), playerSettings.get(uuid, setting));
            }
        }
        return result;
    }

    /**
     * Applies or removes the endless night vision behind the /settings toggle.
     * The effect is infinite, ambient and particle-free, so it looks like a
     * server feature rather than a potion the player drank.
     */
    public void applyNightVision(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        boolean on = playerSettings != null
                && playerSettings.get(player.getUniqueId(), PlayerSettings.Setting.NIGHT_VISION);
        if (on) {
            player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.NIGHT_VISION,
                    org.bukkit.potion.PotionEffect.INFINITE_DURATION, 0, true, false, false));
        } else {
            player.removePotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION);
        }
    }

    /** The planet soundtrack (the "Planet Music" toggle in /settings). */
    PlanetMusic planetMusic() {
        return music;
    }

    /** Returns every player's saved homes (/home). */
    HomeManager getHomeManager() {
        return homeManager;
    }

    @Override
    public int homesCount(UUID uuid) {
        return homeManager == null ? 0 : homeManager.count(uuid);
    }

    /**
     * Re-sends a player's dimension registry so a sky-tint change shows up at
     * once (no-op when packet sky tinting is unavailable).
     */
    void refreshSkyTint(Player player) {
        if (skyPackets != null) {
            skyPackets.refreshPlayer(player);
        }
    }

    /**
     * Handles "/myp [planet]": opens the My Planet control panel for the
     * player. Sub-commands: invite, uninvite, accept, deny.
     */
    private void handleMypCommand(Player player, String[] args) {
        if (args.length == 0) {
            openMyPlanetSelect(player);
            return;
        }

        // Sub-commands: claim, rename, invite, uninvite, accept, deny
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "rename" -> mypRename(player, args);
            case "invite" -> mypInvite(player, args);
            case "uninvite" -> mypUninvite(player, args);
            case "accept" -> mypAccept(player, args);
            case "deny" -> mypDeny(player, args);
            case "sell" -> mypSell(player, args);
            case "resetblockcounts" -> mypResetBlockCounts(player, args);
            default -> {
                // /myp <planet> — direct open to control panel
                String query = String.join(" ", args);
                MyPlanetData data = myPlanetManager.get(query);
                if (data == null) {
                    player.sendMessage(Component.text("Unknown planet '").color(NamedTextColor.RED)
                            .append(Component.text(query).color(NamedTextColor.YELLOW))
                            .append(Component.text("'. Use /myp to see your planets.").color(NamedTextColor.RED)));
                    return;
                }
                if (!data.ownerUuid().equals(player.getUniqueId()) && !data.isMember(player.getUniqueId())) {
                    player.sendMessage(Component.text("You are not a member of ").color(NamedTextColor.RED)
                            .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                            .append(Component.text(".").color(NamedTextColor.RED)));
                    return;
                }
                openMyPlanetMenu(player, data);
            }
        }
    }

    // ── /home, /sethome and /delhome ────────────────────────────────────

    /** Handles the three home commands: /home, /sethome and /delhome. */
    private void handleHomeCommand(Player player, String commandName, String[] args) {
        if (!player.hasPermission("planets.homes")) {
            player.sendMessage(Component.text("You don't have permission to use homes.")
                    .color(NamedTextColor.RED));
            return;
        }
        switch (commandName) {
            case "sethome" -> {
                String name = args.length > 0 ? args[0] : null;
                if (name == null || name.isBlank()) {
                    promptNewHome(player, player.getLocation());
                } else {
                    // Typed in chat: just save it and confirm, no menu popping up.
                    applyHomeCreate(player, name, player.getLocation(), false);
                }
            }
            case "delhome" -> {
                if (args.length == 0) {
                    printHomeHelp(player);
                    return;
                }
                deleteHome(player, args[0]);
            }
            default -> {
                if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                    openHomesMenu(player);
                    return;
                }
                if (args[0].equalsIgnoreCase("help")) {
                    printHomeHelp(player);
                    return;
                }
                HomeManager.Home home = homeManager.get(player.getUniqueId(), args[0]);
                if (home == null) {
                    player.sendMessage(Component.text("\uD83C\uDFE0 You don't have a home called ")
                            .color(NamedTextColor.RED)
                            .append(Component.text(args[0]).color(NamedTextColor.YELLOW))
                            .append(Component.text(" — open /home to see your list.").color(NamedTextColor.RED)));
                    return;
                }
                teleportHome(player, home);
            }
        }
    }

    /** Opens the light-blue /home menu showing every slot the player has. */
    void openHomesMenu(Player player) {
        player.closeInventory();
        new HomesMenu(this, player, homeManager).open(player);
    }

    /** Opens the free bed-colour picker for every one of the player's homes. */
    void openHomeColourMenu(Player player) {
        player.closeInventory();
        new HomeColourMenu(this, player, homeManager, null).open(player);
    }

    /** Opens the free colour picker for a single home. */
    void openHomeColourMenu(Player player, HomeManager.Home home) {
        player.closeInventory();
        new HomeColourMenu(this, player, homeManager, home).open(player);
    }

    /** Asks in chat for the name to save the spot the player is standing on as. */
    void promptNewHome(Player player, Location location) {
        pendingHomePrompts.put(player.getUniqueId(), HomePrompt.create(location));
        player.closeInventory();
        player.sendMessage(Component.text("\uD83C\uDFE0 Type a name for this home in chat — ")
                .color(NamedTextColor.YELLOW)
                .append(Component.text("1-16 letters, digits, - or _").color(NamedTextColor.GRAY))
                .append(Component.text(". Type ").color(NamedTextColor.YELLOW))
                .append(Component.text("cancel").color(NamedTextColor.RED))
                .append(Component.text(" to abort.").color(NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("It saves the spot you are standing on right now (").color(NamedTextColor.GRAY)
                .append(Component.text(shortLocation(location)).color(NamedTextColor.AQUA))
                .append(Component.text(").").color(NamedTextColor.GRAY)));
    }

    /** Asks in chat for a home's new name. */
    void promptRenameHome(Player player, String homeName) {
        HomeManager.Home home = homeManager.get(player.getUniqueId(), homeName);
        if (home == null) {
            return;
        }
        pendingHomePrompts.put(player.getUniqueId(), HomePrompt.rename(home.name()));
        player.closeInventory();
        player.sendMessage(Component.text("\u270F Type the new name for ").color(NamedTextColor.YELLOW)
                .append(Component.text(home.name()).color(NamedTextColor.AQUA))
                .append(Component.text(" in chat, or ").color(NamedTextColor.YELLOW))
                .append(Component.text("cancel").color(NamedTextColor.RED))
                .append(Component.text(" to keep it.").color(NamedTextColor.YELLOW)));
    }

    /** Applies the answer to a /home chat prompt and reopens the menu. */
    private void applyHomePrompt(Player player, HomePrompt prompt, String input) {
        if (input.isEmpty() || input.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("\uD83C\uDFE0 Home prompt cancelled.")
                    .color(NamedTextColor.GRAY));
            openHomesMenu(player);
            return;
        }
        if (prompt.rename()) {
            switch (homeManager.rename(player.getUniqueId(), prompt.homeName(), input)) {
                case OK -> player.sendMessage(Component.text("\u270F Renamed to ")
                        .color(NamedTextColor.GREEN)
                        .append(Component.text(input).color(NamedTextColor.AQUA))
                        .append(Component.text(".").color(NamedTextColor.GREEN)));
                case NAME_TAKEN -> player.sendMessage(Component.text("You already have a home called ")
                        .color(NamedTextColor.RED)
                        .append(Component.text(input).color(NamedTextColor.YELLOW))
                        .append(Component.text(".").color(NamedTextColor.RED)));
                case INVALID_NAME -> player.sendMessage(Component.text(
                                "Names can only be 1-16 letters, digits, - or _.")
                        .color(NamedTextColor.RED));
                default -> {
                }
            }
        } else {
            applyHomeCreate(player, input, prompt.location(), true);
        }
        openHomesMenu(player);
    }

    /** Saves a home, reporting whatever went wrong instead of failing silently. */
    private void applyHomeCreate(Player player, String name, Location location, boolean reopenMenu) {
        switch (homeManager.set(player, name, location)) {
            case OK -> {
                int used = homeManager.count(player.getUniqueId());
                int slots = homeManager.slots();
                player.sendMessage(Component.text("\uD83C\uDFE0 Home ").color(NamedTextColor.GREEN)
                        .append(Component.text(name).color(NamedTextColor.AQUA))
                        .append(Component.text(" saved at ").color(NamedTextColor.GREEN))
                        .append(Component.text(shortLocation(location)).color(NamedTextColor.YELLOW))
                        .append(Component.text("  (" + used + "/" + slots + " used).")
                                .color(NamedTextColor.GRAY)));
            }
            case AT_LIMIT -> player.sendMessage(Component.text(
                            "You have used all " + homeManager.slots() + " home slots — ")
                    .color(NamedTextColor.RED)
                    .append(Component.text("/delhome <name>").color(NamedTextColor.YELLOW))
                    .append(Component.text(" frees one.").color(NamedTextColor.RED)));
            case INVALID_NAME -> player.sendMessage(Component.text(
                            "Home names can only be 1-16 letters, digits, - or _.")
                    .color(NamedTextColor.RED));
            default -> {
            }
        }
        if (reopenMenu) {
            openHomesMenu(player);
        }
    }

    /** Removes one of the player's homes. */
    void deleteHome(Player player, String name) {
        if (homeManager.delete(player.getUniqueId(), name)) {
            player.sendMessage(Component.text("\uD83D\uDDD1 Home ").color(NamedTextColor.GREEN)
                    .append(Component.text(name).color(NamedTextColor.AQUA))
                    .append(Component.text(" deleted.").color(NamedTextColor.GREEN)));
        } else {
            player.sendMessage(Component.text("\uD83C\uDFE0 No home called ").color(NamedTextColor.RED)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text(" — open /home to see your list.").color(NamedTextColor.RED)));
        }
    }

    /**
     * Teleports a player to one of their homes. The world is checked and the
     * destination chunk loaded here, then the move waits out the configured
     * countdown (3s by default) so it can be cancelled by moving or damage.
     */
    void teleportHome(Player player, HomeManager.Home home) {
        World world = Bukkit.getWorld(home.world());
        if (world == null) {
            player.sendMessage(Component.text("\uD83C\uDFE0 Home ").color(NamedTextColor.RED)
                    .append(Component.text(home.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" is in a world that isn't loaded right now.")
                            .color(NamedTextColor.RED)));
            return;
        }
        if (PlanetTravel.isInCombat(player)) {
            player.sendMessage(Component.text("\u2694 You can't use /home in combat — wait a few seconds.")
                    .color(NamedTextColor.RED));
            return;
        }
        Location target = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        // Generate/load the destination chunk so nobody lands in ungenerated void.
        world.getChunkAt(target);

        int delay = homeManager.teleportDelaySeconds();
        if (delay <= 0) {
            performHomeTeleport(player, home, target);
            return;
        }

        // Clicking another home (or typing /home again) replaces the countdown.
        cancelPendingHomeTeleport(player, false);
        player.closeInventory();

        PendingHomeTeleport pending = new PendingHomeTeleport(home, delay);
        pendingHomeTeleports.put(player.getUniqueId(), pending);
        boolean messages = PlayerSettings.on(this, player.getUniqueId(), PlayerSettings.Setting.TRAVEL_MESSAGES);
        if (messages) {
            player.sendMessage(Component.text("\uD83C\uDFE0 Teleporting home: ").color(NamedTextColor.GRAY)
                    .append(Component.text(home.name()).color(NamedTextColor.AQUA))
                    .append(Component.text(" in " + delay + " seconds — don't move...")
                            .color(NamedTextColor.GRAY)));
        }

        pending.task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            // A newer teleport (or a cancellation) took over this countdown.
            if (pendingHomeTeleports.get(player.getUniqueId()) != pending) {
                return;
            }
            if (!player.isOnline()) {
                cancelPendingHomeTeleport(player, false);
                return;
            }
            pending.secondsLeft--;
            if (pending.secondsLeft > 0) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.4f);
                if (messages) {
                    player.sendMessage(Component.text("\uD83C\uDFE0 Home in " + pending.secondsLeft + "s…")
                            .color(NamedTextColor.GRAY));
                }
                return;
            }
            pendingHomeTeleports.remove(player.getUniqueId());
            if (pending.task != null) {
                pending.task.cancel();
            }
            performHomeTeleport(player, home, target);
        }, 20L, 20L);
    }

    /** Cancels a home teleport that is still counting down, if there is one. */
    private boolean cancelPendingHomeTeleport(Player player, boolean notify) {
        PendingHomeTeleport pending = pendingHomeTeleports.remove(player.getUniqueId());
        if (pending == null) {
            return false;
        }
        if (pending.task != null) {
            pending.task.cancel();
        }
        if (notify) {
            player.sendMessage(Component.text("\uD83C\uDFE0 Home teleport cancelled.")
                    .color(NamedTextColor.RED));
        }
        return true;
    }

    /** The actual move to the home, once any countdown has finished. */
    private void performHomeTeleport(Player player, HomeManager.Home home, Location target) {
        player.closeInventory();
        player.teleport(target);
        player.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        if (PlayerSettings.on(this, player.getUniqueId(), PlayerSettings.Setting.TRAVEL_MESSAGES)) {
            player.sendMessage(Component.text("\uD83C\uDFE0 Welcome home: ").color(NamedTextColor.GREEN)
                    .append(Component.text(home.name()).color(NamedTextColor.AQUA))
                    .append(Component.text("  (" + home.describe() + ")").color(NamedTextColor.GRAY)));
        }
    }

    // ── Admin homes panel ───────────────────────────────────────────────

    /** Opens the admin Homes list: every player that has homes saved. */
    void openAdminHomes(Player player) {
        if (!canUseAdmin(player)) {
            player.sendMessage(Component.text("You don't have permission to use the planet admin panel.")
                    .color(NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        new AdminHomesMenu(this, player).open(player);
    }

    /** Opens one player's homes with admin teleport/delete actions. */
    void openAdminPlayerHomes(Player player, UUID ownerUuid) {
        if (!canUseAdmin(player)) {
            return;
        }
        player.closeInventory();
        new AdminPlayerHomesMenu(this, player, ownerUuid).open(player);
    }

    /**
     * Opens the full data snapshot of a player: balance, NEB, playtime, planets,
     * homes, settings, first/last seen. Usage: {@code /planets admin playerdata <player>}.
     */
    void handleAdminPlayerData(Player player, String[] args) {
        if (!canUseAdmin(player)) {
            player.sendMessage(Component.text("You don't have permission to use the planet admin panel.")
                    .color(NamedTextColor.RED));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /planets admin playerdata <player>")
                    .color(NamedTextColor.RED));
            return;
        }
        String query = args[2];
        OfflinePlayer target = Bukkit.getOfflinePlayer(query);
        if (target.getName() == null || target.getName().isBlank()) {
            player.sendMessage(Component.text("Unknown player '").color(NamedTextColor.RED)
                    .append(Component.text(query).color(NamedTextColor.YELLOW))
                    .append(Component.text("'.").color(NamedTextColor.RED)));
            return;
        }
    }

    /** The name of a homes owner, or a short uuid when it can't be resolved. */
    String homesOwnerName(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }

    /**
     * Teleports an admin to another player's home. Unlike a player's own
     * {@code /home} this ignores the combat tag and says who it belongs to.
     */
    void teleportAdminToHome(Player admin, UUID ownerUuid, HomeManager.Home home) {
        World world = Bukkit.getWorld(home.world());
        if (world == null) {
            admin.sendMessage(Component.text("Home ").color(NamedTextColor.RED)
                    .append(Component.text(home.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" is in a world that isn't loaded right now.")
                            .color(NamedTextColor.RED)));
            return;
        }
        Location target = new Location(world, home.x(), home.y(), home.z(), home.yaw(), home.pitch());
        world.getChunkAt(target);
        admin.closeInventory();
        admin.teleport(target);
        admin.playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        admin.sendMessage(Component.text("\uD83C\uDFE0 Teleported to ").color(NamedTextColor.GREEN)
                .append(Component.text(homesOwnerName(ownerUuid)).color(NamedTextColor.AQUA))
                .append(Component.text("'s home ").color(NamedTextColor.GREEN))
                .append(Component.text(home.name()).color(NamedTextColor.AQUA))
                .append(Component.text("  (" + home.describe() + ")").color(NamedTextColor.GRAY)));
    }

    /** Deletes one home of another player, reporting what happened. */
    void deletePlayerHome(Player admin, UUID ownerUuid, String homeName) {
        if (!homeManager.delete(ownerUuid, homeName)) {
            admin.sendMessage(Component.text("That home no longer exists.").color(NamedTextColor.RED));
            return;
        }
        admin.sendMessage(Component.text("\uD83D\uDDD1 Deleted ").color(NamedTextColor.GREEN)
                .append(Component.text(homesOwnerName(ownerUuid)).color(NamedTextColor.AQUA))
                .append(Component.text("'s home ").color(NamedTextColor.GREEN))
                .append(Component.text(homeName).color(NamedTextColor.AQUA))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
        getLogger().info(admin.getName() + " deleted " + homesOwnerName(ownerUuid)
                + "'s home '" + homeName + "'.");
    }

    /** Deletes every home of one player at once. */
    void deleteAllPlayerHomes(Player admin, UUID ownerUuid) {
        List<HomeManager.Home> saved = homeManager.homes(ownerUuid);
        int removed = 0;
        for (HomeManager.Home home : saved) {
            if (homeManager.delete(ownerUuid, home.name())) {
                removed++;
            }
        }
        admin.sendMessage(Component.text("\uD83D\uDDD1 Deleted " + removed + " home(s) of ")
                .color(NamedTextColor.GREEN)
                .append(Component.text(homesOwnerName(ownerUuid)).color(NamedTextColor.AQUA))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
        getLogger().info(admin.getName() + " deleted all " + removed + " home(s) of "
                + homesOwnerName(ownerUuid) + ".");
    }

    /** Prints the home commands in chat. */
    void printHomeHelp(Player player) {
        int used = homeManager.count(player.getUniqueId());
        int slots = homeManager.slots();
        helpGroup(player, "Homes", "planets.homes");
        helpLine(player, "planets.homes", "/home", "opens the homes menu — click a home to teleport");
        helpLine(player, "planets.homes", "/home <name>", "teleports you straight to that home");
        helpLine(player, "planets.homes", "/sethome <name>", "saves where you stand as a home");
        helpLine(player, "planets.homes", "/delhome <name>", "deletes a saved home");
        player.sendMessage(Component.text("\uD83C\uDFE0 ").color(NamedTextColor.AQUA)
                .append(Component.text(used + "/" + slots + " home slots used")
                        .color(NamedTextColor.GRAY))
                .append(Component.text("  ·  teleports take " + homeManager.teleportDelaySeconds()
                        + "s  ·  bed colour is free").color(NamedTextColor.AQUA)));
    }

    /** A short "world x, y, z" line for chat messages. */
    private static String shortLocation(Location location) {
        if (location == null || location.getWorld() == null) {
            return "an unknown place";
        }
        return String.format(Locale.ROOT, "%s %.0f, %.0f, %.0f", location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
    }

    // ── Planet HUD, priority action bars and money lines ────────────────

    /**
     * Sends an action bar the optional Planet HUD steps aside for, so a lock
     * countdown, an atmosphere warning or a mention ping is never painted over
     * by the HUD.
     */
    void sendPriorityBar(Player player, Component bar) {
        lastPriorityBar.put(player.getUniqueId(), System.currentTimeMillis());
        player.sendActionBar(bar);
    }

    /**
     * Starts the repeating action-bar HUD (config: {@code hud.interval-ticks}).
     * It only draws while a player is standing on a planet or in a lobby, so a
     * vanilla world never gets an action bar it doesn't need.
     */
    private void startPlanetHud() {
        long interval = Math.max(20L, getConfig().getLong("hud.interval-ticks", 40L));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            Set<String> hudWorlds = null; // built once per cycle, only if it's needed
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID id = player.getUniqueId();
                if (playerSettings == null
                        || !playerSettings.get(id, PlayerSettings.Setting.PLANET_HUD)) {
                    continue;
                }
                if (hudWorlds == null) {
                    hudWorlds = pluginWorldNames();
                }
                World world = player.getWorld();
                if (world == null || !hudWorlds.contains(world.getName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (now - lastPriorityBar.getOrDefault(id, 0L) < HUD_QUIET_MILLIS) {
                    continue;
                }
                player.sendActionBar(hudBar(player));
            }
        }, interval, interval);
    }

    /**
     * Every world this plugin owns, lowercase: the player-owned planets, the
     * public planets and every lobby. The Planet HUD only draws in these, and
     * they are also the worlds the planet soundtrack plays in.
     */
    Set<String> pluginWorldNames() {
        Set<String> names = new HashSet<>();
        if (myPlanetManager != null) {
            for (MyPlanetData data : myPlanetManager.allPlanets()) {
                names.add(data.worldName().toLowerCase(Locale.ROOT));
            }
        }
        for (Lobby lobby : availableLobbies()) {
            names.add(lobby.worldName().toLowerCase(Locale.ROOT));
        }
        for (Planet planet : availablePlanets()) {
            names.add(planet.worldName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /** The action-bar line the Planet HUD shows, in the line the player picked. */
    private Component hudBar(Player player) {
        return Component.text(renderHudTemplate(player, hudModeFor(player.getUniqueId()).template()))
                .color(NamedTextColor.AQUA);
    }

    /** Every HUD line configured under {@code hud.modes}. */
    List<HudMode> hudModes() {
        return hudModes;
    }

    /** The HUD line a player is on, clamped to the configured list. */
    HudMode hudModeFor(UUID uuid) {
        List<HudMode> modes = hudModes.isEmpty() ? DEFAULT_HUD_MODES : hudModes;
        int index = playerSettings == null ? 0 : playerSettings.hudMode(uuid, modes.size());
        return modes.get(Math.min(Math.max(index, 0), modes.size() - 1));
    }

    /**
     * Reads the configurable HUD lines from {@code hud.modes}. A blank or
     * mistyped section falls back to the shipped three, so the HUD can never
     * break on a bad config.
     */
    private void loadHudModes() {
        List<HudMode> modes = new ArrayList<>();
        for (Map<?, ?> entry : getConfig().getMapList("hud.modes")) {
            Object template = entry.get("template");
            if (template == null || String.valueOf(template).isBlank()) {
                continue;
            }
            Object label = entry.get("label");
            modes.add(new HudMode(
                    label == null || String.valueOf(label).isBlank()
                            ? "HUD line " + (modes.size() + 1)
                            : String.valueOf(label),
                    String.valueOf(template)));
        }
        hudModes = modes.isEmpty() ? DEFAULT_HUD_MODES : List.copyOf(modes);
        pushHudDefault();
    }

    /**
     * Fills a HUD template in for one player. Unknown placeholders are left
     * exactly as typed, so a typo shows up in the HUD instead of vanishing.
     *
     * <p>Available: {@code %planet% %world% %balance% %x% %y% %z% %players%
     * %visitors%} and, on a player-owned planet, {@code %members% %blocks%
     * %block-limit% %size% %next-size%}.
     */
    String renderHudTemplate(Player player, String template) {
        World world = player.getWorld();
        Location here = player.getLocation();
        MyPlanetData data = world == null || myPlanetManager == null
                ? null : myPlanetManager.get(world.getName());
        double balance = hasEconomy() ? getBalance(player) : 0;
        return template
                .replace("%planet%", worldLabel(world))
                .replace("%world%", world == null ? "?" : world.getName())
                .replace("%balance%", formatPrice(balance))
                .replace("%x%", String.valueOf(here.getBlockX()))
                .replace("%y%", String.valueOf(here.getBlockY()))
                .replace("%z%", String.valueOf(here.getBlockZ()))
                .replace("%players%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%visitors%", String.valueOf(world == null ? 0 : world.getPlayers().size()))
                .replace("%members%", data == null ? "-" : String.valueOf(data.totalMembers()))
                .replace("%blocks%", data == null ? "-" : String.valueOf(data.totalBlocksPlaced()))
                .replace("%block-limit%", data == null ? "-" : String.valueOf(data.blockLimit()))
                .replace("%size%", data == null ? "-"
                        : MyPlanetData.sizeName(data.upgradeLevel(MyPlanetData.Upgrade.PLANET_SIZE)))
                .replace("%next-size%", data == null ? "-" : nextSizeCost(data));
    }

    // ── In-game HUD editor (/planets hud) ───────────────────────────────

    /** Opens the op-only editor for the HUD lines and their placeholders. */
    void openHudEditor(Player player) {
        if (!canUseAdmin(player)) {
            player.sendMessage(Component.text("You don't have permission to edit the Planet HUD.")
                    .color(NamedTextColor.RED));
            return;
        }
        player.closeInventory();
        new HudEditorMenu(this, player).open(player);
    }

    /** The configured default HUD line, as a 0-based index into the list. */
    int hudDefaultIndex() {
        List<HudMode> modes = hudModes.isEmpty() ? DEFAULT_HUD_MODES : hudModes;
        int oneBased = getConfig().getInt("hud.default-mode", 2);
        return Math.min(Math.max(oneBased - 1, 0), modes.size() - 1);
    }

    /** Writes a whole list of HUD lines back to config.yml and the live list. */
    void saveHudModes(List<HudMode> modes) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (HudMode mode : modes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", mode.label());
            entry.put("template", mode.template());
            raw.add(entry);
        }
        getConfig().set("hud.modes", raw);
        // Keep the default pointing at a line that still exists.
        int wanted = getConfig().getInt("hud.default-mode", 2);
        getConfig().set("hud.default-mode",
                Math.min(Math.max(wanted, 1), Math.max(1, modes.size())));
        saveConfigQuietly();
        hudModes = modes.isEmpty() ? DEFAULT_HUD_MODES : List.copyOf(modes);
        pushHudDefault();
    }

    /** Deletes one configured HUD line. */
    void removeHudMode(int index) {
        List<HudMode> modes = new ArrayList<>(hudModes);
        if (index < 0 || index >= modes.size()) {
            return;
        }
        modes.remove(index);
        saveHudModes(modes);
    }

    /** Marks one HUD line as the one new players start on. */
    void setHudDefault(Player player, int index) {
        List<HudMode> modes = hudModes;
        if (index < 0 || index >= modes.size()) {
            return;
        }
        getConfig().set("hud.default-mode", index + 1);
        saveConfigQuietly();
        pushHudDefault();
        player.sendMessage(Component.text("\u2B50 New players now start on ").color(NamedTextColor.GREEN)
                .append(Component.text(modes.get(index).label()).color(NamedTextColor.AQUA))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
        getLogger().info(player.getName() + " set the default HUD line to '"
                + modes.get(index).label() + "'.");
    }

    /** Re-reads the HUD lines from the config file (the editor's reload button). */
    void reloadHudFromConfig(Player player) {
        reloadConfig();
        loadHudModes();
        player.sendMessage(Component.text("\uD83D\uDDD8 hud.modes reloaded from config.yml (").color(NamedTextColor.AQUA)
                .append(Component.text(hudModes.size() + (hudModes.size() == 1 ? " line" : " lines"))
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(").").color(NamedTextColor.AQUA)));
    }

    /** Flashes a template to one player's action bar, so edits are visible live. */
    void flashHudPreview(Player player, String template) {
        player.sendActionBar(Component.text(renderHudTemplate(player, template))
                .color(NamedTextColor.AQUA));
    }

    /** Asks in chat for the label of a HUD line (-1 = a brand-new line). */
    void promptHudLabel(Player player, int index) {
        pendingHudNames.put(player.getUniqueId(), index);
        player.closeInventory();
        player.sendMessage(Component.text("\uD83D\uDCF0 Type the label for this HUD line in chat — ")
                .color(NamedTextColor.YELLOW)
                .append(Component.text("like \"Planet + visitors\"").color(NamedTextColor.GRAY))
                .append(Component.text(". Type ").color(NamedTextColor.YELLOW))
                .append(Component.text("cancel").color(NamedTextColor.RED))
                .append(Component.text(" to abort.").color(NamedTextColor.YELLOW)));
    }

    /** Applies an answer to the HUD label prompt and reopens the editor. */
    private void applyHudName(Player player, int index, String input) {
        if (input.isEmpty() || input.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("\uD83D\uDCF0 HUD editor prompt cancelled.")
                    .color(NamedTextColor.GRAY));
            openHudEditor(player);
            return;
        }
        if (input.length() > 32) {
            player.sendMessage(Component.text("Keep the label under 32 characters.")
                    .color(NamedTextColor.RED));
            openHudEditor(player);
            return;
        }
        List<HudMode> modes = new ArrayList<>(hudModes);
        if (index < 0) {
            modes.add(new HudMode(input, "\uD83E\uDE90 %planet%"));
        } else if (index < modes.size()) {
            modes.set(index, new HudMode(input, modes.get(index).template()));
        } else {
            openHudEditor(player);
            return;
        }
        saveHudModes(modes);
        player.sendMessage(Component.text("\uD83D\uDCF0 HUD line saved: ").color(NamedTextColor.GREEN)
                .append(Component.text(input).color(NamedTextColor.AQUA))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
        getLogger().info(player.getName() + (index < 0 ? " added" : " renamed")
                + " HUD line '" + input + "'.");
        openHudEditor(player);
    }

    /** Tells the per-player settings store which HUD line is the default. */
    private void pushHudDefault() {
        if (playerSettings != null) {
            playerSettings.defaultHudMode(hudDefaultIndex());
        }
    }

    /** The VPL price of a planet's next Planet Size level, or "max" at the top. */
    private static String nextSizeCost(MyPlanetData data) {
        int level = data.upgradeLevel(MyPlanetData.Upgrade.PLANET_SIZE);
        if (level >= MyPlanetData.PLANET_MAX_SIZE_LEVEL) {
            return "max";
        }
        double cost = MyPlanetData.upgradeCost(MyPlanetData.Upgrade.PLANET_SIZE, level);
        return cost < 0 ? "max" : String.valueOf(Math.round(cost));
    }

    /** A friendly label for a world: the planet's name, else the world's own. */
    String worldLabel(World world) {
        if (world == null) {
            return "Unknown";
        }
        MyPlanetData data = myPlanetManager == null ? null : myPlanetManager.get(world.getName());
        if (data != null) {
            return data.displayName();
        }
        Planet planet = findPlanet(availablePlanets(), world.getName());
        return planet != null ? planet.name() : world.getName();
    }

    /** A one-line summary of a planet's effects, shown when a player arrives. */
    private void sendEffectSummary(Player player) {
        if (playerSettings == null
                || !playerSettings.get(player.getUniqueId(), PlayerSettings.Setting.EFFECT_SUMMARY)) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Map<String, Integer> effects = PlanetEffects.effectsFor(world.getName());
        if (effects.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : effects.entrySet()) {
            PotionEffectType type = PlanetEffects.typeFor(entry.getKey());
            String name = type == null ? entry.getKey() : effectName(type);
            int level = Math.max(1, entry.getValue() + 1);
            parts.add(level > 1 ? name + " " + roman(level) : name);
        }
        parts.sort(String::compareTo);
        player.sendMessage(Component.text("\uD83E\uDE90 " + worldLabel(world) + " effects: ")
                .color(NamedTextColor.GRAY)
                .append(Component.text(String.join(" \u00B7 ", parts)).color(NamedTextColor.AQUA)));
    }

    /** SPEED -> "Speed", JUMP_BOOST -> "Jump Boost". */
    private static String effectName(PotionEffectType type) {
        StringBuilder out = new StringBuilder();
        for (String word : type.getKey().getKey().split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** 1 -> I, 2 -> II, and so on for the effect levels in the summary line. */
    private static String roman(int value) {
        return switch (Math.min(Math.max(value, 1), 10)) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            default -> "X";
        };
    }

    /**
     * A money line, shown only while the player's Balance Notices setting is
     * on. The money still moves either way — this only hides the chat line.
     */
    void balanceNotice(Player player, Component line) {
        if (player == null || !player.isOnline() || playerSettings == null) {
            return;
        }
        if (!playerSettings.get(player.getUniqueId(), PlayerSettings.Setting.BALANCE_NOTICES)) {
            return;
        }
        player.sendMessage(line);
    }

    /** The "Balance: X VPL" line shown after money moves, when it's switched on. */
    void sendBalance(Player player) {
        if (!hasEconomy()) {
            return;
        }
        balanceNotice(player, Component.text("\uD83D\uDCB0 Balance: ").color(NamedTextColor.DARK_GRAY)
                .append(Component.text(formatPrice(getBalance(player)) + " VPL")
                        .color(NamedTextColor.GOLD)));
    }

    // ── Personal settings, /msg, /r and teleport requests ───────────────

    /** Whether the player has one of the plugin's own chat prompts open. */
    private boolean hasPendingPrompt(Player player) {
        UUID id = player.getUniqueId();
        return pendingRenames.containsKey(id) || pendingSells.containsKey(id)
                || pendingDeletes.containsKey(id) || helpChatSearch.containsKey(id)
                || pendingLobbyEdits.containsKey(id) || pendingHomePrompts.containsKey(id)
                || pendingHudNames.containsKey(id);
    }

    /**
     * Sends the resource pack configured under {@code resource-pack} to a
     * player. It is off by default, so with no pack set nothing happens; when a
     * pack is added, the menus' {@code custom-model-data} decorations light up.
     */
    private void sendResourcePack(Player player) {
        if (!getConfig().getBoolean("resource-pack.enabled", false)) {
            return;
        }
        String url = getConfig().getString("resource-pack.url", "");
        if (url == null || url.isBlank()) {
            return;
        }
        String sha1 = getConfig().getString("resource-pack.sha1", "");
        boolean required = getConfig().getBoolean("resource-pack.required", false);
        String promptText = getConfig().getString("resource-pack.prompt",
                "Custom textures make the menus look right");
        try {
            player.setResourcePack(url, sha1 == null ? "" : sha1, required, Component.text(promptText));
        } catch (Throwable ex) {
            getLogger().warning("Could not send the resource pack to " + player.getName() + ": " + ex);
        }
    }

    /** An online player by exact then case-insensitive name, or null. */
    private static Player resolveOnlinePlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        return exact != null ? exact : Bukkit.getPlayer(name);
    }

    /** /msg <player> <message> — a private message. */
    private void handleMsgCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can send private messages.").color(NamedTextColor.RED));
            return;
        }
        if (!player.hasPermission("planets.settings")) {
            player.sendMessage(Component.text("You don't have permission to send private messages.")
                    .color(NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /msg <player> <message>").color(NamedTextColor.YELLOW));
            return;
        }
        Player target = resolveOnlinePlayer(args[0]);
        if (target == null) {
            player.sendMessage(Component.text("Player '").color(NamedTextColor.RED)
                    .append(Component.text(args[0]).color(NamedTextColor.YELLOW))
                    .append(Component.text("' is not online.").color(NamedTextColor.RED)));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You can't message yourself.").color(NamedTextColor.RED));
            return;
        }
        if (!playerSettings.get(target.getUniqueId(), PlayerSettings.Setting.MSG)
                && !player.hasPermission("planets.settings.bypass")) {
            player.sendMessage(Component.text(target.getName()).color(NamedTextColor.YELLOW)
                    .append(Component.text(" has private messages turned off.").color(NamedTextColor.RED)));
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        deliverMessage(player, target, message);
        replyTargets.put(target.getUniqueId(), player.getUniqueId());
        replyTargets.put(player.getUniqueId(), target.getUniqueId());
    }

    /** /r <message> — replies to the last private-message partner. */
    private void handleReplyCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can send private messages.").color(NamedTextColor.RED));
            return;
        }
        if (args.length < 1) {
            player.sendMessage(Component.text("Usage: /r <message>").color(NamedTextColor.YELLOW));
            return;
        }
        UUID partnerId = replyTargets.get(player.getUniqueId());
        Player target = partnerId == null ? null : Bukkit.getPlayer(partnerId);
        if (target == null) {
            player.sendMessage(Component.text("Nobody to reply to — use ").color(NamedTextColor.RED)
                    .append(Component.text("/msg <player> <message>").color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return;
        }
        deliverMessage(player, target, String.join(" ", args));
        replyTargets.put(target.getUniqueId(), player.getUniqueId());
    }

    /** Sends a private message to both ends, pinging the receiver if they want it. */
    private void deliverMessage(Player from, Player to, String message) {
        from.sendMessage(Component.text("→ " + to.getName() + ": ").color(NamedTextColor.GRAY)
                .append(Component.text(message).color(NamedTextColor.WHITE)));
        to.sendMessage(Component.text("← " + from.getName() + ": ").color(NamedTextColor.GRAY)
                .append(Component.text(message).color(NamedTextColor.WHITE)));
        if (playerSettings.get(to.getUniqueId(), PlayerSettings.Setting.MSG_PING)) {
            to.playSound(to.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
        }
    }

    /** /tpa <player> (here=false) or /tpahere <player> (here=true). */
    private void handleTpaCommand(CommandSender sender, String[] args, boolean here) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can send teleport requests.").color(NamedTextColor.RED));
            return;
        }
        if (!player.hasPermission("planets.settings")) {
            player.sendMessage(Component.text("You don't have permission to send teleport requests.")
                    .color(NamedTextColor.RED));
            return;
        }
        if (args.length < 1) {
            player.sendMessage(Component.text("Usage: " + (here ? "/tpahere <player>" : "/tpa <player>"))
                    .color(NamedTextColor.YELLOW));
            return;
        }
        Player target = resolveOnlinePlayer(args[0]);
        if (target == null) {
            player.sendMessage(Component.text("Player '").color(NamedTextColor.RED)
                    .append(Component.text(args[0]).color(NamedTextColor.YELLOW))
                    .append(Component.text("' is not online.").color(NamedTextColor.RED)));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You can't teleport to yourself.").color(NamedTextColor.RED));
            return;
        }
        if (!playerSettings.get(target.getUniqueId(), PlayerSettings.Setting.TP_REQUESTS)
                && !player.hasPermission("planets.settings.bypass")) {
            player.sendMessage(Component.text(target.getName()).color(NamedTextColor.YELLOW)
                    .append(Component.text(" has teleport requests turned off.").color(NamedTextColor.RED)));
            return;
        }
        tpaRequests.put(target.getUniqueId(),
                new TpaRequest(player.getUniqueId(), player.getName(), here, System.currentTimeMillis()));
        player.sendMessage(Component.text(here
                        ? "Teleport request sent to " + target.getName() + " (asking them to come to you)."
                        : "Teleport request sent to " + target.getName() + ".")
                .color(NamedTextColor.GREEN));
        target.sendMessage(Component.text(player.getName()).color(NamedTextColor.YELLOW)
                .append(Component.text(here
                        ? " wants you to teleport to them."
                        : " wants to teleport to you.").color(NamedTextColor.GREEN)));
        Component accept = Component.text("[✔ Accept]").color(NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/tpaccept " + player.getName()))
                .hoverEvent(HoverEvent.showText(Component.text("Accept the teleport")));
        Component deny = Component.text("[✖ Deny]").color(NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/tpdeny " + player.getName()))
                .hoverEvent(HoverEvent.showText(Component.text("Decline the teleport")));
        target.sendMessage(accept.append(Component.text("   ")).append(deny));
    }

    /** /tpaccept [player] — performs the pending teleport. */
    private void handleTpaAccept(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can accept teleport requests.").color(NamedTextColor.RED));
            return;
        }
        TpaRequest request = takeTpaRequest(player, args);
        if (request == null) {
            return;
        }
        Player from = Bukkit.getPlayer(request.from());
        if (from == null) {
            player.sendMessage(Component.text(request.fromName() + " is no longer online.")
                    .color(NamedTextColor.RED));
            return;
        }
        Player traveller = request.here() ? player : from;
        Player anchor = request.here() ? from : player;
        traveller.teleport(anchor.getLocation());
        player.sendMessage(Component.text("Teleport accepted — ").color(NamedTextColor.GREEN)
                .append(Component.text(request.here()
                                ? "you were moved to " + from.getName()
                                : from.getName() + " was moved to you")
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
        if (!from.getUniqueId().equals(player.getUniqueId())) {
            from.sendMessage(Component.text(player.getName() + " accepted your teleport request.")
                    .color(NamedTextColor.GREEN));
        }
        getLogger().info(player.getName() + " accepted " + from.getName() + "'s teleport request.");
    }

    /** /tpdeny [player] — refuses the pending teleport. */
    private void handleTpaDeny(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can deny teleport requests.").color(NamedTextColor.RED));
            return;
        }
        TpaRequest request = takeTpaRequest(player, args);
        if (request == null) {
            return;
        }
        player.sendMessage(Component.text("Teleport request denied.").color(NamedTextColor.YELLOW));
        Player from = Bukkit.getPlayer(request.from());
        if (from != null) {
            from.sendMessage(Component.text(player.getName() + " denied your teleport request.")
                    .color(NamedTextColor.RED));
        }
    }

    /** Removes and returns the still-valid request waiting for this player. */
    private TpaRequest takeTpaRequest(Player player, String[] args) {
        TpaRequest request = tpaRequests.remove(player.getUniqueId());
        if (request == null) {
            player.sendMessage(Component.text("You have no pending teleport request.")
                    .color(NamedTextColor.RED));
            return null;
        }
        if (System.currentTimeMillis() - request.at() > TPA_EXPIRY_MILLIS) {
            player.sendMessage(Component.text("That teleport request expired — ask again.")
                    .color(NamedTextColor.RED));
            return null;
        }
        if (args.length >= 1 && !args[0].equalsIgnoreCase(request.fromName())) {
            tpaRequests.put(player.getUniqueId(), request);
            player.sendMessage(Component.text("No pending request from '").color(NamedTextColor.RED)
                    .append(Component.text(args[0]).color(NamedTextColor.YELLOW))
                    .append(Component.text("'. Use /tpaccept with no name to take the current one.")
                            .color(NamedTextColor.RED)));
            return null;
        }
        return request;
    }

    // ── /myp rename ──────────────────────────────────────────────────

    /**
     * Handles "/myp rename [planet]": starts a chat prompt — the next
     * message the player sends becomes the planet's display name.
     */
    void mypRename(Player player, String[] args) {
        MyPlanetData data = resolvePlanetForCommand(player, args, 1);
        if (data == null) return;
        if (!data.canManage(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner or co-owner can rename the planet.").color(NamedTextColor.RED));
            return;
        }
        int editNumber = data.renameCount() + 1;
        int price = renamePrice(editNumber);
        pendingRenames.put(player.getUniqueId(), data.worldName().toLowerCase(Locale.ROOT));
        player.closeInventory();
        player.sendMessage(Component.text("Type the new name for ").color(NamedTextColor.YELLOW)
                .append(Component.text(data.displayName()).color(NamedTextColor.GOLD))
                .append(Component.text(" in chat (1-32 characters). Type ").color(NamedTextColor.YELLOW))
                .append(Component.text("cancel").color(NamedTextColor.RED))
                .append(Component.text(" to abort.").color(NamedTextColor.YELLOW)));
        if (price > 0) {
            player.sendMessage(Component.text("This will be rename #" + editNumber + " and costs ")
                            .color(NamedTextColor.YELLOW)
                    .append(Component.text(formatPrice(price) + " VPL").color(NamedTextColor.GOLD))
                    .append(Component.text(" — charged when you confirm.").color(NamedTextColor.GRAY)));
        } else {
            player.sendMessage(Component.text("Your first rename is free!").color(NamedTextColor.GREEN));
        }
    }

    /** Applies a rename answered in chat for the /myp rename prompt. */
    private void applyRename(Player player, String worldName, String newName) {
        MyPlanetData data = myPlanetManager.get(worldName);
        if (data == null) {
            return;
        }
        if (newName.isEmpty() || newName.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("Rename cancelled.").color(NamedTextColor.GRAY));
            return;
        }
        if (newName.length() > 32) {
            player.sendMessage(Component.text("Too long — keep the name under 32 characters.").color(NamedTextColor.RED));
            pendingRenames.put(player.getUniqueId(), worldName);
            return;
        }
        // Renames get more expensive each time: 1st free, then 1k, 5k, 10k, 20k...
        int editNumber = data.renameCount() + 1;
        int price = renamePrice(editNumber);
        if (price > 0) {
            if (!hasEconomy()) {
                player.sendMessage(Component.text("Rename #" + editNumber + " costs ").color(NamedTextColor.RED)
                        .append(Component.text(formatPrice(price) + " VPL").color(NamedTextColor.YELLOW))
                        .append(Component.text(" but no economy plugin is available.").color(NamedTextColor.RED)));
                return;
            }
            double balance = getBalance(player);
            if (balance < 0) {
                player.sendMessage(Component.text("Could not check your balance. Is the economy plugin working?").color(NamedTextColor.RED));
                return;
            }
            if (balance < price) {
                player.sendMessage(Component.text("Rename #" + editNumber + " costs ").color(NamedTextColor.RED)
                        .append(Component.text(formatPrice(price) + " VPL").color(NamedTextColor.YELLOW))
                        .append(Component.text(" but you only have ").color(NamedTextColor.RED))
                        .append(Component.text(formatPrice(balance) + " VPL.").color(NamedTextColor.YELLOW)));
                return;
            }
            if (!withdraw(player, price)) {
                player.sendMessage(Component.text("Payment failed — rename cancelled.").color(NamedTextColor.RED));
                return;
            }
        }
        data.displayName(newName);
        data.renameCount(editNumber);
        myPlanetManager.save();
        player.sendMessage(Component.text("Planet renamed to ").color(NamedTextColor.GREEN)
                .append(Component.text(newName).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
        int nextPrice = renamePrice(editNumber + 1);
        if (nextPrice > 0) {
            player.sendMessage(Component.text("Your next rename will cost ").color(NamedTextColor.GRAY)
                    .append(Component.text(formatPrice(nextPrice) + " VPL").color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.GRAY)));
        }
        if (price > 0) {
            sendBalance(player); // this rename was charged, so show what is left
        }
    }

    /**
     * Price in VPL for the Nth rename of a planet (1-based). The first rename
     * is free, then the ladder is 1k, 5k, 10k and every later rename 20k.
     * Configured under {@code my-planet.rename-prices} in config.yml.
     */
    int renamePrice(int editNumber) {
        if (editNumber <= 1) {
            return 0;
        }
        List<Integer> prices = getConfig().getIntegerList("my-planet.rename-prices");
        if (prices.isEmpty()) {
            prices = DEFAULT_RENAME_PRICES;
        }
        int idx = Math.max(editNumber - 2, 0);
        return prices.get(Math.min(idx, prices.size() - 1));
    }

    /** Applies a planet-delete confirmation answered in chat. */
    private void applyDelete(Player player, String worldName, String input) {
        input = input.trim();
        if (input.isEmpty() || input.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("Delete cancelled.").color(NamedTextColor.GRAY));
            return;
        }
        if (!input.equalsIgnoreCase(worldName)) {
            player.sendMessage(Component.text("That doesn't match '").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("'. Delete cancelled.").color(NamedTextColor.RED)));
            return;
        }

        // Perform the actual deletion: the shared helper removes the world, its
        // folder, Multiverse's entry, the ownership record and every setting.
        player.sendMessage(Component.text("Deleting '").color(NamedTextColor.GRAY)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text("'... this removes the world folder permanently.").color(NamedTextColor.GRAY)));
        deletePlanetNow(player, worldName);
    }

    /** Applies a sell price answered in chat for the /myp sell prompt. */
    private void applySellPrice(Player player, String worldName, String priceInput) {
        MyPlanetData data = myPlanetManager.get(worldName);
        if (data == null) {
            return;
        }
        if (priceInput.isEmpty() || priceInput.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("Sell cancelled.").color(NamedTextColor.GRAY));
            return;
        }
        try {
            double price = Double.parseDouble(priceInput);
            if (price <= 0) {
                player.sendMessage(Component.text("Price must be greater than 0.").color(NamedTextColor.RED));
                setPendingSell(player, worldName);
                player.sendMessage(Component.text("Enter a valid price in VPL (or type \"cancel\" to abort).").color(NamedTextColor.GRAY));
                return;
            }
            if (price > 999999999) {
                player.sendMessage(Component.text("Price is too high (max 999,999,999 VPL).").color(NamedTextColor.RED));
                setPendingSell(player, worldName);
                player.sendMessage(Component.text("Enter a valid price in VPL (or type \"cancel\" to abort).").color(NamedTextColor.GRAY));
                return;
            }
            data.putForSale(price);
            myPlanetManager.save();
            player.sendMessage(Component.text("\uD83D\uDCB0 Planet ").color(NamedTextColor.GREEN)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" is now on sale for ").color(NamedTextColor.GREEN))
                    .append(Component.text(formatPrice(price) + " VPL").color(NamedTextColor.YELLOW))
                    .append(Component.text("!").color(NamedTextColor.GREEN)));
            player.sendMessage(Component.text("You can take it off the market again by clicking the sell button in /myp.").color(NamedTextColor.GRAY));
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid price. Enter a number (e.g. 500).").color(NamedTextColor.RED));
            setPendingSell(player, worldName);
            player.sendMessage(Component.text("Enter a valid price in VPL (or type \"cancel\" to abort).").color(NamedTextColor.GRAY));
        }
    }

    // ── /myp resetblockcounts ────────────────────────────────────────────

    /**
     * Handles "/myp resetblockcounts [planet]": clears every player's placed
     * block counter on the planet, so building under the Block Limitations
     * upgrade can start fresh. Owner or co-owner only.
     */
    private void mypResetBlockCounts(Player player, String[] args) {
        MyPlanetData data = resolvePlanetForCommand(player, args, 1);
        if (data == null) return;
        if (!data.canManage(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner or co-owner can reset block counts.").color(NamedTextColor.RED));
            return;
        }
        int cleared = data.totalBlocksPlaced();
        data.resetBlockCounts();
        myPlanetManager.save();
        player.sendMessage(Component.text("\u267B Reset ").color(NamedTextColor.GREEN)
                .append(Component.text(cleared + " placed block(s)").color(NamedTextColor.YELLOW))
                .append(Component.text(" on ").color(NamedTextColor.GREEN))
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(". Everyone can place blocks again.").color(NamedTextColor.GREEN)));
    }

    // ── /myp invite ──────────────────────────────────────────────────────

    /**
     * Handles "/myp invite <player> [planet]": sends an invitation to a
     * player to join the specified planet. If only one planet is owned, the
     * planet argument can be omitted.
     */
    private void mypInvite(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /myp invite <player> [planet]").color(NamedTextColor.YELLOW));
            return;
        }
        // Find the target by name. Offline players can be invited too — the
        // invitation waits for them and is shown the next time they log in.
        String playerName = args[1];
        OfflinePlayer target = resolveTarget(player, playerName);
        if (target == null) {
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You can't invite yourself!").color(NamedTextColor.RED));
            return;
        }
        // Resolve the planet
        MyPlanetData data = resolvePlanetForCommand(player, args, 2);
        if (data == null) return;
        inviteToPlanet(player, data, target.getUniqueId(), targetName(target));
    }

    // ── Invitations (shared by the command and the invitations UI) ───────

    /**
     * Resolves a player by name, online or offline, so invitations also reach
     * players who are not logged in. Returns null (after explaining why) when
     * the name belongs to nobody who has ever played here.
     */
    OfflinePlayer resolveTarget(Player actor, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online == null) {
            online = Bukkit.getPlayer(name);
        }
        if (online != null) {
            return online;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        if (offline == null) {
            offline = Bukkit.getOfflinePlayer(name);
        }
        if (offline == null || (!offline.hasPlayedBefore() && offline.getName() == null)) {
            actor.sendMessage(Component.text("Player '").color(NamedTextColor.RED)
                    .append(Component.text(name).color(NamedTextColor.YELLOW))
                    .append(Component.text("' has never played on this server.").color(NamedTextColor.RED)));
            return null;
        }
        return offline;
    }

    /** A display name for an offline player, falling back to a short uuid. */
    static String targetName(OfflinePlayer target) {
        String name = target.getName();
        return name != null ? name : target.getUniqueId().toString().substring(0, 8);
    }

    /**
     * Invites a player to a planet. Used by {@code /myp invite} and by the
     * invite picker in the invitations UI. Online targets are told right away;
     * offline ones get the invitation the next time they log in.
     */
    boolean inviteToPlanet(Player inviter, MyPlanetData data, UUID targetId, String targetName) {
        if (!data.canManageMembers(inviter.getUniqueId())) {
            inviter.sendMessage(Component.text("Only the owner, co-owner, or moderator can invite players.").color(NamedTextColor.RED));
            return false;
        }
        if (data.isMember(targetId)) {
            inviter.sendMessage(Component.text(targetName).color(NamedTextColor.YELLOW)
                    .append(Component.text(" is already a member of ").color(NamedTextColor.RED))
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return false;
        }
        if (data.isInvited(targetId)) {
            inviter.sendMessage(Component.text(targetName).color(NamedTextColor.YELLOW)
                    .append(Component.text(" already has a pending invitation to ").color(NamedTextColor.RED))
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return false;
        }
        if (data.totalMembers() >= data.memberCapacity()) {
            inviter.sendMessage(Component.text("Planet ").color(NamedTextColor.RED)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" has reached its member capacity (").color(NamedTextColor.RED))
                    .append(Component.text(data.memberCapacity()).color(NamedTextColor.YELLOW))
                    .append(Component.text("). Upgrade the Member Capacity first.").color(NamedTextColor.RED)));
            return false;
        }
        data.invite(inviter.getUniqueId(), targetId);
        myPlanetManager.save();
        inviter.sendMessage(Component.text("Invited ").color(NamedTextColor.GREEN)
                .append(Component.text(targetName).color(NamedTextColor.YELLOW))
                .append(Component.text(" to ").color(NamedTextColor.GREEN))
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
        Player online = Bukkit.getPlayer(targetId);
        if (online != null) {
            notifyInvite(online, data, inviter.getName());
        } else {
            inviter.sendMessage(Component.text(targetName + " is offline — the invitation will be waiting "
                            + "for them the next time they log in.").color(NamedTextColor.GRAY));
        }
        getLogger().info(inviter.getName() + " invited " + targetName + " to '" + data.worldName() + "'.");
        return true;
    }

    /** Sends one invitation with clickable accept / deny buttons. */
    void notifyInvite(Player target, MyPlanetData data, String inviterName) {
        if (!playerSettings.get(target.getUniqueId(), PlayerSettings.Setting.INVITE_NOTIFICATIONS)) {
            return; // the invitation is still stored and listed in /myp → Invitations
        }
        target.sendMessage(Component.text("You've been invited to planet ").color(NamedTextColor.GREEN)
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(inviterName == null ? "!" : " by " + inviterName + "!")
                        .color(NamedTextColor.GREEN)));
        Component accept = Component.text("[✔ Accept]").color(NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/myp accept " + data.worldName()))
                .hoverEvent(HoverEvent.showText(Component.text("Join " + data.displayName())));
        Component deny = Component.text("[✖ Deny]").color(NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/myp deny " + data.worldName()))
                .hoverEvent(HoverEvent.showText(Component.text("Decline the invitation")));
        target.sendMessage(accept.append(Component.text("   ")).append(deny));
        target.sendMessage(Component.text("Or type ").color(NamedTextColor.GRAY)
                .append(Component.text("/myp accept " + data.worldName()).color(NamedTextColor.AQUA))
                .append(Component.text(" to join, ").color(NamedTextColor.GRAY))
                .append(Component.text("/myp deny " + data.worldName()).color(NamedTextColor.RED))
                .append(Component.text(" to decline.").color(NamedTextColor.GRAY)));
    }

    /** Reminds a player, when they join, about every invitation still waiting. */
    void sendPendingInvites(Player player) {
        if (!playerSettings.get(player.getUniqueId(), PlayerSettings.Setting.INVITE_NOTIFICATIONS)) {
            return;
        }
        List<MyPlanetData> pending = new ArrayList<>();
        for (MyPlanetData data : myPlanetManager.allPlanets()) {
            if (data.isInvited(player.getUniqueId())) {
                pending.add(data);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        player.sendMessage(Component.text("✉ You have " + pending.size()
                        + " pending planet invitation(s):").color(NamedTextColor.GOLD));
        for (MyPlanetData data : pending) {
            notifyInvite(player, data, null);
        }
    }

    // ── /myp uninvite ────────────────────────────────────────────────────

    /**
     * Handles "/myp uninvite <player> [planet]": revokes a pending
     * invitation.
     */
    private void mypUninvite(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /myp uninvite <player> [planet]").color(NamedTextColor.YELLOW));
            return;
        }
        // Find target UUID from name (online only for simplicity)
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            target = Bukkit.getPlayer(args[1]);
        }
        if (target == null) {
            player.sendMessage(Component.text("Player '").color(NamedTextColor.RED)
                    .append(Component.text(args[1]).color(NamedTextColor.YELLOW))
                    .append(Component.text("' is not online.").color(NamedTextColor.RED)));
            return;
        }
        MyPlanetData data = resolvePlanetForCommand(player, args, 2);
        if (data == null) return;
        if (!data.canManageMembers(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner, co-owner, or moderator can revoke invitations.").color(NamedTextColor.RED));
            return;
        }
        if (!data.isInvited(target.getUniqueId())) {
            player.sendMessage(Component.text(target.getName()).color(NamedTextColor.YELLOW)
                    .append(Component.text(" doesn't have a pending invitation to ").color(NamedTextColor.RED))
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return;
        }
        data.revokeInvitation(target.getUniqueId());
        myPlanetManager.save();
        player.sendMessage(Component.text("Revoked invitation for ").color(NamedTextColor.GREEN)
                .append(Component.text(target.getName()).color(NamedTextColor.YELLOW))
                .append(Component.text(" to ").color(NamedTextColor.GREEN))
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
        target.sendMessage(Component.text("Your invitation to planet ").color(NamedTextColor.RED)
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(" was revoked.").color(NamedTextColor.RED)));
    }

    // ── /myp accept ──────────────────────────────────────────────────────

    /**
     * Handles "/myp accept [planet]": accepts a pending invitation and
     * joins the planet as a MEMBER.
     */
    private void mypAccept(Player player, String[] args) {
        // Find a planet the player is invited to
        MyPlanetData data = null;
        if (args.length >= 2) {
            data = resolvePlanetForCommand(player, args, 1);
            if (data == null) return;
        } else {
            // Find the first planet the player is invited to
            for (MyPlanetData planet : myPlanetManager.allPlanets()) {
                if (planet.isInvited(player.getUniqueId())) {
                    data = planet;
                    break;
                }
            }
            if (data == null) {
                player.sendMessage(Component.text("You don't have any pending invitations.").color(NamedTextColor.RED));
                return;
            }
        }
        if (!data.isInvited(player.getUniqueId())) {
            player.sendMessage(Component.text("You don't have a pending invitation to ").color(NamedTextColor.RED)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return;
        }
        // Check member capacity
        if (data.totalMembers() >= data.memberCapacity()) {
            player.sendMessage(Component.text("Planet ").color(NamedTextColor.RED)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" has reached its member capacity.").color(NamedTextColor.RED)));
            return;
        }
        // Accept: add as MEMBER, remove invitation
        data.revokeInvitation(player.getUniqueId());
        data.addMember(player.getUniqueId(), MyPlanetData.Role.MEMBER);
        myPlanetManager.save();
        player.sendMessage(Component.text("Welcome to ").color(NamedTextColor.GREEN)
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text("! You are now a MEMBER.").color(NamedTextColor.GREEN)));
        // Notify the owner
        Player owner = Bukkit.getPlayer(data.ownerUuid());
        if (owner != null) {
            owner.sendMessage(Component.text(player.getName()).color(NamedTextColor.YELLOW)
                    .append(Component.text(" joined ").color(NamedTextColor.GREEN))
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.GREEN)));
        }
    }

    // ── /myp deny ────────────────────────────────────────────────────────

    /**
     * Handles "/myp deny [planet]": declines a pending invitation.
     */
    private void mypDeny(Player player, String[] args) {
        MyPlanetData data = null;
        if (args.length >= 2) {
            data = resolvePlanetForCommand(player, args, 1);
            if (data == null) return;
        } else {
            for (MyPlanetData planet : myPlanetManager.allPlanets()) {
                if (planet.isInvited(player.getUniqueId())) {
                    data = planet;
                    break;
                }
            }
            if (data == null) {
                player.sendMessage(Component.text("You don't have any pending invitations.").color(NamedTextColor.RED));
                return;
            }
        }
        if (!data.isInvited(player.getUniqueId())) {
            player.sendMessage(Component.text("You don't have a pending invitation to ").color(NamedTextColor.RED)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
            return;
        }
        data.revokeInvitation(player.getUniqueId());
        myPlanetManager.save();
        player.sendMessage(Component.text("Declined invitation to ").color(NamedTextColor.RED)
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.RED)));
        // Notify the owner
        Player owner = Bukkit.getPlayer(data.ownerUuid());
        if (owner != null) {
            owner.sendMessage(Component.text(player.getName()).color(NamedTextColor.YELLOW)
                    .append(Component.text(" declined the invitation to ").color(NamedTextColor.RED))
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.RED)));
        }
    }

    // ── /myp sell ──────────────────────────────────────────────────────

    /**
     * Handles "/myp sell [planet] <price>": puts the player's planet up for
     * sale at the given VPL price. The last argument is always the price;
     * everything between "sell" and the price is the planet name.
     */
    private void mypSell(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /myp sell [planet] <price>").color(NamedTextColor.YELLOW));
            return;
        }

        // The last arg is the price.
        String priceStr = args[args.length - 1];
        double price;
        try {
            price = Double.parseDouble(priceStr);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid price '\"" + priceStr + "\"'. Enter a number (e.g. 500).\"").color(NamedTextColor.RED));
            return;
        }
        if (price <= 0) {
            player.sendMessage(Component.text("Price must be greater than 0.").color(NamedTextColor.RED));
            return;
        }
        if (price > 999999999) {
            player.sendMessage(Component.text("Price is too high (max 999,999,999 VPL).").color(NamedTextColor.RED));
            return;
        }

        // Resolve the planet: everything between "sell" and the price.
        MyPlanetData data;
        if (args.length == 2) {
            // /myp sell <price> — auto-select if only one owned.
            List<MyPlanetData> owned = myPlanetManager.ownedBy(player.getUniqueId());
            if (owned.isEmpty()) {
                player.sendMessage(Component.text("You don't own any planets.").color(NamedTextColor.RED));
                return;
            }
            if (owned.size() > 1) {
                player.sendMessage(Component.text("You own multiple planets. Specify which one:").color(NamedTextColor.RED));
                for (MyPlanetData p : owned) {
                    player.sendMessage(Component.text("\u2022 ").color(NamedTextColor.GRAY)
                            .append(Component.text(p.displayName()).color(NamedTextColor.YELLOW)));
                }
                return;
            }
            data = owned.get(0);
        } else {
            // /myp sell <planet...> <price> — planet name is args[1..n-1].
            String planetQuery = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
            data = myPlanetManager.get(planetQuery);
            if (data == null) {
                player.sendMessage(Component.text("Unknown planet '\"" + planetQuery + "\"'. Use /myp to see your planets.\"").color(NamedTextColor.RED));
                return;
            }
        }

        if (!data.ownerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Only the owner can sell the planet.").color(NamedTextColor.RED));
            return;
        }

        if (data.forSale()) {
            // Already for sale — update the price.
            data.putForSale(price);
            myPlanetManager.save();
            player.sendMessage(Component.text("\uD83D\uDCB0 Updated ").color(NamedTextColor.GREEN)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text("'s price to ").color(NamedTextColor.GREEN))
                    .append(Component.text(formatPrice(price) + " VPL").color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.GREEN)));
        } else {
            data.putForSale(price);
            myPlanetManager.save();
            player.sendMessage(Component.text("\uD83D\uDCB0 Planet ").color(NamedTextColor.GREEN)
                    .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" is now on sale for ").color(NamedTextColor.GREEN))
                    .append(Component.text(formatPrice(price) + " VPL").color(NamedTextColor.YELLOW))
                    .append(Component.text("!").color(NamedTextColor.GREEN)));
            player.sendMessage(Component.text("Other players can find it in /myp \u2192 Planets for Sale.").color(NamedTextColor.GRAY));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Resolves a planet from the command args starting at the given index.
     * If the player owns only one planet, it can be omitted.
     */
    private MyPlanetData resolvePlanetForCommand(Player player, String[] args, int startIndex) {
        List<MyPlanetData> owned = myPlanetManager.ownedBy(player.getUniqueId());
        if (args.length > startIndex) {
            String query = String.join(" ", Arrays.copyOfRange(args, startIndex, args.length));
            MyPlanetData data = myPlanetManager.get(query);
            // Also try matching by display name if world-name lookup failed.
            if (data == null) {
                String lowerQuery = query.toLowerCase(Locale.ROOT);
                data = owned.stream()
                        .filter(p -> p.displayName().toLowerCase(Locale.ROOT).equals(lowerQuery))
                        .findFirst().orElse(null);
            }
            if (data == null) {
                player.sendMessage(Component.text("Unknown planet '").color(NamedTextColor.RED)
                        .append(Component.text(query).color(NamedTextColor.YELLOW))
                        .append(Component.text("'. Use /planets to see available planets.").color(NamedTextColor.RED)));
                return null;
            }
            if (!data.ownerUuid().equals(player.getUniqueId()) && !data.isMember(player.getUniqueId())) {
                player.sendMessage(Component.text("You are not a member of ").color(NamedTextColor.RED)
                        .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                        .append(Component.text(".").color(NamedTextColor.RED)));
                return null;
            }
            return data;
        }
        // No planet specified — auto-select if only one is owned
        if (owned.size() == 1) {
            return owned.get(0);
        }
        if (owned.isEmpty()) {
            player.sendMessage(Component.text("You don't own any planets. Specify a planet name.").color(NamedTextColor.RED));
        } else {
            player.sendMessage(Component.text("You own multiple planets. Specify which planet:").color(NamedTextColor.RED));
            for (MyPlanetData p : owned) {
                player.sendMessage(Component.text("• ").color(NamedTextColor.GRAY)
                        .append(Component.text(p.displayName()).color(NamedTextColor.YELLOW)));
            }
        }
        return null;
    }

    /** Suggests both world names and display names for a player's owned planets. */
    private List<String> ownedPlanetSuggestions(Player player, String prefix) {
        return myPlanetManager.ownedBy(player.getUniqueId()).stream()
                .flatMap(data -> {
                    List<String> names = new ArrayList<>();
                    names.add(data.worldName());
                    if (!data.displayName().equals(data.worldName())) {
                        names.add(data.displayName());
                    }
                    return names.stream();
                })
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .distinct()
                .toList();
    }

    /** Tab completion for /myp sub-commands (invite, uninvite player names). */
    private List<String> mypTabComplete(Player player, String[] args) {
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            for (String s : List.of("rename", "invite", "uninvite", "accept", "deny", "sell", "resetblockcounts")) {
                if (s.startsWith(prefix)) suggestions.add(s);
            }
            // Also suggest owned planet names + display names for direct /myp <planet>
            suggestions.addAll(ownedPlanetSuggestions(player, prefix));
            return suggestions;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ((sub.equals("invite") || sub.equals("uninvite")) && args.length == 2) {
            // Online player names
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (sub.equals("rename") && args.length == 2) {
            return ownedPlanetSuggestions(player, prefix);
        }
        if (sub.equals("sell") && args.length == 2) {
            return ownedPlanetSuggestions(player, prefix);
        }
        if (sub.equals("resetblockcounts") && args.length == 2) {
            return ownedPlanetSuggestions(player, prefix);
        }
        // Planet name for third+ args
        if ((sub.equals("invite") || sub.equals("uninvite")) && args.length == 3) {
            return ownedPlanetSuggestions(player, prefix);
        }
        if ((sub.equals("accept") || sub.equals("deny")) && args.length == 2) {
            // Planets the player is invited to
            return myPlanetManager.allPlanets().stream()
                    .filter(p -> p.isInvited(player.getUniqueId()))
                    .map(MyPlanetData::worldName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    /** Online player names matching a prefix, for tab completion. */
    private static List<String> onlineNames(String prefix) {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }

    /** Opens the main My Planet control panel for a specific planet. */
    void openMyPlanetMenu(Player player, MyPlanetData data) {
        player.closeInventory();
        new MyPlanetMenu(this, player, data).open(player);
    }

    /**
     * Sets the world border for a planet based on the configured radius.
     * The border is centered on the world spawn.
     */
    private void setPlanetWorldBorder(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("Could not set world border for '" + worldName + "' — world not found.");
            return;
        }
        double borderRadius = getConfig().getDouble("planet-world-border-radius", 15.0);
        if (borderRadius <= 0) {
            return; // Border disabled
        }
        setWorldBorderForPlanet(world, borderRadius);
        recordBorder(worldName, borderRadius);
        saveConfigQuietly();
        getLogger().info("Set world border for '" + worldName + "' to " + borderRadius + " blocks radius.");
    }

    /**
     * Sets the world border for a planet to match its current Planet Size
     * upgrade level. Called after upgrades are purchased.
     */
    public void updateWorldBorder(MyPlanetData data) {
        World world = Bukkit.getWorld(data.worldName());
        if (world == null) {
            getLogger().warning("Could not update world border for '" + data.worldName() + "' — world not found.");
            return;
        }
        int level = data.upgradeLevel(MyPlanetData.Upgrade.PLANET_SIZE);
        int size = MyPlanetData.borderSizeAtLevel(level);
        setWorldBorderForPlanet(world, size);
        // Grow the ground together with the border: newly unlocked land is laid
        // out with the planet's own terrain instead of staying an endless void.
        // Planets bought before terrain was recorded fall back to their
        // archetype's layer stack and get it written now.
        PlanetTerrain.Spec terrain = PlanetTerrain.load(getConfig(), data.worldName());
        if (terrain == null) {
            PlanetArchetypes.Archetype archetype = PlanetArchetypes.byId(data.archetypeId());
            if (archetype != null) {
                terrain = archetype.terrain();
                PlanetTerrain.save(getConfig(), data.worldName(), terrain);
                saveConfigQuietly();
            }
        }
        if (terrain != null) {
            PlanetTerrain.ensureFloor(world, data.worldName(), terrain);
        }
        getLogger().info("Updated world border for '" + data.worldName() + "' to " + size
                + " blocks (" + MyPlanetData.sizeName(level) + " plan).");
    }

    private void setWorldBorderForPlanet(World world, double borderRadius) {
        Location center = world.getSpawnLocation();
        WorldBorder border = world.getWorldBorder();
        border.setCenter(center);
        border.setSize((int) borderRadius);
        border.setWarningTime(5);
        recordBorder(world.getName(), borderRadius);
        // ~10% of the border, and never more than the plot itself: a 5x5 block
        // starter plot must not be permanently wrapped in the red border tint.
        border.setWarningDistance((int) Math.max(1, borderRadius * 0.1));
    }

    /**
     * Safety net run shortly after startup (and usable any time): lays missing
     * ground on every planet that has a recorded terrain spec, falling back to
     * the owner's archetype for planets created before terrain was tracked. It
     * only ever fills empty columns, so player dig-outs and builds are safe.
     */
    private void repairPlanetTerrain() {
        // Preview worlds are throwaway: anything a restart left behind goes now.
        List<String> leftoverPreviews = previewWorldNames();
        if (!leftoverPreviews.isEmpty()) {
            for (String name : leftoverPreviews) {
                clearWorldConfig(name);
                deleteWorldNow(name);
            }
            getConfig().set("preview-worlds", null);
            saveConfigQuietly();
            PlanetTravel.loadConfig(getConfig());
            PlanetEffects.loadConfig(getConfig());
            environment.loadConfig(getConfig());
            getLogger().info("Removed " + leftoverPreviews.size() + " leftover preview world(s).");
        }

        for (Planet planet : availablePlanets()) {
            World world = Bukkit.getWorld(planet.worldName());
            PlanetTerrain.Spec terrain = PlanetTerrain.load(getConfig(), planet.worldName());
            if (world != null && terrain != null) {
                PlanetTerrain.ensureFloor(world, planet.worldName(), terrain);
            }
        }
        for (MyPlanetData data : myPlanetManager.allPlanets()) {
            World world = Bukkit.getWorld(data.worldName());
            if (world == null) {
                continue;
            }
            PlanetTerrain.Spec terrain = PlanetTerrain.load(getConfig(), data.worldName());
            if (terrain == null) {
                PlanetArchetypes.Archetype archetype = PlanetArchetypes.byId(data.archetypeId());
                if (archetype == null) {
                    continue;
                }
                terrain = archetype.terrain();
                PlanetTerrain.save(getConfig(), data.worldName(), terrain);
                saveConfigQuietly();
            }
            PlanetTerrain.ensureFloor(world, data.worldName(), terrain);
        }
    }

    /**
     * Re-applies each planet's <em>own</em> world border after a config load or
     * reload.
     *
     * <p>It deliberately never invents a size: a public planet keeps the border
     * it has by default (its recorded one is restored if the file is known), and
     * a player-owned planet is put back to its Planet Size level. Because
     * Bukkit's {@code setSize} is written into the world's {@code level.dat},
     * blindly stamping one global size here used to shrink every public planet
     * to {@code planet-world-border-radius} on every {@code /planets reload}.
     */
    public void applyWorldBordersToAllPlanets() {
        // Player-owned planets follow their size upgrade level.
        for (MyPlanetData data : myPlanetManager.allPlanets()) {
            World world = Bukkit.getWorld(data.worldName());
            if (world == null) {
                continue;
            }
            setWorldBorderForPlanet(world, MyPlanetData.borderSizeAtLevel(
                    data.upgradeLevel(MyPlanetData.Upgrade.PLANET_SIZE)));
        }
        // Public planets keep their border, unless one was recorded for them.
        for (Planet planet : availablePlanets()) {
            World world = Bukkit.getWorld(planet.worldName());
            if (world == null) {
                continue;
            }
            Double recorded = recordedBorder(planet.worldName());
            if (recorded != null && recorded > 0) {
                setWorldBorderForPlanet(world, recorded);
            }
            // Repair the ground of generated planets: any planet whose world was
            // created before its terrain was recorded (or that a server version
            // left empty) gets its layer stack laid down now.
            PlanetTerrain.Spec terrain = PlanetTerrain.load(getConfig(), planet.worldName());
            if (terrain != null) {
                PlanetTerrain.ensureFloor(world, planet.worldName(), terrain);
            }
        }
    }

    /** The server's main world name, or an empty string. */
    private String mainWorldName() {
        return Bukkit.getWorlds().isEmpty() ? "" : Bukkit.getWorlds().get(0).getName();
    }

    // ── Recorded borders (survive restarts and /planets reload) ──────────

    /** The border size last set for a world, or null when none was recorded. */
    Double recordedBorder(String worldName) {
        String path = "planet-borders." + worldName.toLowerCase(Locale.ROOT);
        return getConfig().contains(path) ? getConfig().getDouble(path) : null;
    }

    /**
     * Remembers a world's border size so a reload can put it back. The caller
     * is responsible for saving the config, so a bulk pass writes the file once.
     */
    private void recordBorder(String worldName, double size) {
        if (isPreviewWorld(worldName)) {
            return; // preview worlds are thrown away; their config goes with them
        }
        getConfig().set("planet-borders." + worldName.toLowerCase(Locale.ROOT), size);
    }

    /**
     * Sets the world border of every public planet (and Middle Earth) to one
     * size, and remembers it. Owned planets are left to their own size level.
     *
     * @return how many worlds were changed
     */
    /** Worlds the last {@link #setPublicPlanetBorders} call deliberately skipped. */
    private final List<String> lastBorderSkips = new ArrayList<>();

    /** The protected worlds skipped by the last bulk border change. */
    List<String> lastBorderSkips() {
        return List.copyOf(lastBorderSkips);
    }

    int setPublicPlanetBorders(double size) {
        int changed = 0;
        lastBorderSkips.clear();
        String mainWorld = mainWorldName();
        String middleEarth = getConfig().getString("middle-earth-world", "Middle_earth");
        for (Planet planet : availablePlanets()) {
            World world = Bukkit.getWorld(planet.worldName());
            if (world == null) {
                continue;
            }
            // Never resize the server's main world or Middle Earth by accident:
            // both are built to be large, and shrinking one is not recoverable.
            if (planet.worldName().equalsIgnoreCase(mainWorld)
                    || planet.worldName().equalsIgnoreCase(middleEarth)) {
                lastBorderSkips.add(planet.worldName());
                continue;
            }
            setWorldBorderForPlanet(world, size);
            recordBorder(planet.worldName(), size);
            changed++;
        }
        saveConfigQuietly();
        return changed;
    }

    /**
     * Puts every planet back to its own border: owned planets to their size
     * level, public planets to the size recorded for them.
     *
     * @return how many worlds were changed
     */
    /**
     * Handles {@code /planets borders}: shows, sets or restores the borders of
     * the public planets.
     */
    private void handleBordersCommand(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("info") || args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(Component.text("\uD83C\uDF10 World borders").color(NamedTextColor.AQUA));
            for (Planet planet : availablePlanets()) {
                World world = Bukkit.getWorld(planet.worldName());
                Double recorded = recordedBorder(planet.worldName());
                sender.sendMessage(Component.text(" \u2022 ").color(NamedTextColor.DARK_GRAY)
                        .append(Component.text(planet.worldName()).color(NamedTextColor.YELLOW))
                        .append(Component.text(world == null ? "  (not loaded)" : "  now: "
                                + (long) world.getWorldBorder().getSize() + " blocks")
                                .color(NamedTextColor.GRAY))
                        .append(Component.text(recorded == null ? "  recorded: none"
                                : "  recorded: " + (long) (double) recorded + " blocks")
                                .color(recorded == null ? NamedTextColor.DARK_GRAY : NamedTextColor.AQUA)));
            }
            sender.sendMessage(Component.text("Usage: ").color(NamedTextColor.GRAY)
                    .append(Component.text("/planets borders set <blocks|NxN>").color(NamedTextColor.YELLOW))
                    .append(Component.text(" — e.g. 96 or 6x6 (chunks)").color(NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/planets borders restore").color(NamedTextColor.YELLOW)
                    .append(Component.text(" — owned planets to their size level, public planets to the size recorded for them")
                            .color(NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("Note: owned planets are never touched by ")
                    .color(NamedTextColor.DARK_GRAY)
                    .append(Component.text("/planets borders set").color(NamedTextColor.GRAY))
                    .append(Component.text(" — they follow their Planet Size upgrade. The main world and Middle Earth are skipped too.")
                            .color(NamedTextColor.DARK_GRAY)));
            return;
        }

        if (args[1].equalsIgnoreCase("restore")) {
            int changed = restorePlanetBorders();
            sender.sendMessage(Component.text("\uD83C\uDF10 Restored the border of " + changed + " planet(s).")
                    .color(NamedTextColor.GREEN));
            return;
        }

        if (!args[1].equalsIgnoreCase("set")) {
            sender.sendMessage(Component.text("Usage: /planets borders [list|set <blocks|NxN>|restore]")
                    .color(NamedTextColor.YELLOW));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /planets borders set <blocks|NxN>")
                    .color(NamedTextColor.YELLOW));
            return;
        }
        double size = parseBorderSize(args[2]);
        if (size <= 0) {
            sender.sendMessage(Component.text("Give a size in blocks (96) or in chunks (6x6), 1 to 60000000.")
                    .color(NamedTextColor.RED));
            return;
        }
        int changed = setPublicPlanetBorders(size);
        sender.sendMessage(Component.text("\uD83C\uDF10 Set the border of " + changed + " public planet(s) to ")
                .color(NamedTextColor.GREEN)
                .append(Component.text((long) size + " blocks").color(NamedTextColor.AQUA))
                .append(Component.text(" and remembered it for the next reload.")
                        .color(NamedTextColor.GRAY)));
        // Be explicit about the protected worlds this command never touches, so
        // it doesn't look like the border change silently failed on them.
        List<String> skipped = lastBorderSkips();
        if (!skipped.isEmpty()) {
            sender.sendMessage(Component.text("Skipped protected world(s): ").color(NamedTextColor.GRAY)
                    .append(Component.text(String.join(", ", skipped)).color(NamedTextColor.YELLOW)));
            sender.sendMessage(Component.text("Resize one with ").color(NamedTextColor.DARK_GRAY)
                    .append(Component.text("/planets world <world> border <size> confirm")
                            .color(NamedTextColor.GRAY)));
        }
    }

    /** Reads a border size as blocks ("96") or chunks ("6x6"), or -1 when invalid. */
    static double parseBorderSize(String input) {
        if (input == null || input.isBlank()) {
            return -1;
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.contains("x")) {
                String[] parts = value.split("x");
                if (parts.length != 2) {
                    return -1;
                }
                int a = Integer.parseInt(parts[0].trim());
                int b = Integer.parseInt(parts[1].trim());
                if (a < 1 || b < 1 || a > 2000 || b > 2000) {
                    return -1;
                }
                return Math.max(a, b) * 16.0;
            }
            double blocks = Double.parseDouble(value);
            return blocks >= 1 && blocks <= 60000000 ? blocks : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    int restorePlanetBorders() {
        int changed = 0;
        for (MyPlanetData data : myPlanetManager.allPlanets()) {
            World world = Bukkit.getWorld(data.worldName());
            if (world == null) {
                continue;
            }
            setWorldBorderForPlanet(world, MyPlanetData.borderSizeAtLevel(
                    data.upgradeLevel(MyPlanetData.Upgrade.PLANET_SIZE)));
            changed++;
        }
        for (Planet planet : availablePlanets()) {
            World world = Bukkit.getWorld(planet.worldName());
            Double recorded = recordedBorder(planet.worldName());
            if (world == null || recorded == null || recorded <= 0) {
                continue;
            }
            setWorldBorderForPlanet(world, recorded);
            changed++;
        }
        return changed;
    }

    /** The custom menu icon for a world from config.yml, or null for the default. */
    Material iconOverride(String worldName) {
        String materialName = getConfig().getString("icons." + worldName);
        Material material = materialName == null ? null : Material.matchMaterial(materialName);
        return material == Material.AIR ? null : material;
    }

    /**
     * Releases a player's ownership of a planet without deleting the world:
     * removes the record, notifies members and sends the owner back to the
     * overview flow.
     */
    void abandonPlanet(Player player, MyPlanetData data) {
        String display = data.displayName();
        // Notify members (owner is removed with the record below).
        for (UUID memberUuid : data.allMembers()) {
            if (memberUuid.equals(player.getUniqueId())) {
                continue;
            }
            Player member = Bukkit.getPlayer(memberUuid);
            if (member != null) {
                member.sendMessage(Component.text(player.getName()).color(NamedTextColor.YELLOW)
                        .append(Component.text(" abandoned ").color(NamedTextColor.RED))
                        .append(Component.text(display).color(NamedTextColor.YELLOW))
                        .append(Component.text(". It is retired forever.").color(NamedTextColor.RED)));
            }
        }
        myPlanetManager.remove(data.worldName());
        // Retire the name permanently: the world folder stays on disk with all
        // its previous builds, so it must never be handed out again as a
        // "brand-new" planet to a future buyer.
        myPlanetManager.retire(data.worldName());
        player.sendMessage(Component.text("You abandoned ").color(NamedTextColor.GREEN)
                .append(Component.text(display).color(NamedTextColor.YELLOW))
                .append(Component.text(". The world is kept, but its name is retired — it can never be bought again.").color(NamedTextColor.GREEN)));
        // Instantly move the ex-owner out of the planet they just lost:
        // first to another planet they own, otherwise to a random planet
        // from the public /p menu.
        evacuateAfterAbandon(player, data.worldName());
    }

    /**
     * Instantly moves a player out of a planet they just abandoned: to
     * another planet they own when they have one, otherwise to a random
     * planet from the public /p menu. Falls back to the main world's spawn
     * when nothing else is available.
     */
    private void evacuateAfterAbandon(Player player, String abandonedWorld) {
        // 1) Another planet the player still owns.
        for (MyPlanetData owned : myPlanetManager.ownedBy(player.getUniqueId())) {
            if (owned.worldName().equalsIgnoreCase(abandonedWorld)) {
                continue;
            }
            World world = loadPlanetWorld(owned.worldName());
            if (world != null) {
                teleportToSpawn(player, world, owned.displayName());
                return;
            }
        }
        // 2) A random planet from the public /p menu (owned planets are excluded there).
        List<Planet> options = new ArrayList<>();
        for (Planet planet : availablePlanets()) {
            if (!planet.worldName().equalsIgnoreCase(abandonedWorld)) {
                options.add(planet);
            }
        }
        if (!options.isEmpty()) {
            Planet target = options.get(ThreadLocalRandom.current().nextInt(options.size()));
            World world = loadPlanetWorld(target.worldName());
            if (world != null) {
                teleportToSpawn(player, world, target.name());
                return;
            }
        }
        // 3) Nothing else available — send them to the main world's spawn.
        World main = Bukkit.getWorlds().get(0);
        teleportToSpawn(player, main, main.getName());
    }

    /** Loads a world for the post-abandon evacuation when it isn't loaded yet. */
    private World loadPlanetWorld(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            return world;
        }
        try {
            return new WorldCreator(worldName).createWorld();
        } catch (Exception ex) {
            getLogger().warning("Could not load world '" + worldName + "' to evacuate a player: " + ex.getMessage());
            return null;
        }
    }

    /** Instantly teleports a player to a world's spawn with a short notice. */
    private void teleportToSpawn(Player player, World world, String planetName) {
        player.teleport(world.getSpawnLocation());
        player.sendMessage(Component.text("You were moved to ").color(NamedTextColor.YELLOW)
                .append(Component.text(planetName).color(NamedTextColor.GREEN))
                .append(Component.text(".").color(NamedTextColor.YELLOW)));
    }

    /**
     * Moves a kicked visitor out of a planet instantly: to their own planet
     * when they have one, otherwise to the main world's spawn. Used by the
     * owner's "Kick Visitors" button in /myp.
     */
    void kickVisitorFromPlanet(Player visitor, MyPlanetData planet) {
        visitor.sendMessage(Component.text("You were kicked from ").color(NamedTextColor.RED)
                .append(Component.text(planet.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.RED)));
        for (MyPlanetData owned : myPlanetManager.ownedBy(visitor.getUniqueId())) {
            World world = loadPlanetWorld(owned.worldName());
            if (world != null) {
                visitor.teleport(world.getSpawnLocation());
                return;
            }
        }
        World main = Bukkit.getWorlds().get(0);
        visitor.teleport(main.getSpawnLocation());
    }

    /**
     * Opens the My Planets overview menu, listing every planet the player
     * owns or is a member of. Run by /myp with no arguments.
     */
    /** Sets a pending sell prompt for the player. */
    void setPendingSell(Player player, String worldName) {
        pendingSells.put(player.getUniqueId(), worldName.toLowerCase(Locale.ROOT));
    }

    /**
     * Formats a money amount compactly for display:
     * 1,000 -> "1k", 1,000,000 -> "1m", 1,000,000,000 -> "1b", etc.
     * Smaller amounts are shown as-is.
     */
    public static String formatPrice(double value) {
        if (value >= 1_000_000_000_000L) {
            return trimPrice(value / 1_000_000_000_000L) + "t";
        }
        if (value >= 1_000_000_000L) {
            return trimPrice(value / 1_000_000_000L) + "b";
        }
        if (value >= 1_000_000L) {
            return trimPrice(value / 1_000_000L) + "m";
        }
        if (value >= 1_000L) {
            return trimPrice(value / 1_000L) + "k";
        }
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** Formats a playtime in hours for menus: 0.5 -> "0.5 hours", 1 -> "1 hour". */
    public static String formatHours(double hours) {
        if (hours <= 0) {
            return "0 hours";
        }
        String amount = hours >= 100
                ? String.valueOf((long) Math.round(hours))
                : String.format(Locale.ROOT, "%.1f", hours);
        if (amount.endsWith(".0")) {
            amount = amount.substring(0, amount.length() - 2);
        }
        return amount + (amount.equals("1") ? " hour" : " hours");
    }

    /** Formats a playtime in days for menus: 0.5 -> "0.5 days", 1 -> "1 day". */
    public static String formatDays(double days) {
        if (days <= 0) {
            return "0 days";
        }
        String amount = days >= 10
                ? String.valueOf((long) Math.round(days))
                : String.format(Locale.ROOT, "%.1f", days);
        if (amount.endsWith(".0")) {
            amount = amount.substring(0, amount.length() - 2);
        }
        return amount + (amount.equals("1") ? " day" : " days");
    }

    /** Formats a timestamp (ms since epoch) as a human-readable date. */
    public static String formatDate(long ms) {
        if (ms <= 0) {
            return "never";
        }
        java.text.DateFormat fmt = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        return fmt.format(new Date(ms));
    }

    /** Trims a scaled price: 1.0 -> "1", 1.5 -> "1.5", 1.25 -> "1.25". */
    private static String trimPrice(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        String s = String.format(Locale.ROOT, "%.2f", value);
        if (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Handles "/planets buy <amount>": sets the price (in VPL money) a player pays
     * to buy a brand-new default planet. The price shows on the paper item in the
     * buy-a-planet UI, so players always see the current cost before confirming.
     */
    private void setPlanetPrice(Player player, String[] args) {
        if (!player.hasPermission("planets.world")) {
            player.sendMessage(Component.text("You don't have permission to set the planet price.").color(NamedTextColor.RED));
            return;
        }
        if (!hasEconomy()) {
            player.sendMessage(Component.text("Economy plugin not found.").color(NamedTextColor.RED));
            return;
        }
        double price;
        try {
            price = Double.parseDouble(args[1]);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            player.sendMessage(Component.text("Usage: /planets buy <amount>").color(NamedTextColor.YELLOW));
            return;
        }
        if (price < 0) {
            player.sendMessage(Component.text("Price cannot be negative.").color(NamedTextColor.RED));
            return;
        }
        getConfig().set("my-planet.buy-cost", price);
        saveConfigQuietly();
        player.sendMessage(Component.text("A new planet now costs ").color(NamedTextColor.GREEN)
                .append(Component.text(formatPrice(price) + " VPL").color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    /**
     * Handles "/planets renameprice" — shows the current rename price ladder,
     * "/planets renameprice <p1> <p2> ..." — sets a new ladder, and
     * "/planets renameprice reset" — restores the default.
     */
    private void setRenamePrices(Player player, String[] args) {
        if (!player.hasPermission("planets.world")) {
            player.sendMessage(Component.text("You don't have permission to change rename prices.").color(NamedTextColor.RED));
            return;
        }

        // /planets renameprice — show the current ladder
        if (args.length == 1) {
            List<Integer> prices = getConfig().getIntegerList("my-planet.rename-prices");
            if (prices.isEmpty()) prices = DEFAULT_RENAME_PRICES;
            player.sendMessage(Component.text("— Rename Price Ladder —").color(NamedTextColor.GOLD));
            player.sendMessage(Component.text("Rename #1 is always free.").color(NamedTextColor.GREEN));
            for (int i = 0; i < prices.size(); i++) {
                int renameNum = i + 2; // 1st is free, ladder starts at 2nd
                renamePriceLine(player, "Rename #" + renameNum, formatPrice(prices.get(i)) + " VPL");
            }
            renamePriceLine(player, "Any later rename", formatPrice(prices.get(prices.size() - 1)) + " VPL");
            player.sendMessage(Component.text("Change with: /planets renameprice <p1> <p2> ... or reset").color(NamedTextColor.GRAY));
            return;
        }

        // /planets renameprice reset — restore defaults
        if (args[1].equalsIgnoreCase("reset")) {
            getConfig().set("my-planet.rename-prices", DEFAULT_RENAME_PRICES);
            saveConfigQuietly();
            player.sendMessage(Component.text("Rename prices reset to default: ").color(NamedTextColor.GREEN)
                    .append(Component.text(DEFAULT_RENAME_PRICES.stream()
                            .map(p -> formatPrice(p)).collect(Collectors.joining(", ")))
                            .color(NamedTextColor.YELLOW))
                    .append(Component.text(".").color(NamedTextColor.GREEN)));
            return;
        }

        // /planets renameprice <p1> <p2> ... — set new ladder
        List<Integer> newPrices = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            try {
                int p = Integer.parseInt(args[i]);
                if (p < 0) {
                    player.sendMessage(Component.text("Price cannot be negative: ").color(NamedTextColor.RED)
                            .append(Component.text(args[i]).color(NamedTextColor.YELLOW)));
                    return;
                }
                newPrices.add(p);
            } catch (NumberFormatException ex) {
                player.sendMessage(Component.text("Invalid price: ").color(NamedTextColor.RED)
                        .append(Component.text(args[i]).color(NamedTextColor.YELLOW))
                        .append(Component.text(". Use whole numbers.").color(NamedTextColor.RED)));
                return;
            }
        }
        if (newPrices.isEmpty()) {
            player.sendMessage(Component.text("Usage: /planets renameprice <price1> [price2] ... or reset").color(NamedTextColor.YELLOW));
            return;
        }
        getConfig().set("my-planet.rename-prices", newPrices);
        saveConfigQuietly();
        player.sendMessage(Component.text("Rename prices updated: ").color(NamedTextColor.GREEN)
                .append(Component.text(newPrices.stream()
                        .map(p -> formatPrice(p)).collect(Collectors.joining(", ")))
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
        player.sendMessage(Component.text("Rename #1 is still free. The last price is reused for every later rename.").color(NamedTextColor.GRAY));
    }

    private static void renamePriceLine(Player player, String label, String value) {
        player.sendMessage(Component.text("• ").color(NamedTextColor.GRAY)
                .append(Component.text(label + ": ").color(NamedTextColor.GRAY))
                .append(Component.text(value).color(NamedTextColor.YELLOW)));
    }

    // ── Admin control panel (/planets admin) ─────────────────────────────

    /** One planet as shown in the admin panel, with everything a row needs. */
    record AdminPlanet(String worldName, String displayName, MyPlanetData owned,
                       boolean locked, boolean protectedWorld, String landingMode,
                       boolean structures, PlanetTerrain.Spec terrain) {

        /** Whether a player owns it (as opposed to a public /p planet). */
        boolean isOwned() {
            return owned != null;
        }
    }

    /** One generation choice in the admin terrain picker. */
    record Generation(String id, String displayName, String description,
                      Material icon, PlanetTerrain.Spec spec) {
    }

    /** Whether a player may use the admin panel: op, or the explicit permission. */
    boolean canUseAdmin(Player player) {
        return player.isOp() || player.hasPermission("planets.admin");
    }

    /** The owner's name, or a short uuid when their account can't be resolved. */
    String adminOwnerName(MyPlanetData data) {
        if (data == null || data.ownerUuid() == null) {
            return "nobody";
        }
        String name = Bukkit.getOfflinePlayer(data.ownerUuid()).getName();
        return name != null ? name : data.ownerUuid().toString().substring(0, 8);
    }

    /** How players land on a world (mirrors what PlanetTravel actually uses). */
    String landingModeOf(String worldName) {
        String mode = getConfig().getString("landing-modes." + worldName);
        if (mode != null && !mode.isBlank()) {
            return mode.toLowerCase(Locale.ROOT);
        }
        for (String station : getConfig().getConfigurationSection("station-worlds") == null
                ? List.<String>of()
                : getConfig().getConfigurationSection("station-worlds").getKeys(false)) {
            if (station.equalsIgnoreCase(worldName)) {
                return "station";
            }
        }
        return "random";
    }

    /** The name to show for a world in the admin panel. */
    private String adminDisplayName(String worldName) {
        MyPlanetData data = myPlanetManager.get(worldName);
        return data != null ? data.displayName() : worldName;
    }

    /** Every planet an admin can act on: player-owned ones first, then public. */
    List<AdminPlanet> adminPlanets() {
        String middleEarth = getConfig().getString("middle-earth-world", "Middle_earth");
        String mainWorld = Bukkit.getWorlds().isEmpty() ? "" : Bukkit.getWorlds().get(0).getName();

        List<AdminPlanet> planets = new ArrayList<>();
        List<MyPlanetData> owned = new ArrayList<>(myPlanetManager.allPlanets());
        owned.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        for (MyPlanetData data : owned) {
            String name = data.worldName();
            planets.add(new AdminPlanet(name, data.displayName(), data,
                    isLockedWorld(name), isProtectedWorldName(name, middleEarth, mainWorld),
                    landingModeOf(name), structuresEnabled(name), PlanetTerrain.load(getConfig(), name)));
        }
        for (Planet planet : availablePlanets()) {
            String name = planet.worldName();
            if (myPlanetManager.get(name) != null) {
                continue; // already listed above
            }
            planets.add(new AdminPlanet(name, planet.name(), null,
                    isLockedWorld(name), isProtectedWorldName(name, middleEarth, mainWorld),
                    landingModeOf(name), structuresEnabled(name), PlanetTerrain.load(getConfig(), name)));
        }
        return planets;
    }

    /** The admin-panel entry of one world, or null when it isn't a planet. */
    AdminPlanet adminPlanet(String worldName) {
        if (worldName == null) {
            return null;
        }
        for (AdminPlanet planet : adminPlanets()) {
            if (planet.worldName().equalsIgnoreCase(worldName)) {
                return planet;
            }
        }
        return null;
    }

    /** Worlds that must never be deleted or regenerated (the hub/Middle Earth). */
    private boolean isProtectedWorldName(String worldName, String middleEarth, String mainWorld) {
        return worldName.equalsIgnoreCase(middleEarth) || worldName.equalsIgnoreCase(mainWorld);
    }

    private boolean isLockedWorld(String worldName) {
        return lockedWorlds().contains(worldName.toLowerCase(Locale.ROOT));
    }

    /** Opens the admin panel: the server-wide dashboard, which links to the planet list. */
    void openAdminMenu(Player player) {
        if (!canUseAdmin(player)) {
            player.sendMessage(Component.text("You don't have permission to use the planet admin panel.")
                    .color(NamedTextColor.RED));
            return;
        }
        openAdminDashboard(player);
    }

    /** Opens the full admin help page (also "/planets admin help"). */
    void openAdminHelp(Player player) {
        openAdminHelp(player, null);
    }

    /** Opens the admin help page, optionally filtered by a search keyword. */
    void openAdminHelp(Player player, String query) {
        openHelp(player, query, true);
    }

    /** Opens the help page every player gets (staff actions included for staff). */
    void openHelp(Player player) {
        openHelp(player, null, false);
    }

    /**
     * Opens a help page. With {@code adminView} it is the op-only admin page;
     * without it every player sees the player-facing commands and panels, plus
     * the staff actions if they can actually run them.
     */
    void openHelp(Player player, String query, boolean adminView) {
        if (adminView && !canUseAdmin(player)) {
            player.sendMessage(Component.text("You don't have permission to use the planet admin panel.")
                    .color(NamedTextColor.RED));
            return;
        }
        String trimmed = query == null || query.isBlank() ? null : query;
        List<AdminHelpMenu.Entry> entries = trimmed == null
                ? helpEntriesFor(player, adminView)
                : matchingHelpEntries(trimmed, player, adminView);
        player.closeInventory();
        new AdminHelpMenu(this, player, entries, trimmed, adminView).open(player);
    }

    /**
     * Every entry a viewer should see: the player-facing list always, then the
     * staff actions when they are on the admin page or can use the panel. The
     * player-facing commands are filtered out of the staff block so nothing is
     * listed twice.
     */
    List<AdminHelpMenu.Entry> helpEntriesFor(Player viewer, boolean adminView) {
        List<AdminHelpMenu.Entry> entries = new ArrayList<>(playerHelpEntries());
        if (!adminView && !canUseAdmin(viewer)) {
            return entries;
        }
        for (AdminHelpMenu.Entry entry : adminHelpEntries()) {
            if (!PLAYER_PERMISSIONS.contains(entry.permission())) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** Permissions an ordinary player is expected to have. */
    private static final Set<String> PLAYER_PERMISSIONS = Set.of(
            "planets.use", "planets.homes", "planets.settings", "planets.bal",
            "planets.leaderboards", "planets.buy");

    /**
     * The help entries matching a search keyword, for one viewer. Every
     * whitespace-separated word must appear somewhere in the entry (usage,
     * description, category, permission or its aliases), so "delete planet"
     * narrows things down while "lock" stays broad.
     */
    List<AdminHelpMenu.Entry> matchingHelpEntries(String query, Player viewer, boolean adminView) {
        List<String> words = new ArrayList<>();
        for (String word : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!word.isBlank()) {
                words.add(word);
            }
        }
        List<AdminHelpMenu.Entry> matches = new ArrayList<>();
        for (AdminHelpMenu.Entry entry : helpEntriesFor(viewer, adminView)) {
            String haystack = entry.haystack();
            boolean all = true;
            for (String word : words) {
                if (!haystack.contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                matches.add(entry);
            }
        }
        return matches;
    }

    /**
     * Everything a player can do, grouped by what they are trying to achieve:
     * find a planet, own and build one, keep a home, talk to people, look at the
     * money. Complements {@link #adminHelpEntries()} rather than repeating it.
     */
    List<AdminHelpMenu.Entry> playerHelpEntries() {
        List<AdminHelpMenu.Entry> entries = new ArrayList<>();

        // ── Start here ──────────────────────────────────────────────────
        help(entries, "Start", Material.GRASS_BLOCK, "/planets  |  /p",
                "opens the planet list — click a planet to travel to it", "planets.use");
        help(entries, "Start", Material.CHEST, "/lobby  |  /hub  |  /l",
                "opens the LOBBIES menu — click a lobby to enter it", "planets.use");
        help(entries, "Start", Material.BEACON, "/myp",
                "your planet's control panel, or the buy-a-planet screen when you own none",
                "planets.use");
        help(entries, "Start", Material.KNOWLEDGE_BOOK, "/planets help",
                "this page: every command and panel in the plugin, searchable", "planets.use");

        // ── Getting a planet ────────────────────────────────────────────
        help(entries, "Planets", Material.DIAMOND, "/planets buy",
                "buys a brand-new planet with VPL (the menu's Buy a Planet button)",
                "planets.buy");
        help(entries, "Planets", Material.EMERALD_BLOCK, "Menu: Planets for Sale",
                "planets other players put up for sale — buying one takes over the world",
                "planets.buy");
        help(entries, "Planets", Material.ENDER_EYE, "Menu: Surprise Me",
                "jumps you to a random planet you're allowed to visit", "planets.use");
        help(entries, "Planets", Material.SCAFFOLDING, "Planet borders",
                "you can't build past a planet's border; the owner grows it with Planet Size",
                "planets.use");
        help(entries, "Planets", Material.OAK_FENCE, "Planet types",
                "new planets get a generation (terrain, sky, effects and weather); normal planets stay vanilla",
                "planets.use");

        // ── Owning and running one ──────────────────────────────────────
        help(entries, "Your planet", Material.DIRT_PATH, "/myp claim",
                "claims an unowned public planet as your own", "planets.use");
        help(entries, "Your planet", Material.NAME_TAG, "/myp rename <name>",
                "renames your planet — the first rename is free, later ones cost VPL",
                "planets.use");
        help(entries, "Your planet", Material.GOLD_INGOT, "/myp sell <price>",
                "puts your planet up for sale; the buyer's VPL goes straight to you",
                "planets.use");
        help(entries, "Your planet", Material.PAPER, "/myp invite <player>",
                "invites someone (online or offline); they get clickable Accept / Deny buttons",
                "planets.use");
        help(entries, "Your planet", Material.BARRIER, "/myp uninvite <player>",
                "takes back an invitation that hasn't been answered yet", "planets.use");
        help(entries, "Your planet", Material.LIME_DYE, "/myp accept <planet>  |  /myp deny <planet>",
                "answers an invitation straight from chat", "planets.use");
        help(entries, "Your planet", Material.ANVIL, "Panel: Upgrades",
                "Planet Size, Member Cap, Visitor Cap, TP Cooldown and Block Limitations, priced in VPL",
                "planets.use");
        help(entries, "Your planet", Material.COMPARATOR, "Panel: Settings",
                "PvP, building, mob spawning, fire, doors/chests, item dropping, weather and visitor access",
                "planets.use");
        help(entries, "Your planet", Material.PLAYER_HEAD, "Panel: Members",
                "promote, demote (owner, co-owner, moderator, member) or remove members",
                "planets.use");
        help(entries, "Your planet", Material.ARMOR_STAND, "Panel: Visitors",
                "who is on your planet right now, with a kick button", "planets.use");
        help(entries, "Your planet", Material.WRITABLE_BOOK, "Panel: Invitations",
                "pending invitations: accept, deny, revoke, or invite from a list of online players",
                "planets.use");
        help(entries, "Your planet", Material.BRICKS, "Panel: Block Leaderboard",
                "who has placed the most blocks on your planet", "planets.use");
        help(entries, "Your planet", Material.TNT, "Panel: Abandon",
                "gives the planet up for good (asks twice — there is no undo)", "planets.use");
        help(entries, "Your planet", Material.CLOCK, "/myp resetblockcounts",
                "clears the planet-wide block counter back to zero", "planets.use");
        help(entries, "Your planet", Material.BARRIER, "Block limits",
                "every planet has a planet-wide block limit; Block Limitations upgrades raise it",
                "planets.use");

        // ── Homes ──────────────────────────────────────────────────────
        help(entries, "Homes", Material.LIGHT_BLUE_BED, "/home",
                "opens your homes menu: click a bed to teleport there", "planets.homes");
        help(entries, "Homes", Material.RED_BED, "/home <name>",
                "teleports straight to that home after a 3-second countdown", "planets.homes");
        help(entries, "Homes", Material.WHITE_BED, "/sethome <name>",
                "saves where you stand as a home — everyone has four slots", "planets.homes");
        help(entries, "Homes", Material.SHEARS, "/delhome <name>",
                "deletes one of your homes", "planets.homes");
        help(entries, "Homes", Material.PAINTING, "Home bed colours",
                "shift-right-click a home in /home to pick its bed colour — free, and saved",
                "planets.homes");

        // ── Talking to people ───────────────────────────────────────────
        help(entries, "Social", Material.REDSTONE_TORCH, "/settings",
                "your personal toggles: chat, msg + ping, join/leave lines, death messages, tp requests, "
                        + "mentions, invite alerts, anti chat spam, balance notices, teleport messages, "
                        + "Planet HUD, effect summary, colored sky, atmosphere, mob spawning, night vision",
                "planets.settings");
        help(entries, "Social", Material.SPYGLASS, "Planet HUD",
                "an action bar on planets and lobbies; right-click its /settings item to cycle what it shows",
                "planets.settings");
        help(entries, "Social", Material.NAME_TAG, "/msg <player> <message>  |  /r <message>",
                "private messages — each player can switch them off", "planets.settings");
        help(entries, "Social", Material.ENDER_PEARL,
                "/tpa <player>  |  /tpahere <player>  |  /tpaccept  |  /tpdeny",
                "teleport requests with clickable accept / deny buttons", "planets.settings");

        // ── Money and ranking ──────────────────────────────────────────
        help(entries, "Money", Material.SUNFLOWER, "/bal <player>",
                "replies with that player's VPL balance", "planets.bal");
        help(entries, "Money", Material.GOLDEN_APPLE, "/leaderboards  |  /lb  |  /lead",
                "richest players first; the anvil sorts by VPL, NEB or days played",
                "planets.leaderboards");
        return entries;
    }

    // ── Help search input ────────────────────────────────────────────────

    /** A virtual sign the player is typing their search keyword into. */
    private record HelpSign(String world, int x, int y, int z, boolean adminView) {
    }

    /** Players currently typing a search keyword into a virtual sign. */
    private final Map<UUID, HelpSign> helpSigns = new HashMap<>();

    /**
     * Players typing their keyword in chat instead (server has no virtual sign),
     * mapped to whether the admin help page should reopen (false = the all page).
     */
    private final Map<UUID, Boolean> helpChatSearch = new HashMap<>();

    /**
     * Asks for a search keyword. On Paper this opens a sign editor at the
     * player's own position (a <b>virtual</b> sign — no block is ever placed or
     * changed); on a server without that API the player is asked to type the
     * keyword in chat instead. Either way the help page reopens filtered.
     */
    void openHelpSearch(Player player, boolean adminView) {
        if (adminView && !canUseAdmin(player)) {
            return;
        }
        player.closeInventory();
        Location at = player.getLocation();
        boolean sign = openVirtualSign(player, at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (!sign) {
            helpChatSearch.put(player.getUniqueId(), adminView);
            player.sendMessage(Component.text("\u2692 Type a keyword in chat (or ").color(NamedTextColor.AQUA)
                    .append(Component.text("cancel").color(NamedTextColor.YELLOW))
                    .append(Component.text(") to search the help page.").color(NamedTextColor.AQUA)));
            return;
        }
        helpSigns.put(player.getUniqueId(), new HelpSign(at.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ(), adminView));
        player.sendMessage(Component.text("\u2692 Write a keyword on the sign ").color(NamedTextColor.AQUA)
                .append(Component.text("(first line)").color(NamedTextColor.GRAY))
                .append(Component.text(" and press Done — try ").color(NamedTextColor.AQUA))
                .append(Component.text("lock, terrain, structures, delete, weather, tp, price")
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.AQUA)));
    }

    /**
     * Opens a virtual sign editor (Paper). Reflection keeps the plugin loadable
     * on servers whose API predates it, where the chat fallback takes over.
     */
    private static boolean openVirtualSign(Player player, int x, int y, int z) {
        try {
            Class<?> positionClass = Class.forName("io.papermc.paper.math.Position");
            Object position = positionClass.getMethod("block", int.class, int.class, int.class)
                    .invoke(null, x, y, z);
            Class<?> sideClass = Class.forName("org.bukkit.block.sign.Side");
            Object front = null;
            for (Object constant : sideClass.getEnumConstants()) {
                if (constant.toString().equalsIgnoreCase("FRONT")) {
                    front = constant;
                }
            }
            if (front == null) {
                return false;
            }
            Player.class.getMethod("openVirtualSign", positionClass, sideClass)
                    .invoke(player, position, front);
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    /**
     * Handles the sign an admin typed a search keyword into. Anything typed on
     * a real sign elsewhere is left completely alone: only the exact virtual
     * position recorded for that player counts.
     */
    private void handleHelpSign(SignChangeEvent event) {
        Player player = event.getPlayer();
        HelpSign expected = helpSigns.get(player.getUniqueId());
        if (expected == null) {
            return;
        }
        String world = event.getBlock().getWorld().getName();
        if (!world.equalsIgnoreCase(expected.world())
                || event.getBlock().getX() != expected.x()
                || event.getBlock().getY() != expected.y()
                || event.getBlock().getZ() != expected.z()) {
            return; // someone editing a real sign — not our business
        }
        helpSigns.remove(player.getUniqueId());
        event.setCancelled(true); // a virtual sign must never reach the world

        String query = null;
        for (int line = 0; line < 4 && query == null; line++) {
            String text;
            try {
                text = event.getLine(line);
            } catch (IndexOutOfBoundsException ex) {
                break;
            }
            if (text != null && !text.isBlank()) {
                query = text.trim();
            }
        }
        if (query == null) {
            openHelp(player, null, expected.adminView()); // empty sign = the full page
            return;
        }
        showHelpSearch(player, query, expected.adminView());
    }

    /** Reopens the help page filtered by the keyword, telling the player how it went. */
    void showHelpSearch(Player player, String query, boolean adminView) {
        if (adminView && !canUseAdmin(player)) {
            return;
        }
        int matches = matchingHelpEntries(query, player, adminView).size();
        player.sendMessage(Component.text("\u2692 Help search: ").color(NamedTextColor.AQUA)
                .append(Component.text("'" + query + "'").color(NamedTextColor.YELLOW))
                .append(Component.text(matches == 0
                                ? " — nothing matched. Try a broader word, like 'planet' or 'world'."
                                : " — " + matches + " result(s).")
                        .color(matches == 0 ? NamedTextColor.RED : NamedTextColor.GREEN)));
        openHelp(player, query, adminView);
    }

    /**
     * Every admin capability, as help entries: the panel actions first (they
     * need no typing), then the commands that do, grouped by area so an admin
     * can find them by intent rather than by command name.
     */
    List<AdminHelpMenu.Entry> adminHelpEntries() {
        List<AdminHelpMenu.Entry> entries = new ArrayList<>();

        // ── Panel ───────────────────────────────────────────────────────
        help(entries, "Panel", Material.COMMAND_BLOCK, "/planets admin",
                "opens the dashboard, planet list and per-planet action panel", "planets.admin");
        help(entries, "Panel", Material.SPYGLASS, "/planets hud",
                "the in-game Planet HUD editor: add, rename, reorder and preview the action-bar lines",
                "planets.admin");
        help(entries, "Panel", Material.COMPARATOR, "Dashboard: Browse Planets",
                "every planet as a list — click one for its actions", "planets.admin");
        help(entries, "Panel", Material.ENDER_PEARL, "Panel: Enter Planet",
                "teleports you to the planet's spawn, loading the world if needed", "planets.admin");
        help(entries, "Panel", Material.STRUCTURE_BLOCK, "Panel: Regenerate",
                "rebuilds the world from scratch with its own terrain (2 clicks)", "planets.admin");
        help(entries, "Panel", Material.IRON_DOOR, "Panel: Lock / Unlock",
                "immediately locks or opens a planet, evicting players who may not visit", "planets.admin");
        help(entries, "Panel", Material.LODESTONE, "Panel: Landing Mode",
                "cycles where players land: station, random or a fixed point", "planets.admin");
        help(entries, "Panel", Material.ITEM_FRAME, "Panel: Menu Icon",
                "cycles the block shown for the planet in the menus", "planets.admin");
        help(entries, "Panel", Material.GRASS_BLOCK, "Panel: Terrain",
                "picks a generation and re-lays the planet's ground", "planets.admin");
        help(entries, "Panel", Material.OAK_SAPLING, "Panel: Structures",
                "turns structure generation on/off for that planet", "planets.admin");
        help(entries, "Panel", Material.WATER_BUCKET, "Panel: Weather Lock",
                "cycles clear, rain, thunder or normal weather", "planets.admin");
        help(entries, "Panel", Material.COMPASS, "Panel: Set Spawn Here",
                "pins the world spawn to where you stand inside that world", "planets.admin");
        help(entries, "Panel", Material.SCAFFOLDING, "Panel: Reset Border",
                "puts the border back to the planet's size level", "planets.admin");
        help(entries, "Panel", Material.TNT, "Panel: Delete Planet",
                "removes world, folder, ownership; the name is retired (2 clicks)", "planets.admin");
        help(entries, "Panel", Material.SPYGLASS, "Dashboard: Clean Up Server",
                "deletes leftover preview worlds and orphaned world folders", "planets.admin");
        help(entries, "Panel", Material.WRITABLE_BOOK, "Panel: Command Reference",
                "prints the typed commands in chat", "planets.admin");

        // ── Planets ─────────────────────────────────────────────────────
        help(entries, "Planets", Material.BEACON, "/planets create <name> [type] [generation]",
                "builds a new public planet; generations: " + String.join(", ", generationIds()),
                "planets.create");
        help(entries, "Planets", Material.SPYGLASS, "/planets preview <generation|end>",
                "walks you through a throwaway world, then discards it", "planets.create");
        help(entries, "Planets", Material.TNT, "/planets delete <planet>",
                "deletes with a chat confirmation (world, folder, ownership)", "planets.delete");
        help(entries, "Planets", Material.IRON_DOOR, "/planets lock <planet> | lock cancel <planet>",
                "locks a planet after a warning countdown, or cancels it", "planets.lock");
        help(entries, "Planets", Material.ITEM_FRAME, "/planets icon <planet> <material|reset>",
                "sets the exact menu icon, or resets it to the default", "planets.icon");
        help(entries, "Planets", Material.LODESTONE, "/planets landing <planet> station|random|point",
                "where players land; /planets setlanding <planet> pins the point", "planets.landing");

        // ── World properties ────────────────────────────────────────────
        help(entries, "World", Material.PAPER, "/planets world <planet> status",
                "every world property at a glance, including terrain and structures", "planets.world");
        help(entries, "World", Material.GRASS_BLOCK, "/planets world <planet> terrain <generation|off>",
                "re-terrains the world with a generation, or stops tracking it", "planets.world");
        help(entries, "World", Material.OAK_SAPLING, "/planets world <planet> structures on|off",
                "toggles structure generation for that world", "planets.world");
        help(entries, "World", Material.CLOCK, "/planets world <planet> time|day-cycle|weather-cycle ...",
                "time of day and the daylight/weather cycles", "planets.world");
        help(entries, "World", Material.SUNFLOWER, "/planets world <planet> sky|particles|effect|atmosphere ...",
                "sky and fog colors, ambient particles, potion effects, hostile air", "planets.world");
        help(entries, "World", Material.SPAWNER, "/planets world <planet> mobs|monsters|animals|ambient|water ...",
                "spawn toggles and per-type spawn limits", "planets.world");
        help(entries, "World", Material.DIAMOND_SWORD, "/planets world <planet> pvp|difficulty ...",
                "PvP toggle and world difficulty", "planets.world");
        help(entries, "World", Material.SCAFFOLDING, "/planets world <planet> border <size> | spawn <x> <y> <z>",
                "world border size and spawn point", "planets.world");
        help(entries, "World", Material.COMMAND_BLOCK, "/planets world <planet> gamerule|tick-speed ...",
                "game rules and random-tick speed", "planets.world");
        help(entries, "World", Material.NETHER_PORTAL, "/planets world <planet> dimensions on|off",
                "disables a planet's linked Nether/End dimensions", "planets.world");
        help(entries, "World", Material.END_PORTAL_FRAME, "/planets portals on|off",
                "disables all Nether/End portals server-wide", "planets.world");
        help(entries, "World", Material.STRUCTURE_BLOCK, "/planets world <planet> regen <random|same>",
                "regenerates a world through Multiverse", "planets.world");

        // ── Economy, config, moderation ─────────────────────────────────
        help(entries, "Server", Material.GOLD_INGOT, "/planets buy <amount> | renameprice <prices...>",
                "price of a new planet, and the rename price ladder", "planets.world");
        help(entries, "Server", Material.BOOK, "/planets reload",
                "reloads config.yml (settings, generations, per-world overrides)", "planets.reload");
        help(entries, "Server", Material.SCAFFOLDING, "/planets borders [list|set <blocks|NxN>|restore]",
                "shows, sets or restores public planet borders (a reload never shrinks them)",
                "planets.world");
        help(entries, "Server", Material.SLIME_BALL, "/sus <player> | /sl | /sus remove <player> | /sus stop",
                "flags players, opens the SUS list, spectates and teleports to them", "planets.sus");
        help(entries, "Server", Material.PAPER, "/bal <player>",
                "replies in chat with that player's VPL balance", "planets.bal");
        help(entries, "Server", Material.PLAYER_HEAD, "/leaderboards | /lb | /lead",
                "richest-to-poorest players, with an anvil to sort by NEB or playtime",
                "planets.leaderboards");
        help(entries, "Server", Material.LODESTONE, "/lobby list",
                "lists every lobby and its landing spot (create/icon/setlanding/delete are op-only)",
                "planets.use");
        help(entries, "Server", Material.LIGHT_BLUE_BED, "/home [name]",
                "a player's saved homes: click a home to teleport, rename or delete it",
                "planets.homes");
        help(entries, "Panel", Material.PLAYER_HEAD, "Panel: Homes",
                "every player with homes: teleport to any home or delete one, or all of them",
                "planets.admin");
        help(entries, "Server", Material.COMPARATOR, "/settings",
                "a player's personal toggles: chat, msg + ping, join/leave lines, death messages, tp requests, "
                        + "mentions, invite alerts, anti chat spam, balance notices, teleport messages, "
                        + "Planet HUD, effect summary, colored sky, atmosphere, mob spawning, night vision",
                "planets.settings");
        help(entries, "Server", Material.NAME_TAG, "/msg <player> <message> | /r <message>",
                "private messages, honouring each player's msg setting", "planets.settings");
        help(entries, "Server", Material.ENDER_PEARL, "/tpa <player> | /tpahere <player> | /tpaccept | /tpdeny",
                "teleport requests, honouring each player's tp-requests setting", "planets.settings");

        // ── Lobbies in the admin panel ────────────────────────────────
        help(entries, "Lobby", Material.OAK_BOAT, "/planets admin → Lobby Admin",
                "every lobby in one panel: enter, icon, landing, weather, terrain, structures, spawn, delete",
                "planets.admin");
        help(entries, "Lobby", Material.ENDER_PEARL, "/lobby <name>",
                "enters a lobby (the panel's Enter Lobby action)", "planets.use");
        help(entries, "Lobby", Material.LODESTONE, "/lobby setlanding <name>",
                "pins your position as a lobby's landing (panel: Set Landing Here / Clear Landing)",
                "planets.landing");
        help(entries, "Lobby", Material.ITEM_FRAME, "/lobby icon <name> <material|reset>",
                "changes a lobby's menu block (panel: Menu Icon)", "planets.icon");
        help(entries, "Lobby", Material.STRUCTURE_BLOCK, "/lobby delete <name>",
                "deletes a lobby and its world (panel: Delete Lobby)", "planets.delete");
        help(entries, "Lobby", Material.WRITABLE_BOOK, "/lobby create <name> [material]",
                "creates a new lobby world, hidden from /planets", "planets.create");
        help(entries, "Lobby", Material.KNOWLEDGE_BOOK, "/lobby editor [lobby]",
                "the lobby panel: rename, menu slot, description, icon, landing, weather, terrain, structures, delete",
                "planets.admin");
        help(entries, "Lobby", Material.NAME_TAG, "/lobby rename <lobby> <new name>",
                "renames a lobby without changing its id or world", "planets.admin");
        help(entries, "Lobby", Material.ITEM_FRAME, "/lobby slot <lobby> <0-25>",
                "moves a lobby's slot in the LOBBIES menu", "planets.admin");
        help(entries, "Lobby", Material.PAPER, "/lobby desc <lobby> <text|none>",
                "sets the description shown under the lobby's name", "planets.admin");
        return entries;
    }

    /**
     * Extra search words per theme, so an admin finds an action by the word
     * they have in mind ("regen", "tp", "village") rather than by the exact
     * command name. A theme matches when its key appears in the entry's text.
     */
    private static final Map<String, String> HELP_ALIASES = Map.ofEntries(
            Map.entry("lock", "unlock private visit access"),
            Map.entry("regenerate", "regen rebuild reset wipe"),
            Map.entry("enter planet", "tp teleport go visit travel"),
            Map.entry("landing", "arrival arrivalpoint where players land"),
            Map.entry("terrain", "generation ground biome type surface"),
            Map.entry("structures", "village ruin tree dungeon generation"),
            Map.entry("weather", "rain thunder storm clear sky storm-lock"),
            Map.entry("border", "size grow chunks level expand"),
            Map.entry("delete", "remove purge wipe destroy"),
            Map.entry("clean up", "cleanup leftovers orphan previews empty tidy junk"),
            Map.entry("create", "new make add generation type build"),
            Map.entry("preview", "test try look sample"),
            Map.entry("icon", "block menu picture image"),
            Map.entry("dimensions", "nether end dimension"),
            Map.entry("portals", "nether end portal"),
            Map.entry("gamerule", "rule tick speed time"),
            Map.entry("mobs", "spawn monsters animals limits"),
            Map.entry("pvp", "combat fight difficulty"),
            Map.entry("sus", "flag suspicious spectate watch stalk"),
            Map.entry("leaderboards", "leaderboard richest top ranking networth"),
            Map.entry("bal", "balance money vpl"),
            Map.entry("reload", "config refresh"),
            Map.entry("lobby", "hub"),
            Map.entry("renameprice", "price cost money rename"),
            Map.entry("spawn", "respawn point death"),
            Map.entry("effect", "potion gravity"),
            Map.entry("sky", "fog color atmosphere tint"),
            Map.entry("particles", "ambient effects dust"),
            Map.entry("dashboard", "overview stats counts health summary"),
            Map.entry("command reference", "commands list syntax"),
            Map.entry("time", "day night clock"),
            Map.entry("resource", "blocks placed usage"),
            Map.entry("retired", "deleted abandoned can never be bought")
    );

    /** One help entry, with the category it belongs to and its search aliases. */
    private static void help(List<AdminHelpMenu.Entry> entries, String category, Material icon,
                             String usage, String description, String permission) {
        String text = (usage + " " + description + " " + category).toLowerCase(Locale.ROOT);
        StringBuilder keywords = new StringBuilder();
        for (Map.Entry<String, String> alias : HELP_ALIASES.entrySet()) {
            if (text.contains(alias.getKey())) {
                keywords.append(alias.getValue()).append(' ');
            }
        }
        entries.add(new AdminHelpMenu.Entry(category, icon, usage, description, permission,
                keywords.toString()));
    }

    /** Opens the server-wide overview (counts, world health, cleanup). */
    void openAdminDashboard(Player player) {
        if (!canUseAdmin(player)) {
            return;
        }
        player.closeInventory();
        new AdminOverviewMenu(this, player, serverStats()).open(player);
    }

    /** Opens the list of every planet, from the dashboard or a planet's back button. */
    void openAdminPlanetList(Player player) {
        if (!canUseAdmin(player)) {
            return;
        }
        List<AdminPlanet> planets = adminPlanets();
        if (planets.isEmpty()) {
            player.sendMessage(Component.text("There are no planets to manage yet.").color(NamedTextColor.GRAY));
            return;
        }
        player.closeInventory();
        new AdminPlanetsMenu(this, player, planets).open(player);
    }

    // ── Server-wide health: counts and cleanup ───────────────────────────

    /** A snapshot of the whole server's planet health, for the admin dashboard. */
    record ServerStats(int owned, int publicPlanets, int loadedWorlds, int unloadedWorlds,
                       int locked, int retired, int previews, int totalBlocks,
                       String topPlanet, int topBlocks, List<String> leftovers,
                       List<String> emptyWorlds) {

        /** Planets an admin can open (owned + public). */
        int planets() {
            return owned + publicPlanets;
        }

        /** How many worlds the cleanup would remove. */
        int cleanable() {
            return leftovers.size() + previews;
        }
    }

    /**
     * Collects the dashboard numbers. Everything is read live from the world
     * container, the ownership records and the plugin's own config, so the
     * dashboard can be re-opened at any time to see the current state.
     */
    ServerStats serverStats() {
        List<MyPlanetData> ownedPlanets = new ArrayList<>(myPlanetManager.allPlanets());
        int loaded = 0;
        int unloaded = 0;
        for (AdminPlanet planet : adminPlanets()) {
            if (Bukkit.getWorld(planet.worldName()) != null) {
                loaded++;
            } else {
                unloaded++;
            }
        }

        int totalBlocks = 0;
        String topPlanet = null;
        int topBlocks = 0;
        for (MyPlanetData data : ownedPlanets) {
            int placed = data.totalBlocksPlaced();
            totalBlocks += placed;
            if (placed > topBlocks) {
                topBlocks = placed;
                topPlanet = data.worldName();
            }
        }

        // Worlds that are loaded but belong to nobody are not leftovers; worlds
        // with no owners at all are (and are what the cleanup removes).
        List<String> leftovers = leftoverWorlds();
        List<String> empty = new ArrayList<>();
        for (String worldName : leftovers) {
            World world = Bukkit.getWorld(worldName);
            if (world == null || world.getPlayers().isEmpty()) {
                empty.add(worldName);
            }
        }

        return new ServerStats(ownedPlanets.size(), availablePlanets().size(), loaded, unloaded,
                lockedWorlds().size(), myPlanetManager.retiredPlanets().size(), livePreviewWorlds().size(),
                totalBlocks, topPlanet, topBlocks, leftovers, empty);
    }

    /** Preview worlds that actually still exist (loaded or on disk). */
    List<String> livePreviewWorlds() {
        List<String> live = new ArrayList<>();
        for (String name : previewWorldNames()) {
            if (Bukkit.getWorld(name) != null || worldFolderExists(name)) {
                live.add(name);
            }
        }
        return live;
    }

    /**
     * World folders that belong to nothing: not loaded, not a planet, not a
     * lobby, not a linked Nether/End dimension of a known world and not a
     * preview. These are the leftovers of worlds that were removed by hand,
     * half-deleted, or restored from a backup.
     */
    List<String> leftoverWorlds() {
        Set<String> known = new HashSet<>();
        known.add(getConfig().getString("middle-earth-world", "Middle_earth").toLowerCase(Locale.ROOT));
        for (World world : Bukkit.getWorlds()) {
            known.add(world.getName().toLowerCase(Locale.ROOT));
        }
        for (Planet planet : availablePlanets()) {
            known.add(planet.worldName().toLowerCase(Locale.ROOT));
        }
        for (MyPlanetData data : myPlanetManager.allPlanets()) {
            known.add(data.worldName().toLowerCase(Locale.ROOT));
        }
        for (Lobby lobby : availableLobbies()) {
            known.add(lobby.worldName().toLowerCase(Locale.ROOT));
        }
        for (String preview : livePreviewWorlds()) {
            known.add(preview.toLowerCase(Locale.ROOT)); // cleaned up separately
        }

        List<String> leftovers = new ArrayList<>();
        File[] children = Bukkit.getWorldContainer().listFiles();
        if (children == null) {
            return leftovers;
        }
        for (File child : children) {
            if (!child.isDirectory() || !new File(child, "level.dat").isFile()) {
                continue; // not a world folder at all
            }
            String name = child.getName();
            String key = name.toLowerCase(Locale.ROOT);
            if (known.contains(key)) {
                continue;
            }
            // A linked dimension folder ("world_nether") belongs to its planet.
            String base = key.endsWith("_nether") ? key.substring(0, key.length() - "_nether".length())
                    : key.endsWith("_the_end") ? key.substring(0, key.length() - "_the_end".length())
                    : key.endsWith("_end") ? key.substring(0, key.length() - "_end".length())
                    : null;
            if (base != null && known.contains(base)) {
                continue;
            }
            leftovers.add(name);
        }
        leftovers.sort(String.CASE_INSENSITIVE_ORDER);
        return leftovers;
    }

    /**
     * The admin cleanup: discards every leftover preview world and every world
     * folder that belongs to nothing. Loaded worlds are never touched (they're
     * not leftovers), players inside are moved to the hub first, and anything
     * removed is logged with who asked for it.
     *
     * @return how many worlds were removed.
     */
    int cleanUpWorlds(Player actor) {
        int removed = 0;
        for (String name : livePreviewWorlds()) {
            clearWorldConfig(name);
            if (deleteWorldNow(name)) {
                removed++;
            }
        }
        getConfig().set("preview-worlds", null);
        for (String name : leftoverWorlds()) {
            if (deleteWorldNow(name)) {
                removed++;
                getLogger().info("Cleanup: removed leftover world folder '" + name + "'.");
            }
        }
        saveConfigQuietly();
        // Drop any config left behind by the removed worlds from the live caches.
        PlanetTravel.loadConfig(getConfig());
        PlanetEffects.loadConfig(getConfig());
        environment.loadConfig(getConfig());
        getLogger().info(actor.getName() + " ran the planet cleanup: " + removed + " world(s) removed.");
        return removed;
    }

    /** Opens the per-planet action panel (re-renders in place when already open). */
    void openAdminPlanet(Player player, String worldName) {
        if (!canUseAdmin(player)) {
            return;
        }
        if (adminPlanet(worldName) != null) {
            player.closeInventory();
            new AdminPlanetPanelMenu(this, player, worldName).open(player);
            return;
        }
        // Not a planet — fall back to the lobby panel so the terrain picker's
        // "back" button lands somewhere sensible for lobby worlds too.
        Lobby lobby = findLobby(worldName);
        if (lobby != null) {
            openAdminLobby(player, lobby.id());
        }
    }

    /** Opens the generation picker for one planet or lobby world. */
    void openAdminTerrainPicker(Player player, String worldName) {
        if (!canUseAdmin(player)) {
            return;
        }
        if (adminPlanet(worldName) == null && findLobby(worldName) == null) {
            return;
        }
        player.closeInventory();
        new AdminTerrainMenu(this, player, worldName, generations()).open(player);
    }

    // ── Admin control panel: lobbies ─────────────────────────────────────

    /** Every configured lobby, sorted by name, for the admin lobby list. */
    List<Lobby> adminLobbies() {
        List<Lobby> lobbies = new ArrayList<>(availableLobbies());
        lobbies.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return lobbies;
    }

    /** A lobby by id, display name or world name, or null. */
    Lobby adminLobby(String query) {
        return findLobby(query);
    }

    /** Opens the admin lobby list (Dashboard → Lobby Admin, or a planet panel). */
    void openAdminLobbies(Player player) {
        if (!canUseAdmin(player)) {
            return;
        }
        List<Lobby> lobbies = adminLobbies();
        player.closeInventory();
        if (lobbies.isEmpty()) {
            player.sendMessage(Component.text("No lobbies configured yet — create one with ")
                    .color(NamedTextColor.YELLOW)
                    .append(Component.text("/lobby create <name> [material]")
                            .color(NamedTextColor.AQUA))
                    .append(Component.text(".").color(NamedTextColor.YELLOW)));
            return;
        }
        new AdminLobbiesMenu(this, player, lobbies).open(player);
    }

    /** Opens one lobby's admin panel. */
    void openAdminLobby(Player player, String lobbyId) {
        if (!canUseAdmin(player)) {
            return;
        }
        Lobby lobby = adminLobby(lobbyId);
        if (lobby == null) {
            player.sendMessage(Component.text("Unknown lobby '").color(NamedTextColor.RED)
                    .append(Component.text(lobbyId).color(NamedTextColor.YELLOW))
                    .append(Component.text("'. Type /lobby list.").color(NamedTextColor.RED)));
            return;
        }
        player.closeInventory();
        new AdminLobbyPanelMenu(this, player, lobby.id()).open(player);
    }

    /** Sends an admin to a lobby (loading its world first when needed). */
    void enterLobbyWorld(Player actor, Lobby lobby) {
        World world = loadWorldNow(lobby.worldName());
        if (world == null) {
            actor.sendMessage(Component.text("World '").color(NamedTextColor.RED)
                    .append(Component.text(lobby.worldName()).color(NamedTextColor.YELLOW))
                    .append(Component.text("' isn't loaded and has no folder on disk.")
                            .color(NamedTextColor.RED)));
            return;
        }
        Location spot = lobby.location();
        actor.closeInventory();
        actor.teleport(spot != null ? spot : world.getSpawnLocation());
        actor.sendMessage(Component.text("\uD83D\uDE80 Entered lobby ").color(NamedTextColor.AQUA)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" at its landing spot.").color(NamedTextColor.GRAY)));
    }

    /** Cycles a lobby's menu icon through the admin palette. */
    void cycleLobbyIcon(Player actor, Lobby lobby) {
        int index = ICON_PALETTE.indexOf(lobby.icon());
        Material next = ICON_PALETTE.get((index + 1) % ICON_PALETTE.size());
        getConfig().set("lobbies." + lobby.id() + ".icon", next.name());
        saveConfigQuietly();
        actor.sendMessage(Component.text("Menu icon of lobby ").color(NamedTextColor.GREEN)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" is now ").color(NamedTextColor.GREEN))
                .append(Component.text(next.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(". Exact block: /lobby icon " + lobby.id() + " <material>")
                        .color(NamedTextColor.GREEN)));
    }

    /** Pins the admin's current position as a lobby's landing spot. */
    void setLobbyLandingHere(Player actor, Lobby lobby) {
        Location location = actor.getLocation();
        String path = "lobbies." + lobby.id();
        getConfig().set(path + ".world", location.getWorld().getName());
        getConfig().set(path + ".x", location.getX());
        getConfig().set(path + ".y", location.getY());
        getConfig().set(path + ".z", location.getZ());
        getConfig().set(path + ".yaw", (double) location.getYaw());
        getConfig().set(path + ".pitch", (double) location.getPitch());
        saveConfigQuietly();
        actor.sendMessage(Component.text("Landing of lobby ").color(NamedTextColor.GREEN)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" pinned at (").color(NamedTextColor.GREEN))
                .append(Component.text(location.getBlockX() + ", " + location.getBlockY() + ", "
                                + location.getBlockZ()).color(NamedTextColor.YELLOW))
                .append(Component.text(") in " + location.getWorld().getName() + ".")
                        .color(NamedTextColor.GREEN)));
    }

    /** Clears a lobby's pinned landing spot (players then land at the world spawn). */
    void clearLobbyLanding(Player actor, Lobby lobby) {
        String path = "lobbies." + lobby.id();
        getConfig().set(path + ".x", null);
        getConfig().set(path + ".y", null);
        getConfig().set(path + ".z", null);
        getConfig().set(path + ".yaw", null);
        getConfig().set(path + ".pitch", null);
        saveConfigQuietly();
        actor.sendMessage(Component.text("Landing of lobby ").color(NamedTextColor.GREEN)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" cleared — players land at the world spawn.")
                        .color(NamedTextColor.GREEN)));
    }

    /**
     * Deletes a lobby: the menu entry, its per-world settings, its world folder
     * and Multiverse's memory of it. Anyone inside is moved to the hub first.
     * The name is retired so the world can't sneak back into the menus.
     */
    boolean deleteLobbyNow(Player actor, Lobby lobby) {
        getConfig().set("lobbies." + lobby.id(), null);
        saveConfigQuietly();
        clearWorldConfig(lobby.worldName());
        deleteWorldNow(lobby.worldName());
        rememberDeletedWorld(lobby.worldName());
        saveConfigQuietly();
        actor.sendMessage(Component.text("Lobby '").color(NamedTextColor.GREEN)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text("' deleted — its world was removed too.").color(NamedTextColor.GREEN)));
        getLogger().info(actor.getName() + " deleted the lobby '" + lobby.id() + "' ("
                + lobby.worldName() + ").");
        return true;
    }

    // ── Lobby editor (/lobby editor) ─────────────────────────────────────

    /** Handles "/lobby editor [lobby]": opens the lobby's admin panel. */
    private void lobbyEditor(Player player, String[] args) {
        if (!canUseAdmin(player)) {
            player.sendMessage(Component.text("You don't have permission to edit lobbies.")
                    .color(NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            openAdminLobbies(player);
            return;
        }
        String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Lobby lobby = findLobby(query);
        if (lobby == null) {
            player.sendMessage(Component.text("Unknown lobby '").color(NamedTextColor.RED)
                    .append(Component.text(query).color(NamedTextColor.YELLOW))
                    .append(Component.text("'. Type /lobby list.").color(NamedTextColor.RED)));
            return;
        }
        openAdminLobby(player, lobby.id());
    }

    /** Handles "/lobby rename <lobby> <new name>". */
    private void lobbyRename(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /lobby rename <lobby> <new name>")
                    .color(NamedTextColor.YELLOW));
            player.sendMessage(Component.text("Use the lobby's id (see /lobby list) when its name has spaces.")
                    .color(NamedTextColor.GRAY));
            return;
        }
        Lobby lobby = findLobby(args[1]);
        if (lobby == null) {
            unknownLobby(player, args[1]);
            return;
        }
        renameLobby(player, lobby, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
    }

    /** Handles "/lobby slot <lobby> <0-25>". */
    private void lobbySlot(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /lobby slot <lobby> <0-25>")
                    .color(NamedTextColor.YELLOW));
            return;
        }
        Lobby lobby = findLobby(args[1]);
        if (lobby == null) {
            unknownLobby(player, args[1]);
            return;
        }
        try {
            setLobbySlot(player, lobby, Integer.parseInt(args[2].trim()));
        } catch (NumberFormatException ex) {
            player.sendMessage(Component.text("'").color(NamedTextColor.RED)
                    .append(Component.text(args[2]).color(NamedTextColor.YELLOW))
                    .append(Component.text("' isn't a number — menu slots run from 0 to 25.")
                            .color(NamedTextColor.RED)));
        }
    }

    /** Handles "/lobby desc <lobby> <text|none>". */
    private void lobbyDescription(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Usage: /lobby desc <lobby> <text|none>")
                    .color(NamedTextColor.YELLOW));
            return;
        }
        Lobby lobby = findLobby(args[1]);
        if (lobby == null) {
            unknownLobby(player, args[1]);
            return;
        }
        setLobbyDescription(player, lobby, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
    }

    private void unknownLobby(Player player, String query) {
        player.sendMessage(Component.text("Unknown lobby '").color(NamedTextColor.RED)
                .append(Component.text(query).color(NamedTextColor.YELLOW))
                .append(Component.text("'. Type /lobby list.").color(NamedTextColor.RED)));
    }

    // ── The edits themselves ────────────────────────────────────────────

    /**
     * Renames a lobby's display name. Its id (the config key and world name)
     * never changes, so landing spots, icons and slots all survive the rename.
     */
    boolean renameLobby(Player actor, Lobby lobby, String newName) {
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty() || name.length() > 64) {
            actor.sendMessage(Component.text("Lobby names must be 1-64 characters.").color(NamedTextColor.RED));
            return false;
        }
        for (Lobby other : availableLobbies()) {
            if (!other.id().equalsIgnoreCase(lobby.id()) && other.name().equalsIgnoreCase(name)) {
                actor.sendMessage(Component.text("Another lobby is already called '").color(NamedTextColor.RED)
                        .append(Component.text(name).color(NamedTextColor.YELLOW))
                        .append(Component.text("'.").color(NamedTextColor.RED)));
                return false;
            }
        }
        String path = "lobbies." + lobby.id();
        // Writing name + world also upgrades a legacy "id: Some Name" entry into
        // a full section, so the rest of the editor works on it afterwards.
        getConfig().set(path + ".name", name);
        getConfig().set(path + ".world", lobby.worldName());
        saveConfigQuietly();
        actor.sendMessage(Component.text("✏ Lobby renamed to ").color(NamedTextColor.GREEN)
                .append(Component.text(name).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
        getLogger().info(actor.getName() + " renamed lobby '" + lobby.id() + "' to '" + name + "'.");
        return true;
    }

    /**
     * Sets or clears a lobby's description. The description shows under the
     * lobby's name in the LOBBIES menu; "none" (or "-") clears it.
     */
    boolean setLobbyDescription(Player actor, Lobby lobby, String description) {
        String text = description == null ? "" : description.trim();
        String path = "lobbies." + lobby.id();
        getConfig().set(path + ".name", lobby.name());
        getConfig().set(path + ".world", lobby.worldName());
        if (text.isEmpty() || text.equalsIgnoreCase("none") || text.equals("-")) {
            getConfig().set(path + ".description", null);
            saveConfigQuietly();
            actor.sendMessage(Component.text("📝 Description of lobby ").color(NamedTextColor.GREEN)
                    .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                    .append(Component.text(" cleared.").color(NamedTextColor.GREEN)));
            return true;
        }
        if (text.length() > 256) {
            actor.sendMessage(Component.text("Keep the description under 256 characters.")
                    .color(NamedTextColor.RED));
            return false;
        }
        getConfig().set(path + ".description", text);
        saveConfigQuietly();
        actor.sendMessage(Component.text("📝 Description of lobby ").color(NamedTextColor.GREEN)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" is now: ").color(NamedTextColor.GREEN))
                .append(Component.text(text).color(NamedTextColor.YELLOW)));
        return true;
    }

    /**
     * Moves a lobby to a slot of the LOBBIES menu (0-25; slot 26 holds the info
     * item). Two lobbies in one slot don't break anything — the menu puts the
     * later one in the first free slot — so this only warns about the clash.
     */
    boolean setLobbySlot(Player actor, Lobby lobby, int slot) {
        if (slot < 0 || slot > 25) {
            actor.sendMessage(Component.text("Menu slots run from 0 to 25 (26 is the info corner).")
                    .color(NamedTextColor.RED));
            return false;
        }
        String path = "lobbies." + lobby.id();
        getConfig().set(path + ".name", lobby.name());
        getConfig().set(path + ".world", lobby.worldName());
        getConfig().set(path + ".slot", slot);
        saveConfigQuietly();
        actor.sendMessage(Component.text("▣ Lobby ").color(NamedTextColor.GREEN)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" now sits in menu slot ").color(NamedTextColor.GREEN))
                .append(Component.text(String.valueOf(slot)).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
        Lobby taken = lobbyInSlot(slot, lobby.id());
        if (taken != null) {
            actor.sendMessage(Component.text("Note: ").color(NamedTextColor.YELLOW)
                    .append(Component.text(taken.name()).color(NamedTextColor.GOLD))
                    .append(Component.text(" was already in that slot — it will be shown in the first "
                            + "free slot instead. Give it another slot to fix that.")
                            .color(NamedTextColor.YELLOW)));
        }
        return true;
    }

    /** The other lobby occupying a menu slot, or null when the slot is free. */
    Lobby lobbyInSlot(int slot, String exceptId) {
        for (Lobby lobby : availableLobbies()) {
            if (!lobby.id().equalsIgnoreCase(exceptId) && lobby.slot() != null && lobby.slot() == slot) {
                return lobby;
            }
        }
        return null;
    }

    // ── Chat prompts for the editor ─────────────────────────────────────

    /** Asks, in chat, for a lobby's new display name. */
    void promptLobbyName(Player actor, Lobby lobby) {
        pendingLobbyEdits.put(actor.getUniqueId(), new PendingLobbyEdit(lobby.id(), LobbyEditKind.NAME));
        actor.closeInventory();
        actor.sendMessage(Component.text("✏ Type the new name for ").color(NamedTextColor.GOLD)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" in chat (1-64 characters), or ").color(NamedTextColor.GOLD))
                .append(Component.text("cancel").color(NamedTextColor.RED))
                .append(Component.text(".").color(NamedTextColor.GOLD)));
    }

    /** Asks, in chat, for a lobby's description. */
    void promptLobbyDescription(Player actor, Lobby lobby) {
        pendingLobbyEdits.put(actor.getUniqueId(), new PendingLobbyEdit(lobby.id(), LobbyEditKind.DESCRIPTION));
        actor.closeInventory();
        actor.sendMessage(Component.text("📝 Type the description for ").color(NamedTextColor.GOLD)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" in chat (up to 256 characters), ").color(NamedTextColor.GOLD))
                .append(Component.text("none").color(NamedTextColor.RED))
                .append(Component.text(" to clear it, or ").color(NamedTextColor.GOLD))
                .append(Component.text("cancel").color(NamedTextColor.RED))
                .append(Component.text(".").color(NamedTextColor.GOLD)));
        String current = lobby.description();
        actor.sendMessage(Component.text("Currently: ").color(NamedTextColor.GRAY)
                .append(Component.text(current == null || current.isBlank() ? "(none)" : current)
                        .color(NamedTextColor.YELLOW)));
    }

    /** Asks, in chat, which menu slot a lobby should sit in. */
    void promptLobbySlot(Player actor, Lobby lobby) {
        pendingLobbyEdits.put(actor.getUniqueId(), new PendingLobbyEdit(lobby.id(), LobbyEditKind.SLOT));
        actor.closeInventory();
        actor.sendMessage(Component.text("▣ Type the menu slot for ").color(NamedTextColor.GOLD)
                .append(Component.text(lobby.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(" in chat (0-25, counting from the top-left; 26 is the info "
                        + "corner), or ").color(NamedTextColor.GOLD))
                .append(Component.text("cancel").color(NamedTextColor.RED))
                .append(Component.text(".").color(NamedTextColor.GOLD)));
        actor.sendMessage(Component.text("Currently: ").color(NamedTextColor.GRAY)
                .append(Component.text(lobby.slot() == null ? "auto" : String.valueOf(lobby.slot()))
                        .color(NamedTextColor.YELLOW)));
    }

    /** Applies whatever the player answered to a lobby edit prompt. */
    private void applyLobbyEdit(Player player, PendingLobbyEdit edit, String input) {
        Lobby lobby = findLobby(edit.lobbyId());
        if (lobby == null) {
            player.sendMessage(Component.text("That lobby no longer exists.").color(NamedTextColor.RED));
            return;
        }
        if (input.isEmpty() || input.equalsIgnoreCase("cancel")) {
            player.sendMessage(Component.text("Lobby edit cancelled.").color(NamedTextColor.GRAY));
            openAdminLobby(player, lobby.id());
            return;
        }
        switch (edit.kind()) {
            case NAME -> renameLobby(player, lobby, input);
            case DESCRIPTION -> setLobbyDescription(player, lobby, input);
            case SLOT -> {
                try {
                    setLobbySlot(player, lobby, Integer.parseInt(input));
                } catch (NumberFormatException ex) {
                    player.sendMessage(Component.text("'").color(NamedTextColor.RED)
                            .append(Component.text(input).color(NamedTextColor.YELLOW))
                            .append(Component.text("' isn't a number — menu slots run from 0 to 25.")
                                    .color(NamedTextColor.RED)));
                    promptLobbySlot(player, lobby);
                    return;
                }
            }
        }
        openAdminLobby(player, lobby.id());
    }

    /** Alias used by the admin menus: whether structures may generate in a world. */
    boolean worldStructures(String worldName) {
        return structuresEnabled(worldName);
    }

    /** Friendly name of a world's locked weather, for the admin panels. */
    String weatherLockName(String worldName) {
        String locked = getConfig().getString("weather-lock." + worldName);
        return locked == null || locked.isBlank() ? "unlocked (normal weather)" : locked;
    }

    /** Every generation the server can build, as picker entries. */
    List<Generation> generations() {
        List<Generation> list = new ArrayList<>();
        for (PlanetArchetypes.Archetype archetype : PlanetArchetypes.all()) {
            list.add(new Generation(archetype.id(), archetype.displayName(), archetype.description(),
                    archetype.icon(), archetype.terrain()));
        }
        list.add(new Generation("void", "Void", "An empty world with no ground at all",
                Material.BARRIER, PlanetTerrain.voidSpec()));
        ConfigurationSection presets = getConfig().getConfigurationSection("generation-presets");
        if (presets != null) {
            for (String id : presets.getKeys(false)) {
                PlanetTerrain.Spec spec = generationSpec(id);
                if (spec == null) {
                    continue;
                }
                ConfigurationSection section = presets.getConfigurationSection(id);
                String description = section == null ? null : section.getString("description");
                list.add(new Generation(id,
                        id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1),
                        description != null ? description : "Custom generation from config.yml",
                        Material.COMMAND_BLOCK, spec));
            }
        }
        return list;
    }

    /**
     * Whether structures may generate on a planet: player-owned planets use
     * their own setting, public planets use {@code planet-structures.<world>}
     * (both default to on) and anything else falls back to the server default.
     */
    boolean structuresEnabled(String worldName) {
        MyPlanetData data = myPlanetManager.get(worldName);
        if (data != null) {
            return data.structures();
        }
        return getConfig().getBoolean("planet-structures." + worldName,
                getConfig().getBoolean("planet-generation.generate-structures", true));
    }

    /**
     * Turns structure generation on or off for a planet. The setting is honoured
     * immediately for tree/mushroom growth, and by the next regeneration or new
     * chunk of that world (Minecraft can't flip the flag of live terrain).
     */
    void setWorldStructures(Player actor, String worldName, boolean enabled) {
        MyPlanetData data = myPlanetManager.get(worldName);
        if (data != null) {
            data.structures(enabled);
            myPlanetManager.save();
        } else {
            getConfig().set("planet-structures." + worldName, enabled);
            saveConfigQuietly();
        }
        actor.sendMessage(Component.text("\uD83C\uDFD8 Structures on ").color(NamedTextColor.GREEN)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text(enabled ? " are now allowed" : " won't generate").color(NamedTextColor.GREEN))
                .append(Component.text(" — tree and mushroom growth follows this at once; newly generated "
                        + "terrain follows it from now on. Regenerate the planet to rebuild what's already "
                        + "there.").color(NamedTextColor.GRAY)));
        getLogger().info(actor.getName() + " set structures " + (enabled ? "on" : "off")
                + " for planet '" + worldName + "'.");
    }

    /**
     * Applies a generation to an existing world: the terrain is recorded for the
     * planet and laid inside the border, replacing ground that doesn't match.
     * Builds that are not part of the ground layer are left standing.
     */
    boolean applyTerrain(Player actor, String worldName, PlanetTerrain.Spec spec) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            actor.sendMessage(Component.text("The world '").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("' isn't loaded — nothing was changed.").color(NamedTextColor.RED)));
            return false;
        }
        if (spec == null) {
            getConfig().set("planet-terrain." + worldName, null);
            saveConfigQuietly();
            actor.sendMessage(Component.text("Terrain of ").color(NamedTextColor.GREEN)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text(" is no longer tracked — the existing ground is kept.")
                            .color(NamedTextColor.GREEN)));
            return true;
        }
        PlanetTerrain.save(getConfig(), worldName, spec);
        saveConfigQuietly();
        int filled = PlanetTerrain.ensureFloor(world, worldName, spec, true);
        actor.sendMessage(Component.text("Terrain of ").color(NamedTextColor.GREEN)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text(" is now ").color(NamedTextColor.GREEN))
                .append(Component.text(PlanetTerrain.describe(spec)).color(NamedTextColor.YELLOW))
                .append(Component.text(" — " + filled + " column(s) re-laid inside the border.")
                        .color(NamedTextColor.GREEN)));
        actor.sendMessage(Component.text("Existing builds stand; only ground that didn't match was replaced.")
                .color(NamedTextColor.GRAY));
        getLogger().info(actor.getName() + " set the terrain of '" + worldName + "' to "
                + PlanetTerrain.describe(spec) + ".");
        return true;
    }

    /** Locks or unlocks a planet, exactly like "/planets lock", without the countdown. */
    void setPlanetLock(Player actor, String worldName, boolean locked) {
        Planet planet = new Planet(adminDisplayName(worldName), Material.GRASS_BLOCK, worldName);
        if (locked) {
            PendingLock pending = pendingLocks.remove(worldName.toLowerCase(Locale.ROOT));
            if (pending != null) {
                pending.cancel();
            }
            lockPlanetNow(actor, planet);
            getLogger().info(actor.getName() + " locked planet '" + worldName + "'.");
            return;
        }
        List<String> names = new ArrayList<>(getConfig().getStringList("locked-worlds"));
        names.removeIf(name -> name.equalsIgnoreCase(worldName));
        getConfig().set("locked-worlds", names);
        saveConfigQuietly();
        actor.sendMessage(Component.text("Planet ").color(NamedTextColor.GREEN)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text(" is now unlocked for everyone.").color(NamedTextColor.GREEN)));
        announceLockChange(actor, planet, false);
        getLogger().info(actor.getName() + " unlocked planet '" + worldName + "'.");
    }

    /** Cycles a planet's landing mode: station → random → point → station. */
    void cycleLandingMode(Player actor, String worldName) {
        String mode = switch (landingModeOf(worldName)) {
            case "station" -> "random";
            case "random" -> "point";
            default -> "station";
        };
        if (mode.equals("point") && !hasLandingSpot(worldName)) {
            // A fixed point has to come from somewhere: pin where the admin stood.
            if (!actor.getWorld().getName().equalsIgnoreCase(worldName)) {
                actor.sendMessage(Component.text("A fixed landing point on '").color(NamedTextColor.RED)
                        .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                        .append(Component.text("' needs a spot: stand where players should land inside that world "
                                + "and click again, or use ").color(NamedTextColor.RED))
                        .append(Component.text("/planets setlanding " + worldName).color(NamedTextColor.YELLOW))
                        .append(Component.text(".").color(NamedTextColor.RED)));
                return;
            }
            saveLandingSpot(worldName, actor.getLocation());
        }
        getConfig().set("landing-modes." + worldName, mode);
        saveConfigQuietly();
        PlanetTravel.loadConfig(getConfig());
        actor.sendMessage(Component.text("Players will now ").color(NamedTextColor.GREEN)
                .append(Component.text(switch (mode) {
                    case "station" -> "always land at the world spawn of ";
                    case "point" -> "land at the fixed landing point on ";
                    default -> "land at random safe spots on ";
                }).color(NamedTextColor.GREEN))
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    /** Cycles a planet's locked-in weather: clear → rain → thunder → off. */
    void cycleWeather(Player actor, String worldName) {
        String current = getConfig().getString("weather-lock." + worldName, "off");
        String next = switch (current.toLowerCase(Locale.ROOT)) {
            case "clear" -> "rain";
            case "rain" -> "thunder";
            case "thunder" -> "off";
            default -> "clear";
        };
        if (next.equals("off")) {
            getConfig().set("weather-lock." + worldName, null);
        } else {
            getConfig().set("weather-lock." + worldName, next);
        }
        saveConfigQuietly();
        environment.loadConfig(getConfig());
        applyWeatherNow(worldName, next);
        actor.sendMessage(Component.text("\u2601 Weather of ").color(NamedTextColor.GREEN)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text(" is now ").color(NamedTextColor.GREEN))
                .append(Component.text(next.equals("off") ? "unlocked (normal weather)" : next)
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    /**
     * The weather mode actually in force for a player planet: the planet's own
     * choice, or the config's lock (an archetype sets one when the planet is
     * created), or {@code off} for normal weather.
     */
    String effectivePlanetWeather(MyPlanetData data) {
        String own = data.weather();
        if (own != null && !own.equalsIgnoreCase("off")) {
            return own.toLowerCase(Locale.ROOT);
        }
        String locked = getConfig().getString("weather-lock." + data.worldName());
        return locked == null || locked.isBlank() ? "off" : locked.toLowerCase(Locale.ROOT);
    }

    /** Cycles a player-owned planet's weather: normal → clear → rain → thunder → normal. */
    void cyclePlanetWeather(Player actor, MyPlanetData data) {
        String next = switch (effectivePlanetWeather(data)) {
            case "clear" -> "rain";
            case "rain" -> "thunder";
            case "thunder" -> "off";
            default -> "clear";
        };
        setPlanetWeather(actor, data, next);
    }

    /**
     * Locks a player-owned planet's weather to a mode and persists it. The mode
     * is written both onto the planet data and into the config's weather-lock
     * section, so the environment keeps enforcing it across restarts.
     */
    void setPlanetWeather(Player actor, MyPlanetData data, String mode) {
        data.weather(mode);
        myPlanetManager.save();
        String worldName = data.worldName();
        if (mode.equals("off")) {
            getConfig().set("weather-lock." + worldName, null);
        } else {
            getConfig().set("weather-lock." + worldName, mode);
        }
        saveConfigQuietly();
        environment.loadConfig(getConfig());
        applyWeatherNow(worldName, mode);
        actor.sendMessage(Component.text("\u2601 Weather of ").color(NamedTextColor.GREEN)
                .append(Component.text(data.displayName()).color(NamedTextColor.YELLOW))
                .append(Component.text(" is now ").color(NamedTextColor.GREEN))
                .append(Component.text(weatherLabel(mode)).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    /** Applies a weather mode to a loaded world right now (no-op when unloaded). */
    private void applyWeatherNow(String worldName, String mode) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return;
        }
        switch (mode == null ? "off" : mode.toLowerCase(Locale.ROOT)) {
            case "clear" -> {
                world.setStorm(false);
                world.setThundering(false);
            }
            case "rain" -> {
                world.setStorm(true);
                world.setThundering(false);
                // Keep it raining for a long stretch; the environment re-locks it anyway.
                world.setWeatherDuration(Integer.MAX_VALUE);
            }
            case "thunder" -> {
                world.setStorm(true);
                world.setThundering(true);
                world.setWeatherDuration(Integer.MAX_VALUE);
                world.setThunderDuration(Integer.MAX_VALUE);
            }
            default -> {
            }
        }
    }

    /** Friendly label for a weather mode. */
    static String weatherLabel(String mode) {
        return switch (mode == null ? "off" : mode.toLowerCase(Locale.ROOT)) {
            case "clear" -> "forced clear";
            case "rain" -> "forced rain";
            case "thunder" -> "forced thunder";
            default -> "normal (changing)";
        };
    }

    /** Pins a planet's spawn to where the admin is standing, inside that world. */
    void setWorldSpawnHere(Player actor, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            actor.sendMessage(Component.text("The world '").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("' isn't loaded.").color(NamedTextColor.RED)));
            return;
        }
        if (!actor.getWorld().getName().equalsIgnoreCase(worldName)) {
            actor.sendMessage(Component.text("Stand inside '").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("' to pin its spawn where you are — use 'Enter Planet' first.")
                            .color(NamedTextColor.RED)));
            return;
        }
        Location here = actor.getLocation();
        world.setSpawnLocation(here.getBlockX(), here.getBlockY(), here.getBlockZ());
        actor.sendMessage(Component.text("\uD83C\uDFF0 Spawn of ").color(NamedTextColor.GREEN)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text(" is now (").color(NamedTextColor.GREEN))
                .append(Component.text(here.getBlockX() + ", " + here.getBlockY() + ", " + here.getBlockZ())
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(").").color(NamedTextColor.GREEN)));
    }

    /** Puts a planet's border back to the size its size level says it should have. */
    void resetPlanetBorder(Player actor, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            actor.sendMessage(Component.text("The world '").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("' isn't loaded.").color(NamedTextColor.RED)));
            return;
        }
        MyPlanetData data = myPlanetManager.get(worldName);
        if (data != null) {
            updateWorldBorder(data);
        } else {
            setPlanetWorldBorder(worldName);
        }
        actor.sendMessage(Component.text("\uD83D\uDFF7 Border of ").color(NamedTextColor.GREEN)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text(" reset to ").color(NamedTextColor.GREEN))
                .append(Component.text((long) world.getWorldBorder().getSize() + " blocks")
                        .color(NamedTextColor.YELLOW))
                .append(Component.text(data != null
                                ? " (its size level)."
                                : " (the public-planet radius).")
                        .color(NamedTextColor.GREEN)));
    }

    /** Cycles a planet's menu icon through the admin palette. */
    void cyclePlanetIcon(Player actor, String worldName) {
        Material current = iconOverride(worldName);
        int index = current == null ? -1 : ICON_PALETTE.indexOf(current);
        Material next = ICON_PALETTE.get((index + 1) % ICON_PALETTE.size());
        getConfig().set("icons." + worldName, next.name());
        saveConfigQuietly();
        actor.sendMessage(Component.text("Menu icon of ").color(NamedTextColor.GREEN)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text(" is now ").color(NamedTextColor.GREEN))
                .append(Component.text(next.name()).color(NamedTextColor.YELLOW))
                .append(Component.text(". An exact block: ").color(NamedTextColor.GREEN))
                .append(Component.text("/planets icon " + worldName + " <material>").color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }

    /** Sends an admin to a planet's spawn, loading the world if it is unloaded. */
    void enterPlanetWorld(Player actor, String worldName) {
        World world = loadWorldNow(worldName);
        if (world == null) {
            actor.sendMessage(Component.text("World '").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("' isn't loaded and has no folder on disk.").color(NamedTextColor.RED)));
            return;
        }
        actor.closeInventory();
        actor.teleport(world.getSpawnLocation());
        actor.sendMessage(Component.text("\uD83D\uDE80 Entered ").color(NamedTextColor.AQUA)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text(" at its spawn.").color(NamedTextColor.GRAY)));
    }

    /**
     * Loads a world that Multiverse left unloaded, keeping its own generator
     * settings (the world's level.dat), or returns null when there is no folder.
     */
    private World loadWorldNow(String worldName) {
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) {
            return loaded;
        }
        if (!new File(Bukkit.getWorldContainer(), worldName).isDirectory()) {
            return null;
        }
        getServer().dispatchCommand(getServer().getConsoleSender(), "mv load " + worldName);
        return Bukkit.getWorld(worldName);
    }

    /**
     * Rebuilds a planet's world from scratch with its own terrain: the old world
     * is deleted and a fresh one generated with the same layers and identity.
     * Every build in the world is lost — which is why the panel asks twice.
     */
    boolean regeneratePlanet(Player actor, String worldName) {
        AdminPlanet planet = adminPlanet(worldName);
        if (planet == null) {
            actor.sendMessage(Component.text("Unknown planet '").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("'.").color(NamedTextColor.RED)));
            return false;
        }
        if (planet.protectedWorld()) {
            actor.sendMessage(Component.text("'").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("' is a protected world — it can't be regenerated.")
                            .color(NamedTextColor.RED)));
            return false;
        }

        // Keep the planet's terrain: only the world itself is rebuilt.
        PlanetTerrain.Spec spec = planet.terrain();
        if (spec == null && planet.owned() != null) {
            PlanetArchetypes.Archetype archetype = PlanetArchetypes.byId(planet.owned().archetypeId());
            if (archetype != null) {
                spec = archetype.terrain();
            }
        }

        World old = Bukkit.getWorld(worldName);
        int players = old == null ? 0 : old.getPlayers().size();
        actor.sendMessage(Component.text("Regenerating '").color(NamedTextColor.GRAY)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text("' — the old world is being removed...").color(NamedTextColor.GRAY)));

        deleteWorldNow(worldName);
        if (worldFolderExists(worldName)) {
            actor.sendMessage(Component.text("The old world folder of '").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("' is still locked by the server — try again in a moment.")
                            .color(NamedTextColor.RED)));
            return false;
        }

        World world = createPlanetWorld(worldName, spec, planet.structures());
        if (world == null) {
            actor.sendMessage(Component.text("Could not regenerate '").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("' — check the console. The world is gone, so the planet is empty until "
                            + "you create its terrain again.").color(NamedTextColor.RED)));
            return false;
        }
        if (spec != null) {
            PlanetTerrain.save(getConfig(), worldName, spec);
        }
        saveConfigQuietly();
        PlanetTravel.loadConfig(getConfig());

        // Ground, border and the planet's own size level.
        MyPlanetData owned = myPlanetManager.get(worldName);
        if (owned != null) {
            updateWorldBorder(owned);
        } else {
            setPlanetWorldBorder(worldName);
            PlanetTerrain.ensureFloor(world, worldName, spec);
        }
        // Register it with Multiverse again, so it behaves like the planet it was.
        getServer().dispatchCommand(getServer().getConsoleSender(), "mv import " + worldName + " normal");

        actor.sendMessage(Component.text("\u267B Regenerated ").color(NamedTextColor.GREEN)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text(" with ").color(NamedTextColor.GREEN))
                .append(Component.text(PlanetTerrain.describe(spec)).color(NamedTextColor.YELLOW))
                .append(Component.text(players > 0 ? " — " + players + " player(s) were moved out." : ".")
                        .color(NamedTextColor.GREEN)));
        getLogger().info(actor.getName() + " regenerated planet '" + worldName + "' ("
                + PlanetTerrain.describe(spec) + ").");
        return true;
    }

    /**
     * Deletes a planet's world for good: folder, Multiverse entry, ownership
     * record and every per-world setting. The name stays retired, so it can
     * never be handed to a future buyer.
     */
    boolean deletePlanetNow(Player actor, String worldName) {
        AdminPlanet planet = adminPlanet(worldName);
        if (planet != null && planet.protectedWorld()) {
            actor.sendMessage(Component.text("'").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("' is a protected world — it can't be deleted.")
                            .color(NamedTextColor.RED)));
            return false;
        }
        if (Bukkit.getWorld(worldName) == null && !worldFolderExists(worldName)) {
            actor.sendMessage(Component.text("There is no world called '").color(NamedTextColor.RED)
                    .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                    .append(Component.text("'.").color(NamedTextColor.RED)));
            return false;
        }

        boolean worldGone = deleteWorldNow(worldName);
        clearWorldConfig(worldName);
        rememberDeletedWorld(worldName);
        saveConfigQuietly();
        PlanetTravel.loadConfig(getConfig());
        PlanetEffects.loadConfig(getConfig());
        environment.loadConfig(getConfig());
        myPlanetManager.remove(worldName);
        myPlanetManager.retire(worldName);
        getLogger().info(actor.getName() + " deleted planet world '" + worldName + "'.");
        actor.sendMessage(Component.text("\uD83D\uDDD1 Deleted ").color(NamedTextColor.GREEN)
                .append(Component.text(worldName).color(NamedTextColor.YELLOW))
                .append(Component.text(" — the world, its folder and its ownership record are gone, and the "
                        + "name is retired forever.").color(NamedTextColor.GREEN)));
        if (Bukkit.getWorld(worldName) != null || !worldGone) {
            actor.sendMessage(Component.text("Note: the server still had '" + worldName + "' loaded, so its "
                    + "folder may need deleting after a restart. Its name is retired either way.")
                    .color(NamedTextColor.YELLOW));
        }
        return true;
    }

    void openMyPlanetSelect(Player player) {
        // Owned planets first, then any planet the player is only a member of, so
        // a member (not owner) still reaches the planet they belong to instead of
        // being sent to the "buy your first planet" screen.
        List<MyPlanetData> listed = new ArrayList<>(myPlanetManager.ownedBy(player.getUniqueId()));
        for (MyPlanetData data : myPlanetManager.memberOf(player.getUniqueId())) {
            if (!data.ownerUuid().equals(player.getUniqueId())) {
                listed.add(data);
            }
        }
        player.closeInventory();
        if (!listed.isEmpty()) {
            new MyPlanetsOverviewMenu(this, player, listed).open(player);
            return;
        }
        // No planets owned — show the introduction UI.
        new MyPlanetIntroMenu(this, player).open(player);
    }

    /** Opens the public /planets menu (planet browser) for a player. */
    void openPlanetsMenu(Player player) {
        player.closeInventory();
        List<Planet> planets = availablePlanets();
        new PlanetsMenu(this, planets, player).open(player);
    }
}
