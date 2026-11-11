package forge.ai.simulation;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.player.Player;

/**
 * GameCopier put the real game's Mana objects into the copy's pool as they were. A Mana holds the card it came from, the
 * ability part that made it and its player, all of the game it was made in, so a copy kept that game alive, and a copy
 * of a copy kept its whole line: on boards that float a lot of mana (Tron, Lands) that ran forge sim out of heap.
 */
public class GameCopierManaTest extends SimulationTest {

    private Game gameWithFloatingMana() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card forest = addCard("Forest", p);
        final Card swamp = addCard("Swamp", p);
        p.getManaPool().addManaNoEvent(new Mana(MagicColor.GREEN, forest, forest.getManaAbilities().get(0).getManaPart(), p));
        p.getManaPool().addManaNoEvent(new Mana(MagicColor.GREEN, forest, forest.getManaAbilities().get(0).getManaPart(), p));
        p.getManaPool().addManaNoEvent(new Mana(MagicColor.BLACK, swamp, swamp.getManaAbilities().get(0).getManaPart(), p));
        return game;
    }

    @Test
    public void theCopysFloatingManaBelongsToTheCopy() {
        final Game game = gameWithFloatingMana();
        final Player p = game.getPlayers().get(1);

        final Game copy = new GameCopier(game).makeCopy();
        final Player copyP = copy.getPlayers().get(1);

        final List<Mana> mana = new ArrayList<>();
        copyP.getManaPool().forEach(mana::add);
        AssertJUnit.assertEquals(3, mana.size());
        AssertJUnit.assertEquals(2, copyP.getManaPool().getAmountOfColor(MagicColor.GREEN));
        AssertJUnit.assertEquals(1, copyP.getManaPool().getAmountOfColor(MagicColor.BLACK));
        for (final Mana m : mana) {
            AssertJUnit.assertSame(copy, m.getSourceCard().getGame());
            AssertJUnit.assertSame(copy, m.getManaAbility().getSourceCard().getGame());
            AssertJUnit.assertSame(copyP, m.getPlayer());
        }
        // the real game's pool is left as it was
        for (final Mana m : p.getManaPool()) {
            AssertJUnit.assertSame(game, m.getSourceCard().getGame());
        }
    }

    /** Nothing else of this board ties the copy to the real game, so once the real game is dropped it can be collected. */
    @Test
    public void aCopyDoesNotKeepTheRealGameAlive() {
        Game game = gameWithFloatingMana();
        final WeakReference<Game> real = new WeakReference<>(game);
        final Game copy = new GameCopier(game).makeCopy();
        game = null;

        for (int i = 0; i < 50 && real.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        AssertJUnit.assertNull("the copy still holds the real game", real.get());
        AssertJUnit.assertEquals(3, copy.getPlayers().get(1).getManaPool().totalMana());
    }
}
