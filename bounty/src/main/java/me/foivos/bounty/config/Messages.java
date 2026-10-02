package me.foivos.bounty.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every line the plugin says, read from {@code messages} in config.yml.
 *
 * <p>Nothing user-facing is hard-coded in a class: each message has a key, is
 * written with {@code &} colour codes, and takes the {@code %placeholders%}
 * named in config.yml beside it. A message that is missing from the config
 * simply renders as nothing rather than throwing, so a half-edited config.yml
 * cannot break the board.
 *
 * <p>Values that are lists are the multi-line messages (the bounty-increased
 * note, the claim notices), read with {@link #lines} or {@link #lore}.
 */
public final class Messages {

    /** The lines, keyed by their dotted path under {@code messages}. */
    private final Map<String, List<String>> lines = new HashMap<>();

    /** Reads the {@code messages} section. Called on enable and on reload. */
    public void load(ConfigurationSection messages) {
        lines.clear();
        if (messages != null) {
            collect(messages, "");
        }
    }

    private void collect(ConfigurationSection section, String path) {
        for (String key : section.getKeys(false)) {
            String full = path.isEmpty() ? key : path + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                collect(child, full);
                continue;
            }
            List<String> value = section.isList(key)
                    ? section.getStringList(key)
                    : List.of(String.valueOf(section.get(key)));
            lines.put(full, value);
        }
    }

    // ── Raw text ────────────────────────────────────────────────────────

    /** One message as written, placeholders filled, colours left as codes. */
    public String text(String key, Map<String, String> placeholders) {
        return fill(first(key), placeholders);
    }

    /** One message as written, with no placeholders to fill. */
    public String text(String key) {
        return first(key);
    }

    /** The first line of a message (multi-line messages have more). */
    public String first(String key) {
        List<String> value = lines.get(key);
        return value == null || value.isEmpty() ? "" : value.get(0);
    }

    /** Every line of a message, placeholders filled. */
    public List<String> lines(String key, Map<String, String> placeholders) {
        List<String> value = lines.get(key);
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        List<String> filled = new ArrayList<>(value.size());
        for (String line : value) {
            filled.add(fill(line, placeholders));
        }
        return filled;
    }

    /** Whether a key exists in config.yml at all. */
    public boolean has(String key) {
        return lines.containsKey(key);
    }

    private static String fill(String raw, Map<String, String> placeholders) {
        String filled = raw == null ? "" : raw;
        if (placeholders == null || placeholders.isEmpty()) {
            return filled;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            filled = filled.replace("%" + entry.getKey() + "%",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return filled;
    }

    // ── Components ──────────────────────────────────────────────────────

    /** A message as a component, with {@code &} codes turned into colours. */
    public Component component(String key, Map<String, String> placeholders) {
        return of(text(key, placeholders));
    }

    /** Every line of a message as components, ready for an item's lore. */
    public List<Component> lore(String key, Map<String, String> placeholders) {
        List<Component> components = new ArrayList<>();
        for (String line : lines(key, placeholders)) {
            components.add(of(line));
        }
        return components;
    }

    /** The plugin's prefix followed by a message. */
    public Component prefixed(String key, Map<String, String> placeholders) {
        return of(first("prefix")).append(component(key, placeholders));
    }

    /** A message, exactly as written, as a component. */
    public Component of(String raw) {
        return noItalic(LegacyComponentSerializer.legacyAmpersand().deserialize(raw == null ? "" : raw));
    }

    /**
     * Turns italic off wherever the text did not ask for it.
     *
     * <p>Vanilla italicises item names and lore on its own, so every component
     * that ends up on an item is flattened here — unless the message says
     * {@code &o}, which is left alone.
     */
    public static Component noItalic(Component component) {
        Component fixed = component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        List<Component> children = new ArrayList<>(fixed.children());
        if (children.isEmpty()) {
            return fixed;
        }
        Component withoutChildren = fixed.children(List.of());
        for (Component child : children) {
            withoutChildren = withoutChildren.append(noItalic(child));
        }
        return withoutChildren;
    }

    // ── Sending ─────────────────────────────────────────────────────────

    /** Sends a prefixed message to a player, if the message is not blank. */
    public void send(CommandSender to, String key, Map<String, String> placeholders) {
        if (to == null || !has(key)) {
            return;
        }
        to.sendMessage(prefixed(key, placeholders));
    }

    /** Sends a message exactly as written, with no prefix. */
    public void sendRaw(CommandSender to, String key, Map<String, String> placeholders) {
        if (to == null || !has(key)) {
            return;
        }
        to.sendMessage(component(key, placeholders));
    }

    /** Sends a multi-line message, line by line. */
    public void sendLines(CommandSender to, String key, Map<String, String> placeholders) {
        if (to == null) {
            return;
        }
        for (String line : lines(key, placeholders)) {
            to.sendMessage(of(line));
        }
    }
}
