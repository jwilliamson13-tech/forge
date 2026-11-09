package forge.ai.simulation;

import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Forge's own evaluator, asked through the seam: {@link GameStateEvaluator}'s {@code value} for the state, normalized
 * to a probability. Run inside a {@link TwoLookScorer}, it should play as the stock search does, which is what proves
 * the seam adds nothing of its own.
 * <p>
 * The normalization is chosen so that {@link TwoLookScorer#toScore} gives back Forge's integer exactly, which keeps
 * every tie and every ordering Forge's evaluator makes:
 * <ul>
 *     <li>A live score is copied as it is. Forge's live scores are in the low thousands, and anything past
 *     {@link #LIVE_LIMIT} either side is clamped there.</li>
 *     <li>Forge's evaluator also returns its win and loss bands for a game that is still on, when the combat it plays
 *     out for this turn ends the game. Those keep their order too: a win {@code t} turns in maps to
 *     {@code LIVE_SCORE_MAX - t}, a loss to {@code LIVE_SCORE_MIN + t}, above and below every live score.</li>
 * </ul>
 * For the real state, the first look, {@link #estimate} also hands the scorer such a band score as the end of the
 * game, so the {@link TwoLookScorer} returns it as it is, value and available value alike, and
 * {@link SimulationController#shouldRecurse} stops searching at a win as it does in stock. A loss keeps Forge's loss
 * band the same way, and the search goes on past it, as in stock.
 * <p>
 * Otherwise the available value isn't Forge's. The {@link TwoLookScorer} takes it from Forge's {@code value} of the
 * copy with the AI's sick creatures removed, where Forge marks those creatures as worth 0 in place. A band Forge gives
 * that copy, and not the real state, stays a live score at the top or bottom of the range: stock never sees that end.
 */
public final class PassthroughEvaluator implements WinProbabilityEvaluator {
    /** Scores kept free at each end of the live range for Forge's discounted wins and losses, one per turn. */
    static final int TERMINAL_TURNS = GameStateEvaluator.MAX_DISCOUNTED_TURNS;
    /** The largest live score copied as it is, either side of 0. */
    public static final int LIVE_LIMIT = TwoLookScorer.LIVE_SCORE_MAX - TERMINAL_TURNS - 1;

    @Override
    public double winProbability(Game game, Player aiPlayer) {
        return toProbability(new GameStateEvaluator().getScoreForGameState(game, aiPlayer).value);
    }

    /** Forge's score once, as a probability, and as the end of the game when it is one of Forge's bands. */
    @Override
    public Estimate estimate(Game game, Player aiPlayer) {
        Score forge = new GameStateEvaluator().getScoreForGameState(game, aiPlayer);
        boolean ends = GameStateEvaluator.isWinning(forge.value) || GameStateEvaluator.isLosing(forge.value);
        return new Estimate(toProbability(forge.value), ends ? forge : null);
    }

    /** The probability whose {@link TwoLookScorer#toScore} is {@link #toLiveScore} of this Forge score. */
    public static double toProbability(int forgeScore) {
        return TwoLookScorer.toProbability(toLiveScore(forgeScore));
    }

    /** Where a score of Forge's evaluator lands in the live range. Strictly increasing over every score it returns. */
    static int toLiveScore(int forgeScore) {
        if (GameStateEvaluator.isWinning(forgeScore)) {
            long turns = ((long) Integer.MAX_VALUE - forgeScore) / GameStateEvaluator.GAME_OVER_TURN_PENALTY;
            return TwoLookScorer.LIVE_SCORE_MAX - (int) Math.min(turns, TERMINAL_TURNS);
        }
        if (GameStateEvaluator.isLosing(forgeScore)) {
            long turns = ((long) forgeScore - (Integer.MIN_VALUE + 1)) / GameStateEvaluator.GAME_OVER_TURN_PENALTY;
            return TwoLookScorer.LIVE_SCORE_MIN + (int) Math.min(turns, TERMINAL_TURNS);
        }
        return Math.max(-LIVE_LIMIT, Math.min(LIVE_LIMIT, forgeScore));
    }

    @Override
    public String toString() {
        return Evaluators.PASSTHROUGH;
    }
}
