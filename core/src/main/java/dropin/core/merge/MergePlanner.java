package dropin.core.merge;

import dropin.core.manifest.RecordKey;
import dropin.core.merge.Branches.Change;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The three-way merge of one preserved branch into the main world (DESIGN 4.10), as a pure function. For
 * every key the branch changed since the common ancestor:
 * <ul>
 * <li>the main world did not change it since then: the branch's record is applied;</li>
 * <li>both changed it: the main world's record stays, the branch's record is backed up, and it is counted;</li>
 * <li>the three records of one chunk (region, entities, POI) are decided together: if the main world changed
 * any of them, all three stay;</li>
 * <li>a player's own files are decided together in the same way, except the files of the player who played
 * the branch, which are always taken from it;</li>
 * <li>world-level files always stay as the main world has them.</li>
 * </ul>
 * A record the main world already holds exactly as the branch has it is neither applied nor a conflict, so a
 * merge that was cut short can simply be planned again.
 */
public final class MergePlanner {
    /**
     * @param apply         keys whose branch record replaces the main world's (a branch hash of "" deletes it)
     * @param conflicts     keys whose branch record is saved under {@code backups/conflicts/}; the main world's stays
     * @param chunksApplied chunks taken from the branch
     * @param chunksKept    chunks changed on both sides, kept from the main world
     * @param filesApplied  file records taken from the branch
     * @param filesKept     file records changed on both sides, kept from the main world
     */
    public record Plan(List<String> apply, List<String> conflicts, int chunksApplied, int chunksKept,
                       int filesApplied, int filesKept) {
        public boolean isEmpty() {
            return apply.isEmpty() && conflicts.isEmpty();
        }
    }

    private MergePlanner() {
    }

    /**
     * @param branch  the keys of the branch (see {@link Branches#bundle})
     * @param mainNow the hash of a record in the main world right now, null or "" if it has none
     */
    public static Plan plan(Map<String, Change> branch, Function<String, String> mainNow) {
        return plan(branch, mainNow, mainNow);
    }

    /**
     * @param mainContent the content hash ({@link ChunkContent}) of a record in the main world right now, null
     *                    or "" if it has none. A record that differs from the ancestor's only in what the
     *                    content hash leaves out counts as unchanged, on either side.
     */
    public static Plan plan(Map<String, Change> branch, Function<String, String> mainNow, Function<String, String> mainContent) {
        return plan(branch, mainNow, mainContent, null);
    }

    /**
     * @param owner the UUID of the player who played the branch (its ledger entry's host), or null. That
     *              player's own files are taken from the branch even if the main world changed them too: they
     *              join the main world before the merge runs, so the main world's copy of their files holds
     *              only that short visit, while the branch holds the offline session. The main world's copy
     *              is replaced like any applied record (and so backed up under {@code backups/merged/}).
     */
    public static Plan plan(Map<String, Change> branch, Function<String, String> mainNow, Function<String, String> mainContent,
                            String owner) {
        String ownerGroup = owner == null || owner.isEmpty() ? null : "p|" + owner;
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String key : branch.keySet()) {
            if (Branches.worldLevel(key)) {
                continue;
            }
            groups.computeIfAbsent(group(key), id -> new ArrayList<>()).add(key);
        }
        List<String> apply = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        int chunksApplied = 0;
        int chunksKept = 0;
        int filesApplied = 0;
        int filesKept = 0;
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            boolean chunk = group.getKey().startsWith("c|");
            boolean mainChanged = false;
            List<String> differ = new ArrayList<>();
            for (String key : group.getValue()) {
                Change change = branch.get(key);
                String main = mainNow.apply(key);
                main = main == null ? "" : main;
                if (main.equals(change.hash())) {
                    continue;
                }
                // By content where the branch knows the content at the ancestor, by record hash otherwise.
                boolean byContent = change.contentBase() != null;
                String base = byContent ? change.contentBase() : change.base();
                String theirs = byContent ? change.content() : change.hash();
                if (byContent) {
                    main = mainContent.apply(key);
                    main = main == null ? "" : main;
                    if (main.equals(theirs)) {
                        continue;
                    }
                }
                if (!main.equals(base)) {
                    mainChanged = true;
                }
                if (!theirs.equals(base)) {
                    differ.add(key);
                }
            }
            if (differ.isEmpty()) {
                continue;
            }
            if (mainChanged && !group.getKey().equals(ownerGroup)) {
                conflicts.addAll(differ);
                if (chunk) {
                    chunksKept++;
                } else {
                    filesKept += differ.size();
                }
            } else {
                apply.addAll(differ);
                if (chunk) {
                    chunksApplied++;
                } else {
                    filesApplied += differ.size();
                }
            }
        }
        return new Plan(apply, conflicts, chunksApplied, chunksKept, filesApplied, filesKept);
    }

    /** Keys with the same group are decided together. */
    private static String group(String key) {
        if (RecordKey.isChunk(key)) {
            RecordKey.Chunk chunk = RecordKey.parseChunk(key);
            return "c|" + chunk.dim() + "|" + chunk.cx() + "|" + chunk.cz();
        }
        String player = Branches.playerOf(key);
        return player != null ? "p|" + player : key;
    }
}
