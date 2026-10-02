package me.foivos.bounty.config;

import me.foivos.bounty.BountyPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Level;

/**
 * config.yml, read once into values the rest of the plugin can use without
 * digging through paths, and with every number that has to make sense clamped
 * so a typo cannot break the board (a negative minimum, a page of nothing, a
 * refund of 500%).
 *
 * <p>{@link #reload()} re-reads everything, which is what {@code /bounty
 * reload} does.
 */
public final class BountyConfig {

    private final BountyPlugin plugin;
    private final Messages messages = new Messages();

    private double minimum = 100.0;
    private double maximum = 1_000_000.0;
    private String targets = "all";
    private boolean allowSelfBounty = true;

    private boolean selfBountyEnabled = true;
    private int selfBountyRefundPercent = 100;

    private boolean personalAdded = true;
    private boolean personalClaimed = true;
    private boolean personalReceived = true;
    private boolean broadcastAdded;
    private boolean broadcastClaimed = true;

    private String guiTitle = "&b&lBounties";
    private Material frameMaterial = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    private int entriesPerPage = 21;
    private int selfSlot = 13;
    private int sortingSlot = 49;
    private int previousSlot = 47;
    private int nextSlot = 51;
    private int pageSlot = 48;
    private int closeSlot = 53;
    private double quickAddAmount = 1000.0;

    private boolean antiAbuseEnabled = true;
    private long killCooldownSeconds = 60L;
    private boolean preventRepeatedKills = true;

    private String storageFile = "bounties.db";
    private int saveIntervalSeconds = 5;

    private String currencySymbol = "$";
    private boolean thousandsSeparator = true;

    public BountyConfig(BountyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Reads everything again, and the messages with it. */
    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        boolean grown = adoptShippedMessages(config);
        messages.load(config.getConfigurationSection("messages"));

        minimum = Math.max(0.0, config.getDouble("settings.minimum-bounty", 100.0));
        maximum = Math.max(minimum, config.getDouble("settings.maximum-bounty", 1_000_000.0));
        targets = String.valueOf(config.getString("settings.targets", "all")).toLowerCase(Locale.ROOT);
        if (!targets.equals("online") && !targets.equals("played")) {
            targets = "all";
        }
        allowSelfBounty = config.getBoolean("settings.allow-self-bounty", true);

        selfBountyEnabled = config.getBoolean("self-bounty.enabled", true);
        selfBountyRefundPercent = Math.min(100,
                Math.max(0, config.getInt("self-bounty.refund-percent", 100)));

        personalAdded = config.getBoolean("notifications.personal.bounty-added", true);
        personalClaimed = config.getBoolean("notifications.personal.bounty-claimed", true);
        personalReceived = config.getBoolean("notifications.personal.bounty-received", true);
        broadcastAdded = config.getBoolean("notifications.broadcast.bounty-added", false);
        broadcastClaimed = config.getBoolean("notifications.broadcast.bounty-claimed", true);

        guiTitle = String.valueOf(config.getString("gui.title", "&b&lBounties"));
        Material frame = Material.matchMaterial(String.valueOf(config.getString(
                "gui.frame-material", Material.LIGHT_BLUE_STAINED_GLASS_PANE.name())));
        frameMaterial = frame == null || frame == Material.AIR
                ? Material.LIGHT_BLUE_STAINED_GLASS_PANE : frame;
        entriesPerPage = clampSlot(config.getInt("gui.entries-per-page", 21));
        selfSlot = clampSlot(config.getInt("gui.self-slot", 13));
        sortingSlot = clampSlot(config.getInt("gui.sorting-slot", 49));
        previousSlot = clampSlot(config.getInt("gui.previous-slot", 47));
        nextSlot = clampSlot(config.getInt("gui.next-slot", 51));
        pageSlot = clampSlot(config.getInt("gui.page-slot", 48));
        closeSlot = clampSlot(config.getInt("gui.close-slot", 53));
        quickAddAmount = Math.max(1.0, config.getDouble("gui.quick-add-amount", 1000.0));

        antiAbuseEnabled = config.getBoolean("anti-abuse.enabled", true);
        killCooldownSeconds = Math.max(0L, config.getLong("anti-abuse.kill-cooldown", 60L));
        preventRepeatedKills = config.getBoolean("anti-abuse.prevent-repeated-kills", true);

        storageFile = String.valueOf(config.getString("storage.file", "bounties.db"));
        if (storageFile.isBlank()) {
            storageFile = "bounties.db";
        }
        saveIntervalSeconds = Math.max(1, config.getInt("storage.save-interval-seconds", 5));

        currencySymbol = String.valueOf(config.getString("economy.currency-symbol", "$"));
        thousandsSeparator = config.getBoolean("economy.thousands-separator", true);

        if (grown) {
            plugin.saveConfig();
        }
    }

    /**
     * Writes any shipped message the config.yml on disk has never been told
     * about into it.
     *
     * <p>A message is not like a setting: nothing reads a missing one out loud.
     * {@code Messages} answers an empty string for a key it has never seen, so a
     * message an update added — the amount dialog's own buttons, say — draws a
     * screen full of nameless items, and there is no error anywhere to say why.
     * Merging the shipped messages in is what stops an update from needing a
     * fresh config.yml to work.
     *
     * <p>An existing value is never overwritten. A message an owner has deleted
     * will come back, because there is no way to tell that from a config.yml
     * written before the message existed; setting one to {@code ""} is how to
     * silence it for good.
     *
     * @return whether anything was added, and the file wants saving
     */
    private boolean adoptShippedMessages(FileConfiguration config) {
        FileConfiguration shipped = shippedConfig();
        if (shipped == null) {
            return false;
        }
        ConfigurationSection shippedMessages = shipped.getConfigurationSection("messages");
        if (shippedMessages == null) {
            return false;
        }
        ConfigurationSection live = config.isConfigurationSection("messages")
                ? config.getConfigurationSection("messages")
                : config.createSection("messages");
        return merge(shippedMessages, live);
    }

    /**
     * Copies every path of {@code from} that {@code into} has never heard of.
     *
     * <p>Every check asks about the file itself, not about the defaults
     * hanging off it: {@code reloadConfig} makes the config.yml inside the jar
     * the defaults of the one on disk, so a plain {@code contains} answers
     * {@code true} for every shipped message and nothing would ever be copied.
     * Both halves below therefore pass {@code ignoreDefault}. The same reason
     * stops a section the file lacks being read from the defaults and written
     * into — that would edit the jar's copy, not the server's.
     */
    private static boolean merge(ConfigurationSection from, ConfigurationSection into) {
        boolean added = false;
        for (String key : from.getKeys(false)) {
            ConfigurationSection fromChild = from.getConfigurationSection(key);
            if (fromChild != null) {
                ConfigurationSection intoChild = into.contains(key, true)
                        ? into.getConfigurationSection(key) : null;
                if (intoChild == null) {
                    intoChild = into.createSection(key);
                    added = true;
                }
                if (merge(fromChild, intoChild)) {
                    added = true;
                }
                continue;
            }
            if (!into.contains(key, true)) {
                into.set(key, from.get(key));
                added = true;
            }
        }
        return added;
    }

    /** The config.yml this jar ships, or null when it cannot be read. */
    private FileConfiguration shippedConfig() {
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not read the config.yml inside the jar", ex);
            return null;
        }
    }

    /** A GUI slot has to be inside the board (0-53); anything else wraps into it. */
    private static int clampSlot(int slot) {
        return Math.max(0, Math.min(53, slot));
    }

    public Messages messages() {
        return messages;
    }

    public double minimum() {
        return minimum;
    }

    public double maximum() {
        return maximum;
    }

    /** Whether a player may be targeted without being online. */
    public boolean allowOfflineTargets() {
        return !targets.equals("online");
    }

    public boolean allowUnknownTargets() {
        return targets.equals("all");
    }

    public boolean allowSelfBounty() {
        return allowSelfBounty;
    }

    public boolean selfBountyEnabled() {
        return selfBountyEnabled;
    }

    public int selfBountyRefundPercent() {
        return selfBountyRefundPercent;
    }

    public boolean personalAdded() {
        return personalAdded;
    }

    public boolean personalClaimed() {
        return personalClaimed;
    }

    public boolean personalReceived() {
        return personalReceived;
    }

    public boolean broadcastAdded() {
        return broadcastAdded;
    }

    public boolean broadcastClaimed() {
        return broadcastClaimed;
    }

    /** The stored default for one of the three personal notification switches. */
    public boolean personalDefault(String settingKey) {
        return switch (settingKey) {
            case "bounty-claimed" -> personalClaimed;
            case "bounty-received" -> personalReceived;
            default -> personalAdded;
        };
    }

    public String guiTitle() {
        return guiTitle;
    }

    public Material frameMaterial() {
        return frameMaterial;
    }

    /** How many bounty heads one page shows (the board's grid holds 21). */
    public int entriesPerPage() {
        return entriesPerPage;
    }

    public int selfSlot() {
        return selfSlot;
    }

    public int sortingSlot() {
        return sortingSlot;
    }

    public int previousSlot() {
        return previousSlot;
    }

    public int nextSlot() {
        return nextSlot;
    }

    public int pageSlot() {
        return pageSlot;
    }

    public int closeSlot() {
        return closeSlot;
    }

    public double quickAddAmount() {
        return quickAddAmount;
    }

    public boolean antiAbuseEnabled() {
        return antiAbuseEnabled;
    }

    public long killCooldownSeconds() {
        return antiAbuseEnabled ? killCooldownSeconds : 0L;
    }

    public boolean preventRepeatedKills() {
        return antiAbuseEnabled && preventRepeatedKills;
    }

    public String storageFile() {
        return storageFile;
    }

    public int saveIntervalSeconds() {
        return saveIntervalSeconds;
    }

    public String currencySymbol() {
        return currencySymbol;
    }

    public boolean thousandsSeparator() {
        return thousandsSeparator;
    }

    // ── Money as players read it ────────────────────────────────────────

    /**
     * An amount with the configured symbol and separators: {@code $10,000},
     * {@code $1,250.50}, {@code $750}. Whole numbers lose the decimals.
     */
    public String format(double amount) {
        double rounded = Math.round(amount * 100.0) / 100.0;
        // Locale.US so a server with a European locale still prints $10,000.50
        // rather than $10.000,50.
        String number = rounded == Math.floor(rounded)
                ? String.format(Locale.US, thousandsSeparator ? "%,d" : "%d", (long) rounded)
                : String.format(Locale.US, thousandsSeparator ? "%,.2f" : "%.2f", rounded);
        return currencySymbol + number;
    }
}
