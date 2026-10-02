package me.foivos.bounty.bounty;

import me.foivos.bounty.BountyPlugin;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * The one place a bounty is actually placed, shared by {@code /bounty add}, the
 * chat prompt behind the board's "Add Bounty" button, and its right-click quick
 * add.
 *
 * <p>It does the checks, takes the money through Vault and records the
 * contribution, in that order: nothing is written to a bounty unless the money
 * has already left the placer's balance, so a failed withdrawal can never
 * create money, and a crash between the two can never take money without
 * recording it.
 *
 * <p>It answers with what happened and lets the caller say it in the words the
 * config asks for; the one thing it announces itself is the new bounty
 * ("Bounty Increased!", the optional broadcast), so that all three entry
 * points tell the world about a new bounty in exactly the same way.
 */
public final class BountyPlacer {

    /** What happened, so the caller can pick the right message. */
    public enum Outcome {
        PLACED,
        /** No Vault economy answered. */
        NO_ECONOMY,
        /** The amount was zero, negative, or not a number it could read. */
        NOT_AN_AMOUNT,
        BELOW_MINIMUM,
        ABOVE_MAXIMUM,
        NOT_ENOUGH_MONEY,
        /** Bounties on yourself are switched off. */
        SELF_NOT_ALLOWED,
        /** The economy refused the withdrawal, so nothing was placed. */
        FAILED
    }

    /** The result of one attempt. */
    public record Attempt(Outcome outcome, double amount, double previous, double total,
                          String targetName) {

        public boolean placed() {
            return outcome == Outcome.PLACED;
        }
    }

    private final BountyPlugin plugin;

    public BountyPlacer(BountyPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Takes {@code amount} from {@code actor} and puts it on {@code target}.
     *
     * @param actor      the player whose balance pays for it
     * @param target     whose head the money goes on (may be the actor)
     * @param targetName the target's name, used for the board and messages
     * @param amount     how much to place
     */
    public Attempt place(Player actor, OfflinePlayer target, String targetName, double amount) {
        if (actor == null || target == null) {
            return new Attempt(Outcome.NOT_AN_AMOUNT, amount, 0, 0, targetName);
        }
        if (!plugin.economy().available()) {
            return new Attempt(Outcome.NO_ECONOMY, amount, 0, 0, targetName);
        }
        if (!(amount > 0) || Double.isNaN(amount) || Double.isInfinite(amount)) {
            return new Attempt(Outcome.NOT_AN_AMOUNT, amount, 0, 0, targetName);
        }
        // bounty.admin is meant for staff fixing a board by hand: it skips the
        // configured range, but never the balance.
        boolean staff = actor.hasPermission("bounty.admin");
        if (!staff && amount < plugin.bountyConfig().minimum()) {
            return new Attempt(Outcome.BELOW_MINIMUM, amount, 0, 0, targetName);
        }
        if (!staff && amount > plugin.bountyConfig().maximum()) {
            return new Attempt(Outcome.ABOVE_MAXIMUM, amount, 0, 0, targetName);
        }
        boolean self = target.getUniqueId().equals(actor.getUniqueId());
        if (self && !plugin.bountyConfig().allowSelfBounty()) {
            return new Attempt(Outcome.SELF_NOT_ALLOWED, amount, 0, 0, targetName);
        }
        double balance = plugin.economy().balance(actor);
        if (balance < amount) {
            return new Attempt(Outcome.NOT_ENOUGH_MONEY, amount, 0, 0, targetName);
        }
        if (!plugin.economy().withdraw(actor, amount)) {
            return new Attempt(Outcome.FAILED, amount, 0, 0, targetName);
        }
        double previous = plugin.bounties().total(target.getUniqueId());
        Bounty bounty = plugin.bounties().add(actor.getUniqueId(), actor.getName(),
                target.getUniqueId(), targetName, amount);
        plugin.notifications().added(actor, target, targetName, amount, previous, bounty.total());
        return new Attempt(Outcome.PLACED, amount, previous, bounty.total(), targetName);
    }

    /** Reads an amount from what a player typed, or NaN when it is not a number. */
    public static double parseAmount(String input) {
        if (input == null) {
            return Double.NaN;
        }
        String cleaned = input.trim().replace(",", "").replace("_", "");
        if (cleaned.startsWith("$")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }
}
