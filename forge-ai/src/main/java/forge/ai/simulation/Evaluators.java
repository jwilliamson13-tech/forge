package forge.ai.simulation;

import java.util.List;

/**
 * The evaluators a seat can be given by name, as {@code forge sim -eval} does. A name gives the {@link SearchScorer}
 * to set on the seat's {@link forge.ai.LobbyPlayerAi}. A new evaluator is one more name here.
 */
public final class Evaluators {
    /** Forge's own evaluator, unchanged: {@link SearchScorer#FORGE}. The default. */
    public static final String FORGE = "forge";
    /** A {@link RandomEvaluator} through the seam. */
    public static final String RANDOM = "random";
    /** A {@link PassthroughEvaluator} through the seam. */
    public static final String PASSTHROUGH = "passthrough";
    public static final List<String> NAMES = List.of(FORGE, RANDOM, PASSTHROUGH);

    private Evaluators() {
    }

    /**
     * The scorer behind an evaluator name, or null if there is no such evaluator. {@code seed} seeds an evaluator that
     * draws, so that it replays; null leaves it unseeded.
     */
    public static SearchScorer scorer(String name, Long seed) {
        switch (name) {
            case FORGE:
                return SearchScorer.FORGE;
            case RANDOM:
                return new TwoLookScorer(seed == null ? new RandomEvaluator() : new RandomEvaluator(seed));
            case PASSTHROUGH:
                return new TwoLookScorer(new PassthroughEvaluator());
            default:
                return null;
        }
    }

    /**
     * The seed for an evaluator of seat {@code seat}, counted from 1, in a run seeded with {@code runSeed}. Each seat
     * gets its own stream, and none of them is the game's own {@code new Random(runSeed)}.
     */
    public static long seatSeed(long runSeed, int seat) {
        return runSeed ^ (0x9E3779B97F4A7C15L * seat);
    }
}
