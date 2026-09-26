package com.mystipixel.royalgarden.milestone;

import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Which breaks count toward milestones.
 *
 * <p>A block counts if it is a milestone crop, broken in a counting world (every Garden by default), by
 * a player not in creative, fully grown if it has an age, and — for crops marked {@code natural-only} —
 * not placed by a player. Sugar cane and cactus count the column that falls with them.
 *
 * <p>{@code blocks} counting adds the crop's {@code points} per block. {@code drops} counting adds the
 * crop items that actually drop, so Fortune counts; a plugin that pockets drops before they fall
 * (telekinesis, auto-pickup) hides them from it, so {@code blocks} is the default.
 */
public final class MilestoneListener implements Listener {

    private record Pending(Player player, MilestoneCrop crop, int stackedAbove) {
    }

    private final MilestoneService milestones;
    private final PlacedBlocks placed;
    private final Map<Long, Pending> pending = new HashMap<>();

    public MilestoneListener(MilestoneService milestones, PlacedBlocks placed) {
        this.milestones = milestones;
        this.placed = placed;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        MilestoneConfig config = milestones.config();
        Block block = event.getBlock();
        MilestoneCrop crop = config.byBlock(block.getType());
        if (crop == null || !config.counts(block.getWorld())) {
            return;
        }
        boolean natural = !crop.naturalOnly() || !placed.isPlaced(block);
        if (crop.naturalOnly()) {
            placed.unmark(block);
        }
        Player player = event.getPlayer();
        if (!natural || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (crop.requireRipe() && block.getBlockData() instanceof Ageable ageable
                && ageable.getAge() < ageable.getMaximumAge()) {
            return;
        }
        int stacked = crop.stacked() ? stackedAbove(block, crop) : 0;
        if (config.counting() == MilestoneConfig.Counting.DROPS) {
            pending.put(key(block), new Pending(player, crop, stacked));
        } else {
            milestones.record(player, crop, (long) crop.points() * (1 + stacked));
        }
    }

    private int stackedAbove(Block block, MilestoneCrop crop) {
        int count = 0;
        Block above = block.getRelative(BlockFace.UP);
        while (count < 32 && crop.blocks().contains(above.getType())) {
            if (!crop.naturalOnly() || !placed.isPlaced(above)) {
                count++;
            }
            if (crop.naturalOnly()) {
                placed.unmark(above);
            }
            above = above.getRelative(BlockFace.UP);
        }
        return count;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent event) {
        Pending waiting = pending.remove(key(event.getBlock()));
        if (waiting == null) {
            return;
        }
        long count = waiting.stackedAbove();
        for (Item item : event.getItems()) {
            if (waiting.crop().drops().contains(item.getItemStack().getType())) {
                count += item.getItemStack().getAmount();
            }
        }
        milestones.record(waiting.player(), waiting.crop(), count);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        milestones.store().unload(event.getPlayer().getUniqueId());
    }

    /** Forget breaks whose drops never came, so the map cannot grow. */
    public void clearStale() {
        pending.clear();
    }

    private static long key(Block block) {
        return ((long) block.getX() & 0x3FFFFFF) << 38 | ((long) block.getZ() & 0x3FFFFFF) << 12
                | ((long) block.getY() & 0xFFF);
    }
}
