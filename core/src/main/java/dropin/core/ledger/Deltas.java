package dropin.core.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code dropin/deltas/<seq>.bin}: the keys changed at one checkpoint (DESIGN 4.2), one
 * {@code key<TAB>hash} line each, with an empty hash for a deleted record.
 */
public final class Deltas {
    private Deltas() {
    }

    public static Path file(Path deltasDir, long seq) {
        return deltasDir.resolve(seq + ".bin");
    }

    public static void write(Path deltasDir, long seq, Map<String, String> changed) throws IOException {
        Files.createDirectories(deltasDir);
        StringBuilder text = new StringBuilder();
        changed.forEach((key, hash) -> text.append(key).append('\t').append(hash).append('\n'));
        Files.writeString(file(deltasDir, seq), text, StandardCharsets.UTF_8);
    }

    public static Map<String, String> read(Path deltasDir, long seq) throws IOException {
        Map<String, String> changed = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file(deltasDir, seq), StandardCharsets.UTF_8)) {
            int tab = line.lastIndexOf('\t');
            if (tab > 0) {
                changed.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
        return changed;
    }
}
