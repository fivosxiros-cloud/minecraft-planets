package me.foivos.bounty.listener;

import me.foivos.bounty.BountyPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Claiming a bounty: one player killing another who has money on their head.
 *
 * <p>Only a real player-versus-player kill counts — {@code getKiller()} has to
 * be a player, so a fall, a mob, a trap, lava, the void, or the victim dying to
 * themselves are all just deaths and leave the bounty exactly where it was.
 *
 * <p>The order of the payout matters and is deliberate:
 * <ol>
 *   <li>the anti-abuse check runs first, and a blocked claim leaves the bounty
 *       standing rather than eating it;</li>
 *   <li>the killer is paid <em>before</em> the bounty is cleared, so an economy
 *       that refuses the payment cannot lose anybody their money;</li>
 *   <li>the victim's own contribution is refunded after that, at the percentage
 *       config.yml asks for;</li>
 *   <li>only then is the bounty forgotten, and it is written out of the
 *       database on the next flush.</li>
 * </ol>
 */
public final class BountyKillListener implements Listener {

    private final BountyPlugin plugin;

    public BountyKillListener(BountyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return; // not a kill by somebody else
        }
        UUID victimId = victim.getUniqueId();
        if (!plugin.bounties().has(victimId)) {
            return; // nothing on their head
        }
        long cooldown = plugin.bountyConfig().killCooldownSeconds();
        if (plugin.bountyConfig().preventRepeatedKills()
                && plugin.antiAbuse().blocked(killer.getUniqueId(), victimId, cooldown)) {
            plugin.messages().send(killer, "claim.blocked", Map.of(
                    "player", victim.getName(),
                    "amount", plugin.bountyConfig().format(plugin.bounties().total(victimId)),
                    "seconds", String.valueOf(plugin.antiAbuse()
                            .remainingSeconds(killer.getUniqueId(), victimId, cooldown))));
            return;
        }

        double amount = plugin.bounties().total(victimId);
        if (amount <= 0) {
            return;
        }
        if (!plugin.economy().available()) {
            plugin.messages().send(killer, "economy-missing", Map.of());
            return;
        }
        double refund = refundFor(victimId, amount);

        if (!plugin.economy().deposit(killer, amount)) {
            // The bounty is untouched: it can be claimed again later.
            plugin.messages().send(killer, "claim.failed",
                    Map.of("player", victim.getName(),
                            "amount", plugin.bountyConfig().format(amount)));
            return;
        }

        plugin.bounties().clear(victimId);
        if (refund > 0 && !plugin.economy().deposit(victim, refund)) {
            plugin.getLogger().log(Level.WARNING, "Could not refund " + refund + " to "
                    + victim.getName() + " for their own bounty.");
        }
        plugin.antiAbuse().record(killer.getUniqueId(), victimId);
        plugin.notifications().claimed(killer, victim, victim.getName(), amount, refund);
        plugin.getLogger().info(killer.getName() + " claimed " + victim.getName()
                + "'s bounty of " + plugin.bountyConfig().format(amount)
                + (refund > 0 ? " (" + plugin.bountyConfig().format(refund) + " refunded)" : ""));
    }

    /**
     * What the victim gets back out of their own money, or 0 when self-bounty
     * refunds are switched off. It can never be more than the bounty itself.
     */
    private double refundFor(UUID victimId, double amount) {
        if (!plugin.bountyConfig().selfBountyEnabled()
                || plugin.bountyConfig().selfBountyRefundPercent() <= 0) {
            return 0.0;
        }
        double own = Math.min(plugin.bounties().selfTotal(victimId), amount);
        if (own <= 0) {
            return 0.0;
        }
        double refund = own * plugin.bountyConfig().selfBountyRefundPercent() / 100.0;
        return Math.round(refund * 100.0) / 100.0;
    }
}
