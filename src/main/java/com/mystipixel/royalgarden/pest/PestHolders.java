package com.mystipixel.royalgarden.pest;

import com.mystipixel.royalgarden.garden.GardenLayout;
import com.mystipixel.royalgarden.garden.GardenWorlds;
import com.mystipixel.royalgarden.milestone.MilestoneConfig;
import com.mystipixel.royalgarden.milestone.MilestoneCrop;
import com.mystipixel.royalgarden.upgrade.EffectTemplate;
import com.mystipixel.royalskyblock.libreforge.RoyalHolder;
import com.mystipixel.royalskyblock.libreforge.RoyalHolderKt;
import com.mystipixel.royalskyblock.libreforge.RoyalHolders;
import com.willfp.eco.core.EcoPlugin;
import com.willfp.libreforge.Dispatcher;
import com.willfp.libreforge.HolderProvider;
import com.willfp.libreforge.HolderProviderKt;
import com.willfp.libreforge.ProvidedHolder;
import com.willfp.libreforge.SimpleProvidedHolder;
import com.willfp.libreforge.ViolationContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Infested plots as libreforge effect holders — so what an infestation <em>does</em> is written in the
 * eco dialect ({@code infested-effects:} in {@code pests.yml}), not implemented here.
 *
 * <p>A player standing on a plot with at least {@code infested-at} pests holds one holder, compiled from
 * the template with {@code %pests%}, {@code %plot%}, {@code %crop%}, {@code %blocks%} and {@code %drops%}
 * filled in for that plot. Holders are compiled the first time a combination is needed and kept until a
 * reload. Only the Garden's owner is affected unless {@code infested-applies-to} says everyone.
 *
 * <p>The host refreshes holders on join and on changing world, but not on walking from plot to plot, so
 * this refreshes a player whenever they cross a plot edge or teleport inside a Garden, and everyone in a
 * Garden whenever its pests change. libreforge has no way to remove a provider, so on disable this one
 * is switched off rather than removed; a reload registers a fresh one.
 */
public final class PestHolders implements HolderProvider, Listener {

    private final EcoPlugin plugin;
    private final PestService pests;
    private final MilestoneConfig crops;
    private final Map<String, Optional<ProvidedHolder>> compiled = new ConcurrentHashMap<>();
    private volatile boolean active = true;

    public PestHolders(EcoPlugin plugin, PestService pests, MilestoneConfig crops) {
        this.plugin = plugin;
        this.pests = pests;
        this.crops = crops;
    }

    /** Start providing. */
    public void register() {
        HolderProviderKt.registerHolderProvider(this);
        refreshAll();
    }

    /** Stop providing, for good — this instance is never used again. */
    public void disable() {
        active = false;
        compiled.clear();
        refreshAll();
    }

    /** Forget every compiled holder, after {@code pests.yml} or {@code milestones.yml} changed. */
    public void compile() {
        compiled.clear();
    }

    @Override
    public Collection<ProvidedHolder> provide(Dispatcher<?> dispatcher) {
        if (!active || !(dispatcher.getDispatcher() instanceof Player player)) {
            return List.of();
        }
        PestConfig config = pests.config();
        World world = player.getWorld();
        UUID owner = GardenWorlds.ownerOf(world);
        if (owner == null || !config.enabled()
                || (config.appliesTo() == PestConfig.AppliesTo.OWNER && !owner.equals(player.getUniqueId()))) {
            return List.of();
        }
        Location at = player.getLocation();
        int px = GardenLayout.plotAt(at.getBlockX());
        int pz = GardenLayout.plotAt(at.getBlockZ());
        if (!GardenLayout.inGrid(px, pz)) {
            return List.of();
        }
        int plot = PestStore.pack(px, pz);
        PestStore.Data data = pests.store().peek(owner);
        int count = data.count(plot);
        if (!PestMath.infested(count, config.infestedAt())) {
            return List.of();
        }
        if (config.maxPerPlot() > 0) {
            count = Math.min(count, config.maxPerPlot());
        }
        String crop = newestCrop(data, plot);
        String key = PestStore.plotName(plot).toLowerCase(Locale.ROOT) + "_" + count + "_" + crop;
        int pestsHere = count;
        Optional<ProvidedHolder> holder = compiled.computeIfAbsent(key,
                ignored -> Optional.ofNullable(compile(plot, pestsHere, crop, key)));
        return holder.map(List::of).orElse(List.of());
    }

    /** The crop being farmed when the newest pest on a plot came, or blank. */
    private static String newestCrop(PestStore.Data data, int plot) {
        PestStore.Alive newest = null;
        for (PestStore.Alive pest : data.alive().values()) {
            if (pest.plot() == plot && (newest == null || pest.spawned() > newest.spawned())) {
                newest = pest;
            }
        }
        return newest == null || newest.crop() == null ? "" : newest.crop();
    }

    private ProvidedHolder compile(int plot, int count, String cropId, String key) {
        PestConfig config = pests.config();
        List<?> effects = config.effects();
        List<?> conditions = config.conditions();
        if (effects.isEmpty() && conditions.isEmpty()) {
            return null;
        }
        MilestoneCrop crop = pests.crop(cropId);
        Set<Material> blocks = new LinkedHashSet<>();
        Set<Material> drops = new LinkedHashSet<>();
        for (MilestoneCrop each : crop != null ? List.of(crop) : crops.crops()) {
            blocks.addAll(each.blocks());
            drops.addAll(each.drops().isEmpty() ? each.blocks() : each.drops());
        }
        Map<String, List<String>> lists = Map.of("%blocks%", names(blocks), "%drops%", names(drops));
        Map<String, String> text = new LinkedHashMap<>();
        text.put("%pests%", String.valueOf(count));
        text.put("%plot%", PestStore.plotName(plot));
        text.put("%crop%", crop == null ? "" : crop.id());
        YamlConfiguration section = new YamlConfiguration();
        section.set("effects", EffectTemplate.fill(effects, lists, text));
        section.set("conditions", EffectTemplate.fill(conditions, lists, text));
        NamespacedKey id = new NamespacedKey(plugin, "garden_pests_" + key.replaceAll("[^a-z0-9_]", "_"));
        RoyalHolder holder = RoyalHolderKt.compileRoyalHolder(plugin, id, section,
                new ViolationContext(plugin, "RoyalGarden infested plot " + PestStore.plotName(plot)));
        return holder == null ? null : new SimpleProvidedHolder(holder);
    }

    private static List<String> names(Set<Material> materials) {
        List<String> out = new ArrayList<>(materials.size());
        for (Material material : materials) {
            out.add(material.getKey().getKey());
        }
        return out;
    }

    // ── refreshing ─────────────────────────────────────────────────────────────

    /** Crossing a plot edge in a Garden changes which plot's pests apply. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        if (!GardenWorlds.isGarden(to.getWorld())) {
            return;
        }
        if (GardenLayout.plotAt(from.getBlockX()) != GardenLayout.plotAt(to.getBlockX())
                || GardenLayout.plotAt(from.getBlockZ()) != GardenLayout.plotAt(to.getBlockZ())) {
            RoyalHolders.refresh(event.getPlayer());
        }
    }

    /** A teleport inside a Garden — to a plot from the Pests page, say. Refreshed once it has happened. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || !GardenWorlds.isGarden(to.getWorld()) || !to.getWorld().equals(event.getFrom().getWorld())) {
            return;                                  // changing world is the host's to refresh
        }
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                RoyalHolders.refresh(player);
            }
        });
    }

    /** Refresh everyone in a Garden — after its pests changed. */
    public static void refreshGarden(UUID owner) {
        World world = Bukkit.getWorld(GardenWorlds.worldName(owner));
        if (world == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            RoyalHolders.refresh(player);
        }
    }

    /** Make libreforge ask again for everyone online — after a reload. */
    public static void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            RoyalHolders.refresh(player);
        }
    }
}
