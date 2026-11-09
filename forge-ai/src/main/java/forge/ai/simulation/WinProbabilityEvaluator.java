package forge.ai.simulation;

import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.player.Player;

/**
 * The evaluator seam: a game state in, the chance that one player wins it out.
 * <p>
 * An implementation only produces the number. {@link TwoLookScorer} turns it into the two integers the simulation
 * search compares: it scores finished games itself with Forge's win and loss bands, asks for a second probability on
 * a copy of the game for the available value, and maps both into a fixed range clear of those bands. So an
 * implementation never sees a finished game and never has to know about Forge's scores.
 * <p>
 * What an implementation can rely on and must keep to:
 * <ul>
 *     <li>{@code aiPlayer} is still in the game ({@link Player#isInGame()}), and the probability is from its side.</li>
 *     <li>The game may be the real one, one of the search's copies, or a copy the scorer derived from either of
 *     those. It must be read, not changed.</li>
 *     <li>Return a value from 0 to 1. Anything above or below is clamped, and NaN is an error.</li>
 *     <li>The search replays from its seed only if scoring draws nothing from {@link forge.util.MyRandom}. An
 *     implementation that needs randomness keeps its own generator.</li>
 *     <li>It is called on the game thread, once or twice for every state the search scores, so it should be fast.</li>
 * </ul>
 * An evaluator that looks ahead, as Forge's own does when it plays out this turn's coming combat, may find that the
 * game ends there. It says so through {@link #estimate}, and the scorer then gives the state Forge's band for that
 * end, as stock Forge does. The default sees no end coming.
 */
@FunctionalInterface
public interface WinProbabilityEvaluator {
    /** The probability, from 0 to 1, that {@code aiPlayer} wins {@code game} from here. */
    double winProbability(Game game, Player aiPlayer);

    /**
     * What the evaluator says of a state: its probability, and the end of the game it sees coming, if it sees one.
     * {@link TwoLookScorer} asks this for the real state, the first look, and {@link #winProbability} for the second.
     * <p>
     * The default is {@link #winProbability} with no end. An evaluator that overrides it keeps the two consistent:
     * {@code probability} is what {@link #winProbability} returns for the same state.
     */
    default Estimate estimate(Game game, Player aiPlayer) {
        return new Estimate(winProbability(game, aiPlayer), null);
    }

    /**
     * A probability, and the end of the game the evaluator sees coming from the state, or null for none. An end is a
     * score in one of Forge's bands, {@link GameStateEvaluator#isWinning} or {@link GameStateEvaluator#isLosing}, with
     * {@code availableValue} equal to {@code value}: what {@link GameStateEvaluator} returns when its look at the
     * coming combat ends the game.
     */
    record Estimate(double probability, Score end) {
    }
}
