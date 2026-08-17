package com.mystipixel.royalgarden.garden;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * The Garden's grid, and the only place that knows its arithmetic.
 *
 * <p>A 5&times;5 board of 96&times;96 plots &mdash; 480 blocks square, centred on the world origin. The
 * middle plot holds the farmhouse; the other twenty-four unlock one at a time.
 *
 * <p>The centre plot is centred on {@code (0, 0)} rather than starting there, so the barn sits at the
 * world origin and every plot index reads symmetrically as an offset from the middle. That makes plot
 * {@code (2, 2)} span {@code -48..47} on both axes, and the board as a whole span {@code -240..239}.
 * Those 480 columns are exactly the world border's {@code [-240, 240]} span, so "inside the border"
 * and "on a plot" are the same statement rather than two that drift apart.
 *
 * <p>All of this is deliberately static and dependency-free: it is arithmetic, it is the thing every
 * other class in the extension agrees about, and it is the part worth being able to reason about
 * without a server running.
 */
public final class GardenLayout {

    /** Side length of one plot, in blocks. */
    public static final int PLOT_SIZE = 96;

    /** Plots per side. Odd on purpose — an even board has no middle to put the farmhouse on. */
    public static final int GRID = 5;

    /** Side length of the whole board: {@value #GRID} &times; {@value #PLOT_SIZE} = 480. */
    public static final int SPAN = PLOT_SIZE * GRID;

    /** Index of the middle row/column, so the centre plot is {@code (CENTRE, CENTRE)}. */
    public static final int CENTRE = GRID / 2;

    /** Y of the plot surface. Blocks are laid <em>at</em> this level; you stand at {@code GROUND_Y + 1}. */
    public static final int GROUND_Y = 100;

    private GardenLayout() {
    }

    /** Lowest block coordinate covered by plot index {@code p} on either axis. */
    public static int plotMin(int p) {
        return (p - CENTRE) * PLOT_SIZE - PLOT_SIZE / 2;
    }

    /** Highest block coordinate covered by plot index {@code p} on either axis. */
    public static int plotMax(int p) {
        return plotMin(p) + PLOT_SIZE - 1;
    }

    /** The plot index covering block coordinate {@code v}, or {@code -1} if it falls off the board. */
    public static int plotAt(int v) {
        // floorDiv, not integer division: the board straddles zero, and `/` rounds toward zero, which
        // would fold the two plots either side of the origin onto the same index.
        int p = Math.floorDiv(v + PLOT_SIZE / 2, PLOT_SIZE) + CENTRE;
        return inGrid(p) ? p : -1;
    }

    /** Whether a plot index is on the board. */
    public static boolean inGrid(int p) {
        return p >= 0 && p < GRID;
    }

    /** Whether both indices are on the board. */
    public static boolean inGrid(int px, int pz) {
        return inGrid(px) && inGrid(pz);
    }

    /** Whether this is the middle plot — the one the farmhouse stands on, always unlocked. */
    public static boolean isCentre(int px, int pz) {
        return px == CENTRE && pz == CENTRE;
    }

    /** Lowest block coordinate on the whole board, on either axis. */
    public static int boardMin() {
        return plotMin(0);
    }

    /** Highest block coordinate on the whole board, on either axis. */
    public static int boardMax() {
        return plotMax(GRID - 1);
    }

    /** Whether a block coordinate pair is anywhere on the board. */
    public static boolean onBoard(int x, int z) {
        return x >= boardMin() && x <= boardMax() && z >= boardMin() && z <= boardMax();
    }

    /**
     * A human-facing name for a plot: {@code A1} through {@code E5}, reading west-to-east then
     * north-to-south, so the middle plot is {@code C3}.
     */
    public static String plotName(int px, int pz) {
        if (!inGrid(px, pz)) {
            return "off-board";
        }
        return String.valueOf((char) ('A' + px)) + (pz + 1);
    }

    /** Standing position at the centre of a plot, feet on the surface. */
    public static Location plotCentre(World world, int px, int pz) {
        double x = plotMin(px) + PLOT_SIZE / 2.0;
        double z = plotMin(pz) + PLOT_SIZE / 2.0;
        return new Location(world, x, GROUND_Y + 1, z);
    }
}
