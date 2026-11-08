package forge.ai;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertSame;
import static org.testng.AssertJUnit.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.testng.annotations.Test;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import forge.ai.simulation.GameCopier;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.HiddenReadWatch;
import forge.game.zone.ZoneType;

/**
 * This class is in forge.ai on purpose: the tripwire tells forge-ai from the rules engine by stack frame, so a read made
 * from a test method here is a forge-ai read.
 */
public class HiddenReadWatchTest extends AITest {
    private Game game;
    private Player ai;
    private Player opp;
    private HiddenReadWatch watch;
    private Card fog;
    private Card aiTop;

    /** Two AI seats as forge sim sets them up, in AI's main phase, with a card in the opponent's hand and library. */
    private void startGame(final boolean scoped) {
        final List<RegisteredPlayer> players = Lists.newArrayList();
        for (final String name : List.of("opp", "ai")) {
            final LobbyPlayerAi lobby = new LobbyPlayerAi(name, null);
            lobby.setScopedDecisions(scoped);
            players.add(new RegisteredPlayer(new Deck()).setPlayer(lobby));
        }
        final GameRules rules = new GameRules(GameType.Constructed);
        game = new Game(players, rules, new Match(rules, players, "Test"));
        opp = game.getPlayers().get(0);
        ai = game.getPlayers().get(1);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.getPhaseHandler().onStackResolved();
        addCard("Forest", opp);
        fog = addCardToZone("Fog", opp, ZoneType.Hand);
        addCardToZone("Lightning Bolt", opp, ZoneType.Library);
        addCardToZone("Island", opp, ZoneType.Library);
        addCardToZone("Island", ai, ZoneType.Hand);
        fillLibrary(ai, 5);
        aiTop = ai.getCardsIn(ZoneType.Library).getFirst();
        watch = new HiddenReadWatch();
        game.HIDDEN_READ_WATCH = watch;
    }

    private void startDeciding() {
        startGame(true);
        watch.enter(ai);
    }

    private void assertOneHitIn(final String method) {
        assertEquals(watch.hitsBySignature().toString(), 1, watch.hitCount());
        final String signature = watch.hitsBySignature().keySet().iterator().next();
        assertTrue(signature, signature.startsWith("forge.ai." + method + ":"));
    }

    private void assertNoHits() {
        assertEquals(watch.hitsBySignature().toString(), 0, watch.hitCount());
    }

    @Test
    public void theFogPeekFires() {
        startDeciding();
        assertTrue(ComputerUtil.hasAFogEffect(opp, ai, false));
        final List<String> first = new ArrayList<>(watch.hitsBySignature().keySet());
        assertTrue(first.toString(), first.stream().anyMatch(s -> s.startsWith("forge.ai.ComputerUtil.hasAFogEffect:")));
    }

    @Test
    public void aCountReadDoesNotFire() {
        startDeciding();
        assertEquals(1, opp.getCardsIn(ZoneType.Hand).size());
        assertFalse(opp.getCardsIn(ZoneType.Hand).isEmpty());
        assertEquals(2, opp.getCardsIn(ZoneType.Library).size());
        assertFalse(opp.getCardsIn(ZoneType.Library).isEmpty());
        assertEquals(1, Iterables.size(opp.getCardsIn(ZoneType.Hand)));
        assertFalse(Iterables.isEmpty(opp.getCardsIn(ZoneType.Library)));
        assertEquals(1, opp.getZone(ZoneType.Hand).size());
        assertFalse(opp.getZone(ZoneType.Library).isEmpty());
        assertNoHits();
    }

    /** ChangeZoneAi does this, with the zones a script names, and only asks whether the result is empty. */
    @Test
    public void aCountOverSeveralZonesDoesNotFire() {
        startDeciding();
        final List<ZoneType> zones = List.of(ZoneType.Hand, ZoneType.Library);
        assertEquals(3, opp.getCardsIn(zones).size());
        assertFalse(opp.getCardsIn(zones).isEmpty());
        assertEquals(3, opp.getCardsIn(ZoneType.Hand, ZoneType.Library).size());
        assertFalse(opp.getCardsIn(ZoneType.Battlefield, ZoneType.Hand).isEmpty());
        assertNoHits();
    }

    @Test
    public void aLookOverSeveralZonesFires() {
        startDeciding();
        final CardCollectionView cards = opp.getCardsIn(List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library));
        assertEquals(List.of("Forest", "Fog", "Lightning Bolt", "Island"), cards.stream().map(Card::getName).toList());
        assertOneHitIn("HiddenReadWatchTest.aLookOverSeveralZonesFires");
    }

    @Test
    public void aLookOverPublicZonesDoesNotFire() {
        startDeciding();
        assertEquals("Forest", opp.getCardsIn(ZoneType.Graveyard, ZoneType.Battlefield).getFirst().getName());
        assertNoHits();
    }

    @Test
    public void aCountThenALookIsARead() {
        startDeciding();
        final CardCollectionView hand = opp.getCardsIn(ZoneType.Hand);
        assertEquals(1, hand.size());
        assertNoHits();
        assertEquals("Fog", hand.getFirst().getName());
        assertEquals(1, watch.hitCount());
    }

    /** The frame that iterates here is forge-game's CardLists. A check made at the point of use would call it the rules. */
    @Test
    public void aForgeGameHelperOnAViewForgeAiAskedForIsStillARead() {
        startDeciding();
        assertEquals(1, CardLists.filter(opp.getCardsIn(ZoneType.Hand), c -> true).size());
        assertOneHitIn("HiddenReadWatchTest.aForgeGameHelperOnAViewForgeAiAskedForIsStillARead");
    }

    @Test
    public void everyWayToReadAViewFires() {
        final List<Predicate<CardCollectionView>> reads = List.of(
                v -> v.iterator() != null, v -> v.get(0) != null, v -> v.getFirst() != null, v -> v.getLast() != null,
                v -> v.indexOf(null) < 1, v -> v.lastIndexOf(null) < 1, v -> !v.contains(null), v -> v.stream() != null,
                v -> v.anyMatch(c -> true), v -> v.allMatch(c -> true), v -> v.subList(0, 1) != null,
                v -> v.threadSafeIterable() != null, v -> v.toArray().length > 0, v -> !v.toString().isEmpty(),
                v -> new CardCollection(v).size() > 0, v -> new ArrayList<>(v).size() > 0, v -> v.hashCode() != 1);
        for (int i = 0; i < reads.size(); i++) {
            startDeciding();
            assertTrue("read " + i, reads.get(i).test(opp.getCardsIn(ZoneType.Hand)));
            assertEquals("read " + i, 1, watch.hitCount());
        }
    }

    @Test
    public void aViewCountsOnceHoweverOftenItIsUsed() {
        startDeciding();
        final CardCollectionView hand = opp.getCardsIn(ZoneType.Hand);
        hand.getFirst();
        hand.iterator();
        assertEquals(1, watch.hitCount());
    }

    @Test
    public void readsStraightOffTheZoneFire() {
        startDeciding();
        assertNotNull(opp.getZone(ZoneType.Library).get(0));
        assertEquals(1, watch.hitCount());
        assertNotNull(opp.getZone(ZoneType.Hand).iterator());
        assertEquals(2, watch.hitCount());
        assertTrue(opp.getZone(ZoneType.Hand).contains(c -> true));
        assertEquals(3, watch.hitCount());
    }

    @Test
    public void wholeGameReadsFire() {
        startDeciding();
        assertFalse(opp.getAllCards().isEmpty());
        assertNoHits();
        assertTrue(opp.getAllCards().iterator().hasNext());
        assertEquals(1, watch.hitCount());
        assertFalse(game.getCardsInGame().isEmpty());
        assertEquals("the opponent's hand and library", 3, watch.hitCount());
        assertFalse(game.getCardsIn(ZoneType.Hand).isEmpty());
        assertEquals(4, watch.hitCount());
    }

    @Test
    public void aViewGivesTheSameCardsAsTheZone() {
        startDeciding();
        final CardCollection real = new CardCollection();
        opp.getZone(ZoneType.Library).getCards(); // not consumed, so no hit
        assertNoHits();
        real.addAll(opp.getCardsIn(ZoneType.Library));
        assertEquals(List.of("Lightning Bolt", "Island"), real.stream().map(Card::getName).toList());
    }

    /** Aggregates.random takes a different path, with different draws, for anything that isn't a List. */
    @Test
    public void aViewIsAListSoItDrawsTheSameRandomNumbers() {
        startDeciding();
        assertTrue(opp.getCardsIn(ZoneType.Library) instanceof List);
    }

    @Test
    public void theDecidersOwnZonesDoNotFire() {
        startDeciding();
        assertEquals("Island", ai.getCardsIn(ZoneType.Hand).getFirst().getName());
        assertNotNull(ai.getZone(ZoneType.Library).get(0));
        assertNoHits();
    }

    @Test
    public void publicZonesDoNotFire() {
        startDeciding();
        addCardToZone("Fog", opp, ZoneType.Graveyard);
        assertEquals("Fog", opp.getCardsIn(ZoneType.Graveyard).getFirst().getName());
        assertEquals("Forest", opp.getCardsIn(ZoneType.Battlefield).getFirst().getName());
        assertNoHits();
    }

    @Test
    public void aCardTheRulesLetTheAiSeeDoesNotFire() {
        startDeciding();
        fog.addMayLookTemp(ai);
        assertEquals("Fog", opp.getCardsIn(ZoneType.Hand).getFirst().getName());
        assertNoHits();
        addCardToZone("Island", opp, ZoneType.Hand);
        assertEquals("Fog", opp.getCardsIn(ZoneType.Hand).getFirst().getName());
        assertEquals("one card is still hidden", 1, watch.hitCount());
    }

    @Test
    public void anEmptyZoneHasNothingToGiveAway() {
        startDeciding();
        assertFalse(opp.getCardsIn(ZoneType.Exile).iterator().hasNext());
        assertFalse(opp.getCardsIn(ZoneType.Sideboard).iterator().hasNext());
        game.getAction().moveToGraveyard(fog, null);
        assertFalse(opp.getCardsIn(ZoneType.Hand).iterator().hasNext());
        assertNoHits();
    }

    @Test
    public void aRulesReadDuringADecisionDoesNotFire() {
        startDeciding();
        final Card source = addCard("Island", ai);
        final SpellAbility sa = source.getFirstSpellAbility();
        // the rules engine counts the opponent's hand, for a cost or a legality check, inside an AI decision
        assertEquals(1, AbilityUtils.calculateAmount(source, "Count$ValidHand Card.OppOwn", sa));
        assertNoHits();
    }

    @Test
    public void lookingUpACardByIdentityDoesNotFire() {
        startDeciding();
        assertSame(fog, game.getCardState(fog));
        assertNoHits();
    }

    @Test
    public void activatableCardsDoNotFire() {
        startDeciding();
        opp.getCardsActivatableInExternalZones(true);
        ai.getCardsActivatableInExternalZones(true);
        assertNoHits();
    }

    @Test
    public void readsOnASimulationCopyDoNotFireButTheCopyIsChecked() {
        startDeciding();
        final Game copy = new GameCopier(game).makeCopy(null, ai);
        assertNull(copy.HIDDEN_READ_WATCH);
        assertEquals(1, watch.copiesChecked());
        // the prune is off, so the copy holds the opponent's hand and library: one hit for each place, per copy
        final Map<String, Integer> checked = watch.hitsBySignature();
        assertEquals(Map.of(HiddenReadWatch.COPY + "Hand", 1, HiddenReadWatch.COPY + "Library", 1), checked);
        final Player copiedOpp = copy.getPlayers().get(0);
        assertEquals("opp", copiedOpp.getName());
        assertEquals("Fog", copiedOpp.getCardsIn(ZoneType.Hand).getFirst().getName());
        assertEquals(checked, watch.hitsBySignature());
    }

    @Test
    public void aReadOnTheAiEvalThreadFires() throws InterruptedException {
        startDeciding();
        // the heuristic AI picks spells on a thread of its own, and the decider isn't kept per thread
        final Thread eval = new Thread(() -> opp.getCardsIn(ZoneType.Hand).getFirst(), "Game AI Eval");
        eval.start();
        eval.join();
        assertEquals(1, watch.hitCount());
    }

    @Test
    public void nestedDecisionsReadAsTheInnerPlayer() {
        startDeciding();
        watch.enter(opp);
        assertEquals("Fog", opp.getCardsIn(ZoneType.Hand).getFirst().getName());
        assertNoHits();
        assertEquals("Island", ai.getCardsIn(ZoneType.Hand).getFirst().getName());
        assertEquals(1, watch.hitCount());
        watch.exit(opp);
        assertEquals("Island", ai.getCardsIn(ZoneType.Hand).getFirst().getName());
        assertEquals(1, watch.hitCount());
    }

    /** Resolves a sorcery from the real stack. Nothing here opens a decision: only the controller call can. */
    private void resolve(final SpellAbility sa) {
        sa.setActivatingPlayer(ai);
        game.getStack().add(sa);
        game.getStack().resolveStack();
    }

    @Test
    public void aCardNamedWhileASpellResolvesFires() {
        startGame(true);
        resolve(addCardToZone("Conjurer's Ban", ai, ZoneType.Hand).getFirstSpellAbility());
        assertEquals(watch.hitsBySignature().toString(), 1, watch.hitCount());
        final String signature = watch.hitsBySignature().keySet().iterator().next();
        assertTrue(signature, signature.startsWith("forge.ai.PlayerControllerAi.chooseCardName:"));
    }

    @Test
    public void aClashResolvingFiresOnThePeekAndNotOnTheCount() {
        startGame(true);
        aiTop.addMayLookTemp(ai);
        final SpellAbility clash = addCardToZone("Whirlpool Whelm", ai, ZoneType.Hand).getFirstSpellAbility();
        clash.getTargets().add(addCard("Grizzly Bears", opp));
        resolve(clash);
        // ClashAi looks at the size of the library first, and only then at the top card
        assertOneHitIn("ability.ClashAi.chooseSinglePlayer");
    }

    @Test
    public void aDecisionNoBracketCoveredStillFires() {
        startGame(false);
        resolve(addCardToZone("Conjurer's Ban", ai, ZoneType.Hand).getFirstSpellAbility());
        // own library read too, because with no decider nothing says whose it is
        assertFalse(watch.hitsBySignature().isEmpty());
        for (final String signature : watch.hitsBySignature().keySet()) {
            assertTrue(signature, signature.startsWith(HiddenReadWatch.NO_DECIDER));
        }
    }

    @Test
    public void everyControllerDecisionIsBracketed() {
        final List<String> missing = new ArrayList<>();
        for (final Method method : PlayerControllerAi.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic() || Set.of("isAI").contains(method.getName())
                    || !overridesPlayerController(method)) {
                continue;
            }
            try {
                ScopedPlayerControllerAi.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (final NoSuchMethodException e) {
                missing.add(method.toString());
            }
        }
        assertTrue(missing.toString(), missing.isEmpty());
    }

    private static boolean overridesPlayerController(final Method method) {
        try {
            PlayerController.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (final NoSuchMethodException e) {
            return false;
        }
    }

    @Test
    public void defaultPlayHasNoWatchAndNoScope() {
        final Game plain = initAndCreateGame();
        assertNull(plain.HIDDEN_READ_WATCH);
        final Player player = plain.getPlayers().get(0);
        assertEquals(PlayerControllerAi.class, player.getController().getClass());
        assertTrue(player.getCardsIn(ZoneType.Hand) instanceof CardCollection);
    }

    @Test
    public void aScopedControllerInAnUnwatchedGameStillDecides() {
        startGame(true);
        game.HIDDEN_READ_WATCH = null;
        resolve(addCardToZone("Conjurer's Ban", ai, ZoneType.Hand).getFirstSpellAbility());
        assertEquals(ScopedPlayerControllerAi.class, ai.getController().getClass());
        assertNoHits();
    }
}
