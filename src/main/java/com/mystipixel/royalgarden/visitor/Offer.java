package com.mystipixel.royalgarden.visitor;

import java.util.List;

/**
 * A visitor waiting at a Garden. Everything is decided on arrival and saved, so a restart or a
 * visitors.yml edit never re-rolls an offer already made.
 *
 * @param id      unique among this player's visitors; the NPC standing for it carries the same id
 * @param visitor the visitor's id in {@code visitors.yml}
 */
public record Offer(String id, String visitor, List<Wanted> items, long copper, long xp, long arrived) {

    public record Wanted(String item, long amount, String name) {
    }
}
