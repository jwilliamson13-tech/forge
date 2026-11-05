package forge.view;

import java.util.Set;

import org.testng.annotations.Test;

import forge.ai.AIOption;

import static org.testng.AssertJUnit.assertEquals;
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
}
