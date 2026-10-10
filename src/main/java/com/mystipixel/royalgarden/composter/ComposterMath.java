package com.mystipixel.royalgarden.composter;

import java.util.random.RandomGenerator;

/**
 * Composter arithmetic. Pure, so it can be tested without a server.
 *
 * <p>One Compost every {@link Machine#interval()} while it holds a Compost's worth of matter and fuel and
 * has room; the clock only runs while all three hold, so a stopped Composter keeps no partial progress.
 * Catching up is additive (advancing a then b equals advancing a + b, bonus rolls aside), which is what
 * lets an offline Composter catch up in one step.
 */
public final class ComposterMath {

    // Slack for comparing stored matter and fuel against a cost, so 0.1 * 3 still pays for 0.3.
    private static final double EPSILON = 1e-9;

    // Above this many Compost at once, bonus Compost is the expected share rather than one roll each.
    private static final long ROLL_LIMIT = 10_000;

    private ComposterMath() {
    }

    /**
     * A Composter as its upgrades leave it.
     *
     * @param interval    milliseconds per Compost
     * @param maxStored   the most finished Compost it holds; 0 or less for no limit
     * @param bonusChance chance, 0 to 1, that a finished Compost comes with a second one
     */
    public record Machine(long interval, double matterCost, double fuelCost, long maxStored, double bonusChance) {
    }

    /**
     * What a Composter holds.
     *
     * @param progress milliseconds already spent on the Compost being made
     */
    public record State(double matter, double fuel, long stored, long progress) {
    }

    /** The state after some time, with how many Compost were made and how many of them were bonus. */
    public record Result(State state, long made, long bonus) {
    }

    public enum Status { RUNNING, NO_MATTER, NO_FUEL, FULL }

    /** Being full is reported before running dry. */
    public static Status status(State state, Machine machine) {
        if (machine.maxStored() > 0 && state.stored() >= machine.maxStored()) {
            return Status.FULL;
        }
        if (state.matter() + EPSILON < machine.matterCost()) {
            return Status.NO_MATTER;
        }
        if (state.fuel() + EPSILON < machine.fuelCost()) {
            return Status.NO_FUEL;
        }
        return Status.RUNNING;
    }

    /** Unlimited when it costs nothing. */
    public static long affordable(double have, double cost) {
        if (cost <= 0) {
            return Long.MAX_VALUE;
        }
        return (long) Math.floor(Math.max(0, have) / cost + EPSILON);
    }

    /**
     * Run a Composter for {@code elapsed} milliseconds.
     *
     * @param random rolls bonus Compost; null to make none, which is how placeholders project a state
     *               without deciding any luck
     */
    public static Result advance(State state, Machine machine, long elapsed, RandomGenerator random) {
        if (elapsed <= 0 || machine.interval() <= 0 || status(state, machine) != Status.RUNNING) {
            return new Result(state, 0, 0);
        }
        long total = saturatedAdd(Math.max(0, state.progress()), elapsed);
        long possible = total / machine.interval();
        long room = machine.maxStored() > 0 ? machine.maxStored() - state.stored() : Long.MAX_VALUE;
        long made = Math.min(possible, Math.min(room, Math.min(affordable(state.matter(), machine.matterCost()),
                affordable(state.fuel(), machine.fuelCost()))));
        long bonus = Math.min(bonus(made, machine.bonusChance(), random), room - made);
        double matter = Math.max(0, state.matter() - made * Math.max(0, machine.matterCost()));
        double fuel = Math.max(0, state.fuel() - made * Math.max(0, machine.fuelCost()));
        long progress = total - made * machine.interval();
        State after = new State(matter, fuel, state.stored() + made + bonus, progress);
        if (status(after, machine) != Status.RUNNING) {
            after = new State(matter, fuel, after.stored(), 0);   // it stopped; nothing is half-made
        }
        return new Result(after, made, bonus);
    }

    private static long bonus(long made, double chance, RandomGenerator random) {
        double p = Math.clamp(chance, 0.0, 1.0);
        if (made <= 0 || p <= 0 || random == null) {
            return 0;
        }
        if (made > ROLL_LIMIT) {
            return Math.round(made * p);
        }
        long out = 0;
        for (long i = 0; i < made; i++) {
            if (random.nextDouble() < p) {
                out++;
            }
        }
        return out;
    }

    /** -1 while the Composter is stopped. */
    public static long untilNext(State state, Machine machine) {
        if (machine.interval() <= 0 || status(state, machine) != Status.RUNNING) {
            return -1;
        }
        return Math.max(0, machine.interval() - Math.max(0, state.progress()) % machine.interval());
    }

    /** +100% halves it. Never below 1ms. */
    public static long interval(long base, double speedPercent) {
        double factor = 1.0 + Math.max(0.0, speedPercent) / 100.0;
        return Math.max(1, Math.round(Math.max(1, base) / factor));
    }

    /** {@code reductionPercent} is held between 0 and 100. */
    public static double reduced(double cost, double reductionPercent) {
        return Math.max(0, cost) * (1.0 - Math.clamp(reductionPercent, 0.0, 100.0) / 100.0);
    }

    /** How many items worth {@code value} each fit between {@code current} and {@code cap}. */
    public static long fits(double current, double cap, double value) {
        if (value <= 0) {
            return 0;
        }
        return Math.max(0, (long) Math.floor((cap - current) / value + EPSILON));
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }
}
