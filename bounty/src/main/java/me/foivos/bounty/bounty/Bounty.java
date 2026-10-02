package me.foivos.bounty.bounty;

import java.util.UUID;

/**
 * One player's bounty: the money on their head, and how much of it they put
 * there themselves.
 *
 * <p>The second number is private. It exists for one reason — a self-bounty is
 * refunded to its owner when somebody claims it (config:
 * {@code self-bounty.refund-percent}) — and is never shown in the board, in a
 * command, or in any message another player can read.
 *
 * <p>The target is always the UUID. The name is only carried along so an
 * offline player can be listed on the board and named in messages.
 */
public final class Bounty {

    private final UUID target;
    private String targetName;
    private double total;
    private double selfTotal;
    private long updatedAt;

    public Bounty(UUID target, String targetName, double total, double selfTotal, long updatedAt) {
        this.target = target;
        this.targetName = targetName == null || targetName.isBlank() ? "unknown" : targetName;
        this.total = Math.max(0.0, total);
        this.selfTotal = Math.max(0.0, selfTotal);
        this.updatedAt = updatedAt;
    }

    /** A brand-new bounty with nothing on it yet. */
    public static Bounty empty(UUID target, String targetName) {
        return new Bounty(target, targetName, 0.0, 0.0, System.currentTimeMillis());
    }

    public UUID target() {
        return target;
    }

    public String targetName() {
        return targetName;
    }

    /** Keeps the stored name in step with the player's current one. */
    public void targetName(String name) {
        if (name != null && !name.isBlank()) {
            this.targetName = name;
        }
    }

    /** The whole bounty: everything anyone has placed on this player. */
    public double total() {
        return total;
    }

    /** How much of the total this player placed on themselves. */
    public double selfTotal() {
        return selfTotal;
    }

    public long updatedAt() {
        return updatedAt;
    }

    /** Adds money to the bounty and stamps it as changed. */
    public void add(double amount, boolean byTheTarget) {
        if (amount <= 0) {
            return;
        }
        total += amount;
        if (byTheTarget) {
            selfTotal += amount;
        }
        touch();
    }

    /** Empties the bounty (after a claim, or an admin clear). */
    public void clear() {
        total = 0.0;
        selfTotal = 0.0;
        touch();
    }

    public void touch() {
        updatedAt = System.currentTimeMillis();
    }

    /** Whether there is any money left to claim. */
    public boolean active() {
        return total > 0.0;
    }
}
