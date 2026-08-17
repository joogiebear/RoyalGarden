package com.mystipixel.royalgarden;

import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.data.Storage;
import com.mystipixel.royalskyblock.island.IslandManager;
import com.mystipixel.royalskyblock.world.IslandWorldService;
import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.extensions.Extension;

import java.util.function.Supplier;

/**
 * The Garden, loaded as an eco extension of RoyalSkyblock.
 *
 * <p>An extension rather than a plugin because everything The Garden needs already exists in the
 * host: per-profile worlds through Advanced Slime Paper, persistence, a menu engine, currencies and
 * a border service. Building alongside would mean reimplementing all of it and then keeping the two
 * copies agreeing about who owns what.
 *
 * <p>eco loads any jar in {@code plugins/RoyalSkyblock/extensions/} that carries an
 * {@code extension.yml}. It does so quietly: a jar with no manifest is skipped with a log line and
 * no error, so the console is the only confirmation a build actually took.
 *
 * <h2>Phase 0</h2>
 *
 * <p>This does nothing yet on purpose. It exists to prove the two assumptions the whole design rests
 * on — that an extension loads at all, and that it can reach the host's services — before any Garden
 * behaviour is written on top of them. Both are cheap to check now and expensive to discover later.
 */
public final class RoyalGardenExtension extends Extension {

    private IslandManager islands;
    private IslandWorldService worlds;
    private Storage storage;

    public RoyalGardenExtension(EcoPlugin plugin) {
        super(plugin);
    }

    /**
     * Runs inside the host's own enable, before its services exist.
     *
     * <p>Keep this trivial. eco enables extensions from {@code EcoPlugin.onEnable}, so anything
     * thrown here propagates out of RoyalSkyblock's enable and disables the whole plugin — an
     * extension can take its host down. Service resolution therefore waits for
     * {@link #onAfterLoad()}, and everything here is defensive by default.
     */
    @Override
    protected void onEnable() {
        getLogger().info("RoyalGarden " + getVersion() + " loaded.");
    }

    /**
     * Runs once the host has finished loading, which is where its services actually exist.
     *
     * <p>They can still be absent: RoyalSkyblock leaves {@code islandManager} null when the island
     * world backend is unavailable — running plain Paper instead of Advanced Slime Paper does it —
     * and its accessors assert non-null, so reaching for one throws. Nothing here is allowed to
     * escape; a Garden that cannot start should say so and leave the server running.
     */
    @Override
    protected void onAfterLoad() {
        RoyalSkyblockPlugin host = RoyalSkyblockPlugin.Companion.get();

        // The fields behind these accessors are private; the accessors are not. That is the whole
        // reason this can be an extension without touching RoyalSkyblock itself.
        this.islands = resolve("island manager", host::islands);
        this.worlds = resolve("world service", host::worlds);
        this.storage = resolve("storage", host::storage);

        if (isReady()) {
            getLogger().info("Host services resolved — RoyalGarden can build on:");
            getLogger().info("  island manager: " + this.islands.getClass().getSimpleName());
            getLogger().info("  world service:  " + this.worlds.getClass().getSimpleName());
            getLogger().info("  storage:        " + this.storage.getClass().getSimpleName());
        } else {
            getLogger().warning("RoyalGarden is loaded but idle: the host has no island services. "
                    + "Advanced Slime Paper is required for island worlds; on plain Paper "
                    + "RoyalSkyblock aborts before creating them.");
        }
        getLogger().info("Phase 0: no Garden behaviour registered yet.");
    }

    /** Reads one host service, treating an absent one as a fact to report rather than a crash. */
    private <T> T resolve(String what, Supplier<T> accessor) {
        try {
            return accessor.get();
        } catch (RuntimeException absent) {
            getLogger().warning("Host " + what + " unavailable: " + absent);
            return null;
        }
    }

    /** Whether every host service this extension needs is present. */
    public boolean isReady() {
        return islands != null && worlds != null && storage != null;
    }

    @Override
    protected void onDisable() {
        this.islands = null;
        this.worlds = null;
        this.storage = null;
    }

    /** The host's island manager, once enabled. */
    public IslandManager islands() {
        return islands;
    }

    /** The host's world service — how a Garden world will be created and unloaded. */
    public IslandWorldService worlds() {
        return worlds;
    }

    /** The host's storage. Garden state is expected to live in the island upgrade map instead. */
    public Storage storage() {
        return storage;
    }
}
