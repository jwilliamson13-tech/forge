package forge.view;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import forge.ai.simulation.WinProbabilityEvaluator;
import forge.game.Game;
import forge.game.RevealLog;
import forge.game.player.Player;

/**
 * The learned evaluator: a Spec 5 export bundle run under ONNX Runtime on the CPU.
 * <p>
 * A state goes in as the game record's snapshot line for the AI's side, built by {@link SnapshotLine}, the code that
 * writes the training data, and {@link SnapshotEncoder} turns it into the contract's inputs. So the model sees only
 * what that side may see: the opponent's hand and library as counts, a face-down card it may not look at as hidden,
 * and the opponent's cards it was shown as {@code opp_revealed}.
 * <p>
 * The search's copies have no reveal log, so on a copy {@code opp_revealed} is what the real game's log held the last
 * time this evaluator scored the real game for that player. The search scores the real game first at every decision,
 * so that is the log at the root of the search. Nothing the search imagines being revealed is added.
 * <p>
 * It only reads: no {@code MyRandom}, and the log is read with {@link RevealLog#writtenDown}, which adds nothing.
 * A session runs one state at a time, on one thread unless {@code threads} says otherwise.
 */
public final class OnnxEvaluator implements WinProbabilityEvaluator, AutoCloseable {
    private final OnnxBundle bundle;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final SnapshotEncoder encoder;
    private final Map<Integer, List<RevealLog.Entry>> revealedAtRoot = new ConcurrentHashMap<>();
    private final Set<String> missingSeen = ConcurrentHashMap.newKeySet();
    private final AtomicLong missingCards = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();
    private final AtomicLong states = new AtomicLong();
    private final AtomicLong cardsScored = new AtomicLong();

    private OnnxEvaluator(final OnnxBundle bundle, final int threads) throws OrtException {
        this.bundle = bundle;
        checkCppRuntime();
        this.env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(threads);
            options.setInterOpNumThreads(1);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            this.session = env.createSession(bundle.modelFile().toString(), options);
        }
        try {
            checkSession();
        } catch (final RuntimeException | OrtException e) {
            session.close();
            throw e;
        }
        this.encoder = new SnapshotEncoder(bundle, this::missing);
    }

    /** Loads the bundle in {@code folder} and opens its model on one CPU thread. */
    public static OnnxEvaluator load(final Path folder) throws IOException, OrtException {
        return load(folder, 1);
    }

    public static OnnxEvaluator load(final Path folder, final int threads) throws IOException, OrtException {
        return new OnnxEvaluator(OnnxBundle.load(folder), threads);
    }

    /** The oldest msvcp140.dll ONNX Runtime 1.31 runs on. Older ones crash the JVM inside its std::mutex. */
    static final int[] MIN_CPP_RUNTIME = {14, 40};

    /**
     * On Windows the JVM loads {@code msvcp140.dll} from its own {@code bin} folder at startup, and ONNX Runtime binds
     * to that copy. Oracle JDK 21.0.2 ships 14.31, and ONNX Runtime then dies with an access violation, taking the JVM
     * with it. So this refuses first, with a message, instead of letting the run crash.
     */
    static void checkCppRuntime() {
        final Path dll = Path.of(System.getProperty("java.home"), "bin", "msvcp140.dll");
        if (!System.getProperty("os.name", "").startsWith("Windows") || !Files.isRegularFile(dll)) {
            return;
        }
        final int[] version = fileVersion(dll);
        if (version != null && (version[0] < MIN_CPP_RUNTIME[0]
                || version[0] == MIN_CPP_RUNTIME[0] && version[1] < MIN_CPP_RUNTIME[1])) {
            throw new IllegalStateException(dll + " is version " + version[0] + "." + version[1] + ", and ONNX Runtime needs "
                    + MIN_CPP_RUNTIME[0] + "." + MIN_CPP_RUNTIME[1] + " or newer, or it crashes the JVM. Run Forge on a Java "
                    + "whose bin folder has a newer msvcp140.dll, vcruntime140.dll and vcruntime140_1.dll, such as a jlink "
                    + "image of this JDK with those three copied in from C:\\Windows\\System32.");
        }
    }

    /** Major and minor of a PE file's version, from its VS_FIXEDFILEINFO, or null if it has none. */
    static int[] fileVersion(final Path file) {
        try {
            final byte[] bytes = Files.readAllBytes(file);
            final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i + 16 <= bytes.length; i++) {
                if (buffer.getInt(i) == 0xFEEF04BD) {
                    final int high = buffer.getInt(i + 8);
                    return new int[] {high >>> 16, high & 0xFFFF};
                }
            }
            return null;
        } catch (final IOException e) {
            return null;
        }
    }

    /** The model file must take exactly the contract's inputs, so a bundle with a mismatched model fails here. */
    private void checkSession() throws OrtException {
        final Map<String, NodeInfo> inputs = session.getInputInfo();
        final Map<String, String> found = new HashMap<>();
        final Map<String, String> wanted = new HashMap<>();
        for (final List<String> input : OnnxBundle.INPUTS) {
            wanted.put(input.get(0), input.get(1) + " rank " + input.get(2).split(" ").length);
        }
        for (final Map.Entry<String, NodeInfo> input : inputs.entrySet()) {
            if (input.getValue().getInfo() instanceof TensorInfo info) {
                final String type = info.type == OnnxJavaType.FLOAT ? "float32" : info.type == OnnxJavaType.INT64 ? "int64" : info.type.toString();
                found.put(input.getKey(), type + " rank " + info.getShape().length);
            } else {
                found.put(input.getKey(), "not a tensor");
            }
        }
        if (!found.equals(wanted)) {
            throw new OnnxBundle.BundleException(bundle.modelFile() + " takes " + found + ", but the contract says " + wanted);
        }
        if (!session.getOutputNames().contains(OnnxBundle.OUTPUT)) {
            throw new OnnxBundle.BundleException(bundle.modelFile() + " has no output " + OnnxBundle.OUTPUT);
        }
    }

    public OnnxBundle bundle() {
        return bundle;
    }

    @Override
    public double winProbability(final Game game, final Player aiPlayer) {
        final long started = System.nanoTime();
        final int seat = game.getRegisteredPlayers().indexOf(aiPlayer) + 1;
        final JsonObject line = SnapshotLine.of(game, aiPlayer, new RecordIds(0L, 0, seat), revealed(game, aiPlayer));
        final double p = probability(line);
        nanos.addAndGet(System.nanoTime() - started);
        calls.incrementAndGet();
        return p;
    }

    /** The real game's log is read and kept. A copy gets what was kept for that player. */
    private List<RevealLog.Entry> revealed(final Game game, final Player aiPlayer) {
        if (game.isSimulationCopy()) {
            return revealedAtRoot.getOrDefault(aiPlayer.getId(), List.of());
        }
        final List<RevealLog.Entry> now = game.REVEAL_LOG == null ? List.of() : game.REVEAL_LOG.writtenDown(aiPlayer);
        revealedAtRoot.put(aiPlayer.getId(), now);
        return now;
    }

    /** The model's probability for one snapshot line, as the game record holds it. */
    public double probability(final JsonObject line) {
        final SnapshotEncoder.Inputs in = encoder.encode(line);
        states.incrementAndGet();
        cardsScored.addAndGet(in.cards);
        Map<String, OnnxTensor> tensors = Map.of();
        try {
            tensors = tensors(in);
            try (OrtSession.Result result = session.run(tensors, Set.of(OnnxBundle.OUTPUT))) {
                final float[] out = (float[]) result.get(0).getValue();
                return out[0];
            }
        } catch (final OrtException e) {
            throw new IllegalStateException("ONNX Runtime failed on a state with " + in.cards + " cards", e);
        } finally {
            tensors.values().forEach(OnnxTensor::close);
        }
    }

    /** The contract's inputs as tensors, in its order. The caller closes them. */
    Map<String, OnnxTensor> tensors(final SnapshotEncoder.Inputs in) throws OrtException {
        final long n = in.cards, a = bundle.abilities(), t = bundle.tokens(), k = bundle.keys();
        final Map<String, OnnxTensor> tensors = new LinkedHashMap<>();
        try {
            tensors.put("scoreboard", OnnxTensor.createTensor(env, FloatBuffer.wrap(in.scoreboard), new long[] {in.scoreboard.length}));
            tensors.put("token_ids", OnnxTensor.createTensor(env, LongBuffer.wrap(in.tokenIds), new long[] {n, a, t}));
            tensors.put("token_mask", OnnxTensor.createTensor(env, FloatBuffer.wrap(in.tokenMask), new long[] {n, a, t}));
            tensors.put("numbers", OnnxTensor.createTensor(env, FloatBuffer.wrap(in.numbers), new long[] {n, a, k}));
            tensors.put("number_mask", OnnxTensor.createTensor(env, FloatBuffer.wrap(in.numberMask), new long[] {n, a, k}));
            tensors.put("live", OnnxTensor.createTensor(env, FloatBuffer.wrap(in.live), new long[] {n, OnnxBundle.LIVE_FIELDS.size()}));
            tensors.put("zone", OnnxTensor.createTensor(env, LongBuffer.wrap(in.zone), new long[] {n}));
            return tensors;
        } catch (final OrtException | RuntimeException e) {
            tensors.values().forEach(OnnxTensor::close);
            throw e;
        }
    }

    /** Calls through the seam so far. */
    public long calls() {
        return calls.get();
    }

    /** The contract's fallback for a card the table lacks: all masks 0. Logged once per name and face, counted every time. */
    private void missing(final String name, final String face) {
        missingCards.incrementAndGet();
        if (missingSeen.add(name + " (" + face + ")")) {
            System.err.println("ONNX evaluator: no card features for " + name + " (" + face + "), scored as a card with no text");
        }
    }

    /** Named cards scored without features so far, counting repeats. */
    public long missingCards() {
        return missingCards.get();
    }

    /** One line on what this evaluator has done: calls through the seam, time per call, cards per state, missing cards. */
    public String summary() {
        final long count = calls.get();
        final double meanMs = count == 0 ? 0 : nanos.get() / 1e6 / count;
        final long scored = states.get();
        return String.format("ONNX evaluator: %d calls, %.3f ms per call, %d states, %.1f cards per state, "
                        + "%d cards scored without features, %d names missing",
                count, meanMs, scored, scored == 0 ? 0.0 : (double) cardsScored.get() / scored, missingCards.get(), missingSeen.size());
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
