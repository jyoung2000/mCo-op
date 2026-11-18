package dropin.core.world;

import dropin.core.world.WorldArchive.Reason;
import dropin.core.world.WorldArchive.WorldImportException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldArchiveTest {
    @TempDir
    Path temp;
    Path saves;

    @BeforeEach
    void setUp() throws IOException {
        saves = Files.createDirectories(temp.resolve("saves"));
    }

    private Path zip(String name, String... entries) throws IOException {
        Path zip = temp.resolve(name);
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zos.putNextEntry(new ZipEntry(entry));
                if (!entry.endsWith("/")) {
                    zos.write(("content of " + entry).getBytes(StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
        return zip;
    }

    private long savesEntries() throws IOException {
        try (Stream<Path> files = Files.list(saves)) {
            return files.count();
        }
    }

    @Test
    void zipWithLevelDatAtTheRootIsNamedAfterTheZip() throws Exception {
        Path world = WorldArchive.extractZip(zip("My World.zip", "level.dat", "region/r.0.0.mca"), saves);
        assertEquals(saves.resolve("My World"), world);
        assertEquals("content of level.dat", Files.readString(world.resolve("level.dat")));
        assertTrue(Files.isRegularFile(world.resolve("region/r.0.0.mca")));
    }

    @Test
    void zipWithOneTopFolderIsUnwrapped() throws Exception {
        Path world = WorldArchive.extractZip(zip("backup.zip", "Castle/", "Castle/level.dat", "Castle/data/x.dat",
                "Castle/session.lock"), saves);
        assertEquals(saves.resolve("Castle"), world);
        assertTrue(Files.isRegularFile(world.resolve("level.dat")));
        assertTrue(Files.isRegularFile(world.resolve("data/x.dat")));
        assertFalse(Files.exists(world.resolve("session.lock")));
    }

    @Test
    void anExistingFolderIsNeverOverwritten() throws Exception {
        Files.createDirectories(saves.resolve("Castle"));
        Path world = WorldArchive.extractZip(zip("backup.zip", "Castle/level.dat"), saves);
        assertEquals(saves.resolve("Castle (2)"), world);
    }

    @Test
    void pathEscapingEntriesAreRefusedAndNothingIsWritten() throws Exception {
        for (String bad : new String[]{"../evil.txt", "world/../../evil.txt", "/abs.txt", "C:/abs.txt", "..\\evil.txt"}) {
            Path zip = zip("bad.zip", "level.dat", bad);
            WorldImportException e = assertThrows(WorldImportException.class, () -> WorldArchive.extractZip(zip, saves), bad);
            assertEquals(Reason.PATH_ESCAPE, e.reason(), bad);
            assertEquals(0, savesEntries(), bad);
            assertFalse(Files.exists(temp.resolve("evil.txt")), bad);
            Files.delete(zip);
        }
    }

    @Test
    void zipWithoutLevelDatWhereItMustBeIsRefused() throws Exception {
        Path none = zip("none.zip", "readme.txt");
        assertEquals(Reason.NO_LEVEL_DAT, assertThrows(WorldImportException.class, () -> WorldArchive.extractZip(none, saves)).reason());
        Path twoFolders = zip("two.zip", "a/level.dat", "b/level.dat");
        assertEquals(Reason.NO_LEVEL_DAT, assertThrows(WorldImportException.class, () -> WorldArchive.extractZip(twoFolders, saves)).reason());
        Path deep = zip("deep.zip", "a/b/level.dat");
        assertEquals(Reason.NO_LEVEL_DAT, assertThrows(WorldImportException.class, () -> WorldArchive.extractZip(deep, saves)).reason());
        assertEquals(0, savesEntries());
    }

    @Test
    void aFileThatIsNotAZipIsRefused() throws Exception {
        Path notZip = Files.writeString(temp.resolve("world.zip"), "this is not a zip");
        assertEquals(Reason.UNREADABLE, assertThrows(WorldImportException.class, () -> WorldArchive.extractZip(notZip, saves)).reason());
    }

    @Test
    void folderIsCopiedWithoutItsSessionLock() throws Exception {
        Path source = Files.createDirectories(temp.resolve("elsewhere/Farm"));
        Files.writeString(source.resolve("level.dat"), "dat");
        Files.writeString(source.resolve("session.lock"), "lock");
        Files.createDirectories(source.resolve("region"));
        Files.writeString(source.resolve("region/r.0.0.mca"), "mca");
        Path world = WorldArchive.copyFolder(source, saves);
        assertEquals(saves.resolve("Farm"), world);
        assertEquals("mca", Files.readString(world.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(world.resolve("session.lock")));
        assertTrue(Files.exists(source.resolve("level.dat")), "the source is left alone");
    }

    @Test
    void folderWithoutLevelDatIsRefused() throws Exception {
        Path source = Files.createDirectories(temp.resolve("NotAWorld"));
        Files.writeString(source.resolve("readme.txt"), "hi");
        assertEquals(Reason.NO_LEVEL_DAT, assertThrows(WorldImportException.class, () -> WorldArchive.copyFolder(source, saves)).reason());
        assertEquals(0, savesEntries());
    }

    @Test
    void exportedZipLeavesOutTheCoopFolderAndImportsBack(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path world = Files.createDirectories(dir.resolve("saves/My World"));
        Files.writeString(world.resolve("level.dat"), "level");
        Files.writeString(world.resolve("session.lock"), "lock");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("region/r.0.0.mca"), "chunks");
        Files.createDirectories(world.resolve("dropin/backups"));
        Files.writeString(world.resolve("dropin/world.json"), "{}");
        // Into the world's own backups folder, as the menu does.
        Path zip = world.resolve("dropin/backups/My World.zip");
        org.junit.jupiter.api.Assertions.assertEquals(2, WorldArchive.exportZip(world, zip));

        Path back = WorldArchive.extractZip(zip, Files.createDirectories(dir.resolve("other")));
        org.junit.jupiter.api.Assertions.assertEquals("My World", back.getFileName().toString());
        org.junit.jupiter.api.Assertions.assertEquals("chunks", Files.readString(back.resolve("region/r.0.0.mca")));
        org.junit.jupiter.api.Assertions.assertEquals("level", Files.readString(back.resolve("level.dat")));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(back.resolve("dropin")), "the co-op folder was exported");
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(back.resolve("session.lock")));

        Path notAWorld = Files.createDirectories(dir.resolve("empty"));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> WorldArchive.exportZip(notAWorld, dir.resolve("x.zip")));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(dir.resolve("x.zip")));
    }
}
