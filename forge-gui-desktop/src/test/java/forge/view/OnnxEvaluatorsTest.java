package forge.view;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNotSame;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertSame;
import static org.testng.AssertJUnit.assertTrue;
import static org.testng.AssertJUnit.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.Evaluators;
import forge.ai.simulation.SearchScorer;
import forge.ai.simulation.SpellAbilityPicker;
import forge.ai.simulation.TwoLookScorer;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** The {@code onnx:DIR} evaluator name: one model per bundle folder, a bad folder refused, two folders at one table. */
public class OnnxEvaluatorsTest extends AITest {
    private Path bundle;

    @BeforeMethod
    public void register() throws Exception {
        OnnxEvaluators.closeAll();
        OnnxEvaluators.register();
        bundle = OnnxEvaluatorTest.fixtureFolder().resolve("bundle");
    }

    @AfterMethod
    public void unregister() throws Exception {
        OnnxEvaluators.closeAll();
        Evaluators.unregister(OnnxEvaluators.PREFIX);
    }

    private static String name(final Path folder) {
        return "onnx:" + folder;
    }

    private static OnnxEvaluator evaluatorOf(final SearchScorer scorer) {
        return (OnnxEvaluator) ((TwoLookScorer) scorer).getEvaluator();
    }

    /** A copy of the fixture bundle that says it is generation {@code generation}. */
    private Path generation(final int generation) throws IOException {
        final Path copy = Files.createTempDirectory("onnx-gen" + generation);
        try (Stream<Path> files = Files.list(bundle)) {
            for (final Path file : files.toList()) {
                Files.copy(file, copy.resolve(file.getFileName()));
            }
        }
        final Path contractFile = copy.resolve(OnnxBundle.CONTRACT);
        final JsonObject contract = JsonParser.parseString(Files.readString(contractFile)).getAsJsonObject();
        contract.getAsJsonObject("bundle").addProperty("generation", generation);
        Files.writeString(contractFile, contract.toString());
        return copy;
    }

    @Test
    public void theNameIsKnownOnlyOnceRegistered() {
        Evaluators.unregister(OnnxEvaluators.PREFIX);
        assertNull(Evaluators.scorer(name(bundle), null));
        assertTrue(Evaluators.problem(name(bundle)).startsWith("Unknown evaluator - onnx:"));
        OnnxEvaluators.register();
        assertTrue(Evaluators.names().contains("onnx:DIR"));
        assertEquals(OnnxEvaluators.PREFIX, Evaluators.family(name(bundle)));
        assertEquals("random", Evaluators.family("random"));
        assertEquals(bundle.toString(), OnnxEvaluators.folderOf(name(bundle)));
        assertNull(OnnxEvaluators.folderOf("random"));
        assertEquals(0, OnnxEvaluators.loaded());
    }

    @Test
    public void aFolderLoadsOnceHoweverItIsSpelled() throws Exception {
        final SearchScorer first = Evaluators.scorer(name(bundle), 1L);
        final SearchScorer second = Evaluators.scorer(name(bundle), 2L);
        final SearchScorer respelled = Evaluators.scorer(name(bundle.resolve("..").resolve("bundle")), null);
        // each seat gets its own scorer, over the one model
        assertNotSame(first, second);
        assertSame(evaluatorOf(first), evaluatorOf(second));
        assertSame(evaluatorOf(first), evaluatorOf(respelled));
        // checking the option loads nothing more
        assertNull(SimulateMatch.evalOptionProblem(Map.of("sim", List.of("full", "full"),
                "eval", List.of(name(bundle), name(bundle)))));
        assertEquals(1, OnnxEvaluators.loaded());
        final List<String> summaries = OnnxEvaluators.summaries();
        assertEquals(1, summaries.size());
        assertTrue(summaries.get(0), summaries.get(0).startsWith("ONNX evaluator: 0 calls, "));
    }

    @Test
    public void aBadFolderIsRefusedWithTheReason() throws Exception {
        final Path missing = bundle.resolveSibling("no-such-bundle");
        final Path empty = Files.createTempDirectory("not-a-bundle");
        for (final Path folder : List.of(missing, empty)) {
            try {
                Evaluators.scorer(name(folder), null);
                fail("took a folder with no bundle: " + folder);
            } catch (final IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().startsWith("can't load the ONNX bundle " + folder));
            }
        }
        final String problem = SimulateMatch.evalOptionProblem(Map.of("sim", List.of("full", "full"),
                "eval", List.of(name(bundle), name(empty))));
        assertNotNull(problem);
        // the runner counts a line that starts with "-eval " as Forge giving up before playing
        assertTrue(problem, problem.startsWith("-eval onnx:" + empty + " can't be used: can't load the ONNX bundle"));
        assertTrue(problem, problem.contains("not an export bundle"));
        final String noFolder = SimulateMatch.evalOptionProblem(Map.of("sim", List.of("full"), "eval", List.of("onnx:")));
        assertTrue(noFolder, noFolder.contains("names no DIR"));
        // the good folder loaded, and the bad ones left nothing behind
        assertEquals(1, OnnxEvaluators.loaded());
    }

    @Test
    public void aHeuristicSeatCantTakeTheModel() {
        final String problem = SimulateMatch.evalOptionProblem(Map.of("sim", List.of("full", "heuristic"),
                "eval", List.of("forge", name(bundle))));
        assertTrue(problem, problem.contains("seat 2 is heuristic"));
    }

    @Test
    public void theRetiredPropertiesStopTheRun() {
        assertNull(OnnxEvaluators.retiredPropertyProblem());
        for (final String property : List.of("forge.onnx.bundle", "forge.onnx.seats")) {
            System.setProperty(property, "1");
            try {
                final String problem = OnnxEvaluators.retiredPropertyProblem();
                assertTrue(problem, problem.startsWith("-eval onnx:DIR replaced -D" + property));
            } finally {
                System.clearProperty(property);
            }
        }
    }

    @Test
    public void twoFoldersSitAtOneTable() throws Exception {
        final Path next = generation(1);
        assertNull(SimulateMatch.evalOptionProblem(Map.of("sim", List.of("full", "full"),
                "eval", List.of(name(bundle), name(next)))));
        // and a model across from the other evaluators
        assertNull(SimulateMatch.evalOptionProblem(Map.of("sim", List.of("full", "hybrid"),
                "eval", List.of(name(bundle), "random"))));
        assertNull(SimulateMatch.evalOptionProblem(Map.of("sim", List.of("full", "full"),
                "eval", List.of("passthrough", name(next)))));
        assertEquals(2, OnnxEvaluators.loaded());

        final Game game = initAndCreateGame();
        final Player ai = game.getRegisteredPlayers().get(0);
        final Player opp = game.getRegisteredPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai, 3);
        addCard("Grizzly Bears", ai);
        addCard("Mountain", ai);
        addCardToZone("Lightning Bolt", ai, ZoneType.Hand);
        addCard("Silver Knight", opp);
        fillLibrary(ai, 5);
        fillLibrary(opp, 7);

        final SearchScorer seat1 = Evaluators.scorer(name(bundle), null);
        final SearchScorer seat2 = Evaluators.scorer(name(next), null);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setSearchScorer(seat1);
        ((LobbyPlayerAi) opp.getLobbyPlayer()).setSearchScorer(seat2);
        final OnnxEvaluator gen0 = evaluatorOf(seat1);
        final OnnxEvaluator gen1 = evaluatorOf(seat2);
        assertNotSame(gen0, gen1);
        assertEquals(0, gen0.bundle().generation());
        assertEquals(1, gen1.bundle().generation());

        // each seat's search scores with its own model, and leaves the other's alone
        new SpellAbilityPicker(ai).chooseSpellAbilityToPlay(null);
        final long seat1Calls = gen0.calls();
        assertTrue(seat1Calls > 1);
        assertEquals(0, gen1.calls());
        SearchScorer.of(opp).score(game, opp);
        assertEquals(seat1Calls, gen0.calls());
        assertTrue(gen1.calls() > 0);

        final List<String> summaries = OnnxEvaluators.summaries();
        assertEquals(2, summaries.size());
        assertTrue(summaries.get(0), summaries.get(0).endsWith(", bundle " + bundle.toRealPath()));
        assertTrue(summaries.get(1), summaries.get(1).endsWith(", bundle " + next.toRealPath()));
    }
}
