package forge.ai.simulation;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.RevealLog;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.HiddenReadWatch;
import forge.game.zone.ZoneType;

/**
 * Issue 25: with Game.AI_PRUNE_HIDDEN_INFO on, a copy made for a searching AI holds no card of another player's hand,
 * library or face-down side that the AI couldn't know. Its own cards and what is on its notepad stay.
 */
public class GameCopierPruneTest extends SimulationTest {
    private static final String HIDDEN = GameCopier.HIDDEN_CARD_NAME;

    private Game game;
    private Player opp;
    private Player ai;

    /** The AI in its main phase, with a notepad as forge sim keeps one with -record, and a few cards on each side. */
    private void start(final boolean prune) {
        game = initAndCreateGame();
        opp = game.getPlayers().get(0);
        ai = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getPhaseHandler().onStackResolved();
        game.AI_PRUNE_HIDDEN_INFO = prune;
        game.REVEAL_LOG = new RevealLog(game);
        addCardToZone("Fog", opp, ZoneType.Hand);
        addCardToZone("Counterspell", opp, ZoneType.Hand);
        for (final String name : List.of("Island", "Force of Will", "Brainstorm")) {
            addCardToZone(name, opp, ZoneType.Library);
        }
        addCardToZone("Shock", ai, ZoneType.Hand);
        addCardToZone("Mountain", ai, ZoneType.Hand);
        for (final String name : List.of("Lightning Bolt", "Mountain", "Grizzly Bears")) {
            addCardToZone(name, ai, ZoneType.Library);
        }
    }

    /** What each card really is, face down or not, in zone order. */
    private static List<String> identities(final Player player, final ZoneType zone) {
        final List<String> names = new ArrayList<>();
        for (final Card c : player.getCardsIn(zone)) {
            names.add(c.getState(CardStateName.Original).getName());
        }
        return names;
    }

    private static List<Integer> ids(final Player player, final ZoneType zone) {
        final List<Integer> ids = new ArrayList<>();
        for (final Card c : player.getCardsIn(zone)) {
            ids.add(c.getId());
        }
        return ids;
    }

    private Card morph(final Player controller) {
        final Card card = addCard("Grizzly Bears", controller);
        card.turnFaceDown(true);
        return card;
    }

    @Test
    public void withThePruneOffACopyHoldsEveryCard() {
        start(false);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        final Player copiedOpp = copier.find(opp);
        assertEquals(List.of("Fog", "Counterspell"), identities(copiedOpp, ZoneType.Hand));
        assertEquals(List.of("Island", "Force of Will", "Brainstorm"), identities(copiedOpp, ZoneType.Library));
    }

    @Test
    public void aPrunedCopyHoldsNoOpponentHandOrLibraryCard() {
        start(true);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        final Player copiedOpp = copier.find(opp);
        assertEquals(List.of(HIDDEN, HIDDEN), identities(copiedOpp, ZoneType.Hand));
        assertEquals(List.of(HIDDEN, HIDDEN, HIDDEN), identities(copiedOpp, ZoneType.Library));
        // the same sizes, and the same card ids, which the real game shows too
        assertEquals(ids(opp, ZoneType.Hand), ids(copiedOpp, ZoneType.Hand));
        assertEquals(ids(opp, ZoneType.Library), ids(copiedOpp, ZoneType.Library));
        for (final Card c : copiedOpp.getCardsIn(ZoneType.Hand)) {
            // a card with no mana cost can't be cast, so the copy's opponent can't play a placeholder
            assertTrue(c.getManaCost().isNoCost());
        }
    }

    @Test
    public void theAiKeepsItsOwnHandAndLibraryInOrder() {
        start(true);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        final Player copiedAi = copier.find(ai);
        assertEquals(identities(ai, ZoneType.Hand), identities(copiedAi, ZoneType.Hand));
        assertEquals(identities(ai, ZoneType.Library), identities(copiedAi, ZoneType.Library));
        assertEquals(ids(ai, ZoneType.Library), ids(copiedAi, ZoneType.Library));
    }

    @Test
    public void aCardOnTheNotepadKeepsItsIdentityAndGoesOnTheCopysNotepad() {
        start(true);
        final Card counterspell = opp.getCardsIn(ZoneType.Hand).get(1);
        game.REVEAL_LOG.revealed(ai, counterspell);
        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy(null, ai);
        final Player copiedOpp = copier.find(opp);
        // believed cards fill the unseen slots in order, whichever real card is there
        assertEquals(List.of("Counterspell", HIDDEN), identities(copiedOpp, ZoneType.Hand));
        assertNotNull(copy.REVEAL_LOG);
        final List<RevealLog.Entry> notepad = copy.REVEAL_LOG.writtenDown(copier.find(ai));
        assertEquals(1, notepad.size());
        assertEquals("Counterspell", notepad.get(0).name());
        assertEquals(ZoneType.Hand, notepad.get(0).seenIn());
        assertTrue(copy.REVEAL_LOG.writtenDown(copiedOpp).isEmpty());
    }

    @Test
    public void theCopyGoesByWhereTheNotepadSawACardNotWhereItWent() {
        start(true);
        final Card counterspell = opp.getCardsIn(ZoneType.Hand).get(1);
        game.REVEAL_LOG.revealed(ai, counterspell);
        // put back on top of the library unseen, as Brainstorm does: a person would still think it's in hand
        opp.getZone(ZoneType.Hand).remove(counterspell);
        opp.getZone(ZoneType.Library).add(counterspell, 0);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        final Player copiedOpp = copier.find(opp);
        assertEquals(List.of("Counterspell"), identities(copiedOpp, ZoneType.Hand));
        assertEquals(List.of(HIDDEN, HIDDEN, HIDDEN, HIDDEN), identities(copiedOpp, ZoneType.Library));
    }

    @Test
    public void aLibraryCardTheNotepadSawGoesOnTop() {
        start(true);
        final Card force = opp.getCardsIn(ZoneType.Library).get(1);
        game.REVEAL_LOG.revealed(ai, force);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        assertEquals(List.of("Force of Will", HIDDEN, HIDDEN), identities(copier.find(opp), ZoneType.Library));
    }

    @Test
    public void aLibraryTopTheAiMayLookAtStaysWhereItIs() {
        start(true);
        final Card brainstorm = opp.getCardsIn(ZoneType.Library).get(2);
        brainstorm.addMayLookTemp(ai);
        final GameCopier copier = new GameCopier(game);
        final Game copy = copier.makeCopy(null, ai);
        assertEquals(List.of(HIDDEN, HIDDEN, "Brainstorm"), identities(copier.find(opp), ZoneType.Library));
        assertEquals(1, copy.REVEAL_LOG.writtenDown(copier.find(ai)).size());
    }

    @Test
    public void anOpponentsFaceDownCardIsAPlaceholderAndTheAisOwnIsNot() {
        start(true);
        final Card theirs = morph(opp);
        final Card mine = morph(ai);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        final Card theirCopy = copier.find(theirs);
        final Card myCopy = copier.find(mine);
        assertTrue(theirCopy.isFaceDown());
        assertEquals(2, theirCopy.getNetPower());
        assertEquals(HIDDEN, theirCopy.getState(CardStateName.Original).getName());
        assertTrue(myCopy.isFaceDown());
        assertEquals("Grizzly Bears", myCopy.getState(CardStateName.Original).getName());
    }

    @Test
    public void aFaceDownCardTheNotepadSawThereKeepsItsIdentity() {
        start(true);
        final Card theirs = morph(opp);
        game.REVEAL_LOG.revealed(ai, theirs);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        assertEquals("Grizzly Bears", copier.find(theirs).getState(CardStateName.Original).getName());
    }

    @Test
    public void theFallbackCopyPathPrunesTheSame() {
        GameCopier.useFromPaperCard = false;
        try {
            start(true);
            final Card counterspell = opp.getCardsIn(ZoneType.Hand).get(1);
            game.REVEAL_LOG.revealed(ai, counterspell);
            final Card theirs = morph(opp);
            final GameCopier copier = new GameCopier(game);
            copier.makeCopy(null, ai);
            final Player copiedOpp = copier.find(opp);
            assertEquals(List.of("Counterspell", HIDDEN), identities(copiedOpp, ZoneType.Hand));
            assertEquals(List.of(HIDDEN, HIDDEN, HIDDEN), identities(copiedOpp, ZoneType.Library));
            assertEquals(HIDDEN, copier.find(theirs).getState(CardStateName.Original).getName());
            assertEquals(identities(ai, ZoneType.Hand), identities(copier.find(ai), ZoneType.Hand));
            assertEquals(identities(ai, ZoneType.Library), identities(copier.find(ai), ZoneType.Library));
        } finally {
            GameCopier.useFromPaperCard = true;
        }
    }

    @Test
    public void aSnapshotCopyIsNotUsedWhenPruning() {
        start(true);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = true;
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        assertEquals(List.of(HIDDEN, HIDDEN), identities(copier.find(opp), ZoneType.Hand));
    }

    @Test
    public void aSecondCopyIsForTheSamePlayerAndACopyForNoOneHidesEverything() {
        start(true);
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        copier.makeCopy();
        assertEquals(identities(ai, ZoneType.Hand), identities(copier.find(ai), ZoneType.Hand));
        assertEquals(List.of(HIDDEN, HIDDEN), identities(copier.find(opp), ZoneType.Hand));

        final GameCopier forNoOne = new GameCopier(game);
        forNoOne.makeCopy();
        assertEquals(List.of(HIDDEN, HIDDEN), identities(forNoOne.find(ai), ZoneType.Hand));
        assertEquals(List.of(HIDDEN, HIDDEN), identities(forNoOne.find(opp), ZoneType.Hand));
    }

    @Test
    public void aCopyOfAPrunedCopyStaysPruned() {
        start(true);
        final Card counterspell = opp.getCardsIn(ZoneType.Hand).get(1);
        game.REVEAL_LOG.revealed(ai, counterspell);
        final GameCopier first = new GameCopier(game);
        final Game copy = first.makeCopy(null, ai);
        assertTrue(copy.AI_PRUNE_HIDDEN_INFO);
        final GameCopier second = new GameCopier(copy);
        second.makeCopy(null, first.find(ai));
        final Player twiceCopiedOpp = second.find(first.find(opp));
        assertEquals(List.of("Counterspell", HIDDEN), identities(twiceCopiedOpp, ZoneType.Hand));
    }

    @Test
    public void theTripwireCountsEachHiddenPlaceACopyHoldsWithThePruneOff() {
        start(false);
        final HiddenReadWatch watch = new HiddenReadWatch();
        game.HIDDEN_READ_WATCH = watch;
        morph(opp);
        new GameCopier(game).makeCopy(null, ai);
        assertEquals(1, watch.copiesChecked());
        assertEquals(Map.of(HiddenReadWatch.COPY + "Hand", 1, HiddenReadWatch.COPY + "Library", 1,
                HiddenReadWatch.COPY + "face-down Battlefield", 1), watch.hitsBySignature());
    }

    @Test
    public void theTripwireFindsNothingInAPrunedCopy() {
        start(true);
        final HiddenReadWatch watch = new HiddenReadWatch();
        game.HIDDEN_READ_WATCH = watch;
        morph(opp);
        game.REVEAL_LOG.revealed(ai, opp.getCardsIn(ZoneType.Hand).get(0));
        final GameCopier copier = new GameCopier(game);
        copier.makeCopy(null, ai);
        copier.makeCopy();
        assertEquals(2, watch.copiesChecked());
        assertEquals(0, watch.hitCount());
    }

    @Test
    public void theSearchStillPlaysOnPrunedCopies() {
        start(true);
        final HiddenReadWatch watch = new HiddenReadWatch();
        game.HIDDEN_READ_WATCH = watch;
        addCard("Mountain", ai);
        addCard("Runeclaw Bear", opp);
        opp.setLife(2, null);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        game.getAction().checkStateEffects(true);

        final SpellAbility sa = new SpellAbilityPicker(ai).chooseSpellAbilityToPlay(null);

        assertNotNull(sa);
        assertEquals("Shock", sa.getHostCard().getName());
        assertNull(sa.getTargetCard());
        assertEquals(opp, sa.getTargets().getFirstTargetedPlayer());
        // the real game's reads here have no decider open, so only the copies' signatures are this test's business
        assertTrue(watch.copiesChecked() > 0);
        assertFalse(watch.hitsBySignature().toString(), watch.hitsBySignature().keySet().stream().anyMatch(s -> s.startsWith(HiddenReadWatch.COPY)));
    }
}
