package com.mystipixel.royalgarden.pest;

import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.garden.GardenLayout;
import com.mystipixel.royalgarden.garden.GardenState;
import com.mystipixel.royalgarden.garden.GardenWorlds;
import com.mystipixel.royalgarden.milestone.MilestoneCrop;
import com.mystipixel.royalgarden.pest.PestStore.Alive;
import com.mystipixel.royalgarden.visitor.VisitorMath;
import com.mystipixel.royalskyblock.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * Pests. A counted harvest in the owner's Garden, off cooldown, rolls {@code spawning.chance}; a hit
 * picks a plot, a kind by weight and a spot, then runs that pest's console command. The spawned entity
 * is caught and tagged with kind, plot and spawn time, which is how it is found, counted and exempted
 * from the mob sweep. Infested-plot effects live in {@link PestHolders}.
 */
public final class PestService implements Listener {

    /** Pays Garden XP: player, amount. */
    public interface XpSink {
        void give(UUID player, long amount);
    }

    /** Pays Copper: player, amount. */
    public interface CopperSink {
        void pay(Player player, long amount);
    }

    public enum Outcome { SPAWNED, BAD_PLOT, NO_ROOM, NO_SPOT, NO_PEST, NOT_LOADED, FAILED, UNREADABLE }

    private static final NamespacedKey ECOMOBS_MOB = NamespacedKey.fromString("ecomobs:mob");

    private final PestConfig config;
    private final PestStore store;
    private final Messages messages;
    private final GardenWorlds gardens;
    private final Logger logger;
    private final NamespacedKey typeKey;
    private final NamespacedKey plotKey;
    private final NamespacedKey spawnedKey;
    private ToIntFunction<UUID> level = owner -> 1;
    private XpSink xp = (player, amount) -> { };
    private CopperSink copper = (player, amount) -> { };
    private java.util.function.Supplier<String> currency = () -> "copper";
    private Consumer<UUID> changed = owner -> { };
    private java.util.function.Function<String, MilestoneCrop> crops = id -> null;
    // Entities spawned while a pest's command runs, or null when none is running.
    private List<Entity> capturing;

    public PestService(Plugin plugin, PestConfig config, PestStore store, Messages messages, GardenWorlds gardens,
                       Logger logger) {
        this.config = config;
        this.store = store;
        this.messages = messages;
        this.gardens = gardens;
        this.logger = logger;
        this.typeKey = new NamespacedKey(plugin, "garden_pest");
        this.plotKey = new NamespacedKey(plugin, "garden_pest_plot");
        this.spawnedKey = new NamespacedKey(plugin, "garden_pest_spawned");
    }

    public PestConfig config() {
        return config;
    }

    public PestStore store() {
        return store;
    }

    public void setPayment(ToIntFunction<UUID> level, XpSink xp, CopperSink copper,
                           java.util.function.Supplier<String> currency) {
        this.level = level;
        this.xp = xp;
        this.copper = copper;
        this.currency = currency == null ? () -> "copper" : currency;
    }

    /** For {@code %crop%} in the infested effects. */
    public void setCrops(java.util.function.Function<String, MilestoneCrop> crops) {
        this.crops = crops;
    }

    public MilestoneCrop crop(String id) {
        return id == null || id.isBlank() ? null : crops.apply(id);
    }

    /** Told whenever a Garden's pests change; this is how infested plots' effects stay in step. */
    public void onChange(Consumer<UUID> changed) {
        this.changed = changed == null ? owner -> { } : changed;
    }

    /** From the milestone listener. Maybe brings a pest. */
    public void harvested(Player player, Block block, MilestoneCrop crop) {
        UUID owner = player.getUniqueId();
        World world = block.getWorld();
        if (!config.enabled() || !owner.equals(GardenWorlds.ownerOf(world)) || config.pests().isEmpty()) {
            return;
        }
        int px = GardenLayout.plotAt(block.getX());
        int pz = GardenLayout.plotAt(block.getZ());
        if (!GardenLayout.inGrid(px, pz) || store.readOnly(owner)) {
            return;
        }
        PestStore.Data data = store.get(owner);
        RandomGenerator random = ThreadLocalRandom.current();
        if (!PestMath.offCooldown(data.lastSpawn(), System.currentTimeMillis(), config.cooldownMillis())
                || !PestMath.rolls(config.chance(crop.id()), random.nextDouble())) {
            return;
        }
        int plot = PestMath.pickPlot(PestStore.pack(px, pz), eligiblePlots(world), data.counts(),
                config.maxPerPlot(), config.maxPerGarden(), config.otherPlotChance(), random.nextDouble(),
                random.nextDouble());
        if (plot < 0) {
            return;
        }
        spawn(world, null, plot, crop.id(), random);
    }

    /** Unlocked, and not the farmhouse unless it is allowed. */
    public List<Integer> eligiblePlots(World world) {
        GardenState state = gardens.stateOf(world);
        List<Integer> out = new ArrayList<>();
        if (state == null) {
            return out;
        }
        for (int[] plot : state.plots()) {
            if (config.farmhouse() || !GardenLayout.isCentre(plot[0], plot[1])) {
                out.add(PestStore.pack(plot[0], plot[1]));
            }
        }
        return out;
    }

    /**
     * Spawns a pest now in a loaded Garden, ignoring cooldown and chance but not the caps.
     *
     * @param type a kind of pest, or null for a random pick by weight
     * @param plot a plot, or -1 for a random one with room
     * @param crop the crop being farmed, or null
     */
    public Outcome spawn(World world, PestType type, int plot, String crop, RandomGenerator random) {
        UUID owner = GardenWorlds.ownerOf(world);
        if (owner == null) {
            return Outcome.NOT_LOADED;
        }
        if (store.readOnly(owner)) {
            return Outcome.UNREADABLE;
        }
        PestStore.Data data = store.get(owner);
        List<Integer> eligible = eligiblePlots(world);
        if (plot < 0) {
            plot = PestMath.pickPlot(-1, eligible, data.counts(), config.maxPerPlot(), config.maxPerGarden(),
                    100, 0, random.nextDouble());
        }
        if (plot >= 0 && !eligible.contains(plot)) {
            return Outcome.BAD_PLOT;
        }
        if (plot < 0 || !PestMath.hasRoom(data.count(plot), data.total(), config.maxPerPlot(), config.maxPerGarden())) {
            return Outcome.NO_ROOM;
        }
        if (type == null) {
            type = VisitorMath.pickWeighted(config.eligible(Math.max(1, level.applyAsInt(owner))), PestType::weight,
                    random.nextDouble());
        }
        if (type == null) {
            return Outcome.NO_PEST;
        }
        Location spot = spot(world, plot, random);
        if (spot == null) {
            return Outcome.NO_SPOT;
        }
        Entity entity = run(type, spot, owner, plot);
        if (entity == null) {
            logger.warning("pests.yml: '" + type.command() + "' (pest " + type.id() + ") spawned nothing RoyalGarden "
                    + "could find" + (type.mob().isBlank() ? "" : " with EcoMobs id '" + type.mob() + "'")
                    + "; check the command and the EcoMobs mob.");
            return Outcome.FAILED;
        }
        long now = System.currentTimeMillis();
        tag(entity, type.id(), plot, now);
        data.addAlive(entity.getUniqueId(), new Alive(type.id(), plot, crop == null ? "" : crop,
                spot.getX(), spot.getY(), spot.getZ(), now));
        data.setLastSpawn(now);
        store.save(owner);
        Player online = Bukkit.getPlayer(owner);
        if (online != null) {
            messages.send(online, "pest-spawned", placeholders(owner, type, plot));
            sound(online, config.spawnSound());
        }
        changed.accept(owner);
        return Outcome.SPAWNED;
    }

    // A random spot with room to stand, on a loaded part of the plot, or null.
    private Location spot(World world, int plot, RandomGenerator random) {
        int px = PestStore.px(plot);
        int pz = PestStore.pz(plot);
        for (int attempt = 0; attempt < config.attempts(); attempt++) {
            int x = PestMath.spot(GardenLayout.plotMin(px), GardenLayout.plotSize(), config.edgeMargin(),
                    random.nextDouble());
            int z = PestMath.spot(GardenLayout.plotMin(pz), GardenLayout.plotSize(), config.edgeMargin(),
                    random.nextDouble());
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block feet = world.getHighestBlockAt(x, z).getRelative(0, 1, 0);
            if (feet.getY() <= GardenLayout.floorY() || !feet.isPassable()
                    || !feet.getRelative(0, 1, 0).isPassable()) {
                continue;
            }
            return new Location(world, x + 0.5, feet.getY(), z + 0.5, random.nextFloat() * 360f, 0f);
        }
        return null;
    }

    private Entity run(PestType type, Location at, UUID owner, int plot) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        String command = Messages.fill(type.command(),
                "%x%", String.valueOf(at.getX()), "%y%", String.valueOf(at.getY()), "%z%", String.valueOf(at.getZ()),
                "%world%", at.getWorld().getName(), "%player%", player.getName() == null ? owner.toString() : player.getName(),
                "%uuid%", owner.toString(), "%plot%", PestStore.plotName(plot), "%pest%", type.id());
        List<Entity> spawned = new ArrayList<>();
        List<Entity> outer = capturing;
        capturing = spawned;
        try {
            dispatch(command);
        } catch (RuntimeException failed) {
            logger.warning("pests.yml: '" + command + "' (pest " + type.id() + ") failed: " + failed);
            return null;
        } finally {
            capturing = outer;
        }
        for (Entity entity : spawned) {
            if (candidate(entity, type)) {
                return entity;
            }
        }
        // A command that spawns a tick later, or outside the spawn event: the newest match standing there.
        Entity newest = null;
        for (Entity entity : at.getWorld().getNearbyEntities(at, 2, 3, 2)) {
            if (candidate(entity, type) && (newest == null || entity.getTicksLived() < newest.getTicksLived())) {
                newest = entity;
            }
        }
        return newest;
    }

    private boolean candidate(Entity entity, PestType type) {
        if (!(entity instanceof LivingEntity) || entity instanceof Player || !entity.isValid() || typeOf(entity) != null) {
            return false;
        }
        if (type.mob().isBlank()) {
            return true;
        }
        String mob = ECOMOBS_MOB == null ? null
                : entity.getPersistentDataContainer().get(ECOMOBS_MOB, PersistentDataType.STRING);
        return type.mob().equalsIgnoreCase(mob);
    }

    private void tag(Entity entity, String type, int plot, long spawned) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(typeKey, PersistentDataType.STRING, type);
        data.set(plotKey, PersistentDataType.STRING, PestStore.plotName(plot));
        data.set(spawnedKey, PersistentDataType.LONG, spawned);
        entity.setPersistent(true);
        if (entity instanceof LivingEntity living) {
            living.setRemoveWhenFarAway(false);
        }
    }

    /** Null if it is not a pest. */
    public String typeOf(Entity entity) {
        return entity.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (capturing != null) {
            capturing.add(event.getEntity());
        }
    }

    // A known pest is kept unless its plot was locked or the caps lowered. One without a record is taken
    // back in if there is room, unless it predates a clear or its plot is locked or gone.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        World world = event.getWorld();
        UUID owner = GardenWorlds.ownerOf(world);
        if (owner == null) {
            return;
        }
        boolean dirty = false;
        List<Integer> eligible = null;
        for (Entity entity : event.getEntities()) {
            String type = typeOf(entity);
            if (type == null) {
                continue;
            }
            if (store.readOnly(owner)) {
                return;
            }
            if (eligible == null) {
                eligible = eligiblePlots(world);
            }
            PestStore.Data data = store.get(owner);
            Alive known = data.alive().get(entity.getUniqueId());
            if (known != null) {
                if (!eligible.contains(known.plot()) || overCap(data, known.plot())) {
                    data.removeAlive(entity.getUniqueId());
                    entity.remove();
                    dirty = true;
                }
                continue;
            }
            PersistentDataContainer tags = entity.getPersistentDataContainer();
            int[] plot = GardenLayout.parsePlot(tags.get(plotKey, PersistentDataType.STRING));
            long spawned = tags.getOrDefault(spawnedKey, PersistentDataType.LONG, 0L);
            int packed = plot == null ? -1 : PestStore.pack(plot[0], plot[1]);
            if (plot == null || spawned <= data.clearedAt() || !eligible.contains(packed)
                    || !PestMath.hasRoom(data.count(packed), data.total(), config.maxPerPlot(), config.maxPerGarden())) {
                entity.remove();
                continue;
            }
            Location at = entity.getLocation();
            if (PestMath.outside(at.getX(), at.getZ(), GardenLayout.plotMin(plot[0]), GardenLayout.plotMax(plot[0]),
                    GardenLayout.plotMin(plot[1]), GardenLayout.plotMax(plot[1]))) {
                at = GardenLayout.plotCentre(world, plot[0], plot[1]);
            }
            data.addAlive(entity.getUniqueId(), new Alive(type, packed, "", at.getX(), at.getY(), at.getZ(), spawned));
            dirty = true;
        }
        if (dirty) {
            store.save(owner);
            changed.accept(owner);
        }
    }

    private boolean overCap(PestStore.Data data, int plot) {
        int perPlot = config.maxPerPlot();
        int perGarden = config.maxPerGarden();
        return (perPlot > 0 && data.count(plot) > perPlot) || (perGarden > 0 && data.total() > perGarden);
    }

    /**
     * Runs on a timer: a pest outside its plot is put back where it appeared, and a record whose chunk is
     * loaded without its entity is dropped.
     */
    public void confineAll() {
        for (World world : Bukkit.getWorlds()) {
            UUID owner = GardenWorlds.ownerOf(world);
            if (owner == null || store.readOnly(owner)) {
                continue;
            }
            PestStore.Data data = store.get(owner);
            if (data.total() == 0) {
                continue;
            }
            boolean dirty = false;
            for (Map.Entry<UUID, Alive> entry : new HashMap<>(data.alive()).entrySet()) {
                Alive pest = entry.getValue();
                Entity entity = Bukkit.getEntity(entry.getKey());
                if (entity == null || !entity.isValid()) {
                    int cx = (int) Math.floor(pest.x()) >> 4;
                    int cz = (int) Math.floor(pest.z()) >> 4;
                    boolean gone = (entity != null && entity.isDead())
                            || (world.isChunkLoaded(cx, cz) && world.getChunkAt(cx, cz).isEntitiesLoaded());
                    if (gone) {
                        data.removeAlive(entry.getKey());
                        dirty = true;
                    }
                    continue;
                }
                if (!entity.getWorld().equals(world)) {
                    data.removeAlive(entry.getKey());
                    dirty = true;
                    continue;
                }
                int px = PestStore.px(pest.plot());
                int pz = PestStore.pz(pest.plot());
                Location at = entity.getLocation();
                if (PestMath.outside(at.getX(), at.getZ(), GardenLayout.plotMin(px), GardenLayout.plotMax(px),
                        GardenLayout.plotMin(pz), GardenLayout.plotMax(pz))) {
                    entity.teleport(new Location(world, pest.x(), pest.y(), pest.z(), at.getYaw(), at.getPitch()));
                }
            }
            if (dirty) {
                store.save(owner);
                changed.accept(owner);
            }
        }
    }

    /**
     * Admin, and {@code /garden reset}. Pests in unloaded chunks are removed as they load, since they
     * predate the clear. Returns how many records went.
     */
    public int clear(UUID owner) {
        if (store.readOnly(owner)) {
            return -1;
        }
        PestStore.Data data = store.get(owner);
        int removed = data.total();
        World world = Bukkit.getWorld(GardenWorlds.worldName(owner));
        if (world != null) {
            for (UUID id : data.alive().keySet()) {
                Entity entity = Bukkit.getEntity(id);
                if (entity != null) {
                    entity.remove();
                }
            }
            for (Entity entity : world.getEntities()) {
                if (typeOf(entity) != null) {
                    entity.remove();
                }
            }
        }
        data.clearAlive();
        data.setClearedAt(System.currentTimeMillis());
        store.save(owner);
        changed.accept(owner);
        return removed;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        String type = typeOf(entity);
        UUID owner = type == null ? null : GardenWorlds.ownerOf(entity.getWorld());
        if (owner == null || store.readOnly(owner)) {
            return;
        }
        PestStore.Data data = store.get(owner);
        Alive record = data.removeAlive(entity.getUniqueId());
        store.save(owner);
        changed.accept(owner);
        Player killer = entity.getKiller();
        if (killer == null || !config.enabled() || (config.ownerOnly() && !killer.getUniqueId().equals(owner))) {
            return;
        }
        int plot = record != null ? record.plot() : plotOf(entity);
        pay(killer, type, plot);
    }

    private int plotOf(Entity entity) {
        int[] plot = GardenLayout.parsePlot(entity.getPersistentDataContainer().get(plotKey, PersistentDataType.STRING));
        return plot == null ? -1 : PestStore.pack(plot[0], plot[1]);
    }

    // Copper, Garden XP, then every stacked reward.
    private void pay(Player killer, String typeId, int plot) {
        UUID id = killer.getUniqueId();
        PestType type = config.pest(typeId);
        if (!store.readOnly(id)) {
            PestStore.Data stats = store.get(id);
            stats.recordKill(typeId);
            ConfigurationSection all = config.rewards();
            // a kill-count reward is paid once, so that count is on disk before it is paid
            if (all != null && all.getConfigurationSection("by-kills." + stats.kills()) != null) {
                store.saveNow(id);
            } else {
                store.save(id);
            }
        }
        RandomGenerator random = ThreadLocalRandom.current();
        long copperPaid = type == null ? 0 : type.copper().roll(random);
        long xpPaid = type == null ? 0 : type.gardenXp();
        String[] placeholders = append(placeholders(id, type, plot),
                "%copper%", String.format("%,d", copperPaid),
                "%xp%", String.format("%,d", xpPaid));
        copper.pay(killer, copperPaid);
        if (xpPaid > 0) {
            xp.give(id, xpPaid);
        }
        List<String> commands = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        ConfigurationSection rewards = config.rewards();
        if (rewards != null) {
            add(rewards.getConfigurationSection("every-kill"), commands, lines);
            add(rewards.getConfigurationSection("by-type." + typeId), commands, lines);
            add(rewards.getConfigurationSection("by-kills." + store.peek(id).kills()), commands, lines);
        }
        if (type != null) {
            add(type.rewards(), commands, lines);
        }
        for (String command : commands) {
            dispatch(Messages.fill(command, placeholders));
        }
        for (String line : lines) {
            killer.sendMessage(Text.color(Messages.fill(line, placeholders)));
        }
        messages.send(killer, "pest-killed", placeholders);
        sound(killer, config.killSound());
    }

    private static void add(ConfigurationSection layer, List<String> commands, List<String> lines) {
        if (layer != null) {
            commands.addAll(layer.getStringList("commands"));
            lines.addAll(layer.getStringList("messages"));
        }
    }

    // loaded while they are online, so their placeholders read the cache rather than the file
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        store.get(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        // Their Garden may stay loaded a while; its pests are re-read from disk if they are needed again.
        store.unload(player);
    }

    /** {@code %player% %uuid% %pest% %pest_name% %plot% %pests% %kills% %pest_kills% %alive% %currency%} */
    public String[] placeholders(UUID owner, PestType type, int plot) {
        PestStore.Data data = store.peek(owner);
        String id = type == null ? "" : type.id();
        return new String[]{
                "%pest%", id,
                "%pest_name%", type == null ? id : type.displayName(),
                "%plot%", plot < 0 ? "" : PestStore.plotName(plot),
                "%pests%", plot < 0 ? "0" : String.valueOf(data.count(plot)),
                "%kills%", String.format("%,d", data.kills()),
                "%pest_kills%", String.format("%,d", data.kills(id)),
                "%alive%", String.valueOf(data.total()),
                "%currency%", currency.get(),
                "%player%", name(owner),
                "%uuid%", owner.toString()};
    }

    /** {@code %alive% %infested% %killed% %max_per_garden%} */
    public String[] statPlaceholders(UUID owner) {
        PestStore.Data data = store.peek(owner);
        return new String[]{
                "%alive%", String.valueOf(data.total()),
                "%infested%", String.valueOf(infestedPlots(data)),
                "%killed%", String.format("%,d", data.kills()),
                "%max_per_garden%", config.maxPerGarden() > 0 ? String.valueOf(config.maxPerGarden()) : "∞",
                "%player%", name(owner)};
    }

    private int infestedPlots(PestStore.Data data) {
        int infested = 0;
        for (int count : data.counts().values()) {
            if (PestMath.infested(count, config.infestedAt())) {
                infested++;
            }
        }
        return infested;
    }

    /**
     * {@code %royalgarden_pests_<key>%} for {@code alive}, {@code infested}, {@code killed}, {@code
     * <pest>_killed} and {@code plot_<plot>}; null otherwise. Plain numbers for use in conditions.
     */
    public String resolve(OfflinePlayer player, String key) {
        String params = key.toLowerCase(Locale.ROOT);
        if (!params.startsWith("pests_")) {
            return null;
        }
        String what = params.substring("pests_".length());
        PestStore.Data data = store.peek(player.getUniqueId());
        switch (what) {
            case "alive":
                return String.valueOf(data.total());
            case "infested":
                return String.valueOf(infestedPlots(data));
            case "killed":
                return String.valueOf(data.kills());
            default:
                break;
        }
        if (what.startsWith("plot_")) {
            int[] plot = GardenLayout.parsePlot(what.substring("plot_".length()));
            return plot == null ? null : String.valueOf(data.count(PestStore.pack(plot[0], plot[1])));
        }
        if (what.endsWith("_killed")) {
            String type = what.substring(0, what.length() - "_killed".length());
            return config.pest(type) == null ? null : String.valueOf(data.kills(type));
        }
        return null;
    }

    private static void dispatch(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.startsWith("/") ? command.substring(1) : command);
    }

    private static void sound(Player player, String sound) {
        if (sound != null && !sound.isBlank()) {
            player.playSound(player.getLocation(), sound.trim().toLowerCase(Locale.ROOT), 1f, 1f);
        }
    }

    private static String name(UUID owner) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        return player.getName() == null ? owner.toString() : player.getName();
    }

    static String[] append(String[] base, String... more) {
        String[] out = new String[base.length + more.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }
}
