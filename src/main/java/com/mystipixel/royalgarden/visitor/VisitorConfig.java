package com.mystipixel.royalgarden.visitor;

import com.mystipixel.royalgarden.config.Durations;
import com.mystipixel.royalgarden.config.YamlFile;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * {@code visitors.yml}: who visits, when, what they ask for, what they pay, and how Copper is given.
 *
 * <p>Read without the shipped file as defaults — {@code visitors:} and {@code rarities:} are lists of
 * things, and one the admin deleted would otherwise keep coming back — so every setting here has its
 * fallback in code.
 */
public final class VisitorConfig extends YamlFile {

    /** A rarity from {@code rarities:}: its name, how likely its visitors are, and what it adds. */
    public record Rarity(String id, String displayName, double weight, VisitorMath.Range copper,
                         VisitorMath.Range xp, ConfigurationSection rewards) {
    }

    /** Where a visitor stands, relative to the middle of the farmhouse plot. */
    public record Spot(double x, double y, double z, float yaw, boolean hasY) {
    }

    private final Map<String, Rarity> rarities = new LinkedHashMap<>();
    private final Map<String, VisitorType> visitors = new LinkedHashMap<>();
    private final List<Spot> spots = new ArrayList<>();
    private Spot entry;
    private double walkSpeed;
    private boolean lineOn;
    private double lineSpacing;
    private int lineLength;
    private NavigableMap<Integer, Integer> queueByLevel = new TreeMap<>();

    public VisitorConfig(File dataFolder, Logger logger) {
        super(dataFolder, "visitors.yml", logger, false);
        load();
    }

    @Override
    public void reload() {
        super.reload();
        load();
    }

    private void load() {
        if (visitors == null) {
            return;                        // called from the super constructor, before fields exist
        }
        loadRarities();
        loadVisitors();
        loadSpots();
        queueByLevel = levelTable(yaml().getConfigurationSection("arrivals.max-queue-by-level"));
    }

    private void loadRarities() {
        rarities.clear();
        ConfigurationSection root = yaml().getConfigurationSection("rarities");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection r = root.getConfigurationSection(id);
            if (r == null) {
                continue;
            }
            ConfigurationSection rewards = r.getConfigurationSection("rewards");
            rarities.put(id.toLowerCase(Locale.ROOT), new Rarity(id.toLowerCase(Locale.ROOT),
                    r.getString("name", id), r.getDouble("weight", 1),
                    range(rewards, "copper", "rarities." + id), range(rewards, "garden-xp", "rarities." + id),
                    rewards));
        }
    }

    private void loadVisitors() {
        visitors.clear();
        ConfigurationSection root = yaml().getConfigurationSection("visitors");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection v = root.getConfigurationSection(id);
            if (v == null) {
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            String where = "visitors." + id;
            List<VisitorType.Request> requests = requests(v, where);
            if (requests.isEmpty()) {
                logger.warning("visitors.yml: " + where + " has no requests with items; skipped.");
                continue;
            }
            String rarityId = v.getString("rarity", "common").toLowerCase(Locale.ROOT);
            Rarity rarity = rarities.get(rarityId);
            if (rarity == null) {
                logger.warning("visitors.yml: " + where + ": rarity '" + rarityId + "' is not under rarities:.");
            }
            double weight = v.contains("weight") ? v.getDouble("weight") : rarity == null ? 1 : rarity.weight();
            Material icon = Material.matchMaterial(v.getString("icon", ""));
            visitors.put(key, new VisitorType(key,
                    v.getString("name", id),
                    rarityId,
                    weight,
                    Math.max(0, v.getInt("garden-level", 0)),
                    v.getString("permission", ""),
                    icon == null || !icon.isItem() ? Material.VILLAGER_SPAWN_EGG : icon,
                    v.getString("entity", "villager").trim().toLowerCase(Locale.ROOT),
                    v.getString("profession", "").trim().toLowerCase(Locale.ROOT),
                    v.getString("villager-type", "").trim().toLowerCase(Locale.ROOT),
                    v.getString("head", "").trim(),
                    lines(v, "dialogue"),
                    List.copyOf(requests),
                    v.getConfigurationSection("rewards")));
        }
    }

    /** {@code requests:} — a list of {@code {weight, items}}; or a bare {@code items:} for one request. */
    private List<VisitorType.Request> requests(ConfigurationSection visitor, String where) {
        List<VisitorType.Request> out = new ArrayList<>();
        List<Map<?, ?>> listed = visitor.getMapList("requests");
        if (listed.isEmpty() && visitor.contains("items")) {
            List<VisitorType.Item> items = items(visitor.getList("items"), where + ".items");
            if (!items.isEmpty()) {
                out.add(new VisitorType.Request(1, List.copyOf(items)));
            }
            return out;
        }
        for (int i = 0; i < listed.size(); i++) {
            Map<?, ?> request = listed.get(i);
            Object rawItems = request.get("items");
            List<VisitorType.Item> items = items(rawItems instanceof List<?> list ? list : null,
                    where + ".requests[" + i + "].items");
            if (items.isEmpty()) {
                continue;
            }
            double weight = request.get("weight") instanceof Number n ? n.doubleValue() : 1;
            out.add(new VisitorType.Request(weight, List.copyOf(items)));
        }
        return out;
    }

    /**
     * Items as {@code "wheat 64"}, {@code "ecoitems:enchanted_wheat 2-4"}, or
     * {@code {item: …, amount: …, name: …}}. The last word of the short form is the amount.
     */
    private List<VisitorType.Item> items(List<?> raw, String where) {
        List<VisitorType.Item> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Object entry : raw) {
            String item;
            Object amount;
            String name = "";
            if (entry instanceof Map<?, ?> map) {
                item = map.get("item") == null ? null : map.get("item").toString();
                amount = map.get("amount");
                name = map.get("name") == null ? "" : map.get("name").toString();
            } else if (entry != null) {
                String text = entry.toString().trim();
                int space = text.lastIndexOf(' ');
                item = space < 0 ? text : text.substring(0, space).trim();
                amount = space < 0 ? "1" : text.substring(space + 1);
            } else {
                continue;
            }
            VisitorMath.Range range = VisitorMath.Range.parse(amount);
            if (item == null || item.isBlank() || range == null || range.max() <= 0) {
                logger.warning("visitors.yml: " + where + ": '" + entry + "' is not an item and amount, "
                        + "like 'wheat 64' or 'ecoitems:enchanted_wheat 2-4'.");
                continue;
            }
            out.add(new VisitorType.Item(item.trim(), range, name));
        }
        return out;
    }

    private void loadSpots() {
        spots.clear();
        entry = null;
        walkSpeed = 0;
        lineOn = false;
        ConfigurationSection line = yaml().getConfigurationSection("line");
        // No line: section at all means a visitors.yml written before the drive-through existed. This file
        // has no shipped defaults behind it, so the defaults are here: the line is on unless it is turned off.
        if (line == null || line.getBoolean("enabled", true)) {
            loadLine(line);
            return;
        }
        for (String raw : yaml().getStringList("spots")) {
            String[] parts = raw.split(",");
            try {
                if (parts.length == 3) {
                    spots.add(new Spot(Double.parseDouble(parts[0].trim()), 0, Double.parseDouble(parts[1].trim()),
                            Float.parseFloat(parts[2].trim()), false));
                } else if (parts.length == 4) {
                    spots.add(new Spot(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()),
                            Double.parseDouble(parts[2].trim()), Float.parseFloat(parts[3].trim()), true));
                } else {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException bad) {
                logger.warning("visitors.yml: spots: '" + raw + "' should be 'x, z, yaw' or 'x, y, z, yaw'.");
            }
        }
    }

    /**
     * Spots in single file behind the drive-through window, front first, and the place newcomers walk in
     * from: as far again behind the last spot as the spacing between two.
     */
    private void loadLine(ConfigurationSection line) {
        String rawFront = line == null ? "6, 6.5, 180" : line.getString("front", "6, 6.5, 180");
        Spot front = parseSpot(rawFront);
        if (front == null) {
            logger.warning("visitors.yml: line.front '" + rawFront + "' should be 'x, z, yaw' or 'x, y, z, yaw'; "
                    + "using the window.");
            front = new Spot(6, 0, 6.5, 180f, false);
        }
        String direction = line == null ? "south" : line.getString("direction", "south");
        double dx = 0;
        double dz = 0;
        switch (direction.trim().toLowerCase(Locale.ROOT)) {
            case "north" -> dz = -1;
            case "east" -> dx = 1;
            case "west" -> dx = -1;
            case "south" -> dz = 1;
            default -> {
                logger.warning("visitors.yml: line.direction '" + direction + "' is not north, south, east or west; "
                        + "using south.");
                dz = 1;
            }
        }
        double spacing = Math.max(0.8, line == null ? 1.5 : line.getDouble("spacing", 1.5));
        int length = Math.clamp(line == null ? 7 : line.getInt("length", 7), 1, 30);
        for (int i = 0; i <= length; i++) {
            Spot spot = new Spot(front.x() + dx * spacing * i, front.y(), front.z() + dz * spacing * i, front.yaw(),
                    front.hasY());
            if (i < length) {
                spots.add(spot);
            } else {
                entry = new Spot(front.x() + dx * spacing * (i + 1), front.y(), front.z() + dz * spacing * (i + 1),
                        front.yaw(), front.hasY());
            }
        }
        walkSpeed = Math.clamp(line == null ? 0.1 : line.getDouble("walk-speed", 0.1), 0.0, 1.0);
        lineOn = true;
        lineSpacing = spacing;
        lineLength = length;
    }

    /**
     * The line from a farmhouse schematic's {@code [window]} sign, when there is one: the first visitor
     * stands on the sign facing the way its writing does, and the line runs back behind them. It takes the
     * place of {@code line.front} and {@code line.direction}; spacing, length and speed still come from here.
     */
    private List<Spot> markedLine() {
        if (!lineOn) {
            return null;
        }
        com.mystipixel.royalgarden.garden.Farmhouse.Anchor window =
                com.mystipixel.royalgarden.garden.Farmhouse.current().anchor("window");
        if (window == null) {
            return null;
        }
        double yaw = Math.toRadians(window.yaw());
        double backX = Math.sin(yaw);                 // facing is (-sin, cos); behind is the opposite
        double backZ = -Math.cos(yaw);
        List<Spot> out = new ArrayList<>();
        for (int i = 0; i <= lineLength; i++) {
            double step = lineSpacing * (i < lineLength ? i : i + 1);
            out.add(new Spot(window.x() + backX * step, window.y(), window.z() + backZ * step, window.yaw(), true));
        }
        return out;
    }

    private static Spot parseSpot(String raw) {
        String[] parts = raw.split(",");
        try {
            if (parts.length == 3) {
                return new Spot(Double.parseDouble(parts[0].trim()), 0, Double.parseDouble(parts[1].trim()),
                        Float.parseFloat(parts[2].trim()), false);
            }
            if (parts.length == 4) {
                return new Spot(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()),
                        Double.parseDouble(parts[2].trim()), Float.parseFloat(parts[3].trim()), true);
            }
        } catch (NumberFormatException bad) {
            return null;
        }
        return null;
    }

    private NavigableMap<Integer, Integer> levelTable(ConfigurationSection section) {
        NavigableMap<Integer, Integer> out = new TreeMap<>();
        if (section == null) {
            return out;
        }
        for (String key : section.getKeys(false)) {
            try {
                out.put(Integer.parseInt(key.trim()), section.getInt(key));
            } catch (NumberFormatException notALevel) {
                logger.warning("visitors.yml: arrivals.max-queue-by-level: '" + key + "' is not a level.");
            }
        }
        return out;
    }

    /** A {@code copper: 10-20} style range under {@code section}, or zero. */
    VisitorMath.Range range(ConfigurationSection section, String key, String where) {
        if (section == null || !section.contains(key)) {
            return VisitorMath.Range.ZERO;
        }
        VisitorMath.Range range = VisitorMath.Range.parse(section.get(key));
        if (range == null) {
            logger.warning("visitors.yml: " + where + ".rewards." + key + ": '" + section.get(key)
                    + "' is not a number or range like 10-20.");
            return VisitorMath.Range.ZERO;
        }
        return range;
    }

    private static List<String> lines(ConfigurationSection section, String key) {
        if (section.isList(key)) {
            return List.copyOf(section.getStringList(key));
        }
        String single = section.getString(key, "");
        return single.isEmpty() ? List.of() : List.of(single);
    }

    // ── settings ───────────────────────────────────────────────────────────────

    public boolean enabled() {
        return yaml().getBoolean("enabled", true);
    }

    /** Time between two arrivals while the player is online. */
    public long intervalMillis() {
        return Math.max(1_000L, Durations.parseMillis(yaml().getString("arrivals.interval", "15m")));
    }

    /** How soon a player's very first visitor arrives. */
    public long firstArrivalMillis() {
        return Math.max(0L, Durations.parseMillis(yaml().getString("arrivals.first-arrival", "1m")));
    }

    /** How often arrivals are checked, in ticks. */
    public long checkTicks() {
        return Math.max(1L, yaml().getLong("arrivals.check-every-seconds", 5)) * 20L;
    }

    /** Most visitors waiting at once for a Garden of this level. */
    public int maxQueue(int level) {
        return Math.clamp(VisitorMath.byLevel(queueByLevel, level, yaml().getInt("arrivals.max-queue", 5)), 1, 45);
    }

    public double offlineRate() {
        return yaml().getDouble("arrivals.offline-rate", 1.0);
    }

    /** Only players who have been to their Garden get visitors. */
    public boolean requireGarden() {
        return yaml().getBoolean("arrivals.require-garden", true);
    }

    /** Whether the same visitor can be waiting twice. */
    public boolean allowDuplicates() {
        return yaml().getBoolean("arrivals.allow-duplicates", false);
    }

    public String arrivalSound() {
        return yaml().getString("arrivals.sound", "entity.villager.ambient");
    }

    public double scalePerLevel() {
        return yaml().getDouble("scaling.per-level", 0.05);
    }

    public double scaleMax() {
        return yaml().getDouble("scaling.max-multiplier", 2.0);
    }

    /** Whether the Copper and Garden XP a visitor pays grow by the same multiplier as the request. */
    public boolean scaleRewards() {
        return yaml().getBoolean("scaling.rewards", true);
    }

    public String acceptSound() {
        return yaml().getString("accept-sound", "entity.villager.yes");
    }

    public String refuseSound() {
        return yaml().getString("refuse-sound", "entity.villager.no");
    }

    // ── copper ─────────────────────────────────────────────────────────────────

    public boolean copperEnabled() {
        return yaml().getBoolean("copper.enabled", true);
    }

    public String copperCurrency() {
        return yaml().getString("copper.currency", "copper");
    }

    /** The console command that pays Copper: {@code %player%}, {@code %amount%}, {@code %currency%}. */
    public String copperCommand() {
        return yaml().getString("copper.give-command", "ecobits givesilent %player% %currency% %amount%");
    }

    // ── skymart ────────────────────────────────────────────────────────────────

    public boolean skymartEnabled() {
        return yaml().getBoolean("skymart.enabled", true);
    }

    public String skymartCommand() {
        return yaml().getString("skymart.command", "skymart");
    }

    public boolean skymartAsConsole() {
        return yaml().getBoolean("skymart.as-console", false);
    }

    // ── things ─────────────────────────────────────────────────────────────────

    public Collection<VisitorType> visitors() {
        return Collections.unmodifiableCollection(visitors.values());
    }

    public VisitorType visitor(String id) {
        return id == null ? null : visitors.get(id.toLowerCase(Locale.ROOT));
    }

    public Rarity rarity(String id) {
        return id == null ? null : rarities.get(id.toLowerCase(Locale.ROOT));
    }

    /** Where visitors stand, the front of the line first when there is one. */
    public List<Spot> spots() {
        List<Spot> marked = markedLine();
        return marked != null ? Collections.unmodifiableList(marked.subList(0, lineLength))
                : Collections.unmodifiableList(spots);
    }

    /** Where a new visitor walks into the line from; null without a line. */
    public Spot entry() {
        List<Spot> marked = markedLine();
        return marked != null ? marked.get(lineLength) : entry;
    }

    /** Blocks per tick visitors walk along the line; 0 to appear in place. */
    public double walkSpeed() {
        return walkSpeed;
    }

    public ConfigurationSection rewards() {
        return yaml().getConfigurationSection("rewards");
    }

    public ConfigurationSection menu() {
        return yaml().getConfigurationSection("menu");
    }
}
