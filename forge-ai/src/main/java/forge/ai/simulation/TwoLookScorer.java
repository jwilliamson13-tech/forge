package forge.ai.simulation;

import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

import java.util.Objects;
import java.util.Random;

/**
 * Fills Forge's two-integer {@link Score} from a {@link WinProbabilityEvaluator}, the same way for every evaluator.
 * <ol>
 *     <li>A finished game keeps Forge's bands: a win is {@code Integer.MAX_VALUE - turn * 1000}, a loss
 *     {@code Integer.MIN_VALUE + 1 + turn * 1000}. The evaluator isn't asked.</li>
 *     <li>{@code value} is the evaluator's probability for the real state.</li>
 *     <li>{@code availableValue} is its probability for a copy of the state with the AI's own summoning-sick creatures
 *     removed. That is what makes the search hold a creature until after combat: cast before combat, the creature
 *     is missing from the second look while its card has left the hand, so the play doesn't raise the available
 *     value and {@link SpellAbilityPicker} vetoes it.</li>
 * </ol>
 * Both probabilities map linearly onto {@link #LIVE_SCORE_MIN} to {@link #LIVE_SCORE_MAX}, which sits well inside the
 * band between the worst win and the best loss, so no output can pass for either, nor for the
 * {@code Integer.MIN_VALUE} of a failed simulation.
 * <p>
 * Forge's own evaluator removes sick creatures only before the second main phase, and so does this. From then on
 * a creature cast now can't attack this turn either way, and removing it would veto every creature spell.
 * When there is nothing to remove, the two looks would be of the same state, so the second isn't taken and
 * {@code availableValue} equals {@code value}.
 * <p>
 * The copy for the second look counts as no search step, like the copy {@link GameStateEvaluator} makes to look at
 * the coming combat. The removal is not a zone change: it runs no triggers and no state-based actions. It takes the
 * creatures out of the battlefield and out of combat, then applies static abilities again, so a lord that was removed
 * no longer pumps what is left. A removed creature is in no zone at all, as a card a spell remembers for its last known
 * information can be, so a later copy of the derived state skips it where another card still remembers it.
 * <p>
 * The second look draws nothing from the game's stream. Whatever the copy or the evaluator draws there comes from a
 * generator of its own, seeded the same way every time. An evaluator that calls into Forge's AI, as the passthrough
 * does through Forge's combat look, would otherwise move {@link MyRandom} on, and every game after it in the JVM would
 * play out differently from the stock search, which takes no second look.
 */
public final class TwoLookScorer implements SearchScorer {
    /** The score for a probability of 0. A probability of 0.5 scores 0. */
    public static final int LIVE_SCORE_MIN = -1_000_000_000;
    /** The score for a probability of 1. */
    public static final int LIVE_SCORE_MAX = 1_000_000_000;

    private final WinProbabilityEvaluator evaluator;

    public TwoLookScorer(WinProbabilityEvaluator evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    public WinProbabilityEvaluator getEvaluator() {
        return evaluator;
    }

    @Override
    public Score score(Game game, Player aiPlayer) {
        if (!aiPlayer.isInGame()) {
            return GameStateEvaluator.getTerminalScore(game, aiPlayer);
        }
        int value = toScore(evaluator.winProbability(game, aiPlayer));
        return new Score(value, availableValue(game, aiPlayer, value));
    }

    private int availableValue(Game game, Player aiPlayer, int value) {
        if (!removesSickCreatures(game) || sickCreatures(aiPlayer).isEmpty()) {
            return value;
        }
        Random gameStream = MyRandom.getRandom();
        MyRandom.setRandom(new Random(SECOND_LOOK_SEED));
        try {
            GameCopier copier = new GameCopier(game);
            Game derived = copier.makeCopy(null, aiPlayer);
            Player derivedAi = copier.find(aiPlayer);
            removeSickCreatures(derived, derivedAi);
            return toScore(evaluator.winProbability(derived, derivedAi));
        } finally {
            MyRandom.setRandom(gameStream);
        }
    }

    /** The seed of the second look's own generator. Any fixed value keeps a run replaying. */
    private static final long SECOND_LOOK_SEED = 0x2100CL;

    /** The probability mapped linearly onto the live range. Out-of-range values are clamped; NaN is an error. */
    public static int toScore(double probability) {
        if (Double.isNaN(probability)) {
            throw new IllegalArgumentException("a win probability evaluator returned NaN");
        }
        double p = Math.max(0.0, Math.min(1.0, probability));
        return (int) (LIVE_SCORE_MIN + Math.round(p * ((long) LIVE_SCORE_MAX - LIVE_SCORE_MIN)));
    }

    /** The inverse of {@link #toScore}, for a score in the live range. */
    public static double toProbability(int score) {
        return (score - (double) LIVE_SCORE_MIN) / ((long) LIVE_SCORE_MAX - LIVE_SCORE_MIN);
    }

    /** Forge's rule for when sick creatures don't count as available: any phase before the second main phase. */
    static boolean removesSickCreatures(Game game) {
        PhaseType phase = game.getPhaseHandler().getPhase();
        return phase != null && phase.isBefore(PhaseType.MAIN2);
    }

    private static CardCollection sickCreatures(Player player) {
        return CardLists.filter(player.getCardsIn(ZoneType.Battlefield), Card::isSick);
    }

    /** Takes {@code aiPlayer}'s summoning-sick creatures out of {@code game}, which must be a copy. */
    static void removeSickCreatures(Game game, Player aiPlayer) {
        if (!game.isSimulationCopy()) {
            throw new IllegalStateException("the second look removes creatures from a copy, never from the real game");
        }
        Combat combat = game.getCombat();
        for (Card c : sickCreatures(aiPlayer)) {
            if (combat != null) {
                combat.removeFromCombat(c);
            }
            Zone zone = c.getZone();
            zone.remove(c);
            // no zone, not the None zone: GameCopier skips a remembered card with no zone, and would fail on any other
            c.setZone(null);
        }
        game.getAction().checkStaticAbilities(false);
    }
}
