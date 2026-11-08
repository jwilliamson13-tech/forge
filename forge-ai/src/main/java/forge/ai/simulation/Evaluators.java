package forge.ai.simulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The evaluators a seat can be given by name, as {@code forge sim -eval} does. A name gives the {@link SearchScorer}
 * to set on the seat's {@link forge.ai.LobbyPlayerAi}. A new evaluator is one more name here.
 * <p>
 * A family of evaluators that takes an argument, written {@code prefix:argument}, is registered from outside with
 * {@link #register}. The ONNX evaluator is one: {@code onnx:DIR} names an export bundle, and its code and ONNX Runtime
 * live in forge-gui-desktop, which depends on this module and not the other way round. So forge-gui-desktop registers
 * the family when {@code forge sim} starts, and this class only knows it by its prefix.
 */
public final class Evaluators {
    /** Forge's own evaluator, unchanged: {@link SearchScorer#FORGE}. The default. */
    public static final String FORGE = "forge";
    /** A {@link RandomEvaluator} through the seam. */
    public static final String RANDOM = "random";
    /** A {@link PassthroughEvaluator} through the seam. */
    public static final String PASSTHROUGH = "passthrough";
    /** The evaluators this module has by itself. {@link #names} adds the registered families. */
    public static final List<String> NAMES = List.of(FORGE, RANDOM, PASSTHROUGH);
    /** Between a family's prefix and its argument. */
    public static final char SEPARATOR = ':';

    /**
     * The scorers of one family, such as one per ONNX bundle. {@code argument} is the text after the prefix and its
     * colon, never empty. A family that can't give a scorer for it throws {@link IllegalArgumentException} with the
     * reason, such as a folder that holds no bundle.
     */
    @FunctionalInterface
    public interface Family {
        SearchScorer scorer(String argument, Long seed);
    }

    private record Registered(String argumentName, Family family) {
    }

    private static final Map<String, Registered> FAMILIES = new LinkedHashMap<>();

    private Evaluators() {
    }

    /**
     * Makes {@code prefix:argument} a name, with {@code family} giving its scorers. {@code argumentName} is what the
     * help and the list of names show for the argument, such as {@code DIR}. Registering a prefix again replaces it.
     */
    public static synchronized void register(String prefix, String argumentName, Family family) {
        if (prefix.isEmpty() || prefix.indexOf(SEPARATOR) >= 0 || NAMES.contains(prefix)) {
            throw new IllegalArgumentException("not a prefix for a family of evaluators: " + prefix);
        }
        FAMILIES.put(prefix, new Registered(argumentName, family));
    }

    /** Takes the family off again. For tests. */
    public static synchronized void unregister(String prefix) {
        FAMILIES.remove(prefix);
    }

    /** Every name a seat can be given: this module's, then each registered family as {@code prefix:ARGUMENT}. */
    public static synchronized List<String> names() {
        List<String> names = new ArrayList<>(NAMES);
        FAMILIES.forEach((prefix, registered) -> names.add(prefix + SEPARATOR + registered.argumentName()));
        return names;
    }

    /**
     * The scorer behind an evaluator name, or null if there is no such evaluator. {@code seed} seeds an evaluator that
     * draws, so that it replays; null leaves it unseeded. A registered family that refuses the argument throws
     * {@link IllegalArgumentException}, and {@link #problem} says why without throwing.
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
                Registered registered = familyOf(name);
                if (registered == null) {
                    return null;
                }
                String argument = argumentOf(name);
                if (argument.isEmpty()) {
                    throw new IllegalArgumentException(name + " names no " + registered.argumentName());
                }
                return registered.family().scorer(argument, seed);
        }
    }

    /**
     * Why {@code name} can't be given to a seat, or null if it can. Asking gets the scorer once, so a family that
     * loads something, such as a model, loads it here, and a failure stops the run before any game.
     */
    public static String problem(String name) {
        try {
            if (scorer(name, null) != null) {
                return null;
            }
            return "Unknown evaluator - " + name + ". Available evaluators: " + String.join(", ", names());
        } catch (IllegalArgumentException e) {
            return "-eval " + name + " can't be used: " + e.getMessage();
        }
    }

    /**
     * The name without its argument: the family's prefix for a registered family, such as {@code onnx}, else the
     * name itself. Short enough for a player's name, where a bundle's path wouldn't be.
     */
    public static String family(String name) {
        return familyOf(name) == null ? name : name.substring(0, name.indexOf(SEPARATOR));
    }

    /** The text after the first colon, or null for a name with none. */
    public static String argumentOf(String name) {
        int at = name.indexOf(SEPARATOR);
        return at < 0 ? null : name.substring(at + 1);
    }

    private static synchronized Registered familyOf(String name) {
        int at = name.indexOf(SEPARATOR);
        return at < 0 ? null : FAMILIES.get(name.substring(0, at));
    }

    /**
     * The seed for an evaluator of seat {@code seat}, counted from 1, in a run seeded with {@code runSeed}. Each seat
     * gets its own stream, and none of them is the game's own {@code new Random(runSeed)}.
     */
    public static long seatSeed(long runSeed, int seat) {
        return runSeed ^ (0x9E3779B97F4A7C15L * seat);
    }
}
