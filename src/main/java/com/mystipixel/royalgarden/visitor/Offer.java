package com.mystipixel.royalgarden.visitor;

import java.util.List;

/**
 * A visitor waiting at a Garden, and exactly what they asked for and will pay.
 *
 * <p>Everything is decided when they arrive and saved with them, so reopening an offer — or the server
 * restarting — never re-rolls it, and editing {@code visitors.yml} does not change a request already
 * made.
 *
 * @param id      unique among this player's visitors; the NPC standing for it carries the same id
 * @param visitor the visitor's id in {@code visitors.yml}
 */
public record Offer(String id, String visitor, List<Wanted> items, long copper, long xp, long arrived) {

    /** One item asked for: an eco item lookup and how many. */
    public record Wanted(String item, long amount, String name) {
    }
}
