package forge.game.player;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Which AI seat is making a decision right now, innermost first. {@code ScopedPlayerControllerAi} keeps it, on every
 * controller call. Each game has its own, and it stays empty unless forge sim asks for that controller.
 * <p>
 * It is held per game and not per thread, because the heuristic AI thinks on an eval thread of its own.
 */
public final class DecisionStack {
    private final Deque<Player> deciders = new ArrayDeque<>();

    /** {@code player} starts a decision. Decisions nest: a spell the AI casts can ask the other seat to choose. */
    public synchronized void enter(final Player player) {
        deciders.push(player);
    }

    /** By player and not by position, because a timed-out AI thread can finish after the decision it belonged to. */
    public synchronized void exit(final Player player) {
        deciders.removeFirstOccurrence(player);
    }

    /** The innermost decider, or null with no decision open. */
    public synchronized Player current() {
        return deciders.peek();
    }
}
