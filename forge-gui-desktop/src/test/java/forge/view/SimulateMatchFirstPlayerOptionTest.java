package forge.view;

import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/** forge sim -firstplayer random|loser. */
public class SimulateMatchFirstPlayerOptionTest {
    private static Map<String, List<String>> run(final String... values) {
        return Map.of("d", List.of("a.dck", "b.dck"), "firstplayer", List.of(values));
    }

    @Test
    public void leftOutTheLoserGoesFirstAsInStockForge() {
        final Map<String, List<String>> params = Map.of("d", List.of("a.dck", "b.dck"));
        assertNull(SimulateMatch.firstPlayerOptionProblem(params));
        assertFalse(SimulateMatch.randomFirstPlayer(params));
    }

    @Test
    public void randomFlipsForEveryGame() {
        assertNull(SimulateMatch.firstPlayerOptionProblem(run("random")));
        assertTrue(SimulateMatch.randomFirstPlayer(run("random")));
    }

    @Test
    public void loserIsTheStockRule() {
        assertNull(SimulateMatch.firstPlayerOptionProblem(run("loser")));
        assertFalse(SimulateMatch.randomFirstPlayer(run("loser")));
    }

    @Test
    public void anythingElseIsRefused() {
        assertTrue(SimulateMatch.firstPlayerOptionProblem(run("winner")).contains("-firstplayer"));
        assertTrue(SimulateMatch.firstPlayerOptionProblem(run()).contains("-firstplayer"));
        assertTrue(SimulateMatch.firstPlayerOptionProblem(run("random", "loser")).contains("-firstplayer"));
    }
}
