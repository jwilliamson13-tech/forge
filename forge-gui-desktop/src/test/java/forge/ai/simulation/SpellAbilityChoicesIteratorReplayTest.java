package forge.ai.simulation;

import java.util.Collections;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * SpellAbilityChoicesIterator replays a spell once per combination of its modes, targets and choices, and keeps a count
 * of the decisions it has open. Two ways that count went wrong ended full-sim games as draws by exception, with
 * "RuntimeException: -1" from advance or a null last decision in SimulationController.doneEvaluating.
 */
public class SpellAbilityChoicesIteratorReplayTest extends SimulationTest {

    private Player seat(Game game) {
        Player p = game.getPlayers().get(1);
        p.setTeam(0);
        game.getPlayers().get(0).setTeam(1);
        return p;
    }

    /**
     * Two choices under each of two modes, so eight passes. When the first mode's choices ran out, the iterator still
     * remembered that the last pass had only moved the second choice, so under the second mode it didn't push the first
     * choice again, and closed one decision more than it opened.
     */
    @Test
    public void choicesUnderTheNextModeArePushedFromTheFirst() {
        Game game = initAndCreateGame();
        Player p = seat(game);
        Card charm = addCardToZone("Boros Charm", p, ZoneType.Hand);
        CardCollection cards = new CardCollection();
        cards.add(addCardToZone("Grizzly Bears", p, ZoneType.Hand));
        cards.add(addCardToZone("Runeclaw Bear", p, ZoneType.Hand));
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);

        SpellAbility sa = charm.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        List<AbilitySub> options = CharmEffect.makePossibleOptions(sa);
        // The double strike mode has no creature to target, so two modes are left.
        AssertJUnit.assertEquals(2, options.size());

        SimulationController controller = new SimulationController(new Score(0));
        controller.evaluateSpellAbility(Collections.singletonList(sa), 0);
        SpellAbilityChoicesIterator iterator = new SpellAbilityChoicesIterator(controller);
        int passes = 0;
        do {
            iterator.chooseModesForAbility(sa, options, 1, 1, false);
            iterator.chooseCard(cards);
            iterator.chooseCard(cards);
            passes++;
        } while (iterator.advance(new Score(passes)));
        controller.doneEvaluating(new Score(passes));

        AssertJUnit.assertEquals(2 * 2 * 2, passes);
        AssertJUnit.assertNull(controller.getLastDecision());
    }

    /**
     * The search looks one ability deeper after each pass, and only while nothing it has seen wins. Lazotep Quarry's
     * sacrifice is the only red mana for Shock, so after the Plains, the deeper search asks whether it can pay for
     * Shock and with that which creature the Quarry would sacrifice. That question reached the Plains' iterator as a
     * choice of the Plains'. Then Shock won, the next pass didn't look deeper, the question wasn't asked again, and the
     * iterator closed decisions it no longer had.
     */
    @Test
    public void theDeeperSearchDoesNotAddChoicesToTheAbilityAboveIt() {
        Game game = initAndCreateGame();
        Player p = seat(game);
        Player opponent = game.getPlayers().get(0);
        addCard("Lazotep Quarry", p);
        // Two creatures the AI is glad to give up (SacMe), or it wouldn't count on the Quarry's sacrifice for mana.
        addToken("r_0_2_dragon_egg_defender_hatches_dragon", p);
        addToken("u_3_3_fish_hatches_whale", p);
        // The Plains comes first in hand, so it is evaluated before Shock, which wins on its own.
        addCardToZone("Plains", p, ZoneType.Hand);
        addCardToZone("Shock", p, ZoneType.Hand);
        opponent.setLife(2, null);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        game.getAction().checkStateEffects(true);

        SpellAbilityPicker picker = new SpellAbilityPicker(p);
        SpellAbility sa = picker.chooseSpellAbilityToPlay(null);

        AssertJUnit.assertNotNull(sa);
    }
}
