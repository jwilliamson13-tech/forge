package forge.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.view.OnnxBundle.CardFeatures;

/**
 * Turns one snapshot line of a game record into the inputs of tensor contract version 1.
 * <p>
 * It reads the line {@link SnapshotLine} writes, the same line the recorder writes for training, and builds each
 * input the way the Python repo's {@code setmodel/dataset.py} builds it from the Parquet tables. So what the model
 * scores in a game is what it trained on: the snapshot is the same code, and only this step is Java's.
 * <ul>
 *     <li>Cards come zone by zone in the contract's zone order, then the stack, top first. The model pools each zone
 *     with a mean, so the order only moves rounding.</li>
 *     <li>A card with no name is hidden: no lookup, all masks 0, {@code hidden} 1.</li>
 *     <li>A named card the table lacks also gets all masks 0, with {@code hidden} 0. Training dropped every snapshot
 *     holding one, so the model has never seen this case. The encoder reports it to {@code missing} and goes on.</li>
 * </ul>
 */
final class SnapshotEncoder {
    private static final int S = OnnxBundle.SCOREBOARD_FIELDS.size();
    private static final int L = OnnxBundle.LIVE_FIELDS.size();
    private static final int STACK_ZONE = OnnxBundle.ZONES.indexOf("stack");
    private static final Map<String, Integer> NAMED_COUNTERS = Map.of(
            "P1P1", live("counters_p1p1"), "M1M1", live("counters_m1m1"), "LOYALTY", live("counters_loyalty"));
    private static final int OTHER_COUNTERS = live("counters_other");
    private static final int POWER = live("power");
    private static final int TOUGHNESS = live("toughness");
    private static final int IS_CREATURE = live("is_creature");
    private static final int TAPPED = live("tapped");
    private static final int SUMMONING_SICK = live("summoning_sick");
    private static final int MINE = live("mine");
    private static final int FACE_DOWN = live("face_down");
    private static final int HIDDEN = live("hidden");
    private static final int DAMAGE = live("damage");
    private static final int ATTACKING = live("attacking");
    private static final int BLOCKING = live("blocking");
    private static final int BLOCKED = live("blocked");
    private static final int ATTACHED = live("attached");
    private static final int STACK_ABILITY = live("stack_ability");

    /** The contract's inputs for one state, flat and row-major, with {@code cards} as N. */
    static final class Inputs {
        final int cards;
        final float[] scoreboard;
        final long[] tokenIds;
        final float[] tokenMask;
        final float[] numbers;
        final float[] numberMask;
        final float[] live;
        final long[] zone;

        private Inputs(final int cards, final int a, final int t, final int k) {
            this.cards = cards;
            scoreboard = new float[S];
            tokenIds = new long[cards * a * t];
            tokenMask = new float[cards * a * t];
            numbers = new float[cards * a * k];
            numberMask = new float[cards * a * k];
            live = new float[cards * L];
            zone = new long[cards];
        }
    }

    private final OnnxBundle bundle;
    private final BiConsumer<String, String> missing;

    /** {@code missing} hears the name and face of every named card the table lacks, each time one is encoded. */
    SnapshotEncoder(final OnnxBundle bundle, final BiConsumer<String, String> missing) {
        this.bundle = bundle;
        this.missing = missing;
    }

    private static int live(final String field) {
        return OnnxBundle.LIVE_FIELDS.indexOf(field);
    }

    Inputs encode(final JsonObject line) {
        final int side = line.get("side").getAsInt();
        final List<JsonObject> cards = new ArrayList<>();
        final List<Integer> zones = new ArrayList<>();
        final JsonObject recordZones = line.getAsJsonObject("zones");
        for (int z = 0; z < OnnxBundle.ZONES.size(); z++) {
            if (z == STACK_ZONE) {
                continue;
            }
            for (final JsonElement card : recordZones.getAsJsonArray(OnnxBundle.ZONES.get(z))) {
                cards.add(card.getAsJsonObject());
                zones.add(z);
            }
        }
        for (final JsonElement item : line.getAsJsonArray("stack")) {
            cards.add(item.getAsJsonObject());
            zones.add(STACK_ZONE);
        }

        final int a = bundle.abilities(), t = bundle.tokens(), k = bundle.keys();
        final Inputs in = new Inputs(cards.size(), a, t, k);
        final JsonObject board = line.getAsJsonObject("scoreboard");
        for (int i = 0; i < S; i++) {
            in.scoreboard[i] = number(board.get(OnnxBundle.SCOREBOARD_FIELDS.get(i)));
        }
        for (int n = 0; n < cards.size(); n++) {
            final JsonObject card = cards.get(n);
            final int zone = zones.get(n);
            in.zone[n] = zone;
            addFeatures(in, n, card, a, t, k);
            if (zone == STACK_ZONE) {
                stackLive(in.live, n * L, card, side);
            } else {
                cardLive(in.live, n * L, card, side);
            }
        }
        return in;
    }

    private void addFeatures(final Inputs in, final int n, final JsonObject card, final int a, final int t, final int k) {
        if (isNull(card, "name")) {
            return;
        }
        final String name = card.get("name").getAsString();
        final String face = card.get("face").getAsString();
        final CardFeatures features = bundle.lookup(name, face);
        if (features == null) {
            missing.accept(name, face);
            return;
        }
        for (int row = 0; row < features.tokens().length; row++) {
            final int base = (n * a + row) * t;
            final int[] ids = features.tokens()[row];
            for (int j = 0; j < ids.length; j++) {
                in.tokenIds[base + j] = ids[j];
                in.tokenMask[base + j] = 1f;
            }
        }
        for (int row = 0; row < features.keys().length; row++) {
            final int base = (n * a + row) * k;
            final int[] keys = features.keys()[row];
            final float[] values = features.values()[row];
            for (int j = 0; j < keys.length; j++) {
                in.numbers[base + keys[j]] = values[j];
                in.numberMask[base + keys[j]] = 1f;
            }
        }
    }

    /** {@code _card_live} in setmodel/dataset.py. A logged {@code opp_revealed} card goes through here too. */
    private static void cardLive(final float[] live, final int at, final JsonObject card, final int side) {
        live[at + POWER] = number(card.get("power"));
        live[at + TOUGHNESS] = number(card.get("toughness"));
        live[at + IS_CREATURE] = flag(!isNull(card, "power"));
        live[at + TAPPED] = number(card.get("tapped"));
        live[at + SUMMONING_SICK] = number(card.get("summoning_sick"));
        live[at + MINE] = flag(card.get("controller").getAsInt() == side);
        live[at + FACE_DOWN] = number(card.get("face_down"));
        live[at + HIDDEN] = flag(isNull(card, "name"));
        live[at + DAMAGE] = number(card.get("damage"));
        live[at + ATTACKING] = flag(!isNull(card, "attacking_seat") || !isNull(card, "attacking_card"));
        live[at + BLOCKING] = flag(hasItems(card, "blocking"));
        live[at + BLOCKED] = flag(hasItems(card, "blocked_by"));
        live[at + ATTACHED] = flag(!isNull(card, "attached_to") || !isNull(card, "attached_to_seat"));
        for (final Map.Entry<String, JsonElement> counter : card.getAsJsonObject("counters").entrySet()) {
            final int field = NAMED_COUNTERS.getOrDefault(counter.getKey(), OTHER_COUNTERS);
            live[at + field] += counter.getValue().getAsFloat();
        }
    }

    /** {@code _stack_live} in setmodel/dataset.py: a stack item sets only these four. */
    private static void stackLive(final float[] live, final int at, final JsonObject item, final int side) {
        live[at + MINE] = flag(item.get("controller").getAsInt() == side);
        live[at + FACE_DOWN] = number(item.get("face_down"));
        live[at + HIDDEN] = flag(isNull(item, "name"));
        live[at + STACK_ABILITY] = flag("ability".equals(item.get("kind").getAsString()));
    }

    private static boolean isNull(final JsonObject object, final String field) {
        final JsonElement value = object.get(field);
        return value == null || value.isJsonNull();
    }

    private static boolean hasItems(final JsonObject object, final String field) {
        final JsonElement value = object.get(field);
        return value != null && value.isJsonArray() && !((JsonArray) value).isEmpty();
    }

    /** A record value as the model reads it: a boolean is 0 or 1, and null is 0. */
    private static float number(final JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return 0f;
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return flag(value.getAsBoolean());
        }
        return value.getAsFloat();
    }

    private static float flag(final boolean on) {
        return on ? 1f : 0f;
    }
}
