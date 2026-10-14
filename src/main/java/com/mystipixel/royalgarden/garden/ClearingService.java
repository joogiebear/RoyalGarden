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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Clearing a bought plot of its {@link Overgrowth}. Any block above the ground of a debris material, on
 * a plot being cleared, is debris (placement there is refused). Once {@code clearing.required-percent}
 * is gone the plot is finished.
 *
 * <p>Events can be missed (a crash, a restored world, an admin tool), so the first touch of a plot's
 * debris after its world loads <b>recounts</b> it from the world. Progress is saved on a timer, and
 * always on finishing a plot, on world unload and on shutdown.
 */
public final class ClearingService implements Listener {

    private static final long SAVE_TICKS = 10 * 20L;

    private final Plugin plugin;
    private final GardenConfig config;
    private final Messages messages;
    private final GardenWorlds worlds;
    private final Set<UUID> dirty = new HashSet<>();

    // world name + "/" + plot name
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

    /** Saves what is outstanding. */
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

    public int required() {
        return config.clearing().required();
    }

    /**
     * Bought but not yet clear enough to build on. Judged against the current threshold, so lowering it
     * frees plots straight away.
     */
    public boolean blocks(GardenState state, int px, int pz) {
        return config.clearing().enabled() && state != null && state.isClearing(px, pz)
                && state.clearingPercent(px, pz) < required();
    }

    /** Above the ground and of a material debris is made of. */
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

    /** {@code %plot% %percent% %required% %bar% %cleared% %total%} */
    public String[] placeholders(GardenState state, int px, int pz) {
        int percent = state.clearingPercent(px, pz);
        return new String[]{
                "%plot%", GardenLayout.plotName(px, pz),
                "%percent%", String.valueOf(percent),
                "%required%", String.valueOf(required()),
                "%bar%", Tiers.bar(required() <= 0 ? 1 : percent / (double) required(), config.clearingBarLength(),
                        config.clearingBarFilled(), config.clearingBarEmpty()),
                "%cleared%", String.valueOf(state.clearingDone(px, pz)),
                "%total%", String.valueOf(state.clearingTotal(px, pz))};
    }

    // Owner only: everyone else was already refused by GardenProtection.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        removed(event.getBlock(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent event) {
        removed(event.getBlock(), null);
    }

    // Leaves on a locked plot stay put: its debris is counted once when grown, so a leaf lost before
    // purchase could never be cleared.
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

    // Water only reaches a plot being cleared from inside it.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (!to.getType().isAir() && !to.isLiquid()) {
            removed(to, null);
        }
    }

    // a Garden that is not loaded has nothing being cleared, so its state can go until it is needed again
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        if (!dirty.contains(owner)) {
            worlds.forget(owner);
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

    // Every caller fires before the change, so the block is still there and a recount now includes it.
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
     * Sets a plot's progress from the debris actually left. About a hundred thousand block reads, once per
     * plot per load; the owner is standing in it, so its chunks are loaded.
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

    /**
     * Stops tracking the plot, tells its owner and pays the rewards.
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
