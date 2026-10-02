package me.foivos.planets.casino;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns the {@code &}-codes this server writes in config.yml into components.
 *
 * <p>Every message the casino shows comes out of {@code casino.messages} in
 * config.yml, so it has to be written the way the rest of the file is written
 * — {@code &7grey}, {@code &lbold}. The plugin parses those codes by hand
 * rather than through a serialiser library, and this is the same small parser;
 * it is deliberately its own copy, because the sidebar's version also strips
 * the underscores it uses as alignment padding, which would eat a real
 * underscore in a message.
 */
public final class CasinoText {

    private static final Map<Character, NamedTextColor> COLOURS = Map.ofEntries(
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

    private CasinoText() {
    }

    /** Parses {@code text}, colouring anything written without a code itself. */
    public static Component legacy(String text, NamedTextColor fallback) {
        String plain = text == null ? "" : text;
        Component root = Component.empty();
        NamedTextColor colour = fallback;
        Set<TextDecoration> styles = EnumSet.noneOf(TextDecoration.class);
        StringBuilder run = new StringBuilder();
        int i = 0;
        while (i < plain.length()) {
            char c = plain.charAt(i);
            if (c == '&' && i + 1 < plain.length() && isCode(plain.charAt(i + 1))) {
                root = root.append(part(run.toString(), colour, styles));
                run.setLength(0);
                char code = Character.toLowerCase(plain.charAt(i + 1));
                NamedTextColor named = COLOURS.get(code);
                if (named != null) {
                    colour = named;
                } else if (code == 'r') {
                    colour = fallback;
                    styles.clear();
                } else {
                    styles.add(style(code));
                }
                i += 2;
                continue;
            }
            run.append(c);
            i++;
        }
        return root.append(part(run.toString(), colour, styles));
    }

    /** Parses {@code text} in white, for messages with no colour of their own. */
    public static Component legacy(String text) {
        return legacy(text, NamedTextColor.WHITE);
    }

    /** The same text with every {@code &}-code taken out. */
    public static String plain(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length() && isCode(text.charAt(i + 1))) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static Component part(String text, NamedTextColor colour, Set<TextDecoration> styles) {
        Component part = Component.text(text).color(colour)
                .decoration(TextDecoration.ITALIC, false);
        for (TextDecoration style : styles) {
            part = part.decoration(style, true);
        }
        return part;
    }

    private static TextDecoration style(char code) {
        return switch (code) {
            case 'l' -> TextDecoration.BOLD;
            case 'n' -> TextDecoration.UNDERLINED;
            case 'm' -> TextDecoration.STRIKETHROUGH;
            case 'o' -> TextDecoration.ITALIC;
            default -> TextDecoration.OBFUSCATED;
        };
    }

    private static boolean isCode(char raw) {
        char code = Character.toLowerCase(raw);
        return COLOURS.containsKey(code) || "lonmkr".indexOf(code) >= 0;
    }
}
