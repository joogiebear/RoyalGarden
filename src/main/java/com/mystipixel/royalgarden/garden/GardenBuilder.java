package com.mystipixel.royalgarden.garden;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Generates a brand-new Garden: the centre plot and the farmhouse standing on it.
 *
 * <p>Only the centre plot is built <em>here</em>, synchronously, so the player has somewhere to stand
 * the moment the world exists. The rest are laid in behind them by {@link GardenWorlds#sync} through
 * the {@link BuildQueue} — locked plots included, because ground you will one day own should look like
 * ground, not a hole in the board.
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
     * The barn as {@link #build} makes it now. A Garden built to an older one is brought up to date by
     * {@link #renovate} on its next load; each version's changes are additions, never a rebuild, so a
     * renovation cannot disturb anything else standing on the farmhouse plot.
     *
     * <ol>
     *   <li>the original barn;</li>
     *   <li>the drive-through window and the lane visitors queue along.</li>
     * </ol>
     */
    public static final int BARN_VERSION = 2;

    /** What plot boundaries used to be drawn in, kept only so old Gardens can be cleaned up. */
    private static final Material LEGACY_BOUNDARY = Material.STONE_BRICK_WALL;

    private GardenBuilder() {
    }

    /**
     * Where a player arrives: on the path just outside the barn doors, looking north at them.
     *
     * <p>Outside rather than inside, so the first thing you see is the building you are about to walk
     * into — and so the spawn point can never be inside a block if the barn is ever reshaped.
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

    /**
     * Where Sam stands: behind the desk at the back of the barn, facing over it toward the doors.
     *
     * <p>Yaw 0 is +z, the way the doors face. It was 180 once, which stood Sam with his back to the desk
     * and his face to the wall; he is re-posted on every load, so that corrects itself.
     */
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

    /** Build the centre plot and its farmhouse — built-in or a schematic — into a freshly created world. */
    public static void build(World world, Palette palette, java.util.logging.Logger logger) {
        for (int row = 0; row < GardenLayout.plotSize(); row++) {
            generatePlotRow(world, GardenLayout.centre(), GardenLayout.centre(), row, Base.FLAT, palette);
        }
        Farmhouse.current().build(world, logger);
    }

    /** The built-in barn, with its path and drive-through, onto bare ground. */
    static void buildBarn(World world) {
        barn(world);
        approach(world);
        driveThrough(world);
    }

    /** Bring a barn built to {@code from} up to {@link #BARN_VERSION}. */
    public static void renovate(World world, int from) {
        if (from < 2) {
            driveThrough(world);
        }
    }

    /**
     * The drive-through: a window in the front wall east of the doors, with a counter and an awning, and a
     * path running south from it for visitors to queue along.
     *
     * <p>The window is where the line in {@code visitors.yml} starts — its front spot, {@code 6, 6.5},
     * stands a visitor square in the opening. Moving one means moving the other.
     */
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
     * The two plots that are generated rather than captured.
     *
     * <p>They are not presets and deliberately do not live in the preset library: they need no file,
     * cannot be deleted, and are the two states a plot can always be returned to.
     */
    public enum Base {
        /** Grass over dirt over bedrock, with a path around the rim — somewhere to start. */
        FLAT,
        /** The bedrock course and nothing else — a bare slab to build on from scratch. */
        BEDROCK
    }

    /** Blocks written by one call to {@link #generatePlotRow} — four courses across the plot. */
    public static int rowCost() {
        return GardenLayout.plotSize() * 4;
    }

    /** The first Y above a generated base, i.e. where clearing starts. */
    public static int baseTop() {
        return GardenLayout.groundY() + 1;
    }

    /** The highest Y a plot owns — the top of the tallest template allowed — so clearing knows where to stop. */
    public static int ceiling(int maxTemplateHeight) {
        return GardenLayout.floorY() + maxTemplateHeight;
    }

    /**
     * Clear one x-row of a plot from {@code fromY} up to the plot ceiling.
     *
     * <p>Applying anything to a plot has to erase what was there, and neither a preset nor a base
     * writes above its own height — so without this, stamping a short preset over a tall build leaves
     * the top of the old build floating above the new one. Only done when applying: during world
     * generation everything above is already air, and clearing it would be a million wasted writes.
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
     * Generate one x-row of a plain plot: surface over subsoil over floor, with a rim along the edge.
     *
     * <p>A row at a time so the caller can spend a fixed block budget per tick. The whole default
     * board is 230,400 columns, and laying it in one go is seconds of frozen server.
     *
     * <p>The bottom course is the floor: a plot floats over the void, and the floor is the only thing
     * between a player with a shovel and a fall out of their own Garden.
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
     * Whether a plot has been generated, judged by the bedrock course under its centre.
     *
     * <p>Sampling one column rather than tracking a "built" flag alongside the unlock set: the world is
     * the authority on what exists in it, and two records of the same fact would eventually disagree.
     *
     * <p>The <em>floor</em>, not the surface: every generated form of a plot lays the bedrock course —
     * flat, templated, and the bare {@code bedrock} slab alike — while the surface is legitimately air
     * on a slab plot. Sampling the surface made {@link GardenWorlds#sync} read a slab as ungenerated
     * and stamp the default preset back over it on the next load.
     */
    public static boolean isGenerated(World world, int px, int pz) {
        int half = GardenLayout.plotSize() / 2;
        return !world.getBlockAt(GardenLayout.plotMin(px) + half, GardenLayout.floorY(),
                GardenLayout.plotMin(pz) + half).getType().isAir();
    }

    /**
     * Strip any boundary wall left behind by an earlier version.
     *
     * <p>Plot edges were once drawn in stone brick wall, before the border became a real world border.
     * A Garden built then still has them standing, and nothing else would ever remove them — so this
     * runs once per load and clears the course above every plot rim. Cheap, and it means an old Garden
     * repairs itself rather than needing to be wiped.
     *
     * <p>It touches every plot edge, which loads most of the board's chunks, so it runs once per Garden
     * — {@link GardenState#legacyWallsCleared()} records that it has — and not on every visit.
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

    /** A short path from the spawn point to the barn doors, so the way in reads as intended. */
    private static void approach(World world) {
        int y = GardenLayout.groundY();
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
