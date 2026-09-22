package me.foivos.planets;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Stops normal players from running the risky commands that Essentials, Vault
 * and a few other server plugins register (gamemode, give, tp, eco give,
 * invsee, weather, op...).
 *
 * <p>The check runs on {@link PlayerCommandPreprocessEvent}, so it catches the
 * command no matter which plugin would have handled it, including the
 * namespaced forms players use to dodge simple blocks
 * ({@code /essentials:gamemode}, {@code /minecraft:give}).
 *
 * <p>Staff are never affected: operators and anyone holding the bypass
 * permission ({@code planets.staff.commands} by default) pass straight through.
 * Both the blocked list and the bypass permission can be changed in
 * {@code config.yml}:
 *
 * <pre>
 * command-guard: true
 * command-guard-bypass-permission: planets.staff.commands
 * blocked-commands:
 *   - gamemode
 *   - give
 *   # ...
 * </pre>
 */
public final class CommandGuard implements Listener {

    /** The default blacklist, used when config.yml doesn't list its own. */
    private static final List<String> DEFAULT_BLOCKED = List.of(
            // game mode
            "gamemode", "gm", "gmc", "gms", "gma", "gmsp", "defaultgamemode",
            // items out of nothing
            "give", "i", "item", "more", "unlimited", "enchant", "repair", "fix",
            "clearinventory", "clear", "ci", "hat", "skull",
            // body state
            "heal", "feed", "god", "ext", "extinguish", "suicide-abuse",
            // movement / position
            "fly", "speed", "jump", "near", "back", "top", "ascend", "descend",
            "tp", "tpo", "tpohere", "tphere", "tppos", "tpall", "tpaall", "teleport",
            "warp-admin", "setwarp", "delwarp", "setspawn", "spawnpoint",
            // other players' stuff
            "invsee", "enderchest", "ec", "openinv", "socialspy", "seen", "ip", "whois",
            "sudo", "nick", "nickname", "realname-abuse", "vanish", "v",
            // mobs and the world
            "kill", "killall", "butcher", "remove", "spawnmob", "mob", "spawner",
            "time", "day", "night", "sunrise", "weather", "sun", "storm", "thunder",
            "world", "worlds", "mv", "multiverse", "seed", "gamerule", "difficulty",
            // economy (Vault / Essentials)
            "eco", "economy", "setworth", "essentials", "vault",
            // server control
            "op", "deop", "stop", "restart", "reload", "rl", "plugman", "plugins", "pl",
            "timings", "version-abuse", "whitelist", "save-all", "save-off", "execute"
    );

    private final Planets plugin;

    public CommandGuard(Planets plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("command-guard", true)) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isOp() || player.hasPermission(bypassPermission())) {
            return;
        }

        String label = label(event.getMessage());
        if (label.isEmpty() || !blocked().contains(label)) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(Component.text("\u26D4 That command is disabled for players.")
                .color(NamedTextColor.RED));
        player.sendMessage(Component.text("Use the server's own menus instead — ")
                .color(NamedTextColor.GRAY)
                .append(Component.text("/planets").color(NamedTextColor.YELLOW))
                .append(Component.text(", ").color(NamedTextColor.GRAY))
                .append(Component.text("/home").color(NamedTextColor.YELLOW))
                .append(Component.text(" or ").color(NamedTextColor.GRAY))
                .append(Component.text("/settings").color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GRAY)));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
    }

    /** The permission that lets a player use the blocked commands anyway. */
    private String bypassPermission() {
        return plugin.getConfig().getString("command-guard-bypass-permission",
                "planets.staff.commands");
    }

    /** The blocked command names, lower-cased and without any plugin prefix. */
    private Set<String> blocked() {
        List<String> configured = plugin.getConfig().getStringList("blocked-commands");
        List<String> source = configured.isEmpty() ? DEFAULT_BLOCKED : configured;
        Set<String> names = new LinkedHashSet<>();
        for (String entry : source) {
            String name = strip(entry);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * The command name a player typed, lower-cased, without the leading slash,
     * without any {@code plugin:} prefix and without its arguments.
     */
    private static String label(String message) {
        String text = message == null ? "" : message.trim();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        String first = Arrays.stream(text.split("\\s+")).findFirst().orElse("");
        return strip(first);
    }

    /** Lower-cases a command name and drops a {@code plugin:} namespace prefix. */
    private static String strip(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("/")) {
            name = name.substring(1);
        }
        int colon = name.indexOf(':');
        if (colon >= 0 && colon + 1 < name.length()) {
            name = name.substring(colon + 1);
        }
        return name.trim();
    }
}
