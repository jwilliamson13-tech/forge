package forge.view;

import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

import forge.ai.AIOption;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

public class SimulateMatchSimModeTest {

    @Test
    public void heuristicMeansNoSimulationOption() {
        assertTrue(SimulateMatch.aiOptionsForSimMode("heuristic").isEmpty());
    }

    @Test
    public void hybridAndFullMapToTheirLobbyOptions() {
        assertEquals(Set.of(AIOption.USE_HYBRID_SIMULATION), SimulateMatch.aiOptionsForSimMode("hybrid"));
        assertEquals(Set.of(AIOption.USE_FULL_SIMULATION), SimulateMatch.aiOptionsForSimMode("full"));
    }

    @Test
    public void anUnknownModeIsRefused() {
        assertNull(SimulateMatch.aiOptionsForSimMode("Full"));
    }

    @Test
    public void theSearchChangesApplyOnlyWhenASeatSearches() {
        assertTrue(SimulateMatch.anySeatSearches(List.of("full", "heuristic")));
        assertTrue(SimulateMatch.anySeatSearches(List.of("heuristic", "hybrid")));
        assertFalse(SimulateMatch.anySeatSearches(List.of("heuristic", "heuristic")));
        assertFalse(SimulateMatch.anySeatSearches(null));
    }

    @Test
    public void aBudgetIsAWholeNumberAboveZero() {
        assertEquals(400, SimulateMatch.parseSearchBudget(List.of("400")));
        assertEquals(0, SimulateMatch.parseSearchBudget(List.of("0")));
        assertEquals(0, SimulateMatch.parseSearchBudget(List.of("-5")));
        assertEquals(0, SimulateMatch.parseSearchBudget(List.of("many")));
        assertEquals(0, SimulateMatch.parseSearchBudget(List.of()));
    }
}
