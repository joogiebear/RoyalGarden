package com.mystipixel.royalgarden.garden;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Generates a brand-new Garden: the centre plot and the farmhouse standing on it.
 *
 * <p><b>Only the centre plot is built.</b> The other twenty-four stay void until they are unlocked,
 * which is both the design (a plot you have not bought should look unbought, not merely fenced off)
 * and the cheap option — one plot is 9,216 columns, the whole board would be 230,400. Generating a
 * plot is what unlocking it will mean.
 *
 * <p>Everything here writes blocks, so it must run on the server thread. It is also deterministic:
 * every Garden is identical, which keeps "is this world already built?" a question about the world
 * name rather than about its contents.
 */
public final class GardenBuilder {

    /** Half-width of the barn's floor plan; the building is {@code BARN_X * 2 + 1} across. */
    private static final int BARN_X = 7;

    /** Half-depth of the barn's floor plan. */
    private static final int BARN_Z = 5;

    /** Wall height between the floor and the eaves. */
    private static final int WALL = 5;

    /**
     * The block a plot boundary is drawn in.
     *
     * <p>A wall block rather than a fence or a slab: it stands 1.5 blocks tall, which is above jump
     * height, so a single course keeps a player on their own plots without needing to be two blocks of
     * eyesore.
     */
    private static final Material BOUNDARY = Material.STONE_BRICK_WALL;

    private GardenBuilder() {
    }

    /**
     * Where a player arrives: on the path just outside the barn doors, looking north at them.
     *
     * <p>Outside rather than inside, so the first thing you see is the building you are about to walk
     * into — and so the spawn point can never be inside a block if the barn is ever reshaped.
     */
    public static Location spawn(World world) {
        Location spawn = new Location(world, 0.5, GardenLayout.GROUND_Y + 1, BARN_Z + 4.5);
        spawn.setYaw(180f);   // facing -z, toward the doors
        spawn.setPitch(0f);
        return spawn;
    }

    /** Where Sam stands: behind the desk at the back of the barn, facing the doors. */
    public static Location samPost(World world) {
        Location post = new Location(world, 0.5, GardenLayout.GROUND_Y + 1, -BARN_Z + 1.5);
        post.setYaw(180f);
        post.setPitch(0f);
        return post;
    }

    /** Build the centre plot and its farmhouse into a freshly created world. */
    public static void build(World world) {
        generatePlot(world, GardenLayout.CENTRE, GardenLayout.CENTRE);
        barn(world);
        approach(world);
    }

    /**
     * Generate one plot: its ground, and the path around its rim.
     *
     * <p>This is what unlocking a plot <em>means</em> — a locked plot is not a fenced-off area, it is
     * an area that does not exist yet. Idempotent, so it can be re-run to repair a plot whose blocks
     * were lost, and cheap enough (9,216 columns) to run inside a click handler.
     */
    public static void generatePlot(World world, int px, int pz) {
        groundPlot(world, px, pz);
        plotEdging(world, px, pz);
    }

    /**
     * Whether a plot has been generated, judged by the block under its centre.
     *
     * <p>Sampling one column rather than tracking a "built" flag alongside the unlock set: the world is
     * the authority on what exists in it, and two records of the same fact would eventually disagree.
     */
    public static boolean isGenerated(World world, int px, int pz) {
        return !world.getBlockAt((int) GardenLayout.plotCentre(world, px, pz).getX(),
                GardenLayout.GROUND_Y, (int) GardenLayout.plotCentre(world, px, pz).getZ())
                .getType().isAir();
    }

    /**
     * Lay one plot's ground: grass over dirt over bedrock.
     *
     * <p>Bedrock on the bottom course, not stone. A farming plot floats over the void, and the floor is
     * the only thing between a player with a shovel and a fall out of their own Garden — so it is the
     * one block in the build that must not be removable.
     */
    private static void groundPlot(World world, int px, int pz) {
        int y = GardenLayout.GROUND_Y;
        for (int x = GardenLayout.plotMin(px); x <= GardenLayout.plotMax(px); x++) {
            for (int z = GardenLayout.plotMin(pz); z <= GardenLayout.plotMax(pz); z++) {
                world.getBlockAt(x, y, z).setType(Material.GRASS_BLOCK, false);
                world.getBlockAt(x, y - 1, z).setType(Material.DIRT, false);
                world.getBlockAt(x, y - 2, z).setType(Material.DIRT, false);
                world.getBlockAt(x, y - 3, z).setType(Material.BEDROCK, false);
            }
        }
    }

    /**
     * Put a wall along every edge of every unlocked plot that faces somewhere you do not own, and take
     * one away everywhere that is now interior.
     *
     * <p>This is the border that moves. Unlocking a plot does not redraw a boundary somewhere else — it
     * removes the wall between the two plots and puts one around the new plot's far side, which is the
     * same operation applied to a larger set. Running it over the whole board each time keeps that
     * true even if unlock state is repaired or edited by hand.
     *
     * <p>A single wall block is 1.5 blocks tall, which is above jump height, so one course is enough to
     * stop someone walking off a plot into the void.
     */
    public static void applyWalls(World world, GardenState state) {
        for (int px = 0; px < GardenLayout.GRID; px++) {
            for (int pz = 0; pz < GardenLayout.GRID; pz++) {
                if (!state.isUnlocked(px, pz)) {
                    continue;
                }
                edge(world, px, pz, 0, -1, !state.isUnlocked(px, pz - 1));
                edge(world, px, pz, 0, 1, !state.isUnlocked(px, pz + 1));
                edge(world, px, pz, -1, 0, !state.isUnlocked(px - 1, pz));
                edge(world, px, pz, 1, 0, !state.isUnlocked(px + 1, pz));
            }
        }
    }

    /** Wall or clear one edge of one plot. {@code dx}/{@code dz} pick which of the four it is. */
    private static void edge(World world, int px, int pz, int dx, int dz, boolean wall) {
        int y = GardenLayout.GROUND_Y + 1;
        int xMin = GardenLayout.plotMin(px);
        int xMax = GardenLayout.plotMax(px);
        int zMin = GardenLayout.plotMin(pz);
        int zMax = GardenLayout.plotMax(pz);

        int fixedX = dx < 0 ? xMin : xMax;
        int fixedZ = dz < 0 ? zMin : zMax;

        if (dx != 0) {
            for (int z = zMin; z <= zMax; z++) {
                setWall(world, fixedX, y, z, wall);
            }
        } else {
            for (int x = xMin; x <= xMax; x++) {
                setWall(world, x, y, fixedZ, wall);
            }
        }
    }

    /** Place or remove a boundary wall block, touching nothing else that happens to be standing there. */
    private static void setWall(World world, int x, int y, int z, boolean wall) {
        Block block = world.getBlockAt(x, y, z);
        if (wall) {
            if (block.getType().isAir() || isWall(block.getType())) {
                block.setType(BOUNDARY, false);
            }
            return;
        }
        if (isWall(block.getType())) {
            block.setType(Material.AIR, false);   // only ever remove our own wall
        }
    }

    /** Whether a material is one of ours to remove. */
    public static boolean isWall(Material material) {
        return material == BOUNDARY;
    }

    /**
     * A path around the plot's rim.
     *
     * <p>Cosmetic, but it earns its place: the grid is invisible on an unbroken field of grass, and a
     * visible edge is the difference between "a big lawn" and "the first of twenty-five plots". It also
     * makes the layout arithmetic checkable by standing on it.
     */
    private static void plotEdging(World world, int px, int pz) {
        int y = GardenLayout.GROUND_Y;
        int min = GardenLayout.plotMin(px);
        int max = GardenLayout.plotMax(px);
        int zMin = GardenLayout.plotMin(pz);
        int zMax = GardenLayout.plotMax(pz);
        for (int x = min; x <= max; x++) {
            world.getBlockAt(x, y, zMin).setType(Material.DIRT_PATH, false);
            world.getBlockAt(x, y, zMax).setType(Material.DIRT_PATH, false);
        }
        for (int z = zMin; z <= zMax; z++) {
            world.getBlockAt(min, y, z).setType(Material.DIRT_PATH, false);
            world.getBlockAt(max, y, z).setType(Material.DIRT_PATH, false);
        }
    }

    /** A short path from the spawn point to the barn doors, so the way in reads as intended. */
    private static void approach(World world) {
        int y = GardenLayout.GROUND_Y;
        for (int z = BARN_Z; z <= BARN_Z + 6; z++) {
            for (int x = -1; x <= 1; x++) {
                world.getBlockAt(x, y, z).setType(Material.DIRT_PATH, false);
            }
        }
    }

    /**
     * The farmhouse: a 15&times;11 spruce-framed barn with a gabled roof and open double doors.
     *
     * <p>Built rather than pasted. A schematic would need WorldEdit installed and a {@code .schem} file
     * shipped and kept in step with the layout constants above; code needs neither, and the barn moves
     * automatically if the plot maths ever changes.
     */
    private static void barn(World world) {
        int y = GardenLayout.GROUND_Y;

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

        // the doorway: a 3-wide, 4-high opening in the +z wall, left open rather than doored so the
        // farmhouse reads as a barn and nobody has to fight a door to reach the desk
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

    /**
     * A gabled roof: each course steps in from both long sides and up by one, ridge along the x axis.
     *
     * <p>The ridge runs the long way on purpose — a barn is a long building, and a roof pitched across
     * the short axis is what makes it read as one rather than as a house that got wider.
     */
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

    /** The desk Sam works at, and enough dressing that the barn looks used. */
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

        // composters and a water barrel down the east end — the working half
        for (int z = -3; z <= 3; z += 2) {
            world.getBlockAt(BARN_X - 1, y + 1, z).setType(Material.COMPOSTER, false);
        }
        world.getBlockAt(BARN_X - 3, y + 1, 0).setType(Material.CAULDRON, false);

        // lanterns hung from the tie beams
        for (int x = -4; x <= 4; x += 4) {
            setData(world, x, y + WALL, 0, "minecraft:lantern[hanging=true]");
        }
    }

    /** Set a block from a blockdata string, skipping if that state is invalid on this version. */
    private static void setData(World world, int x, int y, int z, String data) {
        try {
            world.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(data), false);
        } catch (IllegalArgumentException badData) {
            Block block = world.getBlockAt(x, y, z);
            block.setType(Material.SPRUCE_PLANKS, false);   // visible, wrong, and obviously so
        }
    }
}
