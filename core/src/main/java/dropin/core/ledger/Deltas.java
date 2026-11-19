package dropin.core.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code dropin/deltas/<seq>.bin}: the keys changed at one checkpoint (DESIGN 4.2).
 * <p>
 * Since M5 the file starts with the line {@code #2} and has one {@code key<TAB>hash<TAB>base} line per key:
 * {@code hash} is the record's hash after the checkpoint and {@code base} the one before it, each empty for
 * "no such record". The base is what a merge compares the main world with (DESIGN 4.10). Files written
 * before M5 have no first line and {@code key<TAB>hash} lines; they still read, but without a base.
 */
public final class Deltas {
    private static final String HEADER = "#2";

    /**
     * One checkpoint's changes.
     *
     * @param changed key to the hash after the checkpoint, "" for a deleted record
     * @param base    key to the hash before the checkpoint, "" if there was no record; null for a file written
     *                before M5, which does not say
     */
    public record Delta(Map<String, String> changed, Map<String, String> base, Map<String, String> contentBase) {
        public Delta(Map<String, String> changed, Map<String, String> base) {
            this(changed, base, Map.of());
        }

    }

    private Deltas() {
    }

    public static Path file(Path deltasDir, long seq) {
        return deltasDir.resolve(seq + ".bin");
    }

    /** The format without a base (before M5). A branch cannot be made from such a file. */
    public static void write(Path deltasDir, long seq, Map<String, String> changed) throws IOException {
        Files.createDirectories(deltasDir);
        StringBuilder text = new StringBuilder();
        changed.forEach((key, hash) -> text.append(key).append('\t').append(hash).append('\n'));
        Files.writeString(file(deltasDir, seq), text, StandardCharsets.UTF_8);
    }

    /** @param base the hash each changed key had before; a key missing from it counts as "no record before" */
    public static void write(Path deltasDir, long seq, Map<String, String> changed, Map<String, String> base) throws IOException {
        Files.createDirectories(deltasDir);
        write(deltasDir, seq, changed, base, Map.of());
    }

    /**
     * As {@link #write(Path, long, Map, Map)}, with a fourth column for the keys in {@code contentBase}: the
     * content hash (dropin.core.merge.ChunkContent) the record had before this checkpoint, "" if there was none.
     */
    public static void write(Path deltasDir, long seq, Map<String, String> changed, Map<String, String> base,
                             Map<String, String> contentBase) throws IOException {
        Files.createDirectories(deltasDir);
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        changed.forEach((key, hash) -> {
            text.append(key).append('\t').append(hash).append('\t').append(base.getOrDefault(key, ""));
            if (contentBase.containsKey(key)) {
                text.append('\t').append(contentBase.get(key));
            }
            text.append('\n');
        });
        Files.writeString(file(deltasDir, seq), text, StandardCharsets.UTF_8);
    }

    public static Map<String, String> read(Path deltasDir, long seq) throws IOException {
        return readFull(deltasDir, seq).changed();
    }

    public static Delta readFull(Path deltasDir, long seq) throws IOException {
        List<String> lines = Files.readAllLines(file(deltasDir, seq), StandardCharsets.UTF_8);
        boolean withBase = !lines.isEmpty() && lines.get(0).equals(HEADER);
        Map<String, String> changed = new LinkedHashMap<>();
        Map<String, String> base = withBase ? new LinkedHashMap<>() : null;
        Map<String, String> contentBase = new LinkedHashMap<>();
        for (String line : withBase ? lines.subList(1, lines.size()) : lines) {
            int last = line.lastIndexOf('\t');
            if (last <= 0) {
                continue;
            }
            if (!withBase) {
                changed.put(line.substring(0, last), line.substring(last + 1));
                continue;
            }
            String[] columns = line.split("\t", -1);
            if (columns.length >= 3 && !columns[0].isEmpty()) {
                changed.put(columns[0], columns[1]);
                base.put(columns[0], columns[2]);
                if (columns.length >= 4) {
                    contentBase.put(columns[0], columns[3]);
                }
            }
        }
        return new Delta(changed, base, contentBase);
    }

    /**
     * Deletes the delta files last written before {@code olderThanMillis} (DESIGN 4.10: kept 30 days by default).
     * A branch whose deltas no longer reach the common ancestor cannot be merged any more.
     *
     * @return how many files were deleted
     */
    public static int prune(Path deltasDir, long olderThanMillis) throws IOException {
        if (!Files.isDirectory(deltasDir)) {
            return 0;
        }
        int deleted = 0;
        List<Path> files;
        try (Stream<Path> list = Files.list(deltasDir)) {
            files = list.filter(file -> file.getFileName().toString().endsWith(".bin")).toList();
        }
        FileTime limit = FileTime.fromMillis(olderThanMillis);
        for (Path file : files) {
            if (Files.getLastModifiedTime(file).compareTo(limit) < 0) {
                Files.delete(file);
                deleted++;
            }
        }
        return deleted;
    }
}
