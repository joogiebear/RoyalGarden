package com.mystipixel.royalgarden.config;

import java.util.Locale;

/**
 * Durations as admins write them: {@code 2d}, {@code 4h30m}, {@code 45s}, or {@code 0} for none.
 *
 * <p>The same shorthand RoyalSkyblock's upgrade {@code time:} uses, so one config vocabulary covers
 * both plugins.
 */
public final class Durations {

    private Durations() {
    }

    /**
     * Parse a duration into milliseconds. Returns {@code 0} for blank or unparseable input, which every
     * caller reads as "no wait" — a typo should cost a cooldown, not a player's access.
     */
    public static long parseMillis(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        long total = 0;
        long number = 0;
        boolean digits = false;
        for (char ch : s.toCharArray()) {
            if (Character.isDigit(ch)) {
                number = number * 10 + (ch - '0');
                digits = true;
                continue;
            }
            long unit = switch (ch) {
                case 'd' -> 86_400_000L;
                case 'h' -> 3_600_000L;
                case 'm' -> 60_000L;
                case 's' -> 1_000L;
                case ' ' -> -1L;
                default -> -2L;
            };
            if (unit == -1L) {
                continue;
            }
            if (unit == -2L || !digits) {
                return 0;
            }
            total += number * unit;
            number = 0;
            digits = false;
        }
        // A bare number is seconds, matching how `time: 30` reads to most people.
        return total + (digits ? number * 1_000L : 0);
    }

    /** Format milliseconds for a player: {@code 1h 5m}, {@code 30s}, never more than two units. */
    public static String format(long millis) {
        long seconds = Math.max(0, (millis + 999) / 1000);
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long secs = seconds % 60;
        StringBuilder out = new StringBuilder();
        int parts = 0;
        for (long[] unit : new long[][]{{days, 'd'}, {hours, 'h'}, {minutes, 'm'}, {secs, 's'}}) {
            if (unit[0] > 0 && parts < 2) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(unit[0]).append((char) unit[1]);
                parts++;
            }
        }
        return out.length() == 0 ? "0s" : out.toString();
    }
}
