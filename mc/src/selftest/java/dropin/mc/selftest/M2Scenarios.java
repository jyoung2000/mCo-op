package dropin.mc.selftest;

import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;
import dropin.core.sync.SyncClientSession;
import dropin.core.sync.SyncClientSession.Phase;
import dropin.core.sync.SyncState;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.host.HostSync;
import dropin.mc.member.RecordApplier;
import dropin.mc.member.SyncClient;
import dropin.mc.menu.CoopActions;
import dropin.mc.net.ProtocolSniffer;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.level.block.Blocks;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static dropin.mc.selftest.M1Scenarios.A_HOSTING;
import static dropin.mc.selftest.M1Scenarios.INVITE_FILE;
import static dropin.mc.selftest.M1Scenarios.WORLD_READY;
import static dropin.mc.selftest.M1Scenarios.base;
import static dropin.mc.selftest.M1Scenarios.createWorld;
import static dropin.mc.selftest.M1Scenarios.hosting;
import static dropin.mc.selftest.M1Scenarios.importWorld;
import static dropin.mc.selftest.M1Scenarios.joinedRemote;
import static dropin.mc.selftest.M1Scenarios.pressPlay;
import static dropin.mc.selftest.M1Scenarios.quitToTitle;
import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.SHORT_TICKS;
import static dropin.mc.selftest.Scenarios.STARTUP_TICKS;
import static dropin.mc.selftest.Scenarios.WORLD_ID;
import static dropin.mc.selftest.Scenarios.WORLD_TICKS;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;

/**
 * The M2 gate scenarios (docs/MILESTONES.md): the sniffer, the first download, live checkpoints, a
 * download that is cut and continued, and the path policy. Development only.
 * <p>
 * A imports a world and hosts it; B knows the world only by its invite, so everything B has of it
 * arrived through world sync.
 */
final class M2Scenarios {
    private static final String K = "text.dropin_coop.";
    private static final int INTERVAL_SECONDS = 5;
    private static final String PROBE_CHUNK = RecordKey.chunk("minecraft:overworld", "region", 0, 0);
    private static final byte[] DIAMOND = "minecraft:diamond_block".getBytes(StandardCharsets.UTF_8);
    /** Written by B when it is in the middle of a download; the orchestrator then kills B's process. */
    private static final String KILL_B = "kill-b";

    private M2Scenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "vanilla-through-sniffer:a" -> snifferA(ctx);
            case "vanilla-through-sniffer:b" -> snifferB(ctx);
            case "first-download-root-match:a" -> rootMatchA(ctx, Map.of(), 0);
            case "first-download-root-match:b" -> rootMatchB(ctx);
            case "live-checkpoint:a" -> liveA(ctx);
            case "live-checkpoint:b" -> liveB(ctx);
            case "resume:a" -> rootMatchA(ctx, Map.of("dropin.dev.syncDelayMs", "60"), 450);
            case "resume:b" -> resumeB(ctx);
            case "resume:b2" -> resumeB2(ctx);
            case "path-policy:a" -> policyA(ctx);
            case "path-policy:b" -> policyB(ctx);
            default -> null;
        };
    }

    // ---- building blocks --------------------------------------------------------------------------

    /** Development switches of this scenario. They are read when the sync code starts, after this action. */
    private static StepRunner dev(StepRunner runner, Map<String, String> switches) {
        return runner.action("set development switches", () -> {
            System.setProperty("dropin.dev.checkpointSeconds", Integer.toString(INTERVAL_SECONDS));
            switches.forEach(System::setProperty);
        });
    }

    private static void command(Minecraft mc, String command) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }

    /**
     * A: makes a world, imports it, hosts it, places the probe block and waits for the first checkpoint.
     *
     * @param minRecords if above zero, a few hundred more chunks are loaded and the world must have at least
     *                   this many records before B is told to come
     */
    private static StepRunner hostA(Context ctx, State state, Map<String, String> switches, int minRecords) {
        Minecraft mc = ctx.mc();
        StepRunner runner = importWorld(quitToTitle(createWorld(dev(base(ctx), switches), ctx), ctx), ctx, state)
                .action("share invite", () -> {
                    ctx.writeShared(INVITE_FILE, state.world.invite().encode());
                    ctx.writeShared(WORLD_READY, "ok");
                })
                .action("press Play", () -> pressPlay(mc, state))
                .step("hosting, announced", WORLD_TICKS, () -> hosting(ctx))
                .action("place the probe block", () -> {
                    command(mc, "forceload add 0 0");
                    command(mc, Scenarios.PROBE_COMMAND);
                    if (minRecords > 0) {
                        command(mc, "forceload add -128 -128 127 127");
                        command(mc, "forceload add 128 -128 383 127");
                    }
                })
                .step("probe block placed", SHORT_TICKS,
                        () -> mc.getSingleplayerServer().overworld().getBlockState(Scenarios.PROBE_POS).is(Blocks.GOLD_BLOCK))
                .step("first checkpoint", WORLD_TICKS, () -> {
                    HostSync sync = HostSync.current();
                    return sync != null && sync.seq() >= 1 && sync.records() >= Math.max(1, minRecords);
                });
        return runner.action("tell B", () -> {
            HostSync sync = HostSync.current();
            DropIn.LOGGER.info("Self-test: hosting with {} records at checkpoint {}", sync.records(), sync.seq());
            ctx.writeShared(A_HOSTING, "ok");
        });
    }

    /** B: joins with the invite only and presses Play once A hosts. Nothing of the world is on B's disk. */
    private static StepRunner joinB(Context ctx, State state, Map<String, String> switches) {
        Minecraft mc = ctx.mc();
        return dev(base(ctx), switches)
                .step("A shared the invite", STARTUP_TICKS + WORLD_TICKS, () -> ctx.sharedExists(WORLD_READY))
                .action("join with invite", () -> {
                    CoopActions.Outcome outcome = CoopActions.joinWithInvite(mc, ctx.readShared(INVITE_FILE));
                    require(outcome.ok(), "join with invite failed: " + outcome.message().getString());
                    state.world = outcome.world();
                    require(!CoopActions.folders(mc).containsKey(state.world.worldId()), "B already has a copy");
                })
                .step("A is hosting", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(A_HOSTING))
                .action("press Play", () -> pressPlay(mc, state))
                .step("landed in A's session", PEER_TICKS, () -> joinedRemote(mc));
    }

    /** B's folder of the world under saves/, or null while there is none. */
    private static Path folder(Minecraft mc, State state) {
        String name = CoopActions.folders(mc).get(state.world.worldId());
        return name == null ? null : mc.getLevelSource().getLevelPath(name);
    }

    /** True once B's copy is complete and the sync is waiting for the next checkpoint. Polled twice a second. */
    private static StepRunner.Step copyComplete(Minecraft mc, State state) {
        int[] ticks = {0};
        int[] waiting = {0};
        return () -> {
            failIfDisconnected(mc);
            SyncClientSession.Status live = SyncClient.status(state.world.worldId());
            if (live != null && live.phase() == Phase.WAITING_DIRECT && ++waiting[0] > 1_200) {
                throw new IllegalStateException("no direct path to the host could be established; bulk sync stays off");
            }
            if (ticks[0]++ % 10 != 0 || live == null || live.phase() != Phase.LIVE) {
                return false;
            }
            Path folder = folder(mc, state);
            return folder != null && SyncClient.savedState(folder).complete();
        };
    }

    /** A: waits until B's copy is complete, quits (the last checkpoint), and reports the root it ended on. */
    private static StepRunner quitAndReportRoot(StepRunner runner, Context ctx, State state, int completeTicks) {
        Minecraft mc = ctx.mc();
        return quitToTitle(runner.step("B's copy is complete", completeTicks, () -> ctx.sharedExists("b-complete")), ctx)
                .action("report the root", () -> {
                    Path file = mc.getLevelSource().getLevelPath(WORLD_ID).resolve(CoopWorldStore.MARKER_DIR).resolve("ledger.jsonl");
                    LedgerEntry last = Ledger.load(state.world.keys().auth(), file).last();
                    require(last != null, "A has no checkpoint");
                    DropIn.LOGGER.info("Self-test: A ended on checkpoint {} with root {}", last.seq(), last.root());
                    ctx.writeShared("a-root", last.seq() + " " + last.root());
                })
                .step("B verified its copy", WORLD_TICKS + SHORT_TICKS, () -> ctx.sharedExists("b-verified"));
    }

    /** B: after A quit, proves from the files on disk that the copy has the root A reported. */
    private static StepRunner verifyRoot(StepRunner runner, Context ctx, State state) {
        Minecraft mc = ctx.mc();
        return runner
                .action("tell A", () -> ctx.writeShared("b-complete", "ok"))
                .step("A quit", PEER_TICKS, () -> mc.gui.screen() instanceof DisconnectedScreen
                        || (mc.level == null && mc.gui.screen() instanceof TitleScreen))
                .action("title screen", () -> mc.gui.setScreen(new TitleScreen()))
                .step("A reported its root", WORLD_TICKS, () -> ctx.sharedExists("a-root"))
                .action("the copy on disk has A's root", () -> {
                    String[] reported = ctx.readShared("a-root").split(" ");
                    Path folder = folder(mc, state);
                    require(folder != null, "B has no folder for the world");
                    SyncClient.stopAndWait(state.world.worldId(), 15_000);
                    require(SyncClient.status(state.world.worldId()) == null, "the sync did not let go of the copy");
                    SyncState saved = SyncClient.savedState(folder);
                    require(saved.complete(), "the copy is not marked complete");
                    require(saved.syncedThrough() == Long.parseLong(reported[0]),
                            "B's copy is at checkpoint " + saved.syncedThrough() + ", A ended on " + reported[0]);
                    Path manifestFile = folder.resolve(CoopWorldStore.MARKER_DIR).resolve("manifest").resolve("current.tsv");
                    String recorded = Manifest.load(manifestFile).root();
                    require(recorded.equals(reported[1]), "B recorded root " + recorded + ", A reported " + reported[1]);
                    // The real proof: every chunk and file of the closed copy, read back and hashed again.
                    Manifest onDisk = RecordApplier.scan(folder);
                    require(onDisk.root().equals(reported[1]), "B's files hash to root " + onDisk.root() + " ("
                            + onDisk.size() + " records), A reported " + reported[1]);
                    DropIn.LOGGER.info("Self-test: {} records on disk hash to A's root {}", onDisk.size(), reported[1]);
                });
    }

    // ---- vanilla-through-sniffer ----------------------------------------------------------------------

    private static StepRunner snifferA(Context ctx) {
        Minecraft mc = ctx.mc();
        return hostA(ctx, new State(), Map.of(), 0)
                .step("B logged in", PEER_TICKS, () -> mc.getSingleplayerServer().getPlayerList().getPlayerCount() == 2)
                .step("B finished", SHORT_TICKS, () -> ctx.sharedExists(Scenarios.PEER_DONE))
                .action("both went through the sniffer", () -> {
                    require(ProtocolSniffer.vanillaConnections() >= 2, "the sniffer passed on "
                            + ProtocolSniffer.vanillaConnections() + " game connections, expected the ping and the login");
                    require(ProtocolSniffer.syncConnections() == 0, "a game connection was taken for world sync");
                });
    }

    /** B is a plain client here: a status ping and an ordinary login by address, no invite and no world sync. */
    private static StepRunner snifferB(Context ctx) {
        Minecraft mc = ctx.mc();
        ServerData data = new ServerData("dropin-selftest", ctx.localAddress(), ServerData.Type.OTHER);
        ServerStatusPinger pinger = new ServerStatusPinger();
        return base(ctx)
                .step("A is hosting", STARTUP_TICKS + WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(A_HOSTING))
                .action("send status ping", () -> pinger.pingServer(data, () -> {}, () -> {}, EventLoopGroupHolder.remote(false)))
                .step("status response", SHORT_TICKS, () -> {
                    pinger.tick();
                    return data.players != null;
                })
                .action("check status", () -> {
                    require(data.players.online() == 1, "expected 1 player online in the status response, got " + data.players.online());
                    pinger.removeAll();
                })
                .action("connect", () -> ConnectScreen.startConnecting(new TitleScreen(), mc,
                        ServerAddress.parseString(ctx.localAddress()), data, false, null))
                .step("joined world", SHORT_TICKS, () -> joinedRemote(mc))
                .step("sees A", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return mc.level != null && mc.level.players().size() == 2;
                })
                .action("tell A", () -> ctx.writeShared(Scenarios.PEER_DONE, "ok"));
    }

    // ---- first-download-root-match, resume --------------------------------------------------------------

    private static StepRunner rootMatchA(Context ctx, Map<String, String> switches, int minRecords) {
        State state = new State();
        // The resume run includes a second start of B's game.
        int completeTicks = minRecords > 0 ? STARTUP_TICKS + 2 * PEER_TICKS : 2 * PEER_TICKS;
        return quitAndReportRoot(hostA(ctx, state, switches, minRecords), ctx, state, completeTicks);
    }

    private static StepRunner rootMatchB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = joinB(ctx, state, Map.of())
                .action("plays while downloading", () -> {
                    require(mc.player != null && mc.getSingleplayerServer() == null, "B is not playing on A's session");
                    Path folder = folder(mc, state);
                    DropIn.LOGGER.info("Self-test: B is in the world; copy so far: {}",
                            folder == null ? "none" : SyncClient.savedState(folder).percent() + "%");
                })
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state));
        return verifyRoot(runner, ctx, state)
                .action("press Play", () -> pressPlay(mc, state))
                .step("B hosts its own copy", WORLD_TICKS, () -> hosting(ctx))
                .action("load the probe chunk", () -> command(mc, "forceload add 0 0"))
                .step("the block A placed is there", SHORT_TICKS,
                        () -> mc.getSingleplayerServer().overworld().getBlockState(Scenarios.PROBE_POS).is(Blocks.GOLD_BLOCK))
                .action("tell A", () -> ctx.writeShared("b-verified", "ok"));
    }

    /** B, first start: gets into the download far enough that part of it is safely on disk, then is killed. */
    private static StepRunner resumeB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        int[] ticks = {0};
        return joinB(ctx, state, Map.of())
                .step("mid-download, part of it on disk", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    if (ticks[0]++ % 5 != 0) {
                        return false;
                    }
                    Path folder = folder(mc, state);
                    if (folder == null) {
                        return false;
                    }
                    SyncState saved = SyncClient.savedState(folder);
                    require(!saved.complete(), "the download finished before it could be cut; the world is too small");
                    return saved.have() > 0;
                })
                .action("ask to be killed", () -> ctx.writeShared(KILL_B, "ok"))
                .step("killed by the orchestrator", SHORT_TICKS, () -> false);
    }

    /** B, second start: the unfinished copy is still there; Play continues the download instead of restarting it. */
    private static StepRunner resumeB2(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        int[] kept = {0};
        StepRunner runner = dev(base(ctx), Map.of())
                .action("the unfinished copy survived", () -> {
                    require(CoopWorlds.store().list().size() == 1, "B does not know the world any more");
                    state.world = CoopWorlds.store().list().get(0);
                    Path folder = folder(mc, state);
                    require(folder != null, "B's folder is gone");
                    require(SyncClient.incomplete(folder), "the copy is not marked as an unfinished download");
                    kept[0] = Manifest.load(folder.resolve(CoopWorldStore.MARKER_DIR).resolve("manifest").resolve("current.tsv")).size();
                    require(kept[0] > 0, "nothing of the first download was kept");
                    DropIn.LOGGER.info("Self-test: {} records kept from before the kill, {}% by the saved state", kept[0],
                            SyncClient.savedState(folder).percent());
                })
                .action("press Play", () -> pressPlay(mc, state))
                .step("landed in A's session", PEER_TICKS, () -> joinedRemote(mc))
                // The first thing the new connection reports: how much of the world it found already here.
                .step("the download picks up where it stopped", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    SyncClientSession.Status live = SyncClient.status(state.world.worldId());
                    if (live == null || (live.phase() != Phase.DOWNLOADING && live.phase() != Phase.LIVE)) {
                        return false;
                    }
                    DropIn.LOGGER.info("Self-test: after the restart the sync starts at {} of {} records; {} were kept",
                            live.have(), live.total(), kept[0]);
                    // A's world goes on changing while B is away, so some kept records are out of date; most are not.
                    require(live.have() >= kept[0] / 2, "the download started over: only " + live.have() + " of the "
                            + kept[0] + " kept records counted as present");
                    require(live.have() < live.total(), "nothing was left to download");
                    return true;
                })
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("fetched after the restart", () -> DropIn.LOGGER.info("Self-test: {} record writes after the restart",
                        SyncClient.appliedRecords(state.world.worldId())));
        return verifyRoot(runner, ctx, state)
                .action("tell A", () -> ctx.writeShared("b-verified", "ok"));
    }

    // ---- live-checkpoint ------------------------------------------------------------------------------

    private static StepRunner liveA(Context ctx) {
        Minecraft mc = ctx.mc();
        return hostA(ctx, new State(), Map.of(), 0)
                .step("B's copy is complete", 2 * PEER_TICKS, () -> ctx.sharedExists("b-live"))
                .action("place a block", () -> {
                    IntegratedServer server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                                "setblock 0 251 0 minecraft:diamond_block");
                        try {
                            ctx.writeShared("a-placed", Long.toString(System.currentTimeMillis()));
                        } catch (java.io.IOException e) {
                            DropIn.LOGGER.error("Could not tell B", e);
                        }
                    });
                })
                .step("B has the block in its copy", SHORT_TICKS, () -> ctx.sharedExists("b-saw"));
    }

    private static StepRunner liveB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        long[] seen = {0};
        int[] ticks = {0};
        return joinB(ctx, state, Map.of())
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("the block is not in the copy yet", () -> {
                    byte[] chunk = SyncClient.localChunk(state.world.worldId(), PROBE_CHUNK);
                    require(chunk != null, "the probe chunk is not in B's copy");
                    require(!contains(chunk, DIAMOND), "the block is there before A placed it");
                    ctx.writeShared("b-live", "ok");
                })
                .step("A placed the block", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("a-placed");
                })
                // Two intervals at 20 ticks a second, and a little slack for a slow client tick.
                .step("the block reaches B's copy", 2 * INTERVAL_SECONDS * 20 + 100, () -> {
                    failIfDisconnected(mc);
                    if (ticks[0]++ % 4 != 0) {
                        return false;
                    }
                    byte[] chunk = SyncClient.localChunk(state.world.worldId(), PROBE_CHUNK);
                    if (chunk == null || !contains(chunk, DIAMOND)) {
                        return false;
                    }
                    seen[0] = System.currentTimeMillis();
                    return true;
                })
                .action("within two checkpoint intervals", () -> {
                    long took = seen[0] - Long.parseLong(ctx.readShared("a-placed"));
                    DropIn.LOGGER.info("Self-test: the block reached B's copy after {} ms (interval {} s)", took, INTERVAL_SECONDS);
                    require(took <= 2L * INTERVAL_SECONDS * 1000, "the block took " + took + " ms, more than two intervals of "
                            + INTERVAL_SECONDS + " s");
                    ctx.writeShared("b-saw", "ok");
                });
    }

    private static boolean contains(byte[] data, byte[] part) {
        outer:
        for (int i = 0; i <= data.length - part.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (data[i + j] != part[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    // ---- path-policy ----------------------------------------------------------------------------------

    private static final Map<String, String> RELAYED = Map.of("dropin.dev.forcePath", "relayed");

    private static StepRunner policyA(Context ctx) {
        return hostA(ctx, new State(), RELAYED, 0)
                .step("B is waiting for a direct path", 2 * PEER_TICKS, () -> ctx.sharedExists("b-waiting"))
                .action("no record was sent", () -> {
                    HostSync sync = HostSync.current();
                    require(sync.readyPeers() == 1, "expected B's sync connection, got " + sync.readyPeers());
                    require(sync.recordFrames() == 0, sync.recordFrames() + " record frames went over a relayed path");
                    ctx.writeShared(Scenarios.HOST_DONE, "ok");
                })
                .step("B finished", SHORT_TICKS, () -> ctx.sharedExists(Scenarios.PEER_DONE));
    }

    private static StepRunner policyB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        int[] held = {0};
        return joinB(ctx, state, RELAYED)
                .step("the waiting message shows", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    SyncClientSession.Status live = SyncClient.status(state.world.worldId());
                    return live != null && live.phase() == Phase.WAITING_DIRECT && SyncClient.waitingShown(state.world.worldId());
                })
                .step("it keeps waiting", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    SyncClientSession.Status live = SyncClient.status(state.world.worldId());
                    require(live != null && live.phase() == Phase.WAITING_DIRECT, "the sync left the waiting state");
                    return ++held[0] >= 200;
                })
                .action("nothing was downloaded", () -> {
                    String id = state.world.worldId();
                    require(SyncClient.appliedRecords(id) == 0, SyncClient.appliedRecords(id) + " records arrived over a relayed path");
                    Path folder = folder(mc, state);
                    require(folder == null || !Files.exists(folder.resolve("level.dat")), "B has world data");
                    Component line = SyncClient.statusLine(id, folder);
                    require(line != null && line.getString().equals("Waiting for a direct connection to sync world data."),
                            "status line is '" + (line == null ? "" : line.getString()) + "'");
                    require(!line.getString().startsWith(K), "the waiting message has no translation");
                    ctx.writeShared("b-waiting", "ok");
                })
                .step("A checked", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists(Scenarios.HOST_DONE);
                })
                .action("tell A", () -> ctx.writeShared(Scenarios.PEER_DONE, "ok"));
    }
}
