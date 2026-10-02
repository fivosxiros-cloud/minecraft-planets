package me.foivos.bounty.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.foivos.bounty.BountyPlugin;
import me.foivos.bounty.bounty.BountyPlacer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The dialog a player types a bounty amount into.
 *
 * <p>This is a real client dialog, not a chest full of buttons: one text box,
 * a label, and an Add button. The player types the figure — {@code 2500},
 * {@code 2.5k}, whatever {@link BountyPlacer#parseAmount} understands — and
 * pressing Add places it, exactly as if the amount had been typed after
 * {@code /bounty add}.
 *
 * <p>It is opened by a left-click on the Add button of a bounty's own screen,
 * and by {@code /bounty add <player>} with no amount on it.
 *
 * <p>Every string it draws has wording of its own to fall back on. A message
 * key that config.yml has never heard of would otherwise render as nothing at
 * all, and a dialog with no title, no label and no buttons is indistinguishable
 * from a broken one.
 */
public final class AmountDialog {

    /** The key the typed amount comes back under. */
    private static final String FIELD = "amount";
    /** How wide the input box and each button are drawn, in pixels. */
    private static final int FIELD_WIDTH = 200;
    private static final int BUTTON_WIDTH = 120;
    /** Room for a large amount, written out with separators. */
    private static final int MAX_LENGTH = 24;
    /** How long the buttons stay live, in case the dialog sits open a while. */
    private static final Duration LIFETIME = Duration.ofMinutes(10);

    private AmountDialog() {
    }

    /** Opens the typing dialog for one player's bounty. */
    public static void open(BountyPlugin plugin, Player viewer, UUID target) {
        String name = plugin.displayName(target);

        Component title = text(plugin, "gui.dialog.title",
                "&6&lAdd to %player%&6&l's bounty", Map.of("player", name));
        Component body = text(plugin, "gui.dialog.body",
                "&7Type how much to add to &f%player%&7's bounty."
                        + "\n&7Anything from &f%minimum%&7 to &f%maximum%&7."
                        + "\n&7Your balance: &f%balance%",
                Map.of("player", name,
                        "minimum", plugin.bountyConfig().format(plugin.bountyConfig().minimum()),
                        "maximum", plugin.bountyConfig().format(plugin.bountyConfig().maximum()),
                        "balance", plugin.bountyConfig().format(plugin.economy().balance(viewer))));
        Component label = text(plugin, "gui.dialog.field", "&eAmount", Map.of());
        Component confirm = text(plugin, "gui.dialog.confirm", "&a&lAdd the bounty", Map.of());
        Component cancel = text(plugin, "gui.dialog.cancel", "&7Cancel", Map.of());
        Component confirmTip = text(plugin, "gui.dialog.confirm-tip",
                "&7Takes the money from your balance", Map.of());

        // One use per button, because a click callback that outlives the dialog
        // is a click callback that could place the same bounty twice.
        ClickCallback.Options once = ClickCallback.Options.builder()
                .uses(1)
                .lifetime(LIFETIME)
                .build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .body(List.of(DialogBody.plainMessage(body)))
                        .inputs(List.of(DialogInput.text(FIELD, label)
                                .width(FIELD_WIDTH)
                                .maxLength(MAX_LENGTH)
                                .build()))
                        .canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(
                        List.of(ActionButton.builder(confirm)
                                .tooltip(confirmTip)
                                .width(BUTTON_WIDTH)
                                .action(DialogAction.customClick(
                                        (response, audience) -> submit(plugin, target,
                                                response.getText(FIELD), audience),
                                        once))
                                .build()),
                        ActionButton.builder(cancel)
                                .width(BUTTON_WIDTH)
                                .action(DialogAction.customClick((response, audience) -> {
                                    // Cancelling is leaving the dialog alone.
                                }, once))
                                .build(),
                        1)));

        viewer.showDialog(dialog);
    }

    /**
     * Places the bounty the player typed, or says why it was not placed. The
     * dialog itself has already closed by the time this runs.
     */
    private static void submit(BountyPlugin plugin, UUID target, String typed, Audience audience) {
        if (!(audience instanceof Player player) || !player.isOnline()) {
            return;
        }
        if (!plugin.economy().available()) {
            plugin.messages().send(player, "economy-missing", Map.of());
            return;
        }
        String input = typed == null ? "" : typed.trim();
        if (input.isEmpty()) {
            say(player, plugin, "gui.prompt.empty", "&7Nothing was entered.");
            return;
        }
        double amount = BountyPlacer.parseAmount(input);
        if (Double.isNaN(amount) || amount <= 0) {
            say(player, plugin, "amount-invalid", "&f%input%&c is not an amount of money.",
                    Map.of("input", input));
            return;
        }
        double minimum = plugin.bountyConfig().minimum();
        double maximum = plugin.bountyConfig().maximum();
        if (amount < minimum) {
            say(player, plugin, "gui.prompt.too-low",
                    "&c%input%&c is below the smallest bounty of &f%minimum%&c.",
                    Map.of("input", plugin.bountyConfig().format(amount),
                            "minimum", plugin.bountyConfig().format(minimum)));
            return;
        }
        if (amount > maximum) {
            say(player, plugin, "gui.prompt.too-high",
                    "&c%input%&c is above the largest bounty of &f%maximum%&c.",
                    Map.of("input", plugin.bountyConfig().format(amount),
                            "maximum", plugin.bountyConfig().format(maximum)));
            return;
        }

        BountyPlacer.Attempt attempt = plugin.placer().place(player,
                Bukkit.getOfflinePlayer(target), plugin.displayName(target), amount);
        plugin.feedback().placement(player, attempt, plugin.bountyConfig().format(amount));
        if (attempt.placed()) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
            new BountyDetailsGUI(plugin, player, target).open(player);
        }
    }

    // ── Wording ─────────────────────────────────────────────────────────

    /** A message from config.yml, or this dialog's own wording for it. */
    private static Component text(BountyPlugin plugin, String key, String fallback,
                                  Map<String, String> placeholders) {
        if (plugin.messages().has(key)) {
            return plugin.messages().component(key, placeholders);
        }
        return plugin.messages().of(fill(fallback, placeholders));
    }

    /** The same, said in chat. */
    private static void say(Player player, BountyPlugin plugin, String key, String fallback) {
        say(player, plugin, key, fallback, Map.of());
    }

    private static void say(Player player, BountyPlugin plugin, String key, String fallback,
                            Map<String, String> placeholders) {
        if (plugin.messages().has(key)) {
            plugin.messages().send(player, key, placeholders);
            return;
        }
        player.sendMessage(plugin.messages().of(plugin.messages().first("prefix"))
                .append(plugin.messages().of(fill(fallback, placeholders))));
    }

    private static String fill(String raw, Map<String, String> placeholders) {
        String filled = raw == null ? "" : raw;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            filled = filled.replace("%" + entry.getKey() + "%",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return filled;
    }
}
