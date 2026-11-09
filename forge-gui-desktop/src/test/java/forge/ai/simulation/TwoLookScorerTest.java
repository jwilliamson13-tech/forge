package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The evaluator seam (#22): a win probability in, Forge's two integers out, the same way for every evaluator.
 */
public class TwoLookScorerTest extends SimulationTest {

    /** A stand-in for a model: the AI's creatures' power and toughness plus its hand, squashed into 0 to 1. */
    private static double boardCount(Game game, Player ai) {
        int count = ai.getCardsIn(ZoneType.Hand).size();
        for (Card c : ai.getCreaturesInPlay()) {
            count += c.getNetPower() + c.getNetToughness();
        }
        return 1.0 / (1.0 + Math.exp(-(count - 10) / 10.0));
    }

    /** Remembers every state it was asked about. */
    private static final class Recorder implements WinProbabilityEvaluator {
        final List<Game> games = new ArrayList<>();
        final List<Integer> creatures = new ArrayList<>();

        @Override
        public double winProbability(Game game, Player ai) {
            games.add(game);
            creatures.add(ai.getCreaturesInPlay().size());
            return boardCount(game, ai);
        }
    }

    private static final WinProbabilityEvaluator NEVER_CALLED = (game, ai) -> {
        throw new AssertionError("the evaluator was asked about a finished game");
    };

    private static void useScorer(Player p, SearchScorer scorer) {
        ((LobbyPlayerAi) p.getLobbyPlayer()).setSearchScorer(scorer);
    }

    private static void assertLive(String what, int score) {
        AssertJUnit.assertTrue(what + " is below the live range: " + score, score >= TwoLookScorer.LIVE_SCORE_MIN);
        AssertJUnit.assertTrue(what + " is above the live range: " + score, score <= TwoLookScorer.LIVE_SCORE_MAX);
        AssertJUnit.assertFalse(what + " reads as a win: " + score, GameStateEvaluator.isWinning(score));
        AssertJUnit.assertFalse(what + " reads as a loss: " + score, GameStateEvaluator.isLosing(score));
        AssertJUnit.assertTrue(what + " is the failed-simulation sentinel", score != Integer.MIN_VALUE);
    }

    private Score terminalScore(SearchScorer scorer, int turn, boolean aiWins) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, turn);
        (aiWins ? opp : ai).setLife(0, null);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue("the game should be over", game.isGameOver());
        return scorer.score(game, ai);
    }

    @Test
    public void probabilitiesMapLinearlyOntoTheLiveRange() {
        AssertJUnit.assertEquals(TwoLookScorer.LIVE_SCORE_MIN, TwoLookScorer.toScore(0.0));
        AssertJUnit.assertEquals(0, TwoLookScorer.toScore(0.5));
        AssertJUnit.assertEquals(TwoLookScorer.LIVE_SCORE_MAX, TwoLookScorer.toScore(1.0));
        AssertJUnit.assertEquals(TwoLookScorer.LIVE_SCORE_MAX / 2, TwoLookScorer.toScore(0.75));
        for (double p : new double[] {0.0, 0.5, 1.0}) {
            assertLive("p = " + p, TwoLookScorer.toScore(p));
            AssertJUnit.assertEquals(p, TwoLookScorer.toProbability(TwoLookScorer.toScore(p)), 1e-9);
        }
        // a probability a billionth higher is still a higher score
        AssertJUnit.assertTrue(TwoLookScorer.toScore(0.5 + 1e-9) > TwoLookScorer.toScore(0.5));
    }

    @Test
    public void outOfRangeProbabilitiesAreClampedAndNaNIsRefused() {
        AssertJUnit.assertEquals(TwoLookScorer.LIVE_SCORE_MAX, TwoLookScorer.toScore(1.5));
        AssertJUnit.assertEquals(TwoLookScorer.LIVE_SCORE_MAX, TwoLookScorer.toScore(Double.POSITIVE_INFINITY));
        AssertJUnit.assertEquals(TwoLookScorer.LIVE_SCORE_MIN, TwoLookScorer.toScore(-0.5));
        AssertJUnit.assertEquals(TwoLookScorer.LIVE_SCORE_MIN, TwoLookScorer.toScore(Double.NEGATIVE_INFINITY));
        try {
            TwoLookScorer.toScore(Double.NaN);
            AssertJUnit.fail("NaN should be refused");
        } catch (IllegalArgumentException expected) {
            // a broken evaluator should be loud, not scored as anything
        }
    }

    /**
     * The live range stays clear of the terminal bands at both ends, even near the furthest turn Forge discounts. At
     * turn 1,000,000 itself a win scores exactly the bound of isWinning, which is strict, so the test stops a turn short.
     */
    @Test
    public void theLiveRangeNeverTouchesTheTerminalBands() {
        Score latestWin = terminalScore(SearchScorer.FORGE, 999_999, true);
        Score earliestLoss = terminalScore(SearchScorer.FORGE, 0, false);
        Score latestLoss = terminalScore(SearchScorer.FORGE, 1_000_000, false);
        AssertJUnit.assertTrue(GameStateEvaluator.isWinning(latestWin.value));
        AssertJUnit.assertTrue(GameStateEvaluator.isLosing(earliestLoss.value));
        AssertJUnit.assertTrue(GameStateEvaluator.isLosing(latestLoss.value));
        AssertJUnit.assertTrue(TwoLookScorer.LIVE_SCORE_MAX < latestWin.value);
        AssertJUnit.assertTrue(TwoLookScorer.LIVE_SCORE_MIN > latestLoss.value);
    }

    @Test
    public void liveGamesScoreInsideTheLiveRange() {
        for (double p : new double[] {0.0, 0.5, 1.0}) {
            Game game = initAndCreateGame();
            Player ai = game.getPlayers().get(1);
            addCard("Forest", ai);
            addCard("Grizzly Bears", ai);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
            game.getAction().checkStateEffects(true);

            Score score = new TwoLookScorer((g, pl) -> p).score(game, ai);
            assertLive("value for p = " + p, score.value);
            assertLive("available value for p = " + p, score.availableValue);
            AssertJUnit.assertEquals(TwoLookScorer.toScore(p), score.value);
        }
    }

    @Test
    public void terminalStatesKeepForgesScores() {
        TwoLookScorer scorer = new TwoLookScorer(NEVER_CALLED);
        for (int turn : new int[] {1, 7, 40}) {
            for (boolean aiWins : new boolean[] {true, false}) {
                Score forge = terminalScore(SearchScorer.FORGE, turn, aiWins);
                Score twoLook = terminalScore(scorer, turn, aiWins);
                AssertJUnit.assertEquals("value, turn " + turn + (aiWins ? " win" : " loss"), forge.value, twoLook.value);
                AssertJUnit.assertEquals("available, turn " + turn + (aiWins ? " win" : " loss"),
                        forge.availableValue, twoLook.availableValue);
                AssertJUnit.assertEquals(aiWins, GameStateEvaluator.isWinning(twoLook.value));
                AssertJUnit.assertEquals(!aiWins, GameStateEvaluator.isLosing(twoLook.value));
            }
        }
    }

    /** Sees the given end coming from every state, and fails the test if the scorer asks it for a second look. */
    private static WinProbabilityEvaluator foreseeing(Score end) {
        return new WinProbabilityEvaluator() {
            @Override
            public double winProbability(Game game, Player ai) {
                throw new AssertionError("the scorer took a second look at a state whose end was foreseen");
            }

            @Override
            public Estimate estimate(Game game, Player ai) {
                return new Estimate(0.5, end);
            }
        };
    }

    private static Score winOnTurn(int turn) {
        return new Score(Integer.MAX_VALUE - turn * GameStateEvaluator.GAME_OVER_TURN_PENALTY);
    }

    private static Score lossOnTurn(int turn) {
        return new Score(Integer.MIN_VALUE + 1 + turn * GameStateEvaluator.GAME_OVER_TURN_PENALTY);
    }

    /** A board where the second look would be taken: a sick creature before combat. */
    private Game boardWithASickCreature() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCard("Grizzly Bears", ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        game.getAction().checkStateEffects(true);
        return game;
    }

    /**
     * An end the evaluator sees coming from the real state keeps its band in both integers, with no second look, as
     * stock Forge scores a state whose coming combat ends the game.
     */
    @Test
    public void aForeseenEndKeepsItsBand() {
        for (Score end : new Score[] {winOnTurn(3), lossOnTurn(3)}) {
            Game game = boardWithASickCreature();
            Player ai = game.getPlayers().get(1);
            Score score = new TwoLookScorer(foreseeing(end)).score(game, ai);
            AssertJUnit.assertTrue(end + " vs " + score, end.equals(score));
        }
    }

    /** The search stops at a foreseen win, as it does at Forge's, and goes on past a foreseen loss, as in stock. */
    @Test
    public void aForeseenWinStopsTheSearchAndALossDoesNot() {
        Game game = boardWithASickCreature();
        Player ai = game.getPlayers().get(1);
        Score win = new TwoLookScorer(foreseeing(winOnTurn(3))).score(game, ai);
        Score loss = new TwoLookScorer(foreseeing(lossOnTurn(3))).score(game, ai);
        Score live = new TwoLookScorer((g, p) -> 1.0).score(game, ai);
        AssertJUnit.assertFalse("a win stops the search", new SimulationController(win).shouldRecurse());
        AssertJUnit.assertTrue("a loss doesn't", new SimulationController(loss).shouldRecurse());
        AssertJUnit.assertTrue("nor does the top of the live range", new SimulationController(live).shouldRecurse());
    }

    @Test
    public void aForeseenEndOutsideTheBandsIsRefused() {
        Game game = boardWithASickCreature();
        Player ai = game.getPlayers().get(1);
        for (Score notAnEnd : new Score[] {new Score(5), new Score(TwoLookScorer.LIVE_SCORE_MAX), new Score(Integer.MIN_VALUE)}) {
            try {
                new TwoLookScorer(foreseeing(notAnEnd)).score(game, ai);
                AssertJUnit.fail("took " + notAnEnd + " as the end of the game");
            } catch (IllegalStateException expected) {
                // a live score or the failed-simulation sentinel is no end
            }
        }
    }

    /** An evaluator that doesn't override estimate sees no end, and is scored by two looks as before. */
    @Test
    public void theDefaultEstimateSeesNoEnd() {
        Game game = boardWithASickCreature();
        Player ai = game.getPlayers().get(1);
        WinProbabilityEvaluator quarter = (g, p) -> 0.25;
        WinProbabilityEvaluator.Estimate estimate = quarter.estimate(game, ai);
        AssertJUnit.assertEquals(0.25, estimate.probability(), 0.0);
        AssertJUnit.assertNull(estimate.end());

        Recorder recorder = new Recorder();
        new TwoLookScorer(recorder).score(game, ai);
        AssertJUnit.assertEquals("two looks", 2, recorder.games.size());
    }

    private Game gameWithBearsInHand(PhaseType phase) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCard("Forest", ai);
        addCard("Forest", ai);
        addCardToZone("Grizzly Bears", ai, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(phase, ai);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static SpellAbility bearsSpell(Player ai) {
        Card bears = ai.getCardsIn(ZoneType.Hand).get(0);
        SpellAbility sa = bears.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    /** Cast before combat, a creature is worth more in the long run, but nothing more this turn: the veto holds. */
    @Test
    public void castingACreatureBeforeCombatLowersTheAvailableValue() {
        Game game = gameWithBearsInHand(PhaseType.MAIN1);
        Player ai = game.getPlayers().get(1);
        useScorer(ai, new TwoLookScorer(TwoLookScorerTest::boardCount));

        GameSimulator sim = createSimulator(ai);
        Score notCast = sim.getScoreForOrigGame();
        Score cast = sim.simulateSpellAbility(bearsSpell(ai));

        AssertJUnit.assertEquals("one creature on the battlefield after the cast", 1,
                sim.getSimulatedGameState().getPlayers().get(1).getCreaturesInPlay().size());
        AssertJUnit.assertTrue("the cast should be worth more in the long run: " + cast + " vs " + notCast,
                cast.value > notCast.value);
        AssertJUnit.assertTrue("the cast should not raise the available value: " + cast + " vs " + notCast,
                cast.availableValue < notCast.availableValue);
    }

    @Test
    public void castingACreatureAfterCombatRaisesTheAvailableValue() {
        Game game = gameWithBearsInHand(PhaseType.MAIN2);
        Player ai = game.getPlayers().get(1);
        useScorer(ai, new TwoLookScorer(TwoLookScorerTest::boardCount));

        GameSimulator sim = createSimulator(ai);
        Score notCast = sim.getScoreForOrigGame();
        Score cast = sim.simulateSpellAbility(bearsSpell(ai));

        AssertJUnit.assertTrue(cast.value > notCast.value);
        AssertJUnit.assertEquals(cast.value, cast.availableValue);
        AssertJUnit.assertTrue(cast.availableValue > notCast.availableValue);
    }

    /** The picker goes through the seam: before combat it holds the creature, after combat it casts it. */
    @Test
    public void thePickerHoldsACreatureUntilAfterCombat() {
        for (PhaseType phase : new PhaseType[] {PhaseType.MAIN1, PhaseType.MAIN2}) {
            Game game = gameWithBearsInHand(phase);
            Player ai = game.getPlayers().get(1);
            Recorder recorder = new Recorder();
            useScorer(ai, new TwoLookScorer(recorder));

            SpellAbilityPicker picker = new SpellAbilityPicker(ai);
            List<SpellAbility> candidates = picker.getCandidateSpellsAndAbilities();
            AssertJUnit.assertEquals("the bears are the only candidate", 1, candidates.size());
            SpellAbility chosen = picker.chooseSpellAbilityToPlay(null);

            AssertJUnit.assertFalse("the picker should have scored through the evaluator", recorder.games.isEmpty());
            if (phase == PhaseType.MAIN1) {
                AssertJUnit.assertNull("cast before combat, the bears should be vetoed", chosen);
            } else {
                AssertJUnit.assertNotNull("after combat the bears should be cast", chosen);
                AssertJUnit.assertEquals("Grizzly Bears", chosen.getHostCard().getName());
            }
        }
    }

    @Test
    public void theSecondLookIsACopyWithoutTheSickCreatures() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Card sick = addCard("Grizzly Bears", ai);
        Card ready = addCard("Runeclaw Bear", ai);
        ready.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue(sick.isSick());
        AssertJUnit.assertFalse(ready.isSick());

        Recorder recorder = new Recorder();
        Score score = new TwoLookScorer(recorder).score(game, ai);

        AssertJUnit.assertEquals("two looks", 2, recorder.games.size());
        AssertJUnit.assertSame("the first look is the real game", game, recorder.games.get(0));
        AssertJUnit.assertEquals(Integer.valueOf(2), recorder.creatures.get(0));
        AssertJUnit.assertNotSame(game, recorder.games.get(1));
        AssertJUnit.assertTrue(recorder.games.get(1).isSimulationCopy());
        AssertJUnit.assertEquals("only the creature that isn't sick is left", Integer.valueOf(1), recorder.creatures.get(1));
        AssertJUnit.assertTrue(score.availableValue < score.value);

        AssertJUnit.assertEquals("the real game keeps both creatures", 2, ai.getCreaturesInPlay().size());
        AssertJUnit.assertTrue(sick.isInZone(ZoneType.Battlefield));
    }

    @Test
    public void aRemovedLordNoLongerPumps() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCard("Lord of Atlantis", ai);
        Card merfolk = addCard("Merfolk of the Pearl Trident", ai);
        merfolk.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertEquals("the lord pumps the merfolk", 2, merfolk.getNetPower());

        final List<Integer> merfolkPower = new ArrayList<>();
        new TwoLookScorer((g, p) -> {
            for (Card c : p.getCreaturesInPlay()) {
                if (c.getName().equals("Merfolk of the Pearl Trident")) {
                    merfolkPower.add(c.getNetPower());
                }
            }
            return 0.5;
        }).score(game, ai);

        AssertJUnit.assertEquals(List.of(2, 1), merfolkPower);
        AssertJUnit.assertEquals("the real merfolk is still pumped", 2, merfolk.getNetPower());
    }

    @Test
    public void oneLookWhenThereIsNothingToRemove() {
        // no sick creature before combat
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCard("Runeclaw Bear", ai).setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        Recorder recorder = new Recorder();
        Score score = new TwoLookScorer(recorder).score(game, ai);
        AssertJUnit.assertEquals(1, recorder.games.size());
        AssertJUnit.assertEquals(score.value, score.availableValue);

        // a sick creature after combat is available
        Game later = initAndCreateGame();
        Player laterAi = later.getPlayers().get(1);
        addCard("Grizzly Bears", laterAi);
        later.getPhaseHandler().devModeSet(PhaseType.MAIN2, laterAi);
        later.getAction().checkStateEffects(true);
        Recorder laterRecorder = new Recorder();
        Score laterScore = new TwoLookScorer(laterRecorder).score(later, laterAi);
        AssertJUnit.assertEquals(1, laterRecorder.games.size());
        AssertJUnit.assertEquals(laterScore.value, laterScore.availableValue);
    }

    /** An opponent's sick creature is no resource of the AI's, so it stays. */
    @Test
    public void onlyTheAisOwnCreaturesAreRemoved() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCard("Grizzly Bears", opp);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        Recorder recorder = new Recorder();
        new TwoLookScorer(recorder).score(game, ai);
        AssertJUnit.assertEquals(1, recorder.games.size());
    }

    @Test
    public void theDefaultIsForgesEvaluatorUnchanged() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCard("Forest", ai);
        addCard("Grizzly Bears", ai);
        addCardToZone("Llanowar Elves", ai, ZoneType.Hand);
        addCard("Runeclaw Bear", opp);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);

        AssertJUnit.assertSame(SearchScorer.FORGE, SearchScorer.of(ai));
        AssertJUnit.assertSame(SearchScorer.FORGE, SearchScorer.of(opp));
        Score forge = new GameStateEvaluator().getScoreForGameState(game, ai);
        Score seam = SearchScorer.of(ai).score(game, ai);
        AssertJUnit.assertTrue(forge + " vs " + seam, forge.equals(seam));
        AssertJUnit.assertTrue("Forge's own score has the sick creature unavailable", forge.availableValue < forge.value);
    }

    /** The search's copies share the lobby player, so their AIs score the way the real one does. */
    @Test
    public void copiesOfTheGameKeepTheScorer() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        TwoLookScorer scorer = new TwoLookScorer(TwoLookScorerTest::boardCount);
        useScorer(ai, scorer);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);

        GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        Player copiedAi = copier.find(ai);
        AssertJUnit.assertNotSame(ai, copiedAi);
        AssertJUnit.assertSame(scorer, SearchScorer.of(copiedAi));
        AssertJUnit.assertSame(SearchScorer.FORGE, SearchScorer.of(copier.find(game.getPlayers().get(0))));
    }
}
