package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalskyblock.world.IslandWorldRules;
import com.mystipixel.royalskyblock.world.IslandWorldService;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
 * <em>is</em> the record. Everything else about a Garden lives in {@link GardenStore}.
 */
public final class GardenWorlds {

    /**
     * World-name prefix. Distinct from RoyalSkyblock's {@code island_} so the two can never collide in
     * a shared data source, and greppable in a slime-worlds directory.
     */
    private static final String PREFIX = "garden_";

    private final Plugin plugin;
    private final GardenConfig config;
    private final IslandWorldService worlds;
    private final GardenStore store;
    private final PresetLibrary presets;
    private final BuildQueue queue;
    private final Logger logger;

    /** Opens in flight, so two requests for the same Garden share one load — or one creation. */
    private final Map<UUID, CompletableFuture<World>> opening = new ConcurrentHashMap<>();

    /** Run on every Garden once it is prepared; this is how Sam gets to the desk. */
    private Consumer<World> onPrepared = world -> { };

    public GardenWorlds(Plugin plugin, GardenConfig config, IslandWorldService worlds, GardenStore store,
                        PresetLibrary presets, BuildQueue queue, Logger logger) {
        this.plugin = plugin;
        this.config = config;
        this.worlds = worlds;
        this.store = store;
        this.presets = presets;
        this.queue = queue;
        this.logger = logger;
    }

    /** The preset library — what can be stamped onto a plot. */
    public PresetLibrary presets() {
        return presets;
    }

    /** Something to run on every Garden after {@link #prepare}. */
    public void onPrepared(Consumer<World> action) {
        this.onPrepared = action == null ? world -> { } : action;
    }

    // ── identity ───────────────────────────────────────────────────────────────

    /** The world name for a player's Garden. */
    public static String worldName(UUID owner) {
        return PREFIX + owner;
    }

    /** Whether a world is a Garden at all. */
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

    /** Whether an id names a generated plot rather than a captured one. */
    public static boolean isBuiltIn(String id) {
        return id == null
                || id.equalsIgnoreCase(GardenBuilder.Base.FLAT.name())
                || id.equalsIgnoreCase(GardenBuilder.Base.BEDROCK.name());
    }

    private static String label(String id) {
        return id == null ? "flat" : id.toLowerCase(Locale.ROOT);
    }

    // ── state ──────────────────────────────────────────────────────────────────

    /** State for a Garden world, or null if it is not one. */
    public GardenState stateOf(World world) {
        UUID owner = ownerOf(world);
        return owner == null ? null : store.get(owner);
    }

    /** State for a player. */
    public GardenState state(UUID owner) {
        return store.get(owner);
    }

    /** Persist a state changed from outside (admin commands). */
    public void save(GardenState state) {
        store.save(state);
    }

    /**
     * Unlock a plot: record it and persist it.
     *
     * <p>Nothing is generated here — every plot is built when the Garden is created, locked or not, so
     * unlocking is purely a change of what you are allowed onto (enforced by
     * {@link GardenProtection}). Returns false if the plot was not a legal purchase.
     */
    public boolean unlock(UUID owner, int px, int pz) {
        GardenState state = store.get(owner);
        if (!state.unlock(px, pz)) {
            return false;
        }
        store.save(state);
        return true;
    }

    /** Whether this player has ever had a Garden — so looking at one does not create it. */
    public boolean hasGarden(UUID owner) {
        return store.exists(owner) || worlds.isLoaded(worldName(owner));
    }

    /** Whether this player's Garden is loaded right now. */
    public boolean isLoaded(UUID owner) {
        return worlds.isLoaded(worldName(owner));
    }

    // ── presets ────────────────────────────────────────────────────────────────

    /**
     * Capture a plot and store it under a preset id.
     *
     * <p>Nothing already built changes. Capturing records what a plot looks like; applying it is a
     * separate, destructive act, and keeping the two apart is what makes capture safe to run at any
     * time while you are still building.
     */
    public PlotTemplate capture(World world, int px, int pz, String id) throws IOException {
        PlotTemplate captured = PlotTemplate.capture(world, px, pz, config.maxTemplateHeight());
        presets.save(id, captured);
        logger.info("Captured plot " + GardenLayout.plotName(px, pz) + " as preset '" + id + "' ("
                + captured.height() + " layers).");
        return captured;
    }

    /** Whether a plot is being written right now. */
    public boolean isBuilding(World world, int px, int pz) {
        return queue.isBuilding(world.getName(), px, pz);
    }

    /**
     * Rebuild one plot from a preset, or from {@code flat} / {@code bedrock}.
     *
     * <p>Overwrites the plot completely. That is the intended behaviour and the reason it is only ever
     * reached from a deliberate action: a plot someone has farmed for a week and a plot they just
     * unlocked look the same to this method.
     *
     * <p>The rebuild is recorded as pending before it starts and cleared when it finishes, so one cut
     * short by the world unloading is picked up again by {@link #prepare} on the next visit rather than
     * leaving a plot that is half of two farms.
     *
     * @param paced whether to take the slow, visible pace ({@code generation.paste-ticks-per-row})
     * @return false if the plot is already being written, or the preset could not be read
     */
    public boolean rebuild(World world, int px, int pz, String id, boolean paced) {
        if (queue.isBuilding(world.getName(), px, pz)) {
            // A second paste over a plot already being written would interleave rows with the first
            // and leave a plot that is half of each, with nothing to say which.
            return false;
        }
        GardenBuilder.Base base = GardenBuilder.Base.BEDROCK.name().equalsIgnoreCase(id)
                ? GardenBuilder.Base.BEDROCK
                : GardenBuilder.Base.FLAT;
        PlotTemplate preset = null;
        if (!isBuiltIn(id)) {
            preset = presets.get(id);
            if (preset == null) {
                return false;
            }
        }
        String label = label(id);
        UUID owner = ownerOf(world);
        if (owner != null) {
            GardenState state = store.get(owner);
            state.setPending(px, pz, label);
            store.save(state);
        }
        logger.info("Garden '" + world.getName() + "': applying '" + label + "' to plot "
                + GardenLayout.plotName(px, pz) + ".");
        queue.submit(new BuildQueue.Job(world.getName(), px, pz, preset, base, palette(), pasteRules(),
                true, GardenBuilder.ceiling(config.maxTemplateHeight()),
                paced ? config.pasteTicksPerRow() : 0L,
                outcome -> {
                    if (owner == null || outcome == BuildQueue.Outcome.UNLOADED) {
                        return;                      // stays pending; resumed on the next load
                    }
                    GardenState now = store.get(owner);
                    now.setPending(px, pz, null);
                    if (outcome == BuildQueue.Outcome.DONE) {
                        now.setPresetOn(px, pz, label);
                    }
                    store.save(now);
                }));
        return true;
    }

    private GardenBuilder.Palette palette() {
        return new GardenBuilder.Palette(config.surface(), config.rim(), config.subsoil(), config.floor());
    }

    private PlotTemplate.PasteRules pasteRules() {
        return new PlotTemplate.PasteRules(config.resetCropAge(), config.neverPaste());
    }

    // ── opening ────────────────────────────────────────────────────────────────

    /**
     * Get a player's Garden, building it if this is the first time they have asked for one.
     *
     * <p>The returned future completes <b>on the server thread</b> with a world that has already been
     * prepared, so callers can teleport into it directly. That is a deliberate departure from the host
     * service's contract (which completes on an unspecified thread): every caller here would otherwise
     * have to remember to hop back, and forgetting is the kind of bug that only shows up under load.
     *
     * <p>Two opens of the same Garden while the first is still in flight share it. Without that, a
     * double-click on first visit made both find "no such world" and both try to create it.
     */
    public CompletableFuture<World> open(UUID owner) {
        CompletableFuture<World> result = new CompletableFuture<>();
        CompletableFuture<World> inFlight = opening.putIfAbsent(owner, result);
        if (inFlight != null) {
            return inFlight;
        }
        String name = worldName(owner);
        load(name)
                .thenCompose(world -> world != null
                        ? CompletableFuture.completedFuture(world)
                        : create(name))
                .thenCompose(world -> onMain(() -> {
                    prepare(world);
                    return world;
                }))
                .whenComplete((world, error) -> {
                    opening.remove(owner, result);
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(world);
                    }
                });
        return result;
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
                    GardenBuilder.build(world, palette());
                    UUID owner = ownerOf(world);
                    if (owner != null) {
                        // A new world has no walls from older versions to clear; say so now so the
                        // first prepare does not walk the whole board looking for them.
                        GardenState state = store.get(owner);
                        state.setLegacyWallsCleared(true);
                        store.save(state);
                    }
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
     * added, and after a {@code /garden reload} changed one.
     */
    public void prepare(World world) {
        applyRules(world);
        world.setSpawnLocation(GardenBuilder.spawn(world));

        GardenState state = stateOf(world);
        if (state != null && !state.legacyWallsCleared() && !state.readOnly()) {
            if (config.clearLegacyWalls()) {
                GardenBuilder.clearLegacyWalls(world);
            }
            state.setLegacyWallsCleared(true);
            store.save(state);
        }

        sync(world);
        resumePending(world, state);
        onPrepared.accept(world);
    }

    /** Border, difficulty and gamerules, from {@code world:} in the config. */
    public void applyRules(World world) {
        WorldBorder border = world.getWorldBorder();
        border.setCenter(0.0, 0.0);
        border.setSize(GardenLayout.span());
        border.setWarningDistance((int) config.borderWarningDistance());
        border.setDamageAmount(config.borderDamageAmount());
        border.setDamageBuffer(config.borderDamageBuffer());

        world.setDifficulty(config.difficulty());
        ConfigurationSection rules = config.gamerules();
        if (rules != null) {
            for (String key : rules.getKeys(false)) {
                applyGameRule(world, key, rules);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyGameRule(World world, String key, ConfigurationSection rules) {
        // The host's resolver: it accepts both 26.2 ids and the classic camelCase names.
        GameRule<?> rule = IslandWorldRules.resolveRule(key);
        if (rule == null) {
            logger.warning("world.gamerules: unknown gamerule '" + key + "' — skipped.");
            return;
        }
        try {
            if (rule.getType() == Boolean.class) {
                world.setGameRule((GameRule<Boolean>) rule, rules.getBoolean(key));
            } else if (rule.getType() == Integer.class) {
                world.setGameRule((GameRule<Integer>) rule, rules.getInt(key));
            }
        } catch (RuntimeException rejected) {
            logger.warning("world.gamerules: could not set '" + key + "': " + rejected.getMessage());
        }
    }

    /**
     * Remove vanilla mobs among some entities — called as each chunk's entities load.
     *
     * <p>Blocking spawns only stops new ones. A Garden built before that rule existed grew horses and
     * sheep, and they were saved into the slime file, so they come back on every load until something
     * takes them out.
     *
     * <p>This runs from {@code EntitiesLoadEvent} rather than when the world opens. Paper loads a
     * chunk's entities separately from, and after, its blocks, so sweeping {@code world.getEntities()}
     * at open time saw almost nothing and removed almost nothing.
     *
     * <p>The test is an <b>empty persistent data container</b>. Every plugin that spawns a mob and
     * intends to keep it tags it: Sam carries this extension's key, and EcoMobs tags its own, so both
     * survive while anything vanilla spawned does not. Only {@code Mob}s are considered — dropped items,
     * armour stands, item frames and paintings are left exactly where they are.
     */
    public void sweepMobs(World world, Iterable<Entity> entities) {
        if (!config.sweepVanillaMobs()) {
            return;
        }
        int removed = 0;
        for (Entity entity : entities) {
            if (entity instanceof Mob mob && mob.getPersistentDataContainer().isEmpty()) {
                mob.remove();
                removed++;
            }
        }
        if (removed > 0) {
            logger.info("Garden '" + world.getName() + "': removed " + removed + " vanilla mob(s).");
        }
    }

    /**
     * Bring the world up to date with what it should contain: every plot generated, locked or not.
     *
     * <p>All plots are built, because a locked plot you can see across the board reads as a place you
     * are going to own — an empty hole reads as a bug. Unlocking changes where you may go, not whether
     * the ground exists. Plots already queued are skipped, so opening a Garden again while it is still
     * generating does not start a second copy of the same work.
     */
    public void sync(World world) {
        PlotTemplate fill = presets.defaultPreset();
        int queued = 0;
        for (int px = 0; px < GardenLayout.grid(); px++) {
            for (int pz = 0; pz < GardenLayout.grid(); pz++) {
                if (GardenBuilder.isGenerated(world, px, pz) || queue.isBuilding(world.getName(), px, pz)) {
                    continue;
                }
                // The farmhouse plot is never templated: stamping a farm over it would delete the barn,
                // the desk and Sam.
                PlotTemplate preset = GardenLayout.isCentre(px, pz) ? null : fill;
                queue.submit(new BuildQueue.Job(world.getName(), px, pz, preset, GardenBuilder.Base.FLAT,
                        palette(), pasteRules(), false, GardenBuilder.ceiling(config.maxTemplateHeight()),
                        0L, null));
                queued++;
            }
        }
        if (queued > 0) {
            logger.info("Garden '" + world.getName() + "': generating " + queued + " plot(s).");
        }
    }

    /** Restart rebuilds that were cut short by the world unloading. */
    private void resumePending(World world, GardenState state) {
        if (state == null || state.readOnly()) {
            return;
        }
        for (Map.Entry<int[], String> entry : state.pending().entrySet()) {
            int[] plot = entry.getKey();
            if (queue.isBuilding(world.getName(), plot[0], plot[1])) {
                continue;
            }
            if (!rebuild(world, plot[0], plot[1], entry.getValue(), false)) {
                logger.warning("Garden '" + world.getName() + "': could not resume '" + entry.getValue()
                        + "' on plot " + GardenLayout.plotName(plot[0], plot[1]) + "; leaving it as it is.");
                state.setPending(plot[0], plot[1], null);
                store.save(state);
            }
        }
    }

    // ── admin ──────────────────────────────────────────────────────────────────

    /**
     * Reset a player's Garden: forget its unlocks and presets, and optionally delete the world too.
     *
     * <p>With {@code wipeWorld}, anyone standing in it is sent to {@code evacuateTo} first, its queued
     * builds are dropped, and the world is unloaded without saving and deleted through the host. The
     * next {@code /garden} then builds a brand-new one — which is what a fresh contest needs.
     */
    public CompletableFuture<Void> reset(UUID owner, boolean wipeWorld, Location evacuateTo) {
        store.reset(owner);
        if (!wipeWorld) {
            return CompletableFuture.completedFuture(null);
        }
        String name = worldName(owner);
        queue.cancel(name);
        World world = Bukkit.getWorld(name);
        if (world != null) {
            for (Player player : world.getPlayers()) {
                player.teleport(evacuateTo);
            }
        }
        CompletableFuture<Void> unloaded = worlds.isLoaded(name)
                ? worlds.unloadIsland(name, false)
                : CompletableFuture.completedFuture(null);
        return unloaded.thenCompose(ignored -> worlds.deleteIsland(name));
    }

    // ── plumbing ───────────────────────────────────────────────────────────────

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
