package forge.view;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.TwoLookScorer;

/**
 * Picks the ONNX evaluator for {@code forge sim} seats from system properties, until a command-line flag for evaluators
 * (issue #23) takes over:
 * <ul>
 *     <li>{@code -Dforge.onnx.bundle=DIR} turns it on, with the export bundle in {@code DIR}.</li>
 *     <li>{@code -Dforge.onnx.seats=1,2} says which seats use it, in deck order. Every seat if left out.</li>
 *     <li>{@code -Dforge.onnx.threads=N} gives ONNX Runtime N threads, 1 if left out.</li>
 * </ul>
 * A seat that uses it searches with {@link TwoLookScorer} over the model, so it only matters for a seat in {@code full}
 * or {@code hybrid} mode. One evaluator serves every seat, and the JVM prints its {@link OnnxEvaluator#summary} to
 * stderr at exit. A bundle that won't load stops the run before any game starts.
 */
public final class OnnxSeats {
    public static final String BUNDLE = "forge.onnx.bundle";
    public static final String SEATS = "forge.onnx.seats";
    public static final String THREADS = "forge.onnx.threads";

    private static OnnxEvaluator evaluator;

    private OnnxSeats() {
    }

    /** Sets the ONNX scorer on {@code player}, seat {@code seat} counted from 1, if the properties ask for it. */
    public static void configure(final int seat, final LobbyPlayerAi player) {
        final String folder = System.getProperty(BUNDLE);
        if (folder == null || folder.isBlank()) {
            return;
        }
        final List<Integer> seats = seats();
        if (!seats.isEmpty() && !seats.contains(seat)) {
            return;
        }
        player.setSearchScorer(new TwoLookScorer(evaluator(Path.of(folder))));
        System.err.println("ONNX evaluator: seat " + seat + " searches with " + folder);
    }

    private static List<Integer> seats() {
        final List<Integer> seats = new ArrayList<>();
        final String value = System.getProperty(SEATS, "");
        for (final String part : value.split(",")) {
            if (!part.isBlank()) {
                seats.add(Integer.parseInt(part.trim()));
            }
        }
        return seats;
    }

    private static synchronized OnnxEvaluator evaluator(final Path folder) {
        if (evaluator == null) {
            try {
                final int threads = Integer.parseInt(System.getProperty(THREADS, "1"));
                evaluator = OnnxEvaluator.load(folder, threads);
            } catch (final Exception e) {
                throw new IllegalArgumentException("can't load the ONNX bundle " + folder + ": " + e.getMessage(), e);
            }
            final OnnxEvaluator loaded = evaluator;
            System.err.println("ONNX evaluator: loaded " + folder + ", generation " + loaded.bundle().generation()
                    + ", " + loaded.bundle().cardEntries() + " card entries");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.println(loaded.summary())));
        }
        return evaluator;
    }
}
