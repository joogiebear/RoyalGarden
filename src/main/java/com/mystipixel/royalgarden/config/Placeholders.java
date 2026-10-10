package com.mystipixel.royalgarden.config;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * PlaceholderAPI through reflection, so the extension still loads without it. With it absent, text passes
 * through unchanged and any placeholder requirement reads as unmet.
 */
public final class Placeholders {

    private static Method setPlaceholders;
    private static boolean resolved;

    private Placeholders() {
    }

    public static boolean available() {
        return method() != null;
    }

    /** Returns the text unchanged if PlaceholderAPI is absent. */
    public static String apply(Player player, String text) {
        Method method = method();
        if (method == null || text == null || text.indexOf('%') < 0) {
            return text;
        }
        try {
            return (String) method.invoke(null, player, text);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return text;
        }
    }

    /**
     * Reads the digits in whatever the placeholder returns, so {@code "1,250"} and {@code "Level 12"} both
     * work. {@code NaN} if nothing numeric came back.
     */
    public static double number(Player player, String placeholder) {
        String value = apply(player, placeholder);
        if (value == null || value.equals(placeholder)) {
            return Double.NaN;
        }
        StringBuilder digits = new StringBuilder();
        for (char ch : value.toCharArray()) {
            if (Character.isDigit(ch) || ch == '.' || (ch == '-' && digits.length() == 0)) {
                digits.append(ch);
            }
        }
        try {
            return digits.length() == 0 ? Double.NaN : Double.parseDouble(digits.toString());
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }

    private static synchronized Method method() {
        if (resolved) {
            return setPlaceholders;
        }
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            return null;                          // not cached: it may still be enabling
        }
        try {
            // Through PlaceholderAPI's own class loader: ours only sees the host and its parents.
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true,
                    papi.getClass().getClassLoader());
            setPlaceholders = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        } catch (ReflectiveOperationException | LinkageError unusable) {
            setPlaceholders = null;
        }
        resolved = true;
        return setPlaceholders;
    }
}
