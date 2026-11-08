package forge.ai.simulation;

import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.player.Player;

/**
 * What the simulation search scores a game state with: Forge's two integers, from the side of the player deciding.
 * {@link SpellAbilityPicker} and {@link GameSimulator} get every score they compare from here.
 * <p>
 * There are two kinds. {@link #FORGE}, the default, is Forge's own {@link GameStateEvaluator}, unchanged, so a player
 * nobody set a scorer for plays as upstream does. A {@link TwoLookScorer} wraps a {@link WinProbabilityEvaluator}.
 * Which one a player uses is set on its {@link LobbyPlayerAi}, so the copies the search makes, which share the lobby
 * player, score the same way as the real game.
 */
@FunctionalInterface
public interface SearchScorer {
    /** The score of {@code game} for {@code aiPlayer}: {@code value} for comparing lines, {@code availableValue} for the veto. */
    Score score(Game game, Player aiPlayer);

    /** Forge's hand-tuned evaluator, exactly as the search used it before this interface existed. */
    SearchScorer FORGE = new SearchScorer() {
        @Override
        public Score score(Game game, Player aiPlayer) {
            return new GameStateEvaluator().getScoreForGameState(game, aiPlayer);
        }

        @Override
        public String toString() {
            return "forge";
        }
    };

    /** The scorer for the decisions of {@code aiPlayer}: the one set on its lobby player, or {@link #FORGE}. */
    static SearchScorer of(Player aiPlayer) {
        if (aiPlayer.getLobbyPlayer() instanceof LobbyPlayerAi lobbyPlayer && lobbyPlayer.getSearchScorer() != null) {
            return lobbyPlayer.getSearchScorer();
        }
        return FORGE;
    }
}
