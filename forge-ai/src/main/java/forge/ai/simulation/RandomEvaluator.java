package forge.ai.simulation;

import forge.game.Game;
import forge.game.player.Player;

import java.util.Random;

/**
 * Noise: every state gets a uniform draw from 0 to 1, whatever is on the board. It proves that the full-simulation
 * AI still finishes games when its evaluator knows nothing.
 * <p>
 * It keeps its own generator and never draws from {@link forge.util.MyRandom}, so the game's own stream is the one it
 * would be without the search scoring anything. Seeded, it gives the same draws in the same order, so a run with a
 * search budget replays.
 */
public final class RandomEvaluator implements WinProbabilityEvaluator {
    private final Random random;

    public RandomEvaluator(long seed) {
        this.random = new Random(seed);
    }

    /** Unseeded, for a run without a seed, which doesn't replay anyway. */
    public RandomEvaluator() {
        this.random = new Random();
    }

    @Override
    public double winProbability(Game game, Player aiPlayer) {
        return random.nextDouble();
    }

    @Override
    public String toString() {
        return Evaluators.RANDOM;
    }
}
