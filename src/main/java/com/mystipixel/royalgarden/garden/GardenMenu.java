package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalgarden.config.Durations;
import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalgarden.config.ItemSpec;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalskyblock.util.Text;
import com.willfp.eco.core.gui.menu.Menu;
import com.willfp.eco.core.gui.menu.MenuBuilder;
import com.willfp.eco.core.gui.slot.Slot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The desk menu: the board, drawn as itself — and, from any plot you own, the preset picker.
 *
 * <p>A 5&times;5 grid of plots and a 9-wide inventory are a lucky fit — the board sits centred in the
 * top rows, and every slot is the plot it represents rather than a list entry describing one. Left-click
 * a plot you own to walk there, right-click it to choose a preset; click one you can buy to buy it.
 *
 * <p>Every icon, title and line comes from {@code menu:} and {@code preset-menu:} in {@code config.yml}.
 * The board menu is built once and rendered per player — each slot reads the viewer's own state — and
 * rebuilt by {@link #reload()} so edited icons show without a restart.
 */
public final class GardenMenu {

    private final GardenConfig config;
    private final Messages messages;
    private final GardenWorlds worlds;
    private final UnlockService unlocks;
    private final PresetService presetService;
    private Consumer<Player> milestones;
    private Consumer<Player> visitors;
    private Consumer<Player> upgrades;
    private Consumer<Player> composter;
    private Consumer<Player> pests;
    private ClearingService clearing;
    private java.util.function.Function<java.util.UUID, String[]> levelPlaceholders = owner -> new String[0];
    private Menu menu;

    public GardenMenu(GardenConfig config, Messages messages, GardenWorlds worlds, UnlockService unlocks,
                      PresetService presetService) {
        this.config = config;
        this.messages = messages;
        this.worlds = worlds;
        this.unlocks = unlocks;
        this.presetService = presetService;
        this.menu = build();
    }

    /** Rebuild the menu from the current config. */
    public void reload() {
        this.menu = build();
    }

    /** Give the summary item the Garden's level placeholders ({@code %level%}, {@code %xp%}, …). */
    public void setLevelPlaceholders(java.util.function.Function<java.util.UUID, String[]> placeholders) {
        this.levelPlaceholders = placeholders;
    }

    /** Add the Crop Milestones button, opening this page when clicked. */
    public void setMilestones(Consumer<Player> open) {
        this.milestones = open;
        this.menu = build();
    }

    /** Add the Visitors button, opening this page when clicked. */
    public void setVisitors(Consumer<Player> open) {
        this.visitors = open;
        this.menu = build();
    }

    /** Add the Crop Upgrades button, opening this page when clicked. */
    public void setUpgrades(Consumer<Player> open) {
        this.upgrades = open;
        this.menu = build();
    }

    /** Add the Composter button, opening this page when clicked. */
    public void setComposter(Consumer<Player> open) {
        this.composter = open;
        this.menu = build();
    }

    /** Add the Pests button, opening this page when clicked. */
    public void setPests(Consumer<Player> open) {
        this.pests = open;
        this.menu = build();
    }

    /** Show plots still being cleared as such, with their progress. */
    public void setClearing(ClearingService clearing) {
        this.clearing = clearing;
    }

    public void open(Player player) {
        menu.open(player);
    }

    private ConfigurationSection items() {
        return config.yaml().getConfigurationSection("menu.items");
    }

    private Menu build() {
        int grid = GardenLayout.grid();
        int rows = grid + 1;
        int columnOffset = (9 - grid) / 2 + 1;             // eco slots are 1-indexed
        MenuBuilder builder = Menu.builder(rows)
                .setTitle(Text.legacy(config.yaml().getString("menu.title", "&8The Garden")));

        boolean[][] used = new boolean[rows + 1][10];
        for (int px = 0; px < grid; px++) {
            for (int pz = 0; pz < grid; pz++) {
                int plotX = px;
                int plotZ = pz;
                // North is up: plot z maps to the row.
                builder.setSlot(pz + 1, px + columnOffset, Slot.builder((player, shown) -> icon(player, plotX, plotZ))
                        .onLeftClick((event, slot, clicked) ->
                                leftClick((Player) event.getWhoClicked(), clicked, plotX, plotZ))
                        .onRightClick((event, slot, clicked) ->
                                rightClick((Player) event.getWhoClicked(), plotX, plotZ))
                        .build());
                used[pz + 1][px + columnOffset] = true;
            }
        }

        builder.setSlot(rows, 5, Slot.builder((player, shown) -> summary(player)).build());
        used[rows][5] = true;

        button(builder, used, rows, "milestones", 3, Material.GOLDEN_HOE, milestones);
        button(builder, used, rows, "visitors", 7, Material.VILLAGER_SPAWN_EGG, visitors);
        button(builder, used, rows, "upgrades", 4, Material.ANVIL, upgrades);
        button(builder, used, rows, "composter", 6, Material.COMPOSTER, composter);
        button(builder, used, rows, "pests", 8, Material.IRON_SWORD, pests);

        ItemStack filler = filler();
        if (filler != null) {
            for (int row = 1; row <= rows; row++) {
                for (int column = 1; column <= 9; column++) {
                    if (!used[row][column]) {
                        builder.setSlot(row, column, Slot.builder(filler).build());
                    }
                }
            }
        }
        return builder.build();
    }

    /** A bottom-row button from {@code menu.items.<key>}, in its column if that is free. */
    private void button(MenuBuilder builder, boolean[][] used, int rows, String key, int defaultColumn,
                        Material fallback, Consumer<Player> open) {
        if (open == null) {
            return;
        }
        int column = Math.clamp(config.yaml().getInt("menu.items." + key + ".column", defaultColumn), 1, 9);
        if (used[rows][column]) {
            return;
        }
        builder.setSlot(rows, column, Slot.builder(ItemSpec.build(section(items(), key), fallback))
                .onLeftClick((event, slot, clicked) -> open.accept((Player) event.getWhoClicked()))
                .build());
        used[rows][column] = true;
    }

    private ItemStack filler() {
        String raw = config.yaml().getString("menu.filler", "");
        Material material = raw == null || raw.isBlank() ? null : Material.matchMaterial(raw.trim());
        return material == null || !material.isItem() ? null : ItemSpec.plain(material, " ", List.of());
    }

    // ── rendering ──────────────────────────────────────────────────────────────

    /** The item for one plot, from this player's point of view. */
    private ItemStack icon(Player player, int px, int pz) {
        GardenState state = worlds.state(player.getUniqueId());
        String plot = GardenLayout.plotName(px, pz);
        ConfigurationSection items = items();

        if (GardenLayout.isCentre(px, pz)) {
            return ItemSpec.build(section(items, "farmhouse"), Material.HAY_BLOCK, "%plot%", plot);
        }
        if (state.isUnlocked(px, pz) && clearing != null && clearing.blocks(state, px, pz)) {
            return ItemSpec.build(section(items, "clearing"), Material.OAK_SAPLING,
                    clearing.placeholders(state, px, pz));
        }
        if (state.isUnlocked(px, pz)) {
            World here = player.getWorld();
            boolean building = player.getUniqueId().equals(GardenWorlds.ownerOf(here))
                    && worlds.isBuilding(here, px, pz);
            String preset = state.presetOn(px, pz);
            PresetLibrary.Entry entry = preset == null ? null : worlds.presets().entry(preset);
            String presetName = entry != null ? entry.displayName()
                    : preset != null ? preset : messages.raw("preset-none");
            return ItemSpec.build(section(items, building ? "building" : "unlocked"), Material.GRASS_BLOCK,
                    "%plot%", plot, "%preset%", presetName,
                    "%presets-hint%", config.playerPresetsEnabled() ? messages.raw("menu-presets-hint") : "");
        }
        if (state.canUnlock(px, pz)) {
            int purchase = state.purchasedCount() + 1;
            if (state.unlockedCount() >= unlocks.limit(player)) {
                return ItemSpec.build(section(items, "limit-reached"), Material.BARRIER,
                        "%plot%", plot, "%limit%", String.valueOf(unlocks.farmableLimit(player)));
            }
            UnlockService.Terms terms = unlocks.terms(px, pz, purchase);
            UnlockService.Requirement unmet = unlocks.unmet(player, terms);
            int levelNeeded = unlocks.levelShortfall(player, terms);
            String requirement = levelNeeded > 0
                    ? messages.raw("menu-level-required", "%required%", String.valueOf(levelNeeded))
                    : unmet == null ? "" : Messages.fill(
                            unmet.message().isEmpty() ? messages.raw("unlock-requirement") : unmet.message(),
                            "%required%", String.valueOf(unmet.min()), "%current%", "?", "%plot%", plot);
            return ItemSpec.build(section(items, "unlockable"), Material.LIME_STAINED_GLASS_PANE,
                    "%plot%", plot, "%cost%", unlocks.display(terms.cost()),
                    "%purchase%", String.valueOf(purchase),
                    "%requirement%", requirement);
        }
        return ItemSpec.build(section(items, "locked"), Material.GRAY_STAINED_GLASS_PANE, "%plot%", plot);
    }

    /** The counter at the bottom of the menu. */
    private ItemStack summary(Player player) {
        GardenState state = worlds.state(player.getUniqueId());
        String[] base = {
                // Farmable plots only: the farmhouse is always there and cannot be farmed, so counting it
                // made a full board read as 25 of 25 when there are 24 plots to earn.
                "%unlocked%", String.valueOf(state.purchasedCount()),
                "%total%", String.valueOf(GardenLayout.farmableCount()),
                "%limit%", String.valueOf(unlocks.farmableLimit(player)),
                "%size%", String.valueOf(GardenLayout.plotSize())};
        String[] level = levelPlaceholders.apply(player.getUniqueId());
        String[] all = new String[base.length + level.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(level, 0, all, base.length, level.length);
        return ItemSpec.build(section(items(), "summary"), Material.WHEAT, all);
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        return parent == null ? null : parent.getConfigurationSection(key);
    }

    // ── clicking ───────────────────────────────────────────────────────────────

    private void leftClick(Player player, Menu clicked, int px, int pz) {
        GardenState state = worlds.state(player.getUniqueId());
        if (state.isUnlocked(px, pz)) {
            visit(player, px, pz);
            return;
        }
        if (unlocks.attempt(player, px, pz)) {
            clicked.refresh(player);
        }
    }

    private void rightClick(Player player, int px, int pz) {
        GardenState state = worlds.state(player.getUniqueId());
        if (GardenLayout.isCentre(px, pz) || !state.isUnlocked(px, pz)) {
            return;
        }
        if (!config.playerPresetsEnabled()) {
            messages.send(player, "presets-disabled");
            return;
        }
        if (presetService.refuseUncleared(player, state, px, pz)) {
            return;
        }
        openPresets(player, px, pz);
    }

    /** Walk the player to a plot, opening their Garden first if they are not standing in it. */
    private void visit(Player player, int px, int pz) {
        player.closeInventory();
        World here = player.getWorld();
        if (player.getUniqueId().equals(GardenWorlds.ownerOf(here))) {
            player.teleportAsync(standingSpot(here, px, pz));
            return;
        }
        worlds.open(player.getUniqueId()).whenComplete((world, error) -> {
            if (error != null || world == null) {
                messages.send(player, "garden-open-failed");
                return;
            }
            player.teleportAsync(standingSpot(world, px, pz));
        });
    }

    /**
     * Somewhere to stand at the middle of a plot: on top of whatever is built there.
     *
     * <p>The fixed surface height used to be the target, which put anyone visiting a plot with a farm
     * on it inside the farm.
     */
    static Location standingSpot(World world, int px, int pz) {
        Location centre = GardenLayout.plotCentre(world, px, pz);
        int top = world.getHighestBlockYAt(centre.getBlockX(), centre.getBlockZ());
        if (top >= GardenLayout.floorY()) {
            centre.setY(top + 1);
        }
        return centre;
    }

    // ── the preset picker ──────────────────────────────────────────────────────

    /** Open the preset picker for one of the player's plots. */
    public void openPresets(Player player, int px, int pz) {
        List<PresetLibrary.Entry> entries = worlds.presets().selectable();
        if (entries.isEmpty()) {
            messages.send(player, "presets-none");
            return;
        }
        int shown = Math.min(entries.size(), 45);
        int rows = Math.min(6, (shown + 8) / 9 + 1);
        String plot = GardenLayout.plotName(px, pz);
        MenuBuilder builder = Menu.builder(rows).setTitle(Text.legacy(Messages.fill(
                config.yaml().getString("preset-menu.title", "&8Presets — Plot %plot%"), "%plot%", plot)));

        for (int i = 0; i < shown; i++) {
            PresetLibrary.Entry entry = entries.get(i);
            builder.setSlot(i / 9 + 1, i % 9 + 1, Slot.builder((viewer, picker) -> presetIcon(viewer, entry, px, pz))
                    .onLeftClick((event, slot, clicked) -> {
                        Player who = (Player) event.getWhoClicked();
                        if (presetService.choose(who, px, pz, entry)) {
                            who.closeInventory();
                        } else {
                            clicked.refresh(who);
                        }
                    })
                    .build());
        }
        ConfigurationSection back = config.yaml().getConfigurationSection("preset-menu.back");
        builder.setSlot(rows, 1, Slot.builder(ItemSpec.build(back, Material.ARROW))
                .onLeftClick((event, slot, clicked) -> open((Player) event.getWhoClicked()))
                .build());
        builder.build().open(player);
    }

    private ItemStack presetIcon(Player player, PresetLibrary.Entry entry, int px, int pz) {
        GardenState state = worlds.state(player.getUniqueId());
        long wait = presetService.cooldownLeft(player, state, px, pz, entry);
        boolean current = entry.id().equals(state.presetOn(px, pz));
        String status;
        if (!entry.permission().isBlank() && !player.hasPermission(entry.permission())) {
            status = messages.raw("preset-status-no-permission");
        } else if (entry.gardenLevel() > 0 && presetService.level(player) < entry.gardenLevel()) {
            status = messages.raw("preset-status-level", "%required%", String.valueOf(entry.gardenLevel()));
        } else if (wait > 0) {
            status = messages.raw("preset-status-cooldown", "%time%", Durations.format(wait));
        } else {
            status = messages.raw("preset-status-ready");
        }
        String[] placeholders = {
                "%preset%", entry.displayName(),
                "%plot%", GardenLayout.plotName(px, pz),
                "%cost%", presetService.display(entry.cost()),
                "%cooldown%", Durations.format(presetService.cooldownFor(entry)),
                "%status%", status,
                "%current%", current ? messages.raw("preset-current") : ""};
        List<String> lore = new ArrayList<>();
        for (String line : entry.lore()) {
            lore.add(Messages.fill(line, placeholders));
        }
        for (String line : config.yaml().getStringList("preset-menu.entry-lore")) {
            String filled = Messages.fill(line, placeholders);
            if (line.trim().startsWith("%") && line.trim().endsWith("%") && filled.isBlank()) {
                continue;
            }
            lore.add(filled);
        }
        return ItemSpec.plain(entry.icon(), Messages.fill(entry.displayName(), placeholders), lore);
    }
}
