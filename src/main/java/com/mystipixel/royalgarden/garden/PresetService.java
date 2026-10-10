package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalgarden.config.Durations;
import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalskyblock.currency.Cost;
import com.mystipixel.royalskyblock.currency.CurrencyService;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Choosing a preset for a plot from the desk. Applying one is destructive and, in a contest, valuable,
 * so it needs: a {@code selectable} preset (and its {@code permission}), a plot that is theirs, unlocked,
 * not the farmhouse, not being built and cleared of debris, an expired per-plot cooldown, the cost, and a
 * second click within {@code presets.players.confirm-window}.
 */
public final class PresetService {

    /** Skips the per-plot cooldown between rebuilds, for staff setting up showcase Gardens. */
    public static final String BYPASS_COOLDOWN = "royalgarden.bypass.cooldown";

    private record Armed(int px, int pz, String id, long until) {
    }

    private final GardenConfig config;
    private final Messages messages;
    private final CurrencyService currency;
    private final GardenWorlds worlds;
    private final Map<UUID, Armed> armed = new HashMap<>();
    private java.util.function.ToIntFunction<UUID> levels;
    private ClearingService clearing;

    public PresetService(GardenConfig config, Messages messages, CurrencyService currency, GardenWorlds worlds) {
        this.config = config;
        this.messages = messages;
        this.currency = currency;
        this.worlds = worlds;
    }

    /** Refuse presets on plots that still have debris to clear. */
    public void setClearing(ClearingService clearing) {
        this.clearing = clearing;
    }

    /** Tells the player if the plot must be cleared first. */
    public boolean refuseUncleared(Player player, GardenState state, int px, int pz) {
        if (clearing == null || !clearing.blocks(state, px, pz)) {
            return false;
        }
        messages.send(player, "presets-clear-first", clearing.placeholders(state, px, pz));
        return true;
    }

    /** For the {@code garden-level:} gate. */
    public void setLevels(java.util.function.ToIntFunction<UUID> levels) {
        this.levels = levels;
    }

    /** The highest possible level when levels are not set up. */
    public int level(Player player) {
        return levels == null ? Integer.MAX_VALUE : levels.applyAsInt(player.getUniqueId());
    }

    /** The preset's own, or the configured default. */
    public long cooldownFor(PresetLibrary.Entry entry) {
        return entry.cooldownMillis() >= 0 ? entry.cooldownMillis() : config.presetCooldownMillis();
    }

    /** 0 if the plot may be rebuilt now. */
    public long cooldownLeft(Player player, GardenState state, int px, int pz, PresetLibrary.Entry entry) {
        if (player.hasPermission(BYPASS_COOLDOWN)) {
            return 0;
        }
        long last = state.lastApplied(px, pz);
        if (last <= 0) {
            return 0;
        }
        return Math.max(0, last + cooldownFor(entry) - System.currentTimeMillis());
    }

    public String display(Cost cost) {
        return cost.isFree() ? messages.raw("cost-free") : currency.format(cost);
    }

    /**
     * The first click arms the preset; a second click on it for the same plot, soon enough, applies it.
     *
     * @return true once the rebuild has started, so the caller can close the menu
     */
    public boolean choose(Player player, int px, int pz, PresetLibrary.Entry entry) {
        String plot = GardenLayout.plotName(px, pz);
        String name = entry.displayName();
        if (!config.playerPresetsEnabled()) {
            messages.send(player, "presets-disabled");
            return false;
        }
        World world = player.getWorld();
        if (!player.getUniqueId().equals(GardenWorlds.ownerOf(world))) {
            messages.send(player, "presets-go-to-garden");
            return false;
        }
        GardenState state = worlds.state(player.getUniqueId());
        if (state.readOnly()) {
            messages.send(player, "garden-unreadable");
            return false;
        }
        if (GardenLayout.isCentre(px, pz) || !state.isUnlocked(px, pz)) {
            messages.send(player, "presets-plot-not-yours", "%plot%", plot);
            return false;
        }
        if (refuseUncleared(player, state, px, pz)) {
            return false;
        }
        if (!entry.permission().isBlank() && !player.hasPermission(entry.permission())) {
            messages.send(player, "presets-no-permission", "%preset%", name);
            return false;
        }
        if (entry.gardenLevel() > 0 && level(player) < entry.gardenLevel()) {
            messages.send(player, "presets-garden-level", "%preset%", name,
                    "%required%", String.valueOf(entry.gardenLevel()));
            return false;
        }
        if (worlds.isBuilding(world, px, pz)) {
            messages.send(player, "plot-busy", "%plot%", plot);
            return false;
        }
        long wait = cooldownLeft(player, state, px, pz, entry);
        if (wait > 0) {
            messages.send(player, "presets-cooldown", "%plot%", plot, "%time%", Durations.format(wait));
            return false;
        }

        long now = System.currentTimeMillis();
        Armed pending = armed.get(player.getUniqueId());
        boolean confirmed = pending != null && pending.px() == px && pending.pz() == pz
                && pending.id().equals(entry.id()) && pending.until() >= now;
        if (!confirmed && config.confirmMillis() > 0) {
            armed.put(player.getUniqueId(), new Armed(px, pz, entry.id(), now + config.confirmMillis()));
            messages.send(player, "presets-confirm", "%preset%", name, "%plot%", plot,
                    "%cost%", display(entry.cost()), "%time%", Durations.format(config.confirmMillis()));
            return false;
        }
        armed.remove(player.getUniqueId());

        Cost cost = entry.cost();
        if (!currency.canAfford(player, cost)) {
            messages.send(player, "presets-cannot-afford", "%preset%", name, "%cost%", display(cost));
            return false;
        }
        if (!currency.charge(player, cost)) {
            messages.send(player, "payment-failed");
            return false;
        }
        if (!worlds.rebuild(world, px, pz, entry.id(), true)) {
            currency.refund(player, cost);
            messages.send(player, "presets-failed", "%preset%", name);
            return false;
        }
        GardenState fresh = worlds.state(player.getUniqueId());
        fresh.setLastApplied(px, pz, now);
        worlds.save(fresh);
        messages.send(player, "presets-applying", "%preset%", name, "%plot%", plot);
        return true;
    }
}
