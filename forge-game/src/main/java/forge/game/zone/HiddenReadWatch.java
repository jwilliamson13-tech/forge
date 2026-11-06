package forge.game.zone;

import java.lang.StackWalker.StackFrame;
import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;

/**
 * The hidden-information tripwire: catches forge-ai reading a hand or library that isn't the deciding player's.
 * <p>
 * Only forge sim makes one, so {@link Game#HIDDEN_READ_WATCH} is null in default play, and in the copies the AI searches
 * on, which is why reads on a copy never count. {@code ScopedPlayerControllerAi} says who is deciding through
 * {@link #enter} and {@link #exit}, on every controller call, so a choice made while a spell resolves has a decider too.
 * <p>
 * Who asked and what they did are separate questions, answered at different times. Who asked is settled when the zone
 * hands over its cards, from the first stack frame that isn't plumbing: forge-ai is a suspect, and anything else is the
 * rules engine, so it gets the real list. What they did is settled by {@link RecordingView}: a count passes and any
 * other use is a hit. Deciding both at the point of use would miss {@code CardLists.filter(opp.getCardsIn(Hand), ...)},
 * where the frame doing the reading is a forge-game helper.
 * <p>
 * A forge-ai read with no decider open counts as a hit, marked {@link #NO_DECIDER}. It means a way into the AI that the
 * controller brackets missed, and a zero must not hide one.
 */
public final class HiddenReadWatch {
    /** Starts a signature whose read had no decider open. */
    public static final String NO_DECIDER = "no decider: ";

    private static final int SIGNATURE_FRAMES = 3;
    private static final String AI_PACKAGE = "forge.ai.";
    /** Copying a real game has to read it. Issue 25 owns what a search then sees on the copy. */
    private static final String GAME_COPIER = "forge.ai.simulation.GameCopier";
    private static final StackWalker WALKER = StackWalker.getInstance();
    /**
     * Calls that only pass cards along, so the frame above them is the one asking. Anything left out counts as the rules
     * engine. Two are left out on purpose: Game.forEachCardInGame, which Game.getCardState uses to look up a card the AI
     * already holds, and PlayerZone.getCardsPlayerCanActivate, which returns only what that player may play or look at.
     */
    private static final Set<String> PLUMBING = Set.of(
            "forge.game.zone.Zone.getCards", "forge.game.zone.Zone.get", "forge.game.zone.Zone.iterator",
            "forge.game.zone.Zone.contains", "forge.game.zone.Zone.noteRead",
            "forge.game.player.Player.getCardsIn", "forge.game.player.Player.getAllCards",
            "forge.game.player.PlayerCollection.getCardsIn",
            "forge.game.Game.getCardsIn", "forge.game.Game.getCardsIncludePhasingIn",
            "forge.game.Game.getCardsInOwnedBy", "forge.game.Game.getCardsInGame");

    private final Deque<Player> deciders = new ArrayDeque<>();
    private final Map<String, Integer> hits = new TreeMap<>();

    /** {@code player} starts a decision. Decisions nest: a spell the AI casts can ask the other seat to choose. */
    public synchronized void enter(final Player player) {
        deciders.push(player);
    }

    /** By player and not by position, because a timed-out AI thread can finish after the decision it belonged to. */
    public synchronized void exit(final Player player) {
        deciders.removeFirstOccurrence(player);
    }

    /** Every hit so far, by stack signature, in signature order. */
    public synchronized Map<String, Integer> hitsBySignature() {
        return new TreeMap<>(hits);
    }

    public synchronized int hitCount() {
        return hits.values().stream().mapToInt(Integer::intValue).sum();
    }

    private synchronized Player decider() {
        return deciders.peek();
    }

    private synchronized void hit(final String signature) {
        hits.merge(signature, 1, Integer::sum);
    }

    /** What a zone hands over: the real list, unless forge-ai is asking for cards that aren't the decider's. */
    CardCollectionView view(final Zone zone, final CardCollection cards) {
        final Player reader = decider();
        final String signature = suspect(zone, reader);
        return signature == null ? cards : new RecordingView(cards, reader, signature);
    }

    /** A read straight off the zone, with no collection to hand over, so the use is already known. */
    void directRead(final Zone zone, final CardCollectionView cards) {
        final Player reader = decider();
        final String signature = suspect(zone, reader);
        if (signature != null) {
            countRead(cards, reader, signature);
        }
    }

    /** The signature of this read if forge-ai is making it on a zone it may not see, or else null. */
    private static String suspect(final Zone zone, final Player reader) {
        if (!(zone.is(ZoneType.Hand) || zone.is(ZoneType.Library)) || zone.getPlayer() == reader) {
            return null;
        }
        final String signature = WALKER.walk(HiddenReadWatch::aiSignature);
        return signature == null || reader != null ? signature : NO_DECIDER + signature;
    }

    /** The top forge-ai frames, if forge-ai is what asked, as Class.method:line, nearest first. */
    private static String aiSignature(final Stream<StackFrame> frames) {
        final Iterator<StackFrame> stack = frames.iterator();
        StackFrame asker = null;
        while (stack.hasNext() && asker == null) {
            final StackFrame frame = stack.next();
            if (!isPlumbing(frame)) {
                asker = frame;
            }
        }
        if (asker == null || !asker.getClassName().startsWith(AI_PACKAGE) || asker.getClassName().startsWith(GAME_COPIER)) {
            return null;
        }
        final StringJoiner signature = new StringJoiner(" < ");
        signature.add(describe(asker));
        for (int found = 1; found < SIGNATURE_FRAMES && stack.hasNext();) {
            final StackFrame frame = stack.next();
            if (frame.getClassName().startsWith(AI_PACKAGE)) {
                signature.add(describe(frame));
                found++;
            }
        }
        return signature.toString();
    }

    private static boolean isPlumbing(final StackFrame frame) {
        final String type = frame.getClassName();
        return type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("forge.util.")
                || type.startsWith(HiddenReadWatch.class.getName())
                || PLUMBING.contains(type + "." + frame.getMethodName());
    }

    private static String describe(final StackFrame frame) {
        return frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
    }

    /** One hit, unless the rules let the reader see every card here. An empty zone has nothing to give away. */
    private void countRead(final CardCollectionView cards, final Player reader, final String signature) {
        for (final Card card : cards.threadSafeIterable()) {
            if (reader == null || !card.getView().canBeShownTo(reader.getView())) {
                hit(signature);
                return;
            }
        }
    }

    /**
     * A zone's cards as forge-ai gets them. Counting them is public information, and any other use is a read, counted
     * once per view. It can't extend CardCollection: FCollection copies another FCollection's list directly, so
     * {@code new CardCollection(view)} would never reach an overridden iterator.
     */
    private final class RecordingView extends AbstractCollection<Card> implements CardCollectionView {
        private final CardCollection cards;
        private final Player reader;
        private final String signature;
        private boolean counted = false;

        private RecordingView(final CardCollection cards, final Player reader, final String signature) {
            this.cards = cards;
            this.reader = reader;
            this.signature = signature;
        }

        private CardCollection read() {
            if (!counted) {
                counted = true;
                countRead(cards, reader, signature);
            }
            return cards;
        }

        @Override
        public int size() {
            return cards.size();
        }

        @Override
        public boolean isEmpty() {
            return cards.isEmpty();
        }

        @Override
        public Iterator<Card> iterator() {
            return read().iterator();
        }

        @Override
        public Card get(final int index) {
            return read().get(index);
        }

        @Override
        public Card getFirst() {
            return read().getFirst();
        }

        @Override
        public Card getLast() {
            return read().getLast();
        }

        @Override
        public int indexOf(final Object o) {
            return read().indexOf(o);
        }

        @Override
        public int lastIndexOf(final Object o) {
            return read().lastIndexOf(o);
        }

        @Override
        public boolean contains(final Object o) {
            return read().contains(o);
        }

        @Override
        public boolean containsAll(final Collection<?> c) {
            return read().containsAll(c);
        }

        @Override
        public List<Card> subList(final int fromIndex, final int toIndex) {
            return read().subList(fromIndex, toIndex);
        }

        @Override
        public Iterable<Card> threadSafeIterable() {
            return read().threadSafeIterable();
        }

        @Override
        public Card get(final Card obj) {
            return read().get(obj);
        }

        @Override
        public Stream<Card> stream() {
            return read().stream();
        }

        @Override
        public boolean anyMatch(final Predicate<? super Card> test) {
            return read().anyMatch(test);
        }

        @Override
        public boolean allMatch(final Predicate<? super Card> test) {
            return read().allMatch(test);
        }

        @Override
        public boolean equals(final Object o) {
            return read().equals(o);
        }

        @Override
        public int hashCode() {
            return read().hashCode();
        }
    }
}
