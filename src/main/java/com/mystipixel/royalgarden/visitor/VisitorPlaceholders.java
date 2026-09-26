package com.mystipixel.royalgarden.visitor;

import com.mystipixel.royalgarden.config.Durations;
import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.placeholder.PlayerPlaceholder;
import org.bukkit.entity.Player;

import java.util.function.Function;

/**
 * Visitor stats as PlaceholderAPI placeholders.
 *
 * <p>Registered through eco's placeholder system rather than a PlaceholderAPI expansion of our own:
 * PlaceholderAPI is not on this extension's class path (see
 * {@link com.mystipixel.royalgarden.config.Placeholders}), while eco already bridges every placeholder
 * its plugins register. They are registered on the host, so they carry its prefix:
 *
 * <pre>
 *   %royalskyblock_garden_visitors_served%        visitors served in total
 *   %royalskyblock_garden_visitors_unique%        different visitors served
 *   %royalskyblock_garden_visitors_total%         visitors in visitors.yml
 *   %royalskyblock_garden_visitors_refused%       visitors refused
 *   %royalskyblock_garden_visitors_copper%        Copper earned from visitors
 *   %royalskyblock_garden_visitors_waiting%       visitors waiting now
 *   %royalskyblock_garden_visitors_max_queue%     most that can wait
 *   %royalskyblock_garden_visitors_next%          time to the next arrival
 *   %royalskyblock_garden_visitor_&lt;id&gt;_served%   times one visitor has been served
 * </pre>
 *
 * <p>Plain numbers, no separators, so they can be compared in conditions. Only online players have
 * values.
 */
public final class VisitorPlaceholders {

    public static final String PREFIX = "garden_";

    private final EcoPlugin plugin;
    private final VisitorService service;

    public VisitorPlaceholders(EcoPlugin plugin, VisitorService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /**
     * Register every placeholder, including one per visitor in {@code visitors.yml}. Run again after a
     * reload to add visitors that were new in it; registering an identifier twice replaces it.
     */
    public void register() {
        stat("visitors_served", data -> String.valueOf(data.served()));
        stat("visitors_unique", data -> String.valueOf(data.unique()));
        stat("visitors_refused", data -> String.valueOf(data.refused()));
        stat("visitors_copper", data -> String.valueOf(data.copper()));
        stat("visitors_waiting", data -> String.valueOf(data.queued()));
        new PlayerPlaceholder(plugin, PREFIX + "visitors_total",
                player -> String.valueOf(service.config().visitors().size())).register();
        new PlayerPlaceholder(plugin, PREFIX + "visitors_max_queue",
                player -> String.valueOf(service.maxQueue(player.getUniqueId()))).register();
        new PlayerPlaceholder(plugin, PREFIX + "visitors_next", player -> {
            long next = service.untilNext(player.getUniqueId());
            return next < 0 ? "" : Durations.format(next);
        }).register();
        for (VisitorType type : service.config().visitors()) {
            String id = type.id();
            stat("visitor_" + id + "_served", data -> String.valueOf(data.served(id)));
        }
    }

    private void stat(String name, Function<VisitorStore.Data, String> value) {
        new PlayerPlaceholder(plugin, PREFIX + name, (Player player) -> value.apply(
                service.store().get(player.getUniqueId()))).register();
    }
}
