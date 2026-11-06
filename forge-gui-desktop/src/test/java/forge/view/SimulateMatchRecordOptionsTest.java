package forge.view;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

public class SimulateMatchRecordOptionsTest {
    private static Map<String, List<String>> recordingRun() {
        final Map<String, List<String>> params = new HashMap<>();
        params.put("record", List.of("C:\\runs\\records"));
        params.put("batch", List.of("night-1"));
        params.put("s", List.of("42"));
        params.put("d", List.of("a.dck", "b.dck"));
        return params;
    }

    private static void assertRefused(final Map<String, List<String>> params, final String expected) {
        final String problem = SimulateMatch.recordOptionsProblem(params);
        assertNotNull(problem);
        assertTrue(problem, problem.contains(expected));
    }

    @Test
    public void aRunThatDoesNotRecordIsLeftAlone() {
        assertNull(SimulateMatch.recordOptionsProblem(Map.of("d", List.of("a.dck", "b.dck"))));
    }

    @Test
    public void aCompleteRecordingRunIsAccepted() {
        assertNull(SimulateMatch.recordOptionsProblem(recordingRun()));
    }

    @Test
    public void theOtherOptionsNeedRecord() {
        assertRefused(Map.of("batch", List.of("x")), "-record");
        assertRefused(Map.of("turncap", List.of("10")), "-record");
    }

    @Test
    public void theTripwireIsOnForSimWithOrWithoutRecords() {
        assertTrue(SimulateMatch.tripwireOn(recordingRun()));
        assertTrue(SimulateMatch.tripwireOn(Map.of("d", List.of("a.dck", "b.dck"))));
        assertFalse(SimulateMatch.tripwireOn(Map.of("d", List.of("a.dck", "b.dck"), "tripwire", List.of("off"))));
    }

    @Test
    public void aTournamentHasNoTripwire() {
        assertFalse(SimulateMatch.tripwireOn(Map.of("d", List.of("a.dck", "b.dck"), "t", List.of("Bracket"))));
        assertTrue(SimulateMatch.tripwireOptionProblem(Map.of("tripwire", List.of("on"), "t", List.of("Bracket"))).contains("-t"));
    }

    @Test
    public void theTripwireCanBeSwitched() {
        final Map<String, List<String>> params = recordingRun();
        params.put("tripwire", List.of("off"));
        assertNull(SimulateMatch.tripwireOptionProblem(params));
        assertFalse(SimulateMatch.tripwireOn(params));
        params.put("tripwire", List.of("on"));
        assertNull(SimulateMatch.tripwireOptionProblem(params));
        assertTrue(SimulateMatch.tripwireOn(params));
    }

    @Test
    public void honestPlayTakesOnOrOffAndNotATournament() {
        assertNull(SimulateMatch.honestOptionProblem(Map.of("d", List.of("a.dck", "b.dck"))));
        assertNull(SimulateMatch.honestOptionProblem(Map.of("honest", List.of("on"))));
        assertNull(SimulateMatch.honestOptionProblem(Map.of("honest", List.of("off"))));
        assertTrue(SimulateMatch.honestOptionProblem(Map.of("honest", List.of("maybe"))).contains("-honest"));
        assertTrue(SimulateMatch.honestOptionProblem(Map.of("honest", List.of())).contains("-honest"));
        assertTrue(SimulateMatch.honestOptionProblem(Map.of("honest", List.of("on"), "t", List.of("Bracket"))).contains("-t"));
    }

    @Test
    public void tripwireTakesOnOrOff() {
        assertTrue(SimulateMatch.tripwireOptionProblem(Map.of("tripwire", List.of("maybe"))).contains("-tripwire"));
        assertTrue(SimulateMatch.tripwireOptionProblem(Map.of("tripwire", List.of())).contains("-tripwire"));
        assertNull(SimulateMatch.tripwireOptionProblem(Map.of("d", List.of("a.dck", "b.dck"))));
    }

    @Test
    public void recordTakesOneFolder() {
        final Map<String, List<String>> params = recordingRun();
        params.put("record", List.of());
        assertRefused(params, "-record");
    }

    @Test
    public void recordNeedsABatchIdTheFolderNameCanCarry() {
        final Map<String, List<String>> params = recordingRun();
        params.remove("batch");
        assertRefused(params, "-batch");
        params.put("batch", List.of("night 1"));
        assertRefused(params, "-batch");
    }

    @Test
    public void recordNeedsASeedInTheSchemasRange() {
        final Map<String, List<String>> params = recordingRun();
        params.remove("s");
        assertRefused(params, "-s");
        params.put("s", List.of("-1"));
        assertRefused(params, "-s");
    }

    @Test
    public void recordIsRefusedWithATournament() {
        final Map<String, List<String>> params = recordingRun();
        params.put("t", List.of("Swiss"));
        assertRefused(params, "-t");
    }

    @Test
    public void recordNeedsTwoDecks() {
        final Map<String, List<String>> params = recordingRun();
        params.put("d", List.of("a.dck", "b.dck", "c.dck"));
        assertRefused(params, "two decks");
    }

    @Test
    public void theTurnCapDefaultsTo50AndIsAWholeNumberAboveZero() {
        assertEquals(50, SimulateMatch.parseTurnCap(null));
        assertEquals(12, SimulateMatch.parseTurnCap(List.of("12")));
        assertEquals(0, SimulateMatch.parseTurnCap(List.of("0")));
        assertEquals(0, SimulateMatch.parseTurnCap(List.of("many")));

        final Map<String, List<String>> params = recordingRun();
        params.put("turncap", List.of("many"));
        assertRefused(params, "-turncap");
    }
}
