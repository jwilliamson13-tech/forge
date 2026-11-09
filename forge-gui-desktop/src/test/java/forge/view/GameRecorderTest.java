package forge.view;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AITest;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.RevealLog;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.event.GameEventPlayerPriority;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.HiddenReadWatch;
import forge.game.zone.ZoneType;

public class GameRecorderTest extends AITest {
    private static final List<GameRecorder.Seat> SEATS = List.of(
            new GameRecorder.Seat("Burn Red", "heuristic", 0), new GameRecorder.Seat("Merfolk", "full", 100));
    private static final String FILE_NAME = "night-1_42_3_Burn-Red_vs_Merfolk.jsonl.gz";

    private Path folder;
    private Game game;
    private Player seat1;
    private Player seat2;
    private boolean tripwire = false;

    @BeforeMethod
    public void makeFolder() throws IOException {
        tripwire = false;
        folder = Files.createTempDirectory("game-record-test");
    }

    @AfterMethod
    public void removeFolder() throws IOException {
        SimulateMatch.setRecording(null);
        try (Stream<Path> files = Files.list(folder)) {
            for (final Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(folder);
    }

    private GameRecorder.Settings settings(final int turnCap) {
        return new GameRecorder.Settings(folder, "night-1", 42L, turnCap, SEATS);
    }

    /** A two-seat game on turn 1, main phase 1, seat 1 active, with a recorder attached. */
    private GameRecorder startRecording(final int turnCap) {
        game = initAndCreateGame();
        seat1 = game.getRegisteredPlayers().get(0);
        seat2 = game.getRegisteredPlayers().get(1);
        ((LobbyPlayerAi) seat1.getLobbyPlayer()).setAiProfile("Default");
        ((LobbyPlayerAi) seat2.getLobbyPlayer()).setAiProfile("Reckless");
        fillLibrary(seat1, 10);
        fillLibrary(seat2, 10);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seat1, 1);
        game.getPhaseHandler().onStackResolved();
        if (tripwire) {
            game.HIDDEN_READ_WATCH = new HiddenReadWatch();
        }
        return GameRecorder.attach(game, settings(turnCap), 3);
    }

    private void endByClock(final GameRecorder recorder) {
        recorder.stoppedBy(GameRecorder.Stop.CLOCK);
        game.setGameOver(GameEndReason.Draw);
        recorder.finish();
    }

    private List<Path> files() throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.toList();
        }
    }

    /** The recorded file's lines, metadata first. */
    private List<JsonObject> readFile() throws IOException {
        final List<Path> files = files();
        assertEquals("one game file", 1, files.size());
        final List<JsonObject> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(files.get(0))), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                lines.add(JsonParser.parseString(line).getAsJsonObject());
            }
        }
        return lines;
    }

    private List<JsonObject> snapshotsOf(final List<JsonObject> lines, final int side) {
        return lines.stream().skip(1).filter(line -> line.get("side").getAsInt() == side).toList();
    }

    @Test
    public void aPriorityPassSnapshotsBothSides() throws IOException {
        final GameRecorder recorder = startRecording(50);
        game.getPhaseHandler().mainLoopStep();
        endByClock(recorder);

        final List<JsonObject> lines = readFile();
        assertEquals(1, snapshotsOf(lines, 1).size());
        assertEquals(1, snapshotsOf(lines, 2).size());
        assertTrue(snapshotsOf(lines, 1).get(0).getAsJsonObject("scoreboard").get("me_active").getAsBoolean());
        assertFalse(snapshotsOf(lines, 2).get(0).getAsJsonObject("scoreboard").get("me_active").getAsBoolean());
    }

    @Test
    public void identicalSnapshotsAreSkipped() throws IOException {
        final GameRecorder recorder = startRecording(50);
        gameLoopUntilNextPhase(game);
        gameLoopUntilNextPhase(game);
        endByClock(recorder);

        final List<JsonObject> lines = readFile();
        assertEquals("nothing changed, so one line per side", 3, lines.size());
    }

    @Test
    public void dedupeComparesEachSideWithItsOwnLastLine() throws IOException {
        final GameRecorder recorder = startRecording(50);
        final Card bolt = addCardToZone("Lightning Bolt", seat1, ZoneType.Library);
        game.getPhaseHandler().mainLoopStep();
        game.getAction().reveal(new CardCollection(bolt), seat1);
        game.getPhaseHandler().mainLoopStep();
        endByClock(recorder);

        final List<JsonObject> lines = readFile();
        assertEquals("seat 1 saw no change", 1, snapshotsOf(lines, 1).size());
        assertEquals("seat 2 learned a card", 2, snapshotsOf(lines, 2).size());
    }

    @Test
    public void theTurnCapEndsTheGameAsADrawWithItsReason() throws IOException {
        final GameRecorder recorder = startRecording(3);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, seat1, 3);
        game.getPhaseHandler().onStackResolved();
        playUntilNextTurn(game);

        assertTrue(game.isGameOver());
        assertTrue("no false winner", game.getOutcome().isDraw());
        assertNull(game.getOutcome().getWinningPlayer());
        assertTrue(recorder.stoppedAtTurnCap());
        recorder.finish();

        final List<JsonObject> lines = readFile();
        assertEquals("draw_turn_cap", lines.get(0).get("end_reason").getAsString());
        assertEquals(JsonNull.INSTANCE, lines.get(0).get("winner"));
        assertEquals("the turn after the cap has begun when the game stops", 4, lines.get(0).get("final_turn").getAsInt());
        final JsonObject last = lines.get(lines.size() - 1);
        assertEquals(3, last.getAsJsonObject("scoreboard").get("turn").getAsInt());
    }

    @Test
    public void aGameOnTheCapItselfPlaysOn() {
        final GameRecorder recorder = startRecording(3);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, seat1, 2);
        game.getPhaseHandler().onStackResolved();
        playUntilNextTurn(game);

        assertFalse(game.isGameOver());
        assertFalse(recorder.stoppedAtTurnCap());
    }

    @Test
    public void aWinFlushesWithLabelAndMetadata() throws IOException {
        final GameRecorder recorder = startRecording(50);
        game.getPhaseHandler().mainLoopStep();
        seat1.setLife(0, null);
        game.getAction().checkStateEffects(true);
        assertTrue(game.isGameOver());
        recorder.finish();

        assertEquals(List.of(folder.resolve(FILE_NAME)), files());
        final JsonObject metadata = readFile().get(0);
        assertEquals(2, metadata.get("schema_version").getAsInt());
        assertEquals("night-1", metadata.get("batch_id").getAsString());
        assertEquals(42L, metadata.get("seed").getAsLong());
        assertEquals(3, metadata.get("game_number").getAsInt());
        assertEquals("win", metadata.get("end_reason").getAsString());
        assertEquals(2, metadata.get("winner").getAsInt());
        assertEquals(1, metadata.get("final_turn").getAsInt());

        final JsonObject first = metadata.getAsJsonArray("seats").get(0).getAsJsonObject();
        assertEquals(1, first.get("seat").getAsInt());
        assertEquals("Burn Red", first.get("deck").getAsString());
        assertEquals("Default", first.get("ai_profile").getAsString());
        assertEquals("heuristic", first.get("sim_mode").getAsString());
        assertEquals(0, first.get("search_budget").getAsInt());
        final JsonObject second = metadata.getAsJsonArray("seats").get(1).getAsJsonObject();
        assertEquals(2, second.get("seat").getAsInt());
        assertEquals("Reckless", second.get("ai_profile").getAsString());
        assertEquals("full", second.get("sim_mode").getAsString());
        assertEquals(100, second.get("search_budget").getAsInt());

        final JsonObject tripwire = metadata.getAsJsonObject("tripwire");
        assertFalse(tripwire.get("enabled").getAsBoolean());
        assertEquals(0, tripwire.get("hits").getAsInt());
        assertEquals(0, tripwire.getAsJsonArray("signatures").size());
    }

    @Test
    public void theMetadataSaysWhetherTheAiPlayedHonestly() throws IOException {
        final GameRecorder recorder = startRecording(50);
        game.AI_HONEST_PLAY = true;
        firePriority();
        endByClock(recorder);

        assertTrue(readFile().get(0).get("honest_play").getAsBoolean());
    }

    @Test
    public void aGameWithoutHonestPlaySaysSoToo() throws IOException {
        final GameRecorder recorder = startRecording(50);
        firePriority();
        endByClock(recorder);

        assertFalse(readFile().get(0).get("honest_play").getAsBoolean());
    }

    @Test
    public void aTripwireThatRanReportsItsHits() throws IOException {
        tripwire = true;
        final GameRecorder recorder = startRecording(50);
        addCardToZone("Fog", seat2, ZoneType.Hand);
        game.HIDDEN_READ_WATCH.enter(seat1);
        ComputerUtil.hasAFogEffect(seat2, seat1, false);
        ComputerUtil.hasAFogEffect(seat2, seat1, false);
        game.HIDDEN_READ_WATCH.exit(seat1);
        firePriority();
        endByClock(recorder);

        final JsonObject summary = readFile().get(0).getAsJsonObject("tripwire");
        assertTrue(summary.get("enabled").getAsBoolean());
        assertEquals(game.HIDDEN_READ_WATCH.hitCount(), summary.get("hits").getAsInt());
        assertTrue(summary.get("hits").getAsInt() >= 2);
        final List<String> signatures = new ArrayList<>();
        summary.getAsJsonArray("signatures").forEach(signature -> signatures.add(signature.getAsString()));
        assertEquals(new ArrayList<>(game.HIDDEN_READ_WATCH.hitsBySignature().keySet()), signatures);
        assertTrue(signatures.toString(), signatures.stream().anyMatch(s -> s.startsWith("forge.ai.ComputerUtil.hasAFogEffect:")));
        final List<String> printed = GameRecorder.tripwireLines(game);
        assertEquals("Tripwire: " + summary.get("hits").getAsInt() + " hits", printed.get(0));
        assertEquals(1 + signatures.size(), printed.size());
        assertTrue(printed.get(1), printed.get(1).startsWith("Tripwire hit: ") && printed.get(1).endsWith(" " + signatures.get(0)));
    }

    @Test
    public void aTripwireWithNoHitsIsACleanZero() throws IOException {
        tripwire = true;
        final GameRecorder recorder = startRecording(50);
        firePriority();
        endByClock(recorder);

        final JsonObject summary = readFile().get(0).getAsJsonObject("tripwire");
        assertTrue(summary.get("enabled").getAsBoolean());
        assertEquals(0, summary.get("hits").getAsInt());
        assertEquals(List.of("Tripwire: 0 hits"), GameRecorder.tripwireLines(game));
    }

    @Test
    public void aGameThatSearchesSaysHowManyCopiesWereChecked() throws IOException {
        tripwire = true;
        final GameRecorder recorder = startRecording(50);
        game.AI_SIM_SEARCH = true;
        assertEquals(List.of("Tripwire: 0 hits", "Tripwire copies: 0 checked"), GameRecorder.tripwireLines(game));
        game.HIDDEN_READ_WATCH.copyChecked(List.of("Hand"));
        game.HIDDEN_READ_WATCH.copyChecked(List.of());
        firePriority();
        endByClock(recorder);

        final JsonObject summary = readFile().get(0).getAsJsonObject("tripwire");
        assertEquals(1, summary.get("hits").getAsInt());
        assertEquals("copy: Hand", summary.getAsJsonArray("signatures").get(0).getAsString());
        assertEquals(List.of("Tripwire: 1 hits", "Tripwire hit: 1 copy: Hand", "Tripwire copies: 2 checked"),
                GameRecorder.tripwireLines(game));
    }

    @Test
    public void withTheTripwireOffThereIsNoWatchAndNothingToPrint() {
        startRecording(50);
        assertNull(game.HIDDEN_READ_WATCH);
        assertTrue(GameRecorder.tripwireLines(game).isEmpty());
    }

    @Test
    public void bothPlayersLosingIsADrawByRules() throws IOException {
        final GameRecorder recorder = startRecording(50);
        game.getPhaseHandler().mainLoopStep();
        seat1.setLife(0, null);
        seat2.setLife(0, null);
        game.getAction().checkStateEffects(true);
        recorder.finish();

        final JsonObject metadata = readFile().get(0);
        assertEquals("draw_rules", metadata.get("end_reason").getAsString());
        assertEquals(JsonNull.INSTANCE, metadata.get("winner"));
    }

    @Test
    public void aClockStopIsADrawEvenThoughForgeNamesAWinner() throws IOException {
        final GameRecorder recorder = startRecording(50);
        game.getPhaseHandler().mainLoopStep();
        endByClock(recorder);

        assertFalse("the test needs Forge's false winner", game.getOutcome().isDraw());
        final JsonObject metadata = readFile().get(0);
        assertEquals("draw_clock", metadata.get("end_reason").getAsString());
        assertEquals(JsonNull.INSTANCE, metadata.get("winner"));
    }

    @Test
    public void aClockThatFiresAfterTheRealEndKeepsTheWin() throws IOException {
        final GameRecorder recorder = startRecording(50);
        game.getPhaseHandler().mainLoopStep();
        seat2.setLife(0, null);
        game.getAction().checkStateEffects(true);
        recorder.stoppedBy(GameRecorder.Stop.CLOCK);
        recorder.finish();

        final JsonObject metadata = readFile().get(0);
        assertEquals("win", metadata.get("end_reason").getAsString());
        assertEquals(1, metadata.get("winner").getAsInt());
    }

    @Test
    public void lateEventsFromATimedOutThreadAreDropped() throws IOException {
        final GameRecorder recorder = startRecording(50);
        game.getPhaseHandler().mainLoopStep();
        recorder.stoppedBy(GameRecorder.Stop.CLOCK);
        seat1.setLife(7, null);
        firePriority();
        game.setGameOver(GameEndReason.Draw);
        recorder.finish();

        assertEquals("metadata and one line per side", 3, readFile().size());
    }

    private void firePriority() {
        game.fireEvent(new GameEventPlayerPriority(PlayerView.get(seat1), PhaseType.MAIN1, PlayerView.get(seat1)));
    }

    @Test
    public void anExceptionWritesNothing() throws IOException {
        final GameRecorder recorder = startRecording(50);
        game.getPhaseHandler().mainLoopStep();
        recorder.stoppedBy(GameRecorder.Stop.EXCEPTION);
        game.setGameOver(GameEndReason.Draw);
        recorder.finish();

        assertTrue(files().isEmpty());
    }

    @Test
    public void aGameWithNoSnapshotsWritesNothing() throws IOException {
        final GameRecorder recorder = startRecording(50);
        endByClock(recorder);

        assertTrue(files().isEmpty());
    }

    @Test
    public void aFailureInsideTheHookWritesNothing() throws IOException {
        final GameRecorder recorder = startRecording(50);
        game.getPhaseHandler().mainLoopStep();
        final RevealLog broken = mock(RevealLog.class);
        when(broken.knownTo(any())).thenThrow(new IllegalStateException("from the test"));
        game.REVEAL_LOG = broken;
        firePriority();
        endByClock(recorder);

        assertTrue("a file with holes is worse than no file", files().isEmpty());
    }

    @Test
    public void aDeckNameIsCleanedAndCutForTheFileName() throws IOException {
        startRecording(50);
        final List<GameRecorder.Seat> seats = List.of(
                new GameRecorder.Seat("GAL_19_18_Burn Red Burn-sligh Generated Deck_27_18", "heuristic", 0),
                new GameRecorder.Seat("a  b", "heuristic", 0));
        final GameRecorder recorder = GameRecorder.attach(game,
                new GameRecorder.Settings(folder, "n", 1L, 50, seats), 2);
        game.getPhaseHandler().mainLoopStep();
        endByClock(recorder);

        assertEquals(List.of(folder.resolve("n_1_2_GAL-19-18-Burn-Red-Burn-sligh-Generated-_vs_a-b.jsonl.gz")), files());
    }

    /** A real Match with two AI seats, spied so that startGame can be replaced. */
    private Match spiedMatch() {
        final List<RegisteredPlayer> players = Lists.newArrayList();
        final Deck deck = new Deck();
        players.add(new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi("p1", null)));
        players.add(new RegisteredPlayer(deck).setPlayer(new LobbyPlayerAi("p2", null)));
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setSimTimeout(1);
        return spy(new Match(rules, players, "Test"));
    }

    private void aPriorityPassOn(final Game started) {
        final Player first = started.getRegisteredPlayers().get(0);
        started.getPhaseHandler().devModeSet(PhaseType.MAIN1, first, 1);
        started.fireEvent(new GameEventPlayerPriority(PlayerView.get(first), PhaseType.MAIN1, PlayerView.get(first)));
    }

    @Test
    public void aGameThatThrowsInForgeSimWritesNothing() throws IOException {
        initializeModel();
        SimulateMatch.setRecording(settings(50));
        final Match match = spiedMatch();
        doAnswer(call -> {
            aPriorityPassOn(call.getArgument(0));
            throw new IllegalStateException("from the test");
        }).when(match).startGame(any(Game.class));

        SimulateMatch.simulateSingleMatch(match, 0, false);

        assertTrue(files().isEmpty());
    }

    @Test
    public void aGameThatThrowsBeforeItsFirstPriorityWritesNothing() throws IOException {
        SimulateMatch.setRecording(settings(50));
        final Match match = spiedMatch();
        doThrow(new IllegalStateException("from the test")).when(match).startGame(any(Game.class));

        SimulateMatch.simulateSingleMatch(match, 0, false);

        assertTrue(files().isEmpty());
    }

    @Test
    public void aGameForcedOverWithNoStopReasonWritesNothing() throws IOException {
        initializeModel();
        SimulateMatch.setRecording(settings(50));
        final Match match = spiedMatch();
        doAnswer(call -> {
            aPriorityPassOn(call.getArgument(0));
            return null;
        }).when(match).startGame(any(Game.class));

        SimulateMatch.simulateSingleMatch(match, 0, false);

        assertTrue("the rules never ended this game, so Forge's winner is false", files().isEmpty());
    }

    @Test
    public void aForcedEndAfterOnePlayerLostCarriesNoWinnerEither() throws IOException {
        initializeModel();
        SimulateMatch.setRecording(settings(50));
        final Match match = spiedMatch();
        doAnswer(call -> {
            final Game started = call.getArgument(0);
            aPriorityPassOn(started);
            started.getRegisteredPlayers().get(1).loseConditionMet(GameLossReason.LifeReachedZero, null);
            return null;
        }).when(match).startGame(any(Game.class));

        SimulateMatch.simulateSingleMatch(match, 0, false);

        assertTrue(files().isEmpty());
    }

    @Test
    public void aGameTheRulesEndedIsNotAForcedEnd() throws IOException {
        final GameRecorder recorder = startRecording(50);
        firePriority();
        seat2.setLife(0, null);
        game.getAction().checkStateEffects(true);

        recorder.beforeForcedEnd();
        game.setGameOver(GameEndReason.Draw);
        recorder.finish();

        final JsonObject metadata = readFile().get(0);
        assertEquals("win", metadata.get("end_reason").getAsString());
        assertEquals(1, metadata.get("winner").getAsInt());
    }

    @Test
    public void theTripwireRunsAndReportsWithoutRecords() {
        initializeModel();
        SimulateMatch.setRecording(null);
        SimulateMatch.setTripwire(true);
        final Match match = spiedMatch();
        final List<Boolean> watched = new ArrayList<>();
        doAnswer(call -> {
            final Game started = call.getArgument(0);
            watched.add(started.HIDDEN_READ_WATCH != null);
            aPriorityPassOn(started);
            return null;
        }).when(match).startGame(any(Game.class));
        final ByteArrayOutputStream printed = new ByteArrayOutputStream();
        final PrintStream stdout = System.out;
        System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
        try {
            SimulateMatch.simulateSingleMatch(match, 0, false);
        } finally {
            System.setOut(stdout);
            SimulateMatch.setTripwire(false);
        }

        assertEquals(List.of(true), watched);
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("Tripwire: 0 hits"));
    }

    @Test
    public void aClockStopInForgeSimIsRecordedWithNoWinner() throws IOException {
        SimulateMatch.setRecording(settings(50));
        final Match match = spiedMatch();
        doAnswer(call -> {
            aPriorityPassOn(call.getArgument(0));
            Thread.sleep(5000);
            return null;
        }).when(match).startGame(any(Game.class));

        SimulateMatch.simulateSingleMatch(match, 0, false);

        final JsonObject metadata = readFile().get(0);
        assertEquals("draw_clock", metadata.get("end_reason").getAsString());
        assertEquals(JsonNull.INSTANCE, metadata.get("winner"));
        assertEquals(1, metadata.get("game_number").getAsInt());
    }
}
