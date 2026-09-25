package me.foivos.playerdata.yaml;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import me.foivos.playerdata.IPlayerDataStore;
import me.foivos.playerdata.PlayerData;
import me.foivos.playerdata.PlayerDataContext;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class PlayerDataStore implements IPlayerDataStore {

    private static final String DATA_FOLDER_NAME = "player-data";
    private static final String LAST_ONLINE_KEY = "last-online-ms";
    private static final String FIRST_JOINED_KEY = "first-joined-ms";
    private static final String BALANCE_KEY = "balance";
    private static final String NEB_KEY = "neb";
    private static final String PLAYTIME_HOURS_KEY = "playtime-hours";
    private static final String PLANETS_OWNED_KEY = "planets-owned";
    private static final String HOMES_COUNT_KEY = "homes-count";
    private static final String SETTINGS_KEY = "settings";

    private final JavaPlugin plugin;
    private final File dataFolder;
    /**
     * In-memory cache of the most recently loaded snapshot per uuid.
     */
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    /**
     * Last known name per uuid. Built once from the data folder and kept up to
     * date by every save, so name lookups never touch the disk more than once.
     */
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private volatile boolean nameIndexLoaded;

    /** Upper bound on how many matches a single search returns. */
    private static final int SEARCH_LIMIT = 100;

    public PlayerDataStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), DATA_FOLDER_NAME);
    }

    /**
     * Creates the data folder if it does not exist yet.
     */
    public void ensureFolder() {
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        loadNameIndex();
    }

    /**
     * Reads just the stored name of every player file into memory. Done once,
     * so a tab completion does not re-parse the whole data folder per keystroke.
     */
    private void loadNameIndex() {
        if (nameIndexLoaded) {
            return;
        }
        nameIndexLoaded = true;
        File[] files = dataFolder.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.getName().endsWith(".yml")) {
                continue;
            }
            String base = file.getName().substring(0, file.getName().length() - 4);
            UUID uuid;
            try {
                uuid = UUID.fromString(base);
            } catch (IllegalArgumentException notAUuid) {
                continue;
            }
            String name = YamlConfiguration.loadConfiguration(file).getString("name");
            if (name != null && !name.isBlank()) {
                names.put(uuid, name);
            }
        }
    }

    /**
     * Returns the snapshot for the given player, loading it from disk if needed.
     */
    public PlayerData get(UUID uuid) {
        if (uuid == null) {
            return PlayerData.EMPTY;
        }
        return cache.computeIfAbsent(uuid, this::load);
    }

    /**
     * Refreshes the snapshot for every online player (called on join/quit).
     */
    public void refreshOnlinePlayers(PlayerDataContext context) {
        if (context == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player, context);
        }
    }

    /**
     * Refreshes a single player's snapshot from the live source of truth.
     */
    public void refresh(Player player, PlayerDataContext context) {
        if (player == null) {
            return;
        }
        refreshByUuid(player.getUniqueId(), context);
    }

    /**
     * Refreshes a single player's snapshot by uuid (works for offline players too).
     */
    public void refreshByUuid(UUID uuid, PlayerDataContext context) {
        if (uuid == null) {
            return;
        }
        PlayerData data = computeSnapshot(uuid, context, cache.get(uuid));
        cache.put(uuid, data);
        save(data);
    }

    /**
     * Returns every snapshot currently in memory + any still-on-disk entries.
     */
    public List<PlayerData> getAll() {
        List<PlayerData> result = new ArrayList<>(cache.values());
        File[] files = dataFolder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.getName().endsWith(".yml")) {
                    continue;
                }
                try {
                    String name = file.getName().replaceFirst("\\.yml$", "");
                    UUID uuid = UUID.fromString(name);
                    if (!cache.containsKey(uuid)) {
                        result.add(load(uuid));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a uuid-named file — skip.
                }
            }
        }
        result.sort(Comparator.comparing(PlayerData::name));
        return result;
    }

    /**
     * Returns every snapshot for players with the given name (case-insensitive prefix).
     */
    public List<PlayerData> search(String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        loadNameIndex();
        return names.entrySet().stream()
                .filter(entry -> entry.getValue().toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted(Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER))
                .limit(SEARCH_LIMIT)
                .map(entry -> get(entry.getKey()))
                .collect(Collectors.toList());
    }

    // ── snapshot construction ───────────────────────────────────────────────

    /**
     * Builds a fresh snapshot for the given player from the plugin's live state.
     */
    private PlayerData computeSnapshot(UUID uuid, PlayerDataContext context, PlayerData previous) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        if (name == null || name.isBlank()) {
            name = uuid.toString().substring(0, 8);
        }
        names.put(uuid, name);

        // Anything the live server state cannot answer right now — a balance the
        // economy would not hand back for an offline player, an unset or failing
        // NEB placeholder — keeps its previously stored value rather than being
        // written back as a zero.
        double balance = previous == null ? 0 : previous.balance();
        if (context != null && context.hasEconomy()) {
            try {
                double live = context.getBalance(offline);
                if (live >= 0) {
                    balance = live;
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning(
                        "Could not read the balance of " + name + ": " + ex.getMessage());
            }
        }

        double neb = previous == null ? 0 : previous.neb();
        if (context != null) {
            String placeholder = context.nebPlaceholder();
            if (placeholder != null && !placeholder.isBlank() && !placeholder.equals("%neb%")) {
                neb = readNumber(offline, placeholder, neb);
            }
        }

        double playtimeHours = context != null ? context.hoursPlayed(uuid) : 0;
        int planetsOwned = context != null ? context.planetsOwned(uuid) : 0;
        int homesCount = context != null ? context.homesCount(uuid) : 0;
        Map<String, Boolean> settings = context != null
                ? new LinkedHashMap<>(context.settings(uuid)) : new LinkedHashMap<>();

        long firstJoined = offline.getFirstPlayed();
        if (firstJoined <= 0) {
            firstJoined = System.currentTimeMillis();
        }
        long lastOnline = offline.getLastPlayed();
        if (lastOnline <= 0) {
            lastOnline = firstJoined;
        }

        return new PlayerData(uuid, name, balance, neb, playtimeHours,
                planetsOwned, homesCount, settings, firstJoined, lastOnline);
    }

    // ── persistence ─────────────────────────────────────────────────────────

    private PlayerData load(UUID uuid) {
        File file = new File(dataFolder, uuid.toString() + ".yml");
        if (!file.exists()) {
            // No file yet — a store has no access to the consumer plugin's live
            // state, so the rest fills in on the next refresh from the server.
            return blankSnapshot(uuid);
        }
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("");
        if (root == null) {
            return blankSnapshot(uuid);
        }
        String name = root.getString("name", uuid.toString().substring(0, 8));
        names.put(uuid, name);
        double balance = root.getDouble(BALANCE_KEY, 0);
        double neb = root.getDouble(NEB_KEY, 0);
        double playtimeHours = root.getDouble(PLAYTIME_HOURS_KEY, 0);
        int planetsOwned = root.getInt(PLANETS_OWNED_KEY, 0);
        int homesCount = root.getInt(HOMES_COUNT_KEY, 0);
        long firstJoined = root.getLong(FIRST_JOINED_KEY, System.currentTimeMillis());
        long lastOnline = root.getLong(LAST_ONLINE_KEY, firstJoined);
        Map<String, Boolean> settings = new LinkedHashMap<>();
        ConfigurationSection settingsSection = root.getConfigurationSection(SETTINGS_KEY);
        if (settingsSection != null) {
            for (String key : settingsSection.getKeys(false)) {
                settings.put(key, settingsSection.getBoolean(key));
            }
        }
        return new PlayerData(uuid, name, balance, neb, playtimeHours,
                planetsOwned, homesCount, settings, firstJoined, lastOnline);
    }

    /**
     * A snapshot built only from a player's offline profile (name and login
     * dates), used when no data file exists for them yet.
     */
    private PlayerData blankSnapshot(UUID uuid) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        if (name == null || name.isBlank()) {
            name = uuid.toString().substring(0, 8);
        }
        names.put(uuid, name);
        long firstJoined = offline.getFirstPlayed();
        if (firstJoined <= 0) {
            firstJoined = System.currentTimeMillis();
        }
        long lastOnline = offline.getLastPlayed();
        if (lastOnline <= 0) {
            lastOnline = firstJoined;
        }
        return new PlayerData(uuid, name, 0, 0, 0, 0, 0,
                new LinkedHashMap<>(), firstJoined, lastOnline);
    }

    private void save(PlayerData data) {
        names.put(data.uuid(), data.name());
        File file = new File(dataFolder, data.uuid().toString() + ".yml");
        FileConfiguration yaml = new YamlConfiguration();
        yaml.set("name", data.name());
        yaml.set(BALANCE_KEY, data.balance());
        yaml.set(NEB_KEY, data.neb());
        yaml.set(PLAYTIME_HOURS_KEY, data.playtimeHours());
        yaml.set(PLANETS_OWNED_KEY, data.planetsOwned());
        yaml.set(HOMES_COUNT_KEY, data.homesCount());
        yaml.set(FIRST_JOINED_KEY, data.firstJoinedMs());
        yaml.set(LAST_ONLINE_KEY, data.lastOnlineMs());
        ConfigurationSection settings = yaml.createSection(SETTINGS_KEY);
        for (Map.Entry<String, Boolean> entry : data.settings().entrySet()) {
            settings.set(entry.getKey(), entry.getValue());
        }
        try {
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save player-data for " + data.name() + ": " + ex.getMessage());
        }
    }

    // ── admin dump ──────────────────────────────────────────────────────────

    /**
     * Prints a full snapshot of the given player to the command sender.
     */
    public void dumpTo(CommandSender sender, UUID uuid) {
        if (sender == null) {
            return;
        }
        PlayerData data = get(uuid);
        sender.sendMessage(Component.text("─── Player Data: " + data.name() + " ───")
                .color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("  UUID: ").color(NamedTextColor.GRAY)
                .append(Component.text(data.uuid().toString()).color(NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("  VPL: ").color(NamedTextColor.GRAY)
                .append(Component.text(formatPrice(data.balance())).color(NamedTextColor.GOLD))
                .append(Component.text(" VPL").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  NEB: ").color(NamedTextColor.GRAY)
                .append(Component.text(formatPrice(data.neb())).color(NamedTextColor.LIGHT_PURPLE))
                .append(Component.text(" NEB").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  Playtime: ").color(NamedTextColor.GRAY)
                .append(Component.text(formatHours(data.playtimeHours())).color(NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("  Planets owned: ").color(NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(data.planetsOwned())).color(NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("  Homes: ").color(NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(data.homesCount())).color(NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("  First joined: ").color(NamedTextColor.GRAY)
                .append(Component.text(formatDate(data.firstJoinedMs())).color(NamedTextColor.DARK_PURPLE)));
        sender.sendMessage(Component.text("  Last online: ").color(NamedTextColor.GRAY)
                .append(Component.text(formatDate(data.lastOnlineMs())).color(NamedTextColor.DARK_PURPLE)));
        sender.sendMessage(Component.text("  Settings:").color(NamedTextColor.GRAY));
        for (Map.Entry<String, Boolean> entry : data.settings().entrySet()) {
            String onOff = entry.getValue() ? "ON " : "OFF";
            sender.sendMessage(Component.text("    • " + entry.getKey() + " — ").color(NamedTextColor.DARK_GRAY)
                    .append(Component.text(onOff).color(entry.getValue() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        }
        sender.sendMessage(Component.text("─── end ───").color(NamedTextColor.GOLD));
    }

    private static String formatPrice(double value) {
        return String.format(Locale.ROOT, "%,.2f", value);
    }

    private static String formatHours(double hours) {
        return String.format(Locale.ROOT, "%,.1f h", hours);
    }

    private static String formatDate(long milliseconds) {
        return java.time.Instant.ofEpochMilli(milliseconds)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
                .toString()
                .replace('T', ' ');
    }

    private static double readNumber(OfflinePlayer player, String placeholder, double fallback) {
        if (player == null || placeholder == null
                || Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return fallback;
        }
        try {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Object result = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class)
                    .invoke(null, player, placeholder);
            if (!(result instanceof String raw)) {
                return fallback;
            }
            String value = raw.trim().replaceAll("[^0-9,.\\-]", "");
            int comma = value.lastIndexOf(',');
            int dot = value.lastIndexOf('.');
            if (comma >= 0 && dot >= 0) {
                value = comma > dot ? value.replace(".", "").replace(',', '.') : value.replace(",", "");
            } else if (comma >= 0) {
                value = value.substring(comma + 1).length() == 3
                        ? value.replace(",", "") : value.replace(',', '.');
            }
            return Double.parseDouble(value);
        } catch (ReflectiveOperationException | NumberFormatException ex) {
            return fallback;
        }
    }

}