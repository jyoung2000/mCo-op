package dropin.mc.selftest;

import dropin.mc.DropIn;
import dropin.mc.host.HostSync;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static dropin.mc.selftest.M1Scenarios.A_HOSTING;
import static dropin.mc.selftest.M1Scenarios.INVITE_FILE;
import static dropin.mc.selftest.M1Scenarios.WORLD_READY;
import static dropin.mc.selftest.M1Scenarios.base;
import static dropin.mc.selftest.M1Scenarios.hosting;
import static dropin.mc.selftest.M1Scenarios.importWorld;
import static dropin.mc.selftest.M1Scenarios.pressPlay;
import static dropin.mc.selftest.M1Scenarios.quitToTitle;
import static dropin.mc.selftest.M2Scenarios.copyComplete;
import static dropin.mc.selftest.M2Scenarios.dev;
import static dropin.mc.selftest.M2Scenarios.joinB;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.SHORT_TICKS;
import static dropin.mc.selftest.Scenarios.WORLD_ID;
import static dropin.mc.selftest.Scenarios.WORLD_TICKS;
import static dropin.mc.selftest.Scenarios.inWorld;

/**
 * A measurement, not a gate: what hosting costs the host's game. A plays a normal-terrain world with 512
 * force-loaded chunks alone, then hosts it, then B downloads it and stays. In every phase the same commands
 * change eight chunks a second. Logged per phase: server tick times, the longest time without a server tick,
 * the CPU the game used, and what the checkpoints cost. Development only.
 */
final class PerfScenarios {
    private static final int WINDOW_TICKS = 1_200;
    private static final int SETTLE_TICKS = 400;
    /** Chunks of the force-loaded area: x -8..23, z -8..7. */
    private static final int AREA_CHUNKS = 512;
    private static final Map<String, String> SWITCHES = Map.of("dropin.dev.checkpointSeconds", "10");

    private PerfScenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "perf:a" -> perfA(ctx);
            case "perf:b" -> perfB(ctx);
            default -> null;
        };
    }

    /** Server tick times of one phase, read from the client thread once per client tick. */
    private static final class Sampler {
        private final List<Long> tickNanos = new ArrayList<>();
        private int lastTick = -1;
        private long startNanos;
        private long lastAdvance;
        private long maxStallNanos;
        private long startCpu;

        void poll(MinecraftServer server) {
            long now = System.nanoTime();
            int tick = server.getTickCount();
            if (lastTick < 0) {
                lastTick = tick;
                startNanos = now;
                lastAdvance = now;
                startCpu = cpuNanos();
                return;
            }
            if (tick != lastTick) {
                // Tick n is stored at n % 100 when it ends; tick `tick` may still be running.
                long[] times = server.getTickTimesNanos();
                for (int t = Math.max(lastTick, tick - 100); t < tick; t++) {
                    tickNanos.add(times[t % 100]);
                }
                lastTick = tick;
                maxStallNanos = Math.max(maxStallNanos, now - lastAdvance);
                lastAdvance = now;
            }
        }

        String report() {
            double seconds = (System.nanoTime() - startNanos) / 1e9;
            List<Long> sorted = new ArrayList<>(tickNanos);
            Collections.sort(sorted);
            int n = sorted.size();
            double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
            double p99 = n == 0 ? 0 : sorted.get(Math.min(n - 1, (int) (n * 0.99))) / 1e6;
            double max = n == 0 ? 0 : sorted.get(n - 1) / 1e6;
            double cores = (cpuNanos() - startCpu) / 1e9 / seconds;
            return String.format(Locale.ROOT, "%.0f s, %d ticks (%.1f per s), tick avg %.2f ms, p99 %.2f ms, max %.1f ms, "
                    + "longest gap between ticks %.0f ms, cpu %.2f cores", seconds, n, n / seconds, avg, p99, max,
                    maxStallNanos / 1e6, cores);
        }

        private static long cpuNanos() {
            return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
        }
    }

    private static void command(MinecraftServer server, String command) {
        server.execute(() -> server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    /** Eight chunks of the area get a layer of blocks replaced: the same load in every phase. */
    private static void activity(MinecraftServer server, int second) {
        for (int i = 0; i < 8; i++) {
            int k = second * 8 + i;
            int chunk = k % AREA_CHUNKS;
            int x = (-8 + chunk % 32) * 16;
            int z = (-8 + chunk / 32) * 16;
            String block = (k / AREA_CHUNKS) % 2 == 0 ? "minecraft:stone" : "minecraft:glass";
            command(server, "fill " + x + " 300 " + z + " " + (x + 15) + " 300 " + (z + 15) + " " + block);
        }
    }

    private static StepRunner pause(StepRunner runner, String name, int ticks) {
        int[] count = {0};
        return runner.step(name, ticks + SHORT_TICKS, () -> ++count[0] >= ticks);
    }

    /** One measured phase: {@code ticks} client ticks long, or until {@code until} says so (at most maxTicks). */
    private static StepRunner measure(StepRunner runner, Minecraft mc, String name, int ticks, int maxTicks,
                                      BooleanSupplier until) {
        Sampler[] sampler = {null};
        int[] count = {0};
        return runner
                .action("start: " + name, () -> {
                    sampler[0] = new Sampler();
                    HostSync sync = HostSync.current();
                    if (sync != null) {
                        sync.takePerf();
                    }
                })
                .step("measure: " + name, maxTicks, () -> {
                    IntegratedServer server = mc.getSingleplayerServer();
                    sampler[0].poll(server);
                    if (count[0] % 20 == 0) {
                        activity(server, count[0] / 20);
                    }
                    count[0]++;
                    return until == null ? count[0] >= ticks : until.getAsBoolean();
                })
                .action("report: " + name, () -> {
                    DropIn.LOGGER.info("Self-test perf: [{}] {}", name, sampler[0].report());
                    reportCheckpoints(name);
                });
    }

    private static long sentBefore;

    private static void reportCheckpoints(String name) {
        HostSync sync = HostSync.current();
        if (sync == null) {
            return;
        }
        HostSync.Perf perf = sync.takePerf();
        int n = Math.max(1, perf.checkpoints());
        long bytes = sync.recordBytes();
        DropIn.LOGGER.info("Self-test perf: [{}] {} checkpoints, {} records changed; server thread held by the save: avg {} ms, "
                + "max {} ms; work off the server thread: avg {} ms, max {} ms, CPU time of that work avg {} ms; world record "
                + "bytes sent to members in this phase: {}", name, perf.checkpoints(), perf.changed(),
                perf.saveMsSum() / n, perf.saveMsMax(), perf.workMsSum() / n, perf.workMsMax(), perf.cpuMsSum() / n,
                bytes - sentBefore);
        sentBefore = bytes;
    }

    private static boolean areaLoaded(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        ServerChunkCache chunks = server.overworld().getChunkSource();
        for (int cx = -8; cx <= 23; cx++) {
            for (int cz = -8; cz <= 7; cz++) {
                if (!chunks.hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static StepRunner perfA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = dev(base(ctx), SWITCHES)
                .action("create a normal-terrain world", () -> {
                    LevelSettings settings = new LevelSettings(WORLD_ID, GameType.CREATIVE,
                            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
                            WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel(WORLD_ID, settings, new WorldOptions(Scenarios.SEED, true, false),
                            registries -> registries.lookupOrThrow(Registries.WORLD_PRESET)
                                    .getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
                            mc.gui.screen());
                })
                .step("world loaded", 2 * WORLD_TICKS, () -> inWorld(mc) && mc.getSingleplayerServer() != null)
                .action("force-load 512 chunks", () -> {
                    command(mc.getSingleplayerServer(), "forceload add -128 -128 127 127");
                    command(mc.getSingleplayerServer(), "forceload add 128 -128 383 127");
                })
                .step("the chunks are generated", 4 * WORLD_TICKS, () -> areaLoaded(mc));
        runner = pause(runner, "settle", SETTLE_TICKS);
        runner = measure(runner, mc, "1 alone, not hosting", WINDOW_TICKS, WINDOW_TICKS + SHORT_TICKS, null)
                .action("what one full save costs without the mod", () -> {
                    IntegratedServer server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        long from = System.nanoTime();
                        server.saveEverything(true, false, false);
                        DropIn.LOGGER.info("Self-test perf: [1 alone, not hosting] one game save (what the autosave does "
                                + "every 5 minutes) held the server thread {} ms", (System.nanoTime() - from) / 1_000_000);
                    });
                });
        runner = pause(runner, "the save ran", 100);
        runner = importWorld(quitToTitle(runner, ctx), ctx, state)
                .action("share invite", () -> {
                    ctx.writeShared(INVITE_FILE, state.world.invite().encode());
                    ctx.writeShared(WORLD_READY, "ok");
                })
                .action("press Play", () -> pressPlay(mc, state))
                .step("hosting, announced", 2 * WORLD_TICKS, () -> hosting(ctx))
                .step("first checkpoint", 2 * WORLD_TICKS, () -> {
                    HostSync sync = HostSync.current();
                    return sync != null && sync.seq() >= 1 && areaLoaded(mc);
                })
                .action("report the first checkpoint", () -> {
                    DropIn.LOGGER.info("Self-test perf: hosting with {} records", HostSync.current().records());
                    reportCheckpoints("first checkpoint, full scan");
                });
        runner = pause(runner, "settle", SETTLE_TICKS);
        runner = measure(runner, mc, "2 hosting, nobody joined", WINDOW_TICKS, WINDOW_TICKS + SHORT_TICKS, null)
                .action("tell B", () -> ctx.writeShared(A_HOSTING, "ok"))
                .step("B dropped in", 2 * PEER_TICKS, () -> mc.getSingleplayerServer().getPlayerList().getPlayerCount() == 2);
        runner = measure(runner, mc, "3 hosting, B downloading the world", 0, 4 * PEER_TICKS, () -> ctx.sharedExists("b-complete"));
        runner = measure(runner, mc, "4 hosting, B has its copy", WINDOW_TICKS, WINDOW_TICKS + SHORT_TICKS, null)
                .action("tell B", () -> ctx.writeShared("a-done", "ok"));
        return quitToTitle(runner, ctx);
    }

    private static StepRunner perfB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        long[] joined = {0};
        return joinB(ctx, state, SWITCHES)
                .action("note the time", () -> joined[0] = System.currentTimeMillis())
                .step("copy complete", 4 * PEER_TICKS, copyComplete(mc, state))
                .action("tell A", () -> {
                    DropIn.LOGGER.info("Self-test perf: B had its full copy {} ms after it joined",
                            System.currentTimeMillis() - joined[0]);
                    ctx.writeShared("b-complete", "ok");
                })
                .step("A is done", 4 * PEER_TICKS, () -> ctx.sharedExists("a-done"));
    }
}
