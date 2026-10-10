package com.mystipixel.royalgarden.visitor;

import com.mystipixel.royalgarden.config.Durations;
import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * Visitor stats for {@code %royalgarden_<key>%}, without PlaceholderAPI types.
 *
 * <pre>
 *   %royalgarden_visitors_served%        visitors served in total
 *   %royalgarden_visitors_unique%        different visitors served
 *   %royalgarden_visitors_total%         visitors in visitors.yml
 *   %royalgarden_visitors_refused%       visitors refused
 *   %royalgarden_visitors_copper%        Copper earned from visitors
 *   %royalgarden_visitors_waiting%       visitors waiting now
 *   %royalgarden_visitors_max_queue%     most that can wait
 *   %royalgarden_visitors_next%          time to the next arrival (blank while the queue is full)
 *   %royalgarden_visitor_&lt;id&gt;_served%   times one visitor has been served
 * </pre>
 *
 * <p>Plain numbers, so conditions can compare them. Offline players are read from disk without caching.
 */
public final class VisitorPlaceholders {

    private final VisitorService service;

    public VisitorPlaceholders(VisitorService service) {
        this.service = service;
    }

    /** Null if the key is not one of ours. */
    public String resolve(OfflinePlayer player, String key) {
        String params = key.toLowerCase(Locale.ROOT);
        VisitorStore.Data data = service.store().peek(player.getUniqueId());
        switch (params) {
            case "visitors_served":
                return String.valueOf(data.served());
            case "visitors_unique":
                return String.valueOf(data.unique());
            case "visitors_total":
                return String.valueOf(service.config().visitors().size());
            case "visitors_refused":
                return String.valueOf(data.refused());
            case "visitors_copper":
                return String.valueOf(data.copper());
            case "visitors_waiting":
                return String.valueOf(data.queued());
            case "visitors_max_queue":
                return String.valueOf(service.maxQueue(player.getUniqueId()));
            case "visitors_next": {
                long next = service.untilNext(player.getUniqueId(), data);
                return next < 0 ? "" : Durations.format(next);
            }
            default:
                break;
        }
        if (params.startsWith("visitor_") && params.endsWith("_served")) {
            String id = params.substring("visitor_".length(), params.length() - "_served".length());
            return service.config().visitor(id) == null ? null : String.valueOf(data.served(id));
        }
        return null;
    }
}
