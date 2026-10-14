package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalgarden.config.Messages;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;
import java.util.UUID;

/**
 * What may change inside a Garden. Owned plots are the owner's; the farmhouse plot, locked plots and the
 * floor course are read-only; plots being cleared allow breaking debris only. Non-player block movers
 * (pistons, liquids, explosions, trees, dispensers, falling blocks) are held to the same lines.
 * {@code royalgarden.bypass} skips all of it; each rule can be switched off under {@code protection:}.
 */
public final class GardenProtection implements Listener {

    /** Lets a player edit anywhere in a Garden, farmhouse and floor included. */
    public static final String BYPASS = "royalgarden.bypass";

    private final GardenWorlds worlds;
    private final GardenConfig config;
    private final Messages messages;
    private java.util.function.Predicate<Block> machines = block -> false;
    private ClearingService clearing;

    public GardenProtection(GardenWorlds worlds, GardenConfig config, Messages messages) {
        this.worlds = worlds;
        this.config = config;
        this.messages = messages;
    }

    /**
     * Blocks another listener opens on right-click, such as the Composter. Right-clicks on them are left to
     * it: a refusal here would talk over its menu.
     */
    public void setMachines(java.util.function.Predicate<Block> machines) {
        this.machines = machines == null ? block -> false : machines;
    }

    /** Hold plots that are still being cleared to breaking debris only. */
    public void setClearing(ClearingService clearing) {
        this.clearing = clearing;
    }

    private boolean uncleared(GardenState state, int px, int pz) {
        return clearing != null && clearing.blocks(state, px, pz);
    }

    // Off the board, the farmhouse, a locked plot, or the floor: the question every non-player rule asks.
    private boolean isProtected(World world, int x, int y, int z) {
        if (!GardenWorlds.isGarden(world)) {
            return false;
        }
        if (config.protect("floor") && y <= GardenLayout.floorY()) {
            return true;
        }
        int px = GardenLayout.plotAt(x);
        int pz = GardenLayout.plotAt(z);
        if (!GardenLayout.inGrid(px, pz)) {
            return true;
        }
        if (GardenLayout.isCentre(px, pz)) {
            return config.protect("farmhouse");
        }
        GardenState state = worlds.stateOf(world);
        // A plot being cleared is protected like a locked one from everything but its owner's hands:
        // otherwise a tree grown or a bush bone-mealed from next door would be debris to clear for free.
        return state == null || !state.isUnlocked(px, pz) || uncleared(state, px, pz);
    }

    private boolean isProtected(Block block) {
        return isProtected(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    private boolean isProtected(Location at) {
        return isProtected(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    // Packed for comparison, or -1 off the board.
    private static int plotKey(Block block) {
        int px = GardenLayout.plotAt(block.getX());
        int pz = GardenLayout.plotAt(block.getZ());
        return GardenLayout.inGrid(px, pz) ? pz * GardenLayout.grid() + px : -1;
    }

    // A message key, or null if allowed. Returns the reason so the message is decided with the refusal.
    private String refuse(Player player, Block block, boolean breaking) {
        World world = block.getWorld();
        if (!GardenWorlds.isGarden(world) || player.hasPermission(BYPASS)) {
            return null;
        }
        UUID owner = GardenWorlds.ownerOf(world);
        if (!player.getUniqueId().equals(owner) && !config.visitorsCanBuild()) {
            return "deny-visitor";
        }
        if (config.protect("floor") && block.getY() <= GardenLayout.floorY()) {
            return "deny-floor";
        }
        int px = GardenLayout.plotAt(block.getX());
        int pz = GardenLayout.plotAt(block.getZ());
        if (!GardenLayout.inGrid(px, pz)) {
            return "deny-outside";
        }
        if (GardenLayout.isCentre(px, pz)) {
            return config.protect("farmhouse") ? "deny-farmhouse" : null;
        }
        GardenState state = worlds.stateOf(world);
        if (state == null || !state.isUnlocked(px, pz)) {
            return "deny-locked";
        }
        if (uncleared(state, px, pz) && !(breaking && clearing.isDebris(block))) {
            return "deny-uncleared";
        }
        return null;
    }

    private boolean check(Player player, Block block, Event event) {
        String refusal = refuse(player, block, event instanceof BlockBreakEvent);
        if (refusal == null) {
            return true;
        }
        if (event instanceof org.bukkit.event.Cancellable cancellable) {
            cancellable.setCancelled(true);
        }
        messages.actionBar(player, refusal, placeholders(block));
        return false;
    }

    private String[] placeholders(Block block) {
        int px = GardenLayout.plotAt(block.getX());
        int pz = GardenLayout.plotAt(block.getZ());
        GardenState state = worlds.stateOf(block.getWorld());
        if (clearing != null && state != null && GardenLayout.inGrid(px, pz) && state.isClearing(px, pz)) {
            return clearing.placeholders(state, px, pz);
        }
        return new String[]{"%plot%", GardenLayout.plotName(px, pz)};
    }

    // The border is a square and cannot cut out a locked plot, so this does. Block-to-block moves only,
    // since looking around fires this constantly. A player already inside a locked plot is sent to the
    // farmhouse; cancelling every move would also cancel the steps out.
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        Player player = event.getPlayer();
        if (!GardenWorlds.isGarden(to.getWorld()) || player.hasPermission(BYPASS) || !config.protect("locked-plots")) {
            return;
        }
        if (!isLockedPlot(to)) {
            return;
        }
        if (isLockedPlot(from)) {
            event.setTo(GardenBuilder.spawn(to.getWorld()));
        } else {
            event.setCancelled(true);
        }
        messages.actionBar(player, "deny-locked", "%plot%", plotName(to));
    }

    // Pearls, chorus fruit and other teleports are held to the walking rule. Plugin and command teleports
    // pass: they are how the desk's "go here" and admin tools work.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || !GardenWorlds.isGarden(to.getWorld()) || !config.protect("locked-plots")) {
            return;
        }
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.PLUGIN || cause == PlayerTeleportEvent.TeleportCause.COMMAND) {
            return;
        }
        if (!event.getPlayer().hasPermission(BYPASS) && isLockedPlot(to)) {
            event.setCancelled(true);
            messages.actionBar(event.getPlayer(), "deny-locked", "%plot%", plotName(to));
        }
    }

    // Boats, minecarts and mounts carry a player without a move event of their own.
    @EventHandler(priority = EventPriority.NORMAL)
    public void onVehicleMove(VehicleMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        if (!GardenWorlds.isGarden(to.getWorld()) || !config.protect("locked-plots") || !isLockedPlot(to)) {
            return;
        }
        Vehicle vehicle = event.getVehicle();
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player && !player.hasPermission(BYPASS)) {
                vehicle.eject();
                vehicle.teleport(from);
                messages.actionBar(player, "deny-locked", "%plot%", plotName(to));
                return;
            }
        }
    }

    private boolean isLockedPlot(Location at) {
        int px = GardenLayout.plotAt(at.getBlockX());
        int pz = GardenLayout.plotAt(at.getBlockZ());
        if (!GardenLayout.inGrid(px, pz)) {
            return false;                           // off the board entirely: the world border has that
        }
        GardenState state = worlds.stateOf(at.getWorld());
        return state != null && !state.isUnlocked(px, pz);
    }

    private static String plotName(Location at) {
        return GardenLayout.plotName(GardenLayout.plotAt(at.getBlockX()), GardenLayout.plotAt(at.getBlockZ()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        check(event.getPlayer(), event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        check(event.getPlayer(), event.getBlock(), event);
    }

    // Buckets place blocks without a BlockPlaceEvent, so water would otherwise flood a locked plot.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        check(event.getPlayer(), event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        check(event.getPlayer(), event.getBlock(), event);
    }

    // Hoes, shovels, axes, bone meal and flint change blocks through an interact, not a place or break.
    // The item in hand is only denied when it is not food, so a player can still eat.
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !GardenWorlds.isGarden(block.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) {
            return;
        }
        boolean visitor = !player.getUniqueId().equals(GardenWorlds.ownerOf(block.getWorld()));
        if (event.getAction() == Action.PHYSICAL) {
            if (isProtected(block) || visitor) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || machines.test(block)) {
            return;
        }
        // Doors, trapdoors and gates on the farmhouse plot open for the owner. Containers stay shut: the
        // farmhouse is rebuilt when it changes, and anything stored in it would go with it.
        boolean farmhouseDoor = !visitor && block.getBlockData() instanceof org.bukkit.block.data.Openable
                && GardenLayout.isCentre(GardenLayout.plotAt(block.getX()), GardenLayout.plotAt(block.getZ()))
                && block.getY() > GardenLayout.floorY();
        boolean allowed = farmhouseDoor || config.interactAllowed().contains(block.getType());
        if ((visitor && !config.visitorsCanInteract() && !allowed) || (isProtected(block) && !allowed)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            if (event.getItem() == null || !event.getItem().getType().isEdible()) {
                event.setUseItemInHand(Event.Result.DENY);
            }
            messages.actionBar(player, visitor ? "deny-visitor" : refusalFor(block), placeholders(block));
        }
    }

    private String refusalFor(Block block) {
        int px = GardenLayout.plotAt(block.getX());
        int pz = GardenLayout.plotAt(block.getZ());
        if (block.getY() <= GardenLayout.floorY()) {
            return "deny-floor";
        }
        if (!GardenLayout.inGrid(px, pz)) {
            return "deny-outside";
        }
        if (GardenLayout.isCentre(px, pz)) {
            return "deny-farmhouse";
        }
        GardenState state = worlds.stateOf(block.getWorld());
        return state != null && state.isUnlocked(px, pz) ? "deny-uncleared" : "deny-locked";
    }

    // Item frames and paintings.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null) {
            check(event.getPlayer(), event.getBlock(), event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.getRemover() instanceof Player player) {
            check(player, event.getEntity().getLocation().getBlock(), event);
        } else if (isProtected(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    // Armour stands, boats, minecarts and end crystals.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null) {
            check(event.getPlayer(), event.getBlock(), event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (!GardenWorlds.isGarden(event.getBlock().getWorld()) || !config.protect("liquids")) {
            return;
        }
        Block to = event.getToBlock();
        if (isProtected(to) && plotKey(to) != plotKey(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (config.protect("pistons")
                && pistonTouchesProtected(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (config.protect("pistons")
                && pistonTouchesProtected(event.getBlock(), event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    private boolean pistonTouchesProtected(Block piston, List<Block> moved, BlockFace direction) {
        if (!GardenWorlds.isGarden(piston.getWorld())) {
            return false;
        }
        // Checked in both directions: which way a retracting piston's "direction" points has changed
        // between API versions, and a block either side of a moved one is the whole of its path.
        BlockFace back = direction.getOppositeFace();
        if (isProtected(piston.getRelative(direction))) {
            return true;
        }
        for (Block block : moved) {
            if (isProtected(block) || isProtected(block.getRelative(direction))
                    || isProtected(block.getRelative(back))) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (config.protect("explosions")) {
            event.blockList().removeIf(this::isProtected);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (config.protect("explosions")) {
            event.blockList().removeIf(this::isProtected);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGrow(StructureGrowEvent event) {
        if (config.protect("growth")) {
            event.getBlocks().removeIf(state -> isProtected(state.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (!config.protect("growth")) {
            return;
        }
        if (event.getPlayer() != null && refuse(event.getPlayer(), event.getBlock(), false) != null) {
            event.setCancelled(true);
            return;
        }
        event.getBlocks().removeIf((BlockState state) -> isProtected(state.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        Block block = event.getBlock();
        if (!config.protect("dispensers") || !GardenWorlds.isGarden(block.getWorld())) {
            return;
        }
        if (block.getBlockData() instanceof Directional directional
                && isProtected(block.getRelative(directional.getFacing()))) {
            event.setCancelled(true);
        }
    }

    // Falling sand and gravel, and anything else that turns into a block where it lands.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    // Only reasons in world.allowed-spawn-reasons. CUSTOM (plugin spawns) is allowed by default so EcoMobs
    // and Sam work; add BREEDING, SPAWNER_EGG or SPAWNER to allow animal and mob farms.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!GardenWorlds.isGarden(event.getLocation().getWorld())) {
            return;
        }
        if (!config.allowedSpawnReasons().contains(event.getSpawnReason())) {
            event.setCancelled(true);
        }
    }

    // Sweep vanilla mobs out of each chunk as its entities load.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        World world = event.getWorld();
        if (GardenWorlds.isGarden(world)) {
            worlds.sweepMobs(world, event.getEntities());
        }
    }
}
