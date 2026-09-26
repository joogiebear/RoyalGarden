package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.milestone.Tiers;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Clearing a bought plot of its {@link Overgrowth} before it can be built on.
 *
 * <p>A plot is bought with a count of the debris on it. Every piece that goes — broken by the owner,
 * decayed once its tree is felled, burnt, or washed away — adds to how much has been cleared, and once
 * that reaches {@code clearing.required-percent} the plot is finished: its record is dropped, and from
 * then on it is ordinary ground to build on. What is left of the debris by then is just blocks.
 *
 * <h2>What counts</h2>
 *
 * <p>Any block above the ground, of a material the debris could have been made of, in a plot still being
 * cleared. That can be judged from the block alone because nothing else can be there: a plot being
 * cleared refuses every placement (see {@link GardenProtection}), so whatever of those materials stands
 * above its ground is debris.
 *
 * <h2>Keeping the count honest</h2>
 *
 * <p>The count is kept by events, and events can be missed — a crash between two saves, a world restored
 * from an older save, an admin tool that sets blocks without any. So the first time a plot's debris is
 * touched after its world loads, the plot is <b>recounted</b> from the world itself. A player is never
 * stuck at 89% with nothing left to break, and never handed progress for debris that came back.
 *
 * <p>Progress is saved every few seconds rather than on every block — clearing a plot is thousands of
 * breaks — and always on finishing a plot, on the world unloading, and on shutdown.
 */
public final class ClearingService implements Listener {

    /** Seconds between saves of clearing progress. */
    private static final long SAVE_TICKS = 10 * 20L;

    private final Plugin plugin;
    private final GardenConfig config;
    private final Messages messages;
    private final GardenWorlds worlds;
    private final Set<UUID> dirty = new HashSet<>();

    /** Plots recounted since their world loaded, as {@code world name + "/" + plot name}. */
    private final Set<String> verified = new HashSet<>();
    private BukkitTask saver;
    private Overgrowth.Settings materialsFor;
    private Set<Material> materials = Set.of();

    public ClearingService(Plugin plugin, GardenConfig config, Messages messages, GardenWorlds worlds) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.worlds = worlds;
    }

    public void start() {
        if (saver == null) {
            saver = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, SAVE_TICKS, SAVE_TICKS);
        }
    }

    /** Stop saving on a timer, saving what is outstanding. */
    public void shutdown() {
        if (saver != null) {
            saver.cancel();
            saver = null;
        }
        flush();
    }

    private void flush() {
        for (UUID owner : dirty) {
            worlds.save(worlds.state(owner));
        }
        dirty.clear();
    }

    // ── the rule ───────────────────────────────────────────────────────────────

    public int required() {
        return config.clearing().required();
    }

    /**
     * Whether a plot is bought but not yet clear enough to build on.
     *
     * <p>Judged against the threshold as it is now, so lowering {@code required-percent} frees plots that
     * already meet the new figure without anyone having to break another block.
     */
    public boolean blocks(GardenState state, int px, int pz) {
        return config.clearing().enabled() && state != null && state.isClearing(px, pz)
                && state.clearingPercent(px, pz) < required();
    }

    /** Whether a block could be debris: above the ground and of a material debris is made of. */
    public boolean isDebris(Block block) {
        return block.getY() > GardenLayout.groundY() && debrisMaterials().contains(block.getType());
    }

    private Set<Material> debrisMaterials() {
        Overgrowth.Settings settings = config.clearing();
        if (settings != materialsFor) {
            materials = settings.materials();
            materialsFor = settings;
        }
        return materials;
    }

    /** {@code %plot% %percent% %required% %bar% %cleared% %total%} for one plot. */
    public String[] placeholders(GardenState state, int px, int pz) {
        int percent = state.clearingPercent(px, pz);
        return new String[]{
                "%plot%", GardenLayout.plotName(px, pz),
                "%percent%", String.valueOf(percent),
                "%required%", String.valueOf(required()),
                "%bar%", Tiers.bar(required() <= 0 ? 1 : percent / (double) required(), 20, "&a|", "&7|"),
                "%cleared%", String.valueOf(state.clearingDone(px, pz)),
                "%total%", String.valueOf(state.clearingTotal(px, pz))};
    }

    // ── counting ───────────────────────────────────────────────────────────────

    /** The owner breaking debris; everyone else was already refused by {@link GardenProtection}. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        removed(event.getBlock(), event.getPlayer());
    }

    /** Leaves left behind by a felled tree, decaying on their own. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        removed(event.getBlock(), null);
    }

    /**
     * Leaves on a locked plot stay put. They are planted with their real distance from a log so they
     * should never decay, but a locked plot's debris is counted once, when it is grown, and a leaf that
     * went missing before the plot was bought would be one the player could never clear.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDecayLocked(LeavesDecayEvent event) {
        Block block = event.getBlock();
        GardenState state = worlds.stateOf(block.getWorld());
        if (state == null) {
            return;
        }
        int px = GardenLayout.plotAt(block.getX());
        int pz = GardenLayout.plotAt(block.getZ());
        if (GardenLayout.inGrid(px, pz) && !state.isUnlocked(px, pz)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        removed(event.getBlock(), null);
    }

    /** Water washing away grass and flowers — it only reaches a plot being cleared from inside it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (!to.getType().isAir() && !to.isLiquid()) {
            removed(to, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        if (!GardenWorlds.isGarden(world)) {
            return;
        }
        String prefix = world.getName() + "/";
        verified.removeIf(entry -> entry.startsWith(prefix));
        UUID owner = GardenWorlds.ownerOf(world);
        if (owner != null && dirty.remove(owner)) {
            worlds.save(worlds.state(owner));
        }
    }

    /**
     * A block is about to go. Every event this is called from fires before the change, so the block is
     * still there to be read — and a recount done now still includes it.
     */
    private void removed(Block block, Player by) {
        World world = block.getWorld();
        UUID owner = GardenWorlds.ownerOf(world);
        if (owner == null || !config.clearing().enabled()) {
            return;
        }
        int px = GardenLayout.plotAt(block.getX());
        int pz = GardenLayout.plotAt(block.getZ());
        GardenState state = worlds.state(owner);
        if (!GardenLayout.inGrid(px, pz) || !state.isClearing(px, pz) || state.readOnly() || !isDebris(block)) {
            return;
        }
        if (verified.add(world.getName() + "/" + GardenLayout.plotName(px, pz))) {
            recount(world, state, px, pz);
        }
        state.setClearingDone(px, pz, state.clearingDone(px, pz) + 1);
        dirty.add(owner);

        Player owning = Bukkit.getPlayer(owner);
        if (state.clearingPercent(px, pz) >= required()) {
            finish(state, px, pz, owning);
            return;
        }
        Player told = by != null ? by : owning;
        if (told != null && told.getWorld().equals(world)) {
            messages.actionBar(told, "clearing-progress", placeholders(state, px, pz));
        }
    }

    /**
     * Count what debris is actually left on a plot and set its progress from that.
     *
     * <p>Scans the plot from the ground up to the tallest thing debris can be. The owner is standing in
     * it, so its chunks are loaded; the cost is a hundred thousand block reads, once per plot per load.
     */
    public void recount(World world, GardenState state, int px, int pz) {
        Set<Material> debris = debrisMaterials();
        int minX = GardenLayout.plotMin(px);
        int minZ = GardenLayout.plotMin(pz);
        int bottom = GardenLayout.groundY() + 1;
        int top = bottom + Overgrowth.Settings.MAX_HEIGHT;
        int left = 0;
        for (int x = minX; x < minX + GardenLayout.plotSize(); x++) {
            for (int z = minZ; z < minZ + GardenLayout.plotSize(); z++) {
                for (int y = bottom; y <= top; y++) {
                    if (debris.contains(world.getBlockAt(x, y, z).getType())) {
                        left++;
                    }
                }
            }
        }
        state.setClearingDone(px, pz, state.clearingTotal(px, pz) - left);
        dirty.add(state.owner());
    }

    // ── finishing ──────────────────────────────────────────────────────────────

    /**
     * A plot is clear enough to build on: stop tracking it, tell its owner, and pay the rewards.
     *
     * @param owner the owner if online, for the message; rewards run through the console either way
     */
    public void finish(GardenState state, int px, int pz, Player owner) {
        if (!state.finishClearing(px, pz)) {
            return;
        }
        worlds.save(state);
        dirty.remove(state.owner());
        String plot = GardenLayout.plotName(px, pz);
        OfflinePlayer who = Bukkit.getOfflinePlayer(state.owner());
        String[] placeholders = {"%player%", who.getName() == null ? state.owner().toString() : who.getName(),
                "%uuid%", state.owner().toString(), "%plot%", plot};
        Overgrowth.Settings settings = config.clearing();
        for (String command : settings.rewardCommands()) {
            String line = Messages.fill(command, placeholders);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
        }
        if (owner == null) {
            return;
        }
        messages.send(owner, "clearing-done", placeholders);
        for (String line : settings.rewardMessages()) {
            owner.sendMessage(com.mystipixel.royalskyblock.util.Text.color(Messages.fill(line, placeholders)));
        }
        owner.playSound(owner.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
    }
}
