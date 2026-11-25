package dropin.core.merge;

import dropin.core.crypto.WorldKeys;
import dropin.core.ledger.Deltas;
import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;
import dropin.core.merge.Branches.Change;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeTest {
    private static final WorldKeys KEYS = WorldKeys.fromSeed(new byte[32]);
    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";

    private static String h(String text) {
        return Manifest.hash(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String region(int cx, int cz) {
        return RecordKey.chunk("minecraft:overworld", "region", cx, cz);
    }

    private static String entities(int cx, int cz) {
        return RecordKey.chunk("minecraft:overworld", "entities", cx, cz);
    }

    private static String poi(int cx, int cz) {
        return RecordKey.chunk("minecraft:overworld", "poi", cx, cz);
    }

    // ---- planner ----------------------------------------------------------------------------------

    @Test
    void branchOnlyChangeIsApplied() {
        Map<String, Change> branch = new LinkedHashMap<>();
        branch.put(region(30, 0), new Change(h("branch"), h("base")));
        branch.put(region(31, 0), new Change(h("new chunk"), ""));
        branch.put("f|icon.png", new Change(h("icon2"), h("icon")));
        Map<String, String> main = Map.of(region(30, 0), h("base"), "f|icon.png", h("icon"));

        MergePlanner.Plan plan = MergePlanner.plan(branch, main::get);

        assertEquals(List.of(region(30, 0), region(31, 0), "f|icon.png"), plan.apply());
        assertTrue(plan.conflicts().isEmpty());
        assertEquals(2, plan.chunksApplied());
        assertEquals(0, plan.chunksKept());
        assertEquals(1, plan.filesApplied());
    }

    @Test
    void bothChangedKeepsMainAndBacksTheBranchRecordUp() {
        Map<String, Change> branch = new LinkedHashMap<>();
        branch.put(region(0, 0), new Change(h("branch"), h("base")));
        branch.put(region(30, 0), new Change(h("far"), h("far base")));
        Map<String, String> main = Map.of(region(0, 0), h("main"), region(30, 0), h("far base"));

        MergePlanner.Plan plan = MergePlanner.plan(branch, main::get);

        assertEquals(List.of(region(30, 0)), plan.apply());
        assertEquals(List.of(region(0, 0)), plan.conflicts());
        assertEquals(1, plan.chunksApplied());
        assertEquals(1, plan.chunksKept());
    }

    @Test
    void deletedOnMainWhileChangedOnTheBranchIsAConflict() {
        Map<String, Change> branch = Map.of(region(0, 0), new Change(h("branch"), h("base")));

        MergePlanner.Plan plan = MergePlanner.plan(branch, key -> null);

        assertEquals(List.of(region(0, 0)), plan.conflicts());
        assertTrue(plan.apply().isEmpty());
    }

    @Test
    void deletionOnTheBranchIsApplied() {
        Map<String, Change> branch = Map.of("f|datapacks/old.zip", new Change("", h("pack")));

        MergePlanner.Plan plan = MergePlanner.plan(branch, Map.of("f|datapacks/old.zip", h("pack"))::get);

        assertEquals(List.of("f|datapacks/old.zip"), plan.apply());
    }

    @Test
    void theThreeRecordsOfAChunkMoveTogether() {
        // The branch changed the blocks and the entities of chunk 5,5; the main world only its POI record.
        Map<String, Change> branch = new LinkedHashMap<>();
        branch.put(region(5, 5), new Change(h("blocks b"), h("blocks")));
        branch.put(entities(5, 5), new Change(h("entities b"), h("entities")));
        branch.put(poi(5, 5), new Change(h("poi"), h("poi")));
        // Chunk 6,6: the branch changed all three, the main world none.
        branch.put(region(6, 6), new Change(h("r"), h("r0")));
        branch.put(entities(6, 6), new Change(h("e"), ""));
        branch.put(poi(6, 6), new Change("", h("p0")));
        Map<String, String> main = new HashMap<>();
        main.put(region(5, 5), h("blocks"));
        main.put(entities(5, 5), h("entities"));
        main.put(poi(5, 5), h("poi main"));
        main.put(region(6, 6), h("r0"));
        main.put(poi(6, 6), h("p0"));

        MergePlanner.Plan plan = MergePlanner.plan(branch, main::get);

        assertEquals(List.of(region(5, 5), entities(5, 5)), plan.conflicts());
        assertEquals(List.of(region(6, 6), entities(6, 6), poi(6, 6)), plan.apply());
        assertEquals(1, plan.chunksApplied());
        assertEquals(1, plan.chunksKept());
    }

    @Test
    void playerFilesComeFromTheBranchOnlyIfMainDidNotChangeThem() {
        String data = "f|players/data/" + PLAYER + ".dat";
        String stats = "f|players/stats/" + PLAYER + ".json";
        String other = "f|players/data/99999999-2222-3333-4444-555555555555.dat";
        Map<String, Change> branch = new LinkedHashMap<>();
        branch.put(data, new Change(h("inventory b"), h("inventory")));
        branch.put(stats, new Change(h("stats b"), h("stats")));
        branch.put(other, new Change(h("other b"), h("other")));

        // The main world did not touch this player: both files come from the branch.
        Map<String, String> untouched = Map.of(data, h("inventory"), stats, h("stats"), other, h("other"));
        MergePlanner.Plan plan = MergePlanner.plan(branch, untouched::get);
        assertEquals(List.of(data, stats, other), plan.apply());
        assertEquals(3, plan.filesApplied());

        // The player also played on the main world: all of their files stay, the other player's still merge.
        Map<String, String> played = Map.of(data, h("inventory main"), stats, h("stats"), other, h("other"));
        plan = MergePlanner.plan(branch, played::get);
        assertEquals(List.of(other), plan.apply());
        assertEquals(List.of(data, stats), plan.conflicts());
        assertEquals(2, plan.filesKept());
        assertEquals(0, plan.chunksKept());
    }

    @Test
    void theBranchOwnersFilesComeFromTheBranchEvenIfMainChangedThem() {
        // The owner joined the main world before the merge, so main changed their files too; their offline
        // session wins. Another player who played on both sides keeps the main world's files.
        String data = "f|players/data/" + PLAYER + ".dat";
        String stats = "f|players/stats/" + PLAYER + ".json";
        String other = "f|players/data/99999999-2222-3333-4444-555555555555.dat";
        Map<String, Change> branch = new LinkedHashMap<>();
        branch.put(data, new Change(h("inventory b"), h("inventory")));
        branch.put(stats, new Change(h("stats b"), h("stats")));
        branch.put(other, new Change(h("other b"), h("other")));
        Map<String, String> main = Map.of(data, h("inventory main"), stats, h("stats main"), other, h("other main"));

        MergePlanner.Plan plan = MergePlanner.plan(branch, main::get, main::get, PLAYER);

        assertEquals(List.of(data, stats), plan.apply());
        assertEquals(List.of(other), plan.conflicts());
        assertEquals(2, plan.filesApplied());
        assertEquals(1, plan.filesKept());

        // A file the branch did not change stays as the main world has it, owner or not.
        Map<String, Change> onlyStats = new LinkedHashMap<>();
        onlyStats.put(stats, new Change(h("stats b"), h("stats")));
        onlyStats.put(data, new Change(h("inventory"), h("inventory")));
        plan = MergePlanner.plan(onlyStats, main::get, main::get, PLAYER);
        assertEquals(List.of(stats), plan.apply());
        assertTrue(plan.conflicts().isEmpty());
    }

    @Test
    void worldLevelFilesStayAsTheMainWorldHasThem() {
        Map<String, Change> branch = new LinkedHashMap<>();
        branch.put("f|level.dat", new Change(h("level b"), h("level")));
        branch.put("f|data/minecraft/scoreboard.dat", new Change(h("data b"), h("data")));
        branch.put("f|dimensions/minecraft/overworld/data/raids.dat", new Change(h("raids b"), h("raids")));
        Map<String, String> main = Map.of("f|level.dat", h("level"), "f|data/minecraft/scoreboard.dat", h("data"),
                "f|dimensions/minecraft/overworld/data/raids.dat", h("raids"));

        assertTrue(MergePlanner.plan(branch, main::get).isEmpty());
        assertTrue(Branches.bundle(branch, main::get).isEmpty());
    }

    @Test
    void whatMainAlreadyHoldsIsNeitherAppliedNorAConflict() {
        // A merge that was cut short after writing chunk 1,1, planned again.
        Map<String, Change> branch = new LinkedHashMap<>();
        branch.put(region(1, 1), new Change(h("branch"), h("base")));
        branch.put(entities(1, 1), new Change(h("ent b"), h("ent")));
        branch.put(region(2, 2), new Change(h("two"), h("two base")));
        Map<String, String> main = Map.of(region(1, 1), h("branch"), entities(1, 1), h("ent"), region(2, 2), h("two"));

        MergePlanner.Plan plan = MergePlanner.plan(branch, main::get);

        assertEquals(List.of(entities(1, 1)), plan.apply());
        assertTrue(plan.conflicts().isEmpty());
        assertEquals(1, plan.chunksApplied());
    }

    // ---- what a branch is made of -----------------------------------------------------------------

    @Test
    void changedSinceIsTheUnionOfTheDeltasWithTheOldestBase() {
        Map<Long, Deltas.Delta> deltas = new HashMap<>();
        deltas.put(4L, new Deltas.Delta(Map.of(region(0, 0), h("a1"), "f|level.dat", h("l1"), region(9, 9), h("temp")),
                Map.of(region(0, 0), h("a0"), "f|level.dat", h("l0"))));
        deltas.put(5L, new Deltas.Delta(Map.of(region(0, 0), h("a2"), region(1, 0), "", region(9, 9), ""),
                Map.of(region(0, 0), h("a1"), region(1, 0), h("b0"), region(9, 9), h("temp"))));

        Map<String, Change> changed = Branches.changedSince(3, 5, deltas::get);

        // Region records: these delta files have no content column, so the content at the ancestor is unknown.
        assertEquals(new Change(h("a2"), h("a0"), h("a2"), null), changed.get(region(0, 0)));
        assertEquals(new Change("", h("b0"), "", null), changed.get(region(1, 0)));
        assertEquals(new Change(h("l1"), h("l0")), changed.get("f|level.dat"));
        // Created and deleted again since the ancestor: not a change.
        assertFalse(changed.containsKey(region(9, 9)));
        assertTrue(Branches.changedSince(5, 5, deltas::get).isEmpty());
    }

    @Test
    void deltasThatNoLongerReachTheAncestorMeanSaveAsASeparateWorld() {
        Map<Long, Deltas.Delta> deltas = new HashMap<>();
        deltas.put(5L, new Deltas.Delta(Map.of(region(0, 0), h("a2")), Map.of(region(0, 0), h("a1"))));

        // Checkpoint 4's delta was pruned.
        assertNull(Branches.changedSince(3, 5, deltas::get));
        // A delta written before M5 does not say what the records were before.
        deltas.put(4L, new Deltas.Delta(Map.of(region(0, 0), h("a1")), null));
        assertNull(Branches.changedSince(3, 5, deltas::get));
    }

    @Test
    void bundleListsTheOtherRecordsOfAChangedChunkUnchanged() {
        Map<String, Change> changed = new LinkedHashMap<>();
        changed.put(region(3, 3), new Change(h("r1"), h("r0")));
        changed.put("f|level.dat", new Change(h("l1"), h("l0")));
        changed.put("f|players/data/" + PLAYER + ".dat", new Change(h("p1"), h("p0")));
        Map<String, String> holds = Map.of(region(3, 3), h("r1"), entities(3, 3), h("e0"));

        Map<String, Change> bundle = Branches.bundle(changed, holds::get);

        assertEquals(new Change(h("r1"), h("r0")), bundle.get(region(3, 3)));
        assertEquals(new Change(h("e0"), h("e0")), bundle.get(entities(3, 3)));
        assertEquals(new Change("", ""), bundle.get(poi(3, 3)));
        assertTrue(bundle.containsKey("f|players/data/" + PLAYER + ".dat"));
        assertFalse(bundle.containsKey("f|level.dat"));
        assertEquals(4, bundle.size());
    }

    @Test
    void playerOfNamesTheOwnerOfAPlayerFile() {
        assertEquals(PLAYER, Branches.playerOf("f|players/data/" + PLAYER + ".dat"));
        assertEquals(PLAYER, Branches.playerOf("f|players/advancements/" + PLAYER + ".json"));
        assertNull(Branches.playerOf("f|players/data/readme.txt"));
        assertNull(Branches.playerOf("f|data/" + PLAYER + ".dat"));
        assertNull(Branches.playerOf(region(0, 0)));
    }

    // ---- files ------------------------------------------------------------------------------------

    @Test
    void deltasKeepTheBaseHashes(@TempDir Path dir) throws IOException {
        Map<String, String> changed = new LinkedHashMap<>();
        changed.put(region(0, 0), h("new"));
        changed.put("f|data/a b.dat", "");
        changed.put("f|icon.png", h("icon"));
        Deltas.write(dir, 3, changed, Map.of(region(0, 0), h("old"), "f|data/a b.dat", h("gone")));

        Deltas.Delta delta = Deltas.readFull(dir, 3);

        assertEquals(changed, delta.changed());
        assertEquals(Map.of(region(0, 0), h("old"), "f|data/a b.dat", h("gone"), "f|icon.png", ""), delta.base());
        assertEquals(changed, Deltas.read(dir, 3));

        // The format from before M5 still reads, without a base.
        Deltas.write(dir, 2, changed);
        assertEquals(changed, Deltas.readFull(dir, 2).changed());
        assertNull(Deltas.readFull(dir, 2).base());
    }

    @Test
    void oldDeltasArePruned(@TempDir Path dir) throws IOException {
        Deltas.write(dir, 1, Map.of("f|icon.png", h("a")), Map.of());
        Deltas.write(dir, 2, Map.of("f|icon.png", h("b")), Map.of());
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(Deltas.file(dir, 1), java.nio.file.attribute.FileTime.fromMillis(now - 31L * 24 * 3600 * 1000));

        assertEquals(1, Deltas.prune(dir, now - 30L * 24 * 3600 * 1000));

        assertFalse(Files.exists(Deltas.file(dir, 1)));
        assertTrue(Files.exists(Deltas.file(dir, 2)));
        assertEquals(0, Deltas.prune(dir.resolve("none"), now));
    }

    @Test
    void branchStoreKeepsABranchAndCountsOnlyCompleteOnes(@TempDir Path dir) throws IOException {
        BranchStore store = new BranchStore(dir);
        LedgerEntry ancestor = LedgerEntry.create(KEYS.auth(), 1, "", "a", "Sam", "s", 10, "root1", 3);
        LedgerEntry head = LedgerEntry.create(KEYS.auth(), 2, ancestor.hash(), "b", "Jo", "t", 20, "root2", 2);
        byte[] bytes = "chunk bytes".getBytes(StandardCharsets.UTF_8);
        Map<String, Change> keys = new LinkedHashMap<>();
        keys.put(region(1, 2), new Change(Manifest.hash(bytes), h("before")));
        keys.put(entities(1, 2), new Change(h("same"), h("same")));
        keys.put("f|datapacks/x.zip", new Change("", h("pack")));

        BranchStore.Writer writer = store.begin(head.hash(), ancestor.hash(), head);
        writer.keys(keys);
        assertTrue(store.heads().isEmpty());
        assertFalse(store.has(head.hash()));
        assertTrue(writer.wants(Manifest.hash(bytes)));
        writer.record(bytes);
        assertFalse(writer.wants(Manifest.hash(bytes)));
        writer.finish();

        assertEquals(List.of(head.hash()), store.heads());
        BranchStore.Branch branch = store.load(head.hash());
        assertEquals(ancestor.hash(), branch.ancestor());
        assertEquals(head, branch.entry());
        assertEquals(keys, branch.keys());
        assertEquals(List.copyOf(keys.keySet()), List.copyOf(branch.keys().keySet()));
        assertArrayEquals(bytes, store.record(head.hash(), Manifest.hash(bytes)));

        Files.writeString(dir.resolve(head.hash()).resolve("records").resolve(Manifest.hash(bytes) + ".bin"), "damaged");
        assertThrows(IOException.class, () -> store.record(head.hash(), Manifest.hash(bytes)));

        store.delete(head.hash());
        assertTrue(store.heads().isEmpty());
        assertThrows(IOException.class, () -> store.load(head.hash()));
        assertThrows(IOException.class, () -> store.begin("../escape", "", head));
    }

    // ---- ledger -----------------------------------------------------------------------------------

    @Test
    void mergeEntryHasTwoParentsAndTheLedgerCanBeCutBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ledger.jsonl");
        Ledger ledger = new Ledger(KEYS.auth(), file);
        LedgerEntry one = LedgerEntry.create(KEYS.auth(), 1, "", "a", "Sam", "s", 10, "root1", 3);
        LedgerEntry two = LedgerEntry.create(KEYS.auth(), 2, one.hash(), "b", "Jo", "t", 20, "root2", 2);
        ledger.append(one);
        ledger.append(two);
        String branchHead = two.hash();

        ledger.truncate(1);

        assertEquals(one.hash(), ledger.head());
        assertEquals(1, ledger.size());
        assertFalse(ledger.contains(branchHead));
        LedgerEntry onMain = LedgerEntry.create(KEYS.auth(), 2, one.hash(), "a", "Sam", "u", 30, "root3", 1);
        ledger.append(onMain);
        LedgerEntry merge = LedgerEntry.createMerge(KEYS.auth(), 3, onMain.hash(), branchHead, "a", "Sam", "v", 40, "root4", 2);
        assertTrue(merge.verify(KEYS.auth()));
        assertEquals(branchHead, merge.parents2());
        assertFalse(ledger.hasMerged(branchHead));
        ledger.append(merge);
        assertTrue(ledger.hasMerged(branchHead));

        Ledger loaded = Ledger.load(KEYS.auth(), file);
        assertEquals(ledger.hashes(), loaded.hashes());
        assertEquals(List.of(one, onMain, merge), loaded.entries());
        assertTrue(loaded.hasMerged(branchHead));
        // A changed second parent breaks the MAC.
        LedgerEntry forged = new LedgerEntry(merge.seq(), merge.parent(), merge.host(), merge.name(), merge.sess(), merge.ts(),
                merge.root(), merge.changed(), one.hash(), merge.mac());
        assertFalse(forged.verify(KEYS.auth()));

        loaded.truncate(0);
        assertEquals("", loaded.head());
        assertEquals(0, Ledger.load(KEYS.auth(), file).size());
    }

    // ---- content hashes: a chunk the game only saved again is not a change ------------------------------

    /** The NBT of a chunk with the given save time, inhabited time and one block name in its sections. */
    private static byte[] chunk(long lastUpdate, long inhabited, String block) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        out.writeByte(3);
        out.writeUTF("DataVersion");
        out.writeInt(4700);
        out.writeByte(4);
        out.writeUTF("LastUpdate");
        out.writeLong(lastUpdate);
        out.writeByte(9);
        out.writeUTF("sections");
        out.writeByte(10);
        out.writeInt(2);
        for (int y = 0; y < 2; y++) {
            out.writeByte(1);
            out.writeUTF("Y");
            out.writeByte(y);
            out.writeByte(8);
            out.writeUTF("block");
            out.writeUTF(block);
            out.writeByte(12);
            out.writeUTF("data");
            out.writeInt(3);
            out.writeLong(1);
            out.writeLong(2);
            out.writeLong(3);
            // The same name deeper down is content, not the chunk's own field.
            out.writeByte(4);
            out.writeUTF("LastUpdate");
            out.writeLong(7 + y);
            out.writeByte(0);
        }
        out.writeByte(4);
        out.writeUTF("InhabitedTime");
        out.writeLong(inhabited);
        out.writeByte(7);
        out.writeUTF("bytes");
        out.writeInt(2);
        out.writeByte(1);
        out.writeByte(2);
        out.writeByte(0);
        return bytes.toByteArray();
    }

    @Test
    void contentHashLeavesOutTheFieldsTheGameRewritesOnEverySave() throws IOException {
        String key = region(0, 0);
        byte[] first = chunk(100, 5, "minecraft:stone");
        byte[] savedAgain = chunk(900, 77, "minecraft:stone");
        byte[] edited = chunk(100, 5, "minecraft:glass");
        assertFalse(Manifest.hash(first).equals(Manifest.hash(savedAgain)));
        assertEquals(ChunkContent.hash(key, first), ChunkContent.hash(key, savedAgain));
        assertFalse(ChunkContent.hash(key, first).equals(ChunkContent.hash(key, edited)));
        assertEquals("", ChunkContent.hash(key, null));
        // Only the region record of a chunk; anything else, and bytes that are not such NBT, by record hash.
        assertEquals(Manifest.hash(first), ChunkContent.hash(entities(0, 0), first));
        assertEquals(Manifest.hash(first), ChunkContent.hash("f|level.dat", first));
        byte[] cut = java.util.Arrays.copyOf(first, first.length - 9);
        assertEquals(Manifest.hash(cut), ChunkContent.hash(key, cut));
        byte[] junk = "not nbt".getBytes(StandardCharsets.UTF_8);
        assertEquals(Manifest.hash(junk), ChunkContent.hash(key, junk));
        assertTrue(ChunkContent.applies(key));
        assertFalse(ChunkContent.applies(poi(0, 0)));
    }

    /** A 1.12.2 chunk: {DataVersion, Level: {xPos, LastUpdate, InhabitedTime, block}}, volatile fields in Level. */
    private static byte[] legacyChunk(long lastUpdate, long inhabited, String block) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        out.writeByte(3);
        out.writeUTF("DataVersion");
        out.writeInt(1343);
        out.writeByte(10);
        out.writeUTF("Level");
        out.writeByte(3);
        out.writeUTF("xPos");
        out.writeInt(0);
        out.writeByte(4);
        out.writeUTF("LastUpdate");
        out.writeLong(lastUpdate);
        out.writeByte(4);
        out.writeUTF("InhabitedTime");
        out.writeLong(inhabited);
        out.writeByte(8);
        out.writeUTF("block");
        out.writeUTF(block);
        out.writeByte(0);
        out.writeByte(0);
        return bytes.toByteArray();
    }

    @Test
    void contentHashOfA1122ChunkLeavesOutTheFieldsInLevel() throws IOException {
        String key = region(0, 0);
        byte[] first = legacyChunk(100, 5, "minecraft:stone");
        byte[] savedAgain = legacyChunk(900, 77, "minecraft:stone");
        byte[] edited = legacyChunk(100, 5, "minecraft:glass");
        assertFalse(Manifest.hash(first).equals(Manifest.hash(savedAgain)));
        assertEquals(ChunkContent.hash(key, first), ChunkContent.hash(key, savedAgain));
        assertFalse(ChunkContent.hash(key, first).equals(ChunkContent.hash(key, edited)));
        byte[] cut = java.util.Arrays.copyOf(first, first.length - 3);
        assertEquals(Manifest.hash(cut), ChunkContent.hash(key, cut));
    }

    @Test
    void aChunkOnlySavedAgainIsNotAChangeOnEitherSide() {
        // Record hashes differ everywhere (the game saved the chunks again); the content says what really changed.
        Map<String, Change> branch = new LinkedHashMap<>();
        branch.put(region(0, 0), new Change(h("b0"), h("r0"), h("same"), h("same")));     // both only saved again
        branch.put(region(1, 0), new Change(h("b1"), h("r1"), h("built"), h("c1")));      // branch built, main saved again
        branch.put(region(2, 0), new Change(h("b2"), h("r2"), h("c2"), h("c2")));         // main built, branch saved again
        branch.put(region(3, 0), new Change(h("b3"), h("r3"), h("mine"), h("c3")));       // both built
        Map<String, String> main = Map.of(region(0, 0), h("m0"), region(1, 0), h("m1"), region(2, 0), h("m2"), region(3, 0), h("m3"));
        Map<String, String> mainContent = Map.of(region(0, 0), h("same"), region(1, 0), h("c1"), region(2, 0), h("theirs"),
                region(3, 0), h("theirs"));
        MergePlanner.Plan plan = MergePlanner.plan(branch, main::get, mainContent::get);
        assertEquals(List.of(region(1, 0)), plan.apply());
        assertEquals(List.of(region(3, 0)), plan.conflicts());
        assertEquals(1, plan.chunksApplied());
        assertEquals(1, plan.chunksKept());
    }

    @Test
    void bothMadeTheSameChangeIsNeitherAppliedNorAConflict() {
        Map<String, Change> branch = Map.of(region(0, 0), new Change(h("b0"), h("r0"), h("built"), h("c0")));
        MergePlanner.Plan plan = MergePlanner.plan(branch, key -> h("m0"), key -> h("built"));
        assertTrue(plan.isEmpty());
    }

    @Test
    void withoutAContentHashAtTheAncestorTheRecordHashesDecide() {
        Map<String, Change> branch = new LinkedHashMap<>();
        branch.put(region(0, 0), new Change(h("b0"), h("r0"), h("built"), null));
        branch.put(region(1, 0), new Change(h("b1"), h("r1"), h("built"), null));
        Map<String, String> main = Map.of(region(0, 0), h("r0"), region(1, 0), h("m1"));
        MergePlanner.Plan plan = MergePlanner.plan(branch, main::get, key -> h("anything"));
        assertEquals(List.of(region(0, 0)), plan.apply());
        assertEquals(List.of(region(1, 0)), plan.conflicts());
    }

    @Test
    void aSavedAgainRecordOfAChunkDoesNotHoldBackItsOtherRecords() {
        // The branch added an entity; its region record and the main world's were only saved again.
        Map<String, Change> branch = new LinkedHashMap<>();
        branch.put(entities(4, 4), new Change(h("e1"), h("e0")));
        branch.put(region(4, 4), new Change(h("b4"), h("r4"), h("c4"), h("c4")));
        Map<String, String> main = Map.of(entities(4, 4), h("e0"), region(4, 4), h("m4"));
        MergePlanner.Plan plan = MergePlanner.plan(branch, main::get, key -> key.equals(region(4, 4)) ? h("c4") : main.get(key));
        assertEquals(List.of(entities(4, 4)), plan.apply());
        assertTrue(plan.conflicts().isEmpty());
    }

    @Test
    void deltasCarryTheContentHashAtTheAncestorIntoTheBranch(@TempDir Path dir) throws IOException {
        Map<String, String> changed = new LinkedHashMap<>();
        changed.put(region(0, 0), h("a1"));
        changed.put(region(1, 0), h("n1"));
        changed.put(region(2, 0), h("u1"));
        changed.put("f|icon.png", h("i1"));
        Deltas.write(dir, 4, changed, Map.of(region(0, 0), h("a0"), region(2, 0), h("u0"), "f|icon.png", h("i0")),
                Map.of(region(0, 0), h("ca0"), region(1, 0), ""));
        Deltas.write(dir, 5, Map.of(region(0, 0), h("a2")), Map.of(region(0, 0), h("a1")), Map.of(region(0, 0), h("ca1")));
        assertEquals(Map.of(region(0, 0), h("ca0"), region(1, 0), ""), Deltas.readFull(dir, 4).contentBase());
        Map<String, Change> since = Branches.changedSince(3, 5, seq -> {
            try {
                return Deltas.readFull(dir, seq);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        assertEquals(h("ca0"), since.get(region(0, 0)).contentBase());   // the oldest one, as with the record hash
        assertEquals("", since.get(region(1, 0)).contentBase());         // new on the branch
        assertNull(since.get(region(2, 0)).contentBase());               // the copy did not know it
        assertEquals(h("i0"), since.get("f|icon.png").contentBase());    // a file: its record hash
    }

    @Test
    void branchStoreKeepsTheContentHashes(@TempDir Path dir) throws IOException {
        BranchStore store = new BranchStore(dir);
        LedgerEntry head = LedgerEntry.create(KEYS.auth(), 2, h("p"), "host", "Jo", "s", 10, h("root"), 1);
        byte[] record = "chunk".getBytes(StandardCharsets.UTF_8);
        Map<String, Change> keys = new LinkedHashMap<>();
        keys.put(region(0, 0), new Change(Manifest.hash(record), h("r0"), h("c1"), h("c0")));
        keys.put(region(1, 0), new Change(Manifest.hash(record), h("r1"), h("c1"), null));
        BranchStore.Writer writer = store.begin(head.hash(), "", head);
        writer.keys(keys);
        writer.record(record);
        writer.finish();
        assertEquals(keys, store.load(head.hash()).keys());
    }
    @Test
    void legacyLayoutPlayerFilesAndWorldLevelFiles() {
        String id = "0f0e0d0c-0001-4000-8000-000000000001";
        assertEquals(id, Branches.playerOf(RecordKey.file("playerdata/" + id + ".dat")));
        assertEquals(id, Branches.playerOf(RecordKey.file("advancements/" + id + ".json")));
        assertEquals(id, Branches.playerOf(RecordKey.file("stats/" + id + ".json")));
        assertEquals(id, Branches.playerOf(RecordKey.file("players/data/" + id + ".dat")));
        assertNull(Branches.playerOf(RecordKey.file("playerdata/x/" + id + ".dat")));
        assertNull(Branches.playerOf(RecordKey.file("somemod/" + id + ".dat")));
        assertTrue(Branches.worldLevel(RecordKey.file("DIM-1/data/raids.dat")));
        assertTrue(Branches.worldLevel(RecordKey.file("DIM1/data/raids_end.dat")));
        // 1.12.2: a mod's dimension keeps its saved data in DIM<N>/data/
        assertTrue(Branches.worldLevel(RecordKey.file("DIM2/data/villages.dat")));
        assertTrue(Branches.worldLevel(RecordKey.file("DIM-7/data/x.dat")));
        // Forge's force-loaded chunks: 26.x keeps them in data/chunks.dat, which is world-level
        assertTrue(Branches.worldLevel(RecordKey.file("forcedchunks.dat")));
        assertTrue(Branches.worldLevel(RecordKey.file("DIM2/forcedchunks.dat")));
        assertTrue(Branches.worldLevel(RecordKey.file("DIM-1/forcedchunks.dat")));
        assertFalse(Branches.worldLevel(RecordKey.file("DIM0/forcedchunks.dat")));
        assertTrue(Branches.worldLevel(RecordKey.file("data/raids.dat")));
        assertFalse(Branches.worldLevel(RecordKey.file("playerdata/" + id + ".dat")));
    }
}
