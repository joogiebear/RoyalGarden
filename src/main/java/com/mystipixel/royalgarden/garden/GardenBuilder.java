package com.mystipixel.royalgarden.garden;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Generates a new Garden's centre plot and farmhouse. Only the centre is built here, synchronously, so
 * the player has somewhere to stand; the rest is laid by {@link GardenWorlds#sync} through the {@link
 * BuildQueue}. Writes blocks, so main thread only.
 */
public final class GardenBuilder {

    // Half-width of the barn's floor plan; the building is BARN_X * 2 + 1 across.
    private static final int BARN_X = 7;

    private static final int BARN_Z = 5;

    // Wall height between the floor and the eaves.
    private static final int WALL = 5;

    /**
     * The barn as {@link #build} makes it now; older Gardens are brought up to date by {@link #renovate}.
     * Each version only adds blocks, never rebuilds.
     *
     * <ol>
     *   <li>the original barn;</li>
     *   <li>the drive-through window and the lane visitors queue along.</li>
     * </ol>
     */
    public static final int BARN_VERSION = 2;

    // Kept only so old Gardens can be cleaned up.
    private static final Material LEGACY_BOUNDARY = Material.STONE_BRICK_WALL;

    private GardenBuilder() {
    }

    /**
     * On the path just outside the barn doors, looking at them, so the spawn can never be inside a block if
     * the barn is reshaped.
     */
    public static Location spawn(World world) {
        Farmhouse.Anchor marked = Farmhouse.current().anchor("spawn");
        if (marked != null) {
            return marked.at(world);
        }
        Location spawn = new Location(world, 0.5, GardenLayout.groundY() + 1, BARN_Z + 4.5);
        spawn.setYaw(180f);   // facing -z, toward the doors
        spawn.setPitch(0f);
        return spawn;
    }

    /** Behind the desk at the back of the barn, facing the doors (yaw 0 is +z). */
    public static Location samPost(World world) {
        Farmhouse.Anchor marked = Farmhouse.current().anchor("sam");
        if (marked != null) {
            return marked.at(world);
        }
        Location post = new Location(world, 0.5, GardenLayout.groundY() + 1, -BARN_Z + 1.5);
        post.setYaw(0f);
        post.setPitch(0f);
        return post;
    }

    /** Into a freshly created world; the farmhouse is built-in or a schematic. */
    public static void build(World world, Palette palette, java.util.logging.Logger logger) {
        for (int row = 0; row < GardenLayout.plotSize(); row++) {
            generatePlotRow(world, GardenLayout.centre(), GardenLayout.centre(), row, Base.FLAT, palette);
        }
        Farmhouse.current().build(world, logger);
    }

    // Onto bare ground, with its path and drive-through.
    static void buildBarn(World world) {
        barn(world);
        approach(world);
        driveThrough(world);
    }

    /** From barn version {@code from} up to {@link #BARN_VERSION}. */
    public static void renovate(World world, int from) {
        if (from < 2) {
            driveThrough(world);
        }
    }

    // A window east of the doors with a counter and awning, and a lane south for the queue. The line's front
    // spot in visitors.yml (6, 6.5) stands in this opening: moving one means moving the other.
    private static void driveThrough(World world) {
        int y = GardenLayout.groundY();
        int wall = BARN_Z;
        for (int x = 5; x <= 6; x++) {
            world.getBlockAt(x, y + 1, wall).setType(Material.SPRUCE_PLANKS, false);      // the counter
            world.getBlockAt(x, y + 2, wall).setType(Material.AIR, false);
            world.getBlockAt(x, y + 3, wall).setType(Material.AIR, false);
        }
        for (int x = 4; x <= 7; x++) {
            setData(world, x, y + 4, wall + 1, "minecraft:spruce_trapdoor[half=top,open=false,facing=north]");
        }
        setData(world, 4, y + 1, wall + 1, "minecraft:spruce_fence");
        setData(world, 4, y + 2, wall + 1, "minecraft:lantern[hanging=false]");

        // The lane: two wide, south from the window, as long as the default line and a bit.
        for (int z = wall + 1; z <= wall + 15; z++) {
            for (int x = 5; x <= 6; x++) {
                world.getBlockAt(x, y, z).setType(Material.DIRT_PATH, false);
            }
        }
    }

    /**
     * What a generated plot is made of, from {@code generation.blocks} in {@code config.yml}.
     *
     * @param surface the top course
     * @param rim     the top course around the plot's edge, so plots read as separate fields
     * @param subsoil the two courses under the surface
     * @param floor   the bottom course, the only thing between a shovel and the void
     */
    public record Palette(Material surface, Material rim, Material subsoil, Material floor) {
        public static final Palette DEFAULT =
                new Palette(Material.GRASS_BLOCK, Material.DIRT_PATH, Material.DIRT, Material.BEDROCK);
    }

    /**
     * The two plots that are generated rather than captured. Not in the preset library: they need no file,
     * cannot be deleted, and are the states a plot can always return to.
     */
    public enum Base {
        /** Grass over dirt over bedrock, with a path around the rim. */
        FLAT,
        /** The bedrock course and nothing else. */
        BEDROCK
    }

    /** Blocks written by one call to {@link #generatePlotRow}: four courses across the plot. */
    public static int rowCost() {
        return GardenLayout.plotSize() * 4;
    }

    /** Where clearing starts. */
    public static int baseTop() {
        return GardenLayout.groundY() + 1;
    }

    /** The top of the tallest template allowed, so clearing knows where to stop. */
    public static int ceiling(int maxTemplateHeight) {
        return GardenLayout.floorY() + maxTemplateHeight;
    }

    /**
     * Clears one x-row of a plot from {@code fromY} up to the ceiling, so a short preset stamped over a tall
     * build leaves nothing floating. Only when applying: during generation everything above is already air.
     */
    public static int clearRowAbove(World world, int px, int pz, int row, int fromY, int top) {
        int x = GardenLayout.plotMin(px) + row;
        int zMin = GardenLayout.plotMin(pz);
        int cleared = 0;
        for (int y = fromY; y <= top; y++) {
            for (int z = zMin; z < zMin + GardenLayout.plotSize(); z++) {
                Block block = world.getBlockAt(x, y, z);
                if (!block.getType().isAir()) {
                    block.setType(Material.AIR, false);
                }
                cleared++;
            }
        }
        return cleared;
    }

    /**
     * Generates one x-row: surface over subsoil over floor, with a rim. A row at a time because the whole
     * default board is 230,400 columns, which in one go is seconds of frozen server.
     */
    public static int generatePlotRow(World world, int px, int pz, int row, Base base, Palette palette) {
        int y = GardenLayout.groundY();
        int x = GardenLayout.plotMin(px) + row;
        int zMin = GardenLayout.plotMin(pz);
        int zMax = GardenLayout.plotMax(pz);
        boolean edgeRow = row == 0 || row == GardenLayout.plotSize() - 1;

        for (int z = zMin; z <= zMax; z++) {
            if (base == Base.BEDROCK) {
                world.getBlockAt(x, y, z).setType(Material.AIR, false);
                world.getBlockAt(x, y - 1, z).setType(Material.AIR, false);
                world.getBlockAt(x, y - 2, z).setType(Material.AIR, false);
            } else {
                boolean rim = edgeRow || z == zMin || z == zMax;
                world.getBlockAt(x, y, z).setType(rim ? palette.rim() : palette.surface(), false);
                world.getBlockAt(x, y - 1, z).setType(palette.subsoil(), false);
                world.getBlockAt(x, y - 2, z).setType(palette.subsoil(), false);
            }
            world.getBlockAt(x, y - 3, z).setType(palette.floor(), false);
        }
        return rowCost();
    }

    /**
     * Judged by the bedrock floor under the plot's centre: every generated form lays it, while the surface
     * is legitimately air on a bedrock slab. The world is the authority, not a separate flag.
     */
    public static boolean isGenerated(World world, int px, int pz) {
        int half = GardenLayout.plotSize() / 2;
        return !world.getBlockAt(GardenLayout.plotMin(px) + half, GardenLayout.floorY(),
                GardenLayout.plotMin(pz) + half).getType().isAir();
    }

    /**
     * Strips stone brick plot edges left by an early version. Touches every plot edge (loading most chunks),
     * so it runs once per Garden; {@link GardenState#legacyWallsCleared()} records that it has.
     */
    public static void clearLegacyWalls(World world) {
        int y = GardenLayout.groundY() + 1;
        for (int p = 0; p < GardenLayout.grid(); p++) {
            for (int v = GardenLayout.boardMin(); v <= GardenLayout.boardMax(); v++) {
                clearWall(world, GardenLayout.plotMin(p), y, v);
                clearWall(world, GardenLayout.plotMax(p), y, v);
                clearWall(world, v, y, GardenLayout.plotMin(p));
                clearWall(world, v, y, GardenLayout.plotMax(p));
            }
        }
    }

    private static void clearWall(World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == LEGACY_BOUNDARY) {
            block.setType(Material.AIR, false);
        }
    }

    private static void approach(World world) {
        int y = GardenLayout.groundY();
        for (int z = BARN_Z; z <= BARN_Z + 6; z++) {
            for (int x = -1; x <= 1; x++) {
                world.getBlockAt(x, y, z).setType(Material.DIRT_PATH, false);
            }
        }
    }

    // The 15x11 barn, built in code rather than pasted so it needs no WorldEdit and follows the layout.
    private static void barn(World world) {
        int y = GardenLayout.groundY();

        // floor, and a hollow to stand in
        for (int x = -BARN_X; x <= BARN_X; x++) {
            for (int z = -BARN_Z; z <= BARN_Z; z++) {
                world.getBlockAt(x, y, z).setType(Material.SPRUCE_PLANKS, false);
                for (int h = 1; h <= WALL + 1; h++) {
                    world.getBlockAt(x, y + h, z).setType(Material.AIR, false);
                }
            }
        }

        // walls: red planking between spruce corner and rhythm posts
        for (int h = 1; h <= WALL; h++) {
            for (int x = -BARN_X; x <= BARN_X; x++) {
                for (int z = -BARN_Z; z <= BARN_Z; z++) {
                    if (Math.abs(x) != BARN_X && Math.abs(z) != BARN_Z) {
                        continue;
                    }
                    boolean corner = Math.abs(x) == BARN_X && Math.abs(z) == BARN_Z;
                    boolean post = corner
                            || (Math.abs(x) == BARN_X && z % 3 == 0)
                            || (Math.abs(z) == BARN_Z && x % 4 == 0);
                    world.getBlockAt(x, y + h, z)
                            .setType(post ? Material.SPRUCE_LOG : Material.RED_TERRACOTTA, false);
                }
            }
        }

        // windows down both long walls
        for (int x = -5; x <= 5; x += 5) {
            world.getBlockAt(x, y + 3, -BARN_Z).setType(Material.GLASS_PANE, false);
            world.getBlockAt(x, y + 3, BARN_Z).setType(Material.GLASS_PANE, false);
        }

        // the doorway: a 3-wide, 4-high opening in the +z wall, left open so nobody has to fight a door to
        // reach the desk
        for (int x = -1; x <= 1; x++) {
            for (int h = 1; h <= 4; h++) {
                world.getBlockAt(x, y + h, BARN_Z).setType(Material.AIR, false);
            }
        }
        setData(world, -2, y + 4, BARN_Z, "minecraft:spruce_stairs[facing=west,half=top]");
        setData(world, 2, y + 4, BARN_Z, "minecraft:spruce_stairs[facing=east,half=top]");

        roof(world, y);
        interior(world, y);
    }

    // A gabled roof, ridge along the long (x) axis.
    private static void roof(World world, int y) {
        for (int step = 0; step <= BARN_Z + 1; step++) {
            int level = y + WALL + 1 + step;
            int reach = BARN_Z + 1 - step;
            if (reach < 0) {
                break;
            }
            for (int x = -BARN_X - 1; x <= BARN_X + 1; x++) {
                if (reach == 0) {
                    world.getBlockAt(x, level, 0).setType(Material.DARK_OAK_PLANKS, false);   // ridge
                    continue;
                }
                setData(world, x, level, -reach, "minecraft:dark_oak_stairs[facing=south,half=bottom]");
                setData(world, x, level, reach, "minecraft:dark_oak_stairs[facing=north,half=bottom]");
                // close the gable ends so the loft isn't open to the sky
                if (Math.abs(x) == BARN_X + 1) {
                    for (int z = -reach + 1; z <= reach - 1; z++) {
                        world.getBlockAt(x, level, z).setType(Material.SPRUCE_PLANKS, false);
                    }
                }
            }
        }
    }

    private static void interior(World world, int y) {
        // the desk itself, at the back wall, facing the doors
        setData(world, 0, y + 1, -BARN_Z + 2, "minecraft:lectern[facing=south,has_book=false]");
        world.getBlockAt(-1, y + 1, -BARN_Z + 2).setType(Material.BARREL, false);
        world.getBlockAt(1, y + 1, -BARN_Z + 2).setType(Material.BARREL, false);

        // hay and stores down the west end
        for (int x = -BARN_X + 1; x <= -BARN_X + 3; x++) {
            for (int z = -2; z <= 2; z++) {
                world.getBlockAt(x, y + 1, z).setType(Material.HAY_BLOCK, false);
            }
        }
        for (int z = -1; z <= 1; z++) {
            world.getBlockAt(-BARN_X + 2, y + 2, z).setType(Material.HAY_BLOCK, false);
        }

        // composters and a water barrel down the east end, the working half
        for (int z = -3; z <= 3; z += 2) {
            world.getBlockAt(BARN_X - 1, y + 1, z).setType(Material.COMPOSTER, false);
        }
        world.getBlockAt(BARN_X - 3, y + 1, 0).setType(Material.CAULDRON, false);

        // lanterns hung from the tie beams
        for (int x = -4; x <= 4; x += 4) {
            setData(world, x, y + WALL, 0, "minecraft:lantern[hanging=true]");
        }
    }

    // Skips a state that is invalid on this version.
    private static void setData(World world, int x, int y, int z, String data) {
        try {
            world.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(data), false);
        } catch (IllegalArgumentException badData) {
            Block block = world.getBlockAt(x, y, z);
            block.setType(Material.SPRUCE_PLANKS, false);   // visible, wrong, and obviously so
        }
    }
}
