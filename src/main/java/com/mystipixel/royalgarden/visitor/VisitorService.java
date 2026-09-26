package com.mystipixel.royalgarden.visitor;

import com.mystipixel.royalgarden.config.Durations;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalskyblock.util.Text;
import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.items.TestableItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * Visitors: who arrives at a Garden and when, what they ask for, and paying them.
 *
 * <p>Visitors arrive one per {@code arrivals.interval} while their host is online, up to a queue of
 * {@code arrivals.max-queue} (which can grow with Garden level). Time offline counts at
 * {@code arrivals.offline-rate}. Each arrival is a visitor picked by weight from those the Garden's
 * level allows, with one of their requests picked by weight, its amounts scaled up by Garden level.
 *
 * <p>Accepting takes the items and pays the offer's Copper (through a console command, so the currency
 * lives in EcoBits and is spent in an EcoShop shop) and Garden XP, then every stacked reward:
 * {@code rewards.every-visit}, {@code rewards.first-visit} the first time a visitor is served,
 * {@code rewards.by-served.<n>} on the n-th visitor served, and the rarity's and the visitor's own
 * commands and messages.
 */
public final class VisitorService implements Listener {

    /** Pays Garden XP: owner, amount. */
    public interface XpSink {
        void give(UUID owner, long amount);
    }

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexCharacter('#').hexColors().build();

    private final VisitorConfig config;
    private final VisitorStore store;
    private final Messages messages;
    private final Logger logger;
    private ToIntFunction<UUID> level = owner -> 1;
    private XpSink xp = (owner, amount) -> { };
    private Predicate<UUID> hasGarden = owner -> true;
    private Consumer<UUID> changed = owner -> { };

    public VisitorService(VisitorConfig config, VisitorStore store, Messages messages, Logger logger) {
        this.config = config;
        this.store = store;
        this.messages = messages;
        this.logger = logger;
    }

    public VisitorConfig config() {
        return config;
    }

    public VisitorStore store() {
        return store;
    }

    /** How a Garden's level is read: it gates visitors, scales requests and sizes the queue. */
    public void setLevels(ToIntFunction<UUID> level, XpSink xp) {
        this.level = level;
        this.xp = xp;
    }

    /** Who counts as having a Garden, for {@code arrivals.require-garden}. */
    public void setHasGarden(Predicate<UUID> hasGarden) {
        this.hasGarden = hasGarden;
    }

    /** Told whenever a player's queue changes — how the NPCs at the barn stay in step. */
    public void onChange(Consumer<UUID> changed) {
        this.changed = changed == null ? owner -> { } : changed;
    }

    public int level(UUID owner) {
        return Math.max(1, level.applyAsInt(owner));
    }

    public int maxQueue(UUID owner) {
        return config.maxQueue(level(owner));
    }

    // ── arrivals ───────────────────────────────────────────────────────────────

    /** Check every online player for visitors that are due. Runs on a timer. */
    public void tickAll() {
        if (!config.enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            tick(player);
        }
    }

    /** Let in any visitors due for this player. */
    public void tick(Player player) {
        UUID owner = player.getUniqueId();
        if (!config.enabled() || (config.requireGarden() && !hasGarden.test(owner)) || store.readOnly(owner)) {
            return;
        }
        VisitorStore.Data data = store.get(owner);
        long now = System.currentTimeMillis();
        long interval = config.intervalMillis();
        if (data.lastArrival() == 0) {
            // A new player's clock starts so the first visitor comes after first-arrival, not interval.
            data.setLastArrival(now - interval + Math.min(interval, config.firstArrivalMillis()));
            store.save(owner);
        }
        int lvl = level(owner);
        VisitorMath.Due due = VisitorMath.due(data.lastArrival(), now, interval, data.queued(), config.maxQueue(lvl));
        if (due.count() == 0) {
            return;
        }
        data.setLastArrival(due.lastArrival());
        List<Offer> arrived = new ArrayList<>();
        for (int i = 0; i < due.count(); i++) {
            Offer offer = generate(player, data, lvl, ThreadLocalRandom.current());
            if (offer == null) {
                break;
            }
            data.add(offer);
            arrived.add(offer);
        }
        store.save(owner);
        if (arrived.isEmpty()) {
            return;
        }
        for (Offer offer : arrived) {
            messages.send(player, "visitor-arrived", placeholders(owner, offer));
        }
        playSound(player, config.arrivalSound());
        changed.accept(owner);
    }

    /**
     * Force a visitor to arrive (admin), ignoring the clock, the queue limit and level requirements.
     *
     * @param visitorId a visitor to send, or null for a random pick
     * @return the new offer, or null if there was no such visitor
     */
    public Offer summon(OfflinePlayer target, String visitorId) {
        UUID owner = target.getUniqueId();
        if (store.readOnly(owner)) {
            return null;
        }
        VisitorStore.Data data = store.get(owner);
        int lvl = level(owner);
        Offer offer;
        if (visitorId == null) {
            offer = generate(target.getPlayer(), data, lvl, ThreadLocalRandom.current());
        } else {
            VisitorType type = config.visitor(visitorId);
            offer = type == null ? null : offerFrom(type, data, lvl, ThreadLocalRandom.current());
        }
        if (offer == null) {
            return null;
        }
        data.add(offer);
        store.save(owner);
        Player online = target.getPlayer();
        if (online != null) {
            messages.send(online, "visitor-arrived", placeholders(owner, offer));
            playSound(online, config.arrivalSound());
        }
        changed.accept(owner);
        return offer;
    }

    /** Send every waiting visitor away without counting a refusal (admin, and {@code /garden reset}). */
    public void clear(UUID owner) {
        if (store.readOnly(owner)) {
            return;
        }
        store.get(owner).clearQueue();
        store.save(owner);
        changed.accept(owner);
    }

    /** Pick a visitor this player may receive, and roll their offer. Null if nobody is eligible. */
    Offer generate(Player player, VisitorStore.Data data, int lvl, RandomGenerator random) {
        List<VisitorType> eligible = new ArrayList<>();
        for (VisitorType type : config.visitors()) {
            if (type.gardenLevel() > lvl) {
                continue;
            }
            if (!type.permission().isBlank() && (player == null || !player.hasPermission(type.permission()))) {
                continue;
            }
            if (!config.allowDuplicates() && data.waiting(type.id())) {
                continue;
            }
            eligible.add(type);
        }
        VisitorType picked = VisitorMath.pickWeighted(eligible, VisitorType::weight, random.nextDouble());
        return picked == null ? null : offerFrom(picked, data, lvl, random);
    }

    private Offer offerFrom(VisitorType type, VisitorStore.Data data, int lvl, RandomGenerator random) {
        VisitorType.Request request = VisitorMath.pickWeighted(type.requests(), VisitorType.Request::weight,
                random.nextDouble());
        if (request == null) {
            request = type.requests().getFirst();
        }
        double multiplier = VisitorMath.multiplier(lvl, config.scalePerLevel(), config.scaleMax());
        List<Offer.Wanted> items = new ArrayList<>();
        for (VisitorType.Item item : request.items()) {
            items.add(new Offer.Wanted(item.item(), VisitorMath.scale(item.amount().roll(random), multiplier),
                    item.name()));
        }
        VisitorConfig.Rarity rarity = config.rarity(type.rarity());
        String where = "visitors." + type.id();
        VisitorMath.Range copper = config.range(type.rewards(), "copper", where)
                .plus(rarity == null ? null : rarity.copper());
        VisitorMath.Range gardenXp = config.range(type.rewards(), "garden-xp", where)
                .plus(rarity == null ? null : rarity.xp());
        double rewardScale = config.scaleRewards() ? multiplier : 1.0;
        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, 8);
        } while (data.offer(id) != null);
        return new Offer(id, type.id(), List.copyOf(items), VisitorMath.scale(copper.roll(random), rewardScale),
                VisitorMath.scale(gardenXp.roll(random), rewardScale), System.currentTimeMillis());
    }

    /** Milliseconds until the next visitor, or -1 while the queue is full. */
    public long untilNext(UUID owner) {
        return untilNext(owner, store.get(owner));
    }

    /** {@link #untilNext(UUID)} for data already in hand. */
    public long untilNext(UUID owner, VisitorStore.Data data) {
        if (data.queued() >= maxQueue(owner)) {
            return -1;
        }
        if (data.lastArrival() == 0) {
            return config.firstArrivalMillis();
        }
        return Math.max(0, data.lastArrival() + config.intervalMillis() - System.currentTimeMillis());
    }

    // ── joining and leaving ────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        welcome(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        if (!store.readOnly(owner)) {
            store.get(owner).setLastSeen(System.currentTimeMillis());
        }
        store.unload(owner);
    }

    /**
     * Credit time away to a player who has arrived (or who was online across an extension reload): the
     * part of it {@code offline-rate} does not count pushes their clock back.
     */
    public void welcome(Player player) {
        UUID owner = player.getUniqueId();
        if (store.readOnly(owner)) {
            return;
        }
        VisitorStore.Data data = store.get(owner);
        if (data.lastSeen() <= 0) {
            return;
        }
        if (data.lastArrival() > 0) {
            data.setLastArrival(VisitorMath.creditOffline(data.lastArrival(), data.lastSeen(),
                    System.currentTimeMillis(), config.offlineRate()));
        }
        data.setLastSeen(0);
        store.save(owner);
    }

    /** On shutdown: everyone online is about to be "away", so their downtime counts as offline. */
    public void shutdown() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID owner = player.getUniqueId();
            if (!store.readOnly(owner)) {
                store.get(owner).setLastSeen(now);
            }
        }
        store.saveAll();
    }

    // ── serving ────────────────────────────────────────────────────────────────

    /** Accept an offer: take the items and pay. Returns whether it went through. */
    public boolean accept(Player player, String offerId) {
        UUID owner = player.getUniqueId();
        if (store.readOnly(owner)) {
            messages.send(player, "visitor-unreadable");
            return false;
        }
        VisitorStore.Data data = store.get(owner);
        Offer offer = data.offer(offerId);
        if (offer == null) {
            messages.send(player, "visitor-gone");
            return false;
        }
        List<TestableItem> lookups = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Offer.Wanted wanted : offer.items()) {
            TestableItem item = lookup(wanted.item());
            if (item == null) {
                logger.warning("Visitor '" + offer.visitor() + "' wants '" + wanted.item()
                        + "', which is not an item on this server — check visitors.yml.");
                messages.send(player, "visitor-bad-item", "%item%", wanted.item());
                return false;
            }
            lookups.add(item);
            long have = count(player.getInventory(), item);
            if (have < wanted.amount()) {
                missing.add(messages.raw("visitor-missing-line", "%item%", itemName(wanted),
                        "%amount%", String.format("%,d", wanted.amount() - have)));
            }
        }
        if (!missing.isEmpty()) {
            messages.send(player, "visitor-missing", "%items%", String.join(messages.raw("visitor-missing-separator"),
                    missing));
            return false;
        }
        for (int i = 0; i < lookups.size(); i++) {
            take(player.getInventory(), lookups.get(i), offer.items().get(i).amount());
        }

        boolean wasFull = data.queued() >= maxQueue(owner);
        data.remove(offer.id());
        data.recordServed(offer.visitor(), config.copperEnabled() ? offer.copper() : 0);
        if (wasFull) {
            data.setLastArrival(System.currentTimeMillis());      // the clock was stopped; restart it
        }
        store.save(owner);

        String[] placeholders = placeholders(owner, offer);
        payCopper(player, offer.copper());
        if (offer.xp() > 0) {
            xp.give(owner, offer.xp());
        }
        runRewards(player, offer, data, placeholders);
        messages.send(player, "visitor-accepted", placeholders);
        playSound(player, config.acceptSound());
        changed.accept(owner);
        return true;
    }

    /** Refuse an offer: the visitor leaves with nothing. */
    public boolean refuse(Player player, String offerId) {
        UUID owner = player.getUniqueId();
        if (store.readOnly(owner)) {
            messages.send(player, "visitor-unreadable");
            return false;
        }
        VisitorStore.Data data = store.get(owner);
        Offer offer = data.offer(offerId);
        if (offer == null) {
            messages.send(player, "visitor-gone");
            return false;
        }
        boolean wasFull = data.queued() >= maxQueue(owner);
        data.remove(offer.id());
        data.recordRefused();
        if (wasFull) {
            data.setLastArrival(System.currentTimeMillis());
        }
        store.save(owner);
        messages.send(player, "visitor-refused", placeholders(owner, offer));
        playSound(player, config.refuseSound());
        changed.accept(owner);
        return true;
    }

    private void payCopper(Player player, long amount) {
        if (!config.copperEnabled() || amount <= 0) {
            return;
        }
        dispatch(Messages.fill(config.copperCommand(), "%player%", player.getName(),
                "%uuid%", player.getUniqueId().toString(), "%amount%", String.valueOf(amount),
                "%currency%", config.copperCurrency()));
    }

    private void runRewards(Player player, Offer offer, VisitorStore.Data data, String[] placeholders) {
        List<String> commands = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        ConfigurationSection global = config.rewards();
        if (global != null) {
            add(global.getConfigurationSection("every-visit"), commands, lines);
            if (data.served(offer.visitor()) == 1) {
                add(global.getConfigurationSection("first-visit"), commands, lines);
            }
            add(global.getConfigurationSection("by-served." + data.served()), commands, lines);
        }
        VisitorType type = config.visitor(offer.visitor());
        if (type != null) {
            VisitorConfig.Rarity rarity = config.rarity(type.rarity());
            add(rarity == null ? null : rarity.rewards(), commands, lines);
            add(type.rewards(), commands, lines);
        }
        for (String command : commands) {
            dispatch(Messages.fill(command, placeholders));
        }
        for (String line : lines) {
            player.sendMessage(Text.color(Messages.fill(line, placeholders)));
        }
    }

    private static void add(ConfigurationSection layer, List<String> commands, List<String> lines) {
        if (layer != null) {
            commands.addAll(layer.getStringList("commands"));
            lines.addAll(layer.getStringList("messages"));
        }
    }

    private static void dispatch(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.startsWith("/") ? command.substring(1) : command);
    }

    private static void playSound(Player player, String sound) {
        if (sound != null && !sound.isBlank()) {
            player.playSound(player.getLocation(), sound.trim().toLowerCase(Locale.ROOT), 1f, 1f);
        }
    }

    // ── items ──────────────────────────────────────────────────────────────────

    /**
     * An eco item lookup ({@code wheat}, {@code ecoitems:enchanted_wheat}), or null if there is none. A
     * lookup that matches nothing comes back as an empty item that is air.
     */
    public static TestableItem lookup(String id) {
        TestableItem item = Items.lookup(id);
        if (item == null) {
            return null;
        }
        ItemStack stack = item.getItem();
        return stack == null || stack.getType().isAir() ? null : item;
    }

    /** How many matching items the inventory holds. */
    public static long count(PlayerInventory inventory, TestableItem item) {
        long total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack != null && !stack.getType().isAir() && item.matches(stack)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** Remove {@code amount} matching items. Only call once {@link #count} says there are enough. */
    private static void take(PlayerInventory inventory, TestableItem item, long amount) {
        ItemStack[] contents = inventory.getStorageContents();
        long left = amount;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir() || !item.matches(stack)) {
                continue;
            }
            int used = (int) Math.min(stack.getAmount(), left);
            left -= used;
            if (used == stack.getAmount()) {
                contents[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - used);
            }
        }
        inventory.setStorageContents(contents);
    }

    /** What to call a wanted item: its configured name, else the item's own name. */
    public String itemName(Offer.Wanted wanted) {
        if (wanted.name() != null && !wanted.name().isBlank()) {
            return wanted.name();
        }
        TestableItem item = lookup(wanted.item());
        return item == null ? wanted.item() : itemName(item.getItem());
    }

    /** An item's display name as {@code &} text: its custom name, item name, or the material prettified. */
    public static String itemName(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            Component name = meta.hasDisplayName() ? meta.displayName() : meta.hasItemName() ? meta.itemName() : null;
            if (name != null) {
                return LEGACY.serialize(name);
            }
        }
        return pretty(stack.getType());
    }

    private static String pretty(Material material) {
        StringBuilder out = new StringBuilder();
        for (String word : material.name().toLowerCase(Locale.ROOT).split("_")) {
            if (!word.isEmpty()) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }

    // ── placeholders ───────────────────────────────────────────────────────────

    /**
     * What visitor messages, menus and rewards can use: {@code %player% %uuid% %visitor% %visitor_name%
     * %rarity% %rarity_name% %copper% %xp% %currency% %served% %visitor_served% %unique% %waiting%}.
     */
    public String[] placeholders(UUID owner, Offer offer) {
        VisitorType type = offer == null ? null : config.visitor(offer.visitor());
        String id = offer == null ? "" : offer.visitor();
        return new String[]{
                "%copper%", offer == null ? "0" : String.format("%,d", offer.copper()),
                "%xp%", offer == null ? "0" : String.format("%,d", offer.xp()),
                // The rest, with the visitor's id standing in for a visitor since removed from the config.
                "%visitor%", id,
                "%visitor_name%", type != null ? type.displayName() : id,
                "%rarity%", type == null ? "" : type.rarity(),
                "%rarity_name%", rarityName(type),
                "%visitor_served%", String.format("%,d", store.get(owner).served(id)),
                "%player%", name(owner),
                "%uuid%", owner.toString(),
                "%currency%", config.copperCurrency(),
                "%served%", String.format("%,d", store.get(owner).served()),
                "%unique%", String.valueOf(store.get(owner).unique()),
                "%waiting%", String.valueOf(store.get(owner).queued())};
    }

    /**
     * Placeholders for one visitor rather than one offer — the Visitor Log: {@code %visitor%
     * %visitor_name% %rarity% %rarity_name% %visitor_served% %garden_level%}.
     */
    public String[] placeholders(UUID owner, VisitorType type) {
        return new String[]{
                "%visitor%", type.id(),
                "%visitor_name%", type.displayName(),
                "%rarity%", type.rarity(),
                "%rarity_name%", rarityName(type),
                "%visitor_served%", String.format("%,d", store.get(owner).served(type.id())),
                "%garden_level%", String.valueOf(Math.max(1, type.gardenLevel())),
                "%player%", name(owner)};
    }

    private String rarityName(VisitorType type) {
        if (type == null) {
            return "";
        }
        VisitorConfig.Rarity rarity = config.rarity(type.rarity());
        return rarity != null ? rarity.displayName() : type.rarity();
    }

    private static String name(UUID owner) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        return player.getName() == null ? owner.toString() : player.getName();
    }

    /** Placeholders for the Visitors page's stats: counts, the queue, and the next arrival. */
    public String[] statPlaceholders(UUID owner) {
        VisitorStore.Data data = store.get(owner);
        long next = untilNext(owner);
        return new String[]{
                "%served%", String.format("%,d", data.served()),
                "%refused%", String.format("%,d", data.refused()),
                "%unique%", String.valueOf(data.unique()),
                "%total_visitors%", String.valueOf(config.visitors().size()),
                "%copper_earned%", String.format("%,d", data.copper()),
                "%waiting%", String.valueOf(data.queued()),
                "%max_queue%", String.valueOf(maxQueue(owner)),
                "%next%", next < 0 ? messages.raw("visitor-queue-full") : Durations.format(next)};
    }
}
