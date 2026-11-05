package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** What a suspended card is worth to the search, against the same card in hand (issue 51). */
public class SimSearchSuspendValueTest extends SimulationTest {

    private Player setUp(Game game, boolean simSearch) {
        game.AI_SIM_SEARCH = simSearch;
        Player p = game.getPlayers().get(1);
        p.setTeam(0);
        game.getPlayers().get(0).setTeam(1);
        addCard("Mountain", p);
        return p;
    }

    private void toMain2(Game game, Player p) {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        game.getAction().checkStateEffects(true);
    }

    /** The score of a Rift Bolt in exile with a time counter, less the score of the same Rift Bolt in hand. */
    private int suspendedLessInHand(boolean simSearch) {
        Game game = initAndCreateGame();
        Player p = setUp(game, simSearch);
        Card riftBolt = addCardToZone("Rift Bolt", p, ZoneType.Hand);
        toMain2(game, p);
        int inHand = new GameStateEvaluator().getScoreForGameState(game, p).value;

        Card exiled = game.getAction().exile(riftBolt, null, null);
        exiled.addCounterInternal(CounterEnumType.TIME, 1, p, false, null, null);
        AssertJUnit.assertTrue(exiled.hasSuspend());
        return new GameStateEvaluator().getScoreForGameState(game, p).value - inHand;
    }

    @Test
    public void defaultPlayScoresSuspendingAsLosingACard() {
        AssertJUnit.assertEquals(-5, suspendedLessInHand(false));
    }

    @Test
    public void forgeSimScoresASuspendedCardAboveTheSameCardInHand() {
        AssertJUnit.assertEquals(1, suspendedLessInHand(true));
    }

    @Test
    public void aCopiedGameKeepsTheValueOfASuspendedCard() {
        Game game = initAndCreateGame();
        Player p = setUp(game, true);
        Card riftBolt = addCardToZone("Rift Bolt", p, ZoneType.Hand);
        toMain2(game, p);
        game.getAction().exile(riftBolt, null, null).addCounterInternal(CounterEnumType.TIME, 1, p, false, null, null);

        GameCopier copier = new GameCopier(game);
        Game copy = copier.makeCopy();
        AssertJUnit.assertEquals(new GameStateEvaluator().getScoreForGameState(game, p).value,
                new GameStateEvaluator().getScoreForGameState(copy, copier.find(p)).value);
    }

    @Test
    public void aSuspendedCardDoesNotStopTheSearchFromPlayingOn() {
        Game game = initAndCreateGame();
        Player p = setUp(game, true);
        Card riftBolt = addCardToZone("Rift Bolt", p, ZoneType.Hand);
        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        toMain2(game, p);
        game.getAction().exile(riftBolt, null, null).addCounterInternal(CounterEnumType.TIME, 1, p, false, null, null);

        SpellAbility chosen = new SpellAbilityPicker(p).chooseSpellAbilityToPlay(null);
        AssertJUnit.assertNotNull(chosen);
        AssertJUnit.assertEquals(bolt, chosen.getHostCard());
    }

    private SpellAbility pickWithOneMana(boolean simSearch) {
        Game game = initAndCreateGame();
        Player p = setUp(game, simSearch);
        addCardToZone("Rift Bolt", p, ZoneType.Hand);
        toMain2(game, p);
        return new SpellAbilityPicker(p).chooseSpellAbilityToPlay(null);
    }

    @Test
    public void forgeSimSuspendsWhenItCantHardCast() {
        SpellAbility chosen = pickWithOneMana(true);
        AssertJUnit.assertNotNull(chosen);
        AssertJUnit.assertTrue(chosen.isKeyword(Keyword.SUSPEND));
    }

    @Test
    public void defaultPlayNeverPicksSuspendInTheSearch() {
        AssertJUnit.assertNull(pickWithOneMana(false));
    }

    @Test
    public void forgeSimStillHardCastsToKillACreature() {
        Game game = initAndCreateGame();
        Player p = setUp(game, true);
        addCard("Mountain", p);
        addCard("Mountain", p);
        addCardToZone("Rift Bolt", p, ZoneType.Hand);
        addCard("Runeclaw Bear", game.getPlayers().get(0));
        toMain2(game, p);

        SpellAbility chosen = new SpellAbilityPicker(p).chooseSpellAbilityToPlay(null);
        AssertJUnit.assertNotNull(chosen);
        AssertJUnit.assertFalse(chosen.isKeyword(Keyword.SUSPEND));
    }
}
