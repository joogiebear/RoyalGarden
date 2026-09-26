package com.mystipixel.royalgarden.composter;

import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.garden.GardenLayout;
import com.mystipixel.royalgarden.garden.GardenWorlds;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * The Composter's block in the barn: right-click it to open the Composter.
 *
 * <p>It stands at {@code block.x / y / z} in {@code composter.yml} — by default one of the composters
 * the barn is already dressed with — and is marked in its chunk's persistent data, which Garden worlds
 * save with the chunk. Only a block that is both marked and still the configured material opens the
 * menu, so the barn's other composters stay scenery. The mark is written whenever a Garden is prepared,
 * which also puts the block back if it is missing.
 *
 * <p>The click is handled at {@code LOWEST} and the vanilla use cancelled, so a crop in hand is never
 * composted the vanilla way; the farmhouse's protection is told to leave this block to us.
 */
public final class ComposterBlock implements Listener {

    private final NamespacedKey key;
    private final ComposterConfig config;
    private final Messages messages;
    private final Consumer<Player> open;

    public ComposterBlock(Plugin plugin, ComposterConfig config, Messages messages, Consumer<Player> open) {
        this.key = new NamespacedKey(plugin, "garden_composter");
        this.config = config;
        this.messages = messages;
        this.open = open;
    }

    private static int pack(Block block) {
        return ((block.getY() + 4096) << 8) | ((block.getX() & 15) << 4) | (block.getZ() & 15);
    }

    /** The configured spot in a Garden world. */
    private Block spot(World world) {
        int[] at = config.blockSpot();
        return world.getBlockAt(at[0], GardenLayout.groundY() + at[1], at[2]);
    }

    /** Put the Composter in place in a prepared Garden, and mark it. Runs on the server thread. */
    public void ensure(World world) {
        if (!config.enabled() || !config.blockEnabled() || !GardenWorlds.isGarden(world)) {
            return;
        }
        Block block = spot(world);
        if (config.placeBlock() && block.getType() != config.blockMaterial()) {
            block.setType(config.blockMaterial(), false);
        }
        block.getChunk().getPersistentDataContainer().set(key, PersistentDataType.INTEGER, pack(block));
    }

    /** {@link #ensure} for every Garden loaded now — after a reload has moved or re-enabled it. */
    public void ensureAll() {
        for (World world : Bukkit.getWorlds()) {
            if (GardenWorlds.isGarden(world)) {
                ensure(world);
            }
        }
    }

    /** Whether a block is the Composter. */
    public boolean isComposter(Block block) {
        if (!config.enabled() || !config.blockEnabled() || block.getType() != config.blockMaterial()
                || !GardenWorlds.isGarden(block.getWorld())) {
            return false;
        }
        Chunk chunk = block.getChunk();
        Integer mark = chunk.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return mark != null && mark == pack(block);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || !isComposter(block)) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        // Once per click: the off-hand copy is cancelled above and goes no further.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(GardenWorlds.ownerOf(block.getWorld()))) {
            messages.actionBar(player, "composter-not-yours");
            return;
        }
        open.accept(player);
    }
}
