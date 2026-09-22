package me.foivos.planets;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

/**
 * Reads values from PlaceholderAPI without a compile-time dependency, so the
 * plugin works whether or not PlaceholderAPI is installed (same approach as
 * {@link MultiverseHook}). Used to show a player's NEB value next to their VPL
 * balance: the placeholder is configured under {@code leaderboards.neb-placeholder}
 * in config.yml, and every failure path returns 0 instead of throwing.
 */
final class Placeholders {

    private Placeholders() {
    }

    /** Cached {@code PlaceholderAPI.setPlaceholders(OfflinePlayer, String)}. */
    private static Method setPlaceholders;

    /** Whether PlaceholderAPI is installed and usable right now. */
    static boolean isPresent() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    /**
     * Applies placeholders for a (possibly offline) player.
     *
     * @return the resolved text, or null when PlaceholderAPI is missing or failed.
     */
    static String apply(OfflinePlayer player, String text) {
        if (player == null || text == null || text.isEmpty() || !isPresent()) {
            return null;
        }
        try {
            if (setPlaceholders == null) {
                Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                setPlaceholders = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            }
            Object result = setPlaceholders.invoke(null, player, text);
            return result instanceof String resolved ? resolved : null;
        } catch (Throwable ex) {
            // Any problem (API version mismatch, unparsable placeholder, ...) is
            // treated as "no value" so the leaderboard always renders.
            return null;
        }
    }

    /**
     * Resolves a placeholder to a number (e.g. a currency amount). Returns the
     * fallback when the placeholder is missing, unresolved (still contains a
     * {@code %}) or not numeric. Handles both {@code 1,234.5} and {@code 1.234,5}
     * style output.
     */
    static double readNumber(OfflinePlayer player, String placeholder, double fallback) {
        String raw = apply(player, placeholder);
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.indexOf('%') >= 0) {
            return fallback; // unresolved placeholder
        }
        // Keep digits, separators and a sign only.
        value = value.replaceAll("[^0-9,.\\-]", "");
        if (value.isEmpty()) {
            return fallback;
        }
        int lastComma = value.lastIndexOf(',');
        int lastDot = value.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            // Both present: the right-most one is the decimal separator.
            value = lastComma > lastDot
                    ? value.replace(".", "").replace(',', '.')
                    : value.replace(",", "");
        } else if (lastComma >= 0) {
            // A single comma: thousands separator when it groups 3 digits.
            String afterComma = value.substring(lastComma + 1);
            value = afterComma.length() == 3 && value.indexOf(',') == lastComma
                    ? value.replace(",", "")
                    : value.replace(',', '.');
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
