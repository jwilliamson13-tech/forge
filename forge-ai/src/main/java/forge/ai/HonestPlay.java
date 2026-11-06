package forge.ai;

import java.util.List;

import forge.game.Game;
import forge.game.RevealLog;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
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

    /**
     * The cards to pick a name from for a guess at {@code owner}'s deck. The most copies seen wins such a guess, and
     * lands in play would win every time, so a person's guess goes to the spells unless there are none.
     */
    public static CardCollection namesWorthGuessing(final Player viewer, final Player owner) {
        final CardCollection known = knownCards(viewer, owner);
        final CardCollection spells = CardLists.filter(known, CardPredicates.NON_LANDS);
        return spells.isEmpty() ? known : spells;
    }

    /** {@link #knownCards} for every opponent of {@code viewer}. */
    public static CardCollection knownOpponentCards(final Player viewer) {
        final CardCollection cards = new CardCollection();
        for (final Player opp : viewer.getOpponents()) {
            cards.addAll(knownCards(viewer, opp));
        }
        return cards;
    }

    /**
     * Cards of the opponents that {@code viewer} may play from a hidden zone. A person can only play a card they can see,
     * so these come off the notepad, where a library top the rules let them look at also goes.
     */
    public static CardCollection playableUnseen(final Player viewer) {
        final CardCollection cards = new CardCollection();
        for (final RevealLog.Entry entry : entries(viewer)) {
            final Card current = viewer.getGame().getCardState(entry.card());
            if (!current.mayPlay(viewer).isEmpty()) {
                cards.add(current);
            }
        }
        return cards;
    }

    /**
     * What {@code viewer} can see of {@code owner}'s {@code zones}: all of a public zone or one of the viewer's own, and
     * of an opponent's hidden zone only what was last seen there. Outside honest play it is the whole of each zone.
     */
    public static CardCollectionView seenIn(final Player viewer, final Player owner, final Iterable<ZoneType> zones) {
        if (!on(viewer) || owner.equals(viewer)) {
            return owner.getCardsIn(zones);
        }
        final CardCollection cards = new CardCollection();
        for (final ZoneType zone : zones) {
            if (zone.isHidden()) {
                cards.addAll(rememberedIn(viewer, owner, zone));
            } else {
                cards.addAll(owner.getCardsIn(zone));
            }
        }
        return cards;
    }

    /** {@code Game.getCardsIn}, for the seats' zones the viewer can see. */
    public static CardCollectionView seenInAnySeat(final Player viewer, final Iterable<ZoneType> zones) {
        final Game game = viewer.getGame();
        if (!on(game)) {
            return game.getCardsIn(zones);
        }
        final CardCollection cards = new CardCollection();
        for (final Player p : game.getPlayers()) {
            cards.addAll(seenIn(viewer, p, zones));
        }
        return cards;
    }

    /** {@link #seenIn} over every opponent of {@code viewer}. */
    public static CardCollectionView seenInOpponents(final Player viewer, final ZoneType zone) {
        if (!on(viewer)) {
            return viewer.getOpponents().getCardsIn(zone);
        }
        final CardCollection cards = new CardCollection();
        for (final Player opp : viewer.getOpponents()) {
            cards.addAll(seenIn(viewer, opp, List.of(zone)));
        }
        return cards;
    }

    /** True if {@code owner} has cards in {@code zones} that {@code viewer} can't see, so a list of what it can see may be short. */
    public static boolean holdsUnseen(final Player viewer, final Player owner, final Iterable<ZoneType> zones) {
        if (!on(viewer) || owner.equals(viewer)) {
            return false;
        }
        for (final ZoneType zone : zones) {
            if (zone.isHidden() && !owner.getZone(zone).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Every card {@code viewer} can name, own and opposing. For a question about the whole game. */
    public static CardCollectionView allCardsKnownTo(final Player viewer) {
        return CardCollection.combine(viewer.getAllCards(), knownOpponentCards(viewer));
    }

    /** False under honest play if whoever is deciding isn't allowed to turn this face-down card over. */
    public static boolean deciderCanNameIt(final Card c) {
        final Game game = c.getGame();
        final Player decider = game.AI_DECIDERS.current();
        return !on(game) || decider == null || canNameIt(decider, c);
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
