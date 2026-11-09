package forge.util;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

public class AITimeoutTrackerTest {
    private static final long SEC = TimeUnit.SECONDS.toNanos(1);

    @Test
    public void aBudgetRunsOutOnTheLastStepAndStaysOut() {
        Assert.assertTrue(AITimeoutTracker.armBudget(2));
        try {
            final long stops = AITimeoutTracker.budgetStops();
            AITimeoutTracker.spendStep();
            AITimeoutTracker.check(); // one step left: must not throw
            Assert.assertThrows(AITimeoutTracker.Expired.class, AITimeoutTracker::spendStep);
            Assert.assertThrows(AITimeoutTracker.Expired.class, AITimeoutTracker::check);
            Assert.assertThrows(AITimeoutTracker.Expired.class, AITimeoutTracker::spendStep);
            Assert.assertEquals(AITimeoutTracker.budgetStops(), stops + 1, "one decision, one stop");
        } finally {
            AITimeoutTracker.disarmBudget();
        }
        AITimeoutTracker.check(); // the next decision starts clean
    }

    @Test
    public void withoutABudgetStepsCostNothing() {
        Assert.assertFalse(AITimeoutTracker.armBudget(0));
        for (int i = 0; i < 1000; i++) {
            AITimeoutTracker.spendStep();
        }
        AITimeoutTracker.check();
    }

    @Test
    public void aNestedDecisionSpendsTheOuterBudget() {
        Assert.assertTrue(AITimeoutTracker.armBudget(3));
        try {
            AITimeoutTracker.spendStep();
            Assert.assertFalse(AITimeoutTracker.armBudget(100), "a simulated game must not get a fresh budget");
            AITimeoutTracker.spendStep();
            Assert.assertThrows(AITimeoutTracker.Expired.class, AITimeoutTracker::spendStep);
        } finally {
            AITimeoutTracker.disarmBudget();
        }
    }

    @Test
    public void aStepBudgetPassesThatManyChecksAndStopsOnTheNext() {
        final AITimeoutTracker.Steps steps = AITimeoutTracker.armSteps(3);
        Assert.assertNotNull(steps);
        try {
            final long stops = AITimeoutTracker.stepStops();
            AITimeoutTracker.check();
            AITimeoutTracker.check();
            Assert.assertFalse(AITimeoutTracker.isStopped(), "one step left");
            AITimeoutTracker.check();
            Assert.assertTrue(AITimeoutTracker.isStopped(), "the next check would throw");
            Assert.assertEquals(AITimeoutTracker.stepStops(), stops, "a stop counts when a check is refused");
            Assert.assertThrows(AITimeoutTracker.Expired.class, AITimeoutTracker::check);
            Assert.assertThrows(AITimeoutTracker.Expired.class, AITimeoutTracker::check);
            Assert.assertEquals(AITimeoutTracker.stepStops(), stops + 1, "one decision, one stop");
            Assert.assertEquals(steps.spent(), 3);
        } finally {
            AITimeoutTracker.disarmSteps(steps);
        }
        AITimeoutTracker.check(); // the next decision starts clean
        Assert.assertNull(AITimeoutTracker.currentSteps());
    }

    @Test
    public void withoutAStepBudgetChecksCostNothing() {
        Assert.assertNull(AITimeoutTracker.armSteps(0));
        final long stops = AITimeoutTracker.stepStops();
        for (int i = 0; i < 1000; i++) {
            AITimeoutTracker.check();
        }
        Assert.assertEquals(AITimeoutTracker.stepStops(), stops);
        AITimeoutTracker.disarmSteps(null); // what a caller that armed nothing does
    }

    @Test
    public void aNestedDecisionSpendsTheOuterSteps() {
        final AITimeoutTracker.Steps outer = AITimeoutTracker.armSteps(2);
        try {
            AITimeoutTracker.check();
            Assert.assertNull(AITimeoutTracker.armSteps(100), "a nested decision must not get fresh steps");
            AITimeoutTracker.check();
            Assert.assertThrows(AITimeoutTracker.Expired.class, AITimeoutTracker::check);
        } finally {
            AITimeoutTracker.disarmSteps(outer);
        }
    }

    @Test
    public void anEvalThreadSpendsTheDecisionsSteps() throws Exception {
        final AITimeoutTracker.Steps steps = AITimeoutTracker.armSteps(5);
        try {
            AITimeoutTracker.check();
            final int[] passed = {0};
            final Thread eval = new Thread(() -> {
                final AITimeoutTracker.Steps previous = AITimeoutTracker.adoptSteps(steps);
                try {
                    while (true) {
                        AITimeoutTracker.check();
                        passed[0]++;
                    }
                } catch (AITimeoutTracker.Expired e) {
                    // the budget ran out here, not on the deciding thread
                } finally {
                    AITimeoutTracker.restoreSteps(previous);
                }
            });
            eval.start();
            eval.join(2000);
            Assert.assertEquals(passed[0], 4, "the eval thread gets what the deciding thread left");
            Assert.assertThrows(AITimeoutTracker.Expired.class, AITimeoutTracker::check);
        } finally {
            AITimeoutTracker.disarmSteps(steps);
        }
    }

    @Test
    public void mostStepsIsTheLargestDecision() {
        AITimeoutTracker.resetMostSteps();
        for (final int checks : new int[] {4, 9, 2}) {
            final AITimeoutTracker.Steps steps = AITimeoutTracker.armSteps(100);
            for (int i = 0; i < checks; i++) {
                AITimeoutTracker.check();
            }
            AITimeoutTracker.disarmSteps(steps);
        }
        Assert.assertEquals(AITimeoutTracker.mostSteps(), 9);
    }

    @Test
    public void aDeadlineCountsOnceHoweverOftenItThrows() {
        final long expiries = AITimeoutTracker.deadlineExpiries();
        final Object t = AITimeoutTracker.arm(System.nanoTime() - 1, false);
        try {
            Assert.assertThrows(AITimeoutTracker.Expired.class, AITimeoutTracker::check);
            Assert.assertThrows(AITimeoutTracker.Expired.class, AITimeoutTracker::check);
        } finally {
            AITimeoutTracker.disarm(t);
        }
        Assert.assertEquals(AITimeoutTracker.deadlineExpiries(), expiries + 1);
    }

    @Test
    public void checkIsNoOpWhenNotArmed() {
        AITimeoutTracker.check();
        Assert.assertEquals(AITimeoutTracker.remainingNanos(), Long.MAX_VALUE);
    }

    @Test(expectedExceptions = AITimeoutTracker.Expired.class)
    public void checkThrowsAfterDeadline() {
        final Object t = AITimeoutTracker.arm(System.nanoTime() - 1, false);
        try {
            AITimeoutTracker.check();
        } finally {
            AITimeoutTracker.disarm(t);
        }
    }

    @Test
    public void disarmRestoresPreviousState() {
        final Object t = AITimeoutTracker.arm(System.nanoTime() - 1, false);
        AITimeoutTracker.disarm(t);
        AITimeoutTracker.check(); // must not throw
    }

    @Test
    public void nestedArmCanOnlyTighten() {
        final long now = System.nanoTime();
        final Object outer = AITimeoutTracker.arm(now + 10 * SEC, false);
        final Object inner = AITimeoutTracker.arm(now + 60 * SEC, false);
        try {
            Assert.assertTrue(AITimeoutTracker.remainingNanos() <= 10 * SEC);
        } finally {
            AITimeoutTracker.disarm(inner);
            AITimeoutTracker.disarm(outer);
        }
        Assert.assertEquals(AITimeoutTracker.remainingNanos(), Long.MAX_VALUE);
    }

    @Test
    public void interruptIsHonouredOnlyWhenRequested() {
        final Object t = AITimeoutTracker.arm(System.nanoTime() + 10 * SEC, true);
        Thread.currentThread().interrupt();
        try {
            AITimeoutTracker.check();
            Assert.fail("expected Expired");
        } catch (AITimeoutTracker.Expired expected) {
            // ok
        } finally {
            Thread.interrupted(); // clear the flag for other tests
            AITimeoutTracker.disarm(t);
        }
    }

    @Test
    public void unarmedCallerGetsABoundedWait() {
        Assert.assertTrue(AITimeoutTracker.remainingNanos(910L, 5 * SEC) <= 5 * SEC);
    }

    @Test
    public void barIsHiddenBeforeShowThresholdAndAfterEnd() {
        AITimeoutTracker.begin(911, 10 * SEC);
        Assert.assertTrue(AITimeoutTracker.isActive(911));
        Assert.assertTrue(AITimeoutTracker.getRemainingFraction(911) < 0f);
        AITimeoutTracker.end(911);
        Assert.assertFalse(AITimeoutTracker.isActive(911));
    }

    @Test
    public void leakedSessionDoesNotPoisonNextOne() throws Exception {
        final long old = AITimeoutTracker.staleGraceNanos;
        AITimeoutTracker.staleGraceNanos = TimeUnit.MILLISECONDS.toNanos(50);
        try {
            AITimeoutTracker.begin(912, TimeUnit.MILLISECONDS.toNanos(10)); // never ended
            Thread.sleep(150);
            AITimeoutTracker.begin(912, 5 * SEC);
            try {
                Assert.assertTrue(AITimeoutTracker.deadlineNanos(912, 0) - System.nanoTime() > 4 * SEC);
            } finally {
                AITimeoutTracker.end(912);
            }
        } finally {
            AITimeoutTracker.staleGraceNanos = old;
        }
    }

    @Test
    public void workerThreadStopsAtDeadline() throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        final boolean[] stopped = {false};
        final Thread w = new Thread(() -> {
            final Object t = AITimeoutTracker.arm(deadline, true);
            try {
                while (true) { AITimeoutTracker.check(); }
            } catch (AITimeoutTracker.Expired e) {
                stopped[0] = true;
            } finally {
                AITimeoutTracker.disarm(t);
            }
        });
        w.start();
        w.join(2000);
        Assert.assertTrue(stopped[0], "busy loop with check() must abort at the deadline");
    }

    @Test
    public void sessionsOfDifferentGamesDoNotShareDeadlinesOrBars() {
        // every game numbers its players from 0: same player id, different game
        final long a = AITimeoutTracker.key(7001, 0);
        final long b = AITimeoutTracker.key(7002, 0);
        Assert.assertNotEquals(a, b);
        AITimeoutTracker.begin(a, 10 * SEC);
        try {
            Assert.assertTrue(AITimeoutTracker.isActive(a));
            Assert.assertFalse(AITimeoutTracker.isActive(b), "a session in game 1 must not be visible in game 2");
            Assert.assertTrue(AITimeoutTracker.deadlineNanos(b, 1 * SEC) - System.nanoTime() <= 1 * SEC,
                    "game 2 must get its own fallback deadline, not game 1's");
        } finally {
            AITimeoutTracker.end(a);
        }
    }

    @Test
    public void idleSlotsArePurgedAndKeysStayUsable() {
        for (int g = 0; g < 500; g++) { // many finished games
            final long k = AITimeoutTracker.key(8000 + g, 1);
            AITimeoutTracker.begin(k, 5 * SEC);
            AITimeoutTracker.end(k);
        }
        final long live = AITimeoutTracker.key(9000, 1);
        AITimeoutTracker.begin(live, 5 * SEC);
        try {
            Assert.assertTrue(AITimeoutTracker.isActive(live));
        } finally {
            AITimeoutTracker.end(live);
        }
    }
}
