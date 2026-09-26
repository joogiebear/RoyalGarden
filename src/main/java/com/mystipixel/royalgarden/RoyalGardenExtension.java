package com.mystipixel.royalgarden;

import com.mystipixel.royalgarden.command.CommandDesk;
import com.mystipixel.royalgarden.command.CommandGarden;
import com.mystipixel.royalgarden.composter.ComposterBlock;
import com.mystipixel.royalgarden.composter.ComposterConfig;
import com.mystipixel.royalgarden.composter.ComposterMenu;
import com.mystipixel.royalgarden.composter.ComposterService;
import com.mystipixel.royalgarden.composter.ComposterStore;
import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.garden.BuildQueue;
import com.mystipixel.royalgarden.garden.GardenLayout;
import com.mystipixel.royalgarden.garden.GardenMenu;
import com.mystipixel.royalgarden.garden.GardenProtection;
import com.mystipixel.royalgarden.garden.GardenStore;
import com.mystipixel.royalgarden.garden.GardenWorlds;
import com.mystipixel.royalgarden.garden.PresetLibrary;
import com.mystipixel.royalgarden.garden.PresetService;
import com.mystipixel.royalgarden.garden.Sam;
import com.mystipixel.royalgarden.garden.UnlockService;
import com.mystipixel.royalgarden.hooks.RoyalGardenExpansion;
import com.mystipixel.royalgarden.level.GardenLevels;
import com.mystipixel.royalgarden.level.LevelConfig;
import com.mystipixel.royalgarden.milestone.MilestoneConfig;
import com.mystipixel.royalgarden.milestone.MilestoneListener;
import com.mystipixel.royalgarden.milestone.MilestoneMenu;
import com.mystipixel.royalgarden.milestone.MilestoneService;
import com.mystipixel.royalgarden.milestone.MilestoneStore;
import com.mystipixel.royalgarden.milestone.PlacedBlocks;
import com.mystipixel.royalgarden.pest.PestConfig;
import com.mystipixel.royalgarden.pest.PestHolders;
import com.mystipixel.royalgarden.pest.PestMenu;
import com.mystipixel.royalgarden.pest.PestService;
import com.mystipixel.royalgarden.pest.PestStore;
import com.mystipixel.royalgarden.upgrade.UpgradeConfig;
import com.mystipixel.royalgarden.upgrade.UpgradeHolders;
import com.mystipixel.royalgarden.upgrade.UpgradeMenu;
import com.mystipixel.royalgarden.upgrade.UpgradeService;
import com.mystipixel.royalgarden.upgrade.UpgradeStore;
import com.mystipixel.royalgarden.visitor.VisitorConfig;
import com.mystipixel.royalgarden.visitor.VisitorMenu;
import com.mystipixel.royalgarden.visitor.VisitorNpcs;
import com.mystipixel.royalgarden.visitor.VisitorPlaceholders;
import com.mystipixel.royalgarden.visitor.VisitorService;
import com.mystipixel.royalgarden.visitor.VisitorStore;
import com.mystipixel.royalskyblock.RoyalSkyblockPlugin;
import com.mystipixel.royalskyblock.currency.CurrencyService;
import com.mystipixel.royalskyblock.data.Storage;
import com.mystipixel.royalskyblock.island.IslandManager;
import com.mystipixel.royalskyblock.world.IslandWorldService;
import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.command.impl.PluginCommand;
import com.willfp.eco.core.extensions.Extension;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
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
 * <p>Everything tunable lives in {@code plugins/RoyalSkyblock/extensions/RoyalGarden/}:
 * {@code config.yml} (the board, costs, rewards, rules, menus), {@code messages.yml},
 * {@code presets.yml}, {@code milestones.yml}, {@code levels.yml}, {@code visitors.yml},
 * {@code upgrades.yml}, {@code composter.yml} and {@code pests.yml}. The host's
 * reload reloads them too.
 */
public final class RoyalGardenExtension extends Extension {

    private IslandManager islands;
    private IslandWorldService worlds;
    private Storage storage;
    private CurrencyService currency;

    private GardenConfig config;
    private Messages messages;
    private PresetLibrary presets;
    private BuildQueue queue;
    private GardenWorlds gardens;
    private GardenMenu menu;
    private Sam sam;
    private GardenProtection protection;
    private PluginCommand gardenCommand;
    private PluginCommand deskCommand;
    private LevelConfig levelConfig;
    private MilestoneConfig milestoneConfig;
    private MilestoneStore milestoneStore;
    private MilestoneListener milestoneListener;
    private PlacedBlocks placedBlocks;
    private BukkitTask milestoneSaver;
    private BukkitTask milestoneSweeper;
    private VisitorConfig visitorConfig;
    private VisitorService visitors;
    private VisitorNpcs visitorNpcs;
    private BukkitTask visitorTicker;
    private Object expansion;
    private UpgradeConfig upgradeConfig;
    private UpgradeService upgrades;
    private UpgradeHolders upgradeHolders;
    private ComposterConfig composterConfig;
    private ComposterService composter;
    private ComposterBlock composterBlock;
    private BukkitTask composterTicker;
    private PestConfig pestConfig;
    private PestService pests;
    private PestHolders pestHolders;
    private BukkitTask pestConfiner;

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
        this.currency = resolve("currency service", host::currency);

        if (!isReady()) {
            getLogger().warning("RoyalGarden is loaded but idle: the host has no island services. "
                    + "Advanced Slime Paper is required for island worlds; on plain Paper "
                    + "RoyalSkyblock aborts before creating them.");
            return;
        }

        getLogger().info("Host services resolved — world backend is "
                + this.worlds.getClass().getSimpleName() + ".");
        try {
            start(host);
        } catch (RuntimeException failed) {
            getLogger().log(java.util.logging.Level.SEVERE, "RoyalGarden could not start; the host is "
                    + "unaffected.", failed);
        }
    }

    /**
     * Re-read {@code config.yml}, {@code messages.yml} and {@code presets.yml}.
     *
     * <p>Reached from the host's own reload and from {@code /garden reload}. The board's shape is the
     * one thing that does not change here — see {@link GardenConfig}.
     */
    @Override
    protected void onReload() {
        reloadFiles();
    }

    private void reloadFiles() {
        if (config == null) {
            return;
        }
        config.reload();
        messages.reload();
        presets.reload();
        milestoneConfig.reload();
        levelConfig.reload();
        visitorConfig.reload();
        upgradeConfig.reload();
        composterConfig.reload();                      // after milestones.yml: its crops are organic matter
        menu.reload();
        scheduleMilestoneSaves();
        scheduleVisitorArrivals();
        visitorNpcs.syncAll();
        upgradeHolders.compile();                      // after milestones.yml too: crops come from there
        UpgradeHolders.refreshAll();
        scheduleComposter();
        composterBlock.ensureAll();
        pestConfig.reload();
        pestHolders.compile();                         // after milestones.yml: %blocks% and %drops% come from there
        PestHolders.refreshAll();
        schedulePests();
        getLogger().info("Reloaded config.yml, messages.yml, presets.yml, milestones.yml, levels.yml, "
                + "visitors.yml, upgrades.yml, composter.yml and pests.yml.");
    }

    /**
     * This extension's own folder: {@code plugins/RoyalSkyblock/extensions/<name>/}.
     *
     * <p>Not {@link #getDataFolder()}, which returns the <em>host's</em> folder — every extension
     * calling it writes into {@code plugins/RoyalSkyblock/} directly, mixed in with the host's own
     * {@code gui/}, {@code perks/} and {@code upgrades/}.
     */
    private File dataDir() {
        return new File(new File(getPlugin().getDataFolder(), "extensions"), getName());
    }

    /**
     * Move data written by an earlier version out of the host's folder and into ours.
     *
     * <p>One-time and conditional: it only moves a name if we do not already own one, so it cannot
     * overwrite current data, and it does nothing at all on a clean install.
     */
    private void migrateStrayData(File target) {
        File host = getPlugin().getDataFolder();
        for (String name : new String[]{"gardens", "presets", "presets.yml"}) {
            File stray = new File(host, name);
            File proper = new File(target, name);
            if (!stray.exists() || proper.exists()) {
                continue;
            }
            if (stray.renameTo(proper)) {
                getLogger().info("Moved " + name + " out of the host's folder into " + target.getName() + "/.");
            } else {
                getLogger().warning("Could not move " + stray + " into " + target
                        + " — it will be read from neither. Move it by hand.");
            }
        }
    }

    /** Stand everything up. Only reached once the host's services are known to exist. */
    private void start(RoyalSkyblockPlugin host) {
        File data = dataDir();
        if (!data.exists() && !data.mkdirs()) {
            getLogger().warning("Could not create " + data + " — Garden data will not persist.");
        }
        migrateStrayData(data);

        this.config = new GardenConfig(data, getLogger());
        String layoutProblem = GardenLayout.configure(config.grid(), config.plotSize(), config.groundY());
        if (layoutProblem != null) {
            getLogger().severe(layoutProblem + " — using the default 5x5 board of 96-block plots.");
        }
        this.messages = new Messages(data, getLogger());

        GardenStore store = new GardenStore(data, getLogger());
        this.presets = new PresetLibrary(data, getLogger());
        this.queue = new BuildQueue(getPlugin(), config, worlds, getLogger());
        this.gardens = new GardenWorlds(getPlugin(), config, worlds, store, presets, queue, getLogger());

        UnlockService unlocks = new UnlockService(config, messages, currency, gardens, getLogger());
        PresetService presetService = new PresetService(config, messages, currency, gardens);
        this.menu = new GardenMenu(config, messages, gardens, unlocks, presetService);
        this.sam = new Sam(getPlugin(), config, menu);
        this.protection = new GardenProtection(gardens, config, messages);
        gardens.onPrepared(sam::ensureWhenLoaded);
        Bukkit.getPluginManager().registerEvents(sam, getPlugin());
        Bukkit.getPluginManager().registerEvents(protection, getPlugin());

        declare(CommandGarden.PERMISSION, PermissionDefault.TRUE, "Go to your Garden.");
        declare(CommandDesk.PERMISSION, PermissionDefault.TRUE, "Open the Garden desk.");
        declare(GardenProtection.BYPASS, PermissionDefault.OP, "Edit a Garden's protected blocks.");
        declare(PresetService.BYPASS_COOLDOWN, PermissionDefault.OP, "Skip the cooldown between plot rebuilds.");
        declare(CommandGarden.ADMIN, PermissionDefault.OP, "Manage presets and other players' Gardens.");

        // Crop Milestones: lifetime harvest per crop, with tiers and rewards, on the desk.
        this.milestoneConfig = new MilestoneConfig(data, getLogger());
        this.milestoneStore = new MilestoneStore(data, getLogger());
        MilestoneService milestones = new MilestoneService(milestoneConfig, milestoneStore, messages);
        this.placedBlocks = new PlacedBlocks(getPlugin(), milestoneConfig);
        this.milestoneListener = new MilestoneListener(milestones, placedBlocks);
        MilestoneMenu milestoneMenu = new MilestoneMenu(milestones, menu::open);
        menu.setMilestones(player -> milestoneMenu.open(player, player.getUniqueId(), player.getName()));
        Bukkit.getPluginManager().registerEvents(placedBlocks, getPlugin());
        Bukkit.getPluginManager().registerEvents(milestoneListener, getPlugin());

        // Garden Levels: XP from milestone tiers and plot purchases (and /garden xp), levels that pay
        // rewards and gate plots and presets.
        this.levelConfig = new LevelConfig(data, getLogger());
        GardenLevels levels = new GardenLevels(levelConfig, gardens, messages);
        milestones.onTier((player, crop, tier) ->
                levels.give(player.getUniqueId(), levelConfig.milestoneXp(tier), "level-source-milestone"));
        unlocks.setLevels(new UnlockService.Levels() {
            @Override
            public int level(java.util.UUID owner) {
                return levels.level(owner);
            }

            @Override
            public void unlocked(org.bukkit.entity.Player player, int purchase) {
                levels.give(player.getUniqueId(), levelConfig.unlockXp(purchase), "level-source-unlock");
            }
        });
        presetService.setLevels(levels::level);
        menu.setLevelPlaceholders(levels::placeholders);
        scheduleMilestoneSaves();
        this.milestoneSweeper = Bukkit.getScheduler().runTaskTimer(getPlugin(), milestoneListener::clearStale, 20L, 20L);

        // Visitors: NPCs at the barn asking for crops, paying Copper, Garden XP and rewards.
        this.visitorConfig = new VisitorConfig(data, getLogger());
        VisitorStore visitorStore = new VisitorStore(data, getLogger());
        this.visitors = new VisitorService(visitorConfig, visitorStore, messages, getLogger());
        visitors.setLevels(levels::level, (owner, amount) -> levels.give(owner, amount, "level-source-visitor"));
        visitors.setHasGarden(gardens::hasGarden);
        VisitorMenu visitorMenu = new VisitorMenu(visitors, messages, menu::open);
        this.visitorNpcs = new VisitorNpcs(getPlugin(), visitors, messages, visitorMenu::openOffer);
        visitors.onChange(visitorNpcs::sync);
        menu.setVisitors(visitorMenu::open);
        Sam desk = sam;
        VisitorNpcs outside = visitorNpcs;
        Bukkit.getPluginManager().registerEvents(visitors, getPlugin());
        Bukkit.getPluginManager().registerEvents(visitorNpcs, getPlugin());
        for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
            visitors.welcome(online);                   // online across an extension reload
        }
        scheduleVisitorArrivals();
        visitorNpcs.syncAll();
        // Crop upgrades: levels bought with Copper at the desk, each granting libreforge effects.
        this.upgradeConfig = new UpgradeConfig(data, getLogger());
        this.upgrades = new UpgradeService(upgradeConfig, new UpgradeStore(data, getLogger()), milestoneConfig,
                messages, currency);
        upgrades.setXp((owner, amount) -> levels.give(owner, amount, "level-source-upgrade"));
        upgrades.onChange(UpgradeHolders::refresh);
        UpgradeMenu upgradeMenu = new UpgradeMenu(upgrades, messages, menu::open);
        menu.setUpgrades(upgradeMenu::open);
        Bukkit.getPluginManager().registerEvents(upgrades, getPlugin());
        this.upgradeHolders = new UpgradeHolders(getPlugin(), upgrades);
        upgradeHolders.register();

        // The Composter: organic matter and fuel in, Compost out over time, with machine upgrades bought
        // with Copper. It runs while its owner is away and catches up when next touched.
        this.composterConfig = new ComposterConfig(data, getLogger(), milestoneConfig);
        this.composter = new ComposterService(composterConfig, new ComposterStore(data, getLogger()), messages,
                currency, getLogger());
        composter.setXp((owner, amount) -> levels.give(owner, amount, "level-source-composter"));
        ComposterMenu composterMenu = new ComposterMenu(composter, messages, menu::open);
        menu.setComposter(composterMenu::open);
        this.composterBlock = new ComposterBlock(getPlugin(), composterConfig, messages, composterMenu::open);
        protection.setMachines(composterBlock::isComposter);
        ComposterBlock barnComposter = composterBlock;
        gardens.onPrepared(world -> {
            desk.ensureWhenLoaded(world);
            outside.sync(world);
            barnComposter.ensure(world);
        });
        Bukkit.getPluginManager().registerEvents(composter, getPlugin());
        Bukkit.getPluginManager().registerEvents(composterBlock, getPlugin());
        composterBlock.ensureAll();
        scheduleComposter();

        // Pests: EcoMobs mobs brought onto plots by farming, held to their plot, paying Copper and Garden XP
        // when killed. An infested plot's slowdown is libreforge effects, like crop upgrades.
        this.pestConfig = new PestConfig(data, getLogger());
        this.pests = new PestService(getPlugin(), pestConfig, new PestStore(data, getLogger()), messages, gardens,
                getLogger());
        VisitorService copperPayer = visitors;
        pests.setPayment(levels::level, (player, amount) -> levels.give(player, amount, "level-source-pest"),
                copperPayer::payCopper, visitorConfig::copperCurrency);
        pests.setCrops(milestoneConfig::crop);
        pests.onChange(PestHolders::refreshGarden);
        milestoneListener.onHarvest(pests::harvested);
        PestMenu pestMenu = new PestMenu(pests, gardens, messages, menu::open);
        menu.setPests(pestMenu::open);
        Bukkit.getPluginManager().registerEvents(pests, getPlugin());
        this.pestHolders = new PestHolders(getPlugin(), pests, milestoneConfig);
        Bukkit.getPluginManager().registerEvents(pestHolders, getPlugin());
        pestHolders.register();
        schedulePests();

        VisitorPlaceholders visitorPlaceholders = new VisitorPlaceholders(visitors);
        UpgradeService upgradeLookup = upgrades;
        ComposterService composterLookup = composter;
        PestService pestLookup = pests;
        registerExpansion((player, key) -> {
            String value = visitorPlaceholders.resolve(player, key);
            if (value == null) {
                value = upgradeLookup.resolve(player, key);
            }
            if (value == null) {
                value = composterLookup.resolve(player, key);
            }
            return value != null ? value : pestLookup.resolve(player, key);
        });

        CommandGarden garden = new CommandGarden(getPlugin(), config.commandName("garden", "garden"), gardens,
                messages, this::reloadFiles, () -> evacuationPoint(host));
        garden.setMilestones(milestones, milestoneMenu);
        garden.setLevels(levels);
        garden.setVisitors(visitors, visitorMenu);
        garden.setUpgrades(upgrades, upgradeMenu);
        garden.setComposter(composter, composterMenu);
        garden.setPests(pests, pestMenu);
        this.gardenCommand = garden;
        this.deskCommand = new CommandDesk(getPlugin(), config.commandName("desk", "desk"), menu);
        gardenCommand.register();
        deskCommand.register();

        getLogger().info("Ready: /" + gardenCommand.getName() + ", /" + deskCommand.getName()
                + ", Sam at the desk, " + GardenLayout.grid() + "×" + GardenLayout.grid()
                + " plots of " + GardenLayout.plotSize() + ".");
    }

    /** Save milestone progress every {@code save-interval-seconds}. */
    private void scheduleMilestoneSaves() {
        if (milestoneSaver != null) {
            milestoneSaver.cancel();
        }
        long period = milestoneConfig.saveIntervalSeconds() * 20L;
        milestoneSaver = Bukkit.getScheduler().runTaskTimer(getPlugin(), milestoneStore::saveAll, period, period);
    }

    /** Catch online players' Composters up every {@code compost.check-every-seconds}. */
    private void scheduleComposter() {
        if (composterTicker != null) {
            composterTicker.cancel();
        }
        long period = composterConfig.checkTicks();
        composterTicker = Bukkit.getScheduler().runTaskTimer(getPlugin(), composter::tickAll, period, period);
    }

    /** Hold pests to their plots every {@code confine.check-every-seconds}. */
    private void schedulePests() {
        if (pestConfiner != null) {
            pestConfiner.cancel();
        }
        long period = pestConfig.confineTicks();
        pestConfiner = Bukkit.getScheduler().runTaskTimer(getPlugin(), pests::confineAll, period, period);
    }

    /**
     * Register {@code %royalgarden_...%} with PlaceholderAPI, if it is enabled.
     *
     * <p>The expansion class extends a PlaceholderAPI type, so it is only touched behind the enabled
     * check, and held as an {@code Object} so this class never needs PlaceholderAPI to load. A failure
     * costs the placeholders, never the extension.
     */
    private void registerExpansion(java.util.function.BiFunction<org.bukkit.OfflinePlayer, String, String> resolver) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().info("PlaceholderAPI not found — %royalgarden_...% placeholders are off.");
            return;
        }
        try {
            RoyalGardenExpansion created = new RoyalGardenExpansion(getVersion(), resolver);
            if (created.register()) {
                this.expansion = created;
            } else {
                getLogger().warning("PlaceholderAPI refused the royalgarden expansion.");
            }
        } catch (LinkageError unusable) {
            getLogger().warning("Could not register %royalgarden_...% placeholders: " + unusable);
        }
    }

    /** Check for visitor arrivals every {@code arrivals.check-every-seconds}. */
    private void scheduleVisitorArrivals() {
        if (visitorTicker != null) {
            visitorTicker.cancel();
        }
        long period = visitorConfig.checkTicks();
        visitorTicker = Bukkit.getScheduler().runTaskTimer(getPlugin(), visitors::tickAll, period, period);
    }

    /** Where players standing in a Garden go when it is deleted. */
    private Location evacuationPoint(RoyalSkyblockPlugin host) {
        String name = config.evacuateWorld();
        if (name == null || name.isBlank()) {
            name = host.conf().getString("spawn.world", "world");
        }
        World world = name == null ? null : Bukkit.getWorld(name);
        if (world == null) {
            world = Bukkit.getWorlds().get(0);
        }
        return world.getSpawnLocation();
    }

    /**
     * Register a permission node.
     *
     * <p>An extension has no {@code plugin.yml}, so there is nowhere to declare a node the way the host
     * declares its own — and an <em>undeclared</em> node is not simply ungated. Bukkit resolves one it
     * has never seen through {@code PermissionDefault.OP}, so leaving these undeclared makes both
     * commands operators-only without a word in the log about it. Declaring them here is what makes
     * {@code default: true} mean anything.
     */
    private void declare(String node, PermissionDefault fallback, String description) {
        PluginManager plugins = Bukkit.getPluginManager();
        if (plugins.getPermission(node) != null) {
            return;                          // a reload re-runs start(); adding twice throws
        }
        plugins.addPermission(new Permission(node, description, fallback));
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
        return islands != null && worlds != null && storage != null && currency != null;
    }

    /**
     * Tear down what {@link #start} put up.
     *
     * <p>Commands, listeners and the build queue's task are registered against the <em>host</em>
     * plugin, so nothing here is cleaned up for us on an extension reload — leaving them would stack a
     * second copy of each on the next load. Garden worlds are not unloaded: the host's world service owns
     * their lifecycle and saves them on shutdown, and racing it would be the one way to actually lose
     * someone's farm. Rebuilds cut short here are recorded as pending and resume on the next visit.
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
        if (queue != null) {
            queue.shutdown();
            queue = null;
        }
        if (milestoneSaver != null) {
            milestoneSaver.cancel();
            milestoneSaver = null;
        }
        if (milestoneSweeper != null) {
            milestoneSweeper.cancel();
            milestoneSweeper = null;
        }
        if (milestoneStore != null) {
            milestoneStore.saveAll();
            milestoneStore = null;
        }
        if (milestoneListener != null) {
            HandlerList.unregisterAll(milestoneListener);
            milestoneListener = null;
        }
        if (placedBlocks != null) {
            HandlerList.unregisterAll(placedBlocks);
            placedBlocks = null;
        }
        if (visitorTicker != null) {
            visitorTicker.cancel();
            visitorTicker = null;
        }
        if (upgradeHolders != null) {
            upgradeHolders.disable();
            upgradeHolders = null;
        }
        if (upgrades != null) {
            HandlerList.unregisterAll(upgrades);
            upgrades = null;
        }
        if (composterTicker != null) {
            composterTicker.cancel();
            composterTicker = null;
        }
        if (composter != null) {
            composter.store().saveAll();
            HandlerList.unregisterAll(composter);
            composter = null;
        }
        if (composterBlock != null) {
            HandlerList.unregisterAll(composterBlock);
            composterBlock = null;
        }
        if (pestConfiner != null) {
            pestConfiner.cancel();
            pestConfiner = null;
        }
        if (pestHolders != null) {
            pestHolders.disable();
            HandlerList.unregisterAll(pestHolders);
            pestHolders = null;
        }
        if (pests != null) {
            pests.store().saveAll();
            HandlerList.unregisterAll(pests);
            pests = null;
        }
        if (expansion != null) {
            // persist() keeps it across PlaceholderAPI reloads, so it must be removed by hand here or an
            // extension reload would leave the old one answering from a dead service.
            try {
                ((RoyalGardenExpansion) expansion).unregister();
            } catch (LinkageError ignored) {
                // PlaceholderAPI went away first; nothing is left to unregister from
            }
            expansion = null;
        }
        if (visitors != null) {
            visitors.shutdown();
            HandlerList.unregisterAll(visitors);
            visitors = null;
        }
        if (visitorNpcs != null) {
            HandlerList.unregisterAll(visitorNpcs);
            visitorNpcs = null;
        }
        if (sam != null) {
            HandlerList.unregisterAll(sam);
            sam = null;
        }
        if (protection != null) {
            HandlerList.unregisterAll(protection);
            protection = null;
        }
        PluginManager plugins = Bukkit.getPluginManager();
        plugins.removePermission(CommandGarden.PERMISSION);
        plugins.removePermission(CommandDesk.PERMISSION);
        plugins.removePermission(GardenProtection.BYPASS);
        plugins.removePermission(PresetService.BYPASS_COOLDOWN);
        plugins.removePermission(CommandGarden.ADMIN);
        this.config = null;
        this.menu = null;
        this.gardens = null;
        this.islands = null;
        this.worlds = null;
        this.storage = null;
        this.currency = null;
    }

    /** The host's island manager, once enabled. */
    public IslandManager islands() {
        return islands;
    }

    /** The host's world service — how a Garden world is created and unloaded. */
    public IslandWorldService worlds() {
        return worlds;
    }

    /** The host's storage. */
    public Storage storage() {
        return storage;
    }

    /** Garden worlds, once the extension is running. Null when the host had no island services. */
    public GardenWorlds gardens() {
        return gardens;
    }
}
