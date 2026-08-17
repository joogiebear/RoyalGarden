package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalskyblock.world.IslandWorldService;
import com.willfp.eco.core.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * One Garden world per player, created on first visit and loaded on every one after.
 *
 * <p>Garden worlds are SlimeWorlds obtained through RoyalSkyblock's own
 * {@link IslandWorldService}, which is deliberately name-based rather than island-based — it takes a
 * world name and hands back a Bukkit {@link World}. That is the whole reason The Garden can be an
 * extension: it gets ASP-backed per-player worlds, the unload grace period, and the save-on-shutdown
 * guarantees without owning any of that code, and without ever importing an ASP type.
 *
 * <p>Ownership needs no storage of its own: the world is named from the owner's UUID, so the name
 * <em>is</em> the record. Per-plot unlock state will need real persistence, and that is Phase 2.
 */
public final class GardenWorlds {

    /**
     * World-name prefix. Distinct from RoyalSkyblock's {@code island_} so the two can never collide in
     * a shared data source, and greppable in a slime-worlds directory.
     */
    private static final String PREFIX = "garden_";

    /**
     * Blocks written per tick while laying out a board.
     *
     * <p>Everything is spread against this budget rather than done a plot at a time, because a plot is
     * not a fixed amount of work once a template is involved: the default is four courses, a captured
     * farm can be forty. A block budget is the thing that actually stays constant.
     */
    private static final int BLOCKS_PER_TICK = 24_000;

    /**
     * Ticks between rows when a player applies a preset — 96 rows, so about 58 seconds a plot.
     *
     * <p>Deliberately slow, and not for performance: the budget above could lay the same plot in about
     * a second. Choosing a farm should look like the farm being built, and taking a minute is also what
     * stops a preset being something you flip between casually.
     */
    private static final long PASTE_TICKS_PER_ROW = 12L;

    private final EcoPlugin plugin;
    private final IslandWorldService worlds;
    private final GardenStore store;
    private final PresetLibrary presets;
    private final Logger logger;

    /** Plots currently being written, as {@code world:px,pz}, so two pastes cannot interleave. */
    private final Set<String> laying = new HashSet<>();

    public GardenWorlds(EcoPlugin plugin, IslandWorldService worlds, GardenStore store,
                        PresetLibrary presets, Logger logger) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.store = store;
        this.presets = presets;
        this.logger = logger;
    }

    /** The preset library — what can be stamped onto a plot. */
    public PresetLibrary presets() {
        return presets;
    }

    // ── applying presets ───────────────────────────────────────────────────────

    /**
     * Capture a plot and store it under a preset id.
     *
     * <p>Nothing already built changes. Capturing records what a plot looks like; applying it is a
     * separate, destructive act, and keeping the two apart is what makes capture safe to run at any
     * time while you are still building.
     */
    public PlotTemplate capture(World world, int px, int pz, String id) throws IOException {
        PlotTemplate captured = PlotTemplate.capture(world, px, pz);
        presets.save(id, captured);
        logger.info("Captured plot " + GardenLayout.plotName(px, pz) + " as preset '" + id + "' ("
                + captured.height() + " layers).");
        return captured;
    }

    /**
     * Stamp a preset onto one plot, or flatten it back to a plain field with a null id.
     *
     * <p>Overwrites the plot completely. That is the intended behaviour and the reason it is only ever
     * reached from a deliberate action: a plot someone has farmed for a week and a plot they just
     * unlocked look the same to this method.
     */
    public boolean applyPreset(World world, int px, int pz, String id) {
        String key = world.getName() + ":" + px + "," + pz;
        if (!laying.add(key)) {
            // A second paste over a plot already being written would interleave rows with the first
            // and leave a plot that is half of each, with nothing to say which.
            return false;
        }
        PlotTemplate preset = id == null ? null : presets.get(id);
        lay(world, List.of(new int[]{px, pz}),
                id == null ? "flattening" : "applying '" + id + "' to",
                preset, PASTE_TICKS_PER_ROW, () -> laying.remove(key));
        return true;
    }

    /** Whether a plot is mid-paste right now. */
    public boolean isLaying(World world, int px, int pz) {
        return laying.contains(world.getName() + ":" + px + "," + pz);
    }

    /** The world name for a player's Garden. */
    public static String worldName(UUID owner) {
        return PREFIX + owner;
    }

    /** Whether a world name is a Garden at all. */
    public static boolean isGarden(World world) {
        return world != null && world.getName().startsWith(PREFIX);
    }

    /** Whose Garden this world is, or null if it is not a Garden. */
    public static UUID ownerOf(World world) {
        if (!isGarden(world)) {
            return null;
        }
        try {
            return UUID.fromString(world.getName().substring(PREFIX.length()));
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Unlock state for a Garden world, or null if it is not one. */
    public GardenState stateOf(World world) {
        UUID owner = ownerOf(world);
        return owner == null ? null : store.get(owner);
    }

    /** Unlock state for a player. */
    public GardenState state(UUID owner) {
        return store.get(owner);
    }

    /**
     * Unlock a plot: record it, persist it, and move the border out if it reached further.
     *
     * <p>Nothing is generated here — every plot is built when the Garden is created, locked or not, so
     * unlocking is purely a change of what you are allowed onto. Returns false if the plot was not a
     * legal purchase.
     */
    public boolean unlock(UUID owner, int px, int pz) {
        GardenState state = store.get(owner);
        if (!state.unlock(px, pz)) {
            return false;
        }
        store.save(state);
        return true;
    }

    /** Whether this player's Garden is loaded right now. */
    public boolean isLoaded(UUID owner) {
        return worlds.isLoaded(worldName(owner));
    }

    /**
     * Get a player's Garden, building it if this is the first time they have asked for one.
     *
     * <p>The returned future completes <b>on the server thread</b> with a world that has already been
     * prepared, so callers can teleport into it directly. That is a deliberate departure from the host
     * service's contract (which completes on an unspecified thread): every caller here would otherwise
     * have to remember to hop back, and forgetting is the kind of bug that only shows up under load.
     */
    public CompletableFuture<World> open(UUID owner) {
        String name = worldName(owner);
        return load(name)
                .thenCompose(world -> world != null
                        ? CompletableFuture.completedFuture(world)
                        : create(name))
                .thenCompose(world -> onMain(() -> {
                    prepare(world);
                    return world;
                }));
    }

    /**
     * Load an existing Garden, completing with {@code null} if there isn't one yet.
     *
     * <p>The host service has no "does this world exist?" call — {@code loadIsland} simply fails for an
     * unknown world — so absence has to be read out of the failure. It is identified by the exception's
     * <em>class name</em> rather than by catching {@code UnknownWorldException} directly, because
     * catching it would mean compiling against the ASP API and this extension is built precisely so
     * that it never has to. A stringly check confined to one method is the cheaper end of that trade.
     */
    private CompletableFuture<World> load(String name) {
        return worlds.loadIsland(name)
                .handle((world, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(world);
                    }
                    if (isUnknownWorld(error)) {
                        return CompletableFuture.<World>completedFuture(null);
                    }
                    return CompletableFuture.<World>failedFuture(error);
                })
                .thenCompose(future -> future);
    }

    /** Create a Garden world, build the centre plot into it, and persist it before handing it back. */
    private CompletableFuture<World> create(String name) {
        logger.info("Creating Garden world '" + name + "'.");
        return worlds.createIsland(name)
                .thenCompose(world -> onMain(() -> {
                    long started = System.nanoTime();
                    // Build before prepare: prepare reconciles the world against the unlock state, and
                    // it should find the centre plot already there rather than generate it twice.
                    GardenBuilder.build(world);
                    prepare(world);
                    logger.info("Built Garden '" + name + "' in "
                            + (System.nanoTime() - started) / 1_000_000 + "ms.");
                    return world;
                }))
                // Persist before returning: a crash between "built" and "first save" would otherwise
                // leave an empty world that the load path would then happily accept as finished.
                .thenCompose(world -> worlds.saveIsland(name).thenApply(ignored -> world));
    }

    /**
     * Apply the settings a Garden should always have, on every load rather than only at creation.
     *
     * <p>Idempotent on purpose. World properties come from RoyalSkyblock's island config (the host's
     * service builds the property map, and it does not know Gardens exist), so anything The Garden
     * needs differently has to be re-asserted here — including on worlds built before a setting was
     * added.
     */
    public void prepare(World world) {
        applyBorder(world);

        // A farm, not a survival world: nothing spawns that the farmer did not put there, and nothing
        // rearranges what they planted. DO_MOB_SPAWNING closes the natural path cheaply, without an
        // event per attempt; GardenProtection closes the rest and lets plugin spawns (EcoMobs) through.
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);

        world.setSpawnLocation(GardenBuilder.spawn(world));
        sweepMobs(world);
        sync(world);
    }

    /**
     * Remove vanilla mobs that are already in the world.
     *
     * <p>Blocking spawns only stops new ones. A Garden built before that rule existed had twenty-five
     * plots of plains grass and no restriction, so it grew horses and sheep — and they were saved into
     * the slime file, which means they come back on every load until something takes them out.
     *
     * <p>The test is an <b>empty persistent data container</b>. Every plugin that spawns a mob and
     * intends to keep it tags it: Sam carries this extension's key, and EcoMobs tags its own, so both
     * survive while anything vanilla spawned does not. That is why this can run on every load without
     * eventually deleting the content it is meant to protect — and why it keeps working when EcoMobs
     * arrives, without knowing anything about EcoMobs.
     *
     * <p>Only {@code Mob}s are considered. Dropped items, armour stands, item frames and paintings are
     * not mobs and are left exactly where they are.
     */
    public void sweepMobs(World world) {
        int removed = 0;
        for (Entity entity : world.getEntities()) {
            if (!(entity instanceof Mob mob)) {
                continue;
            }
            if (!mob.getPersistentDataContainer().isEmpty()) {
                continue;                    // a plugin owns this one
            }
            mob.remove();
            removed++;
        }
        if (removed > 0) {
            logger.info("Garden '" + world.getName() + "': removed " + removed + " vanilla mob(s).");
        }
    }

    /**
     * The world's own border, parked at the edge of the board.
     *
     * <p>This is a backstop, not the border a player sees. The visible one is per-player, coloured, and
     * drawn around what they own — see {@link GardenBorder}. This one exists so that a player who
     * somehow has no per-player border still cannot walk off the board, and so the two never disagree
     * about where the world ends.
     */
    public void applyBorder(World world) {
        WorldBorder border = world.getWorldBorder();
        border.setCenter(0.0, 0.0);
        border.setSize(GardenLayout.SPAN);
        border.setWarningDistance(0);
        border.setDamageAmount(0.0);
        border.setDamageBuffer(0.0);
    }

    /**
     * Bring the world up to date with what it should contain: every plot generated, locked or not, and
     * no leftovers from an older layout.
     *
     * <p>All twenty-five are built, because a locked plot you can see across the border reads as a
     * place you are going to own — an empty hole reads as a bug. Unlocking changes where the border is,
     * not whether the ground exists.
     *
     * <p>Generation is spread one plot per tick. The board is 230,400 columns; laying it in a single
     * tick is roughly a five-second freeze, and the player is standing on the farmhouse plot — built
     * synchronously before this starts — while the rest fills in behind them.
     */
    public void sync(World world) {
        GardenBuilder.clearLegacyWalls(world);

        List<int[]> missing = new ArrayList<>();
        for (int px = 0; px < GardenLayout.GRID; px++) {
            for (int pz = 0; pz < GardenLayout.GRID; pz++) {
                if (!GardenBuilder.isGenerated(world, px, pz)) {
                    missing.add(new int[]{px, pz});
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        // Board generation runs at full speed. The slow pace exists to make choosing a farm feel
        // deliberate; nobody chose to wait through the world being created in the first place.
        lay(world, missing, "generating", presets.defaultPreset(), 0L, null);
    }

    /**
     * Write a list of plots into the world, a fixed number of blocks per tick.
     *
     * <p>Row by row against a block budget rather than plot by plot: with a tall template one plot is
     * far too much for a single tick, and with the default field it is far too little. Work is
     * persisted once at the end — an interrupted run simply leaves plots ungenerated, and the next load
     * finds them missing and lays them again.
     */
    private void lay(World world, List<int[]> plots, String verb, PlotTemplate preset,
                     long ticksPerRow, Runnable whenDone) {
        String name = world.getName();
        logger.info("Garden '" + name + "': " + verb + " " + plots.size() + " plot(s)"
                + (preset != null ? " (" + preset.height() + " layers)" : " (flat)") + ".");
        long started = System.nanoTime();
        long period = Math.max(1L, ticksPerRow);

        new BukkitRunnable() {
            private int plot = 0;
            private int row = 0;

            @Override
            public void run() {
                // The world can be unloaded under us — the host unloads an empty Garden after its
                // grace period, and writing blocks into a world nobody holds would throw every tick.
                if (Bukkit.getWorld(name) == null) {
                    finish();
                    return;
                }
                int blocks = BLOCKS_PER_TICK;
                boolean paced = ticksPerRow > 0;
                int rowsThisRun = 0;

                while (plot < plots.size() && blocks > 0 && !(paced && rowsThisRun >= 1)) {
                    int[] at = plots.get(plot);
                    blocks -= writeRow(world, at[0], at[1], row, preset);
                    rowsThisRun++;
                    if (++row >= GardenLayout.PLOT_SIZE) {
                        row = 0;
                        plot++;
                    }
                }
                if (plot >= plots.size()) {
                    worlds.saveIsland(name);
                    logger.info("Garden '" + name + "': " + verb + " done in "
                            + (System.nanoTime() - started) / 1_000_000 + "ms.");
                    finish();
                }
            }

            private void finish() {
                cancel();
                if (whenDone != null) {
                    whenDone.run();
                }
            }
        }.runTaskTimer(plugin, 1L, period);
    }

    /**
     * Write one row of one plot, from the template if there is one.
     *
     * <p>The farmhouse plot is never templated. It carries the barn, the desk and Sam, and stamping a
     * captured farm over it would delete all three.
     */
    private int writeRow(World world, int px, int pz, int row, PlotTemplate preset) {
        if (preset != null && !GardenLayout.isCentre(px, pz)) {
            return preset.pasteRow(world, px, pz, row);
        }
        return GardenBuilder.generatePlotRow(world, px, pz, row);
    }

    /** Walk an async failure's cause chain looking for ASP's "no such world" signal. */
    private static boolean isUnknownWorld(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().equals("UnknownWorldException")) {
                return true;
            }
            if (t.getCause() == t) {
                break;                       // self-referential cause; stop rather than spin
            }
        }
        return false;
    }

    /** Run {@code supplier} on the server thread, completing the returned future with its result. */
    private <T> CompletableFuture<T> onMain(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
        return future;
    }
}
