package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.card.GamePieceType;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The two search failures that ended most of the gen-0 ladder's exception draws on 2026-11-09, each of which threw out
 * of a full-sim seat's search and ended the real game.
 */
public class LadderExceptionTest extends SimulationTest {

    /**
     * "Cannot invoke Integer.intValue() because getDividedValue(...) is null" at DamageDealEffect.resolve: the search
     * split divided damage only among the cards it targeted, so a split among players (Ral, Leyline Prodigy's -2 at
     * both players) gave no target a share, and resolving it threw.
     */
    @Test
    public void dividedDamageAmongPlayersGivesEveryTargetAShare() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card arc = addCardToZone("Arc Lightning", p, ZoneType.Hand);
        final SpellAbility sa = arc.getSpellAbilities().get(0);

        final PossibleTargetSelector selector = new PossibleTargetSelector(sa, sa, 0);
        AssertJUnit.assertTrue(selector.selectNextTargets());

        AssertJUnit.assertEquals(2, sa.getTargets().size()); // no creature on the board: both players
        int total = 0;
        for (final GameObject target : sa.getTargets()) {
            final Integer share = sa.getDividedValue(target);
            AssertJUnit.assertNotNull("no share for " + target, share);
            total += share;
        }
        AssertJUnit.assertEquals(3, total);
    }

    /** The same split among cards gave each card 0 damage, because it read the amount from CounterNum. */
    @Test
    public void dividedDamageAmongCardsSplitsTheWholeAmount() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        addCard("Grizzly Bears", opponent);
        addCard("Grizzly Bears", opponent);
        final Card arc = addCardToZone("Arc Lightning", p, ZoneType.Hand);
        final SpellAbility sa = arc.getSpellAbilities().get(0);

        final PossibleTargetSelector selector = new PossibleTargetSelector(sa, sa, 0);
        AssertJUnit.assertTrue(selector.selectNextTargets());

        AssertJUnit.assertEquals(3, sa.getTargets().size());
        int total = 0;
        for (final GameObject target : sa.getTargets()) {
            final Integer share = sa.getDividedValue(target);
            AssertJUnit.assertNotNull("no share for " + target, share);
            total += share;
        }
        AssertJUnit.assertEquals(3, total);
    }

    /**
     * "Couldn't map Enroot (151)" at GameCopier.find: an effect remembered a card that still named a zone it was no
     * longer in, so the copy held no card with its id and the whole copy threw.
     */
    @Test
    public void aRememberedCardNoLongerInItsZoneDoesNotBreakTheCopy() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card holder = addCard("Grizzly Bears", p);
        final Card gone = addCardToZone("Lightning Bolt", p, ZoneType.Exile);
        holder.addRemembered(gone);
        p.getZone(ZoneType.Exile).remove(gone);
        gone.setZone(p.getZone(ZoneType.Exile)); // it still names the zone, as the prepared spell copy did
        AssertJUnit.assertNotNull(gone.getZone());
        AssertJUnit.assertFalse(gone.getZone().contains(gone));

        final Game copy = new GameCopier(game).makeCopy();

        AssertJUnit.assertNotNull(copy);
        final Card copiedHolder = copy.getCardsIn(ZoneType.Battlefield).getFirst();
        AssertJUnit.assertEquals("Grizzly Bears", copiedHolder.getName());
        AssertJUnit.assertFalse(copiedHolder.hasRemembered());
    }

    /**
     * "Couldn't map The Initiative" at Player.mapEffectCard: a player who lost the initiative keeps its effect card,
     * which still names the command zone it was taken out of, so the copy had no card to map it to.
     */
    @Test
    public void aPlayerWhoLostTheInitiativeCanBeCopied() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opponent = game.getPlayers().get(0);
        p.createInitiativeEffect("CLB");
        p.removeInitiativeEffect(); // as when the opponent takes it
        opponent.createInitiativeEffect("CLB");

        final Game copy = new GameCopier(game).makeCopy();

        AssertJUnit.assertNotNull(copy);
        AssertJUnit.assertEquals(0, copy.getPlayers().get(1).getZone(ZoneType.Command).size());
        AssertJUnit.assertEquals(1, copy.getPlayers().get(0).getZone(ZoneType.Command).size());
    }

    /** A remembered card that is still in its zone is remembered by the copy too, as the card the copy holds. */
    @Test
    public void aRememberedCardStillInItsZoneIsRememberedInTheCopy() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card holder = addCard("Grizzly Bears", p);
        final Card exiled = addCardToZone("Lightning Bolt", p, ZoneType.Exile);
        holder.addRemembered(exiled);

        final Game copy = new GameCopier(game).makeCopy();

        final Card copiedHolder = copy.getCardsIn(ZoneType.Battlefield).getFirst();
        final Card copiedExiled = copy.getCardsIn(ZoneType.Exile).getFirst();
        AssertJUnit.assertTrue(copiedHolder.isRemembered(copiedExiled));
        AssertJUnit.assertSame(copy, ((Card) copiedHolder.getFirstRemembered()).getGame());
    }

    /**
     * "Error in Keyword Chapter:3:DBToken,DBDiscard,DBTransform for card Fable of the Mirror-Breaker" at
     * GameCopier.createCardCopy (issue #82): the copier rebuilt a token copy of a Saga from its keywords alone, and
     * Chapter looks its abilities up in the card's SVars, which the rebuilt token did not have yet.
     */
    @Test
    public void aTokenCopyOfASagaCanBeCopied() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card fable = addCard("Fable of the Mirror-Breaker", p);
        fable.setGamePieceType(GamePieceType.TOKEN); // as a token copy of it is: a token that keeps its paper card

        final Game copy = new GameCopier(game).makeCopy();

        AssertJUnit.assertNotNull(copy);
        final Card copied = copy.getCardsIn(ZoneType.Battlefield).getFirst();
        AssertJUnit.assertEquals("Fable of the Mirror-Breaker", copied.getName());
        AssertJUnit.assertTrue(copied.isToken());
        AssertJUnit.assertTrue(copied.hasSVar("DBToken"));
        AssertJUnit.assertEquals(fable.getTriggers().size(), copied.getTriggers().size());
    }
}
