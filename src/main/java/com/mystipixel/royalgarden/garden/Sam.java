package com.mystipixel.royalgarden.garden;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Sam, who runs the desk in the farmhouse.
 *
 * <p>A plain Bukkit villager with its AI switched off, not a Citizens NPC. Citizens would give better
 * skins and pathing, but it would also make The Garden refuse to work on any server that hasn't
 * installed it — a hard dependency bought for cosmetics. A villager is always available, survives a
 * world save without any of our own persistence, and can be found again by the tag stamped on it.
 *
 * <p>He is identified by persistent data rather than by his name or position, so renaming him, pushing
 * him, or moving the barn cannot orphan him into a second Sam.
 */
public final class Sam implements Listener {

    private static final String TAG = "garden_sam";

    private final NamespacedKey key;

    public Sam(Plugin plugin) {
        this.key = new NamespacedKey(plugin, TAG);
    }

    /** The name shown above him. */
    private static Component displayName() {
        return Component.text("Sam", NamedTextColor.GREEN)
                .append(Component.text(" — Garden Desk", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
    }

    /**
     * Make sure exactly one Sam is standing at the desk in this world.
     *
     * <p>Called on every Garden load, not just on creation: an entity can be lost to a world rollback
     * or a stray {@code /kill @e}, and a Garden with no Sam has no way to get one back otherwise. Any
     * duplicates found are removed, so the "exactly one" in that sentence is enforced rather than
     * hoped for.
     */
    public void ensure(World world) {
        Location post = GardenBuilder.samPost(world);
        Villager kept = null;
        for (Entity entity : world.getEntities()) {
            if (!isSam(entity)) {
                continue;
            }
            if (kept == null && entity instanceof Villager villager) {
                kept = villager;
            } else {
                entity.remove();                       // a second Sam is a bug, not a feature
            }
        }
        if (kept == null) {
            world.spawn(post, Villager.class, this::dress);
            return;
        }
        // He exists; put him back where he belongs and re-apply anything a version bump changed.
        kept.teleport(post);
        dress(kept);
    }

    /** Apply everything that makes a villager into Sam. Idempotent — also used to repair an existing one. */
    private void dress(Villager villager) {
        villager.setAdult();
        villager.setProfession(Villager.Profession.FARMER);
        villager.customName(displayName());
        villager.setCustomNameVisible(true);

        // No AI at all: he must not wander off the desk, restock, breed, seek a bed, or panic. This
        // also stops the villager trading UI from ever being the thing a click opens.
        villager.setAI(false);
        villager.setSilent(true);
        villager.setCollidable(false);
        villager.setInvulnerable(true);
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);

        villager.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }

    /** Whether an entity is Sam. */
    public boolean isSam(Entity entity) {
        return entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * Right-clicking Sam opens the desk.
     *
     * <p>{@code LOWEST} and cancelled unconditionally: the click must never reach the villager trading
     * screen, and cancelling early means nothing else has begun opening one by the time we do.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!isSam(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        Desk.open(event.getPlayer());
    }
}
