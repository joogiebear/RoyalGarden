package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalskyblock.world.IslandWorldService;
import com.willfp.eco.core.EcoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldBorder;

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

    private final EcoPlugin plugin;
    private final IslandWorldService worlds;
    private final GardenStore store;
    private final Logger logger;

    public GardenWorlds(EcoPlugin plugin, IslandWorldService worlds, GardenStore store, Logger logger) {
        this.plugin = plugin;
        this.worlds = worlds;
        this.store = store;
        this.logger = logger;
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
     * Unlock a plot: record it, persist it, generate it and redraw the walls.
     *
     * <p>Returns false if the plot was not a legal purchase. Generation is skipped when the world is
     * not loaded — {@link #prepare} regenerates anything missing on the next visit, so the unlock is
     * never lost for want of a loaded world.
     */
    public boolean unlock(UUID owner, int px, int pz) {
        GardenState state = store.get(owner);
        if (!state.unlock(px, pz)) {
            return false;
        }
        store.save(state);
        World world = Bukkit.getWorld(worldName(owner));
        if (world != null) {
            GardenBuilder.generatePlot(world, px, pz);
            GardenBuilder.applyWalls(world, state);
        }
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
        // The board's edge, enforced by the world's own border. Per-player borders are left alone:
        // RoyalSkyblock's BorderService clears them for any non-island world, and clearing a per-player
        // border falls the player back to exactly this one. The two systems agree by not overlapping.
        WorldBorder border = world.getWorldBorder();
        border.setCenter(0.0, 0.0);
        border.setSize(GardenLayout.SPAN);
        border.setWarningDistance(3);
        border.setDamageAmount(0.0);
        border.setDamageBuffer(0.0);

        // A farm, not a survival world: no hostiles, and nothing that rearranges what you planted.
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);

        world.setSpawnLocation(GardenBuilder.spawn(world));
        sync(world);
    }

    /**
     * Make the world match the unlock state: generate any unlocked plot that isn't there, then redraw
     * the walls.
     *
     * <p>Run on every load, not just after an unlock. A plot can be unlocked while the world is
     * unloaded (from the menu, standing anywhere), and a world can come back from a rollback missing
     * blocks it should have. Reconciling on load means the unlock file is the record and the world is
     * brought to agree with it, rather than the two drifting apart with no way back.
     */
    public void sync(World world) {
        GardenState state = stateOf(world);
        if (state == null) {
            return;
        }
        for (int[] plot : state.plots()) {
            if (!GardenBuilder.isGenerated(world, plot[0], plot[1])) {
                logger.info("Garden '" + world.getName() + "': generating plot "
                        + GardenLayout.plotName(plot[0], plot[1]) + ".");
                GardenBuilder.generatePlot(world, plot[0], plot[1]);
            }
        }
        GardenBuilder.applyWalls(world, state);
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
