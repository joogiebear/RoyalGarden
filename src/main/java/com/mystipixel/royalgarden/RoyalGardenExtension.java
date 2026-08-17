package com.mystipixel.royalgarden;

import com.mystipixel.royalgarden.command.CommandDesk;
import com.mystipixel.royalgarden.command.CommandGarden;
import com.mystipixel.royalgarden.garden.GardenWorlds;
import com.mystipixel.royalgarden.garden.Sam;
import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.data.Storage;
import com.mystipixel.royalskyblock.island.IslandManager;
import com.mystipixel.royalskyblock.world.IslandWorldService;
import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.command.impl.PluginCommand;
import com.willfp.eco.core.extensions.Extension;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

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
 * <h2>Phase 1</h2>
 *
 * <p>A player gets a Garden world of their own, built on first visit: the middle plot of a 5&times;5
 * board, a farmhouse on it, Sam at the desk, and a border at the board's edge. The remaining
 * twenty-four plots are deliberately empty sky — generating one is what unlocking it will mean, and
 * unlock state is the first thing Phase 2 needs to persist.
 */
public final class RoyalGardenExtension extends Extension {

    private IslandManager islands;
    private IslandWorldService worlds;
    private Storage storage;

    private GardenWorlds gardens;
    private Sam sam;
    private PluginCommand gardenCommand;
    private PluginCommand deskCommand;

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

        if (!isReady()) {
            getLogger().warning("RoyalGarden is loaded but idle: the host has no island services. "
                    + "Advanced Slime Paper is required for island worlds; on plain Paper "
                    + "RoyalSkyblock aborts before creating them.");
            return;
        }

        getLogger().info("Host services resolved — world backend is "
                + this.worlds.getClass().getSimpleName() + ".");
        start();
    }

    /** Stand everything up. Only reached once the host's services are known to exist. */
    private void start() {
        this.gardens = new GardenWorlds(getPlugin(), worlds, getLogger());
        this.sam = new Sam(getPlugin());
        Bukkit.getPluginManager().registerEvents(sam, getPlugin());

        declare(CommandGarden.PERMISSION, "Go to your Garden.");
        declare(CommandDesk.PERMISSION, "Open the Garden desk.");

        this.gardenCommand = new CommandGarden(getPlugin(), gardens, sam);
        this.deskCommand = new CommandDesk(getPlugin());
        gardenCommand.register();
        deskCommand.register();

        getLogger().info("Phase 1 ready: /garden, /desk, Sam at the desk.");
    }

    /**
     * Register a permission node that every player has by default.
     *
     * <p>An extension has no {@code plugin.yml}, so there is nowhere to declare a node the way the host
     * declares its own — and an <em>undeclared</em> node is not simply ungated. Bukkit resolves one it
     * has never seen through {@code PermissionDefault.OP}, so leaving these undeclared makes both
     * commands operators-only without a word in the log about it. Declaring them here is what makes
     * {@code default: true} mean anything.
     */
    private void declare(String node, String description) {
        PluginManager plugins = Bukkit.getPluginManager();
        if (plugins.getPermission(node) != null) {
            return;                          // a reload re-runs start(); adding twice throws
        }
        plugins.addPermission(new Permission(node, description, PermissionDefault.TRUE));
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

    /**
     * Tear down what {@link #start()} put up.
     *
     * <p>Commands and listeners are registered against the <em>host</em> plugin, so nothing here is
     * cleaned up for us on an extension reload — leaving them would stack a second copy of both on the
     * next load. Garden worlds are not unloaded: the host's world service owns their lifecycle and
     * saves them on shutdown, and racing it would be the one way to actually lose someone's farm.
     */
    @Override
    protected void onDisable() {
        if (gardenCommand != null) {
            gardenCommand.unregister();
            gardenCommand = null;
        }
        if (deskCommand != null) {
            deskCommand.unregister();
            deskCommand = null;
        }
        if (sam != null) {
            HandlerList.unregisterAll(sam);
            sam = null;
        }
        Bukkit.getPluginManager().removePermission(CommandGarden.PERMISSION);
        Bukkit.getPluginManager().removePermission(CommandDesk.PERMISSION);
        this.gardens = null;
        this.islands = null;
        this.worlds = null;
        this.storage = null;
    }

    /** The host's island manager, once enabled. */
    public IslandManager islands() {
        return islands;
    }

    /** The host's world service — how a Garden world is created and unloaded. */
    public IslandWorldService worlds() {
        return worlds;
    }

    /** The host's storage. Garden unlock state is expected to live here from Phase 2. */
    public Storage storage() {
        return storage;
    }

    /** Garden worlds, once the extension is running. Null when the host had no island services. */
    public GardenWorlds gardens() {
        return gardens;
    }
}
