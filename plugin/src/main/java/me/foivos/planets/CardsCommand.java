package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Routing for the card event's commands, kept apart from the event itself.
 *
 * <ul>
 *   <li>{@code /cards} and {@code /c} — the same menu, two ways in.</li>
 *   <li>{@code /cardeventduration set <hours>} — the admin timer control.</li>
 * </ul>
 *
 * <p>The duration command only ever moves the <b>end timestamp</b>, so what it
 * writes is what a restart reads: setting 168 hours sets an end 168 hours from
 * now and every screen everywhere agrees on the same countdown.
 */
final class CardsCommand {

    private static final List<String> MAIN_SUBCOMMANDS =
            List.of("collection", "info", "leaderboard", "timer", "help");
    private static final List<String> DURATION_SUBCOMMANDS = List.of("set", "info", "extend");
    /** The lengths worth offering in tab completion: a day, a week, a month. */
    private static final List<String> EXAMPLE_HOURS = List.of("24", "72", "168", "336", "720");

    private final Planets plugin;
    private final CardService cards;

    CardsCommand(Planets plugin, CardService cards) {
        this.plugin = plugin;
        this.cards = cards;
    }

    /** The permission that opens the event menus (also declared in plugin.yml). */
    private static final String USE_PERMISSION = "planets.cards";

    // ── /cards and /c ───────────────────────────────────────────────────

    boolean handleCards(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can open the card collection.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendMessage(Component.text("You don't have permission to use the card collection.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            player.closeInventory();
            cards.openMain(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "collection", "cards", "list" -> {
                player.closeInventory();
                cards.openCollection(player);
            }
            case "info", "book", "help", "?" -> {
                player.closeInventory();
                cards.openInfo(player);
            }
            case "leaderboard", "top", "lb" -> {
                player.closeInventory();
                cards.openLeaderboard(player);
            }
            case "timer", "time" -> cards.printTimer(player);
            default -> {
                player.closeInventory();
                cards.openMain(player);
            }
        }
        return true;
    }

    // ── /cardeventduration ──────────────────────────────────────────────

    boolean handleDuration(CommandSender sender, String[] args) {
        if (!canManage(sender)) {
            sender.sendMessage(Component.text("You don't have permission to change the event duration.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            printStatus(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("info") || sub.equals("status")) {
            printStatus(sender);
            return true;
        }
        if (sub.equals("set") || sub.equals("extend")) {
            if (args.length < 2) {
                sender.sendMessage(Component.text("Usage: /cardeventduration set <hours>")
                        .color(NamedTextColor.YELLOW));
                return true;
            }
            double hours;
            try {
                hours = Double.parseDouble(args[1].trim().replace("h", ""));
            } catch (NumberFormatException notANumber) {
                sender.sendMessage(Component.text("\"" + args[1] + "\" is not a number of hours.")
                        .color(NamedTextColor.RED));
                return true;
            }
            if (hours <= 0) {
                sender.sendMessage(Component.text("The duration must be more than 0 hours.")
                        .color(NamedTextColor.RED));
                return true;
            }
            if (sub.equals("extend")) {
                // "extend" adds to whatever is left rather than restarting it.
                long base = cards.hasEnd() && cards.isActive()
                        ? cards.endsAt() : System.currentTimeMillis();
                cards.setEndsAt(base + (long) (hours * 3_600_000L));
            } else {
                cards.setDurationHours(hours);
            }
            announce(sender, hours);
            return true;
        }
        sender.sendMessage(Component.text("Usage: /cardeventduration <set <hours>|info>")
                .color(NamedTextColor.YELLOW));
        return true;
    }

    private boolean canManage(CommandSender sender) {
        return sender.hasPermission(cards.adminPermission()) || sender.isOp();
    }

    private void announce(CommandSender sender, double hours) {
        sender.sendMessage(Component.text("\u23F0 Entity Card Hunt now ends in ")
                .color(NamedTextColor.GREEN)
                .append(Component.text(formatHours(hours)).color(NamedTextColor.GOLD))
                .append(Component.text(" (" + cards.remainingShort() + ").").color(NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("Ends at " + Planets.formatDate(cards.endsAt()))
                .color(NamedTextColor.GRAY));
        plugin.getLogger().info(sender.getName() + " set the Entity Card Hunt duration to "
                + formatHours(hours) + " (ends " + Planets.formatDate(cards.endsAt()) + ").");
    }

    private void printStatus(CommandSender sender) {
        sender.sendMessage(Component.text("\uD83C\uDCCF Entity Card Hunt").color(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        sender.sendMessage(Component.text("  Cards: ").color(NamedTextColor.GRAY)
                .append(Component.text(cards.registry().size() + " ("
                        + cards.registry().obtainableCount() + " obtainable)")
                        .color(NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("  Status: ").color(NamedTextColor.GRAY)
                .append(Component.text(cards.isActive() ? "RUNNING" : "ENDED")
                        .color(cards.isActive() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        sender.sendMessage(Component.text("  Remaining: ").color(NamedTextColor.GRAY)
                .append(Component.text(cards.remainingShort()).color(NamedTextColor.YELLOW)));
        if (cards.hasEnd()) {
            sender.sendMessage(Component.text("  Ends at: ").color(NamedTextColor.GRAY)
                    .append(Component.text(Planets.formatDate(cards.endsAt()))
                            .color(NamedTextColor.LIGHT_PURPLE)));
        }
        sender.sendMessage(Component.text("  Players collecting: ").color(NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(cards.participants()))
                        .color(NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("  Duration: ").color(NamedTextColor.GRAY)
                .append(Component.text("cards.event-duration-hours = "
                                + plugin.getConfig().getDouble("cards.event-duration-hours", 336.0)
                                + " (used only when no end is set)")
                        .color(NamedTextColor.DARK_GRAY)));
    }

    private static String formatHours(double hours) {
        if (hours == Math.rint(hours)) {
            long whole = (long) hours;
            return whole + (whole == 1 ? " hour" : " hours");
        }
        return hours + " hours";
    }

    // ── Tab completion ──────────────────────────────────────────────────

    List<String> tabComplete(CommandSender sender, String commandName, String[] args) {
        if (commandName.equals("cardeventduration")) {
            if (!canManage(sender)) {
                return List.of();
            }
            if (args.length <= 1) {
                return keep(DURATION_SUBCOMMANDS, args.length == 0 ? "" : args[0]);
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("set")
                    || args[0].equalsIgnoreCase("extend"))) {
                return keep(EXAMPLE_HOURS, args[1]);
            }
            return List.of();
        }
        if (!sender.hasPermission(USE_PERMISSION)) {
            return List.of();
        }
        if (args.length <= 1) {
            return keep(MAIN_SUBCOMMANDS, args.length == 0 ? "" : args[0]);
        }
        return List.of();
    }

    private static List<String> keep(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower) && !result.contains(option)) {
                result.add(option);
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }
}
