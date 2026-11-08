package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/** The random and passthrough evaluators (#23), and the names a seat picks them by. */
public class EvaluatorsTest extends SimulationTest {

    private static Score forgeWin(int turn) {
        return new Score(Integer.MAX_VALUE - turn * GameStateEvaluator.GAME_OVER_TURN_PENALTY);
    }

    private static Score forgeLoss(int turn) {
        return new Score(Integer.MIN_VALUE + 1 + turn * GameStateEvaluator.GAME_OVER_TURN_PENALTY);
    }

    @Test
    public void namesGiveTheirScorers() {
        AssertJUnit.assertSame(SearchScorer.FORGE, Evaluators.scorer("forge", 1L));
        SearchScorer random = Evaluators.scorer("random", 1L);
        AssertJUnit.assertTrue(((TwoLookScorer) random).getEvaluator() instanceof RandomEvaluator);
        AssertJUnit.assertTrue(((TwoLookScorer) Evaluators.scorer("random", null)).getEvaluator() instanceof RandomEvaluator);
        SearchScorer passthrough = Evaluators.scorer("passthrough", null);
        AssertJUnit.assertTrue(((TwoLookScorer) passthrough).getEvaluator() instanceof PassthroughEvaluator);
        AssertJUnit.assertNull(Evaluators.scorer("Forge", null));
        AssertJUnit.assertNull(Evaluators.scorer("onnx", null));
        for (String name : Evaluators.NAMES) {
            AssertJUnit.assertNotNull(name, Evaluators.scorer(name, null));
        }
    }

    @Test
    public void eachSeatGetsItsOwnSeed() {
        long run = 42;
        AssertJUnit.assertTrue(Evaluators.seatSeed(run, 1) != Evaluators.seatSeed(run, 2));
        AssertJUnit.assertTrue(Evaluators.seatSeed(run, 1) != run);
        AssertJUnit.assertTrue(Evaluators.seatSeed(run, 2) != run);
        AssertJUnit.assertEquals(Evaluators.seatSeed(run, 1), Evaluators.seatSeed(run, 1));
    }

    @Test
    public void randomReplaysFromItsSeedAndStaysInRange() {
        RandomEvaluator a = new RandomEvaluator(7);
        RandomEvaluator b = new RandomEvaluator(7);
        RandomEvaluator other = new RandomEvaluator(8);
        boolean differs = false;
        for (int i = 0; i < 1000; i++) {
            double p = a.winProbability(null, null);
            AssertJUnit.assertEquals(p, b.winProbability(null, null), 0.0);
            AssertJUnit.assertTrue(p >= 0.0 && p < 1.0);
            differs |= p != other.winProbability(null, null);
        }
        AssertJUnit.assertTrue("another seed should give other draws", differs);
    }

    /** The search replays from the game's seed only if scoring draws nothing from the game's stream. */
    @Test
    public void randomDrawsNothingFromTheGamesStream() {
        RandomEvaluator random = new RandomEvaluator(7);
        long before = MyRandom.callCount();
        for (int i = 0; i < 1000; i++) {
            random.winProbability(null, null);
        }
        AssertJUnit.assertEquals(before, MyRandom.callCount());
    }

    @Test
    public void randomScoresALiveGameInsideTheLiveRange() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCard("Forest", ai);
        addCard("Grizzly Bears", ai);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);

        Score score = Evaluators.scorer("random", 3L).score(game, ai);
        for (int s : new int[] {score.value, score.availableValue}) {
            AssertJUnit.assertTrue(s >= TwoLookScorer.LIVE_SCORE_MIN && s <= TwoLookScorer.LIVE_SCORE_MAX);
            AssertJUnit.assertFalse(GameStateEvaluator.isWinning(s) || GameStateEvaluator.isLosing(s));
        }
    }

    /** Through the seam, a live score of Forge's comes back as the same integer. */
    @Test
    public void passthroughGivesBackForgesLiveScoreExactly() {
        int[] scores = {0, 1, -1, 2, 5, -5, 137, 1000, -1000, 123_456, 9_999_999, -9_999_999,
                PassthroughEvaluator.LIVE_LIMIT, -PassthroughEvaluator.LIVE_LIMIT,
                PassthroughEvaluator.LIVE_LIMIT - 1, -PassthroughEvaluator.LIVE_LIMIT + 1};
        for (int s : scores) {
            AssertJUnit.assertEquals("score " + s, s, TwoLookScorer.toScore(PassthroughEvaluator.toProbability(s)));
        }
        for (int s = -20_000; s <= 20_000; s++) {
            AssertJUnit.assertEquals(s, TwoLookScorer.toScore(PassthroughEvaluator.toProbability(s)));
        }
        AssertJUnit.assertEquals(PassthroughEvaluator.LIVE_LIMIT,
                TwoLookScorer.toScore(PassthroughEvaluator.toProbability(PassthroughEvaluator.LIVE_LIMIT + 1)));
        AssertJUnit.assertEquals(-PassthroughEvaluator.LIVE_LIMIT,
                TwoLookScorer.toScore(PassthroughEvaluator.toProbability(-PassthroughEvaluator.LIVE_LIMIT - 1)));
    }

    /**
     * Forge's evaluator returns its bands for a game still on when the combat it plays out ends it. They keep their
     * order through the seam, above and below every live score, without reading as a win or a loss.
     */
    @Test
    public void passthroughKeepsTheOrderOfForgesBands() {
        List<Integer> ascending = new ArrayList<>(List.of(
                forgeLoss(0).value, forgeLoss(1).value, forgeLoss(2).value, forgeLoss(50).value, forgeLoss(999_999).value,
                -PassthroughEvaluator.LIVE_LIMIT - 5, -PassthroughEvaluator.LIVE_LIMIT, -3000, -1, 0, 1, 3000,
                PassthroughEvaluator.LIVE_LIMIT, PassthroughEvaluator.LIVE_LIMIT + 5,
                forgeWin(999_999).value, forgeWin(50).value, forgeWin(2).value, forgeWin(1).value, forgeWin(0).value));
        int previous = Integer.MIN_VALUE;
        for (int i = 0; i < ascending.size(); i++) {
            int forge = ascending.get(i);
            int live = TwoLookScorer.toScore(PassthroughEvaluator.toProbability(forge));
            AssertJUnit.assertTrue(live >= TwoLookScorer.LIVE_SCORE_MIN && live <= TwoLookScorer.LIVE_SCORE_MAX);
            AssertJUnit.assertFalse(GameStateEvaluator.isWinning(live) || GameStateEvaluator.isLosing(live));
            // the two scores past the limit are clamped onto it, so only those two may tie
            boolean clamped = forge == -PassthroughEvaluator.LIVE_LIMIT || forge == PassthroughEvaluator.LIVE_LIMIT + 5;
            AssertJUnit.assertTrue(forge + " should land above " + previous + ", got " + live,
                    clamped ? live >= previous : live > previous);
            previous = live;
        }
        AssertJUnit.assertEquals(TwoLookScorer.LIVE_SCORE_MAX, TwoLookScorer.toScore(PassthroughEvaluator.toProbability(forgeWin(0).value)));
        AssertJUnit.assertEquals(TwoLookScorer.LIVE_SCORE_MIN, TwoLookScorer.toScore(PassthroughEvaluator.toProbability(forgeLoss(0).value)));
    }

    private Game boardWithBears(PhaseType phase, boolean aiHasBears, boolean oppHasBears) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        addCard("Forest", ai);
        addCard("Forest", ai);
        addCardToZone("Llanowar Elves", ai, ZoneType.Hand);
        if (aiHasBears) {
            addCard("Grizzly Bears", ai).setSickness(false);
        }
        if (oppHasBears) {
            addCard("Runeclaw Bear", opp).setSickness(false);
        }
        game.getPhaseHandler().devModeSet(phase, ai);
        game.getAction().checkStateEffects(true);
        return game;
    }

    /** The ordering half of the passthrough test in Spec 3: two states rank the way Forge ranks them. */
    @Test
    public void passthroughRanksStatesAsForgeDoes() {
        SearchScorer passthrough = Evaluators.scorer("passthrough", null);
        for (PhaseType phase : new PhaseType[] {PhaseType.MAIN1, PhaseType.MAIN2}) {
            List<Game> games = List.of(boardWithBears(phase, false, true), boardWithBears(phase, false, false),
                    boardWithBears(phase, true, true), boardWithBears(phase, true, false));
            List<Integer> forge = new ArrayList<>();
            List<Integer> seam = new ArrayList<>();
            for (Game game : games) {
                Player ai = game.getPlayers().get(1);
                forge.add(new GameStateEvaluator().getScoreForGameState(game, ai).value);
                seam.add(passthrough.score(game, ai).value);
            }
            AssertJUnit.assertEquals(phase + ": the same values, so the same order", forge, seam);
            for (int i = 1; i < forge.size(); i++) {
                AssertJUnit.assertTrue(phase + ": the boards should rank strictly: " + forge, forge.get(i) > forge.get(i - 1));
            }
        }
    }

    /**
     * Where passthrough and stock part: the available value. Forge marks a sick creature as worth 0 and keeps the mana
     * it makes in the mana base. The second look takes the creature away, mana and all.
     */
    @Test
    public void passthroughsAvailableValueDropsASickCreaturesMana() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        addCard("Forest", ai);
        Card elves = addCard("Llanowar Elves", ai);
        // a three-drop in hand, so the second source of mana is worth something to the mana base
        addCardToZone("Centaur Courser", ai, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getAction().checkStateEffects(true);
        AssertJUnit.assertTrue(elves.isSick());

        Score forge = new GameStateEvaluator().getScoreForGameState(game, ai);
        Score seam = Evaluators.scorer("passthrough", null).score(game, ai);
        AssertJUnit.assertEquals("the first look is Forge's value", forge.value, seam.value);
        AssertJUnit.assertTrue("Forge holds the sick elves back: " + forge, forge.availableValue < forge.value);
        AssertJUnit.assertTrue("the second look drops the elves' mana as well: " + seam + " vs " + forge,
                seam.availableValue < forge.availableValue);
    }

    /** With nothing sick before combat, passthrough is Forge's score through and through. */
    @Test
    public void passthroughMatchesForgeWhenNothingIsSick() {
        for (PhaseType phase : new PhaseType[] {PhaseType.MAIN1, PhaseType.MAIN2}) {
            Game game = boardWithBears(phase, true, true);
            Player ai = game.getPlayers().get(1);
            Score forge = new GameStateEvaluator().getScoreForGameState(game, ai);
            Score seam = Evaluators.scorer("passthrough", null).score(game, ai);
            AssertJUnit.assertTrue(phase + ": " + forge + " vs " + seam, forge.equals(seam));
        }
    }
}
