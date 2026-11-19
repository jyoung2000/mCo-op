package dropin.core.merge;

import dropin.core.ledger.Deltas;
import dropin.core.manifest.RecordKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * What a copy that left the main world's lineage changed (DESIGN 4.10), as pure functions: the keys it changed
 * since the common ancestor, and which of them make up the branch that is preserved and merged later.
 */
public final class Branches {
    /**
     * One key of a branch.
     *
     * @param hash the record's hash on the branch, "" if the branch has no such record
     * @param base the record's hash at the common ancestor, "" if there was none
     */
    public record Change(String hash, String base, String content, String contentBase) {
        /** A key whose content hashes are its record hashes (anything but the region record of a chunk). */
        public Change(String hash, String base) {
            this(hash, base, hash, base);
        }

        /**
         * {@code content} and {@code contentBase} are the content hashes ({@link ChunkContent}) on the branch
         * and at the ancestor; {@code contentBase} is null if the copy did not know it, and the merge then
         * goes by the record hashes for this key.
         */
        public Change {
        }

        public boolean changed() {
            return !hash.equals(base);
        }
    }

    private Branches() {
    }

    /**
     * The keys changed by the checkpoints after {@code ancestorSeq} up to {@code headSeq}: the union of their
     * deltas, each key with the hash it had at the ancestor and the one it has now.
     *
     * @param deltas the delta of a checkpoint, or null if its file is gone
     * @return null if the deltas no longer reach the ancestor (a file is gone or was written without base
     *         hashes): the branch is too old to merge and is saved as a separate world instead
     */
    public static Map<String, Change> changedSince(long ancestorSeq, long headSeq, LongFunction<Deltas.Delta> deltas) {
        Map<String, Change> changed = new LinkedHashMap<>();
        for (long seq = ancestorSeq + 1; seq <= headSeq; seq++) {
            Deltas.Delta delta = deltas.apply(seq);
            if (delta == null || delta.base() == null) {
                return null;
            }
            for (Map.Entry<String, String> entry : delta.changed().entrySet()) {
                Change earlier = changed.get(entry.getKey());
                String key = entry.getKey();
                String base = earlier != null ? earlier.base() : delta.base().getOrDefault(key, "");
                String contentBase = earlier != null ? earlier.contentBase()
                        : delta.contentBase().containsKey(key) ? delta.contentBase().get(key)
                        : ChunkContent.applies(key) ? null : base;
                // The content hash on the branch is filled in from the record itself when the branch is written.
                changed.put(key, new Change(entry.getValue(), base, entry.getValue(), contentBase));
            }
        }
        changed.values().removeIf(change -> !change.changed());
        return changed;
    }

    /** World-level files (DESIGN 4.10): the main world's version always stays, so a branch does not carry them. */
    public static boolean worldLevel(String key) {
        if (!RecordKey.isFile(key)) {
            return false;
        }
        String path = RecordKey.filePath(key);
        if (path.equals("level.dat") || path.startsWith("data/")) {
            return true;
        }
        // 26.x keeps a dimension's saved data in dimensions/<namespace>/<name>/data/.
        return path.startsWith("dimensions/") && path.contains("/data/");
    }

    /**
     * The player a file record belongs to ({@code players/<kind>/<uuid>.<ext>}, DESIGN 4.8), or null if it is
     * not a player's own file.
     */
    public static String playerOf(String key) {
        if (!RecordKey.isFile(key)) {
            return null;
        }
        String[] segments = RecordKey.filePath(key).split("/", -1);
        if (segments.length != 3 || !segments[0].equals("players")) {
            return null;
        }
        int dot = segments[2].indexOf('.');
        String name = dot < 0 ? segments[2] : segments[2].substring(0, dot);
        return name.length() == 36 ? name : null;
    }

    /** The region, entities and POI record keys of the chunk a chunk key belongs to. */
    public static List<String> chunkKeys(String chunkKey) {
        RecordKey.Chunk chunk = RecordKey.parseChunk(chunkKey);
        List<String> keys = new ArrayList<>(3);
        for (String kind : List.of("region", "entities", "poi")) {
            keys.add(RecordKey.chunk(chunk.dim(), kind, chunk.cx(), chunk.cz()));
        }
        return keys;
    }

    /**
     * The keys a branch is preserved with: what it changed, without world-level files, plus the other records
     * of every changed chunk. Those are listed unchanged ({@code hash} = {@code base} = what the copy holds),
     * so the merge can tell whether the main world changed any of a chunk's three records.
     *
     * @param holds the hash of a record in the copy the branch is made from, null if it has none
     */
    public static Map<String, Change> bundle(Map<String, Change> changed, Function<String, String> holds) {
        return bundle(changed, holds, holds);
    }

    /**
     * @param holdsContent the content hash ({@link ChunkContent}) of a record in that copy, null if it has none
     */
    public static Map<String, Change> bundle(Map<String, Change> changed, Function<String, String> holds,
                                             Function<String, String> holdsContent) {
        Map<String, Change> keys = new LinkedHashMap<>();
        for (Map.Entry<String, Change> entry : changed.entrySet()) {
            String key = entry.getKey();
            if (worldLevel(key) || !entry.getValue().changed() || !(RecordKey.isChunk(key) || RecordKey.isFile(key))) {
                continue;
            }
            keys.put(key, entry.getValue());
        }
        for (String key : new ArrayList<>(keys.keySet())) {
            if (RecordKey.isChunk(key)) {
                for (String sibling : chunkKeys(key)) {
                    if (!keys.containsKey(sibling) && !changed.containsKey(sibling)) {
                        String hash = holds.apply(sibling);
                        String content = hash == null ? null : holdsContent.apply(sibling);
                        keys.put(sibling, new Change(hash == null ? "" : hash, hash == null ? "" : hash,
                                content == null ? "" : content, content == null ? "" : content));
                    }
                }
            }
        }
        return keys;
    }
}
