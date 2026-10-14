package com.mystipixel.royalgarden.hooks;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * RoyalBazaar's {@code BazaarApi}, reached by reflection: the extension neither compiles against RoyalBazaar
 * nor needs it installed, and RoyalBazaar can enable after RoyalSkyblock. Main thread only.
 */
public final class BazaarHook {

    private static final String API = "com.mystipixel.royalbazaar.api.BazaarApi";

    /** {@code message} is the Bazaar's reason, worded for the player, when {@code ok} is false. */
    public record Purchase(boolean ok, double cost, String message) {
    }

    private final Logger logger;
    private Object api;
    private Method listing;
    private Method quote;
    private Method buy;
    private Method format;
    private Method balance;
    private boolean broken;

    public BazaarHook(Logger logger) {
        this.logger = logger;
    }

    public boolean available() {
        return api() != null;
    }

    /** The listing that sells {@code item}, or null. Scans every listing, so keep the answer. */
    public String listing(ItemStack item) {
        Object live = api();
        return live == null ? null : (String) call(listing, live, item);
    }

    /** The Bazaar's price for {@code amount}, or -1 when it won't sell them. */
    public double quote(String listingId, long amount) {
        Object live = api();
        return live == null ? -1 : (double) call(quote, live, listingId, amount);
    }

    /**
     * Buys exactly {@code amount}, refused when the price times {@code markup} is above {@code maxCost} or
     * they don't all fit. Completes on the main thread.
     */
    public CompletableFuture<Purchase> buy(Player player, String listingId, long amount, double maxCost,
                                           double markup) {
        Object live = api();
        if (live == null) {
            return CompletableFuture.completedFuture(new Purchase(false, 0, null));
        }
        CompletableFuture<?> pending = (CompletableFuture<?>) call(buy, live, player, listingId, amount, maxCost, markup);
        return pending.thenApply(BazaarHook::purchase);
    }

    /** What the player has to spend at the Bazaar, or 0 without it. */
    public double balance(Player player) {
        Object live = api();
        return live == null ? 0 : (double) call(balance, live, player);
    }

    public String format(double amount) {
        Object live = api();
        return live == null ? String.format("%,.2f", amount) : (String) call(format, live, amount);
    }

    private static Purchase purchase(Object result) {
        try {
            Class<?> type = result.getClass();
            return new Purchase((boolean) type.getMethod("ok").invoke(result),
                    (double) type.getMethod("cost").invoke(result), (String) type.getMethod("message").invoke(result));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("RoyalBazaar's purchase result has changed shape", e);
        }
    }

    // null while RoyalBazaar is missing or not trading yet. Looked up each time: a reload of RoyalBazaar
    // replaces the provider, and the old one is closed.
    private Object api() {
        if (broken) {
            return null;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin("RoyalBazaar");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }
        for (RegisteredServiceProvider<?> registration : Bukkit.getServicesManager().getRegistrations(plugin)) {
            if (!registration.getService().getName().equals(API)) {
                continue;
            }
            Object provider = registration.getProvider();
            if (provider != api && !bind(registration.getService())) {
                return null;
            }
            api = provider;
            return api;
        }
        return null;
    }

    private boolean bind(Class<?> type) {
        try {
            listing = type.getMethod("listing", ItemStack.class);
            quote = type.getMethod("quote", String.class, long.class);
            buy = type.getMethod("buy", Player.class, String.class, long.class, double.class, double.class);
            format = type.getMethod("format", double.class);
            balance = type.getMethod("balance", Player.class);
            logger.info("RoyalBazaar found: visitors can buy missing items from the Bazaar.");
            return true;
        } catch (NoSuchMethodException e) {
            broken = true;
            logger.warning("This RoyalBazaar has no matching API (" + e.getMessage()
                    + "); buying visitor items from the Bazaar is off. Update RoyalBazaar.");
            return false;
        }
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("RoyalBazaar " + method.getName() + " failed", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("RoyalBazaar " + method.getName() + " is not accessible", e);
        }
    }
}
