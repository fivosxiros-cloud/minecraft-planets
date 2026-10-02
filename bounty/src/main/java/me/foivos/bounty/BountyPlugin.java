package me.foivos.bounty;

import me.foivos.bounty.bounty.AntiAbuseGuard;
import me.foivos.bounty.bounty.Bounty;
import me.foivos.bounty.bounty.BountyManager;
import me.foivos.bounty.bounty.BountyPlacer;
import me.foivos.bounty.command.BountyCommand;
import me.foivos.bounty.config.BountyConfig;
import me.foivos.bounty.config.Messages;
import me.foivos.bounty.economy.EconomyManager;
import me.foivos.bounty.gui.BountySort;
import me.foivos.bounty.hud.BountyHudService;
import me.foivos.bounty.gui.GUIListener;
import me.foivos.bounty.listener.BountyChatListener;
import me.foivos.bounty.listener.BountyKillListener;
import me.foivos.bounty.listener.BountyPlayerListener;
import me.foivos.bounty.notification.BountyFeedback;
import me.foivos.bounty.notification.BountyNotificationManager;
import me.foivos.bounty.settings.BountySettings;
import me.foivos.bounty.storage.BountyStorage;
import me.foivos.bounty.storage.SQLiteBountyStorage;
import me.foivos.bounty.storage.StorageException;
import me.foivos.planets.api.PlanetariumHudService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The bounty board.
 *
 * <p>Wiring, and nothing else: this class holds the pieces, starts them in the
 * order they depend on each other, and stops them in the reverse order. The
 * gameplay lives in {@code bounty}, the screens in {@code gui}, the money in
 * {@code economy}, the two storage-facing classes in {@code storage}, the
 * listeners in {@code listener}, the wording in {@code config} — so a later
 * feature only has to reach for the piece it needs.
 *
 * <p>Its two optional neighbours are handled without either of them being
 * required: Vault for the money (without it the board says so and moves
 * nothing) and the Planets plugin for the {@code /settings} page the three
 * notification switches are shown on (without it those switches come from
 * config.yml).
 */
public final class BountyPlugin extends JavaPlugin {

    private BountyConfig bountyConfig;
    private EconomyManager economy;
    private BountyStorage storage;
    private BountyManager bounties;
    private BountyPlacer placer;
    private BountySettings settings;
    private BountyNotificationManager notifications;
    private BountyFeedback feedback;
    private BountyChatListener chat;
    private AntiAbuseGuard antiAbuse;
    private BukkitTask saveTask;

    /** uuid -> the way that player last asked the board to sort itself. */
    private final Map<UUID, BountySort> sorting = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bountyConfig = new BountyConfig(this);
        bountyConfig.reload();

        economy = new EconomyManager(this);
        economy.hook();
        // Said out loud on purpose: every money path in this plugin — including
        // the amount dialog — needs an economy, and "I can't add to a bounty and
        // nothing happens" is almost always a server with no Vault economy on it.
        if (economy.available()) {
            getLogger().info("Economy hooked: " + economy.name()
                    + " — bounties and the amount dialog are open.");
        } else {
            getLogger().warning("No Vault economy found: bounties are closed, and"
                    + " the amount dialog will refuse to open. Install Vault and an"
                    + " economy plugin to switch them on.");
        }

        storage = new SQLiteBountyStorage(new File(getDataFolder(), bountyConfig.storageFile()),
                getLogger());
        bounties = new BountyManager(this, storage);
        try {
            int loaded = bounties.load();
            getLogger().info("Loaded " + loaded + " bounty(ies).");
        } catch (StorageException ex) {
            getLogger().log(Level.SEVERE,
                    "Could not read the bounty database — starting with an empty board", ex);
        }

        placer = new BountyPlacer(this);
        feedback = new BountyFeedback(this);
        notifications = new BountyNotificationManager(this);
        antiAbuse = new AntiAbuseGuard();
        settings = new BountySettings(this);
        settings.hook();

        // Publish the totals to the Planets sidebar (%bounty%), when Planets is
        // there to read them. Guarded so a server without Planets never loads
        // the api class at all.
        if (getServer().getPluginManager().getPlugin("planets") != null) {
            try {
                getServer().getServicesManager().register(PlanetariumHudService.class,
                        new BountyHudService(this), this, ServicePriority.Normal);
                getLogger().info("Bounty totals published to the Planets sidebar.");
            } catch (RuntimeException | LinkageError ex) {
                getLogger().log(Level.WARNING,
                        "Could not publish bounty totals to the Planets sidebar", ex);
            }
        }

        chat = new BountyChatListener(this);
        getServer().getPluginManager().registerEvents(new GUIListener(), this);
        getServer().getPluginManager().registerEvents(new BountyKillListener(this), this);
        getServer().getPluginManager().registerEvents(new BountyPlayerListener(this), this);
        getServer().getPluginManager().registerEvents(chat, this);

        PluginCommand command = getCommand("bounty");
        if (command != null) {
            BountyCommand executor = new BountyCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning("The /bounty command is missing from plugin.yml.");
        }

        // Bounties change rarely, so they are written out on a timer instead of
        // on every click; the last change before a stop is written on disable.
        long interval = Math.max(1, bountyConfig.saveIntervalSeconds()) * 20L;
        saveTask = getServer().getScheduler().runTaskTimer(this, this::flush, interval, interval);
    }

    @Override
    public void onDisable() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (bounties != null) {
            int pending = bounties.pendingWrites();
            flush();
            getLogger().info(pending == 0
                    ? "The bounty board was already written out."
                    : "Saved " + pending + " changed bounty(ies).");
        }
        if (storage != null) {
            try {
                storage.close();
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Could not close the bounty database", ex);
            }
        }
        antiAbuse = null;
    }

    /** Writes out everything that changed since the last pass. */
    public void flush() {
        if (bounties == null) {
            return;
        }
        try {
            bounties.flush();
        } catch (StorageException ex) {
            getLogger().log(Level.SEVERE, "Could not write the bounties out", ex);
        }
    }

    // ── The pieces ──────────────────────────────────────────────────────

    public BountyConfig bountyConfig() {
        return bountyConfig;
    }

    public Messages messages() {
        return bountyConfig.messages();
    }

    public EconomyManager economy() {
        return economy;
    }

    public BountyManager bounties() {
        return bounties;
    }

    public BountyPlacer placer() {
        return placer;
    }

    public BountySettings settings() {
        return settings;
    }

    public BountyNotificationManager notifications() {
        return notifications;
    }

    public BountyFeedback feedback() {
        return feedback;
    }

    public BountyChatListener chat() {
        return chat;
    }

    public AntiAbuseGuard antiAbuse() {
        return antiAbuse;
    }

    // ── Small shared lookups ────────────────────────────────────────────

    /** How this player last wanted the board sorted. */
    public BountySort sortOf(UUID uuid) {
        return sorting.getOrDefault(uuid, BountySort.HIGHEST_FIRST);
    }

    public void rememberSort(UUID uuid, BountySort sort) {
        if (uuid != null && sort != null) {
            sorting.put(uuid, sort);
        }
    }

    /**
     * The best name for a UUID the plugin has: the live one when they are
     * online, the name stored with their bounty when they are not, and whatever
     * the server has on file after that.
     */
    public String displayName(UUID uuid) {
        if (uuid == null) {
            return "unknown";
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        Bounty bounty = bounties.bounty(uuid);
        if (bounty != null) {
            return bounty.targetName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name == null ? "unknown" : name;
    }
}
