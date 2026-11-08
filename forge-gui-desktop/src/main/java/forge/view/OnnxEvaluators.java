package forge.view;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.ai.simulation.Evaluators;
import forge.ai.simulation.SearchScorer;
import forge.ai.simulation.TwoLookScorer;

/**
 * The {@code onnx:DIR} evaluators: a seat's search scores states with the export bundle in {@code DIR}, through
 * {@link TwoLookScorer} over an {@link OnnxEvaluator}. {@link #register} makes the name known to {@link Evaluators},
 * which lives in forge-ai and can't see this module, so {@code forge sim -eval} takes it.
 * <p>
 * Each bundle folder loads once per JVM and is kept, so seats on the same folder share one model, and seats on two
 * folders, such as two generations of one model, each get their own. The folder is told apart by its real path, so two
 * spellings of one folder load it once. A bundle that won't load is refused with the reason, before any game starts.
 * <p>
 * Each load writes {@code ONNX evaluator: loaded ...} to stderr, and at exit each loaded bundle writes its
 * {@link OnnxEvaluator#summary}, in the order they loaded, ending with the folder it came from.
 * {@code -Dforge.onnx.threads=N} gives ONNX Runtime N threads per bundle, 1 if left out, because workers already run
 * in parallel.
 */
public final class OnnxEvaluators {
    /** The family's prefix: {@code onnx:DIR}. */
    public static final String PREFIX = "onnx";
    public static final String THREADS = "forge.onnx.threads";
    /** The properties that picked the bundle and seats before {@code -eval onnx:DIR}. A run that still sets them is refused. */
    static final List<String> RETIRED = List.of("forge.onnx.bundle", "forge.onnx.seats");

    private static final Map<Path, OnnxEvaluator> LOADED = new LinkedHashMap<>();
    private static Thread summaries;

    private OnnxEvaluators() {
    }

    /** Makes {@code onnx:DIR} a name {@link Evaluators#scorer} takes. Safe to call more than once. */
    public static void register() {
        Evaluators.register(PREFIX, "DIR", (folder, seed) -> scorer(folder));
    }

    /** A scorer for one seat, over the folder's evaluator. Two seats on one folder get two scorers and one model. */
    static SearchScorer scorer(final String folder) {
        return new TwoLookScorer(evaluator(folder));
    }

    /** The bundle folder of an {@code onnx:DIR} name as it was given, or null for any other name. */
    public static String folderOf(final String evaluatorName) {
        return evaluatorName.startsWith(PREFIX + Evaluators.SEPARATOR) ? Evaluators.argumentOf(evaluatorName) : null;
    }

    /** Why the run can't go on, if a retired property is still set, or null. */
    static String retiredPropertyProblem() {
        for (final String property : RETIRED) {
            if (System.getProperty(property) != null) {
                return "-eval onnx:DIR replaced -D" + property + ": name each seat's bundle with -eval, in deck order";
            }
        }
        return null;
    }

    /** The evaluator for the bundle in {@code folder}, loaded the first time the folder is asked for. */
    static synchronized OnnxEvaluator evaluator(final String folder) {
        final Path key;
        try {
            key = Path.of(folder).toRealPath();
        } catch (final InvalidPathException | IOException e) {
            throw new IllegalArgumentException("can't load the ONNX bundle " + folder + ": no such folder");
        }
        if (!Files.isDirectory(key)) {
            throw new IllegalArgumentException("can't load the ONNX bundle " + folder + ": not a folder");
        }
        OnnxEvaluator evaluator = LOADED.get(key);
        if (evaluator != null) {
            return evaluator;
        }
        try {
            evaluator = OnnxEvaluator.load(key, Integer.parseInt(System.getProperty(THREADS, "1")));
        } catch (final Exception e) {
            throw new IllegalArgumentException("can't load the ONNX bundle " + folder + ": " + e.getMessage(), e);
        }
        LOADED.put(key, evaluator);
        System.err.println("ONNX evaluator: loaded " + folder + ", generation " + evaluator.bundle().generation()
                + ", " + evaluator.bundle().cardEntries() + " card entries");
        if (summaries == null) {
            summaries = new Thread(OnnxEvaluators::printSummaries);
            Runtime.getRuntime().addShutdownHook(summaries);
        }
        return evaluator;
    }

    private static void printSummaries() {
        for (final String line : summaries()) {
            System.err.println(line);
        }
    }

    /** Each loaded bundle's summary line, in load order, ending with its folder. */
    static synchronized List<String> summaries() {
        final List<String> lines = new ArrayList<>();
        LOADED.forEach((folder, evaluator) -> lines.add(evaluator.summary() + ", bundle " + folder));
        return lines;
    }

    /** How many bundles this JVM has loaded. */
    static synchronized int loaded() {
        return LOADED.size();
    }

    /** Closes every loaded bundle and forgets it. For tests. */
    static synchronized void closeAll() throws Exception {
        for (final OnnxEvaluator evaluator : LOADED.values()) {
            evaluator.close();
        }
        LOADED.clear();
    }
}
