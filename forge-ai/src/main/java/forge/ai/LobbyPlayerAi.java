package forge.ai;

import java.util.Set;

import forge.LobbyPlayer;
import forge.ai.simulation.SearchScorer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import org.tinylog.Logger;

public class LobbyPlayerAi extends LobbyPlayer implements IGameEntitiesFactory {

    private String aiProfile = "";
    private boolean rotateProfileEachGame;
    private AIOption option;
    private boolean scopedDecisions = false;
    private SearchScorer searchScorer;

    public LobbyPlayerAi(String name, Set<AIOption> options) {
        super(name);
        if (options != null && !options.isEmpty()) {
            option = options.iterator().next();
        }
    }

    public void setAiProfile(String profileName) {
        Logger.debug("[AI Preferences] " + name + " using profile " + profileName);
        aiProfile = profileName;
    }
    public String getAiProfile() {
        return aiProfile;
    }

    public void setRotateProfileEachGame(boolean rotateProfileEachGame) {
        this.rotateProfileEachGame = rotateProfileEachGame;
    }

    /** Set by forge sim before the game exists, because a game builds its players in its constructor. */
    public void setScopedDecisions(boolean scopedDecisions) {
        this.scopedDecisions = scopedDecisions;
    }

    /** What the simulation search of this player scores states with, or null for Forge's own evaluator. */
    public SearchScorer getSearchScorer() {
        return searchScorer;
    }

    /**
     * Set before the game exists, like the simulation mode. The search's copies of the game share this lobby player,
     * so the AIs in them score with the same scorer as the real one.
     */
    public void setSearchScorer(SearchScorer searchScorer) {
        this.searchScorer = searchScorer;
    }

    private PlayerControllerAi createControllerFor(Player ai) {
        PlayerControllerAi result = scopedDecisions ? new ScopedPlayerControllerAi(ai.getGame(), ai, this)
                : new PlayerControllerAi(ai.getGame(), ai, this);
        result.getAi().setUseSimulation(option);
        return result;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return createControllerFor(slave);
    }

    @Override
    public Player createIngamePlayer(Game game, final int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(createControllerFor(ai));

        if (rotateProfileEachGame) {
            setAiProfile(AiProfileUtil.getRandomProfile());
        }
        return ai;
    }

    @Override
    public void hear(LobbyPlayer player, String message) { /* Local AI is deaf. */ }
}