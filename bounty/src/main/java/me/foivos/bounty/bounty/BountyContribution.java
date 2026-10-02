package me.foivos.bounty.bounty;

import java.util.UUID;

/**
 * One player's share of somebody else's bounty: who put money in, and how much
 * they have put in in total.
 *
 * <p>Who paid what is deliberately invisible in game — the board and every
 * command only ever show a target's total. This is kept because the money on a
 * head has to be accounted for somewhere: it is how the plugin knows which part
 * of a claimed bounty was funded by the victim themselves and has to be
 * refunded to them.
 */
public record BountyContribution(UUID contributor, String name, double amount, long updatedAt) {

    public BountyContribution add(double extra, long now) {
        return new BountyContribution(contributor, name, amount + extra, now);
    }
}
