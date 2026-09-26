package com.mystipixel.royalgarden.command;

import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.garden.GardenBuilder;
import com.mystipixel.royalgarden.garden.GardenLayout;
import com.mystipixel.royalgarden.garden.GardenState;
import com.mystipixel.royalgarden.garden.GardenWorlds;
import com.mystipixel.royalgarden.garden.PresetLibrary;
import com.mystipixel.royalgarden.level.GardenLevels;
import com.mystipixel.royalgarden.milestone.MilestoneCrop;
import com.mystipixel.royalgarden.milestone.MilestoneMenu;
import com.mystipixel.royalgarden.milestone.MilestoneService;
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
 * {@code /garden} — go to your Garden, building it if you have never been.
 *
 * <p>With arguments, and {@code royalgarden.admin}, it is also the admin tool: capturing and applying
 * presets, and managing other players' Gardens — which is most of what running a contest needs.
 *
 * <p>Registered through eco's command framework rather than a {@code plugin.yml} entry, because an
 * extension has no {@code plugin.yml} of its own to put one in. eco registers into the live command
 * map, which is what makes commands possible from an extension at all. The name comes from
 * {@code commands.garden} in the config.
 */
public final class CommandGarden extends PluginCommand {

    /**
     * The node this command needs.
     *
     * <p>Not the empty string. eco checks {@code sender.hasPermission(getPermission())} with no
     * special case for a blank one, and Bukkit resolves an <em>unregistered</em> permission — which
     * {@code ""} is — through {@code PermissionDefault.OP}. An empty permission therefore reads as
     * "operators only", silently, which is the exact opposite of what it looks like it means. The node
     * is registered with {@code default: true} on enable, matching the host's own player-facing nodes.
     */
    public static final String PERMISSION = "royalgarden.garden";

    /** Lets a player run the admin subcommands. */
    public static final String ADMIN = "royalgarden.admin";

    private static final List<String> VERBS = List.of("capture", "apply", "flat", "bedrock", "default",
            "delete", "presets", "reload", "reset", "unlock", "lock", "visit", "info", "milestones", "milestone",
            "xp", "level");

    private final GardenWorlds worlds;
    private final Messages messages;
    private final Runnable reload;
    private final Supplier<Location> evacuateTo;
    private MilestoneService milestones;
    private MilestoneMenu milestoneMenu;
    private GardenLevels levels;

    public CommandGarden(EcoPlugin plugin, String name, GardenWorlds worlds, Messages messages,
                         Runnable reload, Supplier<Location> evacuateTo) {
        // Not players-only: the console needs the admin tools, since other plugins run rewards through it.
        super(plugin, name, PERMISSION, false);
        this.worlds = worlds;
        this.messages = messages;
        this.reload = reload;
        this.evacuateTo = evacuateTo;
    }

    /** Give the command its Crop Milestones subcommands. */
    public void setMilestones(MilestoneService service, MilestoneMenu menu) {
        this.milestones = service;
        this.milestoneMenu = menu;
    }

    /** Give the command its Garden Level subcommands. */
    public void setLevels(GardenLevels levels) {
        this.levels = levels;
    }

    /**
     * The console's way in. Players are handled by {@link #onExecute(Player, List)}; from the console
     * only the admin tools that need no player to stand anywhere are available — the ones other plugins
     * call in their reward commands.
     */
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
            case "reset" -> reset(sender, arg, args.size() > 2 && args.get(2).equalsIgnoreCase("world"));
            case "unlock", "lock" -> setLock(sender, verb.equals("unlock"), arg, args.size() > 2 ? args.get(2) : null);
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
        // The one subcommand every player has: their own milestones. With a name it is an admin lookup.
        if (args.get(0).equalsIgnoreCase("milestones") && milestoneMenu != null
                && (args.size() == 1 || !player.hasPermission(ADMIN))) {
            milestoneMenu.open(player, player.getUniqueId(), player.getName());
            return;
        }
        admin(player, args);
    }

    @Override
    public List<String> tabComplete(Player player, List<String> args) {
        if (!player.hasPermission(ADMIN)) {
            if (args.size() > 1) {
                return List.of();
            }
            String typed = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
            List<String> own = new ArrayList<>(List.of("milestones", "level"));
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
                    case "apply", "default", "delete" -> {
                        options.addAll(worlds.presets().ids());
                        if (verb.equals("apply")) {
                            options.add("flat");
                            options.add("bedrock");
                        }
                    }
                    case "reset", "unlock", "lock", "visit", "info", "milestones", "milestone", "xp", "level" -> {
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            options.add(online.getName());
                        }
                    }
                    default -> {
                    }
                }
            } else if (args.size() == 3) {
                if (verb.equals("unlock") || verb.equals("lock")) {
                    for (int pz = 0; pz < GardenLayout.grid(); pz++) {
                        for (int px = 0; px < GardenLayout.grid(); px++) {
                            options.add(GardenLayout.plotName(px, pz));
                        }
                    }
                } else if (verb.equals("reset")) {
                    options.add("world");
                } else if (verb.equals("xp")) {
                    options.addAll(List.of("give", "take", "set"));
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

    // ── admin ──────────────────────────────────────────────────────────────────

    /**
     * The admin subcommands. Kept as arguments on {@code /garden} rather than commands of their own —
     * they do not need their own names in the command map, and one node gates all of them.
     */
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
                    // Deliberately not "flat": a bare /garden apply used to flatten the plot you stood on.
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
            case "visit" -> adminVisit(player, arg);
            case "info" -> info(player, arg);
            case "milestones" -> viewMilestones(player, arg);
            case "milestone" -> setMilestone(player, args);
            case "xp" -> xp(player, args);
            case "level" -> showLevel(player, arg);
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

    /** Capture the plot the player is standing on under a name. */
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

    /** Stamp a preset onto the plot the player is standing on, or flatten it. */
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

    /** A player by name, online or known to the server; null (with a message) if there is none. */
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
        if (changed) {
            worlds.save(state);
        }
        String who = target.getName() == null ? name : target.getName();
        messages.send(sender, changed ? (unlock ? "admin-unlocked" : "admin-locked") : "admin-unchanged",
                "%player%", who, "%plot%", GardenLayout.plotName(plot[0], plot[1]));
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

    /** Show a Garden's level: the sender's own, or a named player's. */
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

    /**
     * {@code /garden xp <player> <give|take|set> <amount> [silent]}. {@code give} pays any levels it
     * reaches, exactly as earned XP does — it is how other plugins award Garden XP. {@code take} and
     * {@code set} are corrections and pay nothing.
     */
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

    private void viewMilestones(Player sender, String name) {
        if (milestoneMenu == null) {
            return;
        }
        OfflinePlayer target = target(sender, name);
        if (target != null) {
            milestoneMenu.open(sender, target.getUniqueId(), target.getName() == null ? name : target.getName());
        }
    }

    /**
     * {@code /garden milestone <player> <crop> <amount>} sets a total; {@code /garden milestone <player>
     * reset} clears them all. Neither pays rewards — they are corrections, not harvests.
     */
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

    // ── going to your Garden ───────────────────────────────────────────────────

    private void visit(Player player) {
        if (!worlds.isLoaded(player.getUniqueId())) {
            messages.send(player, "garden-opening");
        }
        worlds.open(player.getUniqueId()).whenComplete((world, error) -> {
            if (error != null) {
                // The future can fail on either thread; log the whole chain — the message alone is
                // usually the least useful part.
                getPlugin().getLogger().log(Level.SEVERE, "Failed to open Garden for " + player.getName(), error);
                messages.send(player, "garden-open-failed");
                return;
            }
            // open() completes on the server thread with the world prepared (Sam included), so the
            // player can be moved straight in.
            player.teleportAsync(GardenBuilder.spawn(world)).thenAccept(moved -> {
                if (moved) {
                    messages.send(player, "garden-welcome");
                }
            });
        });
    }
}
