package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.eventbus.Subscribe;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventCardTapped;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * painter-3 v stoneblade-1: the full-simulation AI picked Pyroblast, the real cast could not go on the stack ("Couldn't
 * add to stack, failed to target"), and it picked Pyroblast again, 999 times in one priority with a full search each,
 * until the game ran out of time. After the first failure the cast path refuses the same cast for the rest of the turn
 * (AiController.hasFailedToCast), but the picker did not look at that, so every new search chose it again.
 *
 * Here a Shock that the search wants (lethal) is made to fail the same way in the real game: its targets go away
 * while the mana is paid, so MagicStack refuses it after paying, refunds the mana and returns it to the hand.
 */
public class FailedCastLoopTest extends SimulationTest {

    /** Paying taps the Mountain: while armed, that takes the spell's targets away, like targets that turned illegal. */
    public static final class TargetSaboteur {
        private final SpellAbility sa;
        boolean armed = true;
        int tapEvents;

        TargetSaboteur(final SpellAbility sa) {
            this.sa = sa;
        }

        @Subscribe
        public void onTapped(final GameEventCardTapped e) {
            if (armed) {
                tapEvents++;
                sa.resetTargets();
            }
        }
    }

    private static int shocksIn(final Player p, final ZoneType zone) {
        int n = 0;
        for (final Card c : p.getCardsIn(zone)) {
            if (c.getName().equals("Shock")) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void aCastThatFailedInTheRealGameIsNotPickedAgainThisTurn() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        p.setTeam(0);
        final Player opponent = game.getPlayers().get(0);
        opponent.setTeam(1);

        addCard("Mountain", p);
        addCardToZone("Shock", p, ZoneType.Hand);
        opponent.setLife(2, null);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        game.getAction().checkStateEffects(true);

        AssertJUnit.assertTrue("the test needs an AI controller", p.getController() instanceof PlayerControllerAi);
        final PlayerControllerAi pc = (PlayerControllerAi) p.getController();
        final SpellAbilityPicker picker = new SpellAbilityPicker(p);

        final SpellAbility first = picker.chooseSpellAbilityToPlay(null);
        AssertJUnit.assertNotNull("harness: the search must want the lethal Shock", first);
        AssertJUnit.assertEquals("Shock", first.getHostCard().getName());

        // the real cast, as the game loop does it (PlayerControllerAi.playChosenSpellAbility)
        final TargetSaboteur saboteur = new TargetSaboteur(first);
        game.subscribeToEvents(saboteur);
        final boolean played = pc.playWithRollback(first, null);
        saboteur.armed = false;

        AssertJUnit.assertTrue("harness: paying must have tapped the Mountain, or this test proves nothing", saboteur.tapEvents > 0);
        AssertJUnit.assertFalse("the cast failed", played);
        AssertJUnit.assertEquals("nothing on the stack", 0, game.getStack().size());
        AssertJUnit.assertEquals("the Shock is back in hand", 1, shocksIn(p, ZoneType.Hand));
        AssertJUnit.assertTrue("the AI remembers the failure", pc.getAi().hasFailedToCast(first));

        // The game loop gives priority again. Before the fix the search found the same Shock (the board is as it was),
        // the cast path refused it without doing anything, and that repeated until the 999 iteration guard.
        final SpellAbility second = picker.chooseSpellAbilityToPlay(null);
        AssertJUnit.assertTrue("the failed Shock was picked again: " + second,
                second == null || !second.getHostCard().getName().equals("Shock"));
    }
}
