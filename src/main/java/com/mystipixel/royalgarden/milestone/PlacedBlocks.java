package com.mystipixel.royalgarden.milestone;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Marks crop blocks a player placed so they never count toward milestones. Marks live in chunk
 * persistent data; a block growing into a spot clears a stale mark, and pistons carry marks along.
 */
public final class PlacedBlocks implements Listener {

    private final NamespacedKey key;
    private final MilestoneConfig config;

    public PlacedBlocks(Plugin plugin, MilestoneConfig config) {
        this.key = new NamespacedKey(plugin, "garden_placed");
        this.config = config;
    }

    private static int pack(Block block) {
        return ((block.getY() + 4096) << 8) | ((block.getX() & 15) << 4) | (block.getZ() & 15);
    }

    public boolean isPlaced(Block block) {
        int[] marks = block.getChunk().getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
        if (marks == null) {
            return false;
        }
        int packed = pack(block);
        for (int mark : marks) {
            if (mark == packed) {
                return true;
            }
        }
        return false;
    }

    public void mark(Block block) {
        if (isPlaced(block)) {
            return;
        }
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        int[] marks = pdc.getOrDefault(key, PersistentDataType.INTEGER_ARRAY, new int[0]);
        int[] grown = new int[marks.length + 1];
        System.arraycopy(marks, 0, grown, 0, marks.length);
        grown[marks.length] = pack(block);
        pdc.set(key, PersistentDataType.INTEGER_ARRAY, grown);
    }

    public void unmark(Block block) {
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        int[] marks = pdc.get(key, PersistentDataType.INTEGER_ARRAY);
        if (marks == null) {
            return;
        }
        int packed = pack(block);
        int kept = 0;
        int[] out = new int[marks.length];
        for (int mark : marks) {
            if (mark != packed) {
                out[kept++] = mark;
            }
        }
        if (kept == marks.length) {
            return;
        }
        if (kept == 0) {
            pdc.remove(key);
        } else {
            int[] trimmed = new int[kept];
            System.arraycopy(out, 0, trimmed, 0, kept);
            pdc.set(key, PersistentDataType.INTEGER_ARRAY, trimmed);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (config.tracksPlacement(block.getType()) && config.counts(block.getWorld())) {
            mark(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (config.counts(event.getBlock().getWorld())) {
            unmark(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        carry(event.getBlocks(), facing(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        BlockFace facing = facing(event.getBlock());
        carry(event.getBlocks(), facing == null ? null : facing.getOppositeFace());
    }

    private static BlockFace facing(Block piston) {
        return piston.getBlockData() instanceof Directional directional ? directional.getFacing() : null;
    }

    private void carry(List<Block> moved, BlockFace direction) {
        if (direction == null || moved.isEmpty() || !config.counts(moved.get(0).getWorld())) {
            return;
        }
        List<Block> marked = new ArrayList<>();
        for (Block block : moved) {
            if (isPlaced(block)) {
                marked.add(block);
            }
        }
        for (Block block : marked) {
            unmark(block);
        }
        for (Block block : marked) {
            mark(block.getRelative(direction));
        }
    }
}
