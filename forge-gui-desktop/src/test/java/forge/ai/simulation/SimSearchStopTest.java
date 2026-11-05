package forge.ai.simulation;

import java.util.List;
import java.util.Random;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiController;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.AITimeoutTracker;
import forge.util.MyRandom;

/** A decision stopped by the budget plays the best line found so far, in forge sim only (issue 51). */
public class SimSearchStopTest extends SimulationTest {

    private Player seat(Game game) {
        Player p = game.getPlayers().get(1);
        p.setTeam(0);
        game.getPlayers().get(0).setTeam(1);
        return p;
    }

    private void limit(Game game, Player p, PhaseType phase, int budget, boolean simSearch) {
        game.AI_SEARCH_BUDGET = budget;
        game.AI_SIM_SEARCH = simSearch;
        game.getPhaseHandler().devModeSet(phase, p);
        game.getAction().checkStateEffects(true);
    }

    private static AiController ai(Player p) {
        return ((PlayerControllerAi) p.getController()).getAi();
    }

    /**
     * A Lightning Bolt with three targets: itself, which is worse than passing, the opponent, which beats passing, and
     * a creature, which is better still. A budget of 3 stops the search before the last one.
     */
    private Card boltWithThreeTargets(Game game, Player p) {
        addCard("Mountain", p);
        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        addCard("Runeclaw Bear", game.getPlayers().get(0));
        return bolt;
    }

    @Test
    public void aStoppedDecisionPlaysTheBestLineFoundSoFar() {
        Game game = initAndCreateGame();
        Player p = seat(game);
        Card bolt = boltWithThreeTargets(game, p);
        limit(game, p, PhaseType.MAIN2, 3, true);

        long stops = AITimeoutTracker.budgetStops();
        long played = AITimeoutTracker.stopsPlayed();
        List<SpellAbility> chosen = ai(p).chooseSpellAbilityToPlay();

        AssertJUnit.assertEquals("the budget has to stop this search", stops + 1, AITimeoutTracker.budgetStops());
        AssertJUnit.assertEquals(played + 1, AITimeoutTracker.stopsPlayed());
        AssertJUnit.assertNotNull(chosen);
        AssertJUnit.assertEquals(bolt, chosen.get(0).getHostCard());
        AssertJUnit.assertEquals("the line's target comes along", game.getPlayers().get(0), chosen.get(0).getTargets().getFirstTargetedPlayer());
    }

    @Test
    public void defaultPlayStillPassesOnAStop() {
        Game game = initAndCreateGame();
        Player p = seat(game);
        boltWithThreeTargets(game, p);
        limit(game, p, PhaseType.MAIN2, 3, false);

        long stops = AITimeoutTracker.budgetStops();
        long played = AITimeoutTracker.stopsPlayed();
        long passed = AITimeoutTracker.stopsPassed();

        AssertJUnit.assertNull(ai(p).chooseSpellAbilityToPlay());
        AssertJUnit.assertEquals(stops + 1, AITimeoutTracker.budgetStops());
        AssertJUnit.assertEquals(played, AITimeoutTracker.stopsPlayed());
        AssertJUnit.assertEquals(passed, AITimeoutTracker.stopsPassed());
    }

    @Test
    public void aStopBeforeAnyLineWasScoredPasses() {
        Game game = initAndCreateGame();
        Player p = seat(game);
        boltWithThreeTargets(game, p);
        limit(game, p, PhaseType.MAIN2, 1, true);

        long passed = AITimeoutTracker.stopsPassed();

        AssertJUnit.assertNull(ai(p).chooseSpellAbilityToPlay());
        AssertJUnit.assertEquals(passed + 1, AITimeoutTracker.stopsPassed());
    }

    @Test
    public void aLineThatAddsNothingAvailableIsNoBetterThanPassing() {
        // A creature cast in main phase 1 raises the value but not the available value, so a finished search waits.
        // The same must hold when the search stops after simulating it.
        Game game = initAndCreateGame();
        Player p = seat(game);
        for (int i = 0; i < 4; i++) {
            addCard("Forest", p);
        }
        addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
        addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        limit(game, p, PhaseType.MAIN1, 2, true);

        long stops = AITimeoutTracker.budgetStops();
        long passed = AITimeoutTracker.stopsPassed();

        AssertJUnit.assertNull(ai(p).chooseSpellAbilityToPlay());
        AssertJUnit.assertEquals("the budget has to stop this search", stops + 1, AITimeoutTracker.budgetStops());
        AssertJUnit.assertEquals(passed + 1, AITimeoutTracker.stopsPassed());
    }

    @Test
    public void aStopInAMainPhaseWithALandStillPlaysALine() {
        Game game = initAndCreateGame();
        Player p = seat(game);
        addCard("Mountain", p);
        addCardToZone("Mountain", p, ZoneType.Hand);
        addCardToZone("Shock", p, ZoneType.Hand);
        addCardToZone("Lava Spike", p, ZoneType.Hand);
        limit(game, p, PhaseType.MAIN1, 2, true);

        long stops = AITimeoutTracker.budgetStops();
        List<SpellAbility> chosen = ai(p).chooseSpellAbilityToPlay();

        AssertJUnit.assertEquals("the budget has to stop this search", stops + 1, AITimeoutTracker.budgetStops());
        AssertJUnit.assertNotNull(chosen);
    }

    @Test
    public void aStopLeavesTheRealGameAsItWas() {
        Game game = initAndCreateGame();
        Player p = seat(game);
        Card bolt = boltWithThreeTargets(game, p);
        limit(game, p, PhaseType.MAIN2, 3, true);

        Random stream = MyRandom.getRandom();
        int life = game.getPlayers().get(0).getLife();
        List<SpellAbility> chosen = ai(p).chooseSpellAbilityToPlay();

        AssertJUnit.assertNotNull(chosen);
        AssertJUnit.assertSame("the game goes on drawing from its own stream", stream, MyRandom.getRandom());
        AssertJUnit.assertNull("no hypothetical cast is left on the card", bolt.getCastSA());
        AssertJUnit.assertEquals(ZoneType.Hand, bolt.getZone().getZoneType());
        AssertJUnit.assertTrue(game.getStack().isEmpty());
        AssertJUnit.assertEquals(life, game.getPlayers().get(0).getLife());
    }

    @Test
    public void aFinishedSearchIsNotCountedAsAStop() {
        Game game = initAndCreateGame();
        Player p = seat(game);
        Card bolt = boltWithThreeTargets(game, p);
        limit(game, p, PhaseType.MAIN2, 100, true);

        long stops = AITimeoutTracker.budgetStops();
        long played = AITimeoutTracker.stopsPlayed();
        List<SpellAbility> chosen = ai(p).chooseSpellAbilityToPlay();

        AssertJUnit.assertEquals(bolt, chosen.get(0).getHostCard());
        AssertJUnit.assertEquals("with the whole search done the creature is the better target", "Runeclaw Bear", chosen.get(0).getTargetCard().getName());
        AssertJUnit.assertEquals(stops, AITimeoutTracker.budgetStops());
        AssertJUnit.assertEquals(played, AITimeoutTracker.stopsPlayed());
    }

    @Test
    public void aStoppedLandDropThatNeverComesIsCounted() {
        Game game = initAndCreateGame();
        Player p = seat(game);
        addCard("Mountain", p);
        addCardToZone("Mountain", p, ZoneType.Hand);
        addCardToZone("Shock", p, ZoneType.Hand);
        limit(game, p, PhaseType.MAIN1, 1, true);

        long missed = AITimeoutTracker.landDropsMissed();
        long withLand = AITimeoutTracker.stopsWithLand();
        AssertJUnit.assertNull(ai(p).chooseSpellAbilityToPlay());
        AssertJUnit.assertEquals(withLand + 1, AITimeoutTracker.stopsWithLand());

        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, p);
        ai(p).chooseSpellAbilityToPlay();
        AssertJUnit.assertEquals(missed + 1, AITimeoutTracker.landDropsMissed());
    }
}
