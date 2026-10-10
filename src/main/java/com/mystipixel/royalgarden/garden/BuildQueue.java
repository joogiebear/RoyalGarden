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
 * Every plot being written, in every Garden, sharing one block budget per tick, so fifty Gardens cost
 * the server the same as one. Jobs are served round-robin a row at a time. Failures are caught per job:
 * an exception escaping a repeating task would log every tick forever.
 */
public final class BuildQueue {

    /** {@code UNLOADED}: the world went away under the job (the host unloads an empty Garden). */
    public enum Outcome { DONE, UNLOADED, FAILED }

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
         * @param clearAbove  whether to clear everything above the new content: needed when replacing a plot,
         *                    wasted work when generating into empty air
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
         * Lays a plot's debris. Writes only into air above the ground, so it can share the queue with the job
         * generating the same plot's ground.
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

    /** Main thread only. */
    public void submit(Job job) {
        jobs.addLast(job);
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    /** Queued or being written right now. */
    public boolean isBuilding(String world, int px, int pz) {
        for (Job job : jobs) {
            if (job.covers(world, px, pz)) {
                return true;
            }
        }
        return false;
    }

    /** Queued or being laid right now. */
    public boolean isGrowing(String world, int px, int pz) {
        for (Job job : jobs) {
            if (job.growth != null && job.covers(world, px, pz)) {
                return true;
            }
        }
        return false;
    }

    public boolean isBuilding(String world) {
        for (Job job : jobs) {
            if (job.world.equals(world)) {
                return true;
            }
        }
        return false;
    }

    /** Drops every queued job for one world without calling back, for a Garden being deleted. */
    public void cancel(String world) {
        jobs.removeIf(job -> job.world.equals(world));
    }

    /**
     * Drops every queued job without calling back. Unfinished rebuilds are recorded as pending in each
     * Garden's state and resume on the next load.
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
