package dropin.core.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestTest {
    private static String h(String text) {
        return Manifest.hash(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Manifest sample() {
        Manifest manifest = new Manifest();
        for (int x = -40; x < 40; x += 3) {
            for (int z = -5; z < 5; z++) {
                manifest.put(RecordKey.chunk("minecraft:overworld", "region", x, z), h("c" + x + "," + z));
            }
        }
        manifest.put(RecordKey.chunk("minecraft:the_nether", "entities", 0, 0), h("nether"));
        manifest.put(RecordKey.file("level.dat"), h("level"));
        manifest.put(RecordKey.file("players/data/a.dat"), h("player"));
        return manifest;
    }

    @Test
    void hashIsFirstSixteenBytesOfSha256() {
        // SHA-256("abc") = ba7816bf 8f01cfea 414140de 5dae2223 b00361a3 ...
        assertEquals("ba7816bf8f01cfea414140de5dae2223", h("abc"));
    }

    @Test
    void regionMathHandlesNegativeCoordinates() {
        String key = RecordKey.chunk("minecraft:overworld", "region", -1, -33);
        assertEquals("r|minecraft:overworld|region|-1|-2", RecordKey.regionOf(key));
        assertEquals(31 + 31 * 32, RecordKey.indexInRegion(key));
        assertEquals(key, RecordKey.chunkInRegion(RecordKey.regionOf(key), RecordKey.indexInRegion(key)));
        assertEquals(new RecordKey.Chunk("minecraft:overworld", "region", -1, -33), RecordKey.parseChunk(key));
    }

    @Test
    void rootDoesNotDependOnInsertionOrder() {
        Manifest ordered = sample();
        List<String> keys = new ArrayList<>(ordered.keys());
        Collections.shuffle(keys, new Random(7));
        Manifest shuffled = new Manifest();
        for (String key : keys) {
            shuffled.put(key, ordered.get(key));
        }
        assertEquals(ordered.root(), shuffled.root());
        assertEquals(ordered.size(), shuffled.size());
    }

    @Test
    void rootChangesWithAnyRecord() {
        Manifest manifest = sample();
        String before = manifest.root();
        String key = RecordKey.chunk("minecraft:overworld", "region", -40, -5);
        String old = manifest.get(key);
        manifest.put(key, h("changed"));
        assertNotEquals(before, manifest.root());
        manifest.put(key, old);
        assertEquals(before, manifest.root());
        manifest.remove(RecordKey.file("level.dat"));
        assertNotEquals(before, manifest.root());
    }

    @Test
    void diffDescendsToTheChangedChunksOnly() {
        Manifest theirs = sample();
        Manifest mine = sample();
        assertTrue(Manifest.diff(mine, theirs).isEmpty());

        String changed = RecordKey.chunk("minecraft:overworld", "region", 2, 3);
        String added = RecordKey.chunk("minecraft:overworld", "poi", 100, 100);
        String removed = RecordKey.chunk("minecraft:the_nether", "entities", 0, 0);
        theirs.put(changed, h("new"));
        theirs.put(added, h("added"));
        theirs.remove(removed);
        theirs.put(RecordKey.file("data/map_1.dat"), h("map"));
        mine.put(RecordKey.file("data/old.dat"), h("old"));

        Manifest.Diff diff = Manifest.diff(mine, theirs);
        assertEquals(3, diff.fetch().size());
        assertEquals(h("new"), diff.fetch().get(changed));
        assertEquals(h("added"), diff.fetch().get(added));
        assertEquals(h("map"), diff.fetch().get(RecordKey.file("data/map_1.dat")));
        assertEquals(List.of(removed, RecordKey.file("data/old.dat")), diff.delete());

        diff.fetch().forEach(mine::put);
        diff.delete().forEach(mine::remove);
        assertEquals(theirs.root(), mine.root());
    }

    @Test
    void topAndChunksAreTheTwoDiffLevels() {
        Manifest manifest = sample();
        String region = "r|minecraft:overworld|region|-1|-1";
        assertTrue(manifest.top().containsKey(region));
        assertTrue(manifest.top().containsKey(RecordKey.file("level.dat")));
        assertFalse(manifest.chunks(region).isEmpty());
        manifest.chunks(region).keySet().forEach(key -> assertEquals(region, RecordKey.regionOf(key)));
        assertTrue(manifest.chunks("r|minecraft:overworld|region|50|50").isEmpty());
    }

    @Test
    void savedManifestLoadsBack(@TempDir Path dir) throws Exception {
        Manifest manifest = sample();
        Path file = dir.resolve("manifest").resolve("current.tsv");
        manifest.save(file);
        Manifest loaded = Manifest.load(file);
        assertEquals(manifest.root(), loaded.root());
        assertEquals(manifest.keys(), loaded.keys());
        assertTrue(Manifest.load(dir.resolve("missing.tsv")).isEmpty());
    }

    @Test
    void emptyChunkEntryDisappears() {
        Manifest manifest = new Manifest();
        String key = RecordKey.chunk("minecraft:overworld", "region", 0, 0);
        manifest.put(key, h("x"));
        manifest.remove(key);
        assertNull(manifest.get(key));
        assertEquals(new Manifest().root(), manifest.root());
        assertThrows(IllegalArgumentException.class, () -> manifest.put(key, "short"));
    }

    @Test
    void aModsFileIsLimitedInSizeAndAGameFileIsNot() {
        assertTrue(RecordKey.fileAllowed("somemod/state.dat", RecordKey.MAX_MOD_FILE));
        assertFalse(RecordKey.fileAllowed("somemod/backup.zip", RecordKey.MAX_MOD_FILE + 1));
        assertFalse(RecordKey.fileAllowed("big.bin", RecordKey.MAX_MOD_FILE + 1));
        assertTrue(RecordKey.fileAllowed("level.dat", RecordKey.MAX_MOD_FILE + 1));
        assertTrue(RecordKey.fileAllowed("data/huge.dat", RecordKey.MAX_MOD_FILE + 1));
        assertTrue(RecordKey.fileAllowed("dimensions/minecraft/overworld/data/huge.dat", RecordKey.MAX_MOD_FILE + 1));
        assertFalse(RecordKey.fileAllowed("dropin/ledger.jsonl", 10));
    }

    @Test
    void filePolicyFollowsTheDesign() {
        for (String ok : List.of("level.dat", "icon.png", "data/raids.dat", "players/data/x.dat",
                "players/advancements/x.json", "datapacks/pack/pack.mcmeta", "resourcepacks/a.zip",
                "dimensions/minecraft/overworld/data/chunks.dat", "dimensions/mod/deep/dim/data/x.dat",
                // What a mod keeps in the world's root travels too (owner's decision, 2026-11-18).
                "serverconfig/somemod-server.toml", "somemod/deep/state.dat", "mods/evil.jar", "options.txt", "data")) {
            assertTrue(RecordKey.fileAllowed(ok), ok);
        }
        for (String no : List.of("", "session.lock", "level.dat_old", "dropin/world.json", "dropin/ledger.jsonl",
                "data/x.tmp", "../level.dat", "data/../../x", "/etc/passwd", "C:/x", "data\\x", "data//x",
                "dimensions/minecraft/overworld/region/r.0.0.mca", "dimensions/minecraft/overworld/entities/r.0.0.mca",
                "dimensions/minecraft/overworld/poi/r.0.0.mca", "dimensions/minecraft/data/x",
                "region/r.0.0.mca", "entities/r.0.0.mca", "poi/x", "somemod/r.0.0.mca", "somemod/c.1.2.mcc",
                "somemod/session.lock", "somemod/x.tmp", "somemod/../level.dat")) {
            assertFalse(RecordKey.fileAllowed(no), no);
        }
    }

    @Test
    void badChunkKeysAreRefused() {
        for (String bad : List.of("c|minecraft:overworld|region|0", "c|minecraft:overworld|blocks|0|0",
                "c|../x:y|region|0|0", "c|minecraft:a/../b|region|0|0", "c|minecraft:overworld|region|x|0", "f|level.dat")) {
            assertThrows(IllegalArgumentException.class, () -> RecordKey.parseChunk(bad), bad);
        }
    }
}
