package com.mystipixel.royalgarden.command;

import com.mystipixel.royalgarden.composter.ComposterMenu;
import com.mystipixel.royalgarden.composter.ComposterService;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.garden.ClearingService;
import com.mystipixel.royalgarden.garden.Farmhouse;
import com.mystipixel.royalgarden.garden.GardenBuilder;
import com.mystipixel.royalgarden.garden.GardenLayout;
import com.mystipixel.royalgarden.garden.GardenState;
import com.mystipixel.royalgarden.garden.GardenWorlds;
import com.mystipixel.royalgarden.garden.PresetLibrary;
import com.mystipixel.royalgarden.level.GardenLevels;
import com.mystipixel.royalgarden.milestone.MilestoneCrop;
import com.mystipixel.royalgarden.milestone.MilestoneMenu;
import com.mystipixel.royalgarden.milestone.MilestoneService;
import com.mystipixel.royalgarden.pest.PestMenu;
import com.mystipixel.royalgarden.pest.PestService;
import com.mystipixel.royalgarden.pest.PestStore;
import com.mystipixel.royalgarden.pest.PestType;
import com.mystipixel.royalgarden.upgrade.UpgradeMenu;
import com.mystipixel.royalgarden.upgrade.UpgradeService;
import com.mystipixel.royalgarden.visitor.Offer;
import com.mystipixel.royalgarden.visitor.VisitorMenu;
import com.mystipixel.royalgarden.visitor.VisitorService;
import com.mystipixel.royalgarden.visitor.VisitorType;
import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.command.impl.PluginCommand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * {@code /garden}: go to your Garden, building it on first visit. With arguments and {@code
 * royalgarden.admin} it is also the admin tool. Registered through eco's command framework because an
 * extension has no {@code plugin.yml}. The name comes from {@code commands.garden}.
 */
public final class CommandGarden extends PluginCommand {

    /**
     * Not blank: Bukkit resolves an unregistered permission (which {@code ""} is) as OP-only, and eco does
     * not special-case it. Registered with {@code default: true} on enable.
     */
    public static final String PERMISSION = "royalgarden.garden";

    /** Lets a player run the admin subcommands. */
    public static final String ADMIN = "royalgarden.admin";

    private static final List<String> VERBS = List.of("capture", "apply", "flat", "bedrock", "default",
            "delete", "presets", "reload", "reset", "unlock", "lock", "clear", "farmhouse", "visit", "info", "milestones", "milestone",
            "xp", "level", "visitors", "visitor", "upgrades", "upgrade", "composter", "pests");

    private final GardenWorlds worlds;
    private final Messages messages;
    private final Runnable reload;
    private final Supplier<Location> evacuateTo;
    private MilestoneService milestones;
    private MilestoneMenu milestoneMenu;
    private GardenLevels levels;
    private VisitorService visitors;
    private VisitorMenu visitorMenu;
    private UpgradeService upgrades;
    private UpgradeMenu upgradeMenu;
    private ComposterService composter;
    private ComposterMenu composterMenu;
    private PestService pests;
    private PestMenu pestMenu;
    private ClearingService clearing;

    public CommandGarden(EcoPlugin plugin, String name, GardenWorlds worlds, Messages messages,
                         Runnable reload, Supplier<Location> evacuateTo) {
        // Not players-only: the console needs the admin tools, since other plugins run rewards through it.
        super(plugin, name, PERMISSION, false);
        this.worlds = worlds;
        this.messages = messages;
        this.reload = reload;
        this.evacuateTo = evacuateTo;
    }

    public void setMilestones(MilestoneService service, MilestoneMenu menu) {
        this.milestones = service;
        this.milestoneMenu = menu;
    }

    public void setLevels(GardenLevels levels) {
        this.levels = levels;
    }

    public void setVisitors(VisitorService service, VisitorMenu menu) {
        this.visitors = service;
        this.visitorMenu = menu;
    }

    public void setUpgrades(UpgradeService service, UpgradeMenu menu) {
        this.upgrades = service;
        this.upgradeMenu = menu;
    }

    public void setComposter(ComposterService service, ComposterMenu menu) {
        this.composter = service;
        this.composterMenu = menu;
    }

    public void setClearing(ClearingService clearing) {
        this.clearing = clearing;
    }

    public void setPests(PestService service, PestMenu menu) {
        this.pests = service;
        this.pestMenu = menu;
    }

    // The console gets only the admin tools that need no player standing anywhere, the ones other plugins
    // call in reward commands.
    @Override
    public void onExecute(CommandSender sender, List<String> args) {
        if (sender instanceof Player) {
            return;
        }
        String verb = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
        String arg = args.size() > 1 ? args.get(1) : null;
        switch (verb) {
            case "xp" -> xp(sender, args);
            case "level" -> showLevel(sender, arg);
            case "milestone" -> setMilestone(sender, args);
            case "visitor" -> visitor(sender, args);
            case "upgrade" -> upgrade(sender, args);
            case "composter" -> composter(sender, args);
            case "pests" -> pests(sender, args);
            case "reset" -> reset(sender, arg, args.size() > 2 && args.get(2).equalsIgnoreCase("world"));
            case "unlock", "lock" -> setLock(sender, verb.equals("unlock"), arg, args.size() > 2 ? args.get(2) : null);
            case "clear" -> clear(sender, arg, args.size() > 2 ? args.get(2) : null);
            case "info" -> info(sender, arg);
            case "reload" -> {
                reload.run();
                messages.send(sender, "admin-reloaded");
            }
            default -> messages.send(sender, "console-help");
        }
    }

    @Override
    public void onExecute(Player player, List<String> args) {
        if (args.isEmpty()) {
            visit(player);
            return;
        }
        // Everyone may see their own Garden level; with a name it is an admin lookup.
        if (args.get(0).equalsIgnoreCase("level") && (args.size() == 1 || !player.hasPermission(ADMIN))) {
            showLevel(player, null);
            return;
        }
        // Every player can see their own milestones; with a name it is an admin lookup.
        if (args.get(0).equalsIgnoreCase("milestones") && milestoneMenu != null
                && (args.size() == 1 || !player.hasPermission(ADMIN))) {
            milestoneMenu.open(player, player.getUniqueId(), player.getName());
            return;
        }
        if (args.get(0).equalsIgnoreCase("upgrades") && upgradeMenu != null) {
            upgradeMenu.open(player);
            return;
        }
        // Their own Composter; with a name it is the admin tool.
        if (args.get(0).equalsIgnoreCase("composter") && composterMenu != null
                && (args.size() == 1 || !player.hasPermission(ADMIN))) {
            composterMenu.open(player);
            return;
        }
        // Their own pests (and the Pest Log); with a name it is the admin tool.
        if (args.get(0).equalsIgnoreCase("pests") && pestMenu != null && (args.size() == 1
                || (args.size() == 2 && args.get(1).equalsIgnoreCase("log")) || !player.hasPermission(ADMIN))) {
            if (args.size() > 1 && args.get(1).equalsIgnoreCase("log")) {
                pestMenu.openLog(player);
            } else {
                pestMenu.open(player);
            }
            return;
        }
        if (args.get(0).equalsIgnoreCase("visitors") && visitorMenu != null) {
            if (args.size() > 1 && args.get(1).equalsIgnoreCase("log")) {
                visitorMenu.openLog(player, 1);
            } else {
                visitorMenu.open(player);
            }
            return;
        }
        admin(player, args);
    }

    @Override
    public List<String> tabComplete(Player player, List<String> args) {
        if (!player.hasPermission(ADMIN)) {
            if (args.size() == 2 && (args.get(0).equalsIgnoreCase("visitors") || args.get(0).equalsIgnoreCase("pests"))) {
                return "log".startsWith(args.get(1).toLowerCase(Locale.ROOT)) ? List.of("log") : List.of();
            }
            if (args.size() > 1) {
                return List.of();
            }
            String typed = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
            List<String> own = new ArrayList<>(List.of("milestones", "level", "visitors", "upgrades", "composter", "pests"));
            own.removeIf(option -> !option.startsWith(typed));
            return own;
        }
        List<String> options = new ArrayList<>();
        if (args.size() <= 1) {
            options.addAll(VERBS);
        } else {
            String verb = args.get(0).toLowerCase(Locale.ROOT);
            if (args.size() == 2) {
                switch (verb) {
                    case "farmhouse" -> options.addAll(List.of("set", "unset"));
                    case "apply", "default", "delete" -> {
                        options.addAll(worlds.presets().ids());
                        if (verb.equals("apply")) {
                            options.add("flat");
                            options.add("bedrock");
                        }
                    }
                    case "reset", "unlock", "lock", "clear", "visit", "info", "milestones", "milestone", "xp", "level",
                         "visitor", "upgrade", "composter", "pests" -> {
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            options.add(online.getName());
                        }
                    }
                    default -> {
                    }
                }
            } else if (args.size() == 4 && verb.equals("visitor") && args.get(2).equalsIgnoreCase("add")
                    && visitors != null) {
                for (VisitorType type : visitors.config().visitors()) {
                    options.add(type.id());
                }
            } else if (args.size() == 4 && verb.equals("pests") && args.get(2).equalsIgnoreCase("spawn")
                    && pests != null) {
                for (PestType type : pests.config().pests()) {
                    options.add(type.id());
                }
            } else if (args.size() == 5 && verb.equals("pests") && args.get(2).equalsIgnoreCase("spawn")) {
                for (int pz = 0; pz < GardenLayout.grid(); pz++) {
                    for (int px = 0; px < GardenLayout.grid(); px++) {
                        options.add(GardenLayout.plotName(px, pz));
                    }
                }
            } else if (args.size() == 3) {
                if (verb.equals("unlock") || verb.equals("lock") || verb.equals("clear")) {
                    for (int pz = 0; pz < GardenLayout.grid(); pz++) {
                        for (int px = 0; px < GardenLayout.grid(); px++) {
                            options.add(GardenLayout.plotName(px, pz));
                        }
                    }
                } else if (verb.equals("reset")) {
                    options.add("world");
                } else if (verb.equals("farmhouse")) {
                    options.addAll(Farmhouse.SPOTS);
                } else if (verb.equals("xp")) {
                    options.addAll(List.of("give", "take", "set"));
                } else if (verb.equals("visitor")) {
                    options.addAll(List.of("add", "clear", "stats", "reset-stats"));
                } else if (verb.equals("composter")) {
                    options.addAll(List.of("give-matter", "give-fuel", "collect", "reset"));
                } else if (verb.equals("pests")) {
                    options.addAll(List.of("spawn", "clear", "stats", "reset-stats"));
                } else if (verb.equals("upgrade") && upgrades != null) {
                    for (MilestoneCrop crop : upgrades.crops()) {
                        options.add(crop.id());
                    }
                    options.add("reset");
                } else if (verb.equals("milestone") && milestones != null) {
                    for (MilestoneCrop crop : milestones.config().crops()) {
                        options.add(crop.id());
                    }
                    options.add("reset");
                }
            }
        }
        String typed = args.isEmpty() ? "" : args.get(args.size() - 1).toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }

    // Arguments on /garden rather than their own commands, so one node gates all of them.
    private void admin(Player player, List<String> args) {
        if (!player.hasPermission(ADMIN)) {
            messages.send(player, "no-permission");
            return;
        }
        String verb = args.get(0).toLowerCase(Locale.ROOT);
        String arg = args.size() > 1 ? args.get(1) : null;
        PresetLibrary presets = worlds.presets();

        switch (verb) {
            case "capture" -> inGarden(player, world -> capture(player, world, lower(arg)));
            case "apply" -> {
                if (arg == null) {
                    // Deliberately not "flat": a bare /garden apply must never flatten the plot you stand on.
                    messages.send(player, "admin-apply-usage");
                    return;
                }
                inGarden(player, world -> apply(player, world, lower(arg)));
            }
            case "flat" -> inGarden(player, world -> apply(player, world, "flat"));
            case "bedrock" -> inGarden(player, world -> apply(player, world, "bedrock"));
            case "default" -> {
                String id = lower(arg);
                if (id != null && !presets.exists(id)) {
                    messages.send(player, "admin-no-preset", "%preset%", id);
                    return;
                }
                presets.setDefault(id);
                messages.send(player, id == null ? "admin-default-cleared" : "admin-default-set", "%preset%", id);
            }
            case "delete" -> messages.send(player, presets.delete(lower(arg)) ? "admin-deleted" : "admin-no-preset",
                    "%preset%", arg);
            case "presets" -> list(player, presets);
            case "reload" -> {
                reload.run();
                messages.send(player, "admin-reloaded");
            }
            case "reset" -> reset(player, arg, args.size() > 2 && args.get(2).equalsIgnoreCase("world"));
            case "unlock", "lock" -> setLock(player, verb.equals("unlock"), arg, args.size() > 2 ? args.get(2) : null);
            case "clear" -> clear(player, arg, args.size() > 2 ? args.get(2) : null);
            case "farmhouse" -> farmhouse(player, args);
            case "visit" -> adminVisit(player, arg);
            case "info" -> info(player, arg);
            case "milestones" -> viewMilestones(player, arg);
            case "milestone" -> setMilestone(player, args);
            case "xp" -> xp(player, args);
            case "level" -> showLevel(player, arg);
            case "visitor" -> visitor(player, args);
            case "upgrade" -> upgrade(player, args);
            case "composter" -> composter(player, args);
            case "pests" -> pests(player, args);
            default -> messages.send(player, "admin-help");
        }
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private void inGarden(Player player, java.util.function.Consumer<World> action) {
        World world = player.getWorld();
        if (!GardenWorlds.isGarden(world)) {
            messages.send(player, "admin-stand-in-garden");
            return;
        }
        action.accept(world);
    }

    private void list(Player player, PresetLibrary presets) {
        List<String> ids = presets.ids();
        if (ids.isEmpty()) {
            messages.send(player, "admin-presets-empty");
            return;
        }
        messages.send(player, "admin-presets-header");
        for (String id : ids) {
            PresetLibrary.Entry entry = presets.entry(id);
            String flags = (id.equals(presets.defaultId()) ? messages.raw("admin-presets-default") : "")
                    + (entry != null && entry.selectable() ? messages.raw("admin-presets-selectable") : "");
            messages.send(player, "admin-presets-line", "%preset%", id, "%flags%", flags);
        }
    }

    private void capture(Player player, World world, String name) {
        if (name == null || !PresetLibrary.validId(name)) {
            messages.send(player, "admin-capture-usage");
            return;
        }
        int px = GardenLayout.plotAt(player.getLocation().getBlockX());
        int pz = GardenLayout.plotAt(player.getLocation().getBlockZ());
        if (!GardenLayout.inGrid(px, pz)) {
            messages.send(player, "admin-stand-on-plot");
            return;
        }
        if (GardenLayout.isCentre(px, pz)) {
            // The farmhouse plot is never stamped, so capturing it could only ever be a mistake.
            messages.send(player, "admin-capture-farmhouse");
            return;
        }
        if (worlds.isBuilding(world, px, pz)) {
            messages.send(player, "plot-busy", "%plot%", GardenLayout.plotName(px, pz));
            return;
        }
        try {
            int layers = worlds.capture(world, px, pz, name).height();
            messages.send(player, "admin-captured", "%plot%", GardenLayout.plotName(px, pz),
                    "%preset%", name, "%layers%", String.valueOf(layers));
        } catch (IOException failed) {
            messages.send(player, "admin-capture-failed", "%error%", failed.getMessage());
            getPlugin().getLogger().severe("Preset capture failed: " + failed);
        }
    }

    private void apply(Player player, World world, String name) {
        int px = GardenLayout.plotAt(player.getLocation().getBlockX());
        int pz = GardenLayout.plotAt(player.getLocation().getBlockZ());
        String plot = GardenLayout.plotName(px, pz);
        if (!GardenLayout.inGrid(px, pz)) {
            messages.send(player, "admin-stand-on-plot");
            return;
        }
        if (GardenLayout.isCentre(px, pz)) {
            messages.send(player, "admin-apply-farmhouse");
            return;
        }
        if (!worlds.presets().applicable(name)) {
            messages.send(player, "admin-no-preset", "%preset%", name);
            return;
        }
        if (!worlds.rebuild(world, px, pz, name, true)) {
            messages.send(player, "plot-busy", "%plot%", plot);
            return;
        }
        messages.send(player, "admin-applying", "%preset%", name, "%plot%", plot);
    }

    // Null (with a message) if there is none.
    private OfflinePlayer target(CommandSender sender, String name) {
        if (name == null) {
            messages.send(sender, "admin-help");
            return null;
        }
        OfflinePlayer found = Bukkit.getPlayerExact(name);
        if (found == null) {
            found = Bukkit.getOfflinePlayerIfCached(name);
        }
        if (found == null) {
            messages.send(sender, "admin-unknown-player", "%player%", name);
        }
        return found;
    }

    private void reset(CommandSender sender, String name, boolean wipeWorld) {
        OfflinePlayer target = target(sender, name);
        if (target == null) {
            return;
        }
        String who = target.getName() == null ? name : target.getName();
        if (visitors != null) {
            visitors.clear(target.getUniqueId());       // a reset Garden has nobody waiting at it
        }
        if (pests != null) {
            pests.clear(target.getUniqueId());
            if (!target.isOnline()) {
                pests.store().unload(target.getUniqueId());
            }
        }
        worlds.reset(target.getUniqueId(), wipeWorld, evacuateTo.get()).whenComplete((ignored, error) -> {
            if (error != null) {
                getPlugin().getLogger().log(Level.WARNING, "Resetting the Garden of " + who + " failed", error);
                messages.send(sender, "admin-reset-failed", "%player%", who, "%error%", String.valueOf(error.getMessage()));
                return;
            }
            messages.send(sender, wipeWorld ? "admin-reset-world" : "admin-reset", "%player%", who);
        });
    }

    private void setLock(CommandSender sender, boolean unlock, String name, String plotName) {
        OfflinePlayer target = target(sender, name);
        if (target == null) {
            return;
        }
        int[] plot = GardenLayout.parsePlot(plotName);
        if (plot == null) {
            messages.send(sender, "admin-bad-plot", "%plot%", String.valueOf(plotName));
            return;
        }
        GardenState state = worlds.state(target.getUniqueId());
        if (state.readOnly()) {
            messages.send(sender, "garden-unreadable");
            return;
        }
        boolean changed = unlock ? state.forceUnlock(plot[0], plot[1]) : state.lock(plot[0], plot[1]);
        if (changed && unlock) {
            state.beginClearing(plot[0], plot[1]);     // bought or granted, an overgrown plot needs clearing
        }
        if (changed) {
            worlds.save(state);
        }
        String who = target.getName() == null ? name : target.getName();
        messages.send(sender, changed ? (unlock ? "admin-unlocked" : "admin-locked") : "admin-unchanged",
                "%player%", who, "%plot%", GardenLayout.plotName(plot[0], plot[1]));
    }

    // /garden farmhouse set|unset <spot>, or no arguments to list. A spot is taken from where the admin
    // stands and faces; the Composter's from the block they are looking at.
    private void farmhouse(Player player, List<String> args) {
        String action = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "";
        String spot = args.size() > 2 ? args.get(2).toLowerCase(Locale.ROOT) : "";
        if (action.isEmpty()) {
            Farmhouse farmhouse = Farmhouse.current();
            List<String> lines = new ArrayList<>();
            for (String name : Farmhouse.SPOTS) {
                Farmhouse.Anchor at = farmhouse.anchor(name);
                String source = Farmhouse.setSpots().containsKey(name) ? "set" : at != null ? "sign" : "default";
                lines.add(name + " (" + source + (at == null ? "" : String.format(Locale.ROOT, " %.1f %.1f %.1f",
                        at.x(), at.y(), at.z())) + ")");
            }
            messages.send(player, "admin-farmhouse-info", "%farmhouse%",
                    farmhouse.isSchematic() ? farmhouse.file() : "built-in barn", "%spots%", String.join(", ", lines));
            return;
        }
        if (!(action.equals("set") || action.equals("unset")) || !Farmhouse.SPOTS.contains(spot)) {
            messages.send(player, "admin-farmhouse-usage");
            return;
        }
        if (!GardenWorlds.isGarden(player.getWorld())) {
            messages.send(player, "admin-stand-in-garden");
            return;
        }
        Farmhouse.Anchor anchor = null;
        if (action.equals("set")) {
            if (spot.equals("composter")) {
                org.bukkit.block.Block target = player.getTargetBlockExact(6);
                if (target == null) {
                    messages.send(player, "admin-farmhouse-look");
                    return;
                }
                anchor = new Farmhouse.Anchor(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0f);
            } else {
                Location at = player.getLocation();
                float yaw = Math.round(at.getYaw() / 45f) * 45f;     // squared up, so a line runs straight
                anchor = new Farmhouse.Anchor(at.getBlockX() + 0.5, at.getBlockY(), at.getBlockZ() + 0.5, yaw);
            }
        }
        try {
            Farmhouse.setSpot(spot, anchor);
        } catch (java.io.IOException failed) {
            getPlugin().getLogger().log(Level.WARNING, "Could not save farmhouse-spots.yml", failed);
            messages.send(player, "admin-farmhouse-failed");
            return;
        }
        worlds.refreshSpots();
        messages.send(player, anchor == null ? "admin-farmhouse-unset" : "admin-farmhouse-set", "%spot%", spot);
    }

    // Marks a plot cleared, whatever debris is left on it.
    private void clear(CommandSender sender, String name, String plotName) {
        OfflinePlayer target = target(sender, name);
        if (target == null) {
            return;
        }
        int[] plot = GardenLayout.parsePlot(plotName);
        if (plot == null) {
            messages.send(sender, "admin-bad-plot", "%plot%", String.valueOf(plotName));
            return;
        }
        GardenState state = worlds.state(target.getUniqueId());
        if (state.readOnly()) {
            messages.send(sender, "garden-unreadable");
            return;
        }
        String who = target.getName() == null ? name : target.getName();
        String plotLabel = GardenLayout.plotName(plot[0], plot[1]);
        if (clearing == null || !state.isClearing(plot[0], plot[1])) {
            messages.send(sender, "admin-not-clearing", "%player%", who, "%plot%", plotLabel);
            return;
        }
        clearing.finish(state, plot[0], plot[1], Bukkit.getPlayer(target.getUniqueId()));
        messages.send(sender, "admin-cleared", "%player%", who, "%plot%", plotLabel);
    }

    private void adminVisit(Player sender, String name) {
        OfflinePlayer target = target(sender, name);
        if (target == null) {
            return;
        }
        UUID owner = target.getUniqueId();
        if (!worlds.hasGarden(owner)) {
            messages.send(sender, "admin-no-garden", "%player%", String.valueOf(target.getName()));
            return;
        }
        worlds.open(owner).whenComplete((world, error) -> {
            if (error != null || world == null) {
                messages.send(sender, "garden-open-failed");
                return;
            }
            sender.teleportAsync(GardenBuilder.spawn(world));
        });
    }

    private void info(CommandSender sender, String name) {
        OfflinePlayer target = target(sender, name);
        if (target == null) {
            return;
        }
        GardenState state = worlds.state(target.getUniqueId());
        List<String> plots = new ArrayList<>();
        for (int[] plot : state.plots()) {
            plots.add(GardenLayout.plotName(plot[0], plot[1]));
        }
        messages.send(sender, "admin-info", "%player%", String.valueOf(target.getName()),
                "%unlocked%", String.valueOf(state.unlockedCount()),
                "%total%", String.valueOf(GardenLayout.plotCount()),
                "%plots%", String.join(", ", plots),
                "%loaded%", String.valueOf(worlds.isLoaded(target.getUniqueId())),
                "%readonly%", String.valueOf(state.readOnly()),
                "%level%", levels == null ? "-" : String.valueOf(levels.level(target.getUniqueId())),
                "%xp%", String.valueOf(state.xp()));
    }

    private void showLevel(CommandSender sender, String name) {
        if (levels == null) {
            return;
        }
        UUID owner;
        String who;
        if (name == null) {
            if (!(sender instanceof Player player)) {
                messages.send(sender, "console-help");
                return;
            }
            owner = player.getUniqueId();
            who = player.getName();
        } else {
            OfflinePlayer target = target(sender, name);
            if (target == null) {
                return;
            }
            owner = target.getUniqueId();
            who = target.getName() == null ? name : target.getName();
        }
        String[] placeholders = levels.placeholders(owner);
        String[] all = new String[placeholders.length + 2];
        System.arraycopy(placeholders, 0, all, 0, placeholders.length);
        all[placeholders.length] = "%player%";
        all[placeholders.length + 1] = who;
        messages.send(sender, "level-info", all);
    }

    // /garden xp <player> <give|take|set> <amount> [silent]. give pays levels like earned XP (how other
    // plugins award Garden XP); take and set are corrections and pay nothing.
    private void xp(CommandSender sender, List<String> args) {
        if (levels == null || args.size() < 4) {
            messages.send(sender, "admin-xp-usage");
            return;
        }
        OfflinePlayer target = target(sender, args.get(1));
        if (target == null) {
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args.get(3).replace(",", "").replace("_", ""));
        } catch (NumberFormatException notANumber) {
            messages.send(sender, "admin-xp-usage");
            return;
        }
        UUID owner = target.getUniqueId();
        boolean silent = args.size() > 4 && args.get(4).equalsIgnoreCase("silent");
        switch (args.get(2).toLowerCase(Locale.ROOT)) {
            case "give" -> levels.give(owner, amount, silent ? null : "level-source-command");
            case "take" -> levels.set(owner, levels.xp(owner) - amount);
            case "set" -> levels.set(owner, amount);
            default -> {
                messages.send(sender, "admin-xp-usage");
                return;
            }
        }
        String who = target.getName() == null ? args.get(1) : target.getName();
        String[] placeholders = levels.placeholders(owner);
        String[] all = new String[placeholders.length + 2];
        System.arraycopy(placeholders, 0, all, 0, placeholders.length);
        all[placeholders.length] = "%player%";
        all[placeholders.length + 1] = who;
        messages.send(sender, "admin-xp-done", all);
    }

    // /garden visitor <player> add [visitor] | clear | stats | reset-stats. add ignores the clock, queue
    // limit and level requirements.
    private void visitor(CommandSender sender, List<String> args) {
        if (visitors == null || args.size() < 3) {
            messages.send(sender, "admin-visitor-usage");
            return;
        }
        OfflinePlayer target = target(sender, args.get(1));
        if (target == null) {
            return;
        }
        UUID owner = target.getUniqueId();
        String who = target.getName() == null ? args.get(1) : target.getName();
        switch (args.get(2).toLowerCase(Locale.ROOT)) {
            case "add" -> {
                Offer offer = visitors.summon(target, args.size() > 3 ? args.get(3) : null);
                if (offer == null) {
                    messages.send(sender, "admin-visitor-none");
                } else {
                    messages.send(sender, "admin-visitor-added", withPlayer(visitors.placeholders(owner, offer), who));
                }
            }
            case "clear" -> {
                visitors.clear(owner);
                messages.send(sender, "admin-visitor-cleared", "%player%", who);
            }
            case "stats" -> messages.send(sender, "admin-visitor-stats",
                    withPlayer(visitors.statPlaceholders(owner), who));
            case "reset-stats" -> {
                if (!visitors.store().readOnly(owner)) {
                    visitors.store().get(owner).resetStats();
                    visitors.store().save(owner);
                }
                messages.send(sender, "admin-visitor-reset", "%player%", who);
            }
            default -> messages.send(sender, "admin-visitor-usage");
        }
        if (!target.isOnline()) {
            visitors.store().unload(owner);              // looked up for this command only
        }
    }

    // /garden upgrade <player> <crop> <level>, or <player> reset. Corrections: nothing is charged or paid.
    private void upgrade(CommandSender sender, List<String> args) {
        if (upgrades == null || args.size() < 3) {
            messages.send(sender, "admin-upgrade-usage");
            return;
        }
        OfflinePlayer target = target(sender, args.get(1));
        if (target == null) {
            return;
        }
        UUID owner = target.getUniqueId();
        String who = target.getName() == null ? args.get(1) : target.getName();
        if (args.get(2).equalsIgnoreCase("reset")) {
            for (MilestoneCrop crop : upgrades.crops()) {
                upgrades.set(owner, crop, 0);
            }
            messages.send(sender, "admin-upgrade-reset", "%player%", who);
        } else {
            MilestoneCrop crop = upgrades.crop(args.get(2));
            int level;
            try {
                level = Integer.parseInt(args.size() > 3 ? args.get(3) : "");
            } catch (NumberFormatException notANumber) {
                level = -1;
            }
            if (crop == null || level < 0) {
                messages.send(sender, "admin-upgrade-usage");
                return;
            }
            if (!upgrades.set(owner, crop, level)) {
                messages.send(sender, "upgrade-unreadable");
                return;
            }
            messages.send(sender, "admin-upgrade-set", withPlayer(upgrades.placeholders(owner, crop), who));
        }
        if (!target.isOnline()) {
            upgrades.store().unload(owner);              // looked up for this command only
        }
    }

    // /garden composter <player> give-matter|give-fuel <amount> | collect | reset. collect needs the player
    // online. Nothing is charged.
    private void composter(CommandSender sender, List<String> args) {
        if (composter == null || args.size() < 3) {
            messages.send(sender, "admin-composter-usage");
            return;
        }
        OfflinePlayer target = target(sender, args.get(1));
        if (target == null) {
            return;
        }
        UUID owner = target.getUniqueId();
        String who = target.getName() == null ? args.get(1) : target.getName();
        String action = args.get(2).toLowerCase(Locale.ROOT);
        switch (action) {
            case "give-matter", "give-fuel" -> {
                double amount;
                try {
                    amount = Double.parseDouble(args.size() > 3 ? args.get(3).replace(",", "").replace("_", "") : "");
                } catch (NumberFormatException notANumber) {
                    messages.send(sender, "admin-composter-usage");
                    return;
                }
                ComposterService.Kind kind = action.equals("give-matter")
                        ? ComposterService.Kind.MATTER : ComposterService.Kind.FUEL;
                double added = composter.give(owner, kind, amount);
                if (added < 0) {
                    messages.send(sender, "composter-unreadable");
                } else {
                    messages.send(sender, "admin-composter-given", withPlayer(composter.placeholders(owner), who));
                }
            }
            case "collect" -> {
                Player online = target.getPlayer();
                if (online == null) {
                    messages.send(sender, "admin-composter-offline", "%player%", who);
                    return;
                }
                if (composter.collect(online)) {
                    messages.send(sender, "admin-composter-collected", "%player%", who);
                }
            }
            case "reset" -> {
                composter.reset(owner);
                messages.send(sender, "admin-composter-reset", "%player%", who);
            }
            default -> messages.send(sender, "admin-composter-usage");
        }
        if (!target.isOnline()) {
            composter.store().unload(owner);             // looked up for this command only
        }
    }

    // /garden pests <player> spawn [pest] [plot] | clear | stats | reset-stats. spawn ignores cooldown and
    // chance but not the caps, and needs the Garden loaded. Nothing pays.
    private void pests(CommandSender sender, List<String> args) {
        if (pests == null || args.size() < 3) {
            messages.send(sender, "admin-pests-usage");
            return;
        }
        OfflinePlayer target = target(sender, args.get(1));
        if (target == null) {
            return;
        }
        UUID owner = target.getUniqueId();
        String who = target.getName() == null ? args.get(1) : target.getName();
        switch (args.get(2).toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                PestType type = null;
                if (args.size() > 3 && !args.get(3).equalsIgnoreCase("random")) {
                    type = pests.config().pest(args.get(3));
                    if (type == null) {
                        messages.send(sender, "admin-pests-no-such-pest", "%pest%", args.get(3));
                        return;
                    }
                }
                int plot = -1;
                if (args.size() > 4) {
                    int[] parsed = GardenLayout.parsePlot(args.get(4));
                    if (parsed == null) {
                        messages.send(sender, "admin-bad-plot", "%plot%", args.get(4));
                        return;
                    }
                    plot = PestStore.pack(parsed[0], parsed[1]);
                }
                World world = Bukkit.getWorld(GardenWorlds.worldName(owner));
                PestService.Outcome outcome = world == null ? PestService.Outcome.NOT_LOADED
                        : pests.spawn(world, type, plot, null, java.util.concurrent.ThreadLocalRandom.current());
                messages.send(sender, "admin-pests-spawn-" + outcome.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                        "%player%", who, "%plot%", plot < 0 ? "" : PestStore.plotName(plot));
            }
            case "clear" -> {
                int removed = pests.clear(owner);
                messages.send(sender, removed < 0 ? "pests-unreadable" : "admin-pests-cleared",
                        "%player%", who, "%amount%", String.valueOf(Math.max(0, removed)));
            }
            case "stats" -> messages.send(sender, "admin-pests-stats", withPlayer(pests.statPlaceholders(owner), who));
            case "reset-stats" -> {
                if (!pests.store().readOnly(owner)) {
                    pests.store().get(owner).resetStats();
                    pests.store().save(owner);
                }
                messages.send(sender, "admin-pests-reset", "%player%", who);
            }
            default -> messages.send(sender, "admin-pests-usage");
        }
        if (!target.isOnline()) {
            pests.store().unload(owner);                 // looked up for this command only
        }
    }

    private static String[] withPlayer(String[] placeholders, String who) {
        String[] all = new String[placeholders.length + 2];
        System.arraycopy(placeholders, 0, all, 0, placeholders.length);
        all[placeholders.length] = "%player%";
        all[placeholders.length + 1] = who;
        return all;
    }

    private void viewMilestones(Player sender, String name) {
        if (milestoneMenu == null) {
            return;
        }
        OfflinePlayer target = target(sender, name);
        if (target != null) {
            milestoneMenu.open(sender, target.getUniqueId(), target.getName() == null ? name : target.getName());
        }
    }

    // /garden milestone <player> <crop> <amount>, or <player> reset. Corrections: no rewards are paid.
    private void setMilestone(CommandSender sender, List<String> args) {
        if (milestones == null || args.size() < 3) {
            messages.send(sender, "admin-milestone-usage");
            return;
        }
        OfflinePlayer target = target(sender, args.get(1));
        if (target == null) {
            return;
        }
        String who = target.getName() == null ? args.get(1) : target.getName();
        if (args.get(2).equalsIgnoreCase("reset")) {
            milestones.store().reset(target.getUniqueId());
            milestones.store().save(target.getUniqueId());
            messages.send(sender, "admin-milestone-reset", "%player%", who);
            return;
        }
        MilestoneCrop crop = milestones.config().crop(args.get(2));
        if (crop == null || args.size() < 4) {
            messages.send(sender, "admin-milestone-usage");
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args.get(3).replace(",", "").replace("_", ""));
        } catch (NumberFormatException notANumber) {
            messages.send(sender, "admin-milestone-usage");
            return;
        }
        milestones.store().set(target.getUniqueId(), crop.id(), amount);
        milestones.store().save(target.getUniqueId());
        messages.send(sender, "admin-milestone-set", "%player%", who, "%crop_name%", crop.displayName(),
                "%total%", String.format("%,d", Math.max(0, amount)),
                "%tier%", String.valueOf(crop.tierOf(Math.max(0, amount))));
    }

    private void visit(Player player) {
        if (!worlds.isLoaded(player.getUniqueId())) {
            messages.send(player, "garden-opening");
        }
        worlds.open(player.getUniqueId()).whenComplete((world, error) -> {
            if (error != null) {
                // The future can fail on either thread; log the whole chain, not just the message.
                getPlugin().getLogger().log(Level.SEVERE, "Failed to open Garden for " + player.getName(), error);
                messages.send(player, "garden-open-failed");
                return;
            }
            // open() completes on the main thread with the world prepared (Sam included), so the player can be
            // moved straight in.
            player.teleportAsync(GardenBuilder.spawn(world)).thenAccept(moved -> {
                if (moved) {
                    messages.send(player, "garden-welcome");
                }
            });
        });
    }
}
