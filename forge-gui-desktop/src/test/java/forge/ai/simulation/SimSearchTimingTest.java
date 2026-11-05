package forge.ai.simulation;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** The what-if retry at declare blockers must leave out what can't be done then (issue 51). */
public class SimSearchTimingTest extends SimulationTest {

    private Player setUp(Game game, boolean simSearch) {
        game.AI_SIM_SEARCH = simSearch;
        Player p = game.getPlayers().get(1);
        p.setTeam(0);
        game.getPlayers().get(0).setTeam(1);
        for (int i = 0; i < 3; i++) {
            addCard("Mountain", p);
        }
        addCardToZone("Rift Bolt", p, ZoneType.Hand);
        addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        return p;
    }

    private void toMain1(Game game, Player p) {
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);
    }

    private static boolean hasSuspend(List<SpellAbility> abilities) {
        return abilities.stream().anyMatch(sa -> sa.isKeyword(Keyword.SUSPEND));
    }

    private static boolean hasAbilityOf(List<SpellAbility> abilities, String cardName) {
        return abilities.stream().anyMatch(sa -> !sa.isKeyword(Keyword.SUSPEND) && sa.getHostCard().getName().equals(cardName));
    }

    private static String stderrOfSearch(SpellAbilityPicker picker) {
        PrintStream real = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            picker.chooseSpellAbilityToPlay(null);
        } finally {
            System.setErr(real);
        }
        return captured.toString();
    }

    @Test
    public void suspendAtSorceryTimingIsNotRetriedAfterBlockers() {
        Game game = initAndCreateGame();
        Player p = setUp(game, true);
        toMain1(game, p);

        SpellAbilityPicker picker = new SpellAbilityPicker(p);
        List<SpellAbility> candidates = picker.getCandidateSpellsAndAbilities();
        AssertJUnit.assertTrue("the test needs Suspend among the candidates", hasSuspend(candidates));

        List<SpellAbility> retry = picker.abilitiesToRetryAfterBlockers(candidates);
        AssertJUnit.assertFalse(hasSuspend(retry));
        AssertJUnit.assertTrue("an instant is still worth retrying", hasAbilityOf(retry, "Lightning Bolt"));
    }

    @Test
    public void suspendIsRetriedWhenItsCardHasFlash() {
        Game game = initAndCreateGame();
        Player p = setUp(game, true);
        addCard("Leyline of Anticipation", p);
        toMain1(game, p);

        SpellAbilityPicker picker = new SpellAbilityPicker(p);
        List<SpellAbility> retry = picker.abilitiesToRetryAfterBlockers(picker.getCandidateSpellsAndAbilities());
        AssertJUnit.assertTrue(hasSuspend(retry));
    }

    @Test
    public void defaultPlayStillRetriesSuspend() {
        Game game = initAndCreateGame();
        Player p = setUp(game, false);
        toMain1(game, p);

        SpellAbilityPicker picker = new SpellAbilityPicker(p);
        AssertJUnit.assertTrue(hasSuspend(picker.abilitiesToRetryAfterBlockers(picker.getCandidateSpellsAndAbilities())));
    }

    @Test
    public void theSearchNoLongerLogsAFalseErrorAboutSuspend() {
        Game game = initAndCreateGame();
        Player p = setUp(game, false);
        toMain1(game, p);
        AssertJUnit.assertTrue("without the fix this search hits the error",
                stderrOfSearch(new SpellAbilityPicker(p)).contains("Simulation: SA not found!"));

        game.AI_SIM_SEARCH = true;
        AssertJUnit.assertFalse(stderrOfSearch(new SpellAbilityPicker(p)).contains("Simulation: SA not found!"));
    }
}
