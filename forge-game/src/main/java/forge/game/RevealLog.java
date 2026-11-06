package forge.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.player.Player;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * What each player has been shown of the opponent's hidden cards, kept the way a person keeps it on a notepad.
 * <p>
 * A card goes on the moment it is revealed to the player, or the player is allowed to look at it. It stays through
 * moves the player can't see, such as a shuffle or a face-down exile, because dropping it there would itself tell the
 * player something. It leaves when it shows up where the player can see it. The player's own cards never go on.
 * <p>
 * This is separate from {@code AiCardMemory.REVEALED_CARDS}, which clears at the AI's own end step and holds own and
 * library cards too. Only forge sim makes a log, so {@link Game#REVEAL_LOG} is null in default play, and in the copies
 * the AI searches on.
 */
public final class RevealLog {
    /**
     * One card as it was written down. The values are from the moment it was revealed, and don't follow the card.
     * {@code card} is the object seen then, so asking it where the card is now would be a peek. {@code seenIn} is where
     * it was the last time it was shown, which is the most a notepad can say.
     */
    public record Entry(int id, Player owner, String name, CardStateName face, Integer power, Integer toughness, ZoneType seenIn, Card card) {
        private static Entry of(final Card card) {
            // a face-down card can only be here because its real side was shown
            final CardState seen = card.isFaceDown() ? card.getState(CardStateName.Original) : card.getCurrentState();
            final boolean creature = seen.getType().isCreature();
            return new Entry(card.getId(), card.getOwner(), card.getName(seen), seen.getStateName(),
                    creature ? seen.getBasePower() : null, creature ? seen.getBaseToughness() : null,
                    card.getZone() == null ? null : card.getZone().getZoneType(), card);
        }
    }

    private final Game game;
    private final Map<Player, TreeMap<Integer, Entry>> byViewer = new HashMap<>();

    public RevealLog(final Game game) {
        this.game = game;
    }

    /** The rules showed {@code card} to {@code viewer}. Cards the viewer already sees or owns are skipped. */
    public synchronized void revealed(final Player viewer, final Card card) {
        final Card current = game.getCardState(card);
        if (!current.getOwner().equals(viewer) && !isShowing(current, viewer)) {
            entries(viewer).put(current.getId(), Entry.of(current));
        }
    }

    /** What {@code viewer} has on the notepad right now, by card id. */
    public synchronized List<Entry> knownTo(final Player viewer) {
        addStandingLooks(viewer);
        final TreeMap<Integer, Entry> entries = entries(viewer);
        entries.values().removeIf(entry -> isShowing(game.getCardState(entry.card()), viewer));
        return new ArrayList<>(entries.values());
    }

    private TreeMap<Integer, Entry> entries(final Player viewer) {
        return byViewer.computeIfAbsent(viewer, p -> new TreeMap<>());
    }

    /** A card a static ability lets the player look at, such as a library top played face up, never gets a reveal call. */
    private void addStandingLooks(final Player viewer) {
        for (final Player owner : game.getRegisteredPlayers()) {
            if (owner.equals(viewer)) {
                continue;
            }
            for (final ZoneType zone : List.of(ZoneType.Hand, ZoneType.Library)) {
                for (final Card card : owner.getCardsIn(zone)) {
                    if (card.mayPlayerLook(viewer)) {
                        entries(viewer).put(card.getId(), Entry.of(card));
                    }
                }
            }
        }
    }

    /** True if the viewer can see this card's identity where it is now. */
    private static boolean isShowing(final Card card, final Player viewer) {
        final Zone zone = card.getZone();
        return zone != null && !zone.getZoneType().isHidden()
                && (!card.isFaceDown() || card.getView().canFaceDownBeShownTo(viewer.getView()));
    }
}
