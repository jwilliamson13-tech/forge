package forge.view;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.RevealLog;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.zone.HiddenReadWatch;

/**
 * Writes one game record, the file described in the Python repo's docs/game-record.md.
 * <p>
 * It listens on the real game's own event bus, so the AI's copied games never reach it. Lines stay in memory until the
 * game ends, because the metadata goes first and isn't known before then. Only {@link SimulateMatch} sees every way a
 * game can stop, so it says why through {@link #stoppedBy} and calls {@link #finish} once the game is over.
 * <p>
 * The game thread keeps running for a moment after a clock stop, which is why everything here is synchronized and why
 * a stop closes the recorder to late events.
 */
public final class GameRecorder {
    private static final int SCHEMA_VERSION = 2;
    private static final int DECK_NAME_LIMIT = 40;
    /** Gson drops null fields without this, and the schema needs them. */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    public enum Stop { CLOCK, TURN_CAP, EXCEPTION }

    /** A seat's setup, seat 1 first. The AI profile is read from the game, after any defaulting. */
    public record Seat(String deck, String simMode, int searchBudget) { }

    public record Settings(Path folder, String batchId, long seed, int turnCap, List<Seat> seats) { }

    private final Game game;
    private final Settings settings;
    private final int gameNumber;
    private final List<String> lines = new ArrayList<>();
    private final Map<Integer, String> lastLine = new HashMap<>();
    private final Map<Integer, RecordIds> recordIds = new HashMap<>();
    private int lastTurnSeen = 0;
    private Stop stop = null;
    private boolean noResult = false;
    private boolean failed = false;
    private boolean closed = false;

    private GameRecorder(final Game game, final Settings settings, final int gameNumber) {
        this.game = game;
        this.settings = settings;
        this.gameNumber = gameNumber;
    }

    /** Starts recording {@code game}, which has to be attached before it starts so that no reveal is missed. */
    public static GameRecorder attach(final Game game, final Settings settings, final int gameNumber) {
        final GameRecorder recorder = new GameRecorder(game, settings, gameNumber);
        game.REVEAL_LOG = new RevealLog(game);
        game.subscribeToEvents(recorder);
        return recorder;
    }

    /** Guava logs what a subscriber throws and carries on, which would leave holes in the file, so failures stop here. */
    @Subscribe
    public synchronized void onPriority(final GameEventPlayerPriority event) {
        if (closed) {
            return;
        }
        try {
            final List<Player> seats = game.getRegisteredPlayers();
            for (int i = 0; i < seats.size(); i++) {
                final RecordIds ids = recordIds.computeIfAbsent(i + 1, seat -> new RecordIds(settings.seed(), gameNumber, seat));
                final JsonObject snapshot = SnapshotLine.of(game, seats.get(i), ids);
                final String line = GSON.toJson(snapshot);
                if (!line.equals(lastLine.put(i, line))) {
                    lines.add(line);
                }
            }
            lastTurnSeen = Math.max(lastTurnSeen, game.getPhaseHandler().getTurn());
        } catch (final Throwable t) {
            fail(t);
        }
    }

    /**
     * The cap goes here and not on the priority event, because after a priority subscriber returns the AI would still
     * be asked to decide on a finished game. Marking both players first keeps Player.onGameOver from naming a winner.
     */
    @Subscribe
    public void onTurn(final GameEventTurnBegan event) {
        if (event.turnNumber() <= settings.turnCap()) {
            return;
        }
        try {
            stoppedBy(Stop.TURN_CAP);
            for (final Player player : game.getPlayers()) {
                player.intentionalDraw();
            }
            game.setGameOver(GameEndReason.Draw);
        } catch (final Throwable t) {
            fail(t);
        }
    }

    private synchronized void fail(final Throwable cause) {
        failed = true;
        closed = true;
        System.err.println("Game record dropped: game " + gameNumber);
        cause.printStackTrace();
    }

    /** What really stopped the game. The first reason counts, and a clock that fires after the real end is no stop. */
    public synchronized void stoppedBy(final Stop why) {
        closed = true;
        if (why == Stop.EXCEPTION) {
            noResult = true;
        } else if (stop == null && !(why == Stop.CLOCK && game.isGameOver())) {
            stop = why;
        }
    }

    /**
     * SimulateMatch ends every game with setGameOver, which leaves a game the rules ended alone and names a false winner
     * on any other. So this comes first. A game still going here with nothing to say why has no result to trust, even if
     * one player has already lost, and gets no file.
     */
    public synchronized void beforeForcedEnd() {
        closed = true;
        if (stop == null && !game.isGameOver()) {
            noResult = true;
        }
    }

    public synchronized boolean stoppedAtTurnCap() {
        return stop == Stop.TURN_CAP;
    }

    /** Writes the file, unless the game has no result, the hook failed, or there is nothing to label. */
    public synchronized void finish() {
        closed = true;
        if (noResult || failed || lines.isEmpty()) {
            return;
        }
        final Path file = settings.folder().resolve(fileName());
        final Path temp = settings.folder().resolve(fileName() + ".tmp");
        try {
            Files.createDirectories(settings.folder());
            try (Writer out = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temp)), StandardCharsets.UTF_8)) {
                out.write(GSON.toJson(metadata()));
                out.write('\n');
                for (final String line : lines) {
                    out.write(line);
                    out.write('\n');
                }
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException | RuntimeException e) {
            System.err.println("Game record not written: " + file);
            e.printStackTrace();
        }
    }

    private String fileName() {
        return String.join("_", settings.batchId(), String.valueOf(settings.seed()), String.valueOf(gameNumber),
                cleanDeckName(settings.seats().get(0).deck()), "vs", cleanDeckName(settings.seats().get(1).deck()))
                + ".jsonl.gz";
    }

    private static String cleanDeckName(final String deck) {
        final String clean = deck.replaceAll("[^A-Za-z0-9]+", "-");
        return clean.substring(0, Math.min(clean.length(), DECK_NAME_LIMIT));
    }

    private JsonObject metadata() {
        final JsonObject metadata = new JsonObject();
        metadata.addProperty("schema_version", SCHEMA_VERSION);
        metadata.addProperty("batch_id", settings.batchId());
        metadata.addProperty("seed", settings.seed());
        metadata.addProperty("game_number", gameNumber);
        metadata.add("seats", seats());
        final String endReason = endReason();
        metadata.addProperty("winner", "win".equals(endReason) ? winningSeat() : null);
        // Karn can send the turn number back to 1, and no snapshot may be past the final turn
        metadata.addProperty("final_turn", Math.max(lastTurnSeen, game.getPhaseHandler().getTurn()));
        metadata.addProperty("end_reason", endReason);
        metadata.addProperty("honest_play", game.AI_HONEST_PLAY);
        metadata.add("tripwire", tripwire());
        return metadata;
    }

    private JsonArray seats() {
        final JsonArray seats = new JsonArray();
        final List<Player> players = game.getRegisteredPlayers();
        for (int i = 0; i < settings.seats().size(); i++) {
            final Seat setup = settings.seats().get(i);
            final JsonObject seat = new JsonObject();
            seat.addProperty("seat", i + 1);
            seat.addProperty("deck", setup.deck());
            seat.addProperty("ai_profile", ((LobbyPlayerAi) players.get(i).getLobbyPlayer()).getAiProfile());
            seat.addProperty("sim_mode", setup.simMode());
            seat.addProperty("search_budget", setup.searchBudget());
            seats.add(seat);
        }
        return seats;
    }

    /** Forge's outcome is only asked about a game the rules ended, because a stopped game's outcome names a false winner. */
    private String endReason() {
        if (stop == Stop.CLOCK) {
            return "draw_clock";
        }
        if (stop == Stop.TURN_CAP) {
            return "draw_turn_cap";
        }
        return game.getOutcome().isDraw() ? "draw_rules" : "win";
    }

    private Integer winningSeat() {
        final List<Player> players = game.getRegisteredPlayers();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getOutcome().hasWon()) {
                return i + 1;
            }
        }
        throw new IllegalStateException("a won game with no winner");
    }

    private JsonObject tripwire() {
        final HiddenReadWatch watch = game.HIDDEN_READ_WATCH;
        final JsonArray signatures = new JsonArray();
        if (watch != null) {
            watch.hitsBySignature().keySet().forEach(signatures::add);
        }
        final JsonObject tripwire = new JsonObject();
        tripwire.addProperty("enabled", watch != null);
        tripwire.addProperty("hits", watch == null ? 0 : watch.hitCount());
        tripwire.add("signatures", signatures);
        return tripwire;
    }

    /**
     * A game's tripwire result for stdout, which also covers a game that gets no file. Empty with the tripwire off. Its hits
     * include the copies made for a search, and a game that searched says how many copies were checked.
     */
    public static List<String> tripwireLines(final Game game) {
        final HiddenReadWatch watch = game.HIDDEN_READ_WATCH;
        final List<String> printed = new ArrayList<>();
        if (watch != null) {
            printed.add("Tripwire: " + watch.hitCount() + " hits");
            watch.hitsBySignature().forEach((signature, hits) -> printed.add("Tripwire hit: " + hits + " " + signature));
            // only a game that searches makes copies, so other games print what they always did
            if (game.AI_SIM_SEARCH || watch.copiesChecked() > 0) {
                printed.add("Tripwire copies: " + watch.copiesChecked() + " checked");
            }
        }
        return printed;
    }
}
