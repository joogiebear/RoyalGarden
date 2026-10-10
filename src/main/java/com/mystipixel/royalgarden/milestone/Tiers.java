package com.mystipixel.royalgarden.milestone;

import java.util.List;

/** Milestone tier arithmetic. Pure, so it can be tested without a server. */
public final class Tiers {

    private static final int[] ROMAN_VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN_SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    private Tiers() {
    }

    /**
     * Running totals from a growth curve: tier 1 needs {@code first}, each tier after needs {@code growth}
     * times the one before, rounded to a multiple of {@code roundTo}. {@code first: 30, growth: 1.5} gives
     * totals 30, 75, 145...
     */
    public static long[] curve(long first, double growth, int count, long roundTo) {
        long[] out = new long[Math.max(0, count)];
        double step = Math.max(1, first);
        long total = 0;
        long unit = Math.max(1, roundTo);
        for (int i = 0; i < out.length; i++) {
            long rounded = Math.max(unit, Math.round(step / unit) * unit);
            total += rounded;
            out[i] = total;
            step *= Math.max(1.0, growth);
        }
        return out;
    }

    /**
     * Anything that does not strictly increase is dropped, so a typo cannot make a tier reachable before the
     * one below it.
     */
    public static long[] explicit(List<? extends Number> amounts) {
        long[] out = new long[amounts.size()];
        int kept = 0;
        long previous = 0;
        for (Number amount : amounts) {
            long value = amount.longValue();
            if (value > previous) {
                out[kept++] = value;
                previous = value;
            }
        }
        long[] trimmed = new long[kept];
        System.arraycopy(out, 0, trimmed, 0, kept);
        return trimmed;
    }

    /** Roman numerals ({@code XXIV}); 0 is shown as {@code 0}. */
    public static String roman(int number) {
        if (number <= 0) {
            return "0";
        }
        StringBuilder out = new StringBuilder();
        int rest = number;
        for (int i = 0; i < ROMAN_VALUES.length; i++) {
            while (rest >= ROMAN_VALUES[i]) {
                out.append(ROMAN_SYMBOLS[i]);
                rest -= ROMAN_VALUES[i];
            }
        }
        return out.toString();
    }

    public static String bar(double fraction, int length, String filled, String empty) {
        int done = (int) Math.round(Math.clamp(fraction, 0.0, 1.0) * length);
        return filled.repeat(done) + empty.repeat(Math.max(0, length - done));
    }
}
