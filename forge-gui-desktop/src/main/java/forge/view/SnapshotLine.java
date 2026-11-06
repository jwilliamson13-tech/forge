package forge.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.RevealLog;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** One snapshot line of a game record, written from one side. */
final class SnapshotLine {
    private final Game game;
    private final Player me;
    private final Player opp;

    private SnapshotLine(final Game game, final Player side) {
        this.game = game;
        this.me = side;
        this.opp = game.getRegisteredPlayers().stream().filter(p -> !p.equals(side)).findFirst().orElseThrow();
    }

    static JsonObject of(final Game game, final Player side) {
        return new SnapshotLine(game, side).build();
    }

    private int seat(final Player player) {
        return game.getRegisteredPlayers().indexOf(player) + 1;
    }

    private JsonObject build() {
        final JsonObject line = new JsonObject();
        line.addProperty("side", seat(me));
        line.add("scoreboard", scoreboard());
        final JsonObject zones = new JsonObject();
        final JsonArray revealed = new JsonArray();
        for (final RevealLog.Entry entry : game.REVEAL_LOG.knownTo(me)) {
            revealed.add(entry.id());
        }
        zones.add("opp_revealed", revealed);
        line.add("zones", zones);
        return line;
    }

    private JsonObject scoreboard() {
        final PhaseHandler phases = game.getPhaseHandler();
        final JsonObject board = new JsonObject();
        board.addProperty("me_life", me.getLife());
        board.addProperty("opp_life", opp.getLife());
        board.addProperty("me_poison", me.getPoisonCounters());
        board.addProperty("opp_poison", opp.getPoisonCounters());
        board.addProperty("me_library", me.getZone(ZoneType.Library).size());
        board.addProperty("opp_library", opp.getZone(ZoneType.Library).size());
        board.addProperty("opp_hand", opp.getZone(ZoneType.Hand).size());
        board.addProperty("turn", phases.getTurn());
        board.addProperty("me_active", me.equals(phases.getPlayerTurn()));
        board.addProperty("me_on_play", me.equals(game.getStartingPlayer()));
        return board;
    }
}
