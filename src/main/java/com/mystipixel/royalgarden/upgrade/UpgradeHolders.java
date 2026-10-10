package com.mystipixel.royalgarden.upgrade;

import com.mystipixel.royalgarden.garden.GardenWorlds;
import com.mystipixel.royalgarden.milestone.MilestoneCrop;
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
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Crop upgrades as libreforge effect holders, compiled once per crop level from the {@code effects:} /
 * {@code conditions:} template in {@code upgrades.yml}. A player holds their current level of every
 * upgraded crop while {@code apply-in} allows. libreforge cannot remove a provider, so on disable this
 * one is switched off; a reload registers a fresh one.
 */
public final class UpgradeHolders implements HolderProvider {

    private final EcoPlugin plugin;
    private final UpgradeService upgrades;
    private volatile Map<String, Map<Integer, ProvidedHolder>> holders = Map.of();
    private volatile boolean active = true;

    public UpgradeHolders(EcoPlugin plugin, UpgradeService upgrades) {
        this.plugin = plugin;
        this.upgrades = upgrades;
    }

    public void register() {
        compile();
        HolderProviderKt.registerHolderProvider(this);
        refreshAll();
    }

    /** Permanent: this instance is never used again. */
    public void disable() {
        active = false;
        holders = Map.of();
        refreshAll();
    }

    /** After {@code upgrades.yml} or {@code milestones.yml} changed. */
    public void compile() {
        Map<String, Map<Integer, ProvidedHolder>> out = new HashMap<>();
        for (MilestoneCrop crop : upgrades.crops()) {
            List<?> effects = upgrades.config().effects(crop.id());
            List<?> conditions = upgrades.config().conditions(crop.id());
            if (effects.isEmpty() && conditions.isEmpty()) {
                continue;
            }
            Map<String, List<String>> lists = Map.of(
                    "%blocks%", names(crop.blocks()),
                    "%drops%", names(crop.drops().isEmpty() ? crop.blocks() : crop.drops()));
            Map<Integer, ProvidedHolder> perLevel = new HashMap<>();
            for (int level = 1; level <= upgrades.config().maxLevel(crop.id()); level++) {
                Map<String, String> text = new LinkedHashMap<>();
                text.put("%level%", String.valueOf(level));
                text.put("%crop%", crop.id());
                text.put("%value%", UpgradeService.number(level * upgrades.config().valuePerLevel(crop.id())));
                YamlConfiguration section = new YamlConfiguration();
                section.set("effects", EffectTemplate.fill(effects, lists, text));
                section.set("conditions", EffectTemplate.fill(conditions, lists, text));
                NamespacedKey id = new NamespacedKey(plugin, "garden_upgrade_" + crop.id() + "_" + level);
                RoyalHolder holder = RoyalHolderKt.compileRoyalHolder(plugin, id, section,
                        new ViolationContext(plugin, "RoyalGarden crop upgrade " + crop.id() + " level " + level));
                if (holder != null) {
                    perLevel.put(level, new SimpleProvidedHolder(holder));
                }
            }
            if (!perLevel.isEmpty()) {
                out.put(crop.id(), perLevel);
            }
        }
        holders = out;
    }

    private static List<String> names(Set<Material> materials) {
        List<String> out = new ArrayList<>(materials.size());
        for (Material material : materials) {
            out.add(material.getKey().getKey());
        }
        return out;
    }

    @Override
    public Collection<ProvidedHolder> provide(Dispatcher<?> dispatcher) {
        if (!active || !(dispatcher.getDispatcher() instanceof Player player)) {
            return List.of();
        }
        Map<String, Map<Integer, ProvidedHolder>> compiled = holders;
        if (compiled.isEmpty() || !upgrades.config().enabled() || !appliesHere(player)) {
            return List.of();
        }
        UUID owner = player.getUniqueId();
        List<ProvidedHolder> out = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, ProvidedHolder>> crop : compiled.entrySet()) {
            int level = upgrades.store().level(owner, crop.getKey());
            ProvidedHolder holder = level <= 0 ? null : crop.getValue().get(
                    Math.min(level, upgrades.config().maxLevel(crop.getKey())));
            if (holder != null) {
                out.add(holder);
            }
        }
        return out;
    }

    private boolean appliesHere(Player player) {
        World world = player.getWorld();
        return switch (upgrades.config().applyIn()) {
            case EVERYWHERE -> true;
            case GARDENS -> GardenWorlds.isGarden(world);
            case OWN_GARDEN -> player.getUniqueId().equals(GardenWorlds.ownerOf(world));
        };
    }

    /** Call after a level changes. */
    public static void refresh(Player player) {
        RoyalHolders.refresh(player);
    }

    /** Call after a reload recompiled the levels. */
    public static void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            RoyalHolders.refresh(player);
        }
    }
}
