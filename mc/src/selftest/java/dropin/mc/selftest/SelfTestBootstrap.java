package dropin.mc.selftest;

import dropin.core.dev.SelfTestSpec;
import dropin.mc.DropIn;
import dropin.tunnel.Agnos;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Development-only entry of the scripted self-test (DESIGN §4.12). Looked up by name from
 * {@link DropIn#init()} when {@code -Ddropin.selftest=<scenario>:<role>} is set in a development environment.
 * This source set is not part of the mod jar.
 */
public final class SelfTestBootstrap {
    private static final String TIMEOUT_PROPERTY = "dropin.dev.selftestTimeoutSec";
    private static final long DEFAULT_TIMEOUT_SEC = 540;

    private SelfTestBootstrap() {}

    public static void start(SelfTestSpec spec) {
        Driver driver = new Driver(spec);
        DropIn.setClientTickHook(driver::tick);
        driver.startWatchdog();
        DropIn.LOGGER.info("Self-test armed: scenario={} role={} loader={}", spec.scenario(), spec.role(), Agnos.loaderName());
    }

    private static final class Driver {
        private final SelfTestSpec spec;
        private final long startNanos = System.nanoTime();
        private volatile StepRunner runner;
        private volatile boolean finished;
        private boolean inTick;

        Driver(SelfTestSpec spec) {
            this.spec = spec;
        }

        /** Runs at the end of every client tick. */
        void tick() {
            if (finished || inTick) {
                return;
            }
            inTick = true;
            try {
                if (runner == null) {
                    writePid();
                    try {
                        runner = Scenarios.build(spec, Scenarios.Context.fromSwitches(Minecraft.getMinecraft()));
                    } catch (Throwable t) {
                        finish(false, "could not set up scenario: " + t, List.of());
                        return;
                    }
                }
                StepRunner.State state = runner.tick();
                if (state != StepRunner.State.RUNNING) {
                    finish(state == StepRunner.State.PASSED, runner.failure(), runner.log());
                }
            } finally {
                inTick = false;
            }
        }

        private void finish(boolean passed, String failure, List<StepRunner.StepRecord> steps) {
            finished = true;
            writeResult(passed, failure, steps);
            DropIn.LOGGER.info("Self-test {}:{} {}{}", spec.scenario(), spec.role(), passed ? "PASSED" : "FAILED",
                    failure == null ? "" : " - " + failure);
            // 1.12.2 port: 26.x Minecraft.stop() is Minecraft.shutdown() (ends the game loop).
            Minecraft.getMinecraft().shutdown();
        }

        private synchronized void writeResult(boolean passed, String failure, List<StepRunner.StepRecord> steps) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            SelfTestResult result = new SelfTestResult(spec.scenario(), spec.role(), Agnos.loaderName(), passed, failure,
                    durationMs, steps);
            try {
                result.write(gameDir());
            } catch (Exception e) {
                DropIn.LOGGER.error("Could not write self-test result", e);
            }
        }

        private void writePid() {
            try {
                Path dir = gameDir().resolve(SelfTestResult.DIR);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("pid"), Long.toString(ProcessHandle.current().pid()), StandardCharsets.US_ASCII);
            } catch (Exception e) {
                DropIn.LOGGER.error("Could not write self-test pid file", e);
            }
        }

        private static Path gameDir() {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null ? mc.gameDir.toPath() : Path.of("").toAbsolutePath();
        }

        /** If the client thread hangs, still leave a result and get out of the orchestrator's way. */
        void startWatchdog() {
            long timeoutSec = DropIn.switches().read(TIMEOUT_PROPERTY).map(Long::parseLong).orElse(DEFAULT_TIMEOUT_SEC);
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(timeoutSec * 1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!finished) {
                    StepRunner current = runner;
                    String where = current == null ? "before the first client tick" : "in step '" + current.currentStepName() + "'";
                    writeResult(false, "watchdog: no result after " + timeoutSec + "s, stuck " + where,
                            current == null ? List.of() : current.log());
                    Runtime.getRuntime().halt(2);
                }
            }, "dropin-selftest-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }
    }
}
