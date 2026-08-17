package com.mystipixel.royalgarden.garden;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The coloured border around the part of the Garden you own.
 *
 * <p>A <b>per-player</b> border, the same device RoyalSkyblock uses for islands, including its trick
 * for colouring one: Minecraft has no border colour API, but it renders a static border blue, one
 * lerping outward green, and one lerping inward red. Lerping two blocks over roughly three years is
 * imperceptible movement and a permanent colour.
 *
 * <h2>Why per-player, and why it must run late</h2>
 *
 * <p>RoyalSkyblock's {@code BorderService} clears the per-player border for any world that is not an
 * island — a Garden included — on join and on world change. It is a plain {@code @EventHandler}, so it
 * runs at {@code NORMAL}. This listens at {@code MONITOR} on the same events, which is after, so the
 * Garden border is applied over the clearing rather than being wiped by it.
 *
 * <h2>What it can and cannot express</h2>
 *
 * <p>A border is a square, and a player has exactly one. It therefore cannot outline twenty-five plots
 * at once, and it cannot trace an L-shaped Garden. It is drawn around the smallest box containing your
 * unlocked plots — so it hugs the way your Garden actually grew — and stopping you entering a locked
 * plot <em>inside</em> that box is {@link GardenProtection}'s job, not the border's. The two together
 * are exact; the border alone never could be.
 */
public final class GardenBorder implements Listener {

    /** ~3 years. Over a 2-block delta this is drift of nothing, and a colour that never expires. */
    private static final long LERP_SECONDS = 100_000_000L;
    private static final double LERP_DELTA = 2.0;

    /** How long a refusal shows red before the border goes back to its normal colour. */
    private static final long FLASH_TICKS = 30L;

    /** Border colours, as the render trick expresses them. */
    public enum Color {
        /** Static — renders blue. */
        BLUE,
        /** Lerping outward — renders green. Used for the area you own. */
        GREEN,
        /** Lerping inward — renders red. Used to answer a refusal. */
        RED
    }

    private final Plugin plugin;
    private final GardenWorlds worlds;

    /** Who is currently being shown a red border, so overlapping refusals don't stack restores. */
    private final Set<UUID> flashing = new HashSet<>();

    /** The colour of a Garden's own border. Red is reserved for refusals. */
    private Color color = Color.GREEN;

    public GardenBorder(Plugin plugin, GardenWorlds worlds) {
        this.plugin = plugin;
        this.worlds = worlds;
    }

    public void setColor(Color color) {
        this.color = color == Color.RED ? Color.GREEN : color;
    }

    // ── events ─────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // After the respawn teleport has actually happened, or we would border the world they left.
        Bukkit.getScheduler().runTask(plugin, () -> apply(event.getPlayer()));
    }

    // ── application ────────────────────────────────────────────────────────────

    /** Give everyone in a world a fresh border — used after an unlock moves it. */
    public void applyToWorld(World world) {
        for (Player player : world.getPlayers()) {
            apply(player);
        }
    }

    /**
     * Draw this player's border, or leave them alone if they are not in a Garden.
     *
     * <p>Leaving them alone matters: outside a Garden the border belongs to whoever else manages it,
     * and a MONITOR-priority listener that clears borders everywhere would quietly break islands.
     */
    public void apply(Player player) {
        apply(player, color);
    }

    private void apply(Player player, Color shown) {
        World world = player.getWorld();
        if (!GardenWorlds.isGarden(world)) {
            return;
        }
        GardenState state = worlds.stateOf(world);
        if (state == null) {
            return;
        }

        int[] box = state.bounds();
        double minX = GardenLayout.plotMin(box[0]);
        double minZ = GardenLayout.plotMin(box[1]);
        double maxX = GardenLayout.plotMax(box[2]) + 1.0;
        double maxZ = GardenLayout.plotMax(box[3]) + 1.0;
        double size = Math.max(maxX - minX, maxZ - minZ);

        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter((minX + maxX) / 2.0, (minZ + maxZ) / 2.0);
        border.setWarningDistance(2);
        border.setDamageAmount(0.0);
        border.setDamageBuffer(0.0);
        switch (shown) {
            case GREEN -> {
                border.setSize(size);
                border.setSize(size + LERP_DELTA, LERP_SECONDS);   // outward -> green
            }
            case RED -> {
                border.setSize(size + LERP_DELTA);
                border.setSize(size, LERP_SECONDS);                // inward -> red
            }
            default -> border.setSize(size);                       // static -> blue
        }
        player.setWorldBorder(border);
    }

    /**
     * Turn the border red for a moment, to answer being refused a locked plot.
     *
     * <p>The colour is the whole point of the trick, and this is the one place it says something: an
     * action-bar line explains the refusal, and the border says it in the same instant without being
     * read.
     */
    public void flashRed(Player player) {
        if (!flashing.add(player.getUniqueId())) {
            return;                       // already red; let the existing restore finish
        }
        apply(player, Color.RED);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            flashing.remove(player.getUniqueId());
            if (player.isOnline()) {
                apply(player);
            }
        }, FLASH_TICKS);
    }
}
