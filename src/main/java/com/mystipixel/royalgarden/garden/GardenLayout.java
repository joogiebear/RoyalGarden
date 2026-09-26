package com.mystipixel.royalgarden.garden;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * The Garden's grid, and the only place that knows its arithmetic.
 *
 * <p>By default a 5&times;5 board of 96&times;96 plots &mdash; 480 blocks square, centred on the world
 * origin. The middle plot holds the farmhouse; the others unlock one at a time. All three numbers come
 * from {@code layout:} in {@code config.yml} through {@link #configure}, once, at startup.
 *
 * <p>The centre plot is centred on {@code (0, 0)} rather than starting there, so the barn sits at the
 * world origin and every plot index reads symmetrically as an offset from the middle. With the defaults
 * plot {@code (2, 2)} spans {@code -48..47} on both axes, and the board as a whole spans
 * {@code -240..239}. Those 480 columns are exactly the world border's {@code [-240, 240]} span, so
 * "inside the border" and "on a plot" are the same statement rather than two that drift apart.
 *
 * <p>Everything is static arithmetic on purpose: it is the thing every other class in the extension
 * agrees about, and it is the part worth being able to reason about without a server running. The
 * values are fields rather than constants only so that they can be configured; they are set before any
 * Garden is opened and never change while the server runs.
 */
public final class GardenLayout {

    /** Largest board the desk menu can draw: five rows of plots plus a row of controls. */
    public static final int MAX_GRID = 5;

    private static int plotSize = 96;
    private static int grid = 5;
    private static int groundY = 100;

    private GardenLayout() {
    }

    /**
     * Set the board's shape. Returns the problem with the values, or null if they were accepted.
     *
     * <p>Rejected values leave the previous (default) shape in place rather than half-applying, so a
     * typo in the config cannot produce a board that disagrees with the Gardens already built on it.
     */
    public static String configure(int newGrid, int newPlotSize, int newGroundY) {
        if (newGrid < 1 || newGrid > MAX_GRID || newGrid % 2 == 0) {
            return "layout.grid must be an odd number from 1 to " + MAX_GRID + " (was " + newGrid + ")";
        }
        if (newPlotSize < 32 || newPlotSize > 512) {
            return "layout.plot-size must be between 32 and 512 (was " + newPlotSize + ")";
        }
        if (newGroundY < -60 || newGroundY > 250) {
            return "layout.ground-y must be between -60 and 250 (was " + newGroundY + ")";
        }
        grid = newGrid;
        plotSize = newPlotSize;
        groundY = newGroundY;
        return null;
    }

    /** Side length of one plot, in blocks. */
    public static int plotSize() {
        return plotSize;
    }

    /** Plots per side. Always odd — an even board has no middle to put the farmhouse on. */
    public static int grid() {
        return grid;
    }

    /** Side length of the whole board. */
    public static int span() {
        return plotSize * grid;
    }

    /** Index of the middle row/column, so the centre plot is {@code (centre(), centre())}. */
    public static int centre() {
        return grid / 2;
    }

    /** Y of the plot surface. Blocks are laid <em>at</em> this level; you stand at {@code groundY() + 1}. */
    public static int groundY() {
        return groundY;
    }

    /** Y of the floor course, three below the surface. */
    public static int floorY() {
        return groundY - 3;
    }

    /** How many plots the board has, farmhouse included. */
    /** Plots that can be farmed: every plot but the farmhouse. What players are shown as the board's size. */
    public static int farmableCount() {
        return plotCount() - 1;
    }

    public static int plotCount() {
        return grid * grid;
    }

    /** Lowest block coordinate covered by plot index {@code p} on either axis. */
    public static int plotMin(int p) {
        return (p - centre()) * plotSize - plotSize / 2;
    }

    /** Highest block coordinate covered by plot index {@code p} on either axis. */
    public static int plotMax(int p) {
        return plotMin(p) + plotSize - 1;
    }

    /** The plot index covering block coordinate {@code v}, or {@code -1} if it falls off the board. */
    public static int plotAt(int v) {
        // floorDiv, not integer division: the board straddles zero, and `/` rounds toward zero, which
        // would fold the two plots either side of the origin onto the same index.
        int p = Math.floorDiv(v + plotSize / 2, plotSize) + centre();
        return inGrid(p) ? p : -1;
    }

    /** Whether a plot index is on the board. */
    public static boolean inGrid(int p) {
        return p >= 0 && p < grid;
    }

    /** Whether both indices are on the board. */
    public static boolean inGrid(int px, int pz) {
        return inGrid(px) && inGrid(pz);
    }

    /** Whether this is the middle plot — the one the farmhouse stands on, always unlocked. */
    public static boolean isCentre(int px, int pz) {
        return px == centre() && pz == centre();
    }

    /** Lowest block coordinate on the whole board, on either axis. */
    public static int boardMin() {
        return plotMin(0);
    }

    /** Highest block coordinate on the whole board, on either axis. */
    public static int boardMax() {
        return plotMax(grid - 1);
    }

    /** Whether a block coordinate pair is anywhere on the board. */
    public static boolean onBoard(int x, int z) {
        return x >= boardMin() && x <= boardMax() && z >= boardMin() && z <= boardMax();
    }

    /**
     * A human-facing name for a plot: {@code A1} through {@code E5} on the default board, reading
     * west-to-east then north-to-south, so the middle plot is {@code C3}.
     */
    public static String plotName(int px, int pz) {
        if (!inGrid(px, pz)) {
            return "off-board";
        }
        return String.valueOf((char) ('A' + px)) + (pz + 1);
    }

    /**
     * The plot a name refers to, as {@code {px, pz}}, or null if it names nothing on this board.
     * Case-insensitive, so {@code b2} and {@code B2} are the same plot.
     */
    public static int[] parsePlot(String name) {
        if (name == null || name.length() < 2) {
            return null;
        }
        int px = Character.toUpperCase(name.charAt(0)) - 'A';
        int pz;
        try {
            pz = Integer.parseInt(name.substring(1)) - 1;
        } catch (NumberFormatException notAPlot) {
            return null;
        }
        return inGrid(px, pz) ? new int[]{px, pz} : null;
    }

    /** Standing position at the centre of a plot, feet on the surface. */
    public static Location plotCentre(World world, int px, int pz) {
        double x = plotMin(px) + plotSize / 2.0 + 0.5;
        double z = plotMin(pz) + plotSize / 2.0 + 0.5;
        return new Location(world, x, groundY + 1, z);
    }
}
