package forge.ai;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.AITimeoutTracker;

/** forge sim -aisteps: a heuristic decision counts its checks instead of watching the clock. */
public class HeuristicStepBudgetTest extends AITest {

    /** The heuristic AI in its second main phase with two Forests and a Grizzly Bears to cast. */
    private Game gameWithABearToCast(final int steps) {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        addCard("Forest", p);
        addCard("Forest", p);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        game.getAction().checkStateEffects(true);
        game.AI_STEP_BUDGET = steps;
        return game;
    }

    private static List<SpellAbility> decide(final Game game) {
        return ((PlayerControllerAi) game.getPlayers().get(1).getController()).getAi().chooseSpellAbilityToPlay();
    }

    private static boolean castsTheBear(final List<SpellAbility> chosen) {
        return chosen != null && !chosen.isEmpty() && chosen.get(0).getHostCard().getName().equals("Grizzly Bears");
    }

    /** The checks the decision spends when nothing stops it. */
    private long stepsTheDecisionTakes() {
        AITimeoutTracker.resetMostSteps();
        AssertJUnit.assertTrue(castsTheBear(decide(gameWithABearToCast(1_000_000))));
        return AITimeoutTracker.mostSteps();
    }

    @Test
    public void withoutAStepBudgetTheAiCastsTheBear() {
        long stops = AITimeoutTracker.stepStops();
        AssertJUnit.assertTrue(castsTheBear(decide(gameWithABearToCast(0))));
        AssertJUnit.assertEquals(stops, AITimeoutTracker.stepStops());
    }

    @Test
    public void aBudgetThatIsEnoughChangesNothing() {
        long steps = stepsTheDecisionTakes();
        AssertJUnit.assertTrue("the decision makes checks to count", steps > 0);
        long stops = AITimeoutTracker.stepStops();
        AssertJUnit.assertTrue(castsTheBear(decide(gameWithABearToCast((int) steps))));
        AssertJUnit.assertEquals("exactly enough steps is enough", stops, AITimeoutTracker.stepStops());
    }

    @Test
    public void theSameDecisionTakesTheSameSteps() {
        AssertJUnit.assertEquals(stepsTheDecisionTakes(), stepsTheDecisionTakes());
    }

    @Test
    public void oneStepShortStopsTheDecision() {
        long steps = stepsTheDecisionTakes();
        long stops = AITimeoutTracker.stepStops();
        decide(gameWithABearToCast((int) steps - 1));
        AssertJUnit.assertEquals(stops + 1, AITimeoutTracker.stepStops());
    }

    @Test
    public void aDecisionThatRunsOutEarlyPasses() {
        long stops = AITimeoutTracker.stepStops();
        AssertJUnit.assertNull(decide(gameWithABearToCast(1)));
        AssertJUnit.assertEquals(stops + 1, AITimeoutTracker.stepStops());
        AssertJUnit.assertNull("a stop leaves no budget behind for the next decision", AITimeoutTracker.currentSteps());
        AITimeoutTracker.check();
    }
}
