package forge.ai.simulation;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.Game;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * "Index 1 out of bounds for length 1" at SpellAbilityChoicesIterator.getModeCombination, thrown from a planned
 * modal spell (Jeska's Will, in ruby-storm-3 vs dragon-stompy-2) and ending the real game: the plan stored the mode
 * indexes the search saw, and by the time the spell was cast one mode had dropped out of the choices.
 */
public class PlannedModesTest extends SimulationTest {

    private static void setPlan(final SpellAbilityPicker picker, final int[] modes) throws Exception {
        final ArrayList<Plan.Decision> decisions = new ArrayList<>();
        decisions.add(new Plan.Decision(new Score(0), null, modes, "planned"));
        decisions.add(new Plan.Decision(new Score(0), null, modes, "later")); // keeps the plan active until dropped
        final Plan plan = new Plan(decisions, new Score(0));
        plan.selectNextDecision();
        final Field field = SpellAbilityPicker.class.getDeclaredField("plan");
        field.setAccessible(true);
        field.set(picker, plan);
    }

    @Test
    public void aPlannedModeThatDroppedOutOfTheChoicesDoesNotThrow() throws Exception {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card will = addCardToZone("Jeska's Will", p, ZoneType.Hand);
        final SpellAbility sa = will.getSpellAbilities().get(0);
        final List<AbilitySub> both = CharmEffect.makePossibleOptions(sa);
        AssertJUnit.assertEquals(2, both.size());
        final List<AbilitySub> onlyFirst = new ArrayList<>(both.subList(0, 1)); // the second mode lost its target

        final SpellAbilityPicker picker = new SpellAbilityPicker(p);
        setPlan(picker, new int[] { 1 });

        // null hands the choice back to the AI's own logic, and the stale plan is dropped
        AssertJUnit.assertNull(picker.chooseModeForAbility(sa, onlyFirst, 1, 1, false));
        AssertJUnit.assertFalse(picker.hasActivePlan());
    }

    @Test
    public void aPlannedModeStillInTheChoicesIsUsed() throws Exception {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card will = addCardToZone("Jeska's Will", p, ZoneType.Hand);
        final SpellAbility sa = will.getSpellAbilities().get(0);
        final List<AbilitySub> both = CharmEffect.makePossibleOptions(sa);

        final SpellAbilityPicker picker = new SpellAbilityPicker(p);
        setPlan(picker, new int[] { 1 });

        final List<AbilitySub> chosen = picker.chooseModeForAbility(sa, both, 1, 1, false);
        AssertJUnit.assertEquals(1, chosen.size());
        AssertJUnit.assertSame(both.get(1), chosen.get(0));
    }
}
