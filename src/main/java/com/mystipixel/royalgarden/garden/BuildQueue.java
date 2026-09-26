package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalskyblock.world.IslandWorldService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every plot being written, in every Garden, sharing one block budget per tick.
 *
 * <p>Plots used to be laid by a task per Garden, each with its own budget. That was fine for one
 * player and wrong for a contest opening: ten Gardens created in the same minute meant ten budgets'
 * worth of block writes every tick. One queue with one budget costs the server the same whether one
 * Garden is building or fifty — the fifty just take longer.
 *
 * <p>Jobs are served round-robin a row at a time, so a big template being stamped on one Garden does
 * not hold up another player's world being generated.
 *
 * <h2>When a job cannot finish</h2>
 *
 * <p>A job reports how it ended through its callback: {@link Outcome#DONE}, {@link Outcome#UNLOADED}
 * when the world went away under it (the host unloads an empty Garden after its grace period), or
 * {@link Outcome#FAILED} when writing threw. Failures are caught per job — an exception escaping a
 * repeating Bukkit task does not stop it, it just logs again every tick, forever.
 */
public final class BuildQueue {

    /** How a job ended. */
    public enum Outcome { DONE, UNLOADED, FAILED }

    /** One plot to write. */
    public static final class Job {
        private final String world;
        private final int px;
        private final int pz;
        private final PlotTemplate preset;
        private final GardenBuilder.Base base;
        private final GardenBuilder.Palette palette;
        private final PlotTemplate.PasteRules rules;
        private final boolean clearAbove;
        private final int ceiling;
        private final long ticksPerRow;
        private final Overgrowth.Plan growth;
        private final Consumer<Outcome> whenDone;
        private int row;
        private long nextTick;

        /**
         * @param preset      template to paste, or null to generate {@code base}
         * @param clearAbove  whether to clear everything above the new content — needed when replacing
         *                    a plot, wasted work when generating into empty air
         * @param ticksPerRow {@code 0} to write at full speed, otherwise one row every this many ticks
         */
        public Job(String world, int px, int pz, PlotTemplate preset, GardenBuilder.Base base,
                   GardenBuilder.Palette palette, PlotTemplate.PasteRules rules, boolean clearAbove,
                   int ceiling, long ticksPerRow, Consumer<Outcome> whenDone) {
            this.world = world;
            this.px = px;
            this.pz = pz;
            this.preset = preset;
            this.base = base;
            this.palette = palette;
            this.rules = rules;
            this.clearAbove = clearAbove;
            this.ceiling = ceiling;
            this.ticksPerRow = ticksPerRow;
            this.growth = null;
            this.whenDone = whenDone;
        }

        /**
         * Lay a plot's debris over whatever ground it already has.
         *
         * <p>Writes only into air above the ground, so it may share the queue with the job generating the
         * same plot's ground: the two never write the same block.
         */
        public Job(String world, int px, int pz, Overgrowth.Plan growth, Consumer<Outcome> whenDone) {
            this.world = world;
            this.px = px;
            this.pz = pz;
            this.preset = null;
            this.base = null;
            this.palette = null;
            this.rules = null;
            this.clearAbove = false;
            this.ceiling = 0;
            this.ticksPerRow = 0L;
            this.growth = growth;
            this.whenDone = whenDone;
        }

        boolean covers(String worldName, int x, int z) {
            return world.equals(worldName) && px == x && pz == z;
        }
    }

    private final Plugin plugin;
    private final GardenConfig config;
    private final IslandWorldService worlds;
    private final Logger logger;
    private final Deque<Job> jobs = new ArrayDeque<>();
    private BukkitTask task;
    private long tick;

    public BuildQueue(Plugin plugin, GardenConfig config, IslandWorldService worlds, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.worlds = worlds;
        this.logger = logger;
    }

    /** Queue a plot to be written. Must be called on the server thread. */
    public void submit(Job job) {
        jobs.addLast(job);
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    /** Whether a plot is queued or being written right now. */
    public boolean isBuilding(String world, int px, int pz) {
        for (Job job : jobs) {
            if (job.covers(world, px, pz)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a plot's debris is queued or being laid right now. */
    public boolean isGrowing(String world, int px, int pz) {
        for (Job job : jobs) {
            if (job.growth != null && job.covers(world, px, pz)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any plot of a world is queued. */
    public boolean isBuilding(String world) {
        for (Job job : jobs) {
            if (job.world.equals(world)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drop every queued job for one world, without calling back — for a Garden that is being deleted,
     * where finishing its plots would only write into a world that is about to stop existing.
     */
    public void cancel(String world) {
        jobs.removeIf(job -> job.world.equals(world));
    }

    /**
     * Stop, dropping every queued job without calling back.
     *
     * <p>Used on disable. Unfinished rebuilds are already recorded as pending in each Garden's state,
     * so they resume on the next load; unfinished generation is found missing and redone the same way.
     */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        jobs.clear();
    }

    private void tick() {
        tick++;
        int budget = config.blocksPerTick();
        int size = GardenLayout.plotSize();
        int visits = jobs.size();

        for (int i = 0; i < visits && budget > 0 && !jobs.isEmpty(); i++) {
            Job job = jobs.pollFirst();
            if (job.nextTick > tick) {
                jobs.addLast(job);
                continue;
            }
            World world = Bukkit.getWorld(job.world);
            if (world == null) {
                finish(job, Outcome.UNLOADED);
                continue;
            }
            try {
                if (job.ticksPerRow > 0) {
                    budget -= writeRow(world, job);
                    job.row++;
                    job.nextTick = tick + job.ticksPerRow;
                } else {
                    // Unpaced jobs take one row per visit so that several share a tick fairly; the
                    // loop comes back round to this one while budget remains.
                    budget -= writeRow(world, job);
                    job.row++;
                    if (job.row < size && budget > 0) {
                        visits++;
                    }
                }
            } catch (RuntimeException failed) {
                logger.log(Level.SEVERE, "Garden '" + job.world + "': writing plot "
                        + GardenLayout.plotName(job.px, job.pz) + " failed; it has been abandoned.", failed);
                finish(job, Outcome.FAILED);
                continue;
            }
            if (job.row >= size) {
                finish(job, Outcome.DONE);
            } else {
                jobs.addLast(job);
            }
        }

        if (jobs.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Write one row of one plot, from the template if there is one. */
    private int writeRow(World world, Job job) {
        if (job.growth != null) {
            return Math.max(1, job.growth.writeRow(world, job.px, job.pz, job.row));
        }
        int written;
        int contentTop;
        if (job.preset != null) {
            written = job.preset.pasteRow(world, job.px, job.pz, job.row, job.rules);
            contentTop = GardenLayout.floorY() + job.preset.height();
        } else {
            written = GardenBuilder.generatePlotRow(world, job.px, job.pz, job.row, job.base, job.palette);
            contentTop = GardenBuilder.baseTop();
        }
        if (job.clearAbove) {
            written += GardenBuilder.clearRowAbove(world, job.px, job.pz, job.row, contentTop, job.ceiling);
        }
        return written;
    }

    private void finish(Job job, Outcome outcome) {
        if (outcome == Outcome.DONE && !isBuilding(job.world)) {
            worlds.saveIsland(job.world);             // once per Garden, when its last plot is done
        }
        if (job.whenDone != null) {
            try {
                job.whenDone.accept(outcome);
            } catch (RuntimeException failed) {
                logger.log(Level.WARNING, "Garden '" + job.world + "': a build callback failed", failed);
            }
        }
    }
}
