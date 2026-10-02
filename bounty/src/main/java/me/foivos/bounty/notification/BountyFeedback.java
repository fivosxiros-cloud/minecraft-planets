package me.foivos.bounty.notification;

import me.foivos.bounty.BountyPlugin;
import me.foivos.bounty.bounty.BountyPlacer;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns the outcome of a placement into the one line the player who tried it
 * should read: placed, too small, too big, not enough money, and so on.
 *
 * <p>It exists so {@code /bounty add}, the board's chat prompt and its
 * right-click quick add all explain themselves in exactly the same words, and
 * so those words stay in config.yml like every other message.
 */
public final class BountyFeedback {

    private final BountyPlugin plugin;

    public BountyFeedback(BountyPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reports one placement attempt.
     *
     * @param rawInput what the player typed, so a bad amount can be quoted back
     */
    public void placement(Player actor, BountyPlacer.Attempt attempt, String rawInput) {
        if (actor == null || attempt == null) {
            return;
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", attempt.targetName());
        placeholders.put("amount", plugin.bountyConfig().format(attempt.amount()));
        placeholders.put("previous", plugin.bountyConfig().format(attempt.previous()));
        placeholders.put("total", plugin.bountyConfig().format(attempt.total()));
        placeholders.put("minimum", plugin.bountyConfig().format(plugin.bountyConfig().minimum()));
        placeholders.put("maximum", plugin.bountyConfig().format(plugin.bountyConfig().maximum()));
        placeholders.put("balance", plugin.bountyConfig().format(plugin.economy().balance(actor)));
        placeholders.put("input", rawInput == null ? "" : rawInput);

        switch (attempt.outcome()) {
            case PLACED -> plugin.messages().send(actor, "bounty-added.sender", placeholders);
            case NO_ECONOMY -> plugin.messages().send(actor, "economy-missing", placeholders);
            case NOT_AN_AMOUNT -> plugin.messages().send(actor, "amount-invalid", placeholders);
            case BELOW_MINIMUM -> plugin.messages().send(actor, "amount-minimum", placeholders);
            case ABOVE_MAXIMUM -> plugin.messages().send(actor, "amount-maximum", placeholders);
            case NOT_ENOUGH_MONEY -> plugin.messages().send(actor, "not-enough-money", placeholders);
            case SELF_NOT_ALLOWED -> plugin.messages().send(actor, "self-not-allowed", placeholders);
            case FAILED -> plugin.messages().send(actor, "place-failed", placeholders);
        }
    }
}
