package forge.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.RevealLog;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardState;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;

/**
 * One snapshot line of a game record: what one side can see at a moment, as docs/game-record.md in the Python repo
 * lists it. Everything is written in a stable order, because the recorder drops a line that equals the last one.
 */
final class SnapshotLine {
    private final Game game;
    private final Player me;
    private final Player opp;
    private final Combat combat;
    private final RecordIds ids;
    private final List<RevealLog.Entry> revealedEntries;
    private final List<Card> battlefield = new ArrayList<>();
    /** Each permanent's record id, by Forge's id. What attachments and combat hold can be an older copy of the card. */
    private final Map<Integer, Integer> battlefieldIds = new HashMap<>();

    private SnapshotLine(final Game game, final Player side, final RecordIds ids, final List<RevealLog.Entry> revealed) {
        this.game = game;
        this.me = side;
        this.ids = ids;
        this.revealedEntries = revealed;
        this.opp = game.getRegisteredPlayers().stream().filter(p -> !p.equals(side)).findFirst().orElseThrow();
        this.combat = game.getCombat();
        for (final Player player : game.getRegisteredPlayers()) {
            // leaves out phased-out cards, which the rules treat as not there
            battlefield.addAll(player.getCardsIn(ZoneType.Battlefield));
        }
        for (final Card card : battlefield) {
            battlefieldIds.put(card.getId(), ids.of(card));
        }
        // the two sides' lines would otherwise match up by position, and one of them can name a card the other hides
        battlefield.sort(Comparator.comparing(card -> battlefieldIds.get(card.getId())));
    }

    static JsonObject of(final Game game, final Player side, final RecordIds ids) {
        return of(game, side, ids, game.REVEAL_LOG.knownTo(side));
    }

    /**
     * The line with {@code opp_revealed} taken from {@code revealed} instead of the game's own log. The ONNX evaluator
     * scores the search's copies, which have no log, with what the real game's log held at the root of the search.
     */
    static JsonObject of(final Game game, final Player side, final RecordIds ids, final List<RevealLog.Entry> revealed) {
        return new SnapshotLine(game, side, ids, revealed).build();
    }

    private JsonObject build() {
        final JsonObject line = new JsonObject();
        line.addProperty("side", seat(me));
        line.add("scoreboard", scoreboard());
        line.add("zones", zones());
        line.add("stack", stack());
        return line;
    }

    /** Seats are registration order, the order of -d, which game.getPlayers() doesn't keep. */
    private int seat(final Player player) {
        return game.getRegisteredPlayers().indexOf(player) + 1;
    }

    private JsonObject scoreboard() {
        final PhaseHandler phases = game.getPhaseHandler();
        final JsonObject board = new JsonObject();
        board.addProperty("me_life", me.getLife());
        board.addProperty("opp_life", opp.getLife());
        board.addProperty("me_poison", me.getPoisonCounters());
        board.addProperty("opp_poison", opp.getPoisonCounters());
        board.addProperty("me_library", me.getZone(ZoneType.Library).size());
        board.addProperty("opp_library", opp.getZone(ZoneType.Library).size());
        board.addProperty("opp_hand", opp.getZone(ZoneType.Hand).size());
        board.addProperty("turn", phases.getTurn());
        board.addProperty("me_active", me.equals(phases.getPlayerTurn()));
        board.addProperty("me_on_play", me.equals(game.getStartingPlayer()));
        // schema version 2: the game-wide markers and each player's speed, which Forge keeps off the board as effects
        board.addProperty("me_initiative", me.equals(game.getHasInitiative()));
        board.addProperty("opp_initiative", opp.equals(game.getHasInitiative()));
        board.addProperty("me_monarch", me.equals(game.getMonarch()));
        board.addProperty("opp_monarch", opp.equals(game.getMonarch()));
        board.addProperty("me_speed", me.getSpeed());
        board.addProperty("opp_speed", opp.getSpeed());
        return board;
    }

    private JsonObject zones() {
        final JsonObject zones = new JsonObject();
        zones.add("battlefield", cards(battlefield));
        zones.add("me_hand", cards(me.getCardsIn(ZoneType.Hand)));
        zones.add("me_graveyard", cards(me.getCardsIn(ZoneType.Graveyard)));
        zones.add("opp_graveyard", cards(opp.getCardsIn(ZoneType.Graveyard)));
        zones.add("me_exile", cards(me.getCardsIn(ZoneType.Exile)));
        zones.add("opp_exile", cards(opp.getCardsIn(ZoneType.Exile)));
        zones.add("opp_revealed", revealed());
        return zones;
    }

    private JsonArray cards(final Iterable<Card> cards) {
        final JsonArray list = new JsonArray();
        for (final Card card : cards) {
            list.add(card(card));
        }
        return list;
    }

    private JsonObject card(final Card card) {
        final JsonObject out = new JsonObject();
        out.addProperty("id", ids.of(card));
        addIdentity(out, card);
        // the face-down state is a creature, but a face-down card in exile isn't a 2/2 of anything
        final boolean hasPower = card.isCreature() && (card.isInPlay() || !card.isFaceDown());
        out.addProperty("power", hasPower ? card.getNetPower() : null);
        out.addProperty("toughness", hasPower ? card.getNetToughness() : null);
        out.addProperty("tapped", card.isTapped());
        out.add("counters", counters(card));
        // sickness starts true and only clears on the battlefield, so a card in hand would say true
        out.addProperty("summoning_sick", card.isInPlay() && card.isSick());
        final GameEntity host = card.getEntityAttachedTo();
        out.addProperty("attached_to", host instanceof Card c ? battlefieldIds.get(c.getId()) : null);
        out.addProperty("attached_to_seat", host instanceof Player p ? seat(p) : null);
        out.addProperty("controller", seat(card.getController()));
        out.addProperty("face_down", card.isFaceDown());
        out.addProperty("damage", card.getDamage());
        addCombat(out, card);
        return out;
    }

    /** Name and face as this side may see them. Hidden means null, and the face-down state is never written as a face. */
    private void addIdentity(final JsonObject out, final Card card) {
        if (card.isFaceDown() && !card.getView().canFaceDownBeShownTo(me.getView())) {
            out.addProperty("name", (String) null);
            out.addProperty("face", (String) null);
            return;
        }
        final CardState seen = card.isFaceDown() ? card.getState(CardStateName.Original) : card.getCurrentState();
        final String name = card.getName(seen);
        // an empty Room's state has no name of its own
        out.addProperty("name", name.isEmpty() ? card.getName(card.getState(CardStateName.Original)) : name);
        out.addProperty("face", seen.getStateName().name());
    }

    /** Sorted, because the counters come from a hash set. */
    private static JsonObject counters(final Card card) {
        final Map<String, Integer> sorted = new TreeMap<>();
        for (final Multiset.Entry<CounterType> entry : card.getCounters().entrySet()) {
            sorted.put(entry.getElement().toString(), entry.getCount());
        }
        final JsonObject out = new JsonObject();
        sorted.forEach(out::addProperty);
        return out;
    }

    private void addCombat(final JsonObject out, final Card card) {
        final boolean attacking = combat != null && combat.isAttacking(card);
        final GameEntity defender = attacking ? combat.getDefenderByAttacker(card) : null;
        out.addProperty("attacking_seat", defender instanceof Player p ? seat(p) : null);
        out.addProperty("attacking_card", defender instanceof Card c ? battlefieldIds.get(c.getId()) : null);
        final boolean blocking = combat != null && combat.isBlocking(card);
        out.add("blocking", onBattlefield(blocking ? combat.getAttackersBlockedBy(card) : new CardCollection()));
        out.add("blocked_by", onBattlefield(attacking ? combat.getBlockers(card) : new CardCollection()));
    }

    private JsonArray onBattlefield(final CardCollection cards) {
        final JsonArray list = new JsonArray();
        cards.stream().map(card -> battlefieldIds.get(card.getId())).filter(Objects::nonNull).sorted().forEach(list::add);
        return list;
    }

    /**
     * A logged card is written as it was noted down, with no id and no live state. Either one would tie it to the card it
     * became, such as the tapped face-down creature it was played as. The log comes in Forge's id order, which is deck
     * order, so the lines go by name instead.
     */
    private JsonArray revealed() {
        final JsonArray list = new JsonArray();
        final List<RevealLog.Entry> entries = new ArrayList<>(revealedEntries);
        entries.sort(Comparator.comparing(RevealLog.Entry::name).thenComparing(RevealLog.Entry::face));
        for (final RevealLog.Entry entry : entries) {
            final JsonObject out = new JsonObject();
            out.addProperty("id", (Integer) null);
            out.addProperty("name", entry.name());
            out.addProperty("face", entry.face().name());
            out.addProperty("power", entry.power());
            out.addProperty("toughness", entry.toughness());
            out.addProperty("tapped", false);
            out.add("counters", new JsonObject());
            out.addProperty("summoning_sick", false);
            out.addProperty("attached_to", (Integer) null);
            out.addProperty("attached_to_seat", (Integer) null);
            out.addProperty("controller", seat(entry.owner()));
            out.addProperty("face_down", false);
            out.addProperty("damage", 0);
            out.addProperty("attacking_seat", (Integer) null);
            out.addProperty("attacking_card", (Integer) null);
            out.add("blocking", new JsonArray());
            out.add("blocked_by", new JsonArray());
            list.add(out);
        }
        return list;
    }

    /** Top of the stack first, which is the order it resolves in. */
    private JsonArray stack() {
        final JsonArray list = new JsonArray();
        for (final SpellAbilityStackInstance item : game.getStack()) {
            final SpellAbility ability = item.getSpellAbility();
            final Card source = item.getSourceCard();
            final JsonObject out = new JsonObject();
            out.addProperty("id", ids.of(source));
            addIdentity(out, source);
            out.addProperty("face_down", source.isFaceDown());
            out.addProperty("controller", seat(ability.getActivatingPlayer()));
            out.addProperty("kind", item.isSpell() ? "spell" : "ability");
            out.add("targets", targets(ability));
            list.add(out);
        }
        return list;
    }

    private JsonArray targets(final SpellAbility ability) {
        final JsonArray list = new JsonArray();
        for (final TargetChoices choices : ability.getAllTargetChoices()) {
            for (final GameObject target : choices) {
                final JsonObject out = new JsonObject();
                final Card card = target instanceof SpellAbility spell ? spell.getHostCard() : target instanceof Card c ? c : null;
                out.addProperty("card", card == null ? null : ids.of(card));
                out.addProperty("seat", target instanceof Player p ? seat(p) : null);
                list.add(out);
            }
        }
        return list;
    }
}
