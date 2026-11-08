package forge.view;

import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/** The -eval option (#23): an evaluator per seat, checked before anything is played. */
public class SimulateMatchEvalOptionTest {
    private static void assertRefused(final Map<String, List<String>> params, final String expected) {
        final String problem = SimulateMatch.evalOptionProblem(params);
        assertNotNull(problem);
        assertTrue(problem, problem.contains(expected));
    }

    @Test
    public void aRunWithoutTheFlagIsLeftAlone() {
        assertNull(SimulateMatch.evalOptionProblem(Map.of("d", List.of("a.dck", "b.dck"))));
    }

    @Test
    public void searchingSeatsTakeAnyEvaluator() {
        assertNull(SimulateMatch.evalOptionProblem(Map.of("sim", List.of("full", "hybrid"), "eval", List.of("random", "passthrough"))));
        assertNull(SimulateMatch.evalOptionProblem(Map.of("sim", List.of("full", "full"), "eval", List.of("passthrough", "forge"))));
    }

    @Test
    public void aHeuristicSeatTakesOnlyForgesEvaluator() {
        assertNull(SimulateMatch.evalOptionProblem(Map.of("sim", List.of("full", "heuristic"), "eval", List.of("random", "forge"))));
        assertRefused(Map.of("sim", List.of("full", "heuristic"), "eval", List.of("forge", "random")), "seat 2 is heuristic");
        // a seat -sim leaves out is heuristic
        assertRefused(Map.of("sim", List.of("full"), "eval", List.of("random", "random")), "seat 2 is heuristic");
        assertRefused(Map.of("eval", List.of("passthrough")), "seat 1 is heuristic");
    }

    @Test
    public void anUnknownEvaluatorIsRefusedWithTheNames() {
        assertRefused(Map.of("sim", List.of("full", "full"), "eval", List.of("Random", "forge")),
                "Unknown evaluator - Random. Available evaluators: forge, random, passthrough");
    }

    @Test
    public void theFlagNeedsAValue() {
        assertRefused(Map.of("eval", List.of()), "one evaluator per seat");
    }

    @Test
    public void notForTournaments() {
        assertRefused(Map.of("t", List.of("bracket"), "eval", List.of("forge")), "-t");
    }
}
