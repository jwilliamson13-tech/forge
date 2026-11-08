package forge.view;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Scores every snapshot of some game records with the ONNX evaluator, for the parity check against setmodel:
 * <pre>
 * java -cp forge-gui-desktop-...-jar-with-dependencies.jar forge.view.OnnxScoreRecords BUNDLE SCORES.tsv GAME_OR_FOLDER...
 * python -m setmodel parity MODEL PARQUET CARDS MANIFEST SCORES.tsv
 * </pre>
 * The scores file is the one {@code setmodel.parity} reads: a header, then {@code game_id}, {@code snapshot_index} and
 * {@code probability}, tab-separated. A folder means every {@code *.jsonl.gz} in it. Nothing here starts Forge: it reads
 * the files and runs the model, and prints the time per snapshot to stderr.
 */
public final class OnnxScoreRecords {
    private OnnxScoreRecords() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: OnnxScoreRecords BUNDLE SCORES.tsv GAME_OR_FOLDER...");
            System.exit(2);
        }
        final List<Path> games = new ArrayList<>();
        for (final String arg : Arrays.asList(args).subList(2, args.length)) {
            games.addAll(gameFiles(Path.of(arg)));
        }
        final List<Long> nanos = new ArrayList<>();
        try (OnnxEvaluator evaluator = OnnxEvaluator.load(Path.of(args[0]));
             PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8))) {
            out.print("game_id\tsnapshot_index\tprobability\n");
            for (final Path game : games) {
                score(evaluator, game, out, nanos);
            }
            System.err.println(games.size() + " games, " + nanos.size() + " snapshots, " + timing(nanos));
            System.err.println(evaluator.summary());
        }
    }

    static List<Path> gameFiles(final Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return List.of(path);
        }
        try (Stream<Path> files = Files.list(path)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jsonl.gz")).sorted().toList();
        }
    }

    /** One scores line per snapshot line of {@code game}. The game id is the one the Python loader derives. */
    static void score(final OnnxEvaluator evaluator, final Path game, final PrintWriter out, final List<Long> nanos) throws IOException {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(game)), StandardCharsets.UTF_8))) {
            final JsonObject metadata = JsonParser.parseString(lines.readLine()).getAsJsonObject();
            final String gameId = metadata.get("batch_id").getAsString() + "-" + metadata.get("seed").getAsLong() + "-"
                    + metadata.get("game_number").getAsInt();
            String line;
            int index = 0;
            while ((line = lines.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                final JsonObject snapshot = JsonParser.parseString(line).getAsJsonObject();
                final long started = System.nanoTime();
                final double p = evaluator.probability(snapshot);
                nanos.add(System.nanoTime() - started);
                out.print(gameId + "\t" + index + "\t" + p + "\n");
                index++;
            }
        }
    }

    static String timing(final List<Long> nanos) {
        if (nanos.isEmpty()) {
            return "no timings";
        }
        final long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        final double mean = Arrays.stream(sorted).average().orElse(0) / 1e6;
        return String.format("per snapshot: mean %.3f ms, median %.3f ms, p99 %.3f ms, max %.3f ms", mean,
                sorted[sorted.length / 2] / 1e6, sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * 0.99))] / 1e6,
                sorted[sorted.length - 1] / 1e6);
    }
}
