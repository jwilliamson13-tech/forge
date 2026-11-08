package forge.ai.simulation;

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
 */
@FunctionalInterface
public interface WinProbabilityEvaluator {
    /** The probability, from 0 to 1, that {@code aiPlayer} wins {@code game} from here. */
    double winProbability(Game game, Player aiPlayer);
}
