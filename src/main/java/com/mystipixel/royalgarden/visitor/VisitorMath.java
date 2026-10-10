package com.mystipixel.royalgarden.visitor;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.function.ToDoubleFunction;
import java.util.random.RandomGenerator;

/**
 * Visitor arithmetic: weighted picks, amount ranges, request scaling and arrival timing. Pure, so it can
 * be tested without a server.
 */
public final class VisitorMath {

    private VisitorMath() {
    }

    /** An inclusive whole-number range, written {@code 64} or {@code 32-64} in config. */
    public record Range(long min, long max) {

        public static final Range ZERO = new Range(0, 0);

        /** {@code "32-64"}, {@code "64"} or a number. Anything unreadable is {@code null}. */
        public static Range parse(Object raw) {
            if (raw instanceof Number number) {
                long value = Math.max(0, number.longValue());
                return new Range(value, value);
            }
            if (raw == null) {
                return null;
            }
            String text = raw.toString().trim().replace(",", "").replace("_", "");
            if (text.isEmpty()) {
                return null;
            }
            int dash = text.indexOf('-', 1);
            try {
                if (dash < 0) {
                    long value = Math.max(0, Long.parseLong(text));
                    return new Range(value, value);
                }
                long a = Math.max(0, Long.parseLong(text.substring(0, dash).trim()));
                long b = Math.max(0, Long.parseLong(text.substring(dash + 1).trim()));
                return new Range(Math.min(a, b), Math.max(a, b));
            } catch (NumberFormatException notARange) {
                return null;
            }
        }

        public long roll(RandomGenerator random) {
            return min == max ? min : random.nextLong(min, max + 1);
        }

        /** {@code 1-3 + 10} is {@code 11-13}. */
        public Range plus(Range other) {
            return other == null ? this : new Range(min + other.min, max + other.max);
        }
    }

    /**
     * {@code roll} is uniform in {@code [0, 1)}. Entries with no weight (zero, negative or NaN) are never
     * picked; with nothing weighted the result is {@code null}.
     */
    public static <T> T pickWeighted(List<T> entries, ToDoubleFunction<T> weight, double roll) {
        double total = 0;
        for (T entry : entries) {
            total += usable(weight.applyAsDouble(entry));
        }
        if (total <= 0) {
            return null;
        }
        double target = Math.clamp(roll, 0.0, Math.nextDown(1.0)) * total;
        T last = null;
        for (T entry : entries) {
            double w = usable(weight.applyAsDouble(entry));
            if (w <= 0) {
                continue;
            }
            last = entry;
            if (target < w) {
                return entry;
            }
            target -= w;
        }
        return last;                              // only reachable through rounding at the top end
    }

    private static double usable(double weight) {
        return Double.isFinite(weight) && weight > 0 ? weight : 0;
    }

    /**
     * {@code 1 + perLevel * (level - 1)}, never below 1 and never above {@code max} (a {@code max} under 1
     * means no cap).
     */
    public static double multiplier(int level, double perLevel, double max) {
        double value = 1.0 + Math.max(0.0, perLevel) * Math.max(0, level - 1);
        if (max >= 1.0) {
            value = Math.min(value, max);
        }
        return Math.max(1.0, value);
    }

    /** Rounded, and never below 1 when it started above 0. */
    public static long scale(long amount, double multiplier) {
        if (amount <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(amount * Math.max(0.0, multiplier)));
    }

    /** The entry with the highest key at or below {@code level}, or {@code fallback}. */
    public static int byLevel(NavigableMap<Integer, Integer> table, int level, int fallback) {
        Map.Entry<Integer, Integer> entry = table == null ? null : table.floorEntry(level);
        return entry == null ? fallback : entry.getValue();
    }

    /**
     * @param count       how many arrive
     * @param lastArrival what the arrival clock should read afterwards
     */
    public record Due(int count, long lastArrival) {
    }

    /**
     * Visitors arrived since {@code lastArrival}, one per {@code interval}. The clock stands still while the
     * queue is full. Arrivals that do not fit are lost and the clock restarts now, so freeing a place later
     * does not release a backlog at once.
     */
    public static Due due(long lastArrival, long now, long interval, int queued, int max) {
        if (interval <= 0 || queued >= max || now <= lastArrival) {
            return new Due(0, lastArrival);
        }
        long elapsed = (now - lastArrival) / interval;
        if (elapsed <= 0) {
            return new Due(0, lastArrival);
        }
        int room = max - queued;
        if (elapsed >= room) {
            return new Due(room, elapsed == room ? lastArrival + room * interval : now);
        }
        return new Due((int) elapsed, lastArrival + elapsed * interval);
    }

    /**
     * The arrival clock after time away. Offline, visitors come at {@code rate} of the online speed (1 every
     * minute counts, 0 none); the time that does not count pushes the next arrival back.
     */
    public static long creditOffline(long lastArrival, long leftAt, long now, double rate) {
        if (leftAt <= 0 || now <= leftAt) {
            return lastArrival;
        }
        double counted = Math.clamp(rate, 0.0, 1.0);
        long away = now - leftAt;
        return lastArrival + Math.round(away * (1.0 - counted));
    }
}
