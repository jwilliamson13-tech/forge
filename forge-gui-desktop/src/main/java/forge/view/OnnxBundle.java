package forge.view;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * An export bundle from {@code python -m setmodel export}, read by path: the contract, the card-feature table and the
 * model file. The Python repo's {@code docs/tensor-contract.md} describes it.
 * <p>
 * Loading refuses a bundle whose contract this code doesn't implement: another version, or the same version with an
 * input, a field order or a zone list other than the ones {@link SnapshotEncoder} builds. A refusal is a
 * {@link BundleException} that names what differs, so a model never runs on inputs it wasn't trained on.
 */
public final class OnnxBundle {
    public static final int CONTRACT_VERSION = 1;
    public static final String MODEL = "model.onnx";
    public static final String CONTRACT = "contract.json";
    public static final String CARD_TABLE = "card_features.jsonl.gz";
    public static final String OUTPUT = "win_probability";

    /** Inputs in the contract's order, as name, dtype and shape letters. */
    static final List<List<String>> INPUTS = List.of(
            List.of("scoreboard", "float32", "S"),
            List.of("token_ids", "int64", "N A T"),
            List.of("token_mask", "float32", "N A T"),
            List.of("numbers", "float32", "N A K"),
            List.of("number_mask", "float32", "N A K"),
            List.of("live", "float32", "N L"),
            List.of("zone", "int64", "N"));
    static final List<String> SCOREBOARD_FIELDS = List.of("me_life", "opp_life", "me_poison", "opp_poison", "me_library",
            "opp_library", "opp_hand", "turn", "me_active", "me_on_play");
    static final List<String> LIVE_FIELDS = List.of("power", "toughness", "is_creature", "tapped", "summoning_sick",
            "mine", "face_down", "hidden", "damage", "attacking", "blocking", "blocked", "attached", "counters_p1p1",
            "counters_m1m1", "counters_loyalty", "counters_other", "stack_ability");
    /** The first seven are the game record's zone keys. A stack item is the eighth. */
    static final List<String> ZONES = List.of("battlefield", "me_hand", "me_graveyard", "opp_graveyard", "me_exile",
            "opp_exile", "opp_revealed", "stack");
    static final Map<String, String> SLOTS = Map.of("Original", "front", "EmptyRoom", "empty_room", "any other face", "back");

    /** A bundle this code can't use. */
    public static final class BundleException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        BundleException(final String message) {
            super(message);
        }
    }

    /**
     * One card's precomputed rows from the table: per row, its token ids, and its numeric keys with their values.
     * Rows and tokens are already within the caps.
     */
    record CardFeatures(int[][] tokens, int[][] keys, float[][] values) { }

    private final Path folder;
    private final JsonObject contract;
    private final int abilities;
    private final int tokens;
    private final int keys;
    private final Map<String, CardFeatures> byNameAndSlot;
    private final Map<String, CardFeatures> firstByName;

    private OnnxBundle(final Path folder, final JsonObject contract, final Map<String, CardFeatures> byNameAndSlot,
                       final Map<String, CardFeatures> firstByName) {
        this.folder = folder;
        this.contract = contract;
        final JsonObject sizes = contract.getAsJsonObject("sizes");
        this.abilities = sizes.get("A").getAsInt();
        this.tokens = sizes.get("T").getAsInt();
        this.keys = sizes.get("K").getAsInt();
        this.byNameAndSlot = byNameAndSlot;
        this.firstByName = firstByName;
    }

    /** Reads the bundle in {@code folder}. Throws {@link BundleException} if its contract isn't the one implemented here. */
    public static OnnxBundle load(final Path folder) throws IOException {
        final Path contractFile = folder.resolve(CONTRACT);
        if (!Files.isRegularFile(contractFile) || !Files.isRegularFile(folder.resolve(MODEL))
                || !Files.isRegularFile(folder.resolve(CARD_TABLE))) {
            throw new BundleException(folder + " is not an export bundle: it needs " + CONTRACT + ", " + MODEL + " and " + CARD_TABLE);
        }
        final JsonObject contract;
        try {
            contract = JsonParser.parseString(Files.readString(contractFile, StandardCharsets.UTF_8)).getAsJsonObject();
            checkContract(contract);
        } catch (final BundleException e) {
            throw e;
        } catch (final RuntimeException e) {
            throw new BundleException(contractFile + " is not a tensor contract this Forge can read: " + e);
        }
        final JsonObject sizes = contract.getAsJsonObject("sizes");
        final Map<String, CardFeatures> byNameAndSlot = new HashMap<>();
        final Map<String, CardFeatures> firstByName = new HashMap<>();
        readTable(folder.resolve(CARD_TABLE), sizes.get("A").getAsInt(), sizes.get("T").getAsInt(), sizes.get("K").getAsInt(),
                byNameAndSlot, firstByName);
        return new OnnxBundle(folder, contract, byNameAndSlot, firstByName);
    }

    static void checkContract(final JsonObject contract) {
        final JsonElement version = contract.get("version");
        if (version == null || !version.isJsonPrimitive() || version.getAsInt() != CONTRACT_VERSION) {
            throw new BundleException("tensor contract version " + version + ", but this Forge implements version " + CONTRACT_VERSION);
        }
        final JsonObject sizes = contract.getAsJsonObject("sizes");
        for (final String letter : List.of("A", "T", "K", "L", "S")) {
            if (sizes == null || !sizes.has(letter) || !sizes.get(letter).getAsJsonPrimitive().isNumber() || sizes.get(letter).getAsInt() < 1) {
                throw new BundleException("contract size " + letter + " is missing or not a positive number");
            }
        }
        expect("size L", LIVE_FIELDS.size(), sizes.get("L").getAsInt());
        expect("size S", SCOREBOARD_FIELDS.size(), sizes.get("S").getAsInt());
        final List<List<String>> inputs = new ArrayList<>();
        for (final JsonElement element : contract.getAsJsonArray("inputs")) {
            final JsonObject input = element.getAsJsonObject();
            inputs.add(List.of(input.get("name").getAsString(), input.get("dtype").getAsString(),
                    String.join(" ", strings(input.getAsJsonArray("shape")))));
        }
        expect("inputs", INPUTS, inputs);
        final JsonObject output = contract.getAsJsonObject("output");
        expect("output", List.of(OUTPUT, "float32", "1"), List.of(output.get("name").getAsString(),
                output.get("dtype").getAsString(), String.join(" ", strings(output.getAsJsonArray("shape")))));
        expect("scoreboard_fields", SCOREBOARD_FIELDS, strings(contract.getAsJsonArray("scoreboard_fields")));
        expect("live_fields", LIVE_FIELDS, strings(contract.getAsJsonArray("live_fields")));
        expect("zones", ZONES, strings(contract.getAsJsonArray("zones")));
        final JsonObject table = contract.getAsJsonObject("card_table");
        expect("card_table file", CARD_TABLE, table.get("file").getAsString());
        final Map<String, String> slots = new LinkedHashMap<>();
        table.getAsJsonObject("slots").entrySet().forEach(e -> slots.put(e.getKey(), e.getValue().getAsString()));
        expect("card_table slots", SLOTS, slots);
    }

    private static void expect(final String what, final Object implemented, final Object found) {
        if (!implemented.equals(found)) {
            throw new BundleException("contract " + what + " is " + found + ", but this Forge implements " + implemented);
        }
    }

    private static List<String> strings(final JsonArray array) {
        final List<String> out = new ArrayList<>();
        array.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private static void readTable(final Path file, final int abilities, final int tokens, final int keys,
                                  final Map<String, CardFeatures> byNameAndSlot, final Map<String, CardFeatures> firstByName) throws IOException {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = lines.readLine()) != null) {
                number++;
                if (line.isBlank()) {
                    continue;
                }
                final JsonObject entry = JsonParser.parseString(line).getAsJsonObject();
                final String name = entry.get("name").getAsString();
                final CardFeatures features = features(entry, abilities, tokens, keys, file + " line " + number);
                byNameAndSlot.put(key(name, entry.get("slot").getAsString()), features);
                // the file is in name and slot order, so this is the entry the Python export check falls back to
                firstByName.putIfAbsent(name, features);
            }
        }
    }

    private static CardFeatures features(final JsonObject entry, final int abilities, final int tokens, final int keys, final String where) {
        final JsonArray tokenRows = entry.getAsJsonArray("tokens");
        final JsonArray numberRows = entry.getAsJsonArray("numbers");
        if (tokenRows.size() > abilities || numberRows.size() > abilities) {
            throw new BundleException(where + ": more rows than the cap A = " + abilities);
        }
        final int[][] tokenIds = new int[tokenRows.size()][];
        for (int a = 0; a < tokenIds.length; a++) {
            final JsonArray row = tokenRows.get(a).getAsJsonArray();
            if (row.size() > tokens) {
                throw new BundleException(where + ": more tokens in a row than the cap T = " + tokens);
            }
            tokenIds[a] = new int[row.size()];
            for (int t = 0; t < row.size(); t++) {
                tokenIds[a][t] = row.get(t).getAsInt();
            }
        }
        final int[][] keyIds = new int[numberRows.size()][];
        final float[][] values = new float[numberRows.size()][];
        for (int a = 0; a < keyIds.length; a++) {
            final JsonArray row = numberRows.get(a).getAsJsonArray();
            keyIds[a] = new int[row.size()];
            values[a] = new float[row.size()];
            for (int j = 0; j < row.size(); j++) {
                final JsonArray pair = row.get(j).getAsJsonArray();
                keyIds[a][j] = pair.get(0).getAsInt();
                values[a][j] = pair.get(1).getAsFloat();
                if (keyIds[a][j] < 0 || keyIds[a][j] >= keys) {
                    throw new BundleException(where + ": numeric key " + keyIds[a][j] + " is outside K = " + keys);
                }
            }
        }
        return new CardFeatures(tokenIds, keyIds, values);
    }

    private static String key(final String name, final String slot) {
        return name + '\0' + slot;
    }

    /** The slot a record's face looks up, by the contract's rule. */
    static String slot(final String face) {
        return switch (face) {
            case "Original" -> "front";
            case "EmptyRoom" -> "empty_room";
            default -> "back";
        };
    }

    /** The contract's lookup: (name, slot), else the name under any slot, else null for a card the table lacks. */
    CardFeatures lookup(final String name, final String face) {
        final CardFeatures exact = byNameAndSlot.get(key(name, slot(face)));
        return exact != null ? exact : firstByName.get(name);
    }

    public Path folder() {
        return folder;
    }

    public Path modelFile() {
        return folder.resolve(MODEL);
    }

    /** The contract's {@code bundle.generation}, or -1 if it has none. */
    public int generation() {
        final JsonObject bundle = contract.getAsJsonObject("bundle");
        return bundle != null && bundle.has("generation") ? bundle.get("generation").getAsInt() : -1;
    }

    public int abilities() {
        return abilities;
    }

    public int tokens() {
        return tokens;
    }

    public int keys() {
        return keys;
    }

    public int cardEntries() {
        return byNameAndSlot.size();
    }
}
