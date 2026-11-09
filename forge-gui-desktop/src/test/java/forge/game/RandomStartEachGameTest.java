package forge.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.event.GameEventFlipCoin;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.util.MyRandom;

/** forge sim -firstplayer random: every game of a match picks its first player as the first game does. */
public class RandomStartEachGameTest extends AITest {
    private static final int GAMES = 16;

    /** What each game of one match did: the seat that took the first turn, and the starting coin flips it fired. */
    private record Played(List<Integer> firstSeats, List<Integer> startingFlips) {}

    /**
     * Plays one match of {@link #GAMES} games from {@code seed}. In each game the player who goes first concedes on its
     * first turn, so under stock rules the same seat loses and goes first every game after the first.
     */
    private Played play(final boolean randomStart, final long seed) {
        MyRandom.setRandom(new Random(seed));
        final Deck deck = new Deck("Bears");
        deck.getMain().add("Forest", 24);
        deck.getMain().add("Grizzly Bears", 36);
        final List<RegisteredPlayer> players = Lists.newArrayList();
        players.add(new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi("p1", null)));
        players.add(new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi("p2", null)));
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setRandomStartEachGame(randomStart);
        final Match match = new Match(rules, players, "Test");

        final List<Integer> firstSeats = new ArrayList<>();
        final List<Integer> startingFlips = new ArrayList<>();
        for (int i = 0; i < GAMES; i++) {
            final Game game = match.createGame();
            final int[] flips = {0};
            game.subscribeToEvents(new Object() {
                @Subscribe
                public void onFlip(final GameEventFlipCoin event) {
                    if (event.startingToss()) {
                        flips[0]++;
                    }
                }
            });
            match.startGame(game, () -> {
                final Player first = game.getPhaseHandler().getPlayerTurn();
                firstSeats.add(game.getRegisteredPlayers().indexOf(first) + 1);
                first.concede();
                game.getAction().checkGameOverCondition();
            });
            AssertJUnit.assertTrue("the first player's concession ends the game", game.isGameOver());
            AssertJUnit.assertFalse(game.getOutcome().isWinner(game.getStartingPlayer().getRegisteredPlayer()));
            startingFlips.add(flips[0]);
        }
        return new Played(firstSeats, startingFlips);
    }

    @Test
    public void byDefaultTheLoserOfTheLastGameGoesFirst() {
        final Played played = play(false, 7);
        final int loser = played.firstSeats().get(0);
        for (int i = 1; i < GAMES; i++) {
            AssertJUnit.assertEquals("game " + (i + 1), loser, (int) played.firstSeats().get(i));
            AssertJUnit.assertEquals("only the first game flips for the start", 0, (int) played.startingFlips().get(i));
        }
        AssertJUnit.assertEquals(1, (int) played.startingFlips().get(0));
    }

    @Test
    public void withRandomStartTheLoserDoesNotDecide() {
        final Played played = play(true, 7);
        // the loser rule would give one seat all 16; a fair coin gives both seats some in all but 2 of 65,536 seeds
        AssertJUnit.assertTrue(played.firstSeats().toString(), played.firstSeats().contains(1));
        AssertJUnit.assertTrue(played.firstSeats().toString(), played.firstSeats().contains(2));
        for (int i = 0; i < GAMES; i++) {
            AssertJUnit.assertEquals("every game flips for the start, as the first does", 1,
                    (int) played.startingFlips().get(i));
        }
    }

    @Test
    public void theFirstGameIsTheSameEitherWay() {
        AssertJUnit.assertEquals(play(false, 11).firstSeats().get(0), play(true, 11).firstSeats().get(0));
    }

    @Test
    public void theSameSeedPicksTheSameFirstPlayers() {
        AssertJUnit.assertEquals(play(true, 3).firstSeats(), play(true, 3).firstSeats());
    }
}
