package dropin.mc.selftest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepRunnerTest {
    @Test
    void runsStepsInOrderOnePollPerTick() {
        List<String> seen = new ArrayList<>();
        int[] polls = {0};
        StepRunner runner = new StepRunner()
                .action("first", () -> seen.add("first"))
                .step("wait", 10, () -> ++polls[0] == 3)
                .action("last", () -> seen.add("last"));

        assertEquals(StepRunner.State.RUNNING, runner.tick()); // first
        assertEquals(List.of("first"), seen);
        assertEquals(StepRunner.State.RUNNING, runner.tick()); // wait 1
        assertEquals(StepRunner.State.RUNNING, runner.tick()); // wait 2
        assertEquals(StepRunner.State.RUNNING, runner.tick()); // wait 3, done
        assertEquals(StepRunner.State.PASSED, runner.tick());  // last
        assertEquals(List.of("first", "last"), seen);
        assertNull(runner.failure());
        assertEquals(List.of(
                new StepRunner.StepRecord("first", 1, true),
                new StepRunner.StepRecord("wait", 3, true),
                new StepRunner.StepRecord("last", 1, true)), runner.log());
    }

    @Test
    void timesOutAndStops() {
        boolean[] laterRan = {false};
        StepRunner runner = new StepRunner()
                .step("never", 2, () -> false)
                .action("later", () -> laterRan[0] = true);

        assertEquals(StepRunner.State.RUNNING, runner.tick());
        assertEquals(StepRunner.State.FAILED, runner.tick());
        assertEquals(StepRunner.State.FAILED, runner.tick());
        assertEquals("step 'never' timed out after 2 ticks", runner.failure());
        assertFalse(laterRan[0]);
        assertEquals(List.of(new StepRunner.StepRecord("never", 2, false)), runner.log());
    }

    @Test
    void exceptionFailsTheRun() {
        StepRunner runner = new StepRunner().action("boom", () -> {
            throw new IllegalStateException("nope");
        });
        assertEquals(StepRunner.State.FAILED, runner.tick());
        assertTrue(runner.failure().contains("boom"));
        assertTrue(runner.failure().contains("nope"));
    }

    @Test
    void emptyRunnerPasses() {
        assertEquals(StepRunner.State.PASSED, new StepRunner().tick());
    }

    @Test
    void resultFileIsWrittenAsJson(@TempDir Path gameDir) throws Exception {
        var result = new SelfTestResult("lan-join", "host", "fabric", false, "step 'x' said \"no\"\n", 1234,
                List.of(new StepRunner.StepRecord("a", 1, true), new StepRunner.StepRecord("x", 7, false)));
        result.write(gameDir);

        String json = Files.readString(gameDir.resolve("dropin-selftest").resolve("result.json"));
        assertTrue(json.contains("\"scenario\": \"lan-join\""), json);
        assertTrue(json.contains("\"role\": \"host\""), json);
        assertTrue(json.contains("\"loader\": \"fabric\""), json);
        assertTrue(json.contains("\"passed\": false"), json);
        assertTrue(json.contains("\"failure\": \"step 'x' said \\\"no\\\"\\n\""), json);
        assertTrue(json.contains("\"durationMs\": 1234"), json);
        assertTrue(json.contains("{\"name\": \"x\", \"ticks\": 7, \"ok\": false}"), json);
        assertFalse(Files.exists(gameDir.resolve("dropin-selftest").resolve("result.json.tmp")));
    }

    @Test
    void passedResultHasNullFailure() {
        var result = new SelfTestResult("status-ping", "pinger", "neoforge", true, null, 5, List.of());
        assertTrue(result.toJson().contains("\"failure\": null"));
        assertTrue(result.toJson().contains("\"steps\": []"));
    }
}
