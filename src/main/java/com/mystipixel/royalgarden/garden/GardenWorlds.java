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
 * One Garden world per player, created on first visit. Worlds come from RoyalSkyblock's name-based
 * {@link IslandWorldService}, so this gets ASP-backed worlds, unload grace and save-on-shutdown without
 * importing an ASP type. The world name is derived from the owner's UUID, so it is the ownership record.
 */
public final class GardenWorlds {

    // Distinct from RoyalSkyblock's island_ so the two never collide in a shared data source.
    private static final String PREFIX = "garden_";

    private final Plugin plugin;
    private final GardenConfig config;
    private final IslandWorldService worlds;
    private final GardenStore store;
    private final PresetLibrary presets;
    private final BuildQueue queue;
    private final Logger logger;

    // Opens in flight, so two requests for the same Garden share one load or one creation.
    private final Map<UUID, CompletableFuture<World>> opening = new ConcurrentHashMap<>();

    // Run on every Garden once it is prepared; this is how Sam gets to the desk.
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

    public PresetLibrary presets() {
        return presets;
    }

    /** Run on every Garden after {@link #prepare}. */
    public void onPrepared(Consumer<World> action) {
        this.onPrepared = action == null ? world -> { } : action;
    }

    public static String worldName(UUID owner) {
        return PREFIX + owner;
    }

    public static boolean isGarden(World world) {
        return world != null && world.getName().startsWith(PREFIX);
    }

    /** Null if it is not a Garden. */
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

    /** A generated plot rather than a captured one. */
    public static boolean isBuiltIn(String id) {
        return id == null
                || id.equalsIgnoreCase(GardenBuilder.Base.FLAT.name())
                || id.equalsIgnoreCase(GardenBuilder.Base.BEDROCK.name());
    }

    private static String label(String id) {
        return id == null ? "flat" : id.toLowerCase(Locale.ROOT);
    }

    /** Null if it is not a Garden. */
    public GardenState stateOf(World world) {
        UUID owner = ownerOf(world);
        return owner == null ? null : store.get(owner);
    }

    public GardenState state(UUID owner) {
        return store.get(owner);
    }

    /** For a state changed from outside (admin commands). */
    public void save(GardenState state) {
        store.save(state);
    }

    /** Saved on the writer thread; for changes nothing was paid for. */
    public void saveLater(GardenState state) {
        store.saveLater(state);
    }

    /**
     * Records and persists the unlock. Nothing is generated: every plot is built with the Garden, so this
     * only changes where the player may go. Returns false if it was not a legal purchase.
     */
    public boolean unlock(UUID owner, int px, int pz) {
        GardenState state = store.get(owner);
        if (!state.unlock(px, pz)) {
            return false;
        }
        store.save(state);
        return true;
    }

    /** So looking at a Garden does not create it. */
    public boolean hasGarden(UUID owner) {
        return store.exists(owner) || worlds.isLoaded(worldName(owner));
    }

    public boolean isLoaded(UUID owner) {
        return worlds.isLoaded(worldName(owner));
    }

    /** Nothing already built changes; applying a preset is a separate, destructive act. */
    public PlotTemplate capture(World world, int px, int pz, String id) throws IOException {
        PlotTemplate captured = PlotTemplate.capture(world, px, pz, config.maxTemplateHeight());
        presets.save(id, captured);
        logger.info("Captured plot " + GardenLayout.plotName(px, pz) + " as preset '" + id + "' ("
                + captured.height() + " layers).");
        return captured;
    }

    public boolean isBuilding(World world, int px, int pz) {
        return queue.isBuilding(world.getName(), px, pz);
    }

    /**
     * Rebuilds one plot from a preset, or from {@code flat} / {@code bedrock}, overwriting it completely.
     * Recorded as pending first, so a rebuild cut short by an unload resumes in {@link #prepare}.
     *
     * @param paced whether to take the slow, visible pace ({@code generation.paste-ticks-per-row})
     * @return false if the plot is already being written, or the preset could not be read
     */
    public boolean rebuild(World world, int px, int pz, String id, boolean paced) {
        if (queue.isBuilding(world.getName(), px, pz)) {
            // A second paste over a plot already being written would interleave rows with the first.
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
                        now.forgetDebris(px, pz);   // a rebuild clears everything above the ground
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

    /**
     * A player's Garden, built on first request. Completes <b>on the main thread</b> with a prepared world,
     * unlike the host service, so callers can teleport straight in. Concurrent opens of the same Garden
     * share one future, so a double-click cannot create it twice.
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

    // Completes with null if there is no Garden yet. The host has no exists() call, so absence is read
    // from loadIsland's failure by exception class name, to avoid compiling against the ASP API.
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

    // Builds the centre plot and persists the world before handing it back.
    private CompletableFuture<World> create(String name) {
        logger.info("Creating Garden world '" + name + "'.");
        return worlds.createIsland(name)
                .thenCompose(world -> onMain(() -> {
                    long started = System.nanoTime();
                    GardenBuilder.build(world, palette(), logger);
                    UUID owner = ownerOf(world);
                    if (owner != null) {
                        // A new world has no old walls to clear; recording that saves the first prepare a board-wide walk.
                        GardenState state = store.get(owner);
                        state.setLegacyWallsCleared(true);
                        state.setBarnVersion(GardenBuilder.BARN_VERSION);
                        state.setFarmhouse(Farmhouse.current().id());
                        store.save(state);
                    }
                    logger.info("Built Garden '" + name + "' in "
                            + (System.nanoTime() - started) / 1_000_000 + "ms.");
                    return world;
                }))
                // Persist before returning: a crash before the first save would leave an empty world that loads as
                // finished.
                .thenCompose(world -> worlds.saveIsland(name).thenApply(ignored -> world));
    }

    /**
     * Settings a Garden should always have, applied on every load. Idempotent: world properties come from
     * the host's island config, so anything the Garden needs differently is re-asserted here.
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

        refreshFarmhouse(world, state);

        sync(world);
        resumePending(world, state);
        onPrepared.accept(world);
    }

    // An older built-in barn is renovated in place. Any other change (schematic added, changed or removed)
    // clears and rebuilds the farmhouse plot, which is read-only to players, so nothing of theirs is lost.
    private void refreshFarmhouse(World world, GardenState state) {
        if (state == null || state.readOnly()) {
            return;
        }
        String want = Farmhouse.current().id();
        String have = state.farmhouse();
        if (want.equals(have)) {
            return;
        }
        if (want.startsWith("generated-") && have.startsWith("generated-")) {
            GardenBuilder.renovate(world, state.barnVersion());
            logger.info("Garden '" + world.getName() + "': renovated the barn from version "
                    + state.barnVersion() + " to " + GardenBuilder.BARN_VERSION + ".");
            state.setBarnVersion(GardenBuilder.BARN_VERSION);
            state.setFarmhouse(want);
            store.save(state);
            return;
        }
        int centre = GardenLayout.centre();
        if (queue.isBuilding(world.getName(), centre, centre)) {
            return;                                   // already being rebuilt
        }
        logger.info("Garden '" + world.getName() + "': rebuilding the farmhouse plot (" + have + " -> " + want + ").");
        int ceiling = Math.max(GardenBuilder.ceiling(config.maxTemplateHeight()), Farmhouse.current().top() + 8);
        queue.submit(new BuildQueue.Job(world.getName(), centre, centre, null, GardenBuilder.Base.FLAT, palette(),
                pasteRules(), true, ceiling, 0L, outcome -> {
                    if (outcome != BuildQueue.Outcome.DONE) {
                        return;                       // not recorded, so the next load tries again
                    }
                    Farmhouse farmhouse = Farmhouse.current();
                    farmhouse.build(world, logger);
                    GardenState now = stateOf(world);
                    if (now != null) {
                        now.setFarmhouse(farmhouse.id());
                        store.save(now);
                    }
                    for (Player player : world.getPlayers()) {
                        if (GardenLayout.isCentre(GardenLayout.plotAt(player.getLocation().getBlockX()),
                                GardenLayout.plotAt(player.getLocation().getBlockZ()))) {
                            player.teleport(GardenBuilder.spawn(world));   // not left inside a new wall
                        }
                    }
                    onPrepared.accept(world);          // Sam, the Composter and visitors to their new spots
                }));
    }

    /** Sam, the Composter and the visitors back to their spots in every loaded Garden, after a spot moved. */
    public void refreshSpots() {
        for (World world : Bukkit.getWorlds()) {
            if (isGarden(world)) {
                world.setSpawnLocation(GardenBuilder.spawn(world));
                onPrepared.accept(world);
            }
        }
    }

    /** {@link #refreshFarmhouse} for every loaded Garden, after a reload changed the farmhouse. */
    public void refreshFarmhouses() {
        for (World world : Bukkit.getWorlds()) {
            if (isGarden(world)) {
                refreshFarmhouse(world, stateOf(world));
            }
        }
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
            logger.warning("world.gamerules: unknown gamerule '" + key + "'; skipped.");
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
     * Removes vanilla mobs as each chunk's entities load (older Gardens saved some into the slime file).
     * Called from {@code EntitiesLoadEvent}, since Paper loads entities after blocks. A {@code Mob} with an
     * empty persistent data container is vanilla; Sam and EcoMobs tag theirs. Non-mobs are left alone.
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
     * Generates every plot, locked or not, so the board never shows holes. Plots already queued are skipped,
     * so reopening a Garden mid-generation does not double the work.
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
        overgrow(world);
    }

    // Grows debris on every locked plot that has none yet. Never on bought plots. Writes only into air, so
    // it can queue behind the job generating the same plot's ground.
    private void overgrow(World world) {
        Overgrowth.Settings settings = config.clearing();
        UUID owner = ownerOf(world);
        GardenState state = owner == null ? null : store.get(owner);
        if (!settings.enabled() || state == null || state.readOnly()) {
            return;
        }
        int queued = 0;
        for (int px = 0; px < GardenLayout.grid(); px++) {
            for (int pz = 0; pz < GardenLayout.grid(); pz++) {
                if (GardenLayout.isCentre(px, pz) || state.isUnlocked(px, pz) || state.isOvergrown(px, pz)
                        || queue.isGrowing(world.getName(), px, pz)) {
                    continue;
                }
                Overgrowth.Plan plan = Overgrowth.plan(Overgrowth.seed(owner, px, pz), GardenLayout.plotSize(),
                        GardenLayout.groundY(), settings);
                int plotX = px;
                int plotZ = pz;
                queue.submit(new BuildQueue.Job(world.getName(), px, pz, plan, outcome -> {
                    if (outcome != BuildQueue.Outcome.DONE) {
                        return;                     // not recorded, so the next load grows it again
                    }
                    GardenState now = store.get(owner);
                    now.setOvergrowth(plotX, plotZ, plan.present());
                    store.save(now);
                }));
                queued++;
            }
        }
        if (queued > 0) {
            logger.info("Garden '" + world.getName() + "': growing debris on " + queued + " locked plot(s).");
        }
    }

    // Restart rebuilds that were cut short by the world unloading.
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

    /**
     * Forgets a Garden's unlocks and presets. With {@code wipeWorld}, occupants go to {@code evacuateTo},
     * queued builds are dropped and the world is deleted, so the next {@code /garden} builds a new one.
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

    // Walks an async failure's cause chain looking for ASP's "no such world" signal.
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

    // Completes the returned future on the main thread.
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
