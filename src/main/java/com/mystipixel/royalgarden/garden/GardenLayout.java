package com.mystipixel.royalgarden.garden;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * The Garden's grid, and the only place that knows its arithmetic. Default 5x5 plots of 96 blocks,
 * from {@code layout:} once at startup. The centre plot is centred on (0, 0), so the default board spans
 * -240..239, exactly the world border's [-240, 240]: "inside the border" and "on a plot" agree.
 */
public final class GardenLayout {

    /** Five rows of plots plus a row of controls. */
    public static final int MAX_GRID = 5;

    private static int plotSize = 96;
    private static int grid = 5;
    private static int groundY = 100;

    private GardenLayout() {
    }

    /**
     * Returns the problem with the values, or null if accepted. Rejected values leave the previous shape in
     * place rather than half-applying.
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

    public static int plotSize() {
        return plotSize;
    }

    /** Always odd: an even board has no middle for the farmhouse. */
    public static int grid() {
        return grid;
    }

    public static int span() {
        return plotSize * grid;
    }

    /** The centre plot is {@code (centre(), centre())}. */
    public static int centre() {
        return grid / 2;
    }

    /** Blocks are laid <em>at</em> this level; you stand at {@code groundY() + 1}. */
    public static int groundY() {
        return groundY;
    }

    /** Three below the surface. */
    public static int floorY() {
        return groundY - 3;
    }

    /** Every plot but the farmhouse; what players are shown as the board's size. */
    public static int farmableCount() {
        return plotCount() - 1;
    }

    public static int plotCount() {
        return grid * grid;
    }

    public static int plotMin(int p) {
        return (p - centre()) * plotSize - plotSize / 2;
    }

    public static int plotMax(int p) {
        return plotMin(p) + plotSize - 1;
    }

    /** {@code -1} if it falls off the board. */
    public static int plotAt(int v) {
        // floorDiv, not integer division: the board straddles zero, and `/` rounds toward zero, which
        // would fold the two plots either side of the origin onto the same index.
        int p = Math.floorDiv(v + plotSize / 2, plotSize) + centre();
        return inGrid(p) ? p : -1;
    }

    public static boolean inGrid(int p) {
        return p >= 0 && p < grid;
    }

    public static boolean inGrid(int px, int pz) {
        return inGrid(px) && inGrid(pz);
    }

    /** The farmhouse plot, always unlocked. */
    public static boolean isCentre(int px, int pz) {
        return px == centre() && pz == centre();
    }

    public static int boardMin() {
        return plotMin(0);
    }

    public static int boardMax() {
        return plotMax(grid - 1);
    }

    public static boolean onBoard(int x, int z) {
        return x >= boardMin() && x <= boardMax() && z >= boardMin() && z <= boardMax();
    }

    /** {@code A1} through {@code E5} on the default board, west to east then north to south. */
    public static String plotName(int px, int pz) {
        if (!inGrid(px, pz)) {
            return "off-board";
        }
        return String.valueOf((char) ('A' + px)) + (pz + 1);
    }

    /** As {@code {px, pz}}, or null if it names nothing on this board. Case-insensitive. */
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

    /** Feet on the surface. */
    public static Location plotCentre(World world, int px, int pz) {
        double x = plotMin(px) + plotSize / 2.0 + 0.5;
        double z = plotMin(pz) + plotSize / 2.0 + 0.5;
        return new Location(world, x, groundY + 1, z);
    }
}
