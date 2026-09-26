package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.config.Placeholders;
import com.mystipixel.royalskyblock.currency.Cost;
import com.mystipixel.royalskyblock.currency.CurrencyService;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Buying a plot: what it costs, who may, and what they get for it — all from {@code unlock:} in
 * {@code config.yml}.
 *
 * <h2>Which terms apply</h2>
 *
 * <p>Three layers, most specific first, resolved per setting:
 *
 * <ol>
 *   <li>{@code by-plot.<name>} — a particular plot, e.g. the corners costing more;</li>
 *   <li>{@code by-count.<n>} — the n-th plot bought, farmhouse not counted, for a price curve or
 *       milestone rewards;</li>
 *   <li>{@code default} — everything else, with an optional {@code cost-scaling} multiplier applied
 *       per plot already bought.</li>
 * </ol>
 *
 * <p>So {@code by-count.24} can add a reward for finishing the board without restating the price, and
 * {@code by-plot.A1} can reprice one corner without losing its milestone reward.
 *
 * <p>Costs are charged through RoyalSkyblock's own {@link CurrencyService}, so every currency the host
 * knows — Vault coins, EcoBits gems, anything with a placeholder and a take command — works here with
 * no setup of its own.
 */
public final class UnlockService {

    /** A requirement read through PlaceholderAPI: {@code placeholder} must be at least {@code min}. */
    public record Requirement(String placeholder, double min, String message) {
    }

    /** Everything that applies to one purchase. */
    public record Terms(Cost cost, List<Requirement> requirements, List<String> commands, List<String> messages) {
    }

    private final GardenConfig config;
    private final Messages messages;
    private final CurrencyService currency;
    private final GardenWorlds worlds;
    private final Logger logger;

    public UnlockService(GardenConfig config, Messages messages, CurrencyService currency,
                         GardenWorlds worlds, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.currency = currency;
        this.worlds = worlds;
        this.logger = logger;
    }

    // ── terms ──────────────────────────────────────────────────────────────────

    /** The terms for buying this plot as the player's {@code purchase}-th, counting from 1. */
    public Terms terms(int px, int pz, int purchase) {
        ConfigurationSection root = config.yaml().getConfigurationSection("unlock");
        ConfigurationSection byPlot = section(root, "by-plot." + GardenLayout.plotName(px, pz));
        ConfigurationSection byCount = section(root, "by-count." + purchase);
        ConfigurationSection fallback = section(root, "default");

        Cost cost;
        ConfigurationSection costFrom = firstWith("cost", byPlot, byCount);
        if (costFrom != null) {
            cost = GardenConfig.parseCost(costFrom, "cost", logger);
        } else {
            Cost base = GardenConfig.parseCost(fallback, "cost", logger);
            double scaling = fallback == null ? 1.0 : fallback.getDouble("cost-scaling", 1.0);
            double amount = base.amount() * Math.pow(scaling, Math.max(0, purchase - 1));
            cost = new Cost(base.currency(), Math.round(amount * 100.0) / 100.0);
        }

        ConfigurationSection reqFrom = firstWith("requirements", byPlot, byCount, fallback);
        List<Requirement> requirements = reqFrom == null ? List.of() : requirements(reqFrom);

        // Rewards add up rather than override: a milestone reward on the 10th plot should come on top
        // of whatever every plot gives, not replace it.
        List<String> commands = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (ConfigurationSection layer : new ConfigurationSection[]{fallback, byCount, byPlot}) {
            if (layer != null) {
                commands.addAll(layer.getStringList("rewards.commands"));
                lines.addAll(layer.getStringList("rewards.messages"));
            }
        }
        return new Terms(cost, requirements, commands, lines);
    }

    private static ConfigurationSection section(ConfigurationSection root, String path) {
        return root == null ? null : root.getConfigurationSection(path);
    }

    private static ConfigurationSection firstWith(String key, ConfigurationSection... layers) {
        for (ConfigurationSection layer : layers) {
            if (layer != null && layer.contains(key)) {
                return layer;
            }
        }
        return null;
    }

    private List<Requirement> requirements(ConfigurationSection from) {
        List<Requirement> out = new ArrayList<>();
        for (Map<?, ?> raw : from.getMapList("requirements")) {
            Object placeholder = raw.get("placeholder");
            Object min = raw.get("min");
            if (placeholder == null || !(min instanceof Number number)) {
                logger.warning(from.getCurrentPath() + ".requirements: each entry needs a placeholder "
                        + "and a numeric min; skipping " + raw + ".");
                continue;
            }
            Object message = raw.get("message");
            out.add(new Requirement(placeholder.toString(), number.doubleValue(),
                    message == null ? "" : message.toString()));
        }
        return out;
    }

    /** How a cost reads in a menu or message. */
    public String display(Cost cost) {
        return cost.isFree() ? messages.raw("cost-free") : currency.format(cost);
    }

    /**
     * The most plots, farmhouse included, this player may own: {@code unlock.max-plots}, raised by the
     * highest {@code royalgarden.limit.<n>} permission they have.
     */
    public int limit(Player player) {
        int limit = config.yaml().getInt("unlock.max-plots", GardenLayout.plotCount());
        String prefix = "royalgarden.limit.";
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String node = info.getPermission();
            if (info.getValue() && node.startsWith(prefix)) {
                try {
                    limit = Math.max(limit, Integer.parseInt(node.substring(prefix.length())));
                } catch (NumberFormatException notANumber) {
                    // royalgarden.limit.* or a typo — not a limit
                }
            }
        }
        return Math.min(limit, GardenLayout.plotCount());
    }

    /** The first requirement this player does not meet, or null if they meet them all. */
    public Requirement unmet(Player player, Terms terms) {
        for (Requirement requirement : terms.requirements()) {
            double value = Placeholders.number(player, requirement.placeholder());
            if (Double.isNaN(value) || value < requirement.min()) {
                return requirement;
            }
        }
        return null;
    }

    // ── buying ─────────────────────────────────────────────────────────────────

    /**
     * Try to buy a plot for a player, telling them how it went. Returns true if it is now theirs.
     *
     * <p>Order matters: everything that can refuse without cost is checked before money moves, and the
     * charge is refunded if the unlock itself then fails — so a player can never pay for nothing.
     */
    public boolean attempt(Player player, int px, int pz) {
        GardenState state = worlds.state(player.getUniqueId());
        String plot = GardenLayout.plotName(px, pz);
        if (state.readOnly()) {
            messages.send(player, "garden-unreadable");
            return false;
        }
        if (state.isUnlocked(px, pz)) {
            return false;
        }
        if (!state.canUnlock(px, pz)) {
            messages.send(player, "unlock-not-adjacent", "%plot%", plot);
            return false;
        }
        int limit = limit(player);
        if (state.unlockedCount() >= limit) {
            messages.send(player, "unlock-limit-reached", "%limit%", String.valueOf(limit));
            return false;
        }

        int purchase = state.purchasedCount() + 1;
        Terms terms = terms(px, pz, purchase);
        Requirement unmet = unmet(player, terms);
        if (unmet != null) {
            String current = Placeholders.apply(player, unmet.placeholder());
            String text = unmet.message().isEmpty() ? messages.raw("unlock-requirement") : unmet.message();
            player.sendMessage(com.mystipixel.royalskyblock.util.Text.color(Messages.fill(text,
                    "%required%", trim(unmet.min()), "%current%", current, "%plot%", plot)));
            return false;
        }

        Cost cost = terms.cost();
        if (!currency.canAfford(player, cost)) {
            messages.send(player, "unlock-cannot-afford", "%plot%", plot, "%cost%", display(cost));
            return false;
        }
        if (!currency.charge(player, cost)) {
            messages.send(player, "payment-failed");
            return false;
        }
        if (!worlds.unlock(player.getUniqueId(), px, pz)) {
            currency.refund(player, cost);
            messages.send(player, "payment-refunded");
            return false;
        }

        String count = String.valueOf(state.unlockedCount());
        messages.send(player, "unlocked", "%plot%", plot, "%cost%", display(cost), "%count%", count);
        String[] placeholders = {"%player%", player.getName(), "%uuid%", player.getUniqueId().toString(),
                "%plot%", plot, "%count%", count, "%purchase%", String.valueOf(purchase)};
        for (String command : terms.commands()) {
            String line = Messages.fill(command, placeholders);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
        }
        for (String line : terms.messages()) {
            player.sendMessage(com.mystipixel.royalskyblock.util.Text.color(Messages.fill(line, placeholders)));
        }
        return true;
    }

    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
