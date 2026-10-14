package com.mystipixel.royalgarden.visitor;

import com.mystipixel.royalgarden.config.Durations;
import com.mystipixel.royalgarden.config.ItemSpec;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.hooks.BazaarHook;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The offer page's button that buys what the player is missing from RoyalBazaar and then serves the
 * visitor. The first click quotes and arms, the second buys at the price taken again at that moment.
 * Main thread only.
 */
public final class VisitorBazaar {

    // quote: the Bazaar's price for missing, before the multiplier
    record Need(Offer.Wanted wanted, String listing, long missing, double quote) {
    }

    // cost includes the multiplier; unlisted are missing items the Bazaar won't sell now
    record Shortfall(List<Need> needs, List<Offer.Wanted> unlisted, double cost) {

        boolean nothingMissing() {
            return needs.isEmpty() && unlisted.isEmpty();
        }
    }

    private record Armed(String offerId, double cost, long until) {
    }

    private record Progress(double spent, int bought, String failure) {
    }

    private final VisitorService service;
    private final BazaarHook bazaar;
    private final Messages messages;
    private final Logger logger;
    private final Map<UUID, Armed> armed = new HashMap<>();
    private final Set<UUID> buying = new HashSet<>();
    // item lookup to listing ("" for none): finding a listing scans the whole Bazaar
    private final Map<String, String> listingCache = new HashMap<>();
    private long listingCacheUntil;

    public VisitorBazaar(VisitorService service, BazaarHook bazaar, Messages messages, Logger logger) {
        this.service = service;
        this.bazaar = bazaar;
        this.messages = messages;
        this.logger = logger;
    }

    public boolean active() {
        return service.config().bazaarEnabled() && bazaar.available();
    }

    /** Visitor item lookup to Bazaar listing, null where the Bazaar doesn't list it. Build once per page. */
    public Map<String, String> listings(Offer offer) {
        long now = System.currentTimeMillis();
        if (now > listingCacheUntil) {
            // short-lived, so a Bazaar reload that lists or delists an item shows up soon
            listingCache.clear();
            listingCacheUntil = now + 300_000L;
        }
        Map<String, String> listings = new HashMap<>();
        for (Offer.Wanted wanted : offer.items()) {
            String listing = listingCache.computeIfAbsent(wanted.item(), key -> {
                TestableItem item = VisitorService.lookup(key);
                String found = item == null ? null : bazaar.listing(item.getItem());
                return found == null ? "" : found;
            });
            listings.put(wanted.item(), listing.isEmpty() ? null : listing);
        }
        return listings;
    }

    Shortfall shortfall(Player player, Offer offer, Map<String, String> listings) {
        List<Need> needs = new ArrayList<>();
        List<Offer.Wanted> unlisted = new ArrayList<>();
        double total = 0;
        for (Offer.Wanted wanted : offer.items()) {
            TestableItem item = VisitorService.lookup(wanted.item());
            long have = item == null ? 0 : VisitorService.count(player.getInventory(), item);
            long missing = wanted.amount() - have;
            if (missing <= 0) {
                continue;
            }
            String listing = listings.get(wanted.item());
            double quote = listing == null ? -1 : bazaar.quote(listing, missing);
            if (quote < 0) {
                unlisted.add(wanted);
                continue;
            }
            needs.add(new Need(wanted, listing, missing, quote));
            total += quote;
        }
        return new Shortfall(needs, unlisted, total * service.config().bazaarMultiplier());
    }

    /** {@code menu.offer.bazaar}, with %items% %cost% %status% added to {@code base}. */
    public ItemStack icon(Player player, Offer offer, Map<String, String> listings, ConfigurationSection spec,
                          String lineFormat, String[] base) {
        Shortfall shortfall = shortfall(player, offer, listings);
        List<String> lines = new ArrayList<>();
        for (Need need : shortfall.needs()) {
            lines.add(Messages.fill(lineFormat, "%item%", service.itemName(need.wanted()),
                    "%amount%", String.format("%,d", need.missing())));
        }
        String status;
        if (shortfall.nothingMissing()) {
            status = messages.raw("visitor-bazaar-status-none");
        } else if (!shortfall.unlisted().isEmpty()) {
            status = messages.raw("visitor-bazaar-status-unlisted", "%items%", names(shortfall.unlisted()));
        } else if (isArmed(player.getUniqueId(), offer.id())) {
            status = messages.raw("visitor-bazaar-status-confirm");
        } else {
            status = messages.raw("visitor-bazaar-status-buy");
        }
        boolean priced = !shortfall.nothingMissing() && shortfall.unlisted().isEmpty();
        String[] placeholders = new String[base.length + 6];
        System.arraycopy(base, 0, placeholders, 0, base.length);
        placeholders[base.length] = "%items%";
        placeholders[base.length + 1] = String.join("\n", lines);
        placeholders[base.length + 2] = "%cost%";
        placeholders[base.length + 3] = priced ? bazaar.format(shortfall.cost()) : messages.raw("visitor-bazaar-no-cost");
        placeholders[base.length + 4] = "%status%";
        placeholders[base.length + 5] = status;
        return ItemSpec.build(spec, Material.GOLD_INGOT, placeholders);
    }

    /**
     * Arms, re-arms at a new price, or buys and serves. Returns true when the page should close because a
     * purchase has started.
     */
    public boolean click(Player player, Offer offer, Map<String, String> listings) {
        UUID id = player.getUniqueId();
        if (buying.contains(id)) {
            messages.send(player, "visitor-bazaar-busy");
            return false;
        }
        if (!waiting(id, offer.id())) {
            messages.send(player, "visitor-gone");
            return true;
        }
        Shortfall shortfall = shortfall(player, offer, listings);
        if (shortfall.nothingMissing()) {
            messages.send(player, "visitor-bazaar-nothing");
            return false;
        }
        if (!shortfall.unlisted().isEmpty()) {
            messages.send(player, "visitor-bazaar-unlisted", "%items%", names(shortfall.unlisted()));
            return false;
        }
        if (bazaar.balance(player) < shortfall.cost()) {
            messages.send(player, "visitor-bazaar-cannot-afford", "%cost%", bazaar.format(shortfall.cost()));
            return false;
        }
        long window = service.config().bazaarConfirmMillis();
        long now = System.currentTimeMillis();
        armed.values().removeIf(stale -> stale.until() < now);
        Armed pending = armed.get(id);
        boolean live = pending != null && pending.offerId().equals(offer.id()) && pending.until() >= now;
        // a price that has dropped since the first click is still what they agreed to; one that rose isn't
        if (window > 0 && !(live && shortfall.cost() <= pending.cost() * (1 + 1e-9))) {
            armed.put(id, new Armed(offer.id(), shortfall.cost(), now + window));
            messages.send(player, live ? "visitor-bazaar-price-rose" : "visitor-bazaar-confirm",
                    "%cost%", bazaar.format(shortfall.cost()), "%time%", Durations.format(window));
            return false;
        }
        armed.remove(id);
        buying.add(id);
        buyAll(id, offer.id(), shortfall.needs()).whenComplete((progress, failure) -> {
            buying.remove(id);
            finish(id, offer.id(), progress, failure);
        }).exceptionally(failure -> {
            logger.log(Level.SEVERE, "Serving a visitor after a Bazaar purchase failed for " + id, failure);
            return null;
        });
        return true;
    }

    // still queued and the player's visitor data readable; it can leave while the page is open
    private boolean waiting(UUID id, String offerId) {
        return !service.store().readOnly(id) && service.store().get(id).offer(offerId) != null;
    }

    private boolean isArmed(UUID player, String offerId) {
        Armed pending = armed.get(player);
        return pending != null && pending.offerId().equals(offerId) && pending.until() >= System.currentTimeMillis();
    }

    // one listing after another; the Bazaar takes one trade per player at a time
    private CompletableFuture<Progress> buyAll(UUID id, String offerId, List<Need> needs) {
        double multiplier = service.config().bazaarMultiplier();
        CompletableFuture<Progress> chain = CompletableFuture.completedFuture(new Progress(0, 0, null));
        for (Need need : needs) {
            chain = chain.thenCompose(sofar -> {
                Player player = Bukkit.getPlayer(id);
                if (sofar.failure() != null || player == null) {
                    return CompletableFuture.completedFuture(sofar);
                }
                if (!waiting(id, offerId)) {
                    return CompletableFuture.completedFuture(new Progress(sofar.spent(), sofar.bought(),
                            messages.raw("visitor-bazaar-left")));
                }
                // the price may only have moved in the player's favour since they confirmed
                return bazaar.buy(player, need.listing(), need.missing(), need.quote() * multiplier, multiplier)
                        .thenApply(purchase -> purchase.ok()
                                ? new Progress(sofar.spent() + purchase.cost(), sofar.bought() + 1, null)
                                : new Progress(sofar.spent(), sofar.bought(), purchase.message() == null
                                        ? messages.raw("visitor-bazaar-generic") : purchase.message()));
            });
        }
        return chain;
    }

    private void finish(UUID id, String offerId, Progress progress, Throwable failure) {
        Player player = Bukkit.getPlayer(id);
        if (failure != null) {
            logger.log(Level.SEVERE, "Buying visitor items from RoyalBazaar for " + id
                    + " failed part way; check their balance and inventory.", failure);
            if (player != null) {
                messages.send(player, "visitor-bazaar-failed", "%reason%", messages.raw("visitor-bazaar-generic"));
            }
            return;
        }
        if (player == null) {
            return;
        }
        if (progress.failure() != null) {
            if (progress.bought() > 0) {
                messages.send(player, "visitor-bazaar-partly", "%cost%", bazaar.format(progress.spent()),
                        "%reason%", progress.failure());
            } else {
                messages.send(player, "visitor-bazaar-failed", "%reason%", progress.failure());
            }
            return;
        }
        messages.send(player, "visitor-bazaar-bought", "%cost%", bazaar.format(progress.spent()));
        service.accept(player, offerId);
    }

    private String names(List<Offer.Wanted> items) {
        List<String> names = new ArrayList<>();
        for (Offer.Wanted wanted : items) {
            names.add(service.itemName(wanted));
        }
        return String.join(messages.raw("visitor-missing-separator"), names);
    }
}
