package forge.view;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import forge.game.card.Card;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * The ids one side's snapshots call cards by, so that a game record never holds {@code Card.getId()}.
 * <p>
 * Forge numbers cards in deck order before the shuffle, so the same number is the same card in every game of a batch,
 * and on both sides of one file. That named face-down cards. An id here is the hash of the game, the side and how many
 * ids this side has handed out, so it says nothing but when this side first saw the object. The seed is in the record,
 * which is why the card's own number can't go into the hash: anyone could try all of them.
 * <p>
 * A card that changes zone is a new object under the rules, so it gets a new id. Forge gives a card a new timestamp for
 * every move but one, to the stack, so the zone is part of the key too. This reads no {@code MyRandom}.
 */
final class RecordIds {
    private record Key(int card, long timestamp, ZoneType zone) { }

    private final String stream;
    private final Map<Key, Integer> ids = new HashMap<>();
    private final Set<Integer> taken = new HashSet<>();
    private int handedOut = 0;

    RecordIds(final long seed, final int gameNumber, final int side) {
        stream = seed + ":" + gameNumber + ":" + side + ":";
    }

    /** This side's id for the object {@code card} is right now. */
    int of(final Card card) {
        final Zone zone = card.getZone();
        final Key key = new Key(card.getId(), card.getGameTimestamp(), zone == null ? null : zone.getZoneType());
        Integer id = ids.get(key);
        if (id == null) {
            id = next();
            ids.put(key, id);
        }
        return id;
    }

    private int next() {
        int id;
        do {
            id = hash(stream + handedOut++);
        } while (!taken.add(id));
        return id;
    }

    /** Positive, because the schema's ids fit Java's int and the sign would add nothing. */
    private static int hash(final String text) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getInt() & Integer.MAX_VALUE;
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
    }
}
