package com.mystipixel.royalgarden.pest;

import com.mystipixel.royalgarden.config.ItemSpec;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.garden.GardenLayout;
import com.mystipixel.royalgarden.garden.GardenState;
import com.mystipixel.royalgarden.garden.GardenWorlds;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** The Pests page and the Pest Log. Every icon and line comes from menu: in pests.yml. */
public final class PestMenu {

    private final PestService service;
    private final GardenWorlds gardens;
    private final Messages messages;
    private final Consumer<Player> back;

    public PestMenu(PestService service, GardenWorlds gardens, Messages messages, Consumer<Player> back) {
        this.service = service;
        this.gardens = gardens;
        this.messages = messages;
        this.back = back;
    }

    private ConfigurationSection section(String path) {
        ConfigurationSection menu = service.config().menu();
        return menu == null ? null : menu.getConfigurationSection(path);
    }

    private String string(String path) {
        return service.config().menu().getString(path);
    }

    public void open(Player player) {
        if (!service.config().enabled()) {
            messages.send(player, "pests-disabled");
            return;
        }
        int grid = GardenLayout.grid();
        int rows = grid + 1;
        int columnOffset = (9 - grid) / 2 + 1;
        MenuBuilder builder = Menu.builder(rows).setTitle(Text.legacy(string("title")));
        boolean[][] used = new boolean[rows + 1][10];
        for (int px = 0; px < grid; px++) {
            for (int pz = 0; pz < grid; pz++) {
                int plot = PestStore.pack(px, pz);
                builder.setSlot(pz + 1, px + columnOffset, Slot.builder((viewer, shown) -> plotIcon(viewer, plot))
                        .onLeftClick((event, slot, clicked) -> locate((Player) event.getWhoClicked(), plot))
                        .build());
                used[pz + 1][px + columnOffset] = true;
            }
        }
        place(builder, used, rows, "stats", 5, Material.IRON_SWORD, null);
        place(builder, used, rows, "log-button", 4, Material.WRITABLE_BOOK, this::openLog);
        if (back != null) {
            place(builder, used, rows, "back", 6, Material.ARROW, back);
        }
        fill(builder, used, rows);
        builder.build().open(player);
    }

    private ItemStack plotIcon(Player viewer, int plot) {
        UUID owner = viewer.getUniqueId();
        GardenState state = gardens.state(owner);
        int px = PestStore.px(plot);
        int pz = PestStore.pz(plot);
        String name = PestStore.plotName(plot);
        if (!state.isUnlocked(px, pz)) {
            return ItemSpec.build(section("plot-locked"), Material.GRAY_STAINED_GLASS_PANE, "%plot%", name);
        }
        PestStore.Data data = service.store().get(owner);
        int count = data.count(plot);
        if (count == 0) {
            return ItemSpec.build(section("plot-clear"), Material.LIME_STAINED_GLASS_PANE, "%plot%", name,
                    "%pests%", "0", "%list%", "", "%status%", "");
        }
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (PestStore.Alive pest : data.alive().values()) {
            if (pest.plot() == plot) {
                byType.merge(pest.type(), 1, Integer::sum);
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : byType.entrySet()) {
            PestType type = service.config().pest(entry.getKey());
            lines.add(Messages.fill(string("list-line"),
                    "%pest_name%", type == null ? entry.getKey() : type.displayName(),
                    "%count%", String.valueOf(entry.getValue())));
        }
        String status = PestMath.infested(count, service.config().infestedAt())
                ? messages.raw("pest-status-infested") : messages.raw("pest-status-below");
        return ItemSpec.build(section("plot-infested"), Material.RED_STAINED_GLASS_PANE, "%plot%", name,
                "%pests%", String.valueOf(count), "%list%", String.join("\n", lines), "%status%", status);
    }

    private void locate(Player player, int plot) {
        UUID owner = player.getUniqueId();
        PestStore.Data data = service.store().get(owner);
        PestStore.Alive first = null;
        for (PestStore.Alive pest : data.alive().values()) {
            if (pest.plot() == plot) {
                first = pest;
                break;
            }
        }
        if (first == null) {
            return;
        }
        World world = player.getWorld();
        String name = PestStore.plotName(plot);
        if (!owner.equals(GardenWorlds.ownerOf(world))) {
            messages.send(player, "pest-locate-not-in-garden", "%plot%", name);
            return;
        }
        ConfigurationSection menu = service.config().menu();
        if (menu != null && !menu.getBoolean("locate-teleport", true)) {
            messages.send(player, "pest-locate", "%plot%", name, "%x%", String.valueOf((int) Math.floor(first.x())),
                    "%y%", String.valueOf((int) Math.floor(first.y())), "%z%", String.valueOf((int) Math.floor(first.z())));
            player.closeInventory();
            return;
        }
        Location centre = GardenLayout.plotCentre(world, PestStore.px(plot), PestStore.pz(plot));
        centre.setY(world.getHighestBlockYAt(centre.getBlockX(), centre.getBlockZ()) + 1);
        player.closeInventory();
        player.teleportAsync(centre).thenAccept(moved -> {
            if (moved) {
                messages.send(player, "pest-located", "%plot%", name);
            }
        });
    }

    public void openLog(Player player) {
        if (!service.config().enabled()) {
            messages.send(player, "pests-disabled");
            return;
        }
        List<PestType> all = new ArrayList<>(service.config().pests());
        int shown = Math.min(45, all.size());
        int rows = Math.min(6, Math.max(1, (shown + 8) / 9) + 1);
        MenuBuilder builder = Menu.builder(rows).setTitle(Text.legacy(string("log.title")));
        boolean[][] used = new boolean[rows + 1][10];
        UUID owner = player.getUniqueId();
        PestStore.Data data = service.store().get(owner);
        for (int i = 0; i < shown; i++) {
            PestType type = all.get(i);
            int row = i / 9 + 1;
            int column = i % 9 + 1;
            builder.setSlot(row, column, Slot.builder(logIcon(owner, type, data.kills(type.id()) > 0)).build());
            used[row][column] = true;
        }
        place(builder, used, rows, "log.back", 5, Material.ARROW, this::open);
        fill(builder, used, rows);
        builder.build().open(player);
    }

    private ItemStack logIcon(UUID owner, PestType type, boolean killed) {
        String[] placeholders = PestService.append(service.placeholders(owner, type, -1),
                "%garden_level%", String.valueOf(type.gardenLevel()),
                "%copper%", range(type),
                "%xp%", String.format("%,d", type.gardenXp()),
                "%description%", String.join("\n", type.description()));
        if (!killed) {
            String requirement = Messages.fill(string("log.requirement"),
                    placeholders);
            return ItemSpec.build(section("log.unknown"), Material.GRAY_DYE,
                    PestService.append(placeholders, "%requirement%", type.gardenLevel() > 1 ? requirement : ""));
        }
        // No material under log.killed: each pest shows its own icon.
        return ItemSpec.build(section("log.killed"), type.icon(), placeholders);
    }

    private static String range(PestType type) {
        return type.copper().min() == type.copper().max() ? String.format("%,d", type.copper().min())
                : String.format("%,d-%,d", type.copper().min(), type.copper().max());
    }

    private void place(MenuBuilder builder, boolean[][] used, int row, String key, int defaultColumn,
                       Material fallback, Consumer<Player> click) {
        ConfigurationSection spec = section(key);
        int column = Math.clamp(spec == null ? defaultColumn : spec.getInt("column", defaultColumn), 1, 9);
        if (used[row][column]) {
            return;
        }
        var slot = Slot.builder((player, shown) -> ItemSpec.build(section(key), fallback,
                service.statPlaceholders(player.getUniqueId())));
        if (click != null) {
            slot = slot.onLeftClick((event, s, clicked) -> click.accept((Player) event.getWhoClicked()));
        }
        builder.setSlot(row, column, slot.build());
        used[row][column] = true;
    }

    private void fill(MenuBuilder builder, boolean[][] used, int rows) {
        String raw = string("filler");
        Material material = raw == null || raw.isBlank() ? null : Material.matchMaterial(raw.trim());
        if (material == null || !material.isItem()) {
            return;
        }
        ItemStack filler = ItemSpec.plain(material, " ", List.of());
        for (int row = 1; row <= rows; row++) {
            for (int column = 1; column <= 9; column++) {
                if (!used[row][column]) {
                    builder.setSlot(row, column, Slot.builder(filler).build());
                }
            }
        }
    }
}
