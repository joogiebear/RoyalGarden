package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalskyblock.util.Text;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * Sam, who runs the desk in the farmhouse.
 *
 * <p>A plain Bukkit villager with its AI switched off, not a Citizens NPC. Citizens would give better
 * skins and pathing, but it would also make The Garden refuse to work on any server that hasn't
 * installed it — a hard dependency bought for cosmetics. A villager is always available, survives a
 * world save without any of our own persistence, and can be found again by the tag stamped on it.
 *
 * <p>He is identified by persistent data rather than by his name or position, so renaming him, pushing
 * him, or moving the barn cannot orphan him into a second Sam. His name, profession and whether he
 * exists at all are under {@code sam:} in {@code config.yml}.
 *
 * <h2>When he is checked</h2>
 *
 * <p>Paper loads a chunk's entities after its blocks, and separately. Looking for Sam before his chunk's
 * entities had arrived found nobody and spawned a second one, which is why {@link #ensure} only runs once
 * the desk's chunk reports its entities loaded — either straight away, if they already are, or from
 * {@link EntitiesLoadEvent} when they arrive.
 */
public final class Sam implements Listener {

    private static final String TAG = "garden_sam";

    private final NamespacedKey key;
    private final GardenConfig config;
    private final GardenMenu menu;

    public Sam(Plugin plugin, GardenConfig config, GardenMenu menu) {
        this.key = new NamespacedKey(plugin, TAG);
        this.config = config;
        this.menu = menu;
    }

    /**
     * Put Sam at the desk once it is safe to look for him: now if the desk chunk's entities are
     * loaded, otherwise when they are.
     */
    public void ensureWhenLoaded(World world) {
        Chunk chunk = GardenBuilder.samPost(world).getChunk();
        if (chunk.isEntitiesLoaded()) {
            ensure(world);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        World world = event.getWorld();
        if (!GardenWorlds.isGarden(world)) {
            return;
        }
        Location post = GardenBuilder.samPost(world);
        Chunk chunk = event.getChunk();
        if (chunk.getX() == post.getBlockX() >> 4 && chunk.getZ() == post.getBlockZ() >> 4) {
            ensure(world);
        }
    }

    /**
     * Make sure exactly one Sam is standing at the desk in this world — or none, with {@code sam.enabled}
     * off. Any duplicates are removed, so "exactly one" is enforced rather than hoped for.
     */
    public void ensure(World world) {
        Location post = GardenBuilder.samPost(world);
        Villager kept = null;
        for (Entity entity : post.getChunk().getEntities()) {
            if (!isSam(entity)) {
                continue;
            }
            if (kept == null && config.samEnabled() && entity instanceof Villager villager) {
                kept = villager;
            } else {
                entity.remove();                       // a second Sam is a bug, not a feature
            }
        }
        if (!config.samEnabled()) {
            return;
        }
        if (kept == null) {
            world.spawn(post, Villager.class, this::dress);
            return;
        }
        // He exists; put him back where he belongs and re-apply anything the config changed.
        kept.teleport(post);
        kept.setRotation(post.getYaw(), post.getPitch());   // an AI-less mob's head does not follow a teleport
        dress(kept);
    }

    /** Apply everything that makes a villager into Sam. Idempotent — also used to repair an existing one. */
    private void dress(Villager villager) {
        villager.setAdult();
        Villager.Profession profession = Registry.VILLAGER_PROFESSION.get(
                NamespacedKey.minecraft(config.samProfession().trim().toLowerCase(Locale.ROOT)));
        if (profession != null) {
            villager.setProfession(profession);
        }
        villager.customName(Text.color(config.samName()).decoration(TextDecoration.ITALIC, false));
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
        // The event fires once per hand; opening on both would open the desk twice per click. The
        // off-hand copy is still cancelled above so it can never reach the trading screen.
        if (event.getHand() == EquipmentSlot.HAND) {
            menu.open(event.getPlayer());
        }
    }
}
