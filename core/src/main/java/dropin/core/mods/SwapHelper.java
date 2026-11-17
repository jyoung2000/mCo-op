package dropin.core.mods;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The detached mod-swap helper (DESIGN 4.14). It runs in its own small JVM after the game has been told to
 * quit: it waits for the game process to exit, then makes {@code mods/} match the plan. Files that are not in
 * the plan are moved to {@code mods/.dropin-disabled/<timestamp>/}, never deleted; plan files are copied in
 * from the content-addressed store after their SHA-512 has been checked again.
 *
 * <p>This class is copied alone to a temp folder and started with {@code java -cp <temp> dropin.core.mods.SwapHelper
 * <plan file>}, so it must not use any other class of the mod or any library: no nested classes, no Gson.
 *
 * <p>Plan file ({@code config/dropin/modswap.json}), written by {@link SwapPlan}:
 * <pre>
 * {"format":1, "gamePid":1234, "modsDir":"...", "storeDir":"...", "disabledDir":"...",
 *  "install":[{"name":"x.jar","sha512":"..."}], "keep":["minimap.jar"]}
 * </pre>
 * The result goes to {@code modswap.result.json} beside the plan; the plan is deleted on success.
 */
public final class SwapHelper {
    public static final String RESULT_FILE = "modswap.result.json";
    private static final long WAIT_FOR_EXIT_SECONDS = 600;
    private static final long RETRY_MILLIS = 60_000;

    private SwapHelper() {}

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: SwapHelper <plan file>");
            System.exit(2);
        }
        Path planFile = Path.of(args[0]).toAbsolutePath();
        Path resultFile = planFile.resolveSibling(RESULT_FILE);
        List<String> moved = new ArrayList<>();
        List<String> installed = new ArrayList<>();
        String error = null;
        try {
            Map<String, Object> plan = asObject(parseJson(Files.readString(planFile, StandardCharsets.UTF_8)));
            long pid = ((Number) plan.get("gamePid")).longValue();
            Optional<ProcessHandle> game = ProcessHandle.of(pid);
            if (game.isPresent()) {
                try {
                    game.get().onExit().get(WAIT_FOR_EXIT_SECONDS, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new IOException("the game (pid " + pid + ") is still running; nothing was changed");
                }
            }
            // The process is gone, but Windows can keep its file handles for a moment: retry for a while.
            long deadline = System.currentTimeMillis() + RETRY_MILLIS;
            while (true) {
                try {
                    apply(plan, moved, installed);
                    break;
                } catch (IOException e) {
                    if (System.currentTimeMillis() >= deadline) {
                        throw e;
                    }
                    Thread.sleep(500);
                }
            }
            Files.deleteIfExists(planFile);
        } catch (Throwable t) {
            error = t.toString();
        }
        try {
            Files.writeString(resultFile, resultJson(error, moved, installed), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("could not write " + resultFile + ": " + e);
        }
        if (error != null) {
            System.err.println(error);
            System.exit(1);
        }
    }

    /**
     * Makes the mods folder match the plan. Safe to run again after a failure half-way: what is already in
     * place is left alone. {@code moved} and {@code installed} receive the file names that were handled.
     */
    public static void apply(Map<String, Object> plan, List<String> moved, List<String> installed) throws IOException {
        Path modsDir = Path.of((String) plan.get("modsDir"));
        Path storeDir = Path.of((String) plan.get("storeDir"));
        Path disabledDir = Path.of((String) plan.get("disabledDir"));
        Map<String, String> install = new LinkedHashMap<>();
        for (Object entry : asList(plan.get("install"))) {
            Map<String, Object> file = asObject(entry);
            String name = checkedName((String) file.get("name"));
            String sha512 = ((String) file.get("sha512")).toLowerCase(Locale.ROOT);
            if (!sha512.matches("[0-9a-f]{128}")) {
                throw new IOException("not a SHA-512: " + sha512);
            }
            install.put(name, sha512);
        }
        List<String> keep = new ArrayList<>();
        for (Object name : asList(plan.get("keep"))) {
            keep.add(((String) name).toLowerCase(Locale.ROOT));
        }
        // Check the store first, so that nothing is moved when a file to install is missing or damaged.
        for (Map.Entry<String, String> file : install.entrySet()) {
            Path stored = storeDir.resolve(file.getValue() + ".jar");
            if (!Files.isRegularFile(stored) || !sha512(stored).equals(file.getValue())) {
                throw new IOException("the stored copy of " + file.getKey() + " is missing or damaged");
            }
        }
        Files.createDirectories(modsDir);
        List<Path> present;
        try (Stream<Path> files = Files.list(modsDir)) {
            present = files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().toList();
        }
        for (Path file : present) {
            String name = file.getFileName().toString();
            if (keep.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String wanted = install.get(name);
            if (wanted != null && sha512(file).equals(wanted)) {
                continue;
            }
            Files.createDirectories(disabledDir);
            Path target = disabledDir.resolve(name);
            for (int n = 1; Files.exists(target); n++) {
                target = disabledDir.resolve(n + "-" + name);
            }
            Files.move(file, target);
            moved.add(name);
        }
        for (Map.Entry<String, String> file : install.entrySet()) {
            Path target = modsDir.resolve(file.getKey());
            if (Files.exists(target)) {
                continue;
            }
            Path temp = modsDir.resolve(file.getKey() + ".dropin-tmp");
            Files.copy(storeDir.resolve(file.getValue() + ".jar"), temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target);
            installed.add(file.getKey());
        }
    }

    /** A plain {@code *.jar} file name: no folder, no {@code ..}, nothing a file system treats specially. */
    public static String checkedName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 200 || !name.toLowerCase(Locale.ROOT).endsWith(".jar")
                || name.startsWith(".") || name.contains("..")
                || !name.chars().allMatch(c -> c >= 0x20 && c != 0x7f && "/\\:*?\"<>|".indexOf(c) < 0)) {
            throw new IOException("not a safe jar file name: " + name);
        }
        return name;
    }

    public static String sha512(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String resultJson(String error, List<String> moved, List<String> installed) {
        StringBuilder out = new StringBuilder("{\"ok\":").append(error == null);
        out.append(",\"moved\":").append(jsonArray(moved));
        out.append(",\"installed\":").append(jsonArray(installed));
        if (error != null) {
            out.append(",\"error\":").append(jsonString(error));
        }
        return out.append('}').toString();
    }

    private static String jsonArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            out.append(i == 0 ? "" : ",").append(jsonString(values.get(i)));
        }
        return out.append(']').toString();
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    // ---- a small JSON reader: objects become Map, arrays List, numbers Long or Double ----------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) throws IOException {
        if (!(value instanceof Map)) {
            throw new IOException("expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) throws IOException {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List)) {
            throw new IOException("expected a JSON array");
        }
        return (List<Object>) value;
    }

    public static Object parseJson(String text) throws IOException {
        int[] pos = {0};
        Object value = readValue(text, pos);
        skipSpace(text, pos);
        if (pos[0] != text.length()) {
            throw new IOException("unexpected text after the JSON value at " + pos[0]);
        }
        return value;
    }

    private static void skipSpace(String text, int[] pos) {
        while (pos[0] < text.length() && Character.isWhitespace(text.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    private static Object readValue(String text, int[] pos) throws IOException {
        skipSpace(text, pos);
        if (pos[0] >= text.length()) {
            throw new IOException("unexpected end of JSON");
        }
        char c = text.charAt(pos[0]);
        if (c == '{') {
            pos[0]++;
            Map<String, Object> object = new LinkedHashMap<>();
            skipSpace(text, pos);
            if (peek(text, pos) == '}') {
                pos[0]++;
                return object;
            }
            while (true) {
                skipSpace(text, pos);
                String key = readString(text, pos);
                skipSpace(text, pos);
                expect(text, pos, ':');
                object.put(key, readValue(text, pos));
                skipSpace(text, pos);
                if (peek(text, pos) == ',') {
                    pos[0]++;
                } else {
                    expect(text, pos, '}');
                    return object;
                }
            }
        }
        if (c == '[') {
            pos[0]++;
            List<Object> list = new ArrayList<>();
            skipSpace(text, pos);
            if (peek(text, pos) == ']') {
                pos[0]++;
                return list;
            }
            while (true) {
                list.add(readValue(text, pos));
                skipSpace(text, pos);
                if (peek(text, pos) == ',') {
                    pos[0]++;
                } else {
                    expect(text, pos, ']');
                    return list;
                }
            }
        }
        if (c == '"') {
            return readString(text, pos);
        }
        int start = pos[0];
        while (pos[0] < text.length() && ",]} \t\r\n".indexOf(text.charAt(pos[0])) < 0) {
            pos[0]++;
        }
        String word = text.substring(start, pos[0]);
        switch (word) {
            case "true":
                return Boolean.TRUE;
            case "false":
                return Boolean.FALSE;
            case "null":
                return null;
            default:
                try {
                    return word.matches("-?\\d+") ? (Object) Long.valueOf(word) : (Object) Double.valueOf(word);
                } catch (NumberFormatException e) {
                    throw new IOException("bad JSON value '" + word + "' at " + start);
                }
        }
    }

    private static char peek(String text, int[] pos) throws IOException {
        if (pos[0] >= text.length()) {
            throw new IOException("unexpected end of JSON");
        }
        return text.charAt(pos[0]);
    }

    private static void expect(String text, int[] pos, char wanted) throws IOException {
        if (peek(text, pos) != wanted) {
            throw new IOException("expected '" + wanted + "' at " + pos[0]);
        }
        pos[0]++;
    }

    private static String readString(String text, int[] pos) throws IOException {
        expect(text, pos, '"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = peek(text, pos);
            pos[0]++;
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = peek(text, pos);
            pos[0]++;
            switch (escaped) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u':
                    if (pos[0] + 4 > text.length()) {
                        throw new IOException("bad \\u escape");
                    }
                    out.append((char) Integer.parseInt(text.substring(pos[0], pos[0] + 4), 16));
                    pos[0] += 4;
                    break;
                default: out.append(escaped);
            }
        }
    }
}
