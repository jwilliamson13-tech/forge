package forge.view;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.time.StopWatch;

import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.ai.AiProfileUtil;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.Evaluators;
import forge.ai.simulation.FlatMonteCarloStats;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.HiddenReadWatch;
import forge.gamemodes.tournament.system.AbstractTournament;
import forge.gamemodes.tournament.system.TournamentBracket;
import forge.gamemodes.tournament.system.TournamentPairing;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.gamemodes.tournament.system.TournamentRoundRobin;
import forge.gamemodes.tournament.system.TournamentSwiss;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.AITimeoutTracker;
import forge.util.Lang;
import forge.util.MyRandom;
import forge.util.TextUtil;
import forge.util.WordUtil;
import forge.util.storage.IStorage;

public class SimulateMatch {
    /** Set by -mc: null leaves the game default (off, as the GUI's preference is only applied by HostedMatch). */
    private static Boolean flatMonteCarlo = null;
    private static Boolean flatMonteCarloMax = null;

    private static final String SIM_MODE_HEURISTIC = "heuristic";
    private static final String SIM_MODE_HYBRID = "hybrid";
    private static final String SIM_MODE_FULL = "full";

    /** The turn cap's counterpart of the clock's line. The sim-runner reads it to tell the two kinds of draw apart. */
    private static final String TURN_CAP_LINE = "Stopping game at the turn cap as draw";
    private static final int DEFAULT_TURN_CAP = 50;
    /** Printed once with -firstplayer random. The sim-runner reads it as proof that the jar knows the flag. */
    static final String RANDOM_FIRST_PLAYER_LINE = "First player: random each game";

    /** Set by -budget: search steps per AI decision, or 0 to leave the wall-clock limit in charge. */
    private static int searchBudget = 0;
    /** Set by -aisteps: AI steps per heuristic AI decision, or 0 to leave the wall-clock limit in charge. */
    private static int aiSteps = 0;
    /** True with -sim, -budget or -aisteps. Without them the output stays what stock Forge prints. */
    private static boolean printAiLimits = false;
    /** True when -sim gives a seat a mode that searches. Only then do the search changes of Game.AI_SIM_SEARCH apply. */
    private static boolean simSearch = false;
    /** Set by -record: what to write game records with, or null to write none. */
    private static GameRecorder.Settings recording = null;
    /** True for forge sim unless -tripwire off says otherwise. It reports in stdout, and in the game record if there is one. */
    private static boolean tripwire = false;
    /** True with -honest on: the AI keeps to what a person in its seat could know. See Game.AI_HONEST_PLAY. */
    private static boolean honest = false;
    /** True by default when a seat searches, unless -prune off says otherwise. See Game.AI_PRUNE_HIDDEN_INFO. */
    private static boolean prune = false;
    /** True with -firstplayer random: every game's first player is a coin flip. See GameRules.randomStartEachGame. */
    private static boolean randomFirstPlayer = false;

    static void setTripwire(final boolean on) {
        tripwire = on;
    }

    static void setRecording(final GameRecorder.Settings settings) {
        recording = settings;
    }

    public static void simulate(String[] args) {
        FModel.initialize(null, null);
        // -eval onnx:DIR: the ONNX evaluator lives in this module, and forge-ai's Evaluators only knows it once told
        OnnxEvaluators.register();

        System.out.println("Simulation mode");
        if (args.length < 4) {
            argumentHelp();
            return;
        }

        final Map<String, List<String>> params = new HashMap<>();
        List<String> options = null;

        for (int i = 1; i < args.length; i++) {
            // "sim" is in the 0th slot
            final String a = args[i];

            if (a.charAt(0) == '-') {
                if (a.length() < 2) {
                    System.err.println("Error at argument " + a);
                    argumentHelp();
                    return;
                }

                options = new ArrayList<>();
                params.put(a.substring(1), options);
            } else if (options != null) {
                options.add(a);
            } else {
                System.err.println("Illegal parameter usage");
                return;
            }
        }

        String deckDir = null;
        if (params.containsKey("D")) {
            deckDir = params.get("D").get(0);
        }

        int nGames = 1;
        if (params.containsKey("n")) {
            // Number of games should only be a single string
            nGames = Integer.parseInt(params.get("n").get(0));
        }

        int matchSize = 0;
        if (params.containsKey("m")) {
            // Match size ("best of X games")
            matchSize = Integer.parseInt(params.get("m").get(0));
        }

        boolean outputGamelog = !params.containsKey("q");

        // Flat Monte Carlo is a match preference that only the GUI applies (HostedMatch); without this switch a
        // simulation always runs the plain heuristic AI, whatever the preference says.
        flatMonteCarlo = null;
        flatMonteCarloMax = null;
        if (params.containsKey("mc")) {
            final List<String> mcValues = params.get("mc");
            final String mcValue = mcValues.isEmpty() ? "on" : mcValues.get(0);
            if ("on".equalsIgnoreCase(mcValue) || "true".equalsIgnoreCase(mcValue)) {
                flatMonteCarlo = Boolean.TRUE;
            } else if ("max".equalsIgnoreCase(mcValue)) {
                flatMonteCarlo = Boolean.TRUE;
                flatMonteCarloMax = Boolean.TRUE; // the strength a desktop match gets automatically
            } else if ("off".equalsIgnoreCase(mcValue) || "false".equalsIgnoreCase(mcValue)) {
                flatMonteCarlo = Boolean.FALSE;
            } else {
                System.out.println("Unknown value for -mc (use on, max or off): " + mcValue);
                return;
            }
        }
        FlatMonteCarloStats.reset();

        searchBudget = 0;
        if (params.containsKey("budget")) {
            searchBudget = parseSearchBudget(params.get("budget"));
            if (searchBudget == 0) {
                System.out.println("-budget takes a whole number of search steps above zero: " + params.get("budget"));
                return;
            }
        }
        aiSteps = 0;
        if (params.containsKey("aisteps")) {
            aiSteps = parseSearchBudget(params.get("aisteps"));
            if (aiSteps == 0) {
                System.out.println("-aisteps takes a whole number of AI steps above zero: " + params.get("aisteps"));
                return;
            }
        }
        printAiLimits = searchBudget > 0 || aiSteps > 0 || params.containsKey("sim");
        simSearch = anySeatSearches(params.get("sim"));

        recording = null;
        final String recordProblem = recordOptionsProblem(params);
        if (recordProblem != null) {
            System.out.println(recordProblem);
            return;
        }

        final String tripwireProblem = tripwireOptionProblem(params);
        if (tripwireProblem != null) {
            System.out.println(tripwireProblem);
            return;
        }
        tripwire = tripwireOn(params);

        final String honestProblem = honestOptionProblem(params);
        if (honestProblem != null) {
            System.out.println(honestProblem);
            return;
        }
        honest = List.of("on").equals(params.get("honest"));

        final String pruneProblem = pruneOptionProblem(params);
        if (pruneProblem != null) {
            System.out.println(pruneProblem);
            return;
        }
        prune = pruneOn(params);

        final String firstPlayerProblem = firstPlayerOptionProblem(params);
        if (firstPlayerProblem != null) {
            System.out.println(firstPlayerProblem);
            return;
        }
        randomFirstPlayer = randomFirstPlayer(params);

        String evalProblem = OnnxEvaluators.retiredPropertyProblem();
        if (evalProblem == null) {
            evalProblem = evalOptionProblem(params);
        }
        if (evalProblem != null) {
            System.out.println(evalProblem);
            return;
        }

        Long seed = null;
        if (params.containsKey("s")) {
            seed = Long.parseLong(params.get("s").get(0));
            MyRandom.setRandom(new Random(seed));
        }

        GameType type = GameType.Constructed;
        if (params.containsKey("f")) {
            type = GameType.valueOf(WordUtil.capitalize(params.get("f").get(0)));
        }

        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));

        if (matchSize != 0) {
            rules.setGamesPerMatch(matchSize);
        }
        rules.setRandomStartEachGame(randomFirstPlayer);
        if (randomFirstPlayer) {
            // only with the flag, so that a run without it prints what stock Forge prints
            System.out.println(RANDOM_FIRST_PLAYER_LINE);
        }

        if (params.containsKey("t")) {
            if (params.containsKey("sim")) {
                // Tournament players are built elsewhere, and a seat that quietly stayed heuristic would skew the results.
                System.out.println("-sim sets a mode per deck, so it cannot be used with -t");
                return;
            }
            simulateTournament(params, rules, outputGamelog);
            System.out.flush();
            return;
        }

        List<RegisteredPlayer> pp = new ArrayList<>();
        List<GameRecorder.Seat> seats = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        int i = 1;

        // Optional AI profile per player, in the same order as the decks. Lets a run pit one set of
        // AI settings against another, which is the only way to tell from the results whether an AI
        // change actually helped.
        List<String> aiProfiles = params.get("a");
        if (aiProfiles != null) {
            for (String profile : aiProfiles) {
                if (!AiProfileUtil.getProfilesDisplayList().contains(profile)) {
                    System.out.println(TextUtil.concatNoSpace("Unknown AI profile - ", profile,
                            ". Available profiles: ", String.join(", ", AiProfileUtil.getProfilesDisplayList())));
                    return;
                }
            }
        }

        // Optional simulation mode per player, in the same order as the decks. Only the desktop lobby
        // could set this before, so a simulation always ran the heuristic AI.
        List<String> simModes = params.get("sim");
        if (simModes != null) {
            for (String mode : simModes) {
                if (aiOptionsForSimMode(mode) == null) {
                    System.out.println(TextUtil.concatNoSpace("Unknown simulation mode - ", mode,
                            ". Available modes: ", SIM_MODE_HEURISTIC, ", ", SIM_MODE_HYBRID, ", ", SIM_MODE_FULL));
                    return;
                }
            }
        }

        // Optional evaluator per player for the search to score states with, in the same order as the decks.
        List<String> evaluators = params.get("eval");

        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, type, deckDir);
                if (d == null) {
                    System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck, ", match cannot start"));
                    return;
                }
                if (i > 1) {
                    sb.append(" vs ");
                }
                String profile = aiProfiles != null && aiProfiles.size() >= i ? aiProfiles.get(i - 1) : "";
                String simMode = simModes != null && simModes.size() >= i ? simModes.get(i - 1) : SIM_MODE_HEURISTIC;
                String name = TextUtil.concatNoSpace("Ai(", String.valueOf(i), ")-", d.getName());
                if (simModes != null) {
                    // In the name, so that every result line says which mode the seat played.
                    name = TextUtil.concatNoSpace(name, " [sim=", simMode, "]");
                }
                String evaluator = evaluators != null && evaluators.size() >= i ? evaluators.get(i - 1) : Evaluators.FORGE;
                if (evaluators != null) {
                    // The proof that the evaluator took effect, as [sim=mode] is for the mode. A family's argument,
                    // such as an ONNX bundle's path, stays out of the name; the ONNX seat line on stderr names it.
                    name = TextUtil.concatNoSpace(name, " [eval=", Evaluators.family(evaluator), "]");
                }
                sb.append(name);
                if (!profile.isEmpty()) {
                    sb.append(" [").append(profile).append("]");
                }

                RegisteredPlayer rp;

                if (type.equals(GameType.Commander)) {
                    rp = RegisteredPlayer.forCommander(d);
                } else {
                    rp = new RegisteredPlayer(d);
                }
                // The same sleeve draw as createAiPlayer(name, avatarIndex, profile), which takes no options,
                // so that a seed still replays the same games.
                int sleeveCount = GuiBase.getInterface().getSleevesCount();
                int sleeveIndex = sleeveCount == 0 ? 0 : MyRandom.getRandom().nextInt(sleeveCount);
                LobbyPlayer player = GamePlayerUtil.createAiPlayer(name, i - 1, sleeveIndex, aiOptionsForSimMode(simMode), profile);
                ((LobbyPlayerAi) player).setScopedDecisions(tripwire || honest);
                // a seat's evaluator that draws gets its own stream from the run's seed, so the run still replays
                ((LobbyPlayerAi) player).setSearchScorer(
                        Evaluators.scorer(evaluator, seed == null ? null : Evaluators.seatSeed(seed, i)));
                final String onnxFolder = OnnxEvaluators.folderOf(evaluator);
                if (onnxFolder != null) {
                    // the proof of which bundle the seat took, as the folder was given
                    System.err.println("ONNX evaluator: seat " + i + " searches with " + onnxFolder);
                }
                rp.setPlayer(player);
                pp.add(rp);
                // the budget is one value for the run, and a heuristic seat has no search to spend it on
                seats.add(new GameRecorder.Seat(d.getName(), simMode, SIM_MODE_HEURISTIC.equals(simMode) ? 0 : searchBudget));
                i++;
            }
        }

        if (params.containsKey("c")) {
            rules.setSimTimeout(Integer.parseInt(params.get("c").get(0)));
        }

        if (params.containsKey("record")) {
            recording = new GameRecorder.Settings(Path.of(params.get("record").get(0)), params.get("batch").get(0), seed,
                    parseTurnCap(params.get("turncap")), seats);
        }

        sb.append(" - ").append(Lang.nounWithNumeral(nGames, "game")).append(" of ").append(type);
        if (seed != null) {
            sb.append(" seed ").append(seed);
        }

        System.out.println(sb);

        Match mc = new Match(rules, pp, "Test");

        if (matchSize != 0) {
            int iGame = 0;
            while (!mc.isMatchOver()) {
                // play games until the match ends
                simulateSingleMatch(mc, iGame, outputGamelog);
                iGame++;
            }
        } else {
            for (int iGame = 0; iGame < nGames; iGame++) {
                simulateSingleMatch(mc, iGame, outputGamelog);
            }
        }

        if (Boolean.TRUE.equals(flatMonteCarlo)) {
            System.out.println(FlatMonteCarloStats.summary());
        }
        System.out.flush();
    }

    /** The search steps in a -budget value, or 0 if it isn't a whole number above zero. */
    static int parseSearchBudget(List<String> values) {
        if (values.size() != 1) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(values.get(0)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The turns a -turncap value allows, the default if the flag was left out, or 0 if it isn't a whole number above zero. */
    static int parseTurnCap(List<String> values) {
        if (values == null) {
            return DEFAULT_TURN_CAP;
        }
        return parseSearchBudget(values);
    }

    /** Why these options can't write game records, or null if they can or don't ask to. */
    static String recordOptionsProblem(Map<String, List<String>> params) {
        if (!params.containsKey("record")) {
            return params.containsKey("batch") || params.containsKey("turncap") ? "-batch and -turncap need -record" : null;
        }
        if (params.get("record").size() != 1) {
            return "-record takes one folder";
        }
        List<String> batch = params.get("batch");
        if (batch == null || batch.size() != 1 || !batch.get(0).matches("[A-Za-z0-9_-]+")) {
            return "-record needs -batch with one id of letters, digits, _ and -";
        }
        if (params.containsKey("t")) {
            // simulateSingleMatch also plays tournaments, which have no fixed pair of seats to record
            return "-record cannot be used with -t";
        }
        if (!isRecordableSeed(params.get("s"))) {
            return "-record needs -s with a whole number of 0 or more, because a game record names its seed";
        }
        List<String> decks = params.get("d");
        if (decks == null || decks.size() != 2) {
            return "-record needs two decks, because a game record has two seats";
        }
        if (parseTurnCap(params.get("turncap")) == 0) {
            return "-turncap takes a whole number of turns above zero: " + params.get("turncap");
        }
        return null;
    }

    /** Why -tripwire can't be used, or null if it can or wasn't asked for. */
    static String tripwireOptionProblem(Map<String, List<String>> params) {
        List<String> values = params.get("tripwire");
        if (values == null) {
            return null;
        }
        if (!(List.of("on").equals(values) || List.of("off").equals(values))) {
            return "-tripwire takes on or off: " + values;
        }
        // a tournament's players don't go through the seats set up here, so no decision of theirs would be marked
        return params.containsKey("t") ? "-tripwire cannot be used with -t" : null;
    }

    /** Why -honest can't be used, or null if it can or wasn't asked for. It works without -record, on public cards only. */
    static String honestOptionProblem(Map<String, List<String>> params) {
        List<String> values = params.get("honest");
        if (values == null) {
            return null;
        }
        if (!(List.of("on").equals(values) || List.of("off").equals(values))) {
            return "-honest takes on or off: " + values;
        }
        // simulateSingleMatch also plays tournaments, and a tournament's players don't go through the seats set up here
        return params.containsKey("t") ? "-honest cannot be used with -t" : null;
    }

    /**
     * Why -eval can't be used, or null if it can or wasn't asked for. An evaluator other than Forge's needs its seat to
     * search, because a heuristic seat never scores a state, and a seat that quietly played stock would skew the run.
     */
    static String evalOptionProblem(Map<String, List<String>> params) {
        List<String> evaluators = params.get("eval");
        if (evaluators == null) {
            return null;
        }
        if (params.containsKey("t")) {
            // tournament players are built elsewhere, as with -sim
            return "-eval sets an evaluator per deck, so it cannot be used with -t";
        }
        if (evaluators.isEmpty()) {
            return "-eval takes one evaluator per seat, in deck order: " + String.join(", ", Evaluators.names());
        }
        List<String> simModes = params.get("sim");
        for (int seat = 1; seat <= evaluators.size(); seat++) {
            String evaluator = evaluators.get(seat - 1);
            // a name that loads something, such as an ONNX bundle, loads it here, once, so a bad one stops the run
            String problem = Evaluators.problem(evaluator);
            if (problem != null) {
                return problem;
            }
            String simMode = simModes != null && simModes.size() >= seat ? simModes.get(seat - 1) : SIM_MODE_HEURISTIC;
            if (!Evaluators.FORGE.equals(evaluator) && SIM_MODE_HEURISTIC.equals(simMode)) {
                return TextUtil.concatNoSpace("-eval ", evaluator, " needs a seat that searches, and seat ",
                        String.valueOf(seat), " is ", SIM_MODE_HEURISTIC);
            }
        }
        return null;
    }

    /** Why -prune can't be used, or null if it can or wasn't asked for. */
    static String pruneOptionProblem(Map<String, List<String>> params) {
        List<String> values = params.get("prune");
        if (values == null) {
            return null;
        }
        if (!(List.of("on").equals(values) || List.of("off").equals(values))) {
            return "-prune takes on or off: " + values;
        }
        // -sim, which is what makes a seat search, can't go with -t either
        return params.containsKey("t") ? "-prune cannot be used with -t" : null;
    }

    /** Why -firstplayer can't be used, or null if it can or wasn't asked for. */
    static String firstPlayerOptionProblem(Map<String, List<String>> params) {
        List<String> values = params.get("firstplayer");
        if (values == null || List.of("random").equals(values) || List.of("loser").equals(values)) {
            return null;
        }
        return "-firstplayer takes random or loser: " + values;
    }

    /** True with -firstplayer random. Left out or loser, the loser of the last game goes first, as in stock Forge. */
    static boolean randomFirstPlayer(Map<String, List<String>> params) {
        return List.of("random").equals(params.get("firstplayer"));
    }

    /** On when a seat searches, so a search never sees what its seat couldn't know, unless -prune says otherwise. */
    static boolean pruneOn(Map<String, List<String>> params) {
        List<String> values = params.get("prune");
        return values == null ? anySeatSearches(params.get("sim")) : List.of("on").equals(values);
    }

    /** On for every forge sim run but a tournament, with or without records. Default play outside forge sim has none. */
    static boolean tripwireOn(Map<String, List<String>> params) {
        return !params.containsKey("t") && !List.of("off").equals(params.get("tripwire"));
    }

    private static boolean isRecordableSeed(List<String> values) {
        if (values == null || values.size() != 1) {
            return false;
        }
        try {
            return Long.parseLong(values.get(0)) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** True if any -sim value is a mode that searches. Null means the flag was left out. */
    static boolean anySeatSearches(List<String> simModes) {
        return simModes != null && (simModes.contains(SIM_MODE_HYBRID) || simModes.contains(SIM_MODE_FULL));
    }

    /** The AI options behind a -sim mode name, or null if there is no such mode. */
    static Set<AIOption> aiOptionsForSimMode(String mode) {
        switch (mode) {
            case SIM_MODE_HEURISTIC:
                return Collections.emptySet();
            case SIM_MODE_HYBRID:
                return Collections.singleton(AIOption.USE_HYBRID_SIMULATION);
            case SIM_MODE_FULL:
                return Collections.singleton(AIOption.USE_FULL_SIMULATION);
            default:
                return null;
        }
    }

    private static void argumentHelp() {
        System.out.println("Syntax: forge.exe sim -d <deck1[.dck]> ... <deckX[.dck]> -D [D] -n [N] -m [M] -t [T] -p [P] -f [F] -s [S] -a [A] -sim [SIM] -budget [B] -aisteps [STEPS] -record [DIR] -batch [ID] -turncap [CAP] -tripwire [on|off] -honest [on|off] -prune [on|off] -eval [EVAL] -firstplayer [random|loser] -mc [on|off] -q");
        System.out.println("\tsim - stands for simulation mode");
        System.out.println("\tdeck1 (or deck2,...,X) - constructed deck name or filename (has to be quoted when contains multiple words)");
        System.out.println("\tdeck is treated as file if it ends with a dot followed by three numbers or letters");
        System.out.println("\tD - absolute directory to load decks from");
        System.out.println("\tN - number of games, defaults to 1 (Ignores match setting)");
        System.out.println("\tM - Play full match of X games, typically 1,3,5 games. (Optional, overrides N)");
        System.out.println("\tT - Type of tournament to run with all provided decks (Bracket, RoundRobin, Swiss)");
        System.out.println("\tP - Amount of players per match (used only with Tournaments, defaults to 2)");
        System.out.println("\tF - format of games, defaults to constructed");
        System.out.println("\tS - RNG seed for simulation");
        System.out.println("\tA - AI profile per player, in the same order as the decks (e.g. -a Default Experimental)");
        System.out.println("\tSIM - Simulation mode per player, in the same order as the decks: heuristic, hybrid or full (e.g. -sim full heuristic). Players left out and runs without the flag stay heuristic. With the flag, result lines name each player's mode as [sim=mode]. Not for tournaments.");
        System.out.println("\tB - Search steps per AI decision. Replaces the wall-clock limit on AI decisions with a count, so that a run replays from its seed on a busy machine too. A full sim decision that runs out plays the best line it found, and passes if that is no better than passing. A heuristic seat has no search to run out.");
        System.out.println("\tSTEPS - AI steps per heuristic AI decision, one per check the AI makes in its loops. Replaces the wall-clock limit on AI decisions with a count, so that heuristic play replays from its seed on a busy machine too. A spell choice that runs out passes priority, an attack keeps the attackers chosen so far, and blocks keep the blocks made so far. A full sim spell choice is left to -budget.");
        System.out.println("\tDIR - Write one game record per game to this folder. Needs -batch, -s and two decks. Not for tournaments.");
        System.out.println("\tID - Batch id for the game records: letters, digits, _ and -.");
        System.out.println("\tCAP - With -record, the last turn a game may play before it stops as a draw, defaults to 50.");
        System.out.println("\ttripwire - Catch forge-ai reading a hand or library that isn't its own, defaults to on. Hits go in Tripwire lines before each result, and in the game record with -record. Not for tournaments.");
        System.out.println("\thonest - Have the AI keep to what a person in its seat could know: public cards, plus what it was shown, which it only remembers with -record. Defaults to off, which is the stock AI and its peeks. Not for tournaments.");
        System.out.println("	prune - Have the copies a searching seat plays its search on hold only what that seat could know of the other players' hidden cards: placeholders, and what it was shown. Defaults to on when -sim gives a seat hybrid or full, and to off otherwise. Not for tournaments.");
        System.out.println("\tEVAL - Evaluator per player for the search to score states with, in the same order as the decks: forge, random, passthrough or onnx:DIR (e.g. -eval random forge). forge is Forge's own and the default. random is a uniform draw from its own generator, seeded from -s and the seat. passthrough is Forge's own score through the evaluator seam. onnx:DIR is the learned model in the export bundle in folder DIR, under ONNX Runtime; each folder loads once, so seats on two folders play two models, and each such seat writes 'ONNX evaluator: seat N searches with DIR' to stderr. -Dforge.onnx.threads=N gives each bundle N threads, 1 by default. Any but forge needs the seat to be hybrid or full. With the flag, result lines name each player's evaluator as [eval=name], with onnx for onnx:DIR. Not for tournaments.");
        System.out.println("\tfirstplayer - Who takes the first turn of each game after the first, defaults to loser: the loser of the last game, or after a draw the first deck's player, as in stock Forge. random flips a coin for every game, as for the first one, so that the games of one run don't depend on each other's results. With random, prints 'First player: random each game' once.");
        System.out.println("\tc - Clock flag. Set the maximum time in seconds before calling the match a draw, defaults to 120.");
        System.out.println("\tmc - Flat Monte Carlo AI refinement for this run, on, max or off (default off in simulations; max = desktop strength). Prints timing statistics at the end when on.");
        System.out.println("\tq - Quiet flag. Output just the game result, not the entire game log.");
    }

    public static void simulateSingleMatch(final Match mc, int iGame, boolean outputGamelog) {
        final StopWatch sw = new StopWatch();
        sw.start();

        final Game g1 = mc.createGame();
        g1.setNoGUIUser();
        if (flatMonteCarlo != null) {
            g1.AI_FLAT_MONTE_CARLO = flatMonteCarlo;
            g1.AI_FLAT_MONTE_CARLO_MAX = Boolean.TRUE.equals(flatMonteCarloMax);
        }
        if (searchBudget > 0) {
            g1.AI_SEARCH_BUDGET = searchBudget;
            // no decision can outlast the game, so this leaves the budget as the only limit on one
            g1.AI_TIMEOUT = mc.getRules().getSimTimeout();
        }
        if (aiSteps > 0) {
            g1.AI_STEP_BUDGET = aiSteps;
            // as with -budget: the clock of the game is then the only wall clock a decision meets
            g1.AI_TIMEOUT = mc.getRules().getSimTimeout();
        }
        g1.AI_SIM_SEARCH = simSearch;
        g1.AI_HONEST_PLAY = honest;
        g1.AI_PRUNE_HIDDEN_INFO = prune;
        if (tripwire) {
            g1.HIDDEN_READ_WATCH = new HiddenReadWatch();
        }
        final GameRecorder recorder = recording == null ? null : GameRecorder.attach(g1, recording, 1 + iGame);
        final long expiriesBefore = AITimeoutTracker.deadlineExpiries();
        final long stopsBefore = AITimeoutTracker.budgetStops();
        final long drawsBefore = MyRandom.callCount();
        final long playedBefore = AITimeoutTracker.stopsPlayed();
        final long passedBefore = AITimeoutTracker.stopsPassed();
        final long withLandBefore = AITimeoutTracker.stopsWithLand();
        final long missedBefore = AITimeoutTracker.landDropsMissed();
        final long stepStopsBefore = AITimeoutTracker.stepStops();
        AITimeoutTracker.resetMostSteps();
        // will run match in the same thread
        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> {
                mc.startGame(g1);
                sw.stop();
            }, mc.getRules().getSimTimeout(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("Stopping slow match as draw");
            if (recorder != null) {
                recorder.stoppedBy(GameRecorder.Stop.CLOCK);
            }
        } catch (Exception | StackOverflowError e) {
            e.printStackTrace();
            if (recorder != null) {
                recorder.stoppedBy(GameRecorder.Stop.EXCEPTION);
            }
        } finally {
            if (sw.isStarted()) {
                sw.stop();
            }
            if (recorder != null) {
                recorder.beforeForcedEnd();
            }
            g1.setGameOver(GameEndReason.Draw);
        }
        if (recorder != null) {
            recorder.finish();
        }
        GameRecorder.tripwireLines(g1).forEach(System.out::println);
        if (recorder != null && recorder.stoppedAtTurnCap()) {
            System.out.println(TURN_CAP_LINE);
        }

        List<GameLogEntry> log;
        if (outputGamelog) {
            log = g1.getGameLog().getLogEntries(null);
        } else {
            log = g1.getGameLog().getLogEntries(GameLogEntryType.MATCH_RESULTS);
        }
        Collections.reverse(log);
        for (GameLogEntry l : log) {
            System.out.println(l);
        }

        if (printAiLimits) {
            // the step fields only with -aisteps, so that a run without it prints the line it always did
            final String steps = aiSteps > 0 ? String.format(", %d step stops, %d most steps",
                    AITimeoutTracker.stepStops() - stepStopsBefore, AITimeoutTracker.mostSteps()) : "";
            System.out.printf("AI limits: %d deadline expiries, %d budget stops, %d random draws, "
                            + "%d stops played, %d stops passed, %d stops with a land, %d land drops missed%s%n",
                    AITimeoutTracker.deadlineExpiries() - expiriesBefore, AITimeoutTracker.budgetStops() - stopsBefore,
                    MyRandom.callCount() - drawsBefore, AITimeoutTracker.stopsPlayed() - playedBefore,
                    AITimeoutTracker.stopsPassed() - passedBefore, AITimeoutTracker.stopsWithLand() - withLandBefore,
                    AITimeoutTracker.landDropsMissed() - missedBefore, steps);
        }

        // If both players life totals to 0 in a single turn, the game should end in a draw
        if (g1.getOutcome().isDraw()) {
            System.out.printf("\nGame Result: Game %d ended in a Draw! Took %d ms.%n", 1 + iGame, sw.getTime());
        } else {
            System.out.printf("\nGame Result: Game %d ended in %d ms. %s has won!\n%n", 1 + iGame, sw.getTime(), g1.getOutcome().getWinningLobbyPlayer().getName());
        }
    }

    private static void simulateTournament(Map<String, List<String>> params, GameRules rules, boolean outputGamelog) {
        String tournament = params.get("t").get(0);
        AbstractTournament tourney = null;
        int matchPlayers = params.containsKey("p") ? Integer.parseInt(params.get("p").get(0)) : 2;

        DeckGroup deckGroup = new DeckGroup("SimulatedTournament");
        List<TournamentPlayer> players = new ArrayList<>();
        int numPlayers = 0;
        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, rules.getGameType(), null);
                if (d == null) {
                    System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck, ", match cannot start"));
                    return;
                }

                deckGroup.addAiDeck(d);
                players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(d.getName(), 0), numPlayers));
                numPlayers++;
            }
        }

        if (params.containsKey("D")) {
            // Load decks from the specified directory
            String foldName = params.get("D").get(0);
            File folder = new File(foldName);
            if (!folder.isDirectory()) {
                System.out.println("Directory not found - " + foldName);
            } else {
                for (File deck : folder.listFiles((dir, name) -> name.endsWith(".dck"))) {
                    Deck d = DeckSerializer.fromFile(deck);
                    if (d == null) {
                        System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck.getName(), ", match cannot start"));
                        return;
                    }
                    deckGroup.addAiDeck(d);
                    players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(d.getName(), 0), numPlayers));
                    numPlayers++;
                }
            }
        }

        if (numPlayers == 0) {
            System.out.println("No decks/Players found. Please try again.");
        }

        if ("bracket".equalsIgnoreCase(tournament)) {
            tourney = new TournamentBracket(players, matchPlayers);
        } else if ("roundrobin".equalsIgnoreCase(tournament)) {
            tourney = new TournamentRoundRobin(players, matchPlayers);
        } else if ("swiss".equalsIgnoreCase(tournament)) {
            tourney = new TournamentSwiss(players, matchPlayers);
        }
        if (tourney == null) {
            System.out.println("Failed to initialize tournament, bailing out");
            return;
        }

        tourney.initializeTournament();

        String lastWinner = "";
        int curRound = 0;
        System.out.println(TextUtil.concatNoSpace("Starting a ", tournament, " tournament with ",
                String.valueOf(numPlayers), " players over ",
                String.valueOf(tourney.getTotalRounds()), " rounds"));
        while (!tourney.isTournamentOver()) {
            if (tourney.getActiveRound() != curRound) {
                if (curRound != 0) {
                    System.out.println(TextUtil.concatNoSpace("End Round - ", String.valueOf(curRound)));
                }
                curRound = tourney.getActiveRound();
                System.out.println();
                System.out.println(TextUtil.concatNoSpace("Round ", String.valueOf(curRound), " Pairings:"));

                for (TournamentPairing pairing : tourney.getActivePairings()) {
                    System.out.println(pairing.outputHeader());
                }
                System.out.println();
            }

            TournamentPairing pairing = tourney.getNextPairing();
            List<RegisteredPlayer> regPlayers = AbstractTournament.registerTournamentPlayers(pairing, deckGroup);

            StringBuilder sb = new StringBuilder();
            sb.append("Round ").append(tourney.getActiveRound()).append(" - ");
            sb.append(pairing.outputHeader());
            System.out.println(sb.toString());

            if (!pairing.isBye()) {
                Match mc = new Match(rules, regPlayers, "TourneyMatch");

                int exceptions = 0;
                int iGame = 0;
                while (!mc.isMatchOver()) {
                    // play games until the match ends
                    try {
                        simulateSingleMatch(mc, iGame, outputGamelog);
                        iGame++;
                    } catch (Exception e) {
                        exceptions++;
                        System.out.println(e.toString());
                        if (exceptions > 5) {
                            System.out.println("Exceeded number of exceptions thrown. Abandoning match...");
                            break;
                        } else {
                            System.out.println("Game threw exception. Abandoning game and continuing...");
                        }
                    }

                }
                LobbyPlayer winner = mc.getWinner().getPlayer();
                for (TournamentPlayer tp : pairing.getPairedPlayers()) {
                    if (winner.equals(tp.getPlayer())) {
                        pairing.setWinner(tp);
                        lastWinner = winner.getName();
                        System.out.println(TextUtil.concatNoSpace("Match Winner - ", lastWinner, "!"));
                        System.out.println();
                        break;
                    }
                }
            }

            tourney.reportMatchCompletion(pairing);
        }
        tourney.outputTournamentResults();
    }

    public static Match simulateOffthreadGame(List<Deck> decks, GameType format, int games) {
        return null;
    }

    private static Deck deckFromCommandLineParameter(String deckname, GameType type, String deckDir) {
        int dotpos = deckname.lastIndexOf('.');
        if (dotpos > 0 && dotpos == deckname.length() - 4) {
            String baseDir = deckDir != null ? deckDir : (type.equals(GameType.Commander) ?
                    ForgeConstants.DECK_COMMANDER_DIR : ForgeConstants.DECK_CONSTRUCTED_DIR);

            if (!baseDir.endsWith(File.separator)) {
                baseDir += File.separator;
            }

            File f = new File(baseDir + deckname);
            if (!f.exists()) {
                System.out.println("No deck found in " + baseDir);
            }

            return DeckSerializer.fromFile(f);
        }

        IStorage<Deck> deckStore = null;

        // Add other game types here...
        if (type.equals(GameType.Commander)) {
            deckStore = FModel.getDecks().getCommander();
        } else {
            deckStore = FModel.getDecks().getConstructed();
        }

        return deckStore.get(deckname);
    }

}
