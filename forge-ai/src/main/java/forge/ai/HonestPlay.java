package forge.ai;

import java.util.List;

import forge.game.Game;
import forge.game.RevealLog;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * What an AI may use about cards it can't see when {@link Game#AI_HONEST_PLAY} is on: what is public, and what it has
 * written down in {@link Game#REVEAL_LOG}. The peek fixes in forge-ai read through here, and {@code docs/peek-fixes.md}
 * in the mtg-forge-ai repo lists them.
 * <p>
 * The log is null without {@code -record} and in the copies the AI searches on. The memory is then simply empty, and the
 * AI goes by public information alone.
 */
public final class HonestPlay {
    private static final ZoneType[] PUBLIC_ZONES = {ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Stack, ZoneType.Command};

    private HonestPlay() {
    }

    public static boolean on(final Game game) {
        return game.AI_HONEST_PLAY;
    }

    public static boolean on(final Player player) {
        return on(player.getGame());
    }

    /**
     * The cards of {@code owner} on {@code viewer}'s notepad, as the objects seen then. Asking one of these where it is
     * now would be a peek, which is why {@link #rememberedIn} goes by where it was seen.
     */
    public static CardCollection remembered(final Player viewer, final Player owner) {
        final CardCollection cards = new CardCollection();
        for (final RevealLog.Entry entry : entries(viewer)) {
            if (entry.owner().equals(owner)) {
                cards.add(entry.card());
            }
        }
        return cards;
    }

    /** The cards of {@code owner} that {@code viewer} last saw in {@code zone}, wherever they have gone since. */
    public static CardCollection rememberedIn(final Player viewer, final Player owner, final ZoneType zone) {
        final CardCollection cards = new CardCollection();
        for (final RevealLog.Entry entry : entries(viewer)) {
            if (entry.owner().equals(owner) && entry.seenIn() == zone) {
                cards.add(entry.card());
            }
        }
        return cards;
    }

    /**
     * Every card of {@code owner}'s deck that {@code viewer} has seen and could name: its public cards, then its
     * written-down ones. Face-down cards and tokens say nothing about the deck list, so they aren't here.
     */
    public static CardCollection knownCards(final Player viewer, final Player owner) {
        final CardCollection cards = new CardCollection();
        for (final Card c : owner.getCardsIn(PUBLIC_ZONES)) {
            if (!c.isToken() && !c.isImmutable() && canNameIt(viewer, c)) {
                cards.add(c);
            }
        }
        cards.addAll(remembered(viewer, owner));
        return cards;
    }

    /** {@link #knownCards} for every opponent of {@code viewer}. */
    public static CardCollection knownOpponentCards(final Player viewer) {
        final CardCollection cards = new CardCollection();
        for (final Player opp : viewer.getOpponents()) {
            cards.addAll(knownCards(viewer, opp));
        }
        return cards;
    }

    /** False for a face-down card this viewer isn't allowed to turn over. */
    public static boolean canNameIt(final Player viewer, final Card c) {
        return !c.isFaceDown() || c.getView().canFaceDownBeShownTo(viewer.getView());
    }

    /**
     * {@code owner}'s hand as far as whoever is deciding knows it. Code that takes a player and asks what that player
     * could do can't tell an AI's own payment from a guess at an opponent's, so this goes by the open decision. With no
     * decision open it hands over the real hand, and the tripwire counts that as a read with no decider.
     */
    public static CardCollectionView handAsKnown(final Player owner) {
        final Game game = owner.getGame();
        final Player decider = game.AI_DECIDERS.current();
        if (!on(game) || decider == null || decider.equals(owner)) {
            return owner.getCardsIn(ZoneType.Hand);
        }
        return rememberedIn(decider, owner, ZoneType.Hand);
    }

    private static List<RevealLog.Entry> entries(final Player viewer) {
        final RevealLog log = viewer.getGame().REVEAL_LOG;
        return log == null ? List.of() : log.knownTo(viewer);
    }
}
