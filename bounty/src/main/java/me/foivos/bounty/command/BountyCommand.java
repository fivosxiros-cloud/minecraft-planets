package me.foivos.bounty.command;

import me.foivos.bounty.BountyPlugin;
import me.foivos.bounty.bounty.Bounty;
import me.foivos.bounty.bounty.BountyPlacer;
import me.foivos.bounty.gui.AmountDialog;
import me.foivos.bounty.gui.BountyGUI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /bounty} — and its aliases {@code /b}, {@code /bounties}.
 *
 * <pre>
 *   /bounty                       the board
 *   /bounty add &lt;player&gt; &lt;amount&gt;  put money on somebody (your own name works too)
 *   /bounty check &lt;player&gt;        what is on one head
 *   /bounty list                  everybody who has a bounty, biggest first
 *   /bounty reload                re-read config.yml (bounty.admin)
 * </pre>
 *
 * <p>With no arguments it opens the board, so {@code /b} on its own is the
 * screen and {@code /b add ...} is still the command: the first argument is a
 * sub-command when it names one, and a name to check when it does not.
 */
public final class BountyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("add", "check", "list", "reload");

    private final BountyPlugin plugin;

    public BountyCommand(BountyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            openBoard(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> add(sender, args);
            // "check <player>" and a bare "<player>" both read as a name to
            // check — but in the first the name is the *second* argument, so the
            // sub-command passes the offset rather than the raw args.
            case "check" -> check(sender, args, 1);
            case "list" -> list(sender);
            case "reload" -> reload(sender);
            case "help", "?" -> usage(sender);
            default -> {
                // "/bounty <player>" reads as a check, which is what people type.
                check(sender, args, 0);
            }
        }
        return true;
    }

    // ── Sub-commands ────────────────────────────────────────────────────

    private void openBoard(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            usage(sender);
            return;
        }
        if (!player.hasPermission("bounty.use")) {
            plugin.messages().send(player, "no-permission", Map.of());
            return;
        }
        new BountyGUI(plugin, player).open(player);
    }

    private void add(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "no-permission", Map.of());
            return;
        }
        if (!player.hasPermission("bounty.add")) {
            plugin.messages().send(player, "no-permission", Map.of());
            return;
        }
        if (args.length < 2) {
            usage(player);
            return;
        }
        OfflinePlayer target = resolve(player, args[1]);
        if (target == null) {
            return;
        }
        if (args.length < 3) {
            // No amount on the command: type it into the dialog rather than
            // into chat, which is what the board's Add button does too.
            AmountDialog.open(plugin, player, target.getUniqueId());
            return;
        }
        double amount = BountyPlacer.parseAmount(args[2]);
        if (Double.isNaN(amount)) {
            plugin.messages().send(player, "amount-invalid", Map.of("input", args[2]));
            return;
        }
        String targetName = target.getName() == null ? args[1] : target.getName();
        BountyPlacer.Attempt attempt = plugin.placer().place(player, target, targetName, amount);
        plugin.feedback().placement(player, attempt, args[2]);
    }

    /**
     * @param nameIndex where the name sits in {@code args}: 0 for a bare
     *                  {@code /bounty <player>}, 1 for {@code /bounty check
     *                  <player>}.
     */
    private void check(CommandSender sender, String[] args, int nameIndex) {
        if (!sender.hasPermission("bounty.check")) {
            plugin.messages().send(sender, "no-permission", Map.of());
            return;
        }
        if (args.length <= nameIndex) {
            usage(sender);
            return;
        }
        String name = args[nameIndex];
        Player online = Bukkit.getPlayerExact(name);
        UUIDHolder holder = holder(online, name);
        if (holder == null) {
            // Nobody by that name: online, remembered by the board, or on file.
            plugin.messages().send(sender, "player-not-found", Map.of("player", name));
            return;
        }
        double total = plugin.bounties().total(holder.uuid());
        String display = holder.name();
        plugin.messages().send(sender, total > 0 ? "check.has" : "check.none", Map.of(
                "player", display,
                "amount", plugin.bountyConfig().format(total)));
    }

    private void list(CommandSender sender) {
        if (!sender.hasPermission("bounty.list")) {
            plugin.messages().send(sender, "no-permission", Map.of());
            return;
        }
        List<Bounty> bounties = plugin.bounties().sorted(true);
        if (bounties.isEmpty()) {
            plugin.messages().send(sender, "list.empty", Map.of());
            return;
        }
        plugin.messages().send(sender, "list.header", Map.of("count", String.valueOf(bounties.size())));
        int rank = 1;
        for (Bounty bounty : bounties) {
            plugin.messages().sendRaw(sender, "list.line", Map.of(
                    "rank", String.valueOf(rank++),
                    "player", bounty.targetName(),
                    "amount", plugin.bountyConfig().format(bounty.total())));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("bounty.admin")) {
            plugin.messages().send(sender, "no-permission", Map.of());
            return;
        }
        plugin.bountyConfig().reload();
        sender.sendMessage(net.kyori.adventure.text.Component.text(
                        "\u2699 Bounty config reloaded \u00B7 " + plugin.bounties().size()
                                + " bounty(ies) \u00B7 notifications from "
                                + plugin.settings().source() + ".")
                .color(net.kyori.adventure.text.format.NamedTextColor.AQUA));
    }

    private void usage(CommandSender sender) {
        plugin.messages().sendRaw(sender, "usage", Map.of());
    }

    // ── Who the name means ──────────────────────────────────────────────

    private record UUIDHolder(java.util.UUID uuid, String name) {
    }

    /**
     * Turns what a player typed into somebody to put money on. An online player
     * always resolves; an offline one only when config.yml allows it, and a name
     * the server has never seen only when it allows that too.
     */
    private OfflinePlayer resolve(Player player, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        if (!plugin.bountyConfig().allowOfflineTargets()) {
            plugin.messages().send(player, "target-must-be-online", Map.of("player", name));
            return null;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (!offline.hasPlayedBefore() && !plugin.bountyConfig().allowUnknownTargets()) {
            plugin.messages().send(player, "target-unknown", Map.of("player", name));
            return null;
        }
        return offline;
    }

    /** A name to check: online first, then whoever the board already knows. */
    private UUIDHolder holder(Player online, String name) {
        if (online != null) {
            return new UUIDHolder(online.getUniqueId(), online.getName());
        }
        Bounty stored = plugin.bounties().byName(name);
        if (stored != null) {
            return new UUIDHolder(stored.target(), stored.targetName());
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore() && offline.getName() != null) {
            return new UUIDHolder(offline.getUniqueId(), offline.getName());
        }
        return null;
    }

    // ── Tab completion ──────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return matching(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("add")
                || args[0].equalsIgnoreCase("check"))) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                names.add(online.getName());
            }
            return matching(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            return matching(List.of("100", "500", "1000", "5000", "10000"), args[2]);
        }
        return List.of();
    }

    private static List<String> matching(List<String> options, String prefix) {
        String wanted = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        Map<String, String> unique = new HashMap<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(wanted)) {
                unique.putIfAbsent(option, option);
            }
        }
        return new ArrayList<>(unique.values());
    }
}
