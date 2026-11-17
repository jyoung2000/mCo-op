package dropin.core.mods;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwapHelperTest {
    @TempDir
    Path dir;

    private Path mods() {
        return dir.resolve("mods");
    }

    private Path store() {
        return dir.resolve("modstore");
    }

    private String stored(String content) throws IOException {
        Files.createDirectories(store());
        Path temp = store().resolve("incoming");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        String hash = SwapHelper.sha512(temp);
        Files.move(temp, store().resolve(hash + ".jar"));
        return hash;
    }

    private void inMods(String name, String content) throws IOException {
        Files.createDirectories(mods());
        Files.writeString(mods().resolve(name), content, StandardCharsets.UTF_8);
    }

    private SwapPlan plan(long pid, List<SwapPlan.Install> install, List<String> keep) {
        return new SwapPlan(pid, mods(), store(), SwapPlan.disabledDir(mods(), Instant.parse("2026-11-17T08:00:00Z")), install, keep);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsed(SwapPlan plan) throws IOException {
        return (Map<String, Object>) SwapHelper.parseJson(plan.toJson());
    }

    private List<String> names(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(f -> f.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void movesExtrasKeepsKeptAndInstallsFromTheStore() throws IOException {
        String x = stored("mod x");
        String same = stored("already here");
        inMods("own-minimap.jar", "minimap");
        inMods("old-content.jar", "old");
        inMods("same.jar", "already here");
        inMods("notes.txt", "not a jar");
        SwapPlan plan = plan(1, List.of(new SwapPlan.Install("x.jar", x), new SwapPlan.Install("same.jar", same)),
                List.of("OWN-minimap.jar"));
        List<String> moved = new ArrayList<>();
        List<String> installed = new ArrayList<>();

        SwapHelper.apply(parsed(plan), moved, installed);

        assertEquals(List.of("old-content.jar"), moved);
        assertEquals(List.of("x.jar"), installed);
        assertEquals(List.of(".dropin-disabled", "notes.txt", "own-minimap.jar", "same.jar", "x.jar"), names(mods()));
        assertEquals("mod x", Files.readString(mods().resolve("x.jar")));
        Path disabled = mods().resolve(".dropin-disabled").resolve("20261117-080000");
        assertEquals("old", Files.readString(disabled.resolve("old-content.jar")), "moved, not deleted");
    }

    @Test
    void aFileWithTheRightNameButOtherContentIsReplaced() throws IOException {
        String x = stored("mod x v2");
        inMods("x.jar", "mod x v1");
        List<String> moved = new ArrayList<>();
        List<String> installed = new ArrayList<>();

        SwapHelper.apply(parsed(plan(1, List.of(new SwapPlan.Install("x.jar", x)), List.of())), moved, installed);

        assertEquals(List.of("x.jar"), moved);
        assertEquals("mod x v2", Files.readString(mods().resolve("x.jar")));
        assertEquals("mod x v1", Files.readString(mods().resolve(".dropin-disabled/20261117-080000/x.jar")));
    }

    @Test
    void aDamagedStoreFileStopsEverythingBeforeAnyMove() throws IOException {
        String x = stored("mod x");
        Files.writeString(store().resolve(x + ".jar"), "tampered");
        inMods("own.jar", "own");

        assertThrows(IOException.class, () -> SwapHelper.apply(
                parsed(plan(1, List.of(new SwapPlan.Install("x.jar", x)), List.of())), new ArrayList<>(), new ArrayList<>()));

        assertEquals(List.of("own.jar"), names(mods()));
    }

    @Test
    void unsafeNamesAreRefused() {
        for (String name : List.of("../x.jar", "sub/x.jar", "sub\\x.jar", "..jar", ".hidden.jar", "x.zip", "C:x.jar", "")) {
            assertThrows(IOException.class, () -> SwapHelper.checkedName(name), name);
        }
        String hash = "0".repeat(128);
        assertThrows(IOException.class, () -> plan(1, List.of(new SwapPlan.Install("../x.jar", hash)), List.of())
                .write(dir.resolve("modswap.json")));
    }

    @Test
    void theHelperProcessWaitsForTheGameThenSwapsAndReports() throws Exception {
        String x = stored("mod x");
        inMods("own.jar", "own");
        // Stands in for the game: a process that is still running when the helper starts.
        Process game = new ProcessBuilder(HelperLauncher.javaBinary().toString(), "-cp",
                System.getProperty("java.class.path"), Sleeper.class.getName()).start();
        Path planFile = dir.resolve("config").resolve(SwapPlan.FILE);
        plan(game.pid(), List.of(new SwapPlan.Install("x.jar", x)), List.of()).write(planFile);

        Process helper = HelperLauncher.launch(planFile, dir.resolve("config").resolve("modswap.log"));
        Thread.sleep(1500);
        assertTrue(helper.isAlive(), "the helper must wait while the game runs");
        assertEquals(List.of("own.jar"), names(mods()), "nothing changes while the game runs");
        game.destroyForcibly();

        assertTrue(helper.waitFor(60, TimeUnit.SECONDS), "helper did not finish");
        assertEquals(0, helper.exitValue(), Files.readString(dir.resolve("config").resolve("modswap.log")));
        String result = Files.readString(planFile.resolveSibling(SwapHelper.RESULT_FILE));
        assertEquals("{\"ok\":true,\"moved\":[\"own.jar\"],\"installed\":[\"x.jar\"]}", result);
        assertFalse(Files.exists(planFile), "the plan is removed after success");
        assertEquals(List.of(".dropin-disabled", "x.jar"), names(mods()));
    }

    /** A process that just stays alive. */
    public static final class Sleeper {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(120_000);
        }
    }
}
