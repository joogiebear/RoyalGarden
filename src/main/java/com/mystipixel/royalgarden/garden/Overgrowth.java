package com.mystipixel.royalgarden.garden;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The debris a locked plot is generated with — trees, boulders, bushes, fallen logs and undergrowth —
 * which a player clears after buying the plot and before they may build on it.
 *
 * <p>Planning is separate from writing. {@link #plan} is plain maths over a seeded {@link Random}: it
 * decides every block and touches no world, so it can be tested without a server and gives the same
 * plot the same trees every time it is asked. {@link Plan#writeRow} then lays one x-row of that plan,
 * for the {@link BuildQueue} to budget across ticks like any other plot work.
 *
 * <h2>Counting it</h2>
 *
 * <p>How much debris a plot has is counted as it is written, not as it was planned: a block is counted if
 * it was placed or was already there from an earlier, interrupted run. So a plan cut short by the world
 * unloading and redone on the next visit still ends with the right total, and a block that could not be
 * placed because something else stood there is not counted as something to clear.
 *
 * <h2>Trees that stay up</h2>
 *
 * <p>Tree leaves are written with their real distance from a log, worked out here, and are not
 * persistent — so they stand while the tree does and decay once its logs are cut, as they would in the
 * wild. Written with the default distance of 7 they would decay on the first random tick, locked plot or
 * not. Bushes have no log to hang from, so their leaves are persistent and have to be broken.
 */
public final class Overgrowth {

    /** How a planned block is dressed when written. */
    public enum Form { PLAIN, LOG_X, LOG_Y, LOG_Z, LEAVES, PERSISTENT_LEAVES }

    /**
     * One block of debris.
     *
     * @param x        plot-local x, {@code 0 .. plotSize-1}
     * @param y        world y
     * @param z        plot-local z
     * @param distance for {@link Form#LEAVES}, steps to the nearest log; otherwise unused
     */
    public record Voxel(int x, int y, int z, Material material, Form form, int distance) {
    }

    /**
     * What {@code clearing:} in {@code config.yml} asks for.
     *
     * @param required  percent of a plot's debris that must be cleared before it can be built on
     * @param margin    blocks left bare inside each plot edge, so nothing overhangs the rim or the next plot
     * @param coverChance chance, per bare column, of a plant on it
     */
    public record Settings(boolean enabled, int required, int margin,
                           int trees, List<String> treeTypes,
                           int boulders, List<Material> boulderBlocks,
                           int bushes, List<Material> bushBlocks,
                           int fallenLogs,
                           double coverChance, List<Material> coverBlocks,
                           List<String> rewardCommands, List<String> rewardMessages) {

        /** Tallest thing a plan can put above the ground, so a recount knows how high to look. */
        public static final int MAX_HEIGHT = 12;

        public static final Settings OFF = new Settings(false, 90, 3, 0, List.of(), 0, List.of(), 0, List.of(),
                0, 0, List.of(), List.of(), List.of());

        /** Every material this plan could write — what counts as debris when it is broken. */
        public Set<Material> materials() {
            Set<Material> out = EnumSet.noneOf(Material.class);
            for (String type : treeTypes) {
                out.add(log(type));
                out.add(leaves(type));
            }
            out.addAll(boulderBlocks);
            out.addAll(bushBlocks);
            out.addAll(coverBlocks);
            return out;
        }

        /** Read {@code clearing:}, warning about and skipping anything that is not a block. */
        public static Settings read(ConfigurationSection section, Logger logger) {
            if (section == null) {
                return OFF;
            }
            List<String> treeTypes = new ArrayList<>();
            for (String raw : section.getStringList("trees.types")) {
                String type = raw.trim().toLowerCase(Locale.ROOT);
                if (log(type) == null || leaves(type) == null) {
                    logger.warning("clearing.trees.types: '" + raw + "' has no <type>_log and <type>_leaves; skipped.");
                } else {
                    treeTypes.add(type);
                }
            }
            return new Settings(
                    !section.contains("enabled") || section.getBoolean("enabled"),
                    Math.clamp((int) number(section, "required-percent", 90), 0, 100),
                    Math.max(1, (int) number(section, "margin", 3)),
                    treeTypes.isEmpty() ? 0 : Math.max(0, (int) number(section, "trees.count", 0)),
                    List.copyOf(treeTypes),
                    Math.max(0, (int) number(section, "boulders.count", 0)),
                    blocks(section, "boulders.blocks", logger),
                    Math.max(0, (int) number(section, "bushes.count", 0)),
                    blocks(section, "bushes.blocks", logger),
                    treeTypes.isEmpty() ? 0 : Math.max(0, (int) number(section, "fallen-logs.count", 0)),
                    Math.clamp(number(section, "undergrowth.chance", 0), 0.0, 1.0),
                    blocks(section, "undergrowth.blocks", logger),
                    section.getStringList("rewards.commands"),
                    section.getStringList("rewards.messages"));
        }

        /**
         * A number, falling back to the shipped config before {@code fallback}.
         *
         * <p>Not {@code getInt(path, fallback)}: an explicit fallback wins over the shipped defaults. On a
         * {@code config.yml} written before {@code clearing:} existed, Bukkit hands back an empty section
         * for it, and every count read that way came out as its fallback of 0 — nothing was ever planted.
         * {@code contains} and the one-argument getters both consult the defaults.
         */
        private static double number(ConfigurationSection section, String path, double fallback) {
            return section.contains(path) ? section.getDouble(path) : fallback;
        }

        private static List<Material> blocks(ConfigurationSection section, String path, Logger logger) {
            List<Material> out = new ArrayList<>();
            for (String raw : section.getStringList(path)) {
                Material found = Material.matchMaterial(raw.trim());
                if (found == null || !found.isBlock()) {
                    logger.warning("clearing." + path + ": '" + raw + "' is not a block; skipped.");
                } else {
                    out.add(found);
                }
            }
            return List.copyOf(out);
        }
    }

    private Overgrowth() {
    }

    static Material log(String type) {
        return Material.matchMaterial(type + "_log");
    }

    static Material leaves(String type) {
        return Material.matchMaterial(type + "_leaves");
    }

    /** The seed for one plot of one Garden: fixed, so asking twice plans the same debris. */
    public static long seed(java.util.UUID owner, int px, int pz) {
        long seed = owner.getMostSignificantBits() * 31 + owner.getLeastSignificantBits();
        return seed * 1_000_003L + GardenLayout.plotName(px, pz).hashCode();
    }

    // ── planning ───────────────────────────────────────────────────────────────

    /** Plan the debris for one plot. Pure: the same arguments give the same plan. */
    public static Plan plan(long seed, int size, int groundY, Settings settings) {
        Planner planner = new Planner(new Random(seed), size, groundY + 1, settings);
        planner.run();
        return new Plan(size, planner.placed);
    }

    /** Where the planner has put things so far, keyed by packed position. */
    private static final class Planner {
        private final Random random;
        private final int size;
        private final int base;                     // first y above the ground
        private final Settings settings;
        private final int lo;
        private final int hi;
        private final Map<Long, Voxel> placed = new HashMap<>();

        Planner(Random random, int size, int base, Settings settings) {
            this.random = random;
            this.size = size;
            this.base = base;
            this.settings = settings;
            this.lo = settings.margin();
            this.hi = size - 1 - settings.margin();
        }

        void run() {
            if (hi - lo < 6) {
                return;                             // a plot too small for its margin grows nothing
            }
            // Big things first, so the small ones fill in around them rather than crowding them out.
            attempt(settings.trees(), this::tree);
            attempt(settings.boulders(), this::boulder);
            attempt(settings.fallenLogs(), this::fallenLog);
            attempt(settings.bushes(), this::bush);
            undergrowth();
        }

        private interface Shape {
            List<Voxel> at(int x, int z);
        }

        /** Place {@code count} of a shape at random free spots, giving up on a spot that overlaps. */
        private void attempt(int count, Shape shape) {
            int placedCount = 0;
            for (int tries = 0; tries < count * 8 && placedCount < count; tries++) {
                int x = lo + random.nextInt(hi - lo + 1);
                int z = lo + random.nextInt(hi - lo + 1);
                List<Voxel> voxels = shape.at(x, z);
                if (voxels.isEmpty() || !fits(voxels)) {
                    continue;
                }
                for (Voxel voxel : voxels) {
                    placed.put(key(voxel.x(), voxel.y(), voxel.z()), voxel);
                }
                placedCount++;
            }
        }

        /**
         * Whether a shape lies inside the margin and clear of everything placed, with a block of air all
         * round it. Touching would be more than untidy: a leaf next to another tree's log, or a fallen
         * one, takes its distance from that log, and the planned distances would be wrong.
         */
        private boolean fits(List<Voxel> voxels) {
            for (Voxel voxel : voxels) {
                if (voxel.x() < lo || voxel.x() > hi || voxel.z() < lo || voxel.z() > hi) {
                    return false;
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (placed.containsKey(key(voxel.x() + dx, voxel.y() + dy, voxel.z() + dz))) {
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        }

        private <T> T pick(List<T> from) {
            return from.get(random.nextInt(from.size()));
        }

        /** A trunk with a rounded crown, or a cone for spruce — leaves at their real distance from a log. */
        private List<Voxel> tree(int x, int z) {
            String type = pick(settings.treeTypes());
            Material log = log(type);
            Material leaf = leaves(type);
            boolean conifer = type.equals("spruce");
            int height = conifer ? 6 + random.nextInt(3) : 4 + random.nextInt(3);
            int top = base + height - 1;

            Map<Long, Voxel> shape = new HashMap<>();
            for (int y = base; y <= top; y++) {
                shape.put(key(x, y, z), new Voxel(x, y, z, log, Form.LOG_Y, 0));
            }
            List<long[]> crown = new ArrayList<>();
            if (conifer) {
                for (int y = base + 2; y <= top + 1; y++) {
                    int radius = y > top ? 0 : (top - y) % 2 == 0 ? 1 : 2;
                    ring(crown, x, y, z, radius, y == top + 1 ? 0 : radius);
                }
            } else {
                ring(crown, x, top - 2, z, 2, 2);
                ring(crown, x, top - 1, z, 2, 2);
                ring(crown, x, top, z, 1, 1);
                ring(crown, x, top + 1, z, 1, 0);
            }
            for (long[] leafAt : crown) {
                long k = key((int) leafAt[0], (int) leafAt[1], (int) leafAt[2]);
                shape.putIfAbsent(k, new Voxel((int) leafAt[0], (int) leafAt[1], (int) leafAt[2], leaf, Form.LEAVES, 0));
            }
            return withDistances(shape);
        }

        /**
         * A square layer of leaf positions around a trunk, corners trimmed at random — the difference
         * between a tree and a cube on a stick.
         *
         * @param plus radius of the solid part; beyond it only the axes, for a rounded top
         */
        private void ring(List<long[]> into, int cx, int y, int cz, int radius, int plus) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    boolean corner = Math.abs(dx) == radius && Math.abs(dz) == radius && radius > 0;
                    if (corner && random.nextInt(3) != 0) {
                        continue;
                    }
                    if (plus == 0 && dx != 0 && dz != 0) {
                        continue;
                    }
                    into.add(new long[]{cx + dx, y, cz + dz});
                }
            }
        }

        /**
         * Give each leaf its distance from the nearest log, walking through leaves as the game does, and
         * drop any leaf more than six steps out — it would decay on its own and be cleared by nobody.
         */
        private static List<Voxel> withDistances(Map<Long, Voxel> shape) {
            Map<Long, Integer> distance = new HashMap<>();
            Deque<Voxel> frontier = new ArrayDeque<>();
            for (Voxel voxel : shape.values()) {
                if (voxel.form() == Form.LOG_Y) {
                    distance.put(key(voxel.x(), voxel.y(), voxel.z()), 0);
                    frontier.add(voxel);
                }
            }
            int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            while (!frontier.isEmpty()) {
                Voxel from = frontier.poll();
                int next = distance.get(key(from.x(), from.y(), from.z())) + 1;
                for (int[] step : steps) {
                    long k = key(from.x() + step[0], from.y() + step[1], from.z() + step[2]);
                    Voxel to = shape.get(k);
                    if (to != null && to.form() == Form.LEAVES && !distance.containsKey(k)) {
                        distance.put(k, next);
                        frontier.add(to);
                    }
                }
            }
            List<Voxel> out = new ArrayList<>();
            for (Map.Entry<Long, Voxel> entry : shape.entrySet()) {
                Voxel voxel = entry.getValue();
                if (voxel.form() != Form.LEAVES) {
                    out.add(voxel);
                    continue;
                }
                Integer d = distance.get(entry.getKey());
                if (d != null && d <= 6) {
                    out.add(new Voxel(voxel.x(), voxel.y(), voxel.z(), voxel.material(), Form.LEAVES, d));
                }
            }
            return out;
        }

        /** A low dome of mixed stone. */
        private List<Voxel> boulder(int x, int z) {
            if (settings.boulderBlocks().isEmpty()) {
                return List.of();
            }
            int radius = 1 + random.nextInt(2);
            List<Voxel> out = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = 0; dy <= radius; dy++) {
                        double reach = dx * dx + dz * dz + dy * dy * 1.6;
                        if (reach <= radius * radius + 0.6 + random.nextDouble() * 0.8) {
                            out.add(new Voxel(x + dx, base + dy, z + dz, pick(settings.boulderBlocks()),
                                    Form.PLAIN, 0));
                        }
                    }
                }
            }
            return out;
        }

        /** A log lying on its side. */
        private List<Voxel> fallenLog(int x, int z) {
            Material log = log(pick(settings.treeTypes()));
            int length = 3 + random.nextInt(3);
            boolean alongX = random.nextBoolean();
            List<Voxel> out = new ArrayList<>();
            for (int i = 0; i < length; i++) {
                out.add(alongX
                        ? new Voxel(x + i, base, z, log, Form.LOG_X, 0)
                        : new Voxel(x, base, z + i, log, Form.LOG_Z, 0));
            }
            return out;
        }

        /** A clump of persistent leaves, one or two high. */
        private List<Voxel> bush(int x, int z) {
            if (settings.bushBlocks().isEmpty()) {
                return List.of();
            }
            Material leaf = pick(settings.bushBlocks());
            List<Voxel> out = new ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) == 2 && random.nextBoolean()) {
                        continue;
                    }
                    out.add(new Voxel(x + dx, base, z + dz, leaf, Form.PERSISTENT_LEAVES, 0));
                }
            }
            out.add(new Voxel(x, base + 1, z, leaf, Form.PERSISTENT_LEAVES, 0));
            return out;
        }

        /** Grass, ferns and flowers on the ground that is still bare. */
        private void undergrowth() {
            if (settings.coverBlocks().isEmpty() || settings.coverChance() <= 0) {
                return;
            }
            for (int x = lo; x <= hi; x++) {
                for (int z = lo; z <= hi; z++) {
                    if (random.nextDouble() >= settings.coverChance() || placed.containsKey(key(x, base, z))) {
                        continue;
                    }
                    placed.put(key(x, base, z), new Voxel(x, base, z, pick(settings.coverBlocks()), Form.PLAIN, 0));
                }
            }
        }
    }

    /** Pack a plot-local position; x and z are under 512 and y is a world height. */
    static long key(int x, int y, int z) {
        return ((long) (y + 2048) << 20) | ((long) (x & 0x3FF) << 10) | (z & 0x3FF);
    }

    // ── writing ────────────────────────────────────────────────────────────────

    /**
     * A plot's planned debris, by x-row, and how much of it the write has found or put in place.
     *
     * <p>One plan belongs to one write: {@link #present()} is a running count, not a property of the plan.
     */
    public static final class Plan {
        private final List<List<Voxel>> rows;
        private final int size;
        private final Map<String, BlockData> dressed = new HashMap<>();
        private int present;

        Plan(int size, Map<Long, Voxel> placed) {
            this.size = size;
            List<List<Voxel>> byRow = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                byRow.add(new ArrayList<>());
            }
            for (Voxel voxel : placed.values()) {
                byRow.get(voxel.x()).add(voxel);
            }
            this.rows = Collections.unmodifiableList(byRow);
        }

        /** Every planned block, in no particular order. */
        public List<Voxel> voxels() {
            List<Voxel> all = new ArrayList<>();
            rows.forEach(all::addAll);
            return all;
        }

        public int size() {
            return size;
        }

        /** Blocks placed, or found already in place, by the writes so far. */
        public int present() {
            return present;
        }

        /**
         * Write one x-row. Returns the number of blocks looked at, for the tick budget.
         *
         * <p>Only into air: a plan never overwrites what is already standing, so running it over a plot
         * that already has debris — or anything else — cannot destroy it.
         */
        public int writeRow(World world, int px, int pz, int row) {
            int minX = GardenLayout.plotMin(px);
            int minZ = GardenLayout.plotMin(pz);
            List<Voxel> voxels = rows.get(row);
            for (Voxel voxel : voxels) {
                Block block = world.getBlockAt(minX + voxel.x(), voxel.y(), minZ + voxel.z());
                Material there = block.getType();
                if (there.isAir()) {
                    block.setBlockData(dress(voxel), false);
                    present++;
                } else if (there == voxel.material()) {
                    present++;
                }
            }
            return voxels.size();
        }

        private BlockData dress(Voxel voxel) {
            String id = voxel.material() + ":" + voxel.form() + ":" + voxel.distance();
            return dressed.computeIfAbsent(id, ignored -> {
                BlockData data = Bukkit.createBlockData(voxel.material());
                if (data instanceof Orientable orientable) {
                    switch (voxel.form()) {
                        case LOG_X -> orientable.setAxis(Axis.X);
                        case LOG_Z -> orientable.setAxis(Axis.Z);
                        default -> orientable.setAxis(Axis.Y);
                    }
                }
                if (data instanceof Leaves leaves) {
                    boolean persistent = voxel.form() != Form.LEAVES;
                    leaves.setPersistent(persistent);
                    leaves.setDistance(persistent ? 7 : Math.clamp(voxel.distance(), 1, 7));
                }
                return data;
            });
        }
    }
}
