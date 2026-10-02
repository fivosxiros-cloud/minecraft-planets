package me.foivos.planets.casino;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Routing for {@code /casino} and {@code /gambling}.
 *
 * <ul>
 *   <li>{@code /casino} — the hub, the same screen two commands open.</li>
 *   <li>{@code /casino stats} — the player's record and prize cabinet.</li>
 *   <li>{@code /casino reload} — re-reads the {@code casino} section and
 *       reports what it found.</li>
 * </ul>
 *
 * <p>The permission names are the ones declared in plugin.yml, so a server can
 * hand out the hub, the record and the reload switch separately.
 */
public final class CasinoCommand {

    private static final String USE = "planets.casino.use";
    private static final String STATS = "planets.casino.stats";
    private static final String RELOAD = "planets.casino.reload";
    private static final String ADMIN = "planets.casino.admin";

    private static final List<String> SUBCOMMANDS = List.of("stats", "reload", "info", "help");

    private final CasinoManager casino;

    public CasinoCommand(CasinoManager casino) {
        this.casino = casino;
    }

    /** {@code /casino} and {@code /gambling}. */
    public boolean handle(CommandSender sender, String[] args) {
        if (args.length > 0) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("reload")) {
                return reload(sender);
            }
            if (sub.equals("stats") || sub.equals("record") || sub.equals("stats")) {
                return stats(sender);
            }
            if (sub.equals("info") || sub.equals("help") || sub.equals("?")) {
                printHelp(sender);
                return true;
            }
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can open the casino.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission(USE)) {
            noPermission(player);
            return true;
        }
        if (!casino.enabled()) {
            casino.deny(player, "closed", "&c\uD83C\uDFB0 The casino is closed right now.");
            return true;
        }
        player.closeInventory();
        casino.openHub(player);
        return true;
    }

    private boolean stats(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players have a casino record.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission(STATS)) {
            noPermission(player);
            return true;
        }
        player.closeInventory();
        casino.openStats(player);
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!canManage(sender)) {
            sender.sendMessage(Component.text("You don't have permission to reload the casino.")
                    .color(NamedTextColor.RED));
            return true;
        }
        casino.reload();
        sender.sendMessage(Component.text("\uD83C\uDFB0 Casino reloaded: ")
                .color(NamedTextColor.GREEN)
                .append(Component.text(casino.gamesAvailable() + " playable game(s), "
                                + casino.prizes().size() + " prize(s).")
                        .color(NamedTextColor.GOLD)));
        return true;
    }

    private boolean canManage(CommandSender sender) {
        return sender.hasPermission(RELOAD) || sender.hasPermission(ADMIN) || sender.isOp();
    }

    private static void noPermission(Player player) {
        player.sendMessage(Component.text("You don't have permission to use the casino.")
                .color(NamedTextColor.RED));
        CasinoFeedback.deny(player);
    }

    private static void printHelp(CommandSender sender) {
        sender.sendMessage(Component.text("\uD83C\uDFB0 Casino").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        line(sender, "/casino", "open the hub of games");
        line(sender, "/casino stats", "your rounds, streaks, prizes and achievements");
        line(sender, "/casino reload", "re-read the casino section of config.yml");
        line(sender, "/casino info", "what the games are and what they pay");
        sender.sendMessage(Component.text("Every game is free and pays only in cosmetic prizes.")
                .color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
    }

    private static void line(CommandSender sender, String command, String what) {
        sender.sendMessage(Component.text("  " + command + " ").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text("\u2014 " + what).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
    }

    /** Tab completion for both command names. */
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        if (sender.hasPermission(RELOAD) || sender.hasPermission(ADMIN)) {
            options.add("reload");
        }
        if (sender.hasPermission(STATS)) {
            options.add("stats");
        }
        options.add("info");
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(prefix) && !matches.contains(option)) {
                matches.add(option);
            }
        }
        matches.sort(String.CASE_INSENSITIVE_ORDER);
        return matches;
    }

    /** The sub-commands, for the help screen's tab completion. */
    static List<String> subcommands() {
        return SUBCOMMANDS;
    }
}
