package forge.view;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.RevealLog;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

public class SnapshotLineTest extends AITest {
    private Game game;
    private Player seat1;
    private Player seat2;
    private final Map<Player, RecordIds> ids = new HashMap<>();

    private void setUp() {
        ids.clear();
        game = initAndCreateGame();
        seat1 = game.getRegisteredPlayers().get(0);
        seat2 = game.getRegisteredPlayers().get(1);
        game.REVEAL_LOG = new RevealLog(game);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seat1, 4);
    }

    private JsonObject from(final Player side) {
        return SnapshotLine.of(game, side, idsOf(side));
    }

    private RecordIds idsOf(final Player side) {
        return ids.computeIfAbsent(side, s -> new RecordIds(42L, 1, game.getRegisteredPlayers().indexOf(s) + 1));
    }

    /** The id {@code side}'s lines call this card by. */
    private int idOf(final Player side, final Card card) {
        return idsOf(side).of(card);
    }

    private JsonArray zone(final JsonObject line, final String name) {
        return line.getAsJsonObject("zones").getAsJsonArray(name);
    }

    private JsonObject cardIn(final JsonObject line, final String zoneName, final Card card) {
        for (final JsonElement element : zone(line, zoneName)) {
            if (element.getAsJsonObject().get("id").getAsInt() == idOf(sideOf(line), card)) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError(card + " is not in " + zoneName);
    }

    private JsonObject logged(final JsonObject line, final String name) {
        for (final JsonElement element : zone(line, "opp_revealed")) {
            if (element.getAsJsonObject().get("name").getAsString().equals(name)) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError(name + " is not on the log");
    }

    private Player sideOf(final JsonObject line) {
        return game.getRegisteredPlayers().get(line.get("side").getAsInt() - 1);
    }

    private static boolean isNull(final JsonObject object, final String field) {
        return object.has(field) && object.get(field).equals(JsonNull.INSTANCE);
    }

    private static List<Integer> ints(final JsonArray array) {
        final List<Integer> values = new ArrayList<>();
        array.forEach(value -> values.add(value.getAsInt()));
        return values;
    }

    @Test
    public void theScoreboardIsTheAgreedList() {
        setUp();
        seat1.setLife(17, null);
        seat2.setLife(9, null);
        seat2.setCounters(CounterEnumType.POISON, 3);
        fillLibrary(seat1, 4);
        fillLibrary(seat2, 6);
        addCardToZone("Lightning Bolt", seat2, ZoneType.Hand);
        addCardToZone("Lightning Bolt", seat2, ZoneType.Hand);

        final JsonObject board = from(seat1).getAsJsonObject("scoreboard");

        assertEquals(Set.of("me_life", "opp_life", "me_poison", "opp_poison", "me_library", "opp_library", "opp_hand",
                "turn", "me_active", "me_on_play", "me_initiative", "opp_initiative", "me_monarch", "opp_monarch",
                "me_speed", "opp_speed"), board.keySet());
        assertEquals(17, board.get("me_life").getAsInt());
        assertEquals(9, board.get("opp_life").getAsInt());
        assertEquals(0, board.get("me_poison").getAsInt());
        assertEquals(3, board.get("opp_poison").getAsInt());
        assertEquals(4, board.get("me_library").getAsInt());
        assertEquals(6, board.get("opp_library").getAsInt());
        assertEquals(2, board.get("opp_hand").getAsInt());
        assertEquals(4, board.get("turn").getAsInt());
        assertTrue(board.get("me_active").getAsBoolean());
        assertFalse(board.get("me_initiative").getAsBoolean());
        assertFalse(board.get("opp_monarch").getAsBoolean());
        assertEquals(0, board.get("me_speed").getAsInt());
    }

    @Test
    public void theInitiativeTheMonarchAndSpeedAreScoreboardFieldsFromEachSide() {
        setUp();
        game.setHasInitiative(seat1);
        game.setMonarch(seat2);
        seat1.setSpeed(3);
        seat2.setSpeed(1);

        final JsonObject mine = from(seat1).getAsJsonObject("scoreboard");
        final JsonObject theirs = from(seat2).getAsJsonObject("scoreboard");

        assertTrue(mine.get("me_initiative").getAsBoolean());
        assertFalse(mine.get("opp_initiative").getAsBoolean());
        assertFalse(mine.get("me_monarch").getAsBoolean());
        assertTrue(mine.get("opp_monarch").getAsBoolean());
        assertEquals(3, mine.get("me_speed").getAsInt());
        assertEquals(1, mine.get("opp_speed").getAsInt());
        assertFalse(theirs.get("me_initiative").getAsBoolean());
        assertTrue(theirs.get("opp_initiative").getAsBoolean());
        assertTrue(theirs.get("me_monarch").getAsBoolean());
        assertEquals(1, theirs.get("me_speed").getAsInt());
        assertEquals(3, theirs.get("opp_speed").getAsInt());
    }

    @Test
    public void theOpponentsHandAndBothLibrariesAreCountsOnly() {
        setUp();
        addCardToZone("Lightning Bolt", seat2, ZoneType.Hand);
        addCardToZone("Counterspell", seat2, ZoneType.Library);
        addCardToZone("Giant Growth", seat1, ZoneType.Library);
        addCardToZone("Shock", seat1, ZoneType.Hand);

        final JsonObject line = from(seat1);

        assertEquals(Set.of("battlefield", "me_hand", "me_graveyard", "opp_graveyard", "me_exile", "opp_exile", "opp_revealed"),
                line.getAsJsonObject("zones").keySet());
        final String text = line.toString();
        assertTrue(text.contains("Shock"));
        assertFalse(text.contains("Lightning Bolt"));
        assertFalse(text.contains("Counterspell"));
        assertFalse(text.contains("Giant Growth"));
    }

    @Test
    public void eachPublicZoneListsItsCards() {
        setUp();
        final Card mine = addCard("Grizzly Bears", seat1);
        final Card theirs = addCard("Hill Giant", seat2);
        final Card myDead = addCardToZone("Lightning Bolt", seat1, ZoneType.Graveyard);
        final Card theirDead = addCardToZone("Counterspell", seat2, ZoneType.Graveyard);
        final Card myExile = addCardToZone("Shock", seat1, ZoneType.Exile);
        final Card theirExile = addCardToZone("Giant Growth", seat2, ZoneType.Exile);

        final JsonObject line = from(seat2);

        assertEquals("Grizzly Bears", cardIn(line, "battlefield", mine).get("name").getAsString());
        assertEquals("Hill Giant", cardIn(line, "battlefield", theirs).get("name").getAsString());
        assertEquals("Counterspell", cardIn(line, "me_graveyard", theirDead).get("name").getAsString());
        assertEquals("Lightning Bolt", cardIn(line, "opp_graveyard", myDead).get("name").getAsString());
        assertEquals("Giant Growth", cardIn(line, "me_exile", theirExile).get("name").getAsString());
        assertEquals("Shock", cardIn(line, "opp_exile", myExile).get("name").getAsString());
    }

    @Test
    public void aCardCarriesEveryLiveField() {
        setUp();
        final Card bears = addCard("Grizzly Bears", seat1);
        bears.setCounters(CounterEnumType.P1P1, 2);
        bears.setSickness(false);
        bears.tap(false, null, null);
        bears.setDamage(1);

        final JsonObject card = cardIn(from(seat1), "battlefield", bears);

        assertEquals(Set.of("id", "name", "face", "power", "toughness", "tapped", "counters", "summoning_sick", "attached_to",
                "attached_to_seat", "controller", "face_down", "damage", "attacking_seat", "attacking_card", "blocking",
                "blocked_by"), card.keySet());
        assertEquals(idOf(seat1, bears), card.get("id").getAsInt());
        assertEquals("Grizzly Bears", card.get("name").getAsString());
        assertEquals("Original", card.get("face").getAsString());
        assertEquals(4, card.get("power").getAsInt());
        assertEquals(4, card.get("toughness").getAsInt());
        assertTrue(card.get("tapped").getAsBoolean());
        assertEquals(2, card.getAsJsonObject("counters").get("P1P1").getAsInt());
        assertFalse(card.get("summoning_sick").getAsBoolean());
        assertEquals(1, card.get("controller").getAsInt());
        assertFalse(card.get("face_down").getAsBoolean());
        assertEquals(1, card.get("damage").getAsInt());
    }

    @Test
    public void aNonCreatureHasNullPowerAndToughness() {
        setUp();
        final Card land = addCard("Mountain", seat1);
        final JsonObject card = cardIn(from(seat1), "battlefield", land);
        assertTrue(isNull(card, "power"));
        assertTrue(isNull(card, "toughness"));
    }

    @Test
    public void onlyAPermanentCanBeSummoningSick() {
        setUp();
        final Card inHand = addCardToZone("Grizzly Bears", seat1, ZoneType.Hand);
        final Card inPlay = addCard("Grizzly Bears", seat1);
        final JsonObject line = from(seat1);
        assertFalse(cardIn(line, "me_hand", inHand).get("summoning_sick").getAsBoolean());
        assertTrue(cardIn(line, "battlefield", inPlay).get("summoning_sick").getAsBoolean());
    }

    @Test
    public void attachmentsPointAtACardOrASeat() {
        setUp();
        final Card bears = addCard("Grizzly Bears", seat2);
        final Card aura = addCard("Pacifism", seat1);
        aura.attachToEntity(bears, null);
        final Card curse = addCard("Curse of Thirst", seat1);
        curse.attachToEntity(seat2, null);

        final JsonObject line = from(seat1);

        final JsonObject onCard = cardIn(line, "battlefield", aura);
        assertEquals(idOf(seat1, bears), onCard.get("attached_to").getAsInt());
        assertTrue(isNull(onCard, "attached_to_seat"));
        final JsonObject onSeat = cardIn(line, "battlefield", curse);
        assertTrue(isNull(onSeat, "attached_to"));
        assertEquals(2, onSeat.get("attached_to_seat").getAsInt());
    }

    @Test
    public void anAttachmentToACardOffTheBattlefieldIsNotWritten() {
        setUp();
        final Card dead = addCardToZone("Grizzly Bears", seat1, ZoneType.Graveyard);
        final Card aura = addCard("Pacifism", seat1);
        aura.attachToEntity(dead, null);

        assertTrue(isNull(cardIn(from(seat1), "battlefield", aura), "attached_to"));
    }

    @Test
    public void aFaceDownCardIsHiddenFromTheOpponentAndKnownToItsController() {
        setUp();
        final Card morph = addCard("Hill Giant", seat2);
        morph.setCounters(CounterEnumType.P1P1, 1);
        morph.turnFaceDown(true);

        final JsonObject hidden = cardIn(from(seat1), "battlefield", morph);
        assertTrue(isNull(hidden, "name"));
        assertTrue(isNull(hidden, "face"));
        assertTrue(hidden.get("face_down").getAsBoolean());
        assertEquals(3, hidden.get("power").getAsInt());
        assertEquals(1, hidden.getAsJsonObject("counters").get("P1P1").getAsInt());
        assertEquals(2, hidden.get("controller").getAsInt());

        final JsonObject known = cardIn(from(seat2), "battlefield", morph);
        assertEquals("Hill Giant", known.get("name").getAsString());
        assertEquals("Original", known.get("face").getAsString());
        assertTrue(known.get("face_down").getAsBoolean());
    }

    @Test
    public void aFaceDownExiledCardHasNoPowerAndKeepsItsNameFromTheOpponent() {
        setUp();
        final Card exiled = addCardToZone("Grizzly Bears", seat2, ZoneType.Exile);
        exiled.turnFaceDown(true);

        final JsonObject card = cardIn(from(seat1), "opp_exile", exiled);
        assertTrue(isNull(card, "name"));
        assertTrue("it isn't a 2/2 in exile", isNull(card, "power"));
        assertTrue(isNull(card, "toughness"));
    }

    @Test
    public void anEmptyRoomWritesTheCardsName() {
        setUp();
        final Card room = addCard("Bottomless Pool // Locker Room", seat1);
        room.setState(CardStateName.EmptyRoom, true);

        final JsonObject card = cardIn(from(seat2), "battlefield", room);
        assertEquals("Bottomless Pool // Locker Room", card.get("name").getAsString());
        assertEquals("EmptyRoom", card.get("face").getAsString());
    }

    @Test
    public void aRecordIdIsNotForgesAndDiffersByGameAndSide() {
        setUp();
        final Card bears = addCard("Grizzly Bears", seat1);

        final int mine = new RecordIds(42L, 1, 1).of(bears);

        assertTrue(mine != bears.getId());
        assertEquals("a replay gets the same id", mine, new RecordIds(42L, 1, 1).of(bears));
        assertTrue("the other side", mine != new RecordIds(42L, 1, 2).of(bears));
        assertTrue("the next game", mine != new RecordIds(42L, 2, 1).of(bears));
        assertTrue("another worker", mine != new RecordIds(43L, 1, 1).of(bears));
    }

    @Test
    public void aRecordIdDoesNotDependOnWhichCardItIs() {
        setUp();
        final Card first = addCard("Grizzly Bears", seat1);
        final Card second = addCard("Hill Giant", seat1);

        assertEquals("only the order this side saw them in counts", new RecordIds(42L, 1, 1).of(first),
                new RecordIds(42L, 1, 1).of(second));
    }

    @Test
    public void aCardKeepsItsRecordIdWhileItStaysInAZone() {
        setUp();
        final Card bears = addCard("Grizzly Bears", seat1);
        final int id = idOf(seat1, bears);

        bears.tap(false, null, null);
        bears.turnFaceDown(true);
        bears.setCounters(CounterEnumType.P1P1, 1);
        assertEquals(id, idOf(seat1, bears));

        bears.addTempController(seat2, game.getNextTimestamp());
        game.getAction().controllerChangeZoneCorrection(bears);
        assertEquals("the same zone, whoever holds it", id, idOf(seat1, bears));
    }

    @Test
    public void aCardGetsAFreshRecordIdEachTimeItChangesZone() {
        setUp();
        final Card inHand = addCardToZone("Grizzly Bears", seat1, ZoneType.Hand);
        final int fromHand = idOf(seat1, inHand);

        final Card inPlay = game.getAction().moveToPlay(inHand, null, null);
        final int fromPlay = idOf(seat1, inPlay);
        assertTrue(fromPlay != fromHand);

        final Card inExile = game.getAction().exile(inPlay, null, null);
        final int fromExile = idOf(seat1, inExile);
        assertTrue(fromExile != fromPlay);

        final Card blinked = game.getAction().moveToPlay(inExile, null, null);
        assertTrue("back in the same zone it is still a new object", idOf(seat1, blinked) != fromPlay);
        assertTrue(idOf(seat1, blinked) != fromExile);
    }

    @Test
    public void aSpellIsANewObjectThoughForgeKeepsItsTimestamp() {
        setUp();
        final Card inHand = addCardToZone("Lightning Bolt", seat1, ZoneType.Hand);
        final int fromHand = idOf(seat1, inHand);
        final long stamp = inHand.getGameTimestamp();

        final Card onStack = game.getAction().moveToStack(inHand, null);

        assertEquals(stamp, onStack.getGameTimestamp());
        assertTrue(idOf(seat1, onStack) != fromHand);
    }

    @Test
    public void aFaceDownCardsTwoSidesCantBeMatchedById() {
        setUp();
        final Card morph = addCard("Grizzly Bears", seat1);
        morph.turnFaceDown(true);

        final JsonObject theirs = zone(from(seat2), "battlefield").get(0).getAsJsonObject();
        final JsonObject mine = zone(from(seat1), "battlefield").get(0).getAsJsonObject();

        assertTrue(isNull(theirs, "name"));
        assertEquals("Grizzly Bears", mine.get("name").getAsString());
        assertTrue(theirs.get("id").getAsInt() != mine.get("id").getAsInt());
    }

    @Test
    public void theBattlefieldIsInRecordIdOrderSoTheSidesDontLineUp() {
        setUp();
        final List<Card> entered = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            entered.add(addCard("Grizzly Bears", seat1));
        }

        final List<Integer> mine = idsIn(from(seat1));
        final List<Integer> theirs = idsIn(from(seat2));

        assertEquals(mine.stream().sorted().toList(), mine);
        assertEquals(theirs.stream().sorted().toList(), theirs);
        final List<Card> bySeat1 = new ArrayList<>(entered);
        bySeat1.sort(Comparator.comparing(card -> idOf(seat1, card)));
        final List<Card> bySeat2 = new ArrayList<>(entered);
        bySeat2.sort(Comparator.comparing(card -> idOf(seat2, card)));
        assertFalse("the same cards, in another order", bySeat1.equals(bySeat2));
    }

    private List<Integer> idsIn(final JsonObject line) {
        final List<Integer> values = new ArrayList<>();
        zone(line, "battlefield").forEach(card -> values.add(card.getAsJsonObject().get("id").getAsInt()));
        return values;
    }

    @Test
    public void recordIdsDrawNothingFromTheGamesRandomStream() {
        setUp();
        addCard("Grizzly Bears", seat1);
        final long before = MyRandom.callCount();

        from(seat1);
        from(seat2);

        assertEquals(before, MyRandom.callCount());
    }

    @Test
    public void theRevealedZoneReadsTheLog() {
        setUp();
        final Card bears = addCardToZone("Grizzly Bears", seat2, ZoneType.Hand);
        game.getAction().reveal(new CardCollection(bears), seat2);

        final JsonObject card = logged(from(seat1), "Grizzly Bears");
        assertEquals("Grizzly Bears", card.get("name").getAsString());
        assertEquals("Original", card.get("face").getAsString());
        assertEquals(2, card.get("power").getAsInt());
        assertEquals(2, card.get("controller").getAsInt());
        assertTrue("the owner has nothing on the log", zone(from(seat2), "opp_revealed").isEmpty());
        assertTrue("an id would tie it to the card it becomes", isNull(card, "id"));
    }

    @Test
    public void aLoggedCardGivesNoLiveStateAwayOnceItIsFaceDown() {
        setUp();
        final Card bears = addCardToZone("Grizzly Bears", seat2, ZoneType.Hand);
        game.getAction().reveal(new CardCollection(bears), seat2);
        final Card inPlay = game.getAction().moveToPlay(bears, null, null);
        inPlay.turnFaceDown(true);
        inPlay.tap(false, null, null);
        inPlay.setCounters(CounterEnumType.P1P1, 1);

        final JsonObject card = logged(from(seat1), "Grizzly Bears");
        assertEquals("Grizzly Bears", card.get("name").getAsString());
        assertTrue("nor can an id tie them", isNull(card, "id"));
        assertFalse("tapped would tie it to the face-down card", card.get("tapped").getAsBoolean());
        assertEquals(0, card.getAsJsonObject("counters").size());
        assertFalse(card.get("face_down").getAsBoolean());
        assertEquals(2, card.get("power").getAsInt());
    }

    @Test
    public void theLogIsWrittenByNameAndNotInDeckOrder() {
        setUp();
        final Card first = addCardToZone("Runeclaw Bear", seat2, ZoneType.Hand);
        final Card second = addCardToZone("Grizzly Bears", seat2, ZoneType.Hand);
        game.getAction().reveal(new CardCollection(List.of(first, second)), seat2);

        final JsonArray log = zone(from(seat1), "opp_revealed");

        assertEquals("Grizzly Bears", log.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("Runeclaw Bear", log.get(1).getAsJsonObject().get("name").getAsString());
    }

    private SpellAbility onStack(final Card card, final Player controller, final Object... targets) {
        final SpellAbility sa = card.getFirstSpellAbility();
        return push(sa, controller, targets);
    }

    private SpellAbility push(final SpellAbility sa, final Player controller, final Object... targets) {
        sa.setActivatingPlayer(controller);
        for (final Object target : targets) {
            sa.getTargets().add((forge.game.GameObject) target);
        }
        game.getStack().add(sa);
        return sa;
    }

    @Test
    public void theStackListsEachItemTopFirst() {
        setUp();
        final Card creature = addCard("Grizzly Bears", seat2);
        final Card bolt = addCardToZone("Lightning Bolt", seat1, ZoneType.Hand);
        final SpellAbility boltSa = onStack(bolt, seat1, creature);
        final Card pinger = addCard("Prodigal Pyromancer", seat2);
        SpellAbility ping = null;
        for (final SpellAbility sa : pinger.getSpellAbilities()) {
            if (sa.isAbility() && sa.usesTargeting()) {
                ping = sa;
            }
        }
        push(ping, seat2, seat1);
        final Card counter = addCardToZone("Counterspell", seat2, ZoneType.Hand);
        onStack(counter, seat2, boltSa);

        final JsonArray stack = from(seat1).getAsJsonArray("stack");

        assertEquals(3, stack.size());
        final JsonObject top = stack.get(0).getAsJsonObject();
        assertEquals(Set.of("id", "name", "face", "face_down", "controller", "kind", "targets"), top.keySet());
        assertEquals("Counterspell", top.get("name").getAsString());
        assertEquals(idOf(seat1, counter), top.get("id").getAsInt());
        assertEquals("Original", top.get("face").getAsString());
        assertEquals(2, top.get("controller").getAsInt());
        assertEquals("spell", top.get("kind").getAsString());
        final JsonObject spellTarget = top.getAsJsonArray("targets").get(0).getAsJsonObject();
        assertEquals("a spell is targeted through its card", idOf(seat1, bolt), spellTarget.get("card").getAsInt());
        assertTrue(isNull(spellTarget, "seat"));

        final JsonObject middle = stack.get(1).getAsJsonObject();
        assertEquals("Prodigal Pyromancer", middle.get("name").getAsString());
        assertEquals("ability", middle.get("kind").getAsString());
        final JsonObject seatTarget = middle.getAsJsonArray("targets").get(0).getAsJsonObject();
        assertTrue(isNull(seatTarget, "card"));
        assertEquals(1, seatTarget.get("seat").getAsInt());

        final JsonObject bottom = stack.get(2).getAsJsonObject();
        assertEquals("Lightning Bolt", bottom.get("name").getAsString());
        assertEquals(1, bottom.get("controller").getAsInt());
        assertEquals(idOf(seat1, creature), bottom.getAsJsonArray("targets").get(0).getAsJsonObject().get("card").getAsInt());
    }

    @Test
    public void aFaceDownSpellIsHiddenFromTheOpponent() {
        setUp();
        final Card inHand = addCardToZone("Exalted Angel", seat2, ZoneType.Hand);
        final SpellAbility sa = inHand.getSpellAbilities().stream().filter(SpellAbility::isCastFaceDown).findFirst().orElseThrow();
        sa.setActivatingPlayer(seat2);
        final Card morph = game.getAction().moveToStack(inHand, sa);
        sa.setHostCard(morph);
        morph.turnFaceDown(true);
        push(sa, seat2);

        final JsonObject hidden = from(seat1).getAsJsonArray("stack").get(0).getAsJsonObject();
        assertTrue(isNull(hidden, "name"));
        assertTrue(isNull(hidden, "face"));
        assertTrue(hidden.get("face_down").getAsBoolean());
        assertEquals(idOf(seat1, morph), hidden.get("id").getAsInt());

        final JsonObject known = from(seat2).getAsJsonArray("stack").get(0).getAsJsonObject();
        assertEquals("Exalted Angel", known.get("name").getAsString());
    }

    @Test
    public void anEmptyStackIsAnEmptyList() {
        setUp();
        assertEquals(0, from(seat1).getAsJsonArray("stack").size());
    }

    @Test
    public void combatSaysWhoAttacksAndBlocksWhat() {
        setUp();
        final Card first = addCard("Grizzly Bears", seat1);
        final Card second = addCard("Hill Giant", seat1);
        final Card third = addCard("Raging Goblin", seat1);
        final Card walker = addCard("Jace Beleren", seat2);
        final Card blocker = addCard("Wall of Wood", seat2);
        final Card idle = addCard("Runeclaw Bear", seat2);
        final Combat combat = new Combat(seat1);
        combat.addAttacker(first, seat2);
        combat.addAttacker(second, walker);
        combat.addAttacker(third, seat2);
        combat.addBlocker(first, blocker);
        game.getPhaseHandler().setCombat(combat);

        final JsonObject line = from(seat2);

        final JsonObject atSeat = cardIn(line, "battlefield", first);
        assertEquals(2, atSeat.get("attacking_seat").getAsInt());
        assertTrue(isNull(atSeat, "attacking_card"));
        assertEquals(List.of(idOf(seat2, blocker)), ints(atSeat.getAsJsonArray("blocked_by")));
        assertEquals(0, atSeat.getAsJsonArray("blocking").size());

        final JsonObject atWalker = cardIn(line, "battlefield", second);
        assertTrue(isNull(atWalker, "attacking_seat"));
        assertEquals(idOf(seat2, walker), atWalker.get("attacking_card").getAsInt());
        assertEquals(0, atWalker.getAsJsonArray("blocked_by").size());

        final JsonObject blocking = cardIn(line, "battlefield", blocker);
        assertTrue(isNull(blocking, "attacking_seat"));
        assertEquals(List.of(idOf(seat2, first)), ints(blocking.getAsJsonArray("blocking")));

        final JsonObject bystander = cardIn(line, "battlefield", idle);
        assertTrue(isNull(bystander, "attacking_seat"));
        assertEquals(0, bystander.getAsJsonArray("blocking").size());
        assertEquals(0, bystander.getAsJsonArray("blocked_by").size());
    }

    @Test
    public void outsideCombatNothingIsAttacking() {
        setUp();
        final Card bears = addCard("Grizzly Bears", seat1);
        assertNull(game.getCombat());
        assertTrue(isNull(cardIn(from(seat1), "battlefield", bears), "attacking_seat"));
    }
}
