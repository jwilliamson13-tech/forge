package forge.view;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;
import static org.testng.AssertJUnit.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.TensorInfo;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.ai.simulation.SpellAbilityPicker;
import forge.ai.simulation.TwoLookScorer;
import forge.game.Game;
import forge.game.RevealLog;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * The ONNX evaluator on the fixture the Python repo's {@code tests/forge_fixture.py} builds: a smoke-model bundle, one
 * game record, and what setmodel's PyTorch model gives each of its snapshots.
 */
public class OnnxEvaluatorTest extends AITest {
    /** PyTorch against ONNX Runtime with inputs built in Java. The export check allows 1e-4 between two runtimes. */
    private static final double TOLERANCE = 1e-5;

    private Path fixture;
    private OnnxEvaluator evaluator;

    @BeforeClass
    public void load() throws Exception {
        fixture = fixtureFolder();
        evaluator = OnnxEvaluator.load(fixture.resolve("bundle"));
    }

    @AfterClass
    public void close() throws Exception {
        evaluator.close();
    }

    static Path fixtureFolder() throws URISyntaxException {
        return Path.of(OnnxEvaluatorTest.class.getResource("/onnx-fixture/expected.tsv").toURI()).getParent();
    }

    private List<JsonObject> snapshots() throws IOException {
        final List<JsonObject> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(fixture.resolve("game.jsonl.gz"))), StandardCharsets.UTF_8))) {
            in.readLine();
            String line;
            while ((line = in.readLine()) != null) {
                lines.add(JsonParser.parseString(line).getAsJsonObject());
            }
        }
        return lines;
    }

    private Map<Integer, Double> expected() throws IOException {
        final Map<Integer, Double> expected = new HashMap<>();
        final List<String> lines = Files.readAllLines(fixture.resolve("expected.tsv"), StandardCharsets.UTF_8);
        for (final String line : lines.subList(1, lines.size())) {
            final String[] fields = line.split("\t");
            expected.put(Integer.parseInt(fields[1]), Double.parseDouble(fields[2]));
        }
        return expected;
    }

    private static JsonObject contract(final Path bundle) throws IOException {
        return JsonParser.parseString(Files.readString(bundle.resolve(OnnxBundle.CONTRACT))).getAsJsonObject();
    }

    /** A copy of the fixture bundle with its contract changed by {@code edit}. */
    private Path bundleWith(final java.util.function.Consumer<JsonObject> edit) throws IOException {
        final Path copy = Files.createTempDirectory("onnx-bundle");
        try (Stream<Path> files = Files.list(fixture.resolve("bundle"))) {
            for (final Path file : files.toList()) {
                Files.copy(file, copy.resolve(file.getFileName()));
            }
        }
        final JsonObject contract = contract(copy);
        edit.accept(contract);
        Files.writeString(copy.resolve(OnnxBundle.CONTRACT), contract.toString());
        return copy;
    }

    private void assertRefused(final Path bundle, final String mentioning) throws Exception {
        try {
            OnnxEvaluator.load(bundle).close();
            fail("loaded a bundle it should refuse");
        } catch (final OnnxBundle.BundleException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(mentioning));
        }
    }

    @Test
    public void theFixtureBundleLoadsWithItsContractsSizes() throws Exception {
        final JsonObject sizes = contract(fixture.resolve("bundle")).getAsJsonObject("sizes");
        assertEquals(sizes.get("A").getAsInt(), evaluator.bundle().abilities());
        assertEquals(sizes.get("T").getAsInt(), evaluator.bundle().tokens());
        assertEquals(sizes.get("K").getAsInt(), evaluator.bundle().keys());
        assertTrue(evaluator.bundle().cardEntries() > 0);
    }

    @Test
    public void anotherContractVersionIsRefused() throws Exception {
        assertRefused(bundleWith(c -> c.addProperty("version", 2)), "version");
    }

    @Test
    public void aContractWithAnotherFieldOrderIsRefused() throws Exception {
        assertRefused(bundleWith(c -> {
            final JsonArray fields = c.getAsJsonArray("live_fields");
            final var first = fields.remove(0);
            fields.add(first);
        }), "live_fields");
        assertRefused(bundleWith(c -> c.getAsJsonObject("sizes").addProperty("L", 19)), "size L");
        assertRefused(bundleWith(c -> c.getAsJsonArray("inputs").get(1).getAsJsonObject().addProperty("dtype", "int32")), "inputs");
    }

    @Test
    public void aFolderWithoutABundleIsRefused() throws Exception {
        assertRefused(Files.createTempDirectory("not-a-bundle"), "not an export bundle");
    }

    @Test
    public void aBuiltInputHasTheContractsNamesDtypesAndShapes() throws Exception {
        final JsonObject contract = contract(fixture.resolve("bundle"));
        final JsonObject sizes = contract.getAsJsonObject("sizes");
        final JsonObject line = snapshots().get(0);
        final SnapshotEncoder.Inputs in = new SnapshotEncoder(evaluator.bundle(), (name, face) -> { }).encode(line);
        final Map<String, OnnxTensor> tensors = evaluator.tensors(in);
        try {
            final List<String> names = new ArrayList<>();
            contract.getAsJsonArray("inputs").forEach(e -> names.add(e.getAsJsonObject().get("name").getAsString()));
            assertEquals(names, new ArrayList<>(tensors.keySet()));
            for (final var element : contract.getAsJsonArray("inputs")) {
                final JsonObject input = element.getAsJsonObject();
                final TensorInfo info = tensors.get(input.get("name").getAsString()).getInfo();
                final OnnxJavaType type = "int64".equals(input.get("dtype").getAsString()) ? OnnxJavaType.INT64 : OnnxJavaType.FLOAT;
                assertEquals(input.get("name").getAsString(), type, info.type);
                final JsonArray letters = input.getAsJsonArray("shape");
                assertEquals(letters.size(), info.getShape().length);
                for (int d = 0; d < letters.size(); d++) {
                    final String letter = letters.get(d).getAsString();
                    final long size = "N".equals(letter) ? in.cards : sizes.get(letter).getAsLong();
                    assertEquals(input.get("name") + " " + letter, size, info.getShape()[d]);
                }
            }
            assertEquals(18, in.cards);  // 6 on the battlefield, 3 in hand, 4 in graveyards and exiles, 2 logged, 3 on the stack
        } finally {
            tensors.values().forEach(OnnxTensor::close);
        }
        final double p = evaluator.probability(line);
        assertTrue(p > 0 && p < 1);
    }

    @Test
    public void everyFixtureSnapshotScoresWhatSetmodelGives() throws Exception {
        final List<JsonObject> lines = snapshots();
        final Map<Integer, Double> expected = expected();
        assertEquals(lines.size(), expected.size());
        double worst = 0;
        for (int i = 0; i < lines.size(); i++) {
            worst = Math.max(worst, Math.abs(evaluator.probability(lines.get(i)) - expected.get(i)));
        }
        assertTrue("largest difference " + worst, worst < TOLERANCE);
    }

    @Test
    public void aCardTheTableLacksScoresWithNoTextAndIsCounted() throws Exception {
        final JsonObject line = snapshots().get(0);
        final JsonObject unknown = line.getAsJsonObject("zones").getAsJsonArray("me_hand").get(0).getAsJsonObject().deepCopy();
        unknown.addProperty("name", "A Card From Next Year");
        line.getAsJsonObject("zones").getAsJsonArray("me_hand").add(unknown);
        final List<String> heard = new ArrayList<>();
        final SnapshotEncoder.Inputs in = new SnapshotEncoder(evaluator.bundle(), (name, face) -> heard.add(name + "/" + face)).encode(line);

        assertEquals(List.of("A Card From Next Year/Original"), heard);
        final int n = handCardIndex(line, 3);
        assertAllZero(in.tokenMask, n * evaluator.bundle().abilities() * evaluator.bundle().tokens(), evaluator.bundle().abilities() * evaluator.bundle().tokens());
        assertAllZero(in.numberMask, n * evaluator.bundle().abilities() * evaluator.bundle().keys(), evaluator.bundle().abilities() * evaluator.bundle().keys());
        assertEquals(0f, in.live[n * OnnxBundle.LIVE_FIELDS.size() + OnnxBundle.LIVE_FIELDS.indexOf("hidden")]);
        assertEquals(1f, in.live[n * OnnxBundle.LIVE_FIELDS.size() + OnnxBundle.LIVE_FIELDS.indexOf("mine")]);

        final long before = evaluator.missingCards();
        final double p = evaluator.probability(line);
        assertTrue(p > 0 && p < 1);
        assertEquals(before + 1, evaluator.missingCards());
    }

    /** The position of {@code me_hand} card {@code k} in the card axis: battlefield cards come first. */
    private static int handCardIndex(final JsonObject line, final int k) {
        return line.getAsJsonObject("zones").getAsJsonArray("battlefield").size() + k;
    }

    private static void assertAllZero(final float[] values, final int from, final int count) {
        for (int i = from; i < from + count; i++) {
            assertEquals("index " + i, 0f, values[i]);
        }
    }

    @Test
    public void aHiddenCardHasNoTextAndSaysSo() throws Exception {
        final JsonObject line = snapshots().get(0);
        final SnapshotEncoder.Inputs in = new SnapshotEncoder(evaluator.bundle(), (name, face) -> fail("a hidden card is not missing")).encode(line);
        final int morph = 3;  // the fixture's face-down card, fourth on the battlefield
        final int a = evaluator.bundle().abilities();
        assertAllZero(in.tokenMask, morph * a * evaluator.bundle().tokens(), a * evaluator.bundle().tokens());
        assertAllZero(in.numberMask, morph * a * evaluator.bundle().keys(), a * evaluator.bundle().keys());
        final int live = morph * OnnxBundle.LIVE_FIELDS.size();
        assertEquals(1f, in.live[live + OnnxBundle.LIVE_FIELDS.indexOf("hidden")]);
        assertEquals(1f, in.live[live + OnnxBundle.LIVE_FIELDS.indexOf("face_down")]);
        assertEquals(3f, in.live[live + OnnxBundle.LIVE_FIELDS.indexOf("counters_loyalty")]);
    }

    @Test
    public void theRuntimeCheckReadsADllsVersion() {
        final Path dll = Path.of(System.getProperty("java.home"), "bin", "msvcp140.dll");
        if (Files.isRegularFile(dll)) {
            final int[] version = OnnxEvaluator.fileVersion(dll);
            assertEquals(14, version[0]);
            assertTrue("this JVM loaded ONNX Runtime, so its msvcp140 is new enough", version[1] >= OnnxEvaluator.MIN_CPP_RUNTIME[1]);
        }
    }

    @Test
    public void anEmptyBoardScores() throws Exception {
        final JsonObject line = snapshots().get(2);
        assertEquals(0, new SnapshotEncoder(evaluator.bundle(), (name, face) -> { }).encode(line).cards);
        final double p = evaluator.probability(line);
        assertTrue(p > 0 && p < 1);
    }

    private Game liveGame() {
        final Game game = initAndCreateGame();
        final Player ai = game.getRegisteredPlayers().get(0);
        final Player opp = game.getRegisteredPlayers().get(1);
        game.REVEAL_LOG = new RevealLog(game);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        addCard("Grizzly Bears", ai);
        addCard("Silver Knight", opp);
        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        addCardToZone("Snapcaster Mage", ai, ZoneType.Graveyard);
        fillLibrary(ai, 5);
        fillLibrary(opp, 7);
        final Card shown = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        addCardToZone("Platinum Angel", opp, ZoneType.Hand);
        game.REVEAL_LOG.revealed(ai, shown);
        return game;
    }

    @Test
    public void aLiveGameScoresTheLineTheRecorderWouldWrite() throws Exception {
        final Game game = liveGame();
        final Player ai = game.getRegisteredPlayers().get(0);
        final double live = evaluator.winProbability(game, ai);
        final double recorded = evaluator.probability(SnapshotLine.of(game, ai, new RecordIds(1L, 1, 1)));
        assertEquals(recorded, live, 1e-7);
        assertTrue(live > 0 && live < 1);
    }

    @Test
    public void aSearchCopyIsScoredWithTheLogFromTheRealGame() throws Exception {
        final Game game = liveGame();
        final Player ai = game.getRegisteredPlayers().get(0);
        final double real = evaluator.winProbability(game, ai);
        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy(null, ai);
        assertTrue(copy.isSimulationCopy() && copy.REVEAL_LOG == null);
        assertEquals(real, evaluator.winProbability(copy, copier.find(ai)), 1e-7);

        final JsonObject withoutLog = SnapshotLine.of(copy, copier.find(ai), new RecordIds(0L, 0, 1), List.of());
        final JsonObject withLog = SnapshotLine.of(game, ai, new RecordIds(0L, 0, 1));
        assertEquals(0, withoutLog.getAsJsonObject("zones").getAsJsonArray("opp_revealed").size());
        assertEquals(1, withLog.getAsJsonObject("zones").getAsJsonArray("opp_revealed").size());
        assertEquals(2, withLog.getAsJsonObject("scoreboard").get("opp_hand").getAsInt());
    }

    @Test
    public void theSearchPicksAPlayWithTheModelAsItsScorer() throws Exception {
        final Game game = liveGame();
        final Player ai = game.getRegisteredPlayers().get(0);
        addCard("Mountain", ai);
        final TwoLookScorer scorer = new TwoLookScorer(evaluator);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setSearchScorer(scorer);
        final Score score = scorer.score(game, ai);
        assertTrue(score.value > TwoLookScorer.LIVE_SCORE_MIN && score.value < TwoLookScorer.LIVE_SCORE_MAX);
        final long before = evaluator.calls();
        // either choice is fine for a smoke model: what matters is that the search ran on the model's scores
        new SpellAbilityPicker(ai).chooseSpellAbilityToPlay(null);
        assertTrue(evaluator.calls() > before + 1);
    }
}
