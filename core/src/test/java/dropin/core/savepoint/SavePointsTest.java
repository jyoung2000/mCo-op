package dropin.core.savepoint;

import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;
import dropin.core.savepoint.SavePoints.Point;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavePointsTest {
    private static final String CHUNK_A = RecordKey.chunk("minecraft:overworld", "region", 0, 0);
    private static final String CHUNK_B = RecordKey.chunk("minecraft:overworld", "region", 1, 0);
    private static final String PLAYER = RecordKey.file("players/data/11111111-2222-3333-4444-555555555555.dat");

    /** A world as a map of record key to content. */
    private static final class World implements SavePoints.Source {
        final Map<String, byte[]> records = new LinkedHashMap<>();
        int reads;

        World set(String key, String content) {
            records.put(key, content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        Manifest manifest() {
            Manifest manifest = new Manifest();
            records.forEach((key, bytes) -> manifest.put(key, Manifest.hash(bytes)));
            return manifest;
        }

        @Override
        public byte[] read(String key) {
            reads++;
            return records.get(key);
        }
    }

    private static long objects(Path dir) throws IOException {
        Path objects = dir.resolve("objects");
        if (!Files.isDirectory(objects)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(objects)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void aSavePointHoldsEveryRecordAndUnchangedRecordsAreStoredOnce(@TempDir Path dir) throws IOException {
        SavePoints points = new SavePoints(dir);
        World world = new World().set(CHUNK_A, "stone").set(CHUNK_B, "dirt").set(PLAYER, "three diamonds");
        Point first = points.create("first", false, 4, world.manifest(), world);
        assertEquals(3, first.records());
        assertEquals(4, first.seq());
        assertEquals(world.manifest().root(), first.root());
        assertEquals(3, objects(dir));
        assertEquals(3, world.reads);

        world.set(PLAYER, "no diamonds");
        Point second = points.create("second", false, 5, world.manifest(), world);
        assertEquals(4, objects(dir), "only the changed record is stored again");
        assertEquals(4, world.reads, "records the store has are not read");

        Manifest stored = points.manifest(first);
        assertEquals(first.root(), stored.root());
        assertArrayEquals("three diamonds".getBytes(StandardCharsets.UTF_8), points.read(stored.get(PLAYER)));
        assertArrayEquals("no diamonds".getBytes(StandardCharsets.UTF_8), points.read(points.manifest(second).get(PLAYER)));
        assertEquals(List.of("first", "second"), points.list().stream().map(Point::name).toList());
    }

    @Test
    void aRecordThatChangedSinceTheCheckpointIsStoredAsItIs(@TempDir Path dir) throws IOException {
        SavePoints points = new SavePoints(dir);
        World world = new World().set(CHUNK_A, "stone");
        Manifest checkpoint = world.manifest();
        world.set(CHUNK_A, "glass");
        Point point = points.create("p", false, 1, checkpoint, world);
        assertEquals(world.manifest().root(), point.root(), "the save point describes what it stored");
        assertArrayEquals("glass".getBytes(StandardCharsets.UTF_8), points.read(points.manifest(point).get(CHUNK_A)));
    }

    @Test
    void theEleventhSavePointRemovesTheOldestAndItsRecords(@TempDir Path dir) throws IOException {
        SavePoints points = new SavePoints(dir);
        World world = new World().set(CHUNK_A, "stone");
        for (int i = 0; i < SavePoints.LIMIT + 1; i++) {
            world.set(CHUNK_B, "version " + i);
            points.create("p" + i, false, i, world.manifest(), world);
        }
        List<Point> kept = points.list();
        assertEquals(SavePoints.LIMIT, kept.size());
        assertEquals("p1", kept.get(0).name());
        assertTrue(points.find("p0").isEmpty());
        assertEquals(1 + SavePoints.LIMIT, objects(dir), "the record only the oldest named is gone");
    }

    @Test
    void theAutomaticSavePointReplacesThePreviousOneAndDoesNotCount(@TempDir Path dir) throws IOException {
        SavePoints points = new SavePoints(dir);
        World world = new World().set(CHUNK_A, "stone");
        for (int i = 0; i < SavePoints.LIMIT; i++) {
            points.create("p" + i, false, i, world.manifest(), world);
        }
        world.set(CHUNK_B, "before one");
        points.create(null, true, 20, world.manifest(), world);
        world.set(CHUNK_B, "before two");
        Point auto = points.create(null, true, 21, world.manifest(), world);
        List<Point> all = points.list();
        assertEquals(SavePoints.LIMIT + 1, all.size());
        assertEquals(1, all.stream().filter(Point::auto).count());
        assertEquals(SavePoints.AUTO_NAME, auto.name());
        assertEquals(auto.id(), points.find(SavePoints.AUTO_NAME).orElseThrow().id());
        assertTrue(points.find("p0").isPresent(), "the automatic one does not push a named one out");
        assertEquals(2, objects(dir));
    }

    @Test
    void namesAreCheckedAndUniqueWhateverTheCase(@TempDir Path dir) throws IOException {
        SavePoints points = new SavePoints(dir);
        World world = new World().set(CHUNK_A, "stone");
        points.create("Base_1", false, 1, world.manifest(), world);
        assertThrows(IOException.class, () -> points.create("base_1", false, 2, world.manifest(), world));
        assertThrows(IOException.class, () -> points.create("has space", false, 2, world.manifest(), world));
        assertThrows(IOException.class, () -> points.create("../up", false, 2, world.manifest(), world));
        assertThrows(IOException.class, () -> points.create("", false, 2, world.manifest(), world));
        assertThrows(IOException.class, () -> points.create(SavePoints.AUTO_NAME, false, 2, world.manifest(), world));
        assertFalse(SavePoints.validName("x".repeat(33)));
        assertEquals(1, points.list().size());
    }

    @Test
    void deletingASavePointRemovesTheRecordsOnlyItNamed(@TempDir Path dir) throws IOException {
        SavePoints points = new SavePoints(dir);
        World world = new World().set(CHUNK_A, "stone").set(CHUNK_B, "one");
        Point first = points.create("first", false, 1, world.manifest(), world);
        world.set(CHUNK_B, "two");
        points.create("second", false, 2, world.manifest(), world);
        assertEquals(3, objects(dir));
        points.delete(first);
        assertEquals(2, objects(dir));
        assertEquals(List.of("second"), points.list().stream().map(Point::name).toList());
    }

    @Test
    void anExportedSavePointImportsWithTheSameRoot(@TempDir Path dir) throws IOException {
        SavePoints here = new SavePoints(dir.resolve("here"));
        World world = new World().set(CHUNK_A, "stone").set(CHUNK_B, "dirt").set(PLAYER, "three diamonds");
        Point point = here.create("trip", false, 7, world.manifest(), world);
        Path file = dir.resolve("trip" + SavePoints.FILE_SUFFIX);
        here.exportTo(point, "world-1", file);

        SavePoints there = new SavePoints(dir.resolve("there"));
        assertThrows(IOException.class, () -> there.importFrom("world-2", file), "a file of another world");
        assertEquals(0, there.list().size());
        assertEquals(0, objects(dir.resolve("there")));

        Point imported = there.importFrom("world-1", file);
        assertEquals("trip", imported.name());
        assertEquals(point.root(), imported.root());
        assertEquals(7, imported.seq());
        assertEquals(3, imported.records());
        assertArrayEquals("three diamonds".getBytes(StandardCharsets.UTF_8), there.read(there.manifest(imported).get(PLAYER)));
        assertThrows(IOException.class, () -> there.importFrom("world-1", file), "the name is taken");
        assertEquals(1, there.list().size());
    }

    @Test
    void aFileThatIsNotASavePointIsRefusedAndLeavesNothing(@TempDir Path dir) throws IOException {
        SavePoints points = new SavePoints(dir.resolve("store"));
        Path junk = dir.resolve("junk" + SavePoints.FILE_SUFFIX);
        Files.writeString(junk, "not a zip");
        assertThrows(IOException.class, () -> points.importFrom("world-1", junk));
        assertEquals(0, points.list().size());
    }
}
