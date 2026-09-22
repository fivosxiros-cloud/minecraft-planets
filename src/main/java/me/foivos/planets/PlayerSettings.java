package me.foivos.planets;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Every player's personal preferences, the ones behind {@code /settings}.
 *
 * <p>They are global: once switched they apply in every world until switched
 * back, and they survive restarts because they are written to
 * {@code player-settings.yml}. A player with no entry keeps the default
 * (everything enabled), so the file only ever grows for players who changed
 * something.
 */
public final class PlayerSettings {

    /**
     * The toggles shown in the /settings menu. Each one carries the short
     * "what it affects" line shown in its menu description, plus what the world
     * looks like with it on and off.
     */
    public enum Setting {
        // ── Chat and other players ───────────────────────────────────────
        PUBLIC_CHAT("Public Chat", Material.PAPER,
                "Everyone's public chat as it scrolls past you",
                "You see everyone's public chat",
                "Public chat is hidden from you (private messages still arrive)"),
        MSG("Private Messages", Material.NAME_TAG,
                "Incoming /msg private messages from other players",
                "Other players can send you /msg",
                "Nobody can send you private messages"),
        MSG_PING("Private-Message Ping", Material.NOTE_BLOCK,
                "A soft sound when a private message arrives",
                "You hear a ping for every incoming /msg",
                "Private messages arrive silently"),
        JOIN_LEAVE_MESSAGES("Join/Leave Messages", Material.OAK_DOOR,
                "The lines announcing other players joining and leaving",
                "You see when players join and leave",
                "Join and quit lines are hidden from you"),
        DEATH_MESSAGES("Death Messages", Material.SKELETON_SKULL,
                "Broadcasts when a player dies",
                "You see death broadcasts",
                "Death broadcasts are hidden from you"),
        TP_REQUESTS("Teleport Requests", Material.ENDER_PEARL,
                "Incoming /tpa and /tpahere requests",
                "Players can send you /tpa and /tpahere requests",
                "Nobody can send you teleport requests"),
        MENTION_ALERTS("Name Mentions", Material.BELL,
                "A ping when someone types your name in public chat",
                "You get a ping when someone says your name in chat",
                "No ping when your name is mentioned"),
        INVITE_NOTIFICATIONS("Invite Notifications", Material.WRITABLE_BOOK,
                "Chat alerts when someone invites you to their planet",
                "You get the clickable invite message",
                "Invites wait silently in /myp → Invitations"),
        ANTI_CHAT_SPAM("Anti Chat Spam", Material.CLOCK,
                "A short cooldown between the messages you send",
                "Chat is rate-limited so rapid messages are blocked",
                "You can send messages back-to-back"),
        // ── Money ───────────────────────────────────────────────────────
        BALANCE_NOTICES("Balance Notices", Material.EMERALD,
                "Money lines when VPL is spent, paid out or balances change",
                "You see purchase, sale and balance amount lines",
                "Money chat lines are hidden (the money still moves)"),
        // ── Planets ─────────────────────────────────────────────────────
        TRAVEL_MESSAGES("Teleport Messages", Material.COMPASS,
                "The teleport countdown and arrival chat lines",
                "You see the teleport countdown and arrival messages",
                "Teleport chat messages are hidden (the teleport still happens)"),
        PLANET_HUD("Planet HUD", Material.SPYGLASS,
                "A small action bar while you're on a planet or a lobby",
                "The HUD appears on planets and in lobbies",
                "No action-bar HUD (mine stays untouched)"),
        EFFECT_SUMMARY("Effect Summary", Material.BREWING_STAND,
                "A line listing a planet's effects when you arrive",
                "You're told what a planet does to you when you land",
                "Arriving on a planet stays silent"),
        COLORED_SKY("Colored Sky", Material.LIGHT_BLUE_DYE,
                "The tinted sky and fog of each planet",
                "Planet skies and fog are tinted to match each planet",
                "You see the plain vanilla sky everywhere"),
        PLANET_ATMOSPHERE("Planet Atmosphere", Material.FIREWORK_STAR,
                "The haze and ambient particles drifting around you",
                "Planet haze and ambient particles drift around you",
                "The haze and ambient particles around you are hidden"),
        // ── Comfort ─────────────────────────────────────────────────────
        MUSIC("Planet Music", Material.JUKEBOX,
                "Music that plays while you're on a planet or in a lobby",
                "Every planet and lobby plays its own music around you",
                "Planets and lobbies stay silent for you"),
        MOB_SPAWNING("Mob Spawning", Material.ZOMBIE_HEAD,
                "Mobs around you: their spawning and their targeting",
                "Hostile mobs can spawn near you and target you",
                "Hostile mobs won't spawn near you or target you"),
        NIGHT_VISION("Night Vision", Material.POTION,
                "Endless night vision while you play",
                "You always see in the dark (endless night vision)",
                "You see in the dark normally");

        private final String displayName;
        private final Material icon;
        private final String description;
        private final String enabledText;
        private final String disabledText;
        private final boolean defaultOn;

        Setting(String displayName, Material icon, String description,
                String enabledText, String disabledText) {
            this(displayName, icon, description, enabledText, disabledText, true);
        }

        Setting(String displayName, Material icon, String description,
                String enabledText, String disabledText, boolean defaultOn) {
            this.displayName = displayName;
            this.icon = icon;
            this.description = description;
            this.enabledText = enabledText;
            this.disabledText = disabledText;
            this.defaultOn = defaultOn;
        }

        /**
         * The value a player starts with. Everything is on by default, except
         * night vision: that one is a personal extra nobody is opted into.
         */
        public boolean defaultOn() { return defaultOn; }

        public String displayName() { return displayName; }
        /** The toggle's icon, as a fresh stack (night vision is a potion). */
        public ItemStack icon() {
            ItemStack item = new ItemStack(icon);
            if (this == NIGHT_VISION) {
                if (item.getItemMeta() instanceof PotionMeta meta) {
                    meta.setBasePotionType(PotionType.NIGHT_VISION);
                    item.setItemMeta(meta);
                }
            }
            return item;
        }

        /** One line describing what this setting affects. */
        public String description() { return description; }
        public String enabledText() { return enabledText; }
        public String disabledText() { return disabledText; }
    }

    /**
     * Whether a player has a setting switched on, safe to call from anything
     * holding the plugin instance: defaults to {@code true} while the plugin is
     * still starting up (or when called with another plugin's instance).
     */
    public static boolean on(JavaPlugin plugin, UUID uuid, Setting setting) {
        if (!(plugin instanceof Planets planets)) {
            return true;
        }
        PlayerSettings settings = planets.getPlayerSettings();
        return settings == null || settings.get(uuid, setting);
    }

    private final Planets plugin;
    private final File file;
    private YamlConfiguration config;

    /** uuid -> chosen values (only the settings the player changed). */
    private final Map<UUID, EnumMap<Setting, Boolean>> values = new ConcurrentHashMap<>();

    /**
     * The HUD line a player starts on, as an index into the lines configured
     * under {@code hud.modes} in config.yml. The plugin sets this from
     * {@code hud.default-mode} (the in-game editor writes it too); callers clamp
     * the index, so removing lines can never point a player outside the list.
     */
    private int defaultHudMode = 1;

    /** uuid -> the Planet HUD line that player picked, when it isn't the default. */
    private final Map<UUID, Integer> hudModes = new ConcurrentHashMap<>();

    public PlayerSettings(Planets plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "player-settings.yml");
        load();
    }

    // ── Persistence ─────────────────────────────────────────────────────

    public void load() {
        values.clear();
        hudModes.clear();
        if (!file.exists()) {
            config = new YamlConfiguration();
            return;
        }
        config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = config.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            EnumMap<Setting, Boolean> map = new EnumMap<>(Setting.class);
            for (Setting setting : Setting.values()) {
                String path = "players." + key + "." + setting.name();
                if (config.contains(path)) {
                    map.put(setting, config.getBoolean(path));
                }
            }
            if (!map.isEmpty()) {
                values.put(uuid, map);
            }
            String hudModePath = "players." + key + ".hud-mode";
            if (config.contains(hudModePath)) {
                Object raw = config.get(hudModePath);
                if (raw instanceof Number number) {
                    hudModes.put(uuid, number.intValue());
                } else {
                    // Older builds stored a mode name; map it onto the index.
                    int legacy = switch (String.valueOf(raw).toUpperCase(Locale.ROOT)) {
                        case "PLANET" -> 0;
                        case "COORDS" -> 2;
                        default -> defaultHudMode;
                    };
                    if (legacy != defaultHudMode) {
                        hudModes.put(uuid, legacy);
                    }
                }
            }
        }
        plugin.getLogger().info("Loaded personal settings for " + values.size() + " player(s).");
    }

    public void save() {
        if (config == null) {
            config = new YamlConfiguration();
        }
        config.set("players", null);
        for (Map.Entry<UUID, EnumMap<Setting, Boolean>> entry : values.entrySet()) {
            for (Map.Entry<Setting, Boolean> value : entry.getValue().entrySet()) {
                config.set("players." + entry.getKey() + "." + value.getKey().name(), value.getValue());
            }
        }
        for (Map.Entry<UUID, Integer> entry : hudModes.entrySet()) {
            config.set("players." + entry.getKey() + ".hud-mode", entry.getValue());
        }
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save player-settings.yml", ex);
        }
    }

    // ── Access ──────────────────────────────────────────────────────────

    /** Whether a setting is on for a player (default: on). */
    public boolean get(UUID uuid, Setting setting) {
        EnumMap<Setting, Boolean> map = values.get(uuid);
        if (map == null) {
            return setting.defaultOn();
        }
        return map.getOrDefault(setting, setting.defaultOn());
    }

    /** Switches a setting for a player and persists it. */
    public void set(UUID uuid, Setting setting, boolean value) {
        EnumMap<Setting, Boolean> map = values.computeIfAbsent(uuid, k -> new EnumMap<>(Setting.class));
        map.put(setting, value);
        save();
    }

    /** Flips a setting and returns its new value. */
    public boolean toggle(UUID uuid, Setting setting) {
        boolean next = !get(uuid, setting);
        set(uuid, setting, next);
        return next;
    }

    // ── Planet HUD line ─────────────────────────────────────────────────

    /**
     * Which configured HUD line this player is on, clamped to what the config
     * actually offers (so a shortened {@code hud.modes} list is always safe).
     */
    public int hudMode(UUID uuid, int modeCount) {
        if (modeCount <= 0) {
            return 0;
        }
        int raw = hudModes.getOrDefault(uuid, defaultHudMode);
        return Math.min(Math.max(raw, 0), modeCount - 1);
    }

    /** Cycles this player's HUD line and returns the new index. */
    public int cycleHudMode(UUID uuid, int modeCount) {
        int next = modeCount <= 0 ? 0 : (hudMode(uuid, modeCount) + 1) % modeCount;
        if (next == defaultHudMode) {
            hudModes.remove(uuid); // the default is never stored
        } else {
            hudModes.put(uuid, next);
        }
        save();
        return next;
    }

    /** Which line players start on, as an index into the configured lines. */
    public int defaultHudMode() {
        return Math.max(0, defaultHudMode);
    }

    /** Called by the plugin whenever {@code hud.default-mode} changes. */
    public void defaultHudMode(int index) {
        this.defaultHudMode = Math.max(0, index);
    }

    /** Whether the player changed anything from the defaults. */
    public boolean anyCustom(UUID uuid) {
        return values.containsKey(uuid) || hudModes.containsKey(uuid);
    }

    /** How many of a player's settings differ from the default. */
    public int customCount(UUID uuid) {
        int count = hudModes.containsKey(uuid) ? 1 : 0;
        EnumMap<Setting, Boolean> map = values.get(uuid);
        if (map == null) {
            return count;
        }
        for (Setting setting : Setting.values()) {
            if (map.getOrDefault(setting, setting.defaultOn()) != setting.defaultOn()) {
                count++;
            }
        }
        return count;
    }

    /** Restores a player's settings to the defaults. */
    public void reset(UUID uuid) {
        values.remove(uuid);
        hudModes.remove(uuid);
        save();
    }

    /** Parses a setting name typed in chat/admin usage, or null. */
    public static Setting byName(String name) {
        if (name == null) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (Setting setting : Setting.values()) {
            if (setting.name().equalsIgnoreCase(key)) {
                return setting;
            }
        }
        return null;
    }
}
