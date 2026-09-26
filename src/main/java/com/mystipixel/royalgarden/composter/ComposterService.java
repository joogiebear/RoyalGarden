package com.mystipixel.royalgarden.composter;

import com.mystipixel.royalgarden.composter.ComposterConfig.Source;
import com.mystipixel.royalgarden.composter.ComposterConfig.Upgrade;
import com.mystipixel.royalgarden.composter.ComposterMath.Machine;
import com.mystipixel.royalgarden.composter.ComposterMath.Status;
import com.mystipixel.royalgarden.config.Durations;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.milestone.Tiers;
import com.mystipixel.royalgarden.visitor.VisitorService;
import com.mystipixel.royalskyblock.currency.Cost;
import com.mystipixel.royalskyblock.currency.CurrencyService;
import com.mystipixel.royalskyblock.util.Text;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * The Composter: feeding it, catching it up, collecting Compost, and its upgrades.
 *
 * <p>It turns organic matter and fuel into Compost over time (see {@link ComposterMath}), whether or not
 * its owner is online or their Garden is loaded: nothing ticks for an absent player, and whoever next
 * touches their Composter — the player, a menu, an admin command — catches it up from {@code last-tick}
 * first. Online players are also caught up on a timer, so they hear when it stops.
 *
 * <p>Upgrades are machine properties bought with any host currency (Copper by default): speed, a chance
 * of bonus Compost, bigger matter and fuel tanks, and cheaper Compost. They change how the machine runs,
 * not how good the player is at anything, so they are plain config rather than libreforge effects.
 */
public final class ComposterService implements Listener {

    /** Pays Garden XP: owner, amount. */
    public interface XpSink {
        void give(UUID owner, long amount);
    }

    public enum Kind { MATTER, FUEL }

    private final ComposterConfig config;
    private final ComposterStore store;
    private final Messages messages;
    private final CurrencyService currency;
    private final Logger logger;
    private XpSink xp = (owner, amount) -> { };

    public ComposterService(ComposterConfig config, ComposterStore store, Messages messages, CurrencyService currency,
                            Logger logger) {
        this.config = config;
        this.store = store;
        this.messages = messages;
        this.currency = currency;
        this.logger = logger;
    }

    public ComposterConfig config() {
        return config;
    }

    public ComposterStore store() {
        return store;
    }

    public void setXp(XpSink xp) {
        this.xp = xp == null ? (owner, amount) -> { } : xp;
    }

    // ── the machine as upgraded ────────────────────────────────────────────────

    public int level(ComposterStore.Data data, Upgrade upgrade) {
        return Math.min(data.level(upgrade.key()), config.maxLevel(upgrade));
    }

    /** What an upgrade adds at a level: percent, or an amount for the caps. */
    public double value(Upgrade upgrade, int level) {
        return config.perLevel(upgrade) * level;
    }

    public Machine machine(ComposterStore.Data data) {
        double reduction = value(Upgrade.COST_REDUCTION, level(data, Upgrade.COST_REDUCTION));
        return new Machine(
                ComposterMath.interval(config.baseInterval(), value(Upgrade.SPEED, level(data, Upgrade.SPEED))),
                ComposterMath.reduced(config.matterPerCompost(), reduction),
                ComposterMath.reduced(config.fuelPerCompost(), reduction),
                config.maxStored(),
                value(Upgrade.MULTI_DROP, level(data, Upgrade.MULTI_DROP)) / 100.0);
    }

    public double matterCap(ComposterStore.Data data) {
        return config.baseMatterCap() + value(Upgrade.MATTER_CAP, level(data, Upgrade.MATTER_CAP));
    }

    public double fuelCap(ComposterStore.Data data) {
        return config.baseFuelCap() + value(Upgrade.FUEL_CAP, level(data, Upgrade.FUEL_CAP));
    }

    // ── time ───────────────────────────────────────────────────────────────────

    /** Catch every online player's Composter up. Runs on a timer. */
    public void tickAll() {
        if (!config.enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player.getUniqueId());
        }
    }

    /**
     * Run a Composter up to now. Saves only when Compost was made or its clock was started: otherwise the
     * saved state and the saved {@code last-tick} still add up to the same thing (see {@link ComposterMath}).
     */
    public void update(UUID owner) {
        if (!config.enabled() || store.readOnly(owner)) {
            return;
        }
        ComposterStore.Data data = store.get(owner);
        long now = System.currentTimeMillis();
        if (data.lastTick() <= 0) {
            data.setLastTick(now);
            store.save(owner);
            return;
        }
        if (now <= data.lastTick()) {
            return;                                  // the clock went backwards; wait for it
        }
        Machine machine = machine(data);
        Status before = ComposterMath.status(data.state(), machine);
        ComposterMath.Result result = ComposterMath.advance(data.state(), machine, now - data.lastTick(),
                ThreadLocalRandom.current());
        data.apply(result.state());
        data.setLastTick(now);
        if (result.made() <= 0) {
            return;
        }
        data.addMade(result.made() + result.bonus());
        store.save(owner);
        Status after = ComposterMath.status(result.state(), machine);
        Player online = Bukkit.getPlayer(owner);
        if (online != null && before == Status.RUNNING && after != Status.RUNNING && config.notifyStopped()) {
            messages.send(online, "composter-stopped-" + after.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    placeholders(owner));
        }
    }

    // ── putting things in ──────────────────────────────────────────────────────

    /**
     * Feed the Composter from the player's inventory: the first stack that counts, or with {@code all}
     * everything that counts, up to its capacity. Returns whether anything went in.
     */
    public boolean insert(Player player, Kind kind, boolean all) {
        UUID owner = player.getUniqueId();
        if (!usable(player)) {
            return false;
        }
        update(owner);
        ComposterStore.Data data = store.get(owner);
        List<Source> sources = kind == Kind.MATTER ? config.matterSources() : config.fuelSources();
        double cap = kind == Kind.MATTER ? matterCap(data) : fuelCap(data);
        double before = kind == Kind.MATTER ? data.matter() : data.fuel();
        String name = messages.raw(kind == Kind.MATTER ? "composter-matter-name" : "composter-fuel-name");

        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        int[] take = new int[contents.length];
        double added = 0;
        long items = 0;
        boolean anyMatch = false;
        for (int i = 0; i < contents.length; i++) {
            Source source = ComposterConfig.match(sources, contents[i]);
            if (source == null) {
                continue;
            }
            anyMatch = true;
            long fits = ComposterMath.fits(before + added, cap, source.value());
            int taken = (int) Math.min(contents[i].getAmount(), fits);
            if (taken <= 0) {
                continue;
            }
            take[i] = taken;
            added += taken * source.value();
            items += taken;
            if (!all) {
                break;
            }
        }
        if (items == 0) {
            messages.send(player, anyMatch ? "composter-tank-full" : "composter-nothing-to-insert", "%tank%", name);
            return false;
        }
        // Save first and move the items only once it is on disk; a failed save changes nothing.
        setTank(data, kind, before + added);
        if (!store.save(owner)) {
            setTank(data, kind, before);
            messages.send(player, "composter-save-failed");
            return false;
        }
        for (int i = 0; i < contents.length; i++) {
            if (take[i] <= 0) {
                continue;
            }
            if (take[i] >= contents[i].getAmount()) {
                contents[i] = null;
            } else {
                contents[i].setAmount(contents[i].getAmount() - take[i]);
            }
        }
        inventory.setStorageContents(contents);
        messages.send(player, "composter-inserted", append(placeholders(owner), "%tank%", name,
                "%items%", String.format("%,d", items), "%added%", number(added)));
        sound(player, config.insertSound());
        return true;
    }

    private static void setTank(ComposterStore.Data data, Kind kind, double value) {
        if (kind == Kind.MATTER) {
            data.setMatter(value);
        } else {
            data.setFuel(value);
        }
    }

    private boolean usable(Player player) {
        if (!config.enabled()) {
            messages.send(player, "composter-disabled");
            return false;
        }
        if (store.readOnly(player.getUniqueId())) {
            messages.send(player, "composter-unreadable");
            return false;
        }
        return true;
    }

    // ── taking Compost out ─────────────────────────────────────────────────────

    /** Give the player the Compost waiting for them, as much as their inventory holds. */
    public boolean collect(Player player) {
        UUID owner = player.getUniqueId();
        if (!usable(player)) {
            return false;
        }
        update(owner);
        ComposterStore.Data data = store.get(owner);
        long stored = data.stored();
        if (stored <= 0) {
            messages.send(player, "composter-nothing-to-collect", placeholders(owner));
            return false;
        }
        String itemId = config.outputItem();
        TestableItem output = null;
        if (itemId != null && !itemId.isBlank()) {
            output = VisitorService.lookup(itemId.trim());
            if (output == null) {
                logger.warning("composter.yml output.item '" + itemId + "' is not an item on this server.");
                messages.send(player, "composter-bad-output", "%item%", itemId);
                return false;
            }
        }
        long amount = output == null ? stored : Math.min(stored, space(player.getInventory(), output.getItem()));
        if (amount <= 0) {
            messages.send(player, "composter-inventory-full");
            return false;
        }
        long collectedBefore = data.collected();
        data.setStored(stored - amount);
        data.setCollected(collectedBefore + amount);
        if (!store.save(owner)) {
            data.setStored(stored);
            data.setCollected(collectedBefore);
            messages.send(player, "composter-save-failed");
            return false;
        }
        if (output != null) {
            give(player, output.getItem(), amount);
        }
        String[] placeholders = append(placeholders(owner), "%amount%", String.format("%,d", amount),
                "%amount_raw%", String.valueOf(amount));
        for (String command : config.outputCommands()) {
            dispatch(Messages.fill(command, placeholders));
        }
        long gardenXp = Math.round(config.gardenXpPerCompost() * amount);
        if (gardenXp > 0) {
            xp.give(owner, gardenXp);
        }
        runRewards(player, config.rewards() == null ? null : config.rewards().getConfigurationSection("collect"),
                placeholders);
        messages.send(player, amount < stored ? "composter-collected-some" : "composter-collected", placeholders);
        sound(player, config.collectSound());
        return true;
    }

    /** How many of {@code item} the inventory's storage can take. */
    static long space(PlayerInventory inventory, ItemStack item) {
        int max = Math.max(1, item.getMaxStackSize());
        long room = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) {
                room += max;
            } else if (stack.isSimilar(item)) {
                room += Math.max(0, max - stack.getAmount());
            }
        }
        return room;
    }

    /** Hand over {@code amount} copies in whole stacks; anything that does not fit drops at their feet. */
    private static void give(Player player, ItemStack item, long amount) {
        int max = Math.max(1, item.getMaxStackSize());
        long left = amount;
        while (left > 0) {
            ItemStack stack = item.clone();
            int size = (int) Math.min(max, left);
            stack.setAmount(size);
            left -= size;
            Map<Integer, ItemStack> over = player.getInventory().addItem(stack);
            for (ItemStack dropped : over.values()) {
                player.getWorld().dropItem(player.getLocation(), dropped);
            }
        }
    }

    // ── upgrades ───────────────────────────────────────────────────────────────

    /** What the next level of an upgrade costs, or null at the top. */
    public Cost nextCost(ComposterStore.Data data, Upgrade upgrade) {
        List<Cost> costs = config.costs(upgrade);
        int level = level(data, upgrade);
        return config.upgradeEnabled(upgrade) && level < costs.size() ? costs.get(level) : null;
    }

    public String display(Cost cost) {
        return cost == null ? messages.raw("composter-upgrade-maxed")
                : cost.isFree() ? messages.raw("cost-free") : currency.format(cost);
    }

    /** Buy the next level of an upgrade. Charge, then save, then refund if the save failed. */
    public boolean buy(Player player, Upgrade upgrade) {
        UUID owner = player.getUniqueId();
        if (!usable(player)) {
            return false;
        }
        // Catch up at the old speed and cost before the new level changes them.
        update(owner);
        ComposterStore.Data data = store.get(owner);
        Cost cost = nextCost(data, upgrade);
        String[] before = upgradePlaceholders(owner, upgrade);
        if (cost == null) {
            messages.send(player, "composter-upgrade-maxed-message", before);
            return false;
        }
        if (!currency.canAfford(player, cost)) {
            messages.send(player, "composter-upgrade-cannot-afford", before);
            return false;
        }
        if (!currency.charge(player, cost)) {
            messages.send(player, "payment-failed");
            return false;
        }
        int was = data.level(upgrade.key());
        int reached = level(data, upgrade) + 1;
        data.setLevel(upgrade.key(), reached);
        if (!store.save(owner)) {
            data.setLevel(upgrade.key(), was);
            currency.refund(player, cost);
            messages.send(player, "payment-refunded");
            return false;
        }
        String[] after = append(upgradePlaceholders(owner, upgrade), "%cost%", display(cost));
        messages.send(player, "composter-upgrade-bought", after);
        sound(player, config.upgradeSound());
        xp.give(owner, config.upgradeGardenXp(reached));
        ConfigurationSection rewards = config.rewards();
        runRewards(player, rewards == null ? null : rewards.getConfigurationSection("upgrade"), after);
        return true;
    }

    // ── admin ──────────────────────────────────────────────────────────────────

    /** Add organic matter or fuel outright (admin), up to capacity. Returns what was added, or -1. */
    public double give(UUID owner, Kind kind, double amount) {
        if (store.readOnly(owner)) {
            return -1;
        }
        update(owner);
        ComposterStore.Data data = store.get(owner);
        double before = kind == Kind.MATTER ? data.matter() : data.fuel();
        double cap = kind == Kind.MATTER ? matterCap(data) : fuelCap(data);
        double after = Math.clamp(before + amount, 0, Math.max(before, cap));
        setTank(data, kind, after);
        if (!store.save(owner)) {
            setTank(data, kind, before);
            return -1;
        }
        return after - before;
    }

    /** Empty a Composter and forget its upgrades (admin). */
    public void reset(UUID owner) {
        store.reset(owner);
    }

    private void runRewards(Player player, ConfigurationSection layer, String[] placeholders) {
        if (layer == null) {
            return;
        }
        for (String command : layer.getStringList("commands")) {
            dispatch(Messages.fill(command, placeholders));
        }
        for (String line : layer.getStringList("messages")) {
            player.sendMessage(Text.color(Messages.fill(line, placeholders)));
        }
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

    // ── placeholders ───────────────────────────────────────────────────────────

    /**
     * What Composter messages, menus and rewards can use: {@code %player% %uuid% %matter% %matter_cap%
     * %matter_percent% %matter_bar% %fuel% %fuel_cap% %fuel_percent% %fuel_bar% %stored% %max_stored%
     * %status% %next% %time% %matter_cost% %fuel_cost% %bonus_chance% %made% %collected% %compost%}.
     * Read from the player's data as it stands; call {@link #update} first to show it caught up.
     */
    public String[] placeholders(UUID owner) {
        ComposterStore.Data data = store.get(owner);
        Machine machine = machine(data);
        ComposterMath.State state = data.state();
        double matterCap = matterCap(data);
        double fuelCap = fuelCap(data);
        long next = ComposterMath.untilNext(state, machine);
        return new String[]{
                "%player%", name(owner),
                "%uuid%", owner.toString(),
                "%matter%", number(state.matter()),
                "%matter_cap%", number(matterCap),
                "%matter_percent%", percent(state.matter(), matterCap),
                "%matter_bar%", bar(state.matter(), matterCap),
                "%fuel%", number(state.fuel()),
                "%fuel_cap%", number(fuelCap),
                "%fuel_percent%", percent(state.fuel(), fuelCap),
                "%fuel_bar%", bar(state.fuel(), fuelCap),
                "%stored%", String.format("%,d", state.stored()),
                "%max_stored%", machine.maxStored() > 0 ? String.format("%,d", machine.maxStored())
                        : messages.raw("composter-no-limit"),
                "%status%", messages.raw("composter-status-" + ComposterMath.status(state, machine).name()
                        .toLowerCase(Locale.ROOT).replace('_', '-')),
                "%next%", next < 0 ? messages.raw("composter-next-stopped") : Durations.format(next),
                "%time%", Durations.format(machine.interval()),
                "%matter_cost%", number(machine.matterCost()),
                "%fuel_cost%", number(machine.fuelCost()),
                "%bonus_chance%", number(machine.bonusChance() * 100),
                "%made%", String.format("%,d", data.made()),
                "%collected%", String.format("%,d", data.collected()),
                "%compost%", config.outputName()};
    }

    /**
     * An upgrade's placeholders, with the Composter's own: {@code %upgrade% %upgrade_name% %level%
     * %level_roman% %max_level% %value% %next_value% %next_cost%}.
     */
    public String[] upgradePlaceholders(UUID owner, Upgrade upgrade) {
        ComposterStore.Data data = store.get(owner);
        int level = level(data, upgrade);
        int max = config.maxLevel(upgrade);
        return append(placeholders(owner),
                "%upgrade%", upgrade.key(),
                "%upgrade_name%", config.upgradeName(upgrade),
                "%level%", String.valueOf(level),
                "%level_roman%", Tiers.roman(level),
                "%max_level%", String.valueOf(max),
                "%value%", number(value(upgrade, level)),
                "%next_value%", number(value(upgrade, Math.min(level + 1, max))),
                "%next_cost%", display(nextCost(data, upgrade)));
    }

    /**
     * {@code %royalgarden_composter_<key>%}, or null if the key is not one of these: {@code matter},
     * {@code fuel}, {@code stored}, {@code next} (blank while stopped), {@code matter_cap},
     * {@code fuel_cap}, {@code made} and {@code <upgrade>_level}. Plain numbers for use in conditions.
     *
     * <p>Projected to now without saving anything or rolling bonus Compost, so an offline player's
     * Composter reads as running and nobody's file is written from a scoreboard thread.
     */
    public String resolve(OfflinePlayer player, String key) {
        String params = key.toLowerCase(Locale.ROOT);
        if (!params.startsWith("composter_")) {
            return null;
        }
        String what = params.substring("composter_".length());
        ComposterStore.Data data = store.peek(player.getUniqueId());
        Machine machine = machine(data);
        ComposterMath.State state = data.state();
        long now = System.currentTimeMillis();
        if (config.enabled() && data.lastTick() > 0 && now > data.lastTick()) {
            state = ComposterMath.advance(state, machine, now - data.lastTick(), null).state();
        }
        switch (what) {
            case "matter":
                return plain(state.matter());
            case "fuel":
                return plain(state.fuel());
            case "stored":
                return String.valueOf(state.stored());
            case "next": {
                long next = ComposterMath.untilNext(state, machine);
                return next < 0 ? "" : Durations.format(next);
            }
            case "matter_cap":
                return plain(matterCap(data));
            case "fuel_cap":
                return plain(fuelCap(data));
            case "made":
                return String.valueOf(data.made());
            default:
                break;
        }
        if (what.endsWith("_level")) {
            Upgrade upgrade = Upgrade.byKey(what.substring(0, what.length() - "_level".length()).replace('_', '-'));
            return upgrade == null ? null : String.valueOf(level(data, upgrade));
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        update(owner);
        store.unload(owner);
    }

    // ── formatting ─────────────────────────────────────────────────────────────

    private String bar(double value, double cap) {
        return Tiers.bar(cap <= 0 ? 0 : value / cap, config.barLength(), config.barFilled(), config.barEmpty());
    }

    private static String percent(double value, double cap) {
        return cap <= 0 ? "0" : String.valueOf((int) Math.floor(Math.clamp(value / cap, 0.0, 1.0) * 100));
    }

    /** {@code 1,234} or {@code 1,234.5}: grouped, one decimal only when there is one. */
    static String number(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.format("%,d", (long) rounded) : String.format("%,.1f", rounded);
    }

    /** {@code 1234} or {@code 1234.5}, for placeholders compared in conditions. */
    static String plain(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    private static String name(UUID owner) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        return player.getName() == null ? owner.toString() : player.getName();
    }

    private static String[] append(String[] base, String... more) {
        String[] out = new String[base.length + more.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }
}
