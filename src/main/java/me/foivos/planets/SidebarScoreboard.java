package me.foivos.planets;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The sidebar: a scoreboard pinned down the right of the screen for every
 * player on the server, showing their own numbers.
 *
 * <p>Each player gets their <b>own</b> {@link Scoreboard} instance, so the
 * balance, playtime, deaths and kills in the sidebar are theirs and nobody
 * else's. The lines themselves come from {@code sidebar.lines} in config.yml —
 * one entry per line, each with a label (used by the picker) and a template
 * ({@code %balance%}, {@code %playtime%}, ... filled in by
 * {@link Planets#renderHudTemplate}). The title is the line above them all.
 *
 * <p>Which of those lines a player actually sees, and in what order, is theirs
 * to choose from {@code /settings} → Sidebar; anything they haven't touched
 * shows every line in the configured order.
 *
 * <p>The client draws the title plus at most 14 rows, so longer
 * {@code sidebar.lines} lists are trimmed to {@link #MAX_LINES}.
 */
public final class SidebarScoreboard {

    /** One configurable sidebar line. */
    public record Line(String id, String label, String template) {
    }

    /** The objective that draws the sidebar. */
    private static final String OBJECTIVE = "planetarium";
    /** Team names are limited to 16 characters, so this prefix stays short. */
    private static final String TEAM_PREFIX = "psb_";
    /**
     * How many lines fit: the client draws the title plus at most 14 rows, and
     * every row needs a scoreboard entry of its own.
     */
    public static final int MAX_LINES = 14;
    /** Invisible, unique scoreboard entries — one per row, "§0" to "§f". */
    private static final String ENTRY_CHARS = "0123456789abcdef";

    /** The title used when config.yml names none. */
    private static final String DEFAULT_TITLE = "PLANETARIUM smp";
    private static final long DEFAULT_INTERVAL_TICKS = 20L;

    /** The shipped lines, used when config.yml lists none. */
    private static final List<Line> DEFAULT_LINES = List.of(
            new Line("player", "Player", "%player%"),
            new Line("balance", "Balance", "%balance% \u20BE"),
            new Line("friends", "Friends", "Friends: %friends%"),
            new Line("playtime", "Playtime", "\u23F1 %playtime%"),
            new Line("deaths", "Deaths", "\u2620 %deaths%"),
            new Line("kills", "Kills", "\u2694 %kills%"));

    private final Planets plugin;
    /** One board per player, so each sidebar shows that player's own numbers. */
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    private List<Line> lines = DEFAULT_LINES;
    private String title = DEFAULT_TITLE;
    private NamedTextColor titleColor = NamedTextColor.BLUE;
    private NamedTextColor lineColor = NamedTextColor.WHITE;
    private boolean enabled = true;
    private long intervalTicks = DEFAULT_INTERVAL_TICKS;
    private BukkitTask task;

    public SidebarScoreboard(Planets plugin) {
        this.plugin = plugin;
    }

    // ── Configuration ───────────────────────────────────────────────────

    /**
     * Reads the {@code sidebar} section of config.yml. A blank section falls
     * back to the shipped lines, so the sidebar can never break on a bad config.
     *
     * @return true when the section had to be written into the file, which the
     *         caller saves so an existing config.yml grows the new section.
     */
    boolean loadConfig(ConfigurationSection config) {
        ConfigurationSection section = config == null
                ? null : config.getConfigurationSection("sidebar");
        if (section == null) {
            // An older config.yml with no sidebar section: use the shipped
            // defaults now and write them in, so they can be edited in place.
            writeDefaults(config);
            return true;
        }
        this.enabled = section.getBoolean("enabled", true);
        this.title = section.getString("title", DEFAULT_TITLE);
        this.titleColor = colour(section.getString("title-color"), NamedTextColor.GOLD);
        this.lineColor = colour(section.getString("line-color"), NamedTextColor.WHITE);
        this.intervalTicks = Math.max(5L, section.getLong("interval-ticks", DEFAULT_INTERVAL_TICKS));

        List<Map<?, ?>> configured = section.getMapList("lines");
        List<Line> parsed = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Map<?, ?> raw : configured) {
            Object template = raw.get("template");
            if (template == null || String.valueOf(template).isBlank()) {
                continue;
            }
            Object label = raw.get("label");
            String name = label == null || String.valueOf(label).isBlank()
                    ? "Line " + (parsed.size() + 1) : String.valueOf(label);
            parsed.add(new Line(uniqueId(slug(name), used), name, String.valueOf(template)));
            if (parsed.size() >= MAX_LINES) {
                break;
            }
        }
        // A config.yml written before the Friends & Social system has no
        // friends line. Add one (and have the caller save it) so the scoreboard
        // shows "Friends: 2/8" the moment the plugin is updated, without a
        // server owner having to edit anything by hand. A player who picked
        // their own lines keeps their choice and can add the line themselves.
        boolean addedFriendsLine = false;
        if (!parsed.isEmpty()
                && parsed.stream().noneMatch(line -> line.template().contains("%friends%"))
                && parsed.size() < MAX_LINES) {
            parsed.add(new Line(uniqueId(slug("Friends"), used), "Friends", "Friends: %friends%"));
            // Write it back into the live config too, so the caller's save puts
            // the new line in the file rather than only in memory.
            List<Map<String, Object>> rawLines = new ArrayList<>();
            for (Map<?, ?> raw : configured) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> field : raw.entrySet()) {
                    copy.put(String.valueOf(field.getKey()), field.getValue());
                }
                rawLines.add(copy);
            }
            Map<String, Object> friendsEntry = new LinkedHashMap<>();
            friendsEntry.put("label", "Friends");
            friendsEntry.put("template", "Friends: %friends%");
            rawLines.add(friendsEntry);
            section.set("lines", rawLines);
            addedFriendsLine = true;
        }
        this.lines = parsed.isEmpty() ? DEFAULT_LINES : List.copyOf(parsed);
        return addedFriendsLine;
    }

    /** Writes the shipped lines into a config.yml that has no sidebar section. */
    private static void writeDefaults(ConfigurationSection config) {
        if (config == null) {
            return;
        }
        ConfigurationSection section = config.createSection("sidebar");
        section.set("enabled", true);
        section.set("interval-ticks", DEFAULT_INTERVAL_TICKS);
        section.set("title", DEFAULT_TITLE);
        section.set("title-color", "gold");
        section.set("line-color", "white");
        List<Map<String, Object>> raw = new ArrayList<>();
        for (Line line : DEFAULT_LINES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", line.label());
            entry.put("template", line.template());
            raw.add(entry);
        }
        section.set("lines", raw);
    }

    /** Whether the sidebar is switched on for the whole server. */
    boolean enabled() {
        return enabled;
    }

    /** Every line configured under {@code sidebar.lines}. */
    List<Line> lines() {
        return lines;
    }

    /** The heading drawn above the lines. */
    String title() {
        return title;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /** Starts the repeating redraw (config: {@code sidebar.interval-ticks}). */
    void start() {
        stop();
        if (!enabled) {
            // Switched off in config.yml: make sure nobody is left with one.
            refreshAll();
            return;
        }
        long interval = Math.max(5L, intervalTicks);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, interval, interval);
    }

    /** Stops the redraw without touching the sidebars already on screen. */
    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Removes every sidebar and stops the redraw (server shutdown). */
    void shutdown() {
        stop();
        for (UUID id : new ArrayList<>(boards.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                detach(player);
            }
        }
        boards.clear();
    }

    /** Redraws the sidebar of every online player. */
    void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    /**
     * Draws (or removes) one player's sidebar. A player who turned the sidebar
     * off, or who has hidden every line, gets their normal scoreboard back.
     */
    void refresh(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!enabled || !wanted(player)) {
            detach(player);
            return;
        }
        List<Line> visible = visibleLines(player);
        if (visible.isEmpty()) {
            detach(player);
            return;
        }
        draw(player, visible);
    }

    /**
     * Puts the player back on the server's main scoreboard. Only ever undoes a
     * board this class set, so a scoreboard installed by another plugin is not
     * disturbed.
     */
    void detach(Player player) {
        if (player == null) {
            return;
        }
        if (boards.remove(player.getUniqueId()) != null) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /** Whether the player wants the sidebar at all (their /settings toggle). */
    private boolean wanted(Player player) {
        PlayerSettings settings = plugin.getPlayerSettings();
        return settings == null
                || settings.get(player.getUniqueId(), PlayerSettings.Setting.SIDEBAR);
    }

    /**
     * The lines this player wants and in what order: everything configured, in
     * config order, unless they picked their own set in {@code /settings}.
     * Ids that no longer exist in config.yml are quietly skipped.
     */
    List<Line> visibleLines(Player player) {
        PlayerSettings settings = plugin.getPlayerSettings();
        List<String> chosen = settings == null
                ? null : settings.sidebarLines(player.getUniqueId());
        if (chosen == null) {
            return lines;
        }
        List<Line> result = new ArrayList<>();
        for (String id : chosen) {
            for (Line line : lines) {
                if (line.id().equals(id)) {
                    result.add(line);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Saves the player's own line selection and redraws their sidebar at once.
     * Handing back exactly the configured set in the configured order clears
     * their choice, so they follow future config edits again.
     */
    void chooseLines(Player player, List<String> ids) {
        PlayerSettings settings = plugin.getPlayerSettings();
        if (settings == null || player == null) {
            return;
        }
        List<String> clean = new ArrayList<>(new LinkedHashSet<>(ids));
        List<String> all = new ArrayList<>();
        for (Line line : lines) {
            all.add(line.id());
        }
        if (clean.equals(all)) {
            settings.clearSidebarLines(player.getUniqueId());
        } else {
            settings.setSidebarLines(player.getUniqueId(), clean);
        }
        refresh(player);
    }

    private void draw(Player player, List<Line> visible) {
        UUID id = player.getUniqueId();
        Scoreboard board = boards.get(id);
        if (board == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, titleComponent());
            boards.put(id, board);
            player.setScoreboard(board);
        }
        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            return;
        }
        objective.displayName(titleComponent());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        // The score behind each line is only there to order it, so the red
        // numbers the client would otherwise draw are hidden.
        try {
            objective.numberFormat(NumberFormat.blank());
        } catch (LinkageError | RuntimeException ignored) {
            // Per-objective number formats are newer than this server: the
            // numbers stay visible, but the sidebar still works.
        }

        int used = Math.min(visible.size(), MAX_LINES);
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = entry(i);
            Team team = board.getTeam(teamName(i));
            if (i < used) {
                // Each row is a team whose prefix is the rendered line, with an
                // invisible scoreboard entry carrying the score that orders it.
                if (team == null) {
                    team = board.registerNewTeam(teamName(i));
                }
                team.prefix(lineComponent(visible.get(i), player));
                if (!team.hasEntry(entry)) {
                    team.addEntry(entry);
                }
                objective.getScore(entry).setScore(used - i);
            } else if (team != null) {
                team.unregister();
                board.resetScores(entry);
            }
        }
    }

    private Component titleComponent() {
        return Component.text(title)
                .color(titleColor)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component lineComponent(Line line, Player player) {
        return Component.text(plugin.renderHudTemplate(player, line.template()))
                .color(lineColor)
                .decoration(TextDecoration.ITALIC, false);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** The invisible, unique entry that carries row {@code index}'s score. */
    private static String entry(int index) {
        return "\u00A7" + ENTRY_CHARS.charAt(Math.floorMod(index, ENTRY_CHARS.length()));
    }

    private static String teamName(int index) {
        return TEAM_PREFIX + index;
    }

    /** A stable id for a configured line, derived from its label. */
    static String slug(String label) {
        StringBuilder id = new StringBuilder();
        for (char c : label.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                id.append(c);
            } else if (id.length() > 0 && id.charAt(id.length() - 1) != '-') {
                id.append('-');
            }
        }
        while (id.length() > 0 && id.charAt(id.length() - 1) == '-') {
            id.setLength(id.length() - 1);
        }
        return id.length() == 0 ? "line" : id.toString();
    }

    private static String uniqueId(String id, Set<String> used) {
        String candidate = id;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = id + "-" + suffix++;
        }
        return candidate;
    }

    /** Parses a colour name from config, falling back when it is unknown. */
    static NamedTextColor colour(String name, NamedTextColor fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String key = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        NamedTextColor named = NamedTextColor.NAMES.value(key);
        return named == null ? fallback : named;
    }
}
