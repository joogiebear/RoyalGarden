package com.mystipixel.royalgarden.level;

import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.garden.GardenState;
import com.mystipixel.royalgarden.garden.GardenWorlds;
import com.mystipixel.royalgarden.milestone.Tiers;
import com.mystipixel.royalskyblock.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Garden Levels. XP is saved in the owner's Garden state, so {@code /garden reset} clears it. It comes
 * from milestone tiers, plot purchases and {@code /garden xp <player> give <n>}. Nothing spends XP; each
 * level reached pays {@code rewards.every-level} plus {@code rewards.by-level.<n>} once.
 */
public final class GardenLevels {

    private final LevelConfig config;
    private final GardenWorlds gardens;
    private final Messages messages;

    public GardenLevels(LevelConfig config, GardenWorlds gardens, Messages messages) {
        this.config = config;
        this.gardens = gardens;
        this.messages = messages;
    }

    public LevelConfig config() {
        return config;
    }

    public long xp(UUID owner) {
        return gardens.state(owner).xp();
    }

    public int level(UUID owner) {
        return levelOf(gardens.state(owner));
    }

    public int levelOf(GardenState state) {
        return config.enabled() ? config.levelOf(state.xp()) : config.maxLevel();
    }

    /** For placeholders: see {@link GardenWorlds#peekState}. Null while not loaded. */
    public GardenState peekState(UUID owner) {
        return gardens.peekState(owner);
    }

    /**
     * Pays out any levels the gain reaches.
     *
     * @param reason a message key describing the source, or null to say nothing about the gain
     */
    public void give(UUID owner, long amount, String reason) {
        if (!config.enabled() || amount <= 0) {
            return;
        }
        GardenState state = gardens.state(owner);
        if (state.readOnly()) {
            return;
        }
        int before = config.levelOf(state.xp());
        state.setXp(state.xp() + amount);
        gardens.saveLater(state);
        int after = config.levelOf(state.xp());

        Player online = Bukkit.getPlayer(owner);
        if (online != null && reason != null && config.announceXpGains()) {
            messages.actionBar(online, "level-xp-gained", append(placeholders(owner),
                    "%amount%", String.format("%,d", amount), "%reason%", messages.raw(reason)));
        }
        for (int level = before + 1; level <= after; level++) {
            reach(owner, level);
        }
    }

    /** Admin correction: pays nothing. */
    public void set(UUID owner, long xp) {
        GardenState state = gardens.state(owner);
        if (state.readOnly()) {
            return;
        }
        state.setXp(xp);
        gardens.save(state);
    }

    private void reach(UUID owner, int level) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        String name = player.getName() == null ? owner.toString() : player.getName();
        String[] placeholders = append(placeholders(owner), "%player%", name, "%uuid%", owner.toString(),
                "%reached%", String.valueOf(level), "%reached_roman%", Tiers.roman(level));
        Player online = player.getPlayer();
        if (online != null) {
            messages.send(online, "level-up", placeholders);
            String sound = config.levelUpSound();
            if (sound != null && !sound.isBlank()) {
                online.playSound(online.getLocation(), sound.trim().toLowerCase(Locale.ROOT), 1f, 1f);
            }
        }
        if (config.broadcastLevelUps()) {
            String line = messages.raw("level-broadcast", placeholders);
            if (!line.isEmpty()) {
                Bukkit.broadcast(Text.color(line));
            }
        }
        ConfigurationSection rewards = config.rewards();
        List<String> commands = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        if (rewards != null) {
            commands.addAll(rewards.getStringList("every-level.commands"));
            lines.addAll(rewards.getStringList("every-level.messages"));
            commands.addAll(rewards.getStringList("by-level." + level + ".commands"));
            lines.addAll(rewards.getStringList("by-level." + level + ".messages"));
        }
        for (String command : commands) {
            String filled = Messages.fill(command, placeholders);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), filled.startsWith("/") ? filled.substring(1) : filled);
        }
        if (online != null) {
            for (String line : lines) {
                online.sendMessage(Text.color(Messages.fill(line, placeholders)));
            }
        }
    }

    public String[] placeholders(UUID owner) {
        long xp = xp(owner);
        int level = config.levelOf(xp);
        long next = config.nextThreshold(level);
        long start = config.levelStart(level);
        double fraction = next < 0 ? 1.0 : (double) (xp - start) / Math.max(1, next - start);
        ConfigurationSection bar = config.progressBar();
        return new String[]{
                "%level%", String.valueOf(level),
                "%level_roman%", Tiers.roman(level),
                "%max_level%", String.valueOf(config.maxLevel()),
                "%xp%", String.format("%,d", xp),
                "%next%", next < 0 ? messages.raw("level-maxed") : String.format("%,d", next),
                "%remaining%", next < 0 ? "0" : String.format("%,d", next - xp),
                "%percent%", String.valueOf((int) Math.floor(fraction * 100)),
                "%bar%", Tiers.bar(fraction,
                        bar == null ? 20 : bar.getInt("length", 20),
                        bar.getString("filled"),
                        bar.getString("empty"))};
    }

    private static String[] append(String[] base, String... more) {
        String[] out = new String[base.length + more.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }
}
