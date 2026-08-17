package com.mystipixel.royalgarden.garden;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

/**
 * What you may and may not change inside a Garden.
 *
 * <p>Three rules, in the order they are checked:
 *
 * <ol>
 *   <li><b>The farmhouse plot is read-only.</b> It carries the barn, the desk and Sam, and it is the
 *       one plot every Garden has — so it is the one plot whose contents can be relied upon.</li>
 *   <li><b>Locked plots are read-only</b>, which mostly matters for placing: a locked plot is empty
 *       sky, and bridging out into it would let a player build past a border they have not bought.</li>
 *   <li><b>Boundary walls are read-only</b>, or the border that "moves as you unlock" would instead
 *       move whenever someone brought a pickaxe.</li>
 * </ol>
 *
 * <p>{@code royalgarden.bypass} (operators by default) skips all three — the same shape as the host's
 * {@code royalskyblock.bypass}, so an admin who can already edit islands can edit Gardens too.
 */
public final class GardenProtection implements Listener {

    /** Lets a player edit anywhere in a Garden, farmhouse and walls included. */
    public static final String BYPASS = "royalgarden.bypass";

    private final GardenWorlds worlds;

    public GardenProtection(GardenWorlds worlds) {
        this.worlds = worlds;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        String refusal = refuse(event.getPlayer(), event.getBlock());
        if (refusal != null) {
            event.setCancelled(true);
            deny(event.getPlayer(), refusal);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        String refusal = refuse(event.getPlayer(), event.getBlock());
        if (refusal != null) {
            event.setCancelled(true);
            deny(event.getPlayer(), refusal);
        }
    }

    /** Buckets place blocks without a BlockPlaceEvent, so water would otherwise flood a locked plot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        String refusal = refuse(event.getPlayer(), event.getBlock());
        if (refusal != null) {
            event.setCancelled(true);
            deny(event.getPlayer(), refusal);
        }
    }

    /**
     * Why this edit is not allowed, or null if it is.
     *
     * <p>Returns the reason rather than a boolean so the message the player gets is decided in the same
     * place as the decision — a refusal with the wrong explanation is its own kind of bug.
     */
    private String refuse(Player player, Block block) {
        World world = block.getWorld();
        if (!GardenWorlds.isGarden(world) || player.hasPermission(BYPASS)) {
            return null;
        }
        if (GardenBuilder.isWall(block.getType())) {
            return "That is a plot boundary.";
        }
        if (block.getType() == Material.BEDROCK) {
            return "That is the floor of your Garden.";
        }

        int px = GardenLayout.plotAt(block.getX());
        int pz = GardenLayout.plotAt(block.getZ());
        if (!GardenLayout.inGrid(px, pz)) {
            return "That is outside your Garden.";
        }
        if (GardenLayout.isCentre(px, pz)) {
            return "The farmhouse plot is protected.";
        }

        GardenState state = worlds.stateOf(world);
        if (state == null || !state.isUnlocked(px, pz)) {
            return "Plot " + GardenLayout.plotName(px, pz) + " is locked. Open the desk to unlock it.";
        }
        return null;
    }

    /** Tell the player why, in the action bar — a chat line per swing would be unreadable. */
    private void deny(Player player, String reason) {
        player.sendActionBar(Component.text(reason, NamedTextColor.RED));
    }
}
