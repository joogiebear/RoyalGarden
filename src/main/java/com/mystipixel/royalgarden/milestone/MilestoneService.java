package com.mystipixel.royalgarden.milestone;

import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalskyblock.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crop Milestones. Rewards stack: reaching a tier pays rewards.every-tier, rewards.by-tier.<n>, and the
 * crop's own every-tier and by-tier.<n>. A harvest that jumps several tiers pays each tier it passes.
 */
public final class MilestoneService {

    /** Told about every tier reached; this is how milestones pay Garden XP. */
    public interface TierListener {
        void reached(Player player, MilestoneCrop crop, int tier);
    }

    private final MilestoneConfig config;
    private final MilestoneStore store;
    private final Messages messages;
    private TierListener tierListener = (player, crop, tier) -> { };
    // when each player's progress was last put on their action bar; main thread only
    private final Map<UUID, Long> lastActionBar = new HashMap<>();
    private static final long ACTION_BAR_EVERY_MILLIS = 250L;

    public MilestoneService(MilestoneConfig config, MilestoneStore store, Messages messages) {
        this.config = config;
        this.store = store;
        this.messages = messages;
    }

    public void onTier(TierListener listener) {
        this.tierListener = listener == null ? (player, crop, tier) -> { } : listener;
    }

    public MilestoneConfig config() {
        return config;
    }

    public MilestoneStore store() {
        return store;
    }

    public void record(Player player, MilestoneCrop crop, long amount) {
        if (amount <= 0 || !config.enabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        long before = store.total(id, crop.id());
        long after = store.add(id, crop.id(), amount);
        int tierBefore = crop.tierOf(before);
        int tierAfter = crop.tierOf(after);
        for (int tier = tierBefore + 1; tier <= tierAfter; tier++) {
            reach(player, crop, tier, after);
        }
        if (config.actionBarProgress()) {
            // fast harvesting breaks several blocks a tick; a tier-up always shows at once
            long now = System.currentTimeMillis();
            Long last = lastActionBar.get(id);
            if (tierAfter > tierBefore || last == null || now - last >= ACTION_BAR_EVERY_MILLIS) {
                lastActionBar.put(id, now);
                messages.actionBar(player, "milestone-progress", placeholders(crop, tierAfter, after));
            }
        }
    }

    public void forget(UUID player) {
        lastActionBar.remove(player);
    }

    private void reach(Player player, MilestoneCrop crop, int tier, long total) {
        String[] placeholders = placeholders(crop, tier, total);
        String[] withPlayer = append(placeholders, "%player%", player.getName(), "%uuid%", player.getUniqueId().toString());
        messages.send(player, "milestone-tier-up", placeholders);
        if (config.broadcastTierUps()) {
            String line = messages.raw("milestone-broadcast", withPlayer);
            if (!line.isEmpty()) {
                Bukkit.broadcast(Text.color(line));
            }
        }
        String sound = config.tierUpSound();
        if (sound != null && !sound.isBlank()) {
            player.playSound(player.getLocation(), sound.trim().toLowerCase(java.util.Locale.ROOT), 1f, 1f);
        }
        ConfigurationSection global = config.rewards();
        ConfigurationSection own = config.yaml().getConfigurationSection("crops." + crop.id() + ".rewards");
        List<String> commands = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (ConfigurationSection layer : new ConfigurationSection[]{global, own}) {
            if (layer == null) {
                continue;
            }
            commands.addAll(layer.getStringList("every-tier.commands"));
            lines.addAll(layer.getStringList("every-tier.messages"));
            commands.addAll(layer.getStringList("by-tier." + tier + ".commands"));
            lines.addAll(layer.getStringList("by-tier." + tier + ".messages"));
        }
        for (String command : commands) {
            String filled = Messages.fill(command, withPlayer);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled.startsWith("/") ? filled.substring(1) : filled);
        }
        for (String line : lines) {
            player.sendMessage(Text.color(Messages.fill(line, withPlayer)));
        }
        tierListener.reached(player, crop, tier);
    }

    public String[] placeholders(MilestoneCrop crop, int tier, long total) {
        long next = crop.nextThreshold(tier);
        long start = crop.tierStart(tier);
        double fraction = next < 0 ? 1.0 : (double) (total - start) / Math.max(1, next - start);
        String rendered = config.bar(fraction);
        return new String[]{
                "%crop%", crop.id(),
                "%crop_name%", crop.displayName(),
                "%tier%", String.valueOf(tier),
                "%tier_roman%", Tiers.roman(tier),
                "%max_tier%", String.valueOf(crop.maxTier()),
                "%total%", String.format("%,d", total),
                "%next%", next < 0 ? messages.raw("milestone-maxed") : String.format("%,d", next),
                "%remaining%", next < 0 ? "0" : String.format("%,d", next - total),
                "%percent%", String.valueOf((int) Math.floor(fraction * 100)),
                "%bar%", rendered};
    }

    private static String[] append(String[] base, String... more) {
        String[] out = new String[base.length + more.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }
}
