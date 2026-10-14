package com.mystipixel.royalgarden.garden;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Short-lived copies of players' data that is not loaded (offline players), for placeholders, so a scoreboard
 * does not parse their file on every refresh. Safe from any thread.
 */
public final class PeekCache<T> {

    private static final long TTL_MILLIS = 30_000L;
    // only sweep expired entries once the map is big enough to be worth walking
    private static final int SWEEP_AT = 256;

    private record Entry<T>(T value, long expiresAt) {
    }

    private final Map<UUID, Entry<T>> entries = new ConcurrentHashMap<>();

    /** The copy read within the last 30 seconds, or a fresh one from read. */
    public T get(UUID player, Function<UUID, T> read) {
        long now = System.currentTimeMillis();
        Entry<T> hit = entries.get(player);
        if (hit != null && now < hit.expiresAt()) {
            return hit.value();
        }
        if (entries.size() > SWEEP_AT) {
            entries.values().removeIf(entry -> now >= entry.expiresAt());
        }
        T value = read.apply(player);
        entries.put(player, new Entry<>(value, now + TTL_MILLIS));
        return value;
    }

    /** Drop a player's copy, e.g. once their data was loaded and changed. */
    public void forget(UUID player) {
        entries.remove(player);
    }
}
