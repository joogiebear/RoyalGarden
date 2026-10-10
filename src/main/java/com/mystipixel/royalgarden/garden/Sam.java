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
 * Sam, who runs the desk in the farmhouse: a plain villager with AI off, so Citizens is not a hard
 * dependency. Found by a persistent-data tag, so renaming or moving him never orphans a second Sam.
 * Paper loads a chunk's entities after its blocks, so {@link #ensure} only runs once the desk chunk's
 * entities are loaded, otherwise a duplicate is spawned.
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

    /** Runs {@link #ensure} now if the desk chunk's entities are loaded, otherwise when they are. */
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
     * Exactly one Sam at the desk in this world, or none with {@code sam.enabled} off. Duplicates are
     * removed.
     */
    public void ensure(World world) {
        Location post = GardenBuilder.samPost(world);
        Villager kept = null;
        // The whole loaded world, not the post's chunk: a new farmhouse can move the post, and a Sam left at
        // the old one would otherwise never be found and a second spawned.
        for (Entity entity : world.getEntities()) {
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
        kept.teleport(post);
        kept.setRotation(post.getYaw(), post.getPitch());   // an AI-less mob's head does not follow a teleport
        dress(kept);
    }

    // Idempotent: also used to repair an existing Sam.
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

    public boolean isSam(Entity entity) {
        return entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // LOWEST and cancelled unconditionally, so the click never reaches the villager trading screen.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!isSam(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        // Fires once per hand; the off-hand copy is still cancelled above.
        if (event.getHand() == EquipmentSlot.HAND) {
            menu.open(event.getPlayer());
        }
    }
}
