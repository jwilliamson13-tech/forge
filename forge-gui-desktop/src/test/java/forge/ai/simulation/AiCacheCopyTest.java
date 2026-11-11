package forge.ai.simulation;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.AiCache;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * The gen-0 ladder of 2026-11-10 ran five full-sim batches out of heap. AiCache held every game copy a decision's search
 * made, through the copies' games and players it was keyed to, until the next real decision cleared it.
 */
public class AiCacheCopyTest extends SimulationTest {

    private static final Predicate<Object> OF_A_COPY = o -> o instanceof Game g ? g.isSimulationCopy()
            : o instanceof Player p ? p.getGame().isSimulationCopy()
            : o instanceof Card c && c.getGame() != null && c.getGame().isSimulationCopy();

    @BeforeMethod
    @AfterMethod
    public void clearCache() {
        AiCache.clear(); // it is global, so no test should see another's entries
    }

    @Test
    public void aSearchLeavesNoCopyHeldByTheCache() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        p.setTeam(0);
        opponent.setTeam(1);
        for (final Card bear : addCards("Grizzly Bears", 2, p)) {
            bear.setSickness(false);
        }
        addCards("Mountain", 2, p);
        addCardToZone("Shock", p, ZoneType.Hand);
        addCard("Runeclaw Bear", opponent);
        opponent.setLife(6, null);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        new SpellAbilityPicker(p).chooseSpellAbilityToPlay(null);

        // the search's copies predicted combat, which is what keyed entries to them
        AssertJUnit.assertTrue(AiCache.weakArgsStored() > 0);
        AssertJUnit.assertEquals(0, AiCache.countEntriesHoldingStrongly(OF_A_COPY));
        AssertJUnit.assertEquals(0, entriesOfCopiesAfterGc());
    }

    /** An entry keyed to an object that is still in use keeps being hit, whatever the collector does. */
    @Test
    public void anEntryKeyedToALiveObjectStillHits() {
        final Object key = new Object();
        final AtomicInteger computed = new AtomicInteger();

        AiCache.getCached("test", computed::incrementAndGet, List.of(AiCache.IDENTITY), key);
        collect();
        final int again = AiCache.getCached("test", computed::incrementAndGet, List.of(AiCache.IDENTITY), key);

        AssertJUnit.assertEquals(1, again);
        AssertJUnit.assertEquals(1, computed.get());
        AiCache.getCached("test", computed::incrementAndGet, List.of(AiCache.IDENTITY), new Object());
        AssertJUnit.assertEquals(2, computed.get());
    }

    /** An argument compared by equals is held as before, so an equal one made later still hits. */
    @Test
    public void anArgumentComparedByEqualsIsStillHeld() {
        final AtomicInteger computed = new AtomicInteger();

        AiCache.getCached("test", computed::incrementAndGet, List.of(Objects::equals), new String("deck"));
        collect();
        AiCache.getCached("test", computed::incrementAndGet, List.of(Objects::equals), new String("deck"));

        AssertJUnit.assertEquals(1, computed.get());
        AssertJUnit.assertEquals(0, AiCache.weakArgsStored());
    }

    /** Collects until the entries of copies are gone, for at most about five seconds. */
    private static int entriesOfCopiesAfterGc() {
        int left = AiCache.countEntriesWithArg(OF_A_COPY);
        for (int i = 0; i < 50 && left > 0; i++) {
            collect();
            left = AiCache.countEntriesWithArg(OF_A_COPY);
        }
        return left;
    }

    private static void collect() {
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
