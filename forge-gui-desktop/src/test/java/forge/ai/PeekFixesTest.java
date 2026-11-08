package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import com.google.common.collect.Lists;

import forge.ai.ability.ChangeZoneAi;
import forge.ai.simulation.GameCopier;
import forge.card.CardStateName;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.RevealLog;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.HiddenReadWatch;
import forge.game.zone.ZoneType;

/**
 * The peek fixes: with Game.AI_HONEST_PLAY on, each site makes the choice a person in its seat would, and the tripwire
 * stays silent. With it off the site still peeks, because default play keeps its peeks.
 * <p>
 * This class is in forge.ai on purpose: the tripwire tells forge-ai from the rules engine by stack frame.
 */
public class PeekFixesTest extends AITest {
    private Game game;
    private Player ai;
    private Player opp;
    private HiddenReadWatch watch;

    /** Two AI seats as forge sim sets them up, with AI deciding in its main phase. */
    private void startGame(final boolean honest) {
        final List<RegisteredPlayer> players = Lists.newArrayList();
        for (final String name : List.of("opp", "ai")) {
            final LobbyPlayerAi lobby = new LobbyPlayerAi(name, null);
            lobby.setScopedDecisions(true);
            players.add(new RegisteredPlayer(new Deck()).setPlayer(lobby));
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        game = new Game(players, rules, new Match(rules, players, "Test"));
        opp = game.getPlayers().get(0);
        ai = game.getPlayers().get(1);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getPhaseHandler().onStackResolved();
        fillLibrary(ai, 5);
        game.AI_HONEST_PLAY = honest;
        game.REVEAL_LOG = new RevealLog(game);
        watch = new HiddenReadWatch();
        game.HIDDEN_READ_WATCH = watch;
        watch.enter(ai);
    }

    private void reveal(final Card card) {
        game.getAction().reveal(new CardCollection(card), card.getOwner());
    }

    private void resolve(final SpellAbility sa) {
        sa.setActivatingPlayer(ai);
        game.getStack().add(sa);
        game.getStack().resolveStack();
    }

    private void assertNoHits() {
        assertEquals(watch.hitsBySignature().toString(), 0, watch.hitCount());
    }

    private void assertHitIn(final String method) {
        assertTrue(watch.hitsBySignature().toString(),
                watch.hitsBySignature().keySet().stream().anyMatch(s -> s.startsWith("forge.ai." + method + ":")));
    }

    private void fillHiddenZones(final String name, final int copies) {
        for (int i = 0; i < copies; i++) {
            addCardToZone(name, opp, ZoneType.Library);
        }
    }

    @Test
    public void theOpponentsLibraryTopIsNoCandidate() {
        startGame(true);
        final Card top = addCardToZone("Lightning Bolt", opp, ZoneType.Library);
        final CardCollection cards = ComputerUtilAbility.getAvailableCards(game, ai);
        assertFalse(cards.contains(top));
        assertTrue("the AI still knows its own top card", cards.contains(ai.getCardsIn(ZoneType.Library).getFirst()));
        assertNoHits();
    }

    @Test
    public void anOpponentsCardTheAiMayPlayIsACandidateOnceSeen() {
        startGame(true);
        final Card top = addCardToZone("Lightning Bolt", opp, ZoneType.Library);
        reveal(top);
        top.setMayPlay(new PlayerCollection(ai), false, null, false, false, null);
        assertTrue(ComputerUtilAbility.getAvailableCards(game, ai).contains(top));
        assertNoHits();
    }

    @Test
    public void withTheFlagOffTheLibraryTopStillGoesIn() {
        startGame(false);
        final Card top = addCardToZone("Lightning Bolt", opp, ZoneType.Library);
        assertTrue(ComputerUtilAbility.getAvailableCards(game, ai).contains(top));
        assertHitIn("ComputerUtilAbility.getAvailableCards");
    }

    @Test
    public void anUnseenSpiritGuideIsNoManaSource() {
        startGame(true);
        final Card guide = addCardToZone("Simian Spirit Guide", opp, ZoneType.Hand);
        assertFalse(ComputerUtilMana.getAvailableManaSources(opp, true).contains(guide));
        assertNoHits();
    }

    @Test
    public void aSpiritGuideSeenInHandIsAManaSource() {
        startGame(true);
        final Card guide = addCardToZone("Simian Spirit Guide", opp, ZoneType.Hand);
        reveal(guide);
        assertTrue(ComputerUtilMana.getAvailableManaSources(opp, true).contains(guide));
        assertNoHits();
    }

    @Test
    public void aPlayerPayingForThemselvesStillHasTheirWholeHand() {
        startGame(true);
        final Card guide = addCardToZone("Simian Spirit Guide", ai, ZoneType.Hand);
        assertTrue(ComputerUtilMana.getAvailableManaSources(ai, true).contains(guide));
        assertNoHits();
    }

    @Test
    public void theDecidingOpponentUsesItsOwnHand() {
        startGame(true);
        final Card guide = addCardToZone("Simian Spirit Guide", opp, ZoneType.Hand);
        watch.enter(opp);
        assertTrue(ComputerUtilMana.getAvailableManaSources(opp, true).contains(guide));
        watch.exit(opp);
        assertNoHits();
    }

    /** The opponent's spell targets the AI's creature with Ward, so the AI asks whether the opponent will pay. */
    private SpellAbility boltAtSquelcher() {
        final Card squelcher = addCardToZone("Hexing Squelcher", ai, ZoneType.Battlefield);
        final SpellAbility bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Hand).getFirstSpellAbility();
        bolt.setActivatingPlayer(opp);
        bolt.getTargets().add(squelcher);
        return bolt;
    }

    @Test
    public void aWardCheckForTheOpponentLeavesTheirHandAlone() {
        startGame(true);
        addCardToZone("Mountain", opp, ZoneType.Hand);
        ComputerUtilCost.canPayCost(boltAtSquelcher(), opp, false);
        assertNoHits();
    }

    @Test
    public void withTheFlagOffTheWardCheckReadsTheHand() {
        startGame(false);
        addCardToZone("Mountain", opp, ZoneType.Hand);
        ComputerUtilCost.canPayCost(boltAtSquelcher(), opp, false);
        assertTrue(watch.hitsBySignature().toString(),
                watch.hitsBySignature().keySet().stream().anyMatch(s -> s.contains("ComputerUtilCost.checkDiscardCost:")));
    }

    @Test
    public void anOpponentsDiscardCostGoesByTheHandSeen() {
        startGame(true);
        final Card squee = addCardToZone("Squee, Goblin Nabob", opp, ZoneType.Hand);
        final Card source = addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        final SpellAbility sa = source.getFirstSpellAbility();
        sa.setActivatingPlayer(opp);
        final Cost discard = new Cost("Discard<1/Card>", false);
        assertFalse("nothing seen in their hand to throw away", ComputerUtilCost.checkDiscardCost(opp, discard, source, sa));
        reveal(squee);
        assertTrue("Squee was shown, and it wants discarding", ComputerUtilCost.checkDiscardCost(opp, discard, source, sa));
        assertNoHits();
    }

    @Test
    public void orderingTheOpponentsLibraryTopGoesByWhatWasSeen() {
        startGame(true);
        addCardToZone("Mountain", opp, ZoneType.Hand);
        addCardToZone("Grizzly Bears", opp, ZoneType.Library);
        final Card top = addCardToZone("Lightning Bolt", opp, ZoneType.Library);
        ComputerUtil.scryWillMoveCardToBottomOfLibrary(opp, top);
        assertNoHits();
    }

    @Test
    public void orderingItsOwnLibraryTopReadsItsOwnCards() {
        startGame(true);
        addCardToZone("Mountain", ai, ZoneType.Hand);
        final Card top = ai.getCardsIn(ZoneType.Library).getFirst();
        ComputerUtil.scryWillMoveCardToBottomOfLibrary(ai, top);
        assertNoHits();
    }

    @Test
    public void withTheFlagOffOrderingTheOpponentsLibraryTopReadsTheirCards() {
        startGame(false);
        addCardToZone("Mountain", opp, ZoneType.Hand);
        final Card top = addCardToZone("Lightning Bolt", opp, ZoneType.Library);
        ComputerUtil.scryWillMoveCardToBottomOfLibrary(opp, top);
        assertTrue(watch.hitsBySignature().toString(),
                watch.hitsBySignature().keySet().stream().anyMatch(s -> s.contains("scryWillMoveCardToBottomOfLibrary")));
    }

    @Test
    public void beseechTheMirrorLetsTheSearcherSeeTheCardItExiled() {
        startGame(true);
        final Card beseech = addCardToZone("Beseech the Mirror", ai, ZoneType.Hand);
        assertTrue("the search asks for a look at the card", beseech.getFirstSpellAbility().hasParam("WithMayLook"));
        final SpellAbility search = AbilityFactory.getAbility(
                "SP$ ChangeZone | ChangeNum$ 1 | ChangeType$ Card | Mandatory$ True | Origin$ Library | Destination$ Exile"
                        + " | ExileFaceDown$ True | WithMayLook$ True | Defined$ TopOfLibrary", beseech);
        search.setActivatingPlayer(ai);
        search.resolve();
        final Card exiled = ai.getCardsIn(ZoneType.Exile).getFirst();
        assertTrue(exiled.isFaceDown());
        assertTrue("the searcher may look at it", exiled.getView().canFaceDownBeShownTo(ai.getView()));
        assertFalse("the opponent may not", exiled.getView().canFaceDownBeShownTo(opp.getView()));
    }

    @Test
    public void duressRevealsTheHandSoTheThreatCheckNeedsNoPeek() {
        startGame(true);
        addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        final Card land = addCardToZone("Mountain", opp, ZoneType.Hand);
        final SpellAbility duress = addCardToZone("Duress", ai, ZoneType.Hand).getFirstSpellAbility();
        duress.getTargets().add(opp);
        resolve(duress);
        assertTrue("the hand was written down", HonestPlay.rememberedIn(ai, opp, ZoneType.Hand).contains(land));
        assertEquals("Lightning Bolt", opp.getCardsIn(ZoneType.Graveyard).getFirst().getName());
        assertNoHits();
    }

    @Test
    public void withTheFlagOffTheThreatCheckReadsTheHand() {
        startGame(false);
        addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        addCardToZone("Mountain", opp, ZoneType.Hand);
        final SpellAbility duress = addCardToZone("Duress", ai, ZoneType.Hand).getFirstSpellAbility();
        duress.getTargets().add(opp);
        resolve(duress);
        assertHitIn("HonestPlay.handAsKnown");
    }

    @Test
    public void aShapeshifterInTheOpponentsLibraryDoesNotReorderTheGraveyard() {
        startGame(true);
        addCardToZone("Volrath's Shapeshifter", opp, ZoneType.Library);
        final Card bears = addCard("Grizzly Bears", ai);
        final Card forest = addCard("Forest", ai);
        final CardCollection cards = new CardCollection(List.of(bears, forest));
        assertEquals(cards, ai.getController().orderMoveToZoneList(cards, ZoneType.Graveyard, null));
        assertNoHits();
    }

    @Test
    public void aShapeshifterInPublicReordersTheGraveyard() {
        startGame(true);
        addCardToZone("Volrath's Shapeshifter", opp, ZoneType.Graveyard);
        final Card bears = addCard("Grizzly Bears", ai);
        final Card forest = addCard("Forest", ai);
        final CardCollectionView order = ai.getController().orderMoveToZoneList(new CardCollection(List.of(bears, forest)), ZoneType.Graveyard, null);
        assertEquals("the best creature ends on top", bears, order.getLast());
        assertNoHits();
    }

    @Test
    public void anUnseenFogIsNotExpected() {
        startGame(true);
        addCard("Forest", opp);
        addCardToZone("Fog", opp, ZoneType.Hand);
        assertFalse(ComputerUtil.hasAFogEffect(opp, ai, false));
        assertNoHits();
    }

    @Test
    public void aFogOnTheNotepadIsExpected() {
        startGame(true);
        addCard("Forest", opp);
        reveal(addCardToZone("Fog", opp, ZoneType.Hand));
        assertTrue(ComputerUtil.hasAFogEffect(opp, ai, false));
        assertNoHits();
    }

    @Test
    public void theAiStillKnowsItsOwnFog() {
        startGame(true);
        addCard("Forest", ai);
        addCardToZone("Fog", ai, ZoneType.Hand);
        assertTrue(ComputerUtil.hasAFogEffect(ai, ai, false));
        assertNoHits();
    }

    @Test
    public void withTheFlagOffTheFogPeekStands() {
        startGame(false);
        addCard("Forest", opp);
        addCardToZone("Fog", opp, ZoneType.Hand);
        assertTrue(ComputerUtil.hasAFogEffect(opp, ai, false));
        assertHitIn("ComputerUtil.hasAFogEffect");
    }

    private SpellAbility chaliceReadyToCast() {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
        for (int i = 0; i < 4; i++) {
            addCard("Plains", ai);
        }
        final SpellAbility chalice = addCardToZone("Chalice of the Void", ai, ZoneType.Hand).getFirstSpellAbility();
        chalice.setActivatingPlayer(ai);
        return chalice;
    }

    private AiPlayDecision chaliceDecision(final SpellAbility chalice) {
        return ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(chalice);
    }

    @Test
    public void chaliceIsHeldWithNothingSeenAtThatValue() {
        startGame(true);
        fillHiddenZones("Goblin Guide", 8);
        assertFalse(chaliceDecision(chaliceReadyToCast()) == AiPlayDecision.WillPlay);
        assertNoHits();
    }

    @Test
    public void chaliceGoesOnWhatTheOpponentHasShown() {
        startGame(true);
        for (int i = 0; i < 2; i++) {
            addCardToZone("Goblin Guide", opp, ZoneType.Graveyard);
        }
        final SpellAbility chalice = chaliceReadyToCast();
        assertEquals(AiPlayDecision.WillPlay, chaliceDecision(chalice));
        assertEquals(1, chalice.getXManaCostPaid().intValue());
        assertNoHits();
    }

    @Test
    public void withTheFlagOffChaliceReadsTheLibrary() {
        startGame(false);
        fillHiddenZones("Goblin Guide", 8);
        final SpellAbility chalice = chaliceReadyToCast();
        assertEquals(AiPlayDecision.WillPlay, chaliceDecision(chalice));
        assertHitIn("ability.PermanentAi.checkApiLogic");
    }

    private String nameFor(final SpellAbility sa) {
        sa.setActivatingPlayer(ai);
        return ai.getController().chooseCardName(sa, face -> true, "", "Name a card");
    }

    @Test
    public void namingGoesByTheSpellSeenMost() {
        startGame(true);
        fillHiddenZones("Counterspell", 4);
        addCard("Island", opp);
        addCard("Island", opp);
        addCard("Island", opp);
        addCardToZone("Brainstorm", opp, ZoneType.Graveyard);
        assertEquals("Brainstorm", nameFor(addCardToZone("Conjurer's Ban", ai, ZoneType.Hand).getFirstSpellAbility()));
        assertNoHits();
    }

    @Test
    public void namingWithNothingSeenFallsBackAndIgnoresTheLibrary() {
        startGame(true);
        fillHiddenZones("Counterspell", 4);
        assertEquals("Morphling", nameFor(addCardToZone("Conjurer's Ban", ai, ZoneType.Hand).getFirstSpellAbility()));
        assertNoHits();
    }

    @Test
    public void namingWithNoLogicReadsOnlyWhatIsKnown() {
        startGame(true);
        fillHiddenZones("Counterspell", 4);
        reveal(addCardToZone("Fog", opp, ZoneType.Hand));
        assertEquals("Fog", nameFor(addCardToZone("Lightning Bolt", ai, ZoneType.Hand).getFirstSpellAbility()));
        assertNoHits();
    }

    @Test
    public void withTheFlagOffNamingReadsTheLibrary() {
        startGame(false);
        fillHiddenZones("Counterspell", 4);
        assertEquals("Counterspell", nameFor(addCardToZone("Conjurer's Ban", ai, ZoneType.Hand).getFirstSpellAbility()));
        assertHitIn("PlayerControllerAi.chooseCardName");
    }

    private SpellAbility therapyAimedAtOpp() {
        final SpellAbility sa = addCardToZone("Cabal Therapy", ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        for (SpellAbility a = sa; a != null; a = a.getSubAbility()) {
            if (a.getApi() == ApiType.Discard && a.usesTargeting()) {
                a.getTargets().add(opp);
            }
        }
        return sa;
    }

    @Test
    public void cabalTherapyGuessesFromPublicCardsNotTheLibrary() {
        startGame(true);
        fillHiddenZones("Counterspell", 4);
        addCardToZone("Brainstorm", opp, ZoneType.Graveyard);
        assertEquals("Brainstorm", NamedDiscard.chooseName(ai, therapyAimedAtOpp(), "Card.nonLand"));
        assertNoHits();
    }

    @Test
    public void cabalTherapyNamesACardWrittenDownInTheHand() {
        startGame(true);
        addCardToZone("Brainstorm", opp, ZoneType.Graveyard);
        reveal(addCardToZone("Lightning Bolt", opp, ZoneType.Hand));
        addCardToZone("Fog", opp, ZoneType.Hand);
        assertEquals("Lightning Bolt", NamedDiscard.chooseName(ai, therapyAimedAtOpp(), "Card.nonLand"));
        assertNoHits();
    }

    @Test
    public void pithingNeedleScoresOnlyWhatIsKnown() {
        startGame(true);
        addCardToZone("Aether Vial", opp, ZoneType.Library);
        final SpellAbility sa = AbilityFactory.getAbility(addCard("Pithing Needle", ai), "DBNameCard");
        assertEquals("Liliana of the Veil", SpecialCardAi.PithingNeedle.chooseCard(ai, sa));
        addCard("Aether Vial", opp);
        assertEquals("Aether Vial", SpecialCardAi.PithingNeedle.chooseCard(ai, sa));
        assertNoHits();
    }

    @Test
    public void aClashResolvesWithoutSeeingTheirTopCard() {
        startGame(true);
        fillHiddenZones("Counterspell", 2);
        ai.getCardsIn(ZoneType.Library).getFirst().addMayLookTemp(ai);
        final SpellAbility clash = addCardToZone("Whirlpool Whelm", ai, ZoneType.Hand).getFirstSpellAbility();
        clash.getTargets().add(addCard("Grizzly Bears", opp));
        resolve(clash);
        assertNoHits();
    }

    @Test
    public void withTheFlagOffAClashReadsTheirTopCard() {
        startGame(false);
        fillHiddenZones("Counterspell", 2);
        ai.getCardsIn(ZoneType.Library).getFirst().addMayLookTemp(ai);
        final SpellAbility clash = addCardToZone("Whirlpool Whelm", ai, ZoneType.Hand).getFirstSpellAbility();
        clash.getTargets().add(addCard("Grizzly Bears", opp));
        resolve(clash);
        assertHitIn("ability.ClashAi.chooseSinglePlayer");
    }

    private Card gatekeeperPick() {
        fillHiddenZones("Grizzly Bears", 6);
        final Card bears = addCardToZone("Grizzly Bears", opp, ZoneType.Graveyard);
        final Card bolt = addCardToZone("Lightning Bolt", opp, ZoneType.Graveyard);
        addCardToZone("Shock", opp, ZoneType.Exile);
        addCardToZone("Shock", opp, ZoneType.Exile);
        final SpellAbility sa = AbilityFactory.getAbility(addCard("Cemetery Gatekeeper", ai), "TrigExile");
        sa.setActivatingPlayer(ai);
        return ChangeZoneAi.doExilePreferenceLogic(ai, sa, new CardCollection(List.of(bears, bolt)));
    }

    @Test
    public void theFetchTypeIsTheTypeTheOpponentHasShown() {
        startGame(true);
        assertEquals("Lightning Bolt", gatekeeperPick().getName());
        assertNoHits();
    }

    @Test
    public void withTheFlagOffTheFetchTypeComesFromTheirLibrary() {
        startGame(false);
        assertEquals("Grizzly Bears", gatekeeperPick().getName());
        assertHitIn("ability.ChangeZoneAi.doExilePreferenceLogic");
    }

    @Test
    public void showAndTellDoesNotPeekAtTheOtherHand() {
        startGame(true);
        for (int i = 0; i < 3; i++) {
            addCard("Island", ai);
        }
        addCardToZone("Grizzly Bears", ai, ZoneType.Hand);
        addCardToZone("Lightning Bolt", opp, ZoneType.Hand);
        final SpellAbility show = addCardToZone("Show and Tell", ai, ZoneType.Hand).getFirstSpellAbility();
        show.setActivatingPlayer(ai);
        ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(show);
        assertNoHits();
    }

    @Test
    public void withNoLogHonestPlayGoesByPublicCards() {
        startGame(true);
        game.REVEAL_LOG = null;
        addCard("Forest", opp);
        addCardToZone("Fog", opp, ZoneType.Hand);
        assertFalse(ComputerUtil.hasAFogEffect(opp, ai, false));
        addCardToZone("Brainstorm", opp, ZoneType.Graveyard);
        assertEquals("Brainstorm", nameFor(addCardToZone("Conjurer's Ban", ai, ZoneType.Hand).getFirstSpellAbility()));
        assertNoHits();
    }

    @Test
    public void aCopyPlaysHonestlyWithADeciderStackOfItsOwn() {
        startGame(true);
        addCard("Forest", opp);
        final Game copy = new GameCopier(game).makeCopy();
        assertTrue(copy.AI_HONEST_PLAY);
        assertNull(copy.REVEAL_LOG);
        assertNull(copy.AI_DECIDERS.current());
    }

    private Card morph(final Player controller) {
        final Card card = addCard("Grizzly Bears", controller);
        card.turnFaceDown(true);
        return card;
    }

    @Test
    public void readingAnOpponentsFaceDownCardFires() {
        startGame(true);
        final Card morph = morph(opp);
        assertEquals("Grizzly Bears", morph.getState(CardStateName.Original).getName());
        assertEquals(1, watch.hitCount());
        assertTrue(watch.hitsBySignature().toString(),
                watch.hitsBySignature().keySet().iterator().next().startsWith(HiddenReadWatch.FACE_DOWN + "forge.ai.PeekFixesTest"));
        morph.getOriginalState(CardStateName.Original);
        assertEquals(2, watch.hitCount());
        morph.getRules();
        assertTrue(watch.hitCount() > 2);
    }

    @Test
    public void aFaceDownCardsPublicSideIsFree() {
        startGame(true);
        final Card morph = morph(opp);
        morph.getState(CardStateName.FaceDown);
        assertEquals("", morph.getName());
        assertEquals(2, morph.getNetPower());
        assertNoHits();
    }

    @Test
    public void readingYourOwnFaceDownCardIsFree() {
        startGame(true);
        morph(ai).getState(CardStateName.Original);
        assertNoHits();
    }

    @Test
    public void aFaceDownCardTheAiMayLookAtIsFree() {
        startGame(true);
        final Card morph = morph(opp);
        morph.addMayLookTemp(ai);
        morph.updateStateForView();
        morph.getState(CardStateName.Original);
        assertNoHits();
    }

    @Test
    public void readingAFaceUpCardIsFree() {
        startGame(true);
        addCard("Grizzly Bears", opp).getState(CardStateName.Original);
        assertNoHits();
    }

    @Test
    public void theRulesReadingAFaceDownCardIsSilent() {
        startGame(true);
        game.REVEAL_LOG.revealed(ai, morph(opp));
        assertEquals("Grizzly Bears", game.REVEAL_LOG.knownTo(ai).get(0).name());
        assertNoHits();
    }

    @Test
    public void predictingABlockTreatsTheirMorphAsAMorph() {
        startGame(true);
        final Card morph = morph(opp);
        assertFalse(HonestPlay.deciderCanNameIt(morph));
        watch.enter(opp);
        assertTrue("the one who controls it knows it", HonestPlay.deciderCanNameIt(morph));
        watch.exit(opp);
    }

    @Test
    public void aFaceDownOpposingCardIsNotOneTheAiCanName() {
        startGame(true);
        final Card morph = morph(opp);
        assertFalse(HonestPlay.knownCards(ai, opp).contains(morph));
    }
}
