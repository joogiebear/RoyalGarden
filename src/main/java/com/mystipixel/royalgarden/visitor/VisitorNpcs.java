package com.mystipixel.royalgarden.visitor;

import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.garden.GardenLayout;
import com.mystipixel.royalgarden.garden.GardenWorlds;
import com.mystipixel.royalskyblock.util.Text;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The visitors standing outside the barn: one no-AI mob per waiting visitor, at the spots listed in
 * {@code visitors.yml}. Right-clicking one opens its offer.
 *
 * <p>Built the way Sam is: a plain entity tagged in its persistent data with the id of the offer it
 * stands for, so it is found again after a restart and never duplicated. The queue is the truth and
 * the entities follow it — {@link #sync} removes any that no longer stand for a waiting visitor and
 * spawns any missing. More visitors than spots wait on the Visitors page without a body.
 *
 * <p>Like Sam, syncing waits for the spots' chunks to have their entities loaded: Paper loads those
 * after the blocks, and looking earlier would find nobody and spawn a second copy.
 *
 * <h2>The line</h2>
 *
 * <p>With {@code line:} in {@code visitors.yml} the spots are a single file leading to the barn's
 * drive-through window, front first. Visitors <em>walk</em> between them rather than jumping: a newcomer
 * walks in from behind the line, and when the one at the window is served the rest step up. They have no
 * AI to walk with — that would bring a villager's whole brain along, beds and workstations included — so
 * they are moved a little every tick instead, facing the way they go. The client animates their legs
 * from the movement, so from the outside it is walking.
 */
public final class VisitorNpcs implements Listener {

    private static final String TAG = "garden_visitor";

    private final NamespacedKey key;
    private final VisitorService service;
    private final Messages messages;
    private final BiConsumer<Player, String> open;
    private final Plugin plugin;

    /** Visitors on their way somewhere: entity id to where they are going. */
    private final Map<UUID, Location> walking = new HashMap<>();
    private BukkitTask walker;

    public VisitorNpcs(Plugin plugin, VisitorService service, Messages messages, BiConsumer<Player, String> open) {
        this.key = new NamespacedKey(plugin, TAG);
        this.service = service;
        this.messages = messages;
        this.open = open;
        this.plugin = plugin;
    }

    /** Stop walking anyone; the next sync sends them on again. */
    public void shutdown() {
        if (walker != null) {
            walker.cancel();
            walker = null;
        }
        walking.clear();
    }

    /** Bring the visitors at this owner's Garden in line with their queue, if the Garden is loaded. */
    public void sync(UUID owner) {
        World world = Bukkit.getWorld(GardenWorlds.worldName(owner));
        if (world != null) {
            sync(world);
        }
    }

    /** Bring every loaded Garden's visitors in line — after a reload changed names, spots or looks. */
    public void syncAll() {
        for (World world : Bukkit.getWorlds()) {
            if (GardenWorlds.isGarden(world)) {
                sync(world);
            }
        }
    }

    /** Make the entities at this Garden's spots match its queue — once their chunks' entities are loaded. */
    public void sync(World world) {
        UUID owner = GardenWorlds.ownerOf(world);
        if (owner == null) {
            return;
        }
        List<Location> spots = spots(world);
        Location entrance = entrance(world);
        List<Location> watched = new ArrayList<>(spots);
        if (entrance != null) {
            watched.add(entrance);
        }
        for (Location spot : watched) {
            int cx = spot.getBlockX() >> 4;
            int cz = spot.getBlockZ() >> 4;
            if (!world.isChunkLoaded(cx, cz) || !world.getChunkAt(cx, cz).isEntitiesLoaded()) {
                return;                              // EntitiesLoadEvent will bring us back
            }
        }

        Map<String, Integer> wanted = wanted(owner, spots.size());
        List<Offer> queue = service.store().get(owner).queue();
        Set<String> present = new HashSet<>();
        // Every visitor loaded anywhere in the Garden, not just those in the spots' chunks: a visitor left
        // standing where the spots used to be — before the line, say — is in some other chunk, and looking
        // only at the spots' chunks left them there, never walked in and never removed when served.
        for (Entity entity : world.getEntities()) {
            String id = tagOf(entity);
            if (id == null) {
                continue;
            }
            Integer index = wanted.get(id);
            Offer offer = index == null ? null : queue.get(index);
            VisitorType type = offer == null ? null : service.config().visitor(offer.visitor());
            if (index == null || present.contains(id) || entity.getType() != entityType(type)) {
                walking.remove(entity.getUniqueId());
                entity.remove();
                continue;
            }
            present.add(id);
            walk(entity, spots.get(index));
            dress(entity, owner, offer, type);
        }
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            if (present.contains(entry.getKey())) {
                continue;
            }
            Offer offer = queue.get(entry.getValue());
            VisitorType type = service.config().visitor(offer.visitor());
            EntityType entityType = entityType(type);
            java.util.function.Consumer<Entity> dresser = entity -> dress(entity, owner, offer, type);
            Location spot = spots.get(entry.getValue());
            boolean walksIn = entrance != null && service.config().walkSpeed() > 0;
            Entity spawned = world.spawn(walksIn ? facing(entrance, spot) : spot, entityType.getEntityClass(),
                    dresser);
            if (walksIn) {
                walk(spawned, spot);
            }
        }
    }

    // ── walking ────────────────────────────────────────────────────────────────

    /** Send a visitor to a spot: walking if they are near and walking is on, otherwise straight there. */
    private void walk(Entity entity, Location to) {
        double speed = service.config().walkSpeed();
        Location at = entity.getLocation();
        // Far off means a moved line or a spot list edited under them, not a walk worth watching.
        if (speed <= 0 || !at.getWorld().equals(to.getWorld()) || at.distanceSquared(to) > 40 * 40) {
            walking.remove(entity.getUniqueId());
            place(entity, to);
            return;
        }
        if (at.distanceSquared(to) < 0.0001) {
            walking.remove(entity.getUniqueId());
            face(entity, to.getYaw());
            return;
        }
        walking.put(entity.getUniqueId(), to.clone());
        if (walker == null) {
            walker = Bukkit.getScheduler().runTaskTimer(plugin, this::step, 1L, 1L);
        }
    }

    /** Move every walking visitor one step, and stop the task once nobody is walking. */
    private void step() {
        double speed = service.config().walkSpeed();
        Iterator<Map.Entry<UUID, Location>> it = walking.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Location> next = it.next();
            Entity entity = Bukkit.getEntity(next.getKey());
            Location to = next.getValue();
            if (entity == null || !entity.isValid() || !entity.getWorld().equals(to.getWorld())) {
                it.remove();
                continue;
            }
            Location at = entity.getLocation();
            double dx = to.getX() - at.getX();
            double dz = to.getZ() - at.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (speed <= 0 || distance <= speed) {
                place(entity, to);
                it.remove();
                continue;
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            place(entity, new Location(to.getWorld(), at.getX() + dx / distance * speed, to.getY(),
                    at.getZ() + dz / distance * speed, yaw, 0f));
        }
        if (walking.isEmpty() && walker != null) {
            walker.cancel();
            walker = null;
        }
    }

    private static void place(Entity entity, Location to) {
        entity.teleport(to);
        face(entity, to.getYaw());
    }

    /** Turn body and head: an AI-less mob's head does not follow a teleport on its own. */
    private static void face(Entity entity, float yaw) {
        entity.setRotation(yaw, 0f);
    }

    /** {@code from}, turned to look at {@code to}. */
    private static Location facing(Location from, Location to) {
        Location out = from.clone();
        out.setYaw((float) Math.toDegrees(Math.atan2(-(to.getX() - from.getX()), to.getZ() - from.getZ())));
        return out;
    }

    /** Offer id to spot index, for the visitors that get a spot: the first in the queue. */
    private Map<String, Integer> wanted(UUID owner, int spots) {
        Map<String, Integer> out = new HashMap<>();
        if (!service.config().enabled() || service.store().readOnly(owner)) {
            return out;
        }
        List<Offer> queue = service.store().get(owner).queue();
        for (int i = 0; i < Math.min(spots, queue.size()); i++) {
            out.put(queue.get(i).id(), i);
        }
        return out;
    }

    private List<Location> spots(World world) {
        List<Location> out = new ArrayList<>();
        for (VisitorConfig.Spot spot : service.config().spots()) {
            out.add(location(world, spot));
        }
        return out;
    }

    /** Where newcomers walk into the line from; null without a line. */
    private Location entrance(World world) {
        VisitorConfig.Spot entry = service.config().entry();
        return entry == null ? null : location(world, entry);
    }

    private static Location location(World world, VisitorConfig.Spot spot) {
        return new Location(world, spot.x(), spot.hasY() ? spot.y() : GardenLayout.groundY() + 1, spot.z(),
                spot.yaw(), 0f);
    }

    /** The configured entity type if it is a living thing that can be spawned; a villager otherwise. */
    private static EntityType entityType(VisitorType type) {
        if (type == null) {
            return EntityType.VILLAGER;
        }
        EntityType found = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(type.entity()));
        if (found == null || !found.isSpawnable() || found.getEntityClass() == null
                || !LivingEntity.class.isAssignableFrom(found.getEntityClass())) {
            return EntityType.VILLAGER;
        }
        return found;
    }

    /** Everything that makes an entity into this visitor. Idempotent — also repairs an existing one. */
    private void dress(Entity entity, UUID owner, Offer offer, VisitorType type) {
        String name = Messages.fill(service.config().yaml().getString("npc-name", "%visitor_name%"),
                service.placeholders(owner, offer));
        entity.customName(Text.color(name).decoration(TextDecoration.ITALIC, false));
        entity.setCustomNameVisible(true);
        entity.setSilent(true);
        entity.setInvulnerable(true);
        entity.setPersistent(true);
        if (entity instanceof LivingEntity living) {
            living.setAI(false);
            living.setCollidable(false);
            living.setRemoveWhenFarAway(false);
        }
        if (entity instanceof Ageable ageable) {
            ageable.setAdult();
        }
        // Piglins and hoglins turn into zombies in the Overworld, AI or not.
        if (entity instanceof PiglinAbstract piglin) {
            piglin.setImmuneToZombification(true);
        }
        if (entity instanceof Hoglin hoglin) {
            hoglin.setImmuneToZombification(true);
        }
        if (entity instanceof Villager villager && type != null) {
            if (!type.profession().isBlank()) {
                Villager.Profession profession = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(type.profession()));
                if (profession != null) {
                    villager.setProfession(profession);
                }
            }
            if (!type.biome().isBlank()) {
                Villager.Type look = Registry.VILLAGER_TYPE.get(NamespacedKey.minecraft(type.biome()));
                if (look != null) {
                    villager.setVillagerType(look);
                }
            }
        }
        entity.getPersistentDataContainer().set(key, PersistentDataType.STRING, offer.id());
    }

    /** The offer id an entity stands for, or null if it is not a visitor. */
    public String tagOf(Entity entity) {
        return entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    // ── events ─────────────────────────────────────────────────────────────────

    /**
     * Visitors whose offer is gone are removed as their chunk loads — wherever they stand, so moving
     * the spots cannot leave the old ones behind — and the spots are synced once their chunks are in.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        World world = event.getWorld();
        UUID owner = GardenWorlds.ownerOf(world);
        if (owner == null) {
            return;
        }
        Map<String, Integer> wanted = wanted(owner, service.config().spots().size());
        boolean ours = false;
        for (Entity entity : event.getEntities()) {
            String id = tagOf(entity);
            if (id != null && !wanted.containsKey(id)) {
                entity.remove();
            }
        }
        Chunk chunk = event.getChunk();
        List<Location> watched = spots(world);
        Location entrance = entrance(world);
        if (entrance != null) {
            watched.add(entrance);
        }
        for (Location spot : watched) {
            if (spot.getBlockX() >> 4 == chunk.getX() && spot.getBlockZ() >> 4 == chunk.getZ()) {
                ours = true;
                break;
            }
        }
        if (ours) {
            sync(world);
        }
    }

    /**
     * Right-clicking a visitor opens their offer — for the Garden's owner. {@code LOWEST} and cancelled
     * unconditionally, like Sam, so the villager trading screen never opens.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        String id = tagOf(event.getRightClicked());
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(GardenWorlds.ownerOf(event.getRightClicked().getWorld()))) {
            messages.actionBar(player, "visitor-not-yours");
            return;
        }
        open.accept(player, id);
    }

    /** Invulnerable mobs can still be hurt by players in creative; visitors cannot. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (tagOf(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }
}
