package me.foivos.bounty.economy;

import me.foivos.bounty.BountyPlugin;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.logging.Level;

/**
 * Every coin the bounty board moves goes through here, and only here: money is
 * taken out of the placer's balance when a bounty is placed, and paid into the
 * killer's balance when it is claimed. Nothing is created or destroyed by the
 * plugin itself — including the self-bounty refund, which is a real deposit.
 *
 * <p>Vault is asked for whatever economy the server runs, so this works with
 * Essentials, CMI, their own plugin, or anything else that speaks Vault. If no
 * economy answers, every call here says no and the board explains itself rather
 * than pretending money moved.
 */
public final class EconomyManager {

    private final BountyPlugin plugin;
    private Economy economy;

    public EconomyManager(BountyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Finds the server's economy. False means bounties cannot move money. */
    public boolean hook() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().warning("Vault is not installed — bounties wait until it is.");
            return false;
        }
        RegisteredServiceProvider<Economy> registration =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            plugin.getLogger().warning("Vault is installed but no economy plugin answers it —"
                    + " bounties wait until one does.");
            return false;
        }
        this.economy = registration.getProvider();
        plugin.getLogger().info("Using " + economy.getName() + " for bounty money.");
        return true;
    }

    public boolean available() {
        return economy != null;
    }

    /** The economy's name, for the console and for error messages. */
    public String name() {
        return economy == null ? "none" : economy.getName();
    }

    /**
     * Takes money out of a balance. False when they cannot afford it, when the
     * economy refuses, or when there is no economy at all — never when the
     * player has simply gone offline.
     */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null || amount <= 0) {
            return false;
        }
        try {
            if (!economy.has(player, amount)) {
                return false;
            }
            EconomyResponse response = economy.withdrawPlayer(player, amount);
            if (!response.transactionSuccess()) {
                plugin.getLogger().warning("Could not take " + amount + " from "
                        + name(player) + ": " + response.errorMessage);
                return false;
            }
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "The economy failed while taking money from " + name(player), ex);
            return false;
        }
    }

    /** Pays money out. False when the economy refuses, so nothing is lost. */
    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null || amount <= 0) {
            return false;
        }
        try {
            EconomyResponse response = economy.depositPlayer(player, amount);
            if (!response.transactionSuccess()) {
                plugin.getLogger().warning("Could not pay " + amount + " to "
                        + name(player) + ": " + response.errorMessage);
                return false;
            }
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "The economy failed while paying " + name(player), ex);
            return false;
        }
    }

    /** A player's balance, or 0 when the economy cannot answer. */
    public double balance(OfflinePlayer player) {
        if (economy == null || player == null) {
            return 0.0;
        }
        try {
            return Math.max(0.0, economy.getBalance(player));
        } catch (RuntimeException ex) {
            return 0.0;
        }
    }

    private static String name(OfflinePlayer player) {
        return player == null ? "someone" : player.getName() == null
                ? player.getUniqueId().toString() : player.getName();
    }
}
