package forge.view;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiCardMemory;
import forge.ai.simulation.GameCopier;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.RevealLog;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

public class RevealLogTest extends AITest {
    private Game game;
    private Player viewer;
    private Player opp;

    private void setUp() {
        game = initAndCreateGame();
        viewer = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        fillLibrary(viewer, 10);
        fillLibrary(opp, 10);
        game.REVEAL_LOG = new RevealLog(game);
    }

    /** The rules' own reveal, which reaches each player through their controller. */
    private void reveal(final Card card) {
        game.getAction().reveal(new CardCollection(card), card.getOwner());
    }

    private List<String> logNames(final Player who) {
        return game.REVEAL_LOG.knownTo(who).stream().map(RevealLog.Entry::name).toList();
    }

    private boolean aiRemembers(final Card card) {
        return AiCardMemory.getMemorySet(viewer, AiCardMemory.MemorySet.REVEALED_CARDS).contains(card);
    }

    @Test
    public void aRevealedHandCardGoesOnTheLog() {
        setUp();
        reveal(addCardToZone("Lightning Bolt", opp, ZoneType.Hand));
        assertEquals(List.of("Lightning Bolt"), logNames(viewer));
        assertTrue("the owner's side has nothing to write down", logNames(opp).isEmpty());
    }

    @Test
    public void aRevealedLibraryCardGoesOnTheLog() {
        setUp();
        reveal(addCardToZone("Counterspell", opp, ZoneType.Library));
        assertEquals(List.of("Counterspell"), logNames(viewer));
    }

    @Test
    public void theEntryIsWhatWasSeen() {
        setUp();
        final Card bears = addCardToZone("Grizzly Bears", opp, ZoneType.Hand);
        reveal(bears);
        final RevealLog.Entry entry = game.REVEAL_LOG.knownTo(viewer).get(0);
        assertEquals(bears.getId(), entry.id());
        assertEquals(opp, entry.owner());
        assertEquals(CardStateName.Original, entry.face());
        assertEquals(Integer.valueOf(2), entry.power());
        assertEquals(Integer.valueOf(2), entry.toughness());
    }

    @Test
    public void ownCardsNeverGoOnTheLog() {
        setUp();
        final Card own = addCardToZone("Lightning Bolt", viewer, ZoneType.Hand);
        // false shows the card to its owner too
        game.getAction().reveal(new CardCollection(own), viewer, false);
        assertTrue(logNames(viewer).isEmpty());
    }

    @Test
    public void aCardAlreadyInPublicIsNotLogged() {
        setUp();
        reveal(addCardToZone("Lightning Bolt", opp, ZoneType.Graveyard));
        assertTrue(logNames(viewer).isEmpty());
    }

    @Test
    public void aRevealSurvivesTheEndStepThatClearsAiMemory() {
        setUp();
        // Forge clears REVEALED_CARDS at the AI's own end step, so reveal it on the viewer's turn
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, viewer, 1);
        game.getPhaseHandler().onStackResolved();
        final Card bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        reveal(bolt);
        assertTrue(aiRemembers(bolt));

        playUntilNextTurn(game);

        assertFalse("the test needs the memory cleared",
                aiRemembers(bolt));
        assertEquals(List.of("Lightning Bolt"), logNames(viewer));
    }

    @Test
    public void aRevealSurvivesAHiddenMove() {
        setUp();
        final Card bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        reveal(bolt);

        final Card moved = game.getAction().moveToLibrary(bolt, null);
        assertEquals(List.of("Lightning Bolt"), logNames(viewer));

        opp.shuffle(null);
        assertEquals(List.of("Lightning Bolt"), logNames(viewer));

        game.getAction().moveToHand(moved, null);
        assertEquals(List.of("Lightning Bolt"), logNames(viewer));
    }

    @Test
    public void aRevealLeavesOnAPublicMoveAndDoesNotComeBack() {
        setUp();
        final Card bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        reveal(bolt);

        final Card inGraveyard = game.getAction().moveToGraveyard(bolt, null);
        assertTrue(logNames(viewer).isEmpty());

        game.getAction().moveToLibrary(inGraveyard, null);
        assertTrue("a card that left isn't written back by a hidden move", logNames(viewer).isEmpty());
    }

    @Test
    public void aFaceDownMoveIsHiddenUntilTheCardTurnsUp() {
        setUp();
        final Card bears = addCardToZone("Grizzly Bears", opp, ZoneType.Hand);
        reveal(bears);

        final Card exiled = game.getAction().exile(bears, null, null);
        exiled.turnFaceDown(true);
        assertEquals("dropping it now would say which face-down card it is", List.of("Grizzly Bears"), logNames(viewer));

        exiled.turnFaceUp(null);
        assertTrue(logNames(viewer).isEmpty());
    }

    @Test
    public void aFaceDownCardRevealedInPlaceKeepsItsRealName() {
        setUp();
        final Card morph = addCard("Grizzly Bears", opp);
        morph.turnFaceDown(true);
        reveal(morph);
        assertEquals(List.of("Grizzly Bears"), logNames(viewer));
    }

    @Test
    public void aCardThePlayerMayLookAtGoesOnTheLogAndStays() {
        setUp();
        final Card top = addCardToZone("Lightning Bolt", opp, ZoneType.Library);
        top.addMayLookTemp(viewer);
        assertEquals("a standing look needs no reveal call", List.of("Lightning Bolt"), logNames(viewer));

        top.removeMayLookTemp(viewer);
        assertEquals(List.of("Lightning Bolt"), logNames(viewer));
    }

    @Test
    public void entriesComeOutInIdOrder() {
        setUp();
        final Card first = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        final Card second = addCardToZone("Counterspell", opp, ZoneType.Hand);
        reveal(second);
        reveal(first);
        assertEquals(List.of("Lightning Bolt", "Counterspell"), logNames(viewer));
    }

    @Test
    public void defaultPlayHasNoLogAndKeepsAiMemory() {
        game = initAndCreateGame();
        viewer = game.getPlayers().get(1);
        opp = game.getPlayers().get(0);
        final Card bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);

        assertNull(game.REVEAL_LOG);
        reveal(bolt);

        assertNull(game.REVEAL_LOG);
        assertTrue(aiRemembers(bolt));
    }

    @Test
    public void aSimulatedGameHasNoLog() {
        setUp();
        final Card bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, viewer, 1);
        final Game copy = new GameCopier(game).makeCopy();
        assertNull(copy.REVEAL_LOG);

        final Card copiedBolt = copy.findById(bolt.getId());
        copy.getAction().reveal(new CardCollection(copiedBolt), copiedBolt.getOwner());
        assertTrue("a reveal in a copy never happened", logNames(viewer).isEmpty());
    }
}
