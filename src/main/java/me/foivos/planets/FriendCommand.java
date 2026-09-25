package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Routing for {@code /friend} and {@code /friends}, kept out of the plugin
 * class so the social commands and the social logic stay apart.
 *
 * <p>{@code /friend <player>} is the everyday command: it sends a request, or
 * opens that friend's profile when the two are already friends. The named
 * sub-commands cover the rest.
 */
final class FriendCommand {

    private static final List<String> SUBCOMMANDS = List.of(
            "accept", "deny", "remove", "cancel", "gift", "profile",
            "search", "requests", "activity", "settings", "list", "help");

    private final Planets plugin;
    private final FriendSystem system;

    FriendCommand(Planets plugin, FriendSystem system) {
        this.plugin = plugin;
        this.system = system;
    }

    /** The permission that gates the whole system. */
    private static final String PERMISSION = "planets.friends";

    boolean handle(CommandSender sender, String commandName, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use the friend commands.")
                    .color(NamedTextColor.RED));
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(Component.text("You don't have permission to use friends.")
                    .color(NamedTextColor.RED));
            return true;
        }

        // /friends (and /friend with no arguments) opens the menu.
        if (commandName.equals("friends")) {
            handleFriendsCommand(player, args);
            return true;
        }
        if (args.length == 0) {
            system.openFriends(player);
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "accept", "a", "yes" -> {
                if (needsTarget(player, args, "/friend accept <player>")) {
                    return true;
                }
                withTarget(player, args[1], uuid -> system.accept(player, uuid));
                return true;
            }
            case "deny", "decline", "no", "reject" -> {
                if (needsTarget(player, args, "/friend deny <player>")) {
                    return true;
                }
                withTarget(player, args[1], uuid -> system.deny(player, uuid));
                return true;
            }
            case "remove", "unfriend", "delete" -> {
                if (needsTarget(player, args, "/friend remove <player>")) {
                    return true;
                }
                withTarget(player, args[1], uuid -> system.remove(player, uuid));
                return true;
            }
            case "cancel", "withdraw" -> {
                if (needsTarget(player, args, "/friend cancel <player>")) {
                    return true;
                }
                withTarget(player, args[1], uuid -> system.cancel(player, uuid));
                return true;
            }
            case "gift", "send" -> {
                if (needsTarget(player, args, "/friend gift <player> [amount]")) {
                    return true;
                }
                UUID target = resolve(player, args[1]);
                if (target == null) {
                    return true;
                }
                if (args.length >= 3) {
                    system.gift(player, target, args[2]);
                } else {
                    system.promptGift(player, target);
                }
                return true;
            }
            case "profile", "info", "who" -> {
                if (needsTarget(player, args, "/friend profile <player>")) {
                    return true;
                }
                withTarget(player, args[1], uuid -> {
                    player.closeInventory();
                    system.openProfile(player, uuid);
                });
                return true;
            }
            case "search", "find" -> {
                if (args.length < 2) {
                    system.promptSearch(player);
                    return true;
                }
                handleFriendsCommand(player, new String[]{"search", args[1]});
                return true;
            }
            case "requests", "pending" -> {
                player.closeInventory();
                system.openRequests(player);
                return true;
            }
            case "activity", "feed" -> {
                player.closeInventory();
                system.printActivity(player);
                return true;
            }
            case "settings", "options" -> {
                player.closeInventory();
                system.openSettings(player);
                return true;
            }
            case "list", "all", "menu" -> {
                player.closeInventory();
                system.openFriends(player);
                return true;
            }
            case "help", "?" -> {
                printHelp(player);
                return true;
            }
            default -> {
                // Not a sub-command: the argument is the player to befriend.
                UUID target = resolve(player, args[0]);
                if (target == null) {
                    return true;
                }
                if (system.friends().areFriends(player.getUniqueId(), target)) {
                    player.closeInventory();
                    system.openProfile(player, target);
                } else {
                    system.request(player, target);
                }
                return true;
            }
        }
    }

    /** The {@code /friends} command's own arguments. */
    private void handleFriendsCommand(Player player, String[] args) {
        if (args.length == 0) {
            player.closeInventory();
            system.openFriends(player);
            return;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "requests", "pending", "request" -> {
                player.closeInventory();
                system.openRequests(player);
            }
            case "activity", "feed" -> {
                player.closeInventory();
                system.printActivity(player);
            }
            case "settings", "options" -> {
                player.closeInventory();
                system.openSettings(player);
            }
            case "search", "find" -> {
                if (args.length < 2) {
                    system.promptSearch(player);
                } else {
                    List<UUID> hits = system.search(player, args[1]);
                    if (hits.isEmpty()) {
                        player.sendMessage(Component.text("\uD83D\uDC65 Nobody matched \"")
                                .color(NamedTextColor.RED)
                                .append(Component.text(args[1]).color(NamedTextColor.YELLOW))
                                .append(Component.text("\".").color(NamedTextColor.RED)));
                    } else {
                        player.closeInventory();
                        new FriendsMenu(plugin, player, system, hits, "Search: " + args[1])
                                .open(player);
                    }
                }
            }
            case "help", "?" -> printHelp(player);
            default -> {
                // "/friends Alex" is taken as "/friend Alex".
                UUID target = resolve(player, args[0]);
                if (target != null && system.friends().areFriends(player.getUniqueId(), target)) {
                    player.closeInventory();
                    system.openProfile(player, target);
                } else if (target != null) {
                    system.request(player, target);
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private boolean needsTarget(Player player, String[] args, String usage) {
        if (args.length >= 2) {
            return false;
        }
        player.sendMessage(Component.text("Usage: ").color(NamedTextColor.RED)
                .append(Component.text(usage).color(NamedTextColor.YELLOW)));
        return true;
    }

    /** Resolves a name to a uuid, explaining it in chat when impossible. */
    private UUID resolve(Player player, String query) {
        UUID uuid = plugin.resolveFriendQuery(query);
        if (uuid == null) {
            player.sendMessage(Component.text("\uD83D\uDC65 No player called \"")
                    .color(NamedTextColor.RED)
                    .append(Component.text(query).color(NamedTextColor.YELLOW))
                    .append(Component.text("\" has played here.").color(NamedTextColor.RED)));
            return null;
        }
        if (uuid.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("\uD83D\uDC65 That's you!")
                    .color(NamedTextColor.RED));
            return null;
        }
        return uuid;
    }

    private void withTarget(Player player, String query, java.util.function.Consumer<UUID> action) {
        UUID uuid = resolve(player, query);
        if (uuid != null) {
            action.accept(uuid);
        }
    }

    private void printHelp(Player player) {
        player.sendMessage(Component.text("\uD83D\uDC65 Friends & Social").color(NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        help(player, "/friend <player>", "Send a friend request (or open their profile)");
        help(player, "/friend accept <player>", "Accept a request you received");
        help(player, "/friend deny <player>", "Deny a request you received");
        help(player, "/friend cancel <player>", "Withdraw a request you sent");
        help(player, "/friend remove <player>", "End a friendship");
        help(player, "/friend gift <player>", "Gift a friend some VPL");
        help(player, "/friend profile <player>", "Open a friend's profile");
        help(player, "/friend list", "Your friends list (online first)");
        help(player, "/friend requests", "Requests waiting for you");
        help(player, "/friend activity", "What you and your friends have been up to");
        help(player, "/friend settings", "The Friends page of /settings");
        help(player, "/friends", "Open the friends menu");
    }

    private void help(Player player, String usage, String description) {
        player.sendMessage(Component.text("  " + usage).color(NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false)
                .append(Component.text(" \u2014 " + description).color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
    }

    // ── Tab completion ──────────────────────────────────────────────────

    List<String> tabComplete(Player player, String commandName, String[] args) {
        if (!player.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (commandName.equals("friends")) {
            if (args.length <= 1) {
                List<String> options = new ArrayList<>(List.of("requests", "activity", "settings", "search", "help"));
                options.addAll(plugin.knownPlayerNames(args.length == 0 ? "" : args[0]));
                return keep(options, args.length == 0 ? "" : args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("search")) {
                return plugin.knownPlayerNames(args[1]);
            }
            return List.of();
        }

        if (args.length <= 1) {
            List<String> options = new ArrayList<>(SUBCOMMANDS);
            options.addAll(plugin.knownPlayerNames(args.length == 0 ? "" : args[0]));
            return keep(options, args.length == 0 ? "" : args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("search") && args.length == 2) {
            return plugin.knownPlayerNames(args[1]);
        }
        if (SUBCOMMANDS.contains(sub) && args.length == 2) {
            return plugin.knownPlayerNames(args[1]);
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
