package com.mystipixel.royalgarden.visitor;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.mystipixel.royalgarden.config.ItemSpec;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalskyblock.util.Text;
import com.willfp.eco.core.gui.menu.Menu;
import com.willfp.eco.core.gui.menu.MenuBuilder;
import com.willfp.eco.core.gui.slot.Slot;
import com.willfp.eco.core.items.TestableItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Visitors page of the desk, and each visitor's offer.
 *
 * <p>The Visitor Log lists every visitor there is: a head for each one the player has served, with how
 * many times, and a locked icon for the rest.
 *
 * <p>The page lists everyone waiting — including those without a spot outside the barn — with the
 * player's stats, the time to the next arrival and the SkyMart button. Clicking a visitor, or
 * right-clicking one outside, opens their offer: what they want, what the player has of it, and what
 * they pay. Every icon and line comes from {@code menu:} in {@code visitors.yml}.
 */
public final class VisitorMenu {

    private final VisitorService service;
    private final Messages messages;
    private final Consumer<Player> back;

    public VisitorMenu(VisitorService service, Messages messages, Consumer<Player> back) {
        this.service = service;
        this.messages = messages;
        this.back = back;
    }

    private ConfigurationSection menu() {
        return service.config().menu();
    }

    private ConfigurationSection section(String path) {
        ConfigurationSection menu = menu();
        return menu == null ? null : menu.getConfigurationSection(path);
    }

    private String string(String path, String fallback) {
        ConfigurationSection menu = menu();
        return menu == null ? fallback : menu.getString(path, fallback);
    }

    // ── the list ───────────────────────────────────────────────────────────────

    /** The Visitors page for the viewer's own Garden. */
    public void open(Player player) {
        if (!service.config().enabled()) {
            messages.send(player, "visitors-disabled");
            return;
        }
        UUID owner = player.getUniqueId();
        List<Offer> queue = service.store().get(owner).queue();
        int shown = Math.min(36, Math.max(queue.size(), 1));
        int rows = Math.min(6, (shown + 8) / 9 + 1);
        MenuBuilder builder = Menu.builder(rows).setTitle(Text.legacy(string("title", "&8Visitors")));
        boolean[][] used = new boolean[rows + 1][10];

        if (queue.isEmpty()) {
            builder.setSlot(1, 5, Slot.builder(ItemSpec.build(section("empty"), Material.GRAY_STAINED_GLASS_PANE,
                    service.statPlaceholders(owner))).build());
            used[1][5] = true;
        }
        for (int i = 0; i < Math.min(shown, queue.size()); i++) {
            Offer offer = queue.get(i);
            int row = i / 9 + 1;
            int column = i % 9 + 1;
            builder.setSlot(row, column, Slot.builder(entry(player, offer))
                    .onLeftClick((event, slot, clicked) -> openOffer((Player) event.getWhoClicked(), offer.id()))
                    .build());
            used[row][column] = true;
        }

        int bottom = rows;
        place(builder, used, bottom, "stats", 5, Material.BOOK, null);
        if (back != null) {
            place(builder, used, bottom, "back", 1, Material.ARROW, back);
        }
        if (service.config().skymartEnabled()) {
            place(builder, used, bottom, "skymart", 9, Material.EMERALD, this::skymart);
        }
        place(builder, used, bottom, "log-button", 3, Material.WRITABLE_BOOK, viewer -> openLog(viewer, 1));
        fill(builder, used, rows);
        builder.build().open(player);
    }

    private ItemStack entry(Player player, Offer offer) {
        VisitorType type = service.config().visitor(offer.visitor());
        String[] placeholders = append(service.placeholders(player.getUniqueId(), offer),
                "%items%", itemLines(player, offer), "%rewards%", rewardLines(offer));
        return ItemSpec.build(section("entry"), type == null ? Material.VILLAGER_SPAWN_EGG : type.icon(), placeholders);
    }

    /** Open SkyMart, the Copper shop — an EcoShop shop, reached through its command. */
    private void skymart(Player player) {
        String command = Messages.fill(service.config().skymartCommand(), "%player%", player.getName());
        if (command.isBlank()) {
            return;
        }
        player.closeInventory();
        String line = command.startsWith("/") ? command.substring(1) : command;
        if (service.config().skymartAsConsole()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
        } else {
            player.performCommand(line);
        }
    }

    // ── the log ────────────────────────────────────────────────────────────────

    /**
     * The Visitor Log: every visitor in {@code visitors.yml}, in order. Those the viewer has served show
     * as their head with how many times they have visited; the rest are locked.
     */
    public void openLog(Player player, int page) {
        if (!service.config().enabled()) {
            messages.send(player, "visitors-disabled");
            return;
        }
        UUID owner = player.getUniqueId();
        List<VisitorType> all = new ArrayList<>(service.config().visitors());
        int perPage = 45;
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        int current = Math.clamp(page, 1, pages);
        int from = (current - 1) * perPage;
        int shown = Math.min(perPage, all.size() - from);
        int rows = pages > 1 ? 6 : Math.min(6, Math.max(1, (shown + 8) / 9) + 1);
        String[] paging = {"%page%", String.valueOf(current), "%pages%", String.valueOf(pages)};
        MenuBuilder builder = Menu.builder(rows).setTitle(Text.legacy(Messages.fill(
                string("log.title", "&8Visitor Log"), paging)));
        boolean[][] used = new boolean[rows + 1][10];

        VisitorStore.Data data = service.store().get(owner);
        for (int i = 0; i < shown; i++) {
            VisitorType type = all.get(from + i);
            int row = i / 9 + 1;
            int column = i % 9 + 1;
            builder.setSlot(row, column, Slot.builder(logIcon(owner, type, data.served(type.id()) > 0)).build());
            used[row][column] = true;
        }

        place(builder, used, rows, "log.progress", 5, Material.BOOK, null);
        place(builder, used, rows, "log.back", 1, Material.ARROW, this::open);
        if (current > 1) {
            place(builder, used, rows, "log.previous", 4, Material.ARROW, viewer -> openLog(viewer, current - 1));
        }
        if (current < pages) {
            place(builder, used, rows, "log.next", 6, Material.ARROW, viewer -> openLog(viewer, current + 1));
        }
        fill(builder, used, rows);
        builder.build().open(player);
    }

    private ItemStack logIcon(UUID owner, VisitorType type, boolean served) {
        String[] placeholders = service.placeholders(owner, type);
        int level = service.level(owner);
        String requirement = type.gardenLevel() > level
                ? Messages.fill(string("log.requirement", "&cNeeds Garden level %garden_level%"), placeholders)
                : "";
        String[] all = append(placeholders, "%requirement%", requirement);
        if (!served) {
            return ItemSpec.build(section("log.locked"), Material.GRAY_DYE, all);
        }
        ItemStack head = ItemSpec.build(section("log.served"), Material.PLAYER_HEAD, all);
        if (head.getItemMeta() instanceof SkullMeta skull && !type.head().isBlank()) {
            skull.setPlayerProfile(profile(type));
            head.setItemMeta(skull);
        }
        return head;
    }

    /** A visitor's face: a base64 skin texture, or a player whose skin to borrow. */
    private static PlayerProfile profile(VisitorType type) {
        String head = type.head();
        if (head.length() > 16) {
            // A fixed id per visitor, so the client can cache the skin.
            PlayerProfile profile = Bukkit.createProfile(
                    UUID.nameUUIDFromBytes(("royalgarden-visitor:" + type.id()).getBytes(StandardCharsets.UTF_8)),
                    "visitor");
            profile.setProperty(new ProfileProperty("textures", head));
            return profile;
        }
        return Bukkit.createProfile(head);
    }

    // ── one offer ──────────────────────────────────────────────────────────────

    /** A visitor's offer. */
    public void openOffer(Player player, String offerId) {
        UUID owner = player.getUniqueId();
        Offer offer = service.store().get(owner).offer(offerId);
        if (offer == null) {
            messages.send(player, "visitor-gone");
            player.closeInventory();
            return;
        }
        VisitorType type = service.config().visitor(offer.visitor());
        String[] base = service.placeholders(owner, offer);
        MenuBuilder builder = Menu.builder(4).setTitle(Text.legacy(Messages.fill(
                string("offer.title", "&8%visitor_name%"), base)));
        boolean[][] used = new boolean[5][10];

        List<String> dialogue = type == null ? List.of() : type.dialogue();
        String[] withDialogue = append(base, "%dialogue%", String.join("\n", dialogue));
        builder.setSlot(1, 5, Slot.builder(ItemSpec.build(section("offer.visitor"),
                type == null ? Material.VILLAGER_SPAWN_EGG : type.icon(), withDialogue)).build());
        used[1][5] = true;

        List<Offer.Wanted> items = offer.items();
        int count = Math.min(7, items.size());
        int start = 5 - count / 2;
        for (int i = 0; i < count; i++) {
            Offer.Wanted wanted = items.get(i);
            int column = start + i;
            builder.setSlot(2, column, Slot.builder((viewer, shown) -> wantedIcon(viewer, wanted)).build());
            used[2][column] = true;
        }

        String[] full = append(base, "%rewards%", rewardLines(offer));
        builder.setSlot(4, 3, Slot.builder((viewer, shown) -> ItemSpec.build(section("offer.accept"),
                        Material.LIME_TERRACOTTA, append(full, "%status%", ready(viewer, offer)
                                ? messages.raw("visitor-status-ready") : messages.raw("visitor-status-missing"))))
                .onLeftClick((event, slot, clicked) -> {
                    Player who = (Player) event.getWhoClicked();
                    if (service.accept(who, offer.id())) {
                        who.closeInventory();
                    } else {
                        clicked.refresh(who);
                    }
                })
                .build());
        used[4][3] = true;
        builder.setSlot(4, 7, Slot.builder(ItemSpec.build(section("offer.refuse"), Material.RED_TERRACOTTA, full))
                .onLeftClick((event, slot, clicked) -> {
                    Player who = (Player) event.getWhoClicked();
                    service.refuse(who, offer.id());
                    who.closeInventory();
                })
                .build());
        used[4][7] = true;
        builder.setSlot(4, 5, Slot.builder(ItemSpec.build(section("offer.back"), Material.ARROW))
                .onLeftClick((event, slot, clicked) -> open((Player) event.getWhoClicked()))
                .build());
        used[4][5] = true;
        fill(builder, used, 4);
        builder.build().open(player);
    }

    /** A requested item as itself, with how many are wanted and how many the viewer has. */
    private ItemStack wantedIcon(Player viewer, Offer.Wanted wanted) {
        TestableItem item = VisitorService.lookup(wanted.item());
        ItemStack stack = item == null ? new ItemStack(Material.BARRIER) : item.getItem().clone();
        long have = item == null ? 0 : VisitorService.count(viewer.getInventory(), item);
        String[] placeholders = {
                "%item%", service.itemName(wanted),
                "%amount%", String.format("%,d", wanted.amount()),
                "%have%", String.format("%,d", have),
                "%status%", messages.raw(have >= wanted.amount() ? "visitor-item-have" : "visitor-item-missing",
                        "%have%", String.format("%,d", have), "%amount%", String.format("%,d", wanted.amount()))};
        stack.setAmount((int) Math.clamp(wanted.amount(), 1, stack.getMaxStackSize()));
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        String name = string("offer.item.name", "");
        if (!name.isBlank()) {
            meta.displayName(Text.color(Messages.fill(name, placeholders)).decoration(TextDecoration.ITALIC, false));
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        ConfigurationSection menu = menu();
        List<String> lines = menu == null ? List.of("", "&7Wanted: &e%amount%", "%status%")
                : menu.getStringList("offer.item.lore");
        for (String line : lines) {
            for (String part : Messages.fill(line, placeholders).split("\n", -1)) {
                lore.add(Text.color(part).decoration(TextDecoration.ITALIC, false));
            }
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }

    private boolean ready(Player player, Offer offer) {
        for (Offer.Wanted wanted : offer.items()) {
            TestableItem item = VisitorService.lookup(wanted.item());
            if (item == null || VisitorService.count(player.getInventory(), item) < wanted.amount()) {
                return false;
            }
        }
        return true;
    }

    /** {@code %items%}: one line per requested item, from {@code menu.item-line}. */
    private String itemLines(Player player, Offer offer) {
        List<String> lines = new ArrayList<>();
        for (Offer.Wanted wanted : offer.items()) {
            TestableItem item = VisitorService.lookup(wanted.item());
            long have = item == null ? 0 : VisitorService.count(player.getInventory(), item);
            lines.add(Messages.fill(string("item-line", "&8- &f%amount%x %item%"),
                    "%item%", service.itemName(wanted), "%amount%", String.format("%,d", wanted.amount()),
                    "%have%", String.format("%,d", have),
                    "%check%", messages.raw(have >= wanted.amount() ? "visitor-check-yes" : "visitor-check-no")));
        }
        return String.join("\n", lines);
    }

    /** {@code %rewards%}: Copper, Garden XP, then any lines the visitor's {@code reward-lore} adds. */
    private String rewardLines(Offer offer) {
        List<String> lines = new ArrayList<>();
        if (offer.copper() > 0 && service.config().copperEnabled()) {
            lines.add(Messages.fill(string("reward-copper", "&8+&c%copper% Copper"),
                    "%copper%", String.format("%,d", offer.copper())));
        }
        if (offer.xp() > 0) {
            lines.add(Messages.fill(string("reward-xp", "&8+&2%xp% Garden XP"), "%xp%", String.format("%,d", offer.xp())));
        }
        VisitorType type = service.config().visitor(offer.visitor());
        if (type != null && type.rewards() != null) {
            lines.addAll(type.rewards().getStringList("lore"));
        }
        return String.join("\n", lines);
    }

    // ── layout ─────────────────────────────────────────────────────────────────

    /** Put a configured bottom-row button in its column, if the column is free. */
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
        String raw = string("filler", "black_stained_glass_pane");
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

    private static String[] append(String[] base, String... more) {
        String[] out = new String[base.length + more.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }
}
