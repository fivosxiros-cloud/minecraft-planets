package me.foivos.bounty.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.foivos.bounty.BountyPlugin;
import me.foivos.bounty.bounty.BountyPlacer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "how much?" half of the board's Add Bounty button: the next line the
 * player types is the amount, and nothing else happens to it.
 *
 * <p>The answer is read on the chat thread (that is where a chat message
 * arrives) and acted on back on the server thread, because placing a bounty
 * moves money and talks to Vault. The prompt is taken out of the map as soon as
 * it is read, so a second line typed afterwards is ordinary chat again — and
 * "cancel" walks away without placing anything.
 */
public final class BountyChatListener implements Listener {

    private final BountyPlugin plugin;
    /** uuid -> the bounty whose amount this player is answering for. */
    private final Map<UUID, UUID> pending = new ConcurrentHashMap<>();

    public BountyChatListener(BountyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Whether this player owes the board an amount. */
    public boolean hasPrompt(Player player) {
        return player != null && pending.containsKey(player.getUniqueId());
    }

    /** Forgets a prompt, so walking away cannot leave one armed. */
    public void clearPrompt(UUID uuid) {
        pending.remove(uuid);
    }

    /** Asks for an amount, closing whatever screen asked. */
    public void promptAmount(Player player, UUID target) {
        if (player == null || target == null) {
            return;
        }
        pending.put(player.getUniqueId(), target);
        player.closeInventory();
        plugin.messages().send(player, "prompt.amount", Map.of(
                "player", plugin.displayName(target),
                "minimum", plugin.bountyConfig().format(plugin.bountyConfig().minimum()),
                "maximum", plugin.bountyConfig().format(plugin.bountyConfig().maximum())));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID target = pending.remove(player.getUniqueId());
        if (target == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> answer(player, target, input));
    }

    private void answer(Player player, UUID target, String input) {
        if (!player.isOnline()) {
            return;
        }
        if (input.isEmpty() || input.equalsIgnoreCase("cancel")) {
            plugin.messages().send(player, "prompt.cancelled", Map.of());
            return;
        }
        double amount = BountyPlacer.parseAmount(input);
        if (Double.isNaN(amount)) {
            plugin.messages().send(player, "amount-invalid", Map.of("input", input));
            return;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
        String name = plugin.displayName(target);
        BountyPlacer.Attempt attempt = plugin.placer().place(player, offline, name, amount);
        plugin.feedback().placement(player, attempt, input);
    }
}
