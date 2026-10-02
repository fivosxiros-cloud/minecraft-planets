package me.foivos.planets.casino;

import me.foivos.planets.Planets;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * The money side of a wagering game.
 *
 * <p>The rest of the casino pays in cosmetics, so nothing else here touches a
 * balance. The games that do — the ones where players put coins on the table —
 * go through this class instead of calling Vault directly, so that every stake
 * is taken the same way and every failure has one place to be noticed.
 *
 * <p>Every method is a no-op that answers {@code false} when Vault is absent,
 * so a wagering game degrades to "you cannot bet right now" rather than an
 * exception.
 */
public final class CasinoWager {

    private final Planets plugin;

    public CasinoWager(Planets plugin) {
        this.plugin = plugin;
    }

    /** Whether money exists to bet with at all. */
    public boolean available() {
        return plugin.hasEconomy();
    }

    /** A player's balance, or 0 when there is no economy to read. */
    public double balance(OfflinePlayer player) {
        Economy economy = plugin.economy();
        if (economy == null || player == null) {
            return 0.0;
        }
        return economy.getBalance(player);
    }

    /** Whether a player can cover a stake of this size. */
    public boolean canAfford(OfflinePlayer player, double amount) {
        return amount > 0 && balance(player) >= amount;
    }

    /**
     * Takes a stake out of a player's balance. All or nothing: on failure the
     * balance is untouched and no money is owed.
     */
    public boolean take(OfflinePlayer player, double amount) {
        Economy economy = plugin.economy();
        if (economy == null || player == null || amount <= 0) {
            return false;
        }
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    /** Pays money to a player, offline or not. */
    public boolean give(OfflinePlayer player, double amount) {
        Economy economy = plugin.economy();
        if (economy == null || player == null || amount <= 0) {
            return false;
        }
        return economy.depositPlayer(player, amount).transactionSuccess();
    }

    /** Gives a stake back: the same as {@link #give}, said in the player's terms. */
    public boolean refund(OfflinePlayer player, double amount) {
        return give(player, amount);
    }

    /**
     * Moves a stake from one player to another in one step, for the places
     * where a botched pair of take/give would quietly mint or burn money.
     */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, double amount) {
        if (!take(from, amount)) {
            return false;
        }
        if (give(to, amount)) {
            return true;
        }
        // The payout failed, so undo the stake rather than lose it.
        give(from, amount);
        return false;
    }

    /**
     * The casino's own money shorthand: {@code 1.27k}, {@code 2.1M},
     * {@code 45}. Read at a glance in a lore line, which is all it is for.
     */
    public static String money(double amount) {
        double abs = Math.abs(amount);
        if (abs >= 1_000_000_000d) {
            return trim(amount / 1_000_000_000d) + "B";
        }
        if (abs >= 1_000_000d) {
            return trim(amount / 1_000_000d) + "M";
        }
        if (abs >= 1_000d) {
            return trim(amount / 1_000d) + "k";
        }
        return String.valueOf((long) amount);
    }

    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
