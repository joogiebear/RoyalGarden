package com.mystipixel.royalgarden.garden;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * What you may and may not change inside a Garden.
 *
 * <p>Three rules, in the order they are checked:
 *
 * <ol>
 *   <li><b>The farmhouse plot is read-only.</b> It carries the barn, the desk and Sam, and it is the
 *       one plot every Garden has — so it is the one plot whose contents can be relied upon.</li>
 *   <li><b>Locked plots are read-only.</b> The whole board is generated, so a locked plot is real
 *       ground you may be able to walk on — the world border is a centred square and opens a whole
 *       ring at a time. Owning it is a separate question from standing on it, and this is where that
 *       distinction is enforced.</li>
 *   <li><b>The bedrock floor is read-only</b>, since it is all that stands between a shovel and a
 *       fall out of the world.</li>
 * </ol>
 *
 * <p>{@code royalgarden.bypass} (operators by default) skips all three — the same shape as the host's
 * {@code royalskyblock.bypass}, so an admin who can already edit islands can edit Gardens too.
 */
public final class GardenProtection implements Listener {

    /** Lets a player edit anywhere in a Garden, farmhouse and walls included. */
    public static final String BYPASS = "royalgarden.bypass";

    private final GardenWorlds worlds;
    private final GardenBorder border;

    public GardenProtection(GardenWorlds worlds, GardenBorder border) {
        this.worlds = worlds;
        this.border = border;
    }

    /**
     * Keep a player out of a plot they have not unlocked.
     *
     * <p>This, not the border, is what makes locking exact. A world border is a square and a player has
     * one, so it can enclose the box your Garden grew into but cannot cut a locked plot out of the
     * middle of it. A move check can, for any shape, and it is the difference between "the border is
     * roughly right" and "you cannot walk onto a plot you do not own".
     *
     * <p>Only block-to-block movement is considered — looking around and shuffling within a block fire
     * this event constantly, and comparing block coordinates first keeps the common case to two integer
     * comparisons. Teleports are a separate event class and deliberately pass through, so the menu's
     * "click to visit" and admin teleports still work.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        if (!GardenWorlds.isGarden(to.getWorld()) || player.hasPermission(BYPASS)) {
            return;
        }
        int px = GardenLayout.plotAt(to.getBlockX());
        int pz = GardenLayout.plotAt(to.getBlockZ());
        if (!GardenLayout.inGrid(px, pz)) {
            return;                        // off the board entirely — the world border has that
        }
        GardenState state = worlds.stateOf(to.getWorld());
        if (state == null || state.isUnlocked(px, pz)) {
            return;
        }
        event.setCancelled(true);
        deny(player, "Plot " + GardenLayout.plotName(px, pz) + " is locked.");
        border.flashRed(player);
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

    /**
     * Nothing spawns in a Garden unless a plugin asked for it.
     *
     * <p>No animals, no hostiles, no wandering traders, no chunk-generation herds — a farming world
     * should contain what the farmer put there. The gamerule already stops natural spawning, but that
     * is only the natural path: breeding, spawn eggs, village mechanics and jockeys all arrive by other
     * routes, and this closes them.
     *
     * <p>{@code CUSTOM} is allowed through, and that is the whole point of doing it this way rather
     * than blanket-cancelling. It is the reason a plugin-spawned entity uses, so EcoMobs (and Sam, who
     * is spawned the same way) keep working while vanilla spawning stays shut. {@code COMMAND} is
     * allowed too, so {@code /summon} still works for an admin testing something.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!GardenWorlds.isGarden(event.getLocation().getWorld())) {
            return;
        }
        SpawnReason reason = event.getSpawnReason();
        if (reason == SpawnReason.CUSTOM || reason == SpawnReason.COMMAND) {
            return;
        }
        event.setCancelled(true);
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
