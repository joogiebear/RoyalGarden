package com.mystipixel.royalgarden.composter;

import com.mystipixel.royalgarden.composter.ComposterMath.Machine;
import com.mystipixel.royalgarden.composter.ComposterMath.Result;
import com.mystipixel.royalgarden.composter.ComposterMath.State;
import com.mystipixel.royalgarden.composter.ComposterMath.Status;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComposterMathTest {

    private static final long MINUTE = 60_000;
    // One Compost a minute for 10 matter and 5 fuel, holding up to 100.
    private static final Machine MACHINE = new Machine(MINUTE, 10, 5, 100, 0);

    @Test
    void catchesUpWholeCompostsAndKeepsTheRemainder() {
        Result result = ComposterMath.advance(new State(1000, 1000, 0, 0), MACHINE, 5 * MINUTE + 20_000, null);
        assertEquals(5, result.made());
        assertEquals(new State(950, 975, 5, 20_000), result.state());
    }

    @Test
    void progressCarriesOverBetweenCatchUps() {
        State half = ComposterMath.advance(new State(1000, 1000, 0, 0), MACHINE, 30_000, null).state();
        assertEquals(0, half.stored());
        assertEquals(30_000, half.progress());
        Result rest = ComposterMath.advance(half, MACHINE, 30_000, null);
        assertEquals(1, rest.made());
        assertEquals(0, rest.state().progress());
    }

    @Test
    void catchingUpInStepsMatchesCatchingUpAtOnce() {
        State start = new State(95, 1000, 0, 0);
        State once = ComposterMath.advance(start, MACHINE, 37 * MINUTE + 5, null).state();
        State steps = start;
        for (long left = 37 * MINUTE + 5; left > 0; left -= 7_777) {
            steps = ComposterMath.advance(steps, MACHINE, Math.min(7_777, left), null).state();
        }
        assertEquals(once, steps);
    }

    @Test
    void organicMatterLimitsOutput() {
        Result result = ComposterMath.advance(new State(35, 1000, 0, 0), MACHINE, 60 * MINUTE, null);
        assertEquals(3, result.made());
        assertEquals(new State(5, 985, 3, 0), result.state());
        assertEquals(Status.NO_MATTER, ComposterMath.status(result.state(), MACHINE));
    }

    @Test
    void fuelLimitsOutput() {
        Result result = ComposterMath.advance(new State(1000, 12, 0, 0), MACHINE, 60 * MINUTE, null);
        assertEquals(2, result.made());
        assertEquals(Status.NO_FUEL, ComposterMath.status(result.state(), MACHINE));
        assertEquals(0, result.state().progress());
    }

    @Test
    void theOutputCapLimitsOutput() {
        Result result = ComposterMath.advance(new State(1000, 1000, 98, 0), MACHINE, 60 * MINUTE, null);
        assertEquals(2, result.made());
        assertEquals(100, result.state().stored());
        assertEquals(Status.FULL, ComposterMath.status(result.state(), MACHINE));
    }

    @Test
    void aStoppedComposterDoesNotCountTheTimeItWasStopped() {
        State dry = new State(5, 1000, 0, 0);
        assertEquals(0, ComposterMath.advance(dry, MACHINE, 60 * MINUTE, null).made());
        // Refilled after an hour: the hour it sat empty buys nothing.
        State refilled = new State(100, 1000, 0, 0);
        assertEquals(0, ComposterMath.advance(refilled, MACHINE, 59_999, null).made());
        assertEquals(1, ComposterMath.advance(refilled, MACHINE, MINUTE, null).made());
    }

    @Test
    void nothingHappensWithoutTime() {
        State state = new State(100, 100, 1, 10);
        assertEquals(state, ComposterMath.advance(state, MACHINE, 0, null).state());
        assertEquals(state, ComposterMath.advance(state, MACHINE, -5, null).state());
    }

    @Test
    void noCapMeansNoLimit() {
        Machine uncapped = new Machine(MINUTE, 1, 1, 0, 0);
        assertEquals(500, ComposterMath.advance(new State(500, 500, 10_000, 0), uncapped, 1000 * MINUTE, null).made());
    }

    @Test
    void bonusCompostIsRolledAndStaysUnderTheCap() {
        Machine always = new Machine(MINUTE, 1, 1, 100, 1.0);
        Result doubled = ComposterMath.advance(new State(10, 10, 0, 0), always, 10 * MINUTE, new Random(1));
        assertEquals(10, doubled.made());
        assertEquals(10, doubled.bonus());
        assertEquals(20, doubled.state().stored());
        // The bonus uses no matter or fuel.
        assertEquals(0, doubled.state().matter(), 1e-9);

        Result nearlyFull = ComposterMath.advance(new State(10, 10, 95, 0), always, 10 * MINUTE, new Random(1));
        assertEquals(5, nearlyFull.made());
        assertEquals(100, nearlyFull.state().stored());

        Machine never = new Machine(MINUTE, 1, 1, 100, 0.0);
        assertEquals(0, ComposterMath.advance(new State(10, 10, 0, 0), never, 10 * MINUTE, new Random(1)).bonus());
        // A projection (no random) never invents bonus Compost.
        assertEquals(0, ComposterMath.advance(new State(10, 10, 0, 0), always, 10 * MINUTE, null).bonus());
    }

    @Test
    void bonusIsRoughlyTheChance() {
        Machine quarter = new Machine(1, 0, 0, 0, 0.25);
        long bonus = ComposterMath.advance(new State(0, 0, 0, 0), quarter, 8_000, new Random(42)).bonus();
        assertEquals(2_000, bonus, 150);
        // Past the roll limit the expected share is used.
        assertEquals(12_500, ComposterMath.advance(new State(0, 0, 0, 0), quarter, 50_000, new Random(42)).bonus());
    }

    @Test
    void speedUpgradesShortenTheInterval() {
        assertEquals(MINUTE, ComposterMath.interval(MINUTE, 0));
        assertEquals(30_000, ComposterMath.interval(MINUTE, 100));
        assertEquals(50_000, ComposterMath.interval(MINUTE, 20));
        assertEquals(MINUTE, ComposterMath.interval(MINUTE, -50));
        assertEquals(1, ComposterMath.interval(1, 1_000_000));
    }

    @Test
    void costReductionIsAPercentageHeldInRange() {
        assertEquals(10, ComposterMath.reduced(10, 0), 1e-9);
        assertEquals(7.5, ComposterMath.reduced(10, 25), 1e-9);
        assertEquals(0, ComposterMath.reduced(10, 150), 1e-9);
        assertEquals(10, ComposterMath.reduced(10, -10), 1e-9);
        // A reduced cost is what the machine uses.
        Machine cheaper = new Machine(MINUTE, ComposterMath.reduced(10, 50), ComposterMath.reduced(5, 50), 100, 0);
        assertEquals(7, ComposterMath.advance(new State(35, 1000, 0, 0), cheaper, 60 * MINUTE, null).made());
    }

    @Test
    void fractionalCostsDoNotLoseACompostToRounding() {
        Machine tenth = new Machine(MINUTE, 0.1, 0.1, 0, 0);
        assertEquals(3, ComposterMath.affordable(0.1 + 0.1 + 0.1, 0.1));
        assertEquals(3, ComposterMath.advance(new State(0.3, 0.3, 0, 0), tenth, 10 * MINUTE, null).made());
    }

    @Test
    void untilNextCountsDownAndIsBlankWhenStopped() {
        assertEquals(MINUTE, ComposterMath.untilNext(new State(100, 100, 0, 0), MACHINE));
        assertEquals(15_000, ComposterMath.untilNext(new State(100, 100, 0, 45_000), MACHINE));
        assertEquals(-1, ComposterMath.untilNext(new State(0, 100, 0, 0), MACHINE));
        assertEquals(-1, ComposterMath.untilNext(new State(100, 100, 100, 0), MACHINE));
    }

    @Test
    void fitsCountsWholeItemsUnderTheCap() {
        assertEquals(10, ComposterMath.fits(0, 100, 10));
        assertEquals(3, ComposterMath.fits(65, 100, 10));
        assertEquals(0, ComposterMath.fits(100, 100, 10));
        assertEquals(0, ComposterMath.fits(120, 100, 10));
        assertEquals(0, ComposterMath.fits(0, 100, 0));
        assertEquals(1, ComposterMath.fits(99.9, 100, 0.1));
    }

    @Test
    void statusOrder() {
        assertEquals(Status.FULL, ComposterMath.status(new State(0, 0, 100, 0), MACHINE));
        assertEquals(Status.NO_MATTER, ComposterMath.status(new State(0, 0, 0, 0), MACHINE));
        assertEquals(Status.NO_FUEL, ComposterMath.status(new State(10, 0, 0, 0), MACHINE));
        assertEquals(Status.RUNNING, ComposterMath.status(new State(10, 5, 0, 0), MACHINE));
    }
}
