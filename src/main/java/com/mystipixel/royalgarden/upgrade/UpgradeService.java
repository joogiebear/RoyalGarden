package com.mystipixel.royalgarden.upgrade;

import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.milestone.MilestoneConfig;
import com.mystipixel.royalgarden.milestone.MilestoneCrop;
import com.mystipixel.royalgarden.milestone.Tiers;
import com.mystipixel.royalskyblock.currency.Cost;
import com.mystipixel.royalskyblock.currency.CurrencyService;
import com.mystipixel.royalskyblock.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Crop upgrades: buying levels at the desk.
 *
 * <p>Each level of a crop costs the next entry of {@code costs} in any of the host's currencies —
 * Copper by default. What a level does is not decided here: {@link UpgradeHolders} gives the player
 * that level's libreforge effects, so yield, stats and the rest come from libreforge and EcoSkills.
 * Buying a level pays Garden XP, then the stacked rewards: {@code rewards.every-level} and
 * {@code rewards.by-level.<n>}.
 */
public final class UpgradeService implements Listener {

    /** Pays Garden XP: owner, amount. */
    public interface XpSink {
        void give(UUID owner, long amount);
    }

    private final UpgradeConfig config;
    private final UpgradeStore store;
    private final MilestoneConfig crops;
    private final Messages messages;
    private final CurrencyService currency;
    private XpSink xp = (owner, amount) -> { };
    private java.util.function.Consumer<Player> changed = player -> { };

    public UpgradeService(UpgradeConfig config, UpgradeStore store, MilestoneConfig crops, Messages messages,
                          CurrencyService currency) {
        this.config = config;
        this.store = store;
        this.crops = crops;
        this.messages = messages;
        this.currency = currency;
    }

    public UpgradeConfig config() {
        return config;
    }

    public UpgradeStore store() {
        return store;
    }

    /** Told whenever a player's levels change — how their effect holders are refreshed. */
    public void onChange(java.util.function.Consumer<Player> changed) {
        this.changed = changed == null ? player -> { } : changed;
    }

    public void setXp(XpSink xp) {
        this.xp = xp == null ? (owner, amount) -> { } : xp;
    }

    /** The crops that can be upgraded, in {@code milestones.yml} order. */
    public List<MilestoneCrop> crops() {
        List<MilestoneCrop> out = new ArrayList<>();
        for (MilestoneCrop crop : crops.crops()) {
            if (config.upgradable(crop.id())) {
                out.add(crop);
            }
        }
        return out;
    }

    public MilestoneCrop crop(String id) {
        MilestoneCrop crop = crops.crop(id);
        return crop != null && config.upgradable(crop.id()) ? crop : null;
    }

    public int level(UUID owner, MilestoneCrop crop) {
        return Math.min(store.level(owner, crop.id()), config.maxLevel(crop.id()));
    }

    /** What the next level costs, or null at the top. */
    public Cost nextCost(UUID owner, MilestoneCrop crop) {
        List<Cost> costs = config.costs(crop.id());
        int level = level(owner, crop);
        return level < costs.size() ? costs.get(level) : null;
    }

    public String display(Cost cost) {
        return cost == null ? messages.raw("upgrade-maxed")
                : cost.isFree() ? messages.raw("cost-free") : currency.format(cost);
    }

    /** Buy the next level of a crop. Returns whether it went through. */
    public boolean buy(Player player, MilestoneCrop crop) {
        UUID owner = player.getUniqueId();
        if (!config.enabled()) {
            messages.send(player, "upgrades-disabled");
            return false;
        }
        if (store.readOnly(owner)) {
            messages.send(player, "upgrade-unreadable");
            return false;
        }
        Cost cost = nextCost(owner, crop);
        if (cost == null) {
            messages.send(player, "upgrade-maxed-message", placeholders(owner, crop));
            return false;
        }
        String[] before = placeholders(owner, crop);
        if (!currency.canAfford(player, cost)) {
            messages.send(player, "upgrade-cannot-afford", before);
            return false;
        }
        if (!currency.charge(player, cost)) {
            messages.send(player, "payment-failed");
            return false;
        }
        int reached = level(owner, crop) + 1;
        if (!store.set(owner, crop.id(), reached)) {
            currency.refund(player, cost);
            messages.send(player, "payment-refunded");
            return false;
        }
        changed.accept(player);
        String[] after = append(placeholders(owner, crop), "%cost%", display(cost));
        messages.send(player, "upgrade-bought", after);
        String sound = config.upgradeSound();
        if (sound != null && !sound.isBlank()) {
            player.playSound(player.getLocation(), sound.trim().toLowerCase(Locale.ROOT), 1f, 1f);
        }
        xp.give(owner, config.gardenXp(reached));
        runRewards(player, reached, after);
        return true;
    }

    /** Set a level outright (admin). Pays and charges nothing. */
    public boolean set(UUID owner, MilestoneCrop crop, int level) {
        boolean done = store.set(owner, crop.id(), Math.clamp(level, 0, config.maxLevel(crop.id())));
        Player online = Bukkit.getPlayer(owner);
        if (done && online != null) {
            changed.accept(online);
        }
        return done;
    }

    private void runRewards(Player player, int level, String[] placeholders) {
        ConfigurationSection rewards = config.rewards();
        if (rewards == null) {
            return;
        }
        List<String> commands = new ArrayList<>(rewards.getStringList("every-level.commands"));
        List<String> lines = new ArrayList<>(rewards.getStringList("every-level.messages"));
        commands.addAll(rewards.getStringList("by-level." + level + ".commands"));
        lines.addAll(rewards.getStringList("by-level." + level + ".messages"));
        for (String command : commands) {
            String filled = Messages.fill(command, placeholders);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled.startsWith("/") ? filled.substring(1) : filled);
        }
        for (String line : lines) {
            player.sendMessage(Text.color(Messages.fill(line, placeholders)));
        }
    }

    /**
     * What upgrade messages, menus and rewards can use: {@code %player% %uuid% %crop% %crop_name% %level%
     * %level_roman% %max_level% %value% %next_value% %next_cost%} — {@code %value%} being
     * {@code level × value-per-level}, the admin's description of what the effects do.
     */
    public String[] placeholders(UUID owner, MilestoneCrop crop) {
        int level = level(owner, crop);
        int max = config.maxLevel(crop.id());
        double perLevel = config.valuePerLevel(crop.id());
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        return new String[]{
                "%player%", player.getName() == null ? owner.toString() : player.getName(),
                "%uuid%", owner.toString(),
                "%crop%", crop.id(),
                "%crop_name%", crop.displayName(),
                "%level%", String.valueOf(level),
                "%level_roman%", Tiers.roman(level),
                "%max_level%", String.valueOf(max),
                "%value%", number(level * perLevel),
                "%next_value%", number(Math.min(level + 1, max) * perLevel),
                "%next_cost%", display(nextCost(owner, crop))};
    }

    /** {@code 5} rather than {@code 5.0}; decimals only when there are some. */
    static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /**
     * {@code %royalgarden_upgrade_<crop>_level%} and {@code %royalgarden_upgrade_<crop>_value%}, or
     * null if the key is not one of these. Offline players are read without being cached.
     */
    public String resolve(OfflinePlayer player, String key) {
        String params = key.toLowerCase(Locale.ROOT);
        if (!params.startsWith("upgrade_")) {
            return null;
        }
        boolean level = params.endsWith("_level");
        if (!level && !params.endsWith("_value")) {
            return null;
        }
        String id = params.substring("upgrade_".length(), params.lastIndexOf('_'));
        MilestoneCrop crop = crop(id);
        if (crop == null) {
            return null;
        }
        int value = Math.min(store.peek(player.getUniqueId(), crop.id()), config.maxLevel(crop.id()));
        return level ? String.valueOf(value) : number(value * config.valuePerLevel(crop.id()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        store.unload(event.getPlayer().getUniqueId());
    }

    private static String[] append(String[] base, String... more) {
        String[] out = new String[base.length + more.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }
}
