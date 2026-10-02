package me.foivos.planets;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.key.Key;
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
import java.util.EnumSet;
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
    private static final String DEFAULT_TITLE = "ᴘʟᴀɴᴇᴛᴀʀɪᴜᴍ ѕᴍᴘ";
    private static final long DEFAULT_INTERVAL_TICKS = 20L;

    /**
     * The template of the shipped separator: a rule of dashes that sits in the
     * first row, directly under the title, and sets the player's name apart
     * from the numbers beneath it.
     */
    private static final String BLANK_TEMPLATE = "&7--------------------";
    /** The Friends line added to a config.yml written before the social system. */
    private static final String FRIENDS_TEMPLATE = "Friends: %friends%";

    /**
     * The shipped lines, used when config.yml lists none — and the shape every
     * line keeps, wherever it comes from: a grey label, a dark {@code |}
     * between the two halves, and the number in a colour of its own — nothing
     * before the number, because the glyph that used to sit there (a clock for
     * playtime, a sword for kills, a skull for deaths) only crowded the column.
     * The separator is the exception: a grey rule of dashes with no label or
     * value, drawn as the first row so the board reads title, rule, name, numbers.
     *
     * <p>The labels carry no padding of their own: the {@code |} separators are
     * lined up at render time, by measuring the labels in the client's font
     * ({@link #align}), so a template only has to say what it wants to show.
     */
    private static final List<Line> DEFAULT_LINES = List.of(
            new Line("separator", "Separator", BLANK_TEMPLATE),
            new Line("player", "Player",     "&b%player%"),
            new Line("balance", "Balance",   "&7Balance    &8| &6%balance% &e\u20BE"),
            new Line("friends", "Friends",   "&7Friends     &8| &a%friends%"),
            new Line("playtime", "Playtime", "&7Playtime   &8| &b%playtime%"),
            new Line("kills", "Kills",       "&7Kills      &8| &d%kills%"),
            new Line("deaths", "Deaths",     "&7Deaths     &8| &c%deaths%"),
            new Line("bounty", "Bounty",     "&7Bounty     &8| &c%bounty% &e\u20BE"));

    private final Planets plugin;
    /** One board per player, so each sidebar shows that player's own numbers. */
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    private List<Line> lines = DEFAULT_LINES;
    private String title = DEFAULT_TITLE;
    private NamedTextColor titleColor = NamedTextColor.BLUE;
    private NamedTextColor lineColor = NamedTextColor.WHITE;
    private boolean enabled = true;
    private long intervalTicks = DEFAULT_INTERVAL_TICKS;
    /** Whether the columns are padded with the pack's blank glyphs, to the pixel. */
    private boolean finePadding;
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
        this.finePadding = section.getBoolean("fine-padding", false);

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
        // A config.yml written before a feature joined the board has no line
        // for it: a Friends line, and the separator rule that sets the title and
        // the player's name apart from the numbers under them. Add whichever is
        // missing (and have
        // the caller save it) so the board shows them the moment the plugin is
        // updated, without a server owner having to edit anything by hand. A
        // player who picked their own lines keeps their choice, and can add the
        // line themselves from /settings → Sidebar.
        List<Map<String, Object>> rawLines = copyLines(configured);
        boolean grown = false;
        if (!parsed.isEmpty()
                && parsed.stream().noneMatch(line -> line.template().contains("%friends%"))
                && parsed.size() < MAX_LINES) {
            parsed.add(new Line(uniqueId(slug("Friends"), used), "Friends", FRIENDS_TEMPLATE));
            rawLines.add(lineEntry("Friends", FRIENDS_TEMPLATE));
            grown = true;
        }
        if (!parsed.isEmpty() && parsed.size() < MAX_LINES) {
            // The separator is the first row of all: directly under the title,
            // above the player's name. An older config.yml has the blank row it
            // replaced — a line whose template draws nothing at all — and that
            // row is turned into the rule and moved up, rather than a second row
            // being added beside it and leaving the empty gap in place.
            int blankAt = -1;
            for (int i = 0; i < parsed.size(); i++) {
                if (rendersNothing(parsed.get(i).template())) {
                    blankAt = i;
                    break;
                }
            }
            if (blankAt >= 0) {
                Line replaced = new Line(uniqueId(slug("Separator"), used),
                        "Separator", BLANK_TEMPLATE);
                Map<String, Object> replacedEntry = lineEntry("Separator", BLANK_TEMPLATE);
                if (!parsed.get(blankAt).template().equals(BLANK_TEMPLATE)) {
                    parsed.set(blankAt, replaced);
                    if (blankAt < rawLines.size()) {
                        rawLines.set(blankAt, replacedEntry);
                    }
                    grown = true;
                }
                if (blankAt != 0) {
                    parsed.remove(blankAt);
                    parsed.add(0, replaced);
                    if (blankAt < rawLines.size()) {
                        rawLines.remove(blankAt);
                    }
                    rawLines.add(0, replacedEntry);
                    grown = true;
                }
            } else {
                parsed.add(0, new Line(uniqueId(slug("Separator"), used),
                        "Separator", BLANK_TEMPLATE));
                rawLines.add(0, lineEntry("Separator", BLANK_TEMPLATE));
                grown = true;
            }
        }
        if (grown) {
            // Write them back into the live config too, so the caller's save
            // puts the new lines in the file rather than only in memory.
            section.set("lines", rawLines);
        }
        // A config.yml written before the bounty board joined the scoreboard
        // has no Bounty line. Rewrite the whole set in place to the shipped
        // defaults (which include it) unless the server owner has added one —
        // the call returns false then, and nothing is touched. A player who
        // picked their own lines keeps their choice; they can add the line
        // themselves in /settings → Sidebar.
        boolean upgraded = false;
        boolean restyled = !parsed.isEmpty() && parsed.stream()
                .anyMatch(line -> carriesShippedGlyph(line.template()));
        if (!parsed.isEmpty() && (noLineAbout(parsed, "bounty") || restyled)) {
            this.lines = DEFAULT_LINES;
            writeDefaults(config);
            upgraded = true;
        } else {
            this.lines = parsed.isEmpty() ? DEFAULT_LINES : List.copyOf(parsed);
        }
        return grown || upgraded;
    }

    /** The configured lines as mutable maps, ready to be written back out. */
    private static List<Map<String, Object>> copyLines(List<Map<?, ?>> configured) {
        List<Map<String, Object>> copy = new ArrayList<>(configured.size() + 2);
        for (Map<?, ?> raw : configured) {
            Map<String, Object> line = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : raw.entrySet()) {
                line.put(String.valueOf(field.getKey()), field.getValue());
            }
            copy.add(line);
        }
        return copy;
    }

    /** One {@code label}/{@code template} pair, shaped like a config.yml line. */
    private static Map<String, Object> lineEntry(String label, String template) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("label", label);
        entry.put("template", template);
        return entry;
    }

    /** Whether a template draws no text at all — the blank row, in short. */
    static boolean rendersNothing(String template) {
        return withoutCodes(template).isBlank();
    }

    /**
     * The glyphs the shipped lines used to put in front of their numbers: a
     * clock, a sword, a skull and a sword-cross.
     */
    private static final String SHIPPED_GLYPHS = "\u23F1\u2694\u2620\u2720";

    /**
     * Whether a template still carries one of those glyphs, which is what tells
     * a row of ours as it used to be drawn from a line somebody wrote.
     */
    private static boolean carriesShippedGlyph(String template) {
        if (template == null) {
            return false;
        }
        for (int i = 0; i < SHIPPED_GLYPHS.length(); i++) {
            if (template.indexOf(SHIPPED_GLYPHS.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Writes the shipped lines into a config.yml that has no sidebar section. */
    private static void writeDefaults(ConfigurationSection config) {
        if (config == null) {
            return;
        }
        ConfigurationSection section = config.getConfigurationSection("sidebar");
        if (section == null) {
            section = config.createSection("sidebar");
        }
        section.set("enabled", section.getBoolean("enabled", true));
        section.set("interval-ticks", section.getLong("interval-ticks", DEFAULT_INTERVAL_TICKS));
        section.set("title", section.getString("title", DEFAULT_TITLE));
        section.set("title-color", section.getString("title-color", "gold"));
        section.set("line-color", section.getString("line-color", "white"));
        section.set("fine-padding", section.getBoolean("fine-padding", false));
        List<Map<String, Object>> raw = new ArrayList<>();
        for (Line line : DEFAULT_LINES) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", line.label());
            entry.put("template", line.template());
            raw.add(entry);
        }
        section.set("lines", raw);
    }

    /**
     * Whether none of the configured lines draws the named placeholder — the
     * sign that a config.yml written before a feature joined the board has no
     * line about it.
     */
    private static boolean noLineAbout(List<Line> lines, String placeholder) {
        String token = "%" + placeholder + "%";
        return lines.stream().noneMatch(line -> line.template().contains(token));
    }

    /** Whether the sidebar is switched on for the whole server. */
    boolean enabled() {
        return enabled;
    }

    /**
     * Whether the columns are padded to the exact pixel with the resource
     * pack's blank glyphs, rather than to the nearest whole space.
     */
    boolean finePadding() {
        return finePadding;
    }

    /** Every line configured under {@code sidebar.lines}. */
    List<Line> lines() {
        return lines;
    }

    /** The heading drawn above the lines. */
    String title() {
        return title;
    }

    /** The colour lines without colour codes of their own are drawn in. */
    NamedTextColor lineColour() {
        return lineColor;
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
        // Every row starts at the same x, so the "|" separators only form a
        // column when the labels are all the same width. Render the lines
        // first, measure their labels in the client's font, and pad the
        // shorter ones out to the widest.
        List<String> rendered = new ArrayList<>(used);
        int barColumn = 0;
        int valueColumn = 0;
        for (int i = 0; i < used; i++) {
            String text = plugin.renderHudTemplate(player, visible.get(i).template());
            rendered.add(text);
            barColumn = Math.max(barColumn, labelWidth(text));
            valueColumn = Math.max(valueColumn, valueWidth(text));
        }
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = entry(i);
            Team team = board.getTeam(teamName(i));
            if (i < used) {
                // Each row is a team whose prefix is the rendered line, with an
                // invisible scoreboard entry carrying the score that orders it.
                if (team == null) {
                    team = board.registerNewTeam(teamName(i));
                }
                team.prefix(lineComponent(visible.get(i), player, rendered.get(i), barColumn, valueColumn));
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

    /**
     * One row of the sidebar: the template rendered for this player, in the
     * colour they picked for that line ({@code /settings} → Sidebar →
     * shift-left-click a line). A line they never touched keeps the colours the
     * template asks for.
     */
    private Component lineComponent(Line line, Player player, String rendered, int barColumn, int valueColumn) {
        NamedTextColor chosen = chosenColour(player, line);
        Component row = styled(align(rendered, barColumn, valueColumn, finePadding), lineColor, chosen);
        if (finePadding) {
            // The row is drawn in the pack's font, which is the default font
            // plus the blank glyphs: everything except the padding renders
            // exactly as it did before.
            row = row.style(builder -> builder.font(FINE_FONT));
        }
        return row;
    }

    /** The colour this player picked for one line, or null to follow the config. */
    NamedTextColor chosenColour(Player player, Line line) {
        PlayerSettings settings = plugin.getPlayerSettings();
        if (settings == null || player == null || line == null) {
            return null;
        }
        return colour(settings.sidebarColour(player.getUniqueId(), line.id()), null);
    }

    /**
     * Reads the {@code &} colour codes a template may use, so one row can be
     * several colours: {@code "&7Balance &8| &6%balance%"} draws a grey label, a
     * dark separator and a gold number. Text with no code of its own takes
     * {@code fallback} (the configured line colour).
     *
     * <p>When {@code override} is given the whole row is drawn in it and the
     * codes are dropped: the colour the player picked in {@code /settings} beats
     * whatever config.yml says.
     */
    static Component styled(String text, NamedTextColor fallback, NamedTextColor override) {
        String plain = text == null ? "" : text;
        if (override != null) {
            return Component.text(withoutCodes(plain)).color(override)
                    .decoration(TextDecoration.ITALIC, false);
        }
        // Build the row by folding parts onto a plain Component. Deliberately
        // avoids a TextComponent.Builder, whose build() signature moved between
        // Adventure 4.x and 5.x and blows up with NoSuchMethodError when the
        // plugin is compiled against a different Paper build than it runs on.
        // Underscores are left exactly as they are: they are only padding on
        // the label side of a line, which align has already taken out, and a
        // name that really contains one (shadowmeow2025_2) must keep it.
        Component root = Component.empty();
        NamedTextColor colour = fallback;
        Set<TextDecoration> styles = EnumSet.noneOf(TextDecoration.class);
        int index = 0;
        int at = plain.indexOf('&');
        while (at >= 0 && at + 1 < plain.length()) {
            char code = Character.toLowerCase(plain.charAt(at + 1));
            if (!isCode(code)) {
                // Not a code at all ("A & B"): leave it exactly as typed.
                at = plain.indexOf('&', at + 1);
                continue;
            }
            root = root.append(part(plain.substring(index, at), colour, styles));
            NamedTextColor named = COLOURS.get(code);
            if (named != null) {
                colour = named;
            } else if (code == 'r') {
                colour = fallback;
                styles.clear();
            } else {
                styles.add(style(code));
            }
            index = at + 2;
            at = plain.indexOf('&', index);
        }
        root = root.append(part(plain.substring(index), colour, styles));
        return root;
    }

    /** One run of text inside a line, coloured and decorated as asked. */
    private static Component part(String text, NamedTextColor colour, Set<TextDecoration> styles) {
        Component part = Component.text(text).color(colour)
                .decoration(TextDecoration.ITALIC, false);
        for (TextDecoration style : styles) {
            part = part.decoration(style, true);
        }
        return part;
    }

    /** The style one {@code &}-code turns on ({@code &l} bold, {@code &o} italic). */
    private static TextDecoration style(char code) {
        return switch (code) {
            case 'l' -> TextDecoration.BOLD;
            case 'o' -> TextDecoration.ITALIC;
            case 'n' -> TextDecoration.UNDERLINED;
            case 'm' -> TextDecoration.STRIKETHROUGH;
            default -> TextDecoration.OBFUSCATED;
        };
    }

    /** The same text with every {@code &}-code taken out. */
    static String withoutCodes(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text == null ? "" : text;
        }
        StringBuilder plain = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length() && isCode(text.charAt(i + 1))) {
                i++;
                continue;
            }
            plain.append(c);
        }
        return plain.toString();
    }

    private static boolean isCode(char raw) {
        char code = Character.toLowerCase(raw);
        return COLOURS.containsKey(code) || "lonmkr".indexOf(code) >= 0;
    }

    /**
     * The {@code &}-codes a sidebar template can use, one per Minecraft colour
     * ({@code &7} grey, {@code &8} dark grey, {@code &6} gold, ...). The darker
     * end of the list is exactly what the {@code |} separators use.
     */
    static final Map<Character, NamedTextColor> COLOURS = Map.ofEntries(
            Map.entry('0', NamedTextColor.BLACK),
            Map.entry('1', NamedTextColor.DARK_BLUE),
            Map.entry('2', NamedTextColor.DARK_GREEN),
            Map.entry('3', NamedTextColor.DARK_AQUA),
            Map.entry('4', NamedTextColor.DARK_RED),
            Map.entry('5', NamedTextColor.DARK_PURPLE),
            Map.entry('6', NamedTextColor.GOLD),
            Map.entry('7', NamedTextColor.GRAY),
            Map.entry('8', NamedTextColor.DARK_GRAY),
            Map.entry('9', NamedTextColor.BLUE),
            Map.entry('a', NamedTextColor.GREEN),
            Map.entry('b', NamedTextColor.AQUA),
            Map.entry('c', NamedTextColor.RED),
            Map.entry('d', NamedTextColor.LIGHT_PURPLE),
            Map.entry('e', NamedTextColor.YELLOW),
            Map.entry('f', NamedTextColor.WHITE));

    // ── Alignment ───────────────────────────────────────────────────────

    /**
     * The advance width, in pixels, of each printable ASCII glyph in the
     * client's default font — the same numbers the font itself is built from,
     * so a label can be measured on the server exactly as the client draws it.
     * Indexed by {@code c - ' '}.
     */
    private static final int[] ADVANCES = {
            4, 2, 5, 6, 6, 6, 6, 3, 5, 5, 5, 6, 2, 6, 2, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 2, 2, 5, 6, 5, 6,
            7, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 4, 6, 6,
            3, 6, 6, 6, 6, 6, 5, 6, 6, 2, 6, 5, 3, 6, 6, 6,
            6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6, 5, 2, 5, 7};

    /** How wide one space is in the default font. */
    private static final int SPACE_WIDTH = 4;
    /** Anything past ASCII (⚔, ☠, ₾) is drawn by the fallback font. */
    private static final int FALLBACK_WIDTH = 9;

    /**
     * The font the plugin's resource pack adds: the default font, plus four
     * blank glyphs one, two, three and four pixels wide. A space is the only
     * blank glyph the client has of its own, and it is 4 px, so without these
     * a column can only be padded in steps of four and lands up to two out.
     */
    private static final Key FINE_FONT = Key.key("planetarium", "fine_space");
    /** {@code \uE001}..{@code \uE004}, drawn 1, 2, 3 and 4 px wide. */
    private static final String FINE_GLYPHS = "\uE001\uE002\uE003\uE004";

    /**
     * Puts a line into its column: the label is padded with spaces until it is
     * as wide as the widest label on the board ({@code barColumn} pixels), and
     * the value until it ends where the widest value ends ({@code valueColumn}).
     * The {@code |} separators then form a column down the board instead of
     * stepping in and out with each label's length, and the numbers end
     * together on the right.
     *
     * <p>The client's font is not monospaced, so both are worked out in pixels
     * rather than characters. Its spaces are 4 px wide, which leaves a column
     * at most two pixels out.
     *
     * @param barColumn   the x every {@code |} should sit at, or 0 to leave the
     *                    labels where they are
     * @param valueColumn how far the values should reach, or 0 to leave them
     *                    where they are
     */
    static String align(String line, int barColumn, int valueColumn) {
        return align(line, barColumn, valueColumn, false);
    }

    /**
     * The same, with the choice of padding spelled out.
     *
     * @param fine pad with the resource pack's blank glyphs, which are one,
     *             two, three and four pixels wide, so both columns can be hit
     *             exactly instead of to the nearest whole space. The line then
     *             has to be drawn in that font — see {@link #lineComponent}
     */
    static String align(String line, int barColumn, int valueColumn, boolean fine) {
        if (line == null || (barColumn <= 0 && valueColumn <= 0)) {
            return line;
        }
        int bar = line.indexOf('|');
        if (bar < 0) {
            return line;
        }
        String head = trimPadding(line.substring(0, bar), false).replace("_", "");
        String tail = trimPadding(line.substring(bar + 1), true);
        int labelPx = pixelWidth(withoutCodes(head));
        String barPad = pad(barColumn - labelPx, fine);
        // A label that is not a whole number of spaces short of the column
        // leaves its bar a pixel or two out; carry that over to the value, so
        // the two halves cannot drift apart. Fine padding is exact, so the
        // drift is always zero with the pack.
        int drift = barColumn > 0 ? labelPx + padWidth(barPad, fine) - barColumn : 0;
        StringBuilder out = new StringBuilder(head.length() + tail.length() + 8);
        out.append(head).append(barPad).append('|');
        if (!tail.isEmpty()) {
            int valuePx = pixelWidth(withoutCodes(tail));
            out.append(' ').append(pad(valueColumn - drift - valuePx, fine)).append(tail);
        }
        return out.toString();
    }

    /**
     * The same line with its halves tidied up — the {@code _} padding and the
     * stray spaces an older config.yml put around the {@code |} — ready to be
     * shown on its own, as the {@code /settings} previews are.
     */
    static String tidy(String line) {
        return align(line, Math.max(0, labelWidth(line)), Math.max(0, valueWidth(line)));
    }

    /**
     * How wide the value of {@code line} is — everything right of its first
     * {@code |} — or 0 for a line with no separator to line up.
     */
    static int valueWidth(String line) {
        if (line == null) {
            return 0;
        }
        int bar = line.indexOf('|');
        if (bar < 0) {
            return 0;
        }
        return pixelWidth(withoutCodes(trimPadding(line.substring(bar + 1), true)));
    }

    /**
     * Padding that draws {@code pixels} wide. Whole spaces come to the nearest
     * space to that width, which is the two-pixel ceiling; the pack's glyphs
     * come to exactly it.
     */
    private static String pad(int pixels, boolean fine) {
        if (pixels <= 0) {
            return "";
        }
        if (!fine) {
            return " ".repeat(Math.round(pixels / (float) SPACE_WIDTH));
        }
        StringBuilder out = new StringBuilder(pixels / SPACE_WIDTH + 1);
        int left = pixels;
        while (left >= SPACE_WIDTH) {
            out.append(FINE_GLYPHS.charAt(FINE_GLYPHS.length() - 1));
            left -= SPACE_WIDTH;
        }
        if (left > 0) {
            out.append(FINE_GLYPHS.charAt(left - 1));
        }
        return out.toString();
    }

    /** How wide a run of padding draws, in pixels. */
    private static int padWidth(String padding, boolean fine) {
        if (padding == null || padding.isEmpty()) {
            return 0;
        }
        if (!fine) {
            return padding.length() * SPACE_WIDTH;
        }
        int width = 0;
        for (int i = 0; i < padding.length(); i++) {
            int glyph = FINE_GLYPHS.indexOf(padding.charAt(i));
            width += glyph < 0 ? FINE_GLYPHS.length() : glyph + 1;
        }
        return width;
    }

    /**
     * How wide the label of {@code line} is — everything left of its first
     * {@code |} — or 0 for a line with no separator to line up.
     */
    static int labelWidth(String line) {
        if (line == null) {
            return 0;
        }
        int bar = line.indexOf('|');
        if (bar < 0) {
            return 0;
        }
        return pixelWidth(withoutCodes(trimPadding(line.substring(0, bar), false).replace("_", "")));
    }

    /** How wide {@code plain} is when the client draws it. */
    static int pixelWidth(String plain) {
        int width = 0;
        for (int i = 0; plain != null && i < plain.length(); i++) {
            char c = plain.charAt(i);
            width += c >= ' ' && c <= '~' ? ADVANCES[c - ' '] : FALLBACK_WIDTH;
        }
        return width;
    }

    /**
     * Takes the spacing out of one half of a line: the spaces that trail a
     * label, or lead a value, only to push the {@code |} around. Colour codes
     * are stepped over instead of counted as text, so each half of the line
     * keeps the colours it was written with.
     *
     * @param leading true to trim the half's leading spaces, false its
     *                trailing ones
     */
    private static String trimPadding(String part, boolean leading) {
        if (part == null || part.isEmpty()) {
            return part == null ? "" : part;
        }
        int length = part.length();
        boolean[] drop = new boolean[length];
        if (leading) {
            for (int i = 0; i < length; ) {
                char c = part.charAt(i);
                if (c == ' ') {
                    drop[i++] = true;
                } else if (c == '&' && i + 1 < length && isCode(part.charAt(i + 1))) {
                    i += 2;
                } else {
                    break;
                }
            }
        } else {
            for (int i = length - 1; i >= 0; ) {
                char c = part.charAt(i);
                if (c == ' ') {
                    drop[i--] = true;
                } else if (c != '&' && i > 0 && part.charAt(i - 1) == '&' && isCode(c)) {
                    // The second half of a colour code: step over both.
                    i -= 2;
                } else {
                    break;
                }
            }
        }
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            if (!drop[i]) {
                out.append(part.charAt(i));
            }
        }
        return out.toString();
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
