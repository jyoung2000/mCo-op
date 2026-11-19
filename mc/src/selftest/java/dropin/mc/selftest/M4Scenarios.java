package dropin.mc.selftest;

import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.pkarr.RendezvousRecord;
import dropin.core.sync.Election;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopLauncher;
import dropin.mc.coop.HostSession;
import dropin.mc.handoff.CrashFailover;
import dropin.mc.host.HostSync;
import dropin.mc.member.SyncClient;
import dropin.mc.menu.SessionLine;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static dropin.mc.selftest.M1Scenarios.hosting;
import static dropin.mc.selftest.M1Scenarios.pressPlay;
import static dropin.mc.selftest.M1Scenarios.quitToTitle;
import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.M2Scenarios.copyComplete;
import static dropin.mc.selftest.M2Scenarios.folder;
import static dropin.mc.selftest.M2Scenarios.hostA;
import static dropin.mc.selftest.M2Scenarios.joinB;
import static dropin.mc.selftest.M3Scenarios.players;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.SHORT_TICKS;
import static dropin.mc.selftest.Scenarios.WORLD_ID;
import static dropin.mc.selftest.Scenarios.WORLD_TICKS;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;
import static dropin.mc.selftest.Scenarios.inWorld;

/**
 * The M4 gate scenarios (docs/MILESTONES.md): the host's game is killed and a member takes over; the elected
 * member is killed too and the next one takes over after the timeout; and the warning shown when a copy is
 * behind the last session and nobody is online. Development only.
 * <p>
 * The games of roles a (and b in {@code crash-fallthrough}) never report: {@code scripts/selftest.ps1} kills
 * them the hard way when the shared file {@code kill-a} appears.
 */
final class M4Scenarios {
    /** Placed by A before the checkpoint that everyone must still have after the crash. */
    private static final BlockPos BLOCK_POS = new BlockPos(0, 251, 0);
    private static final String BLOCK_COMMAND = "setblock 0 251 0 minecraft:diamond_block";

    /**
     * With the handoff on, as real players have it. Checkpoints are 20 s apart so that the one with the block,
     * the member list that follows it and the kill all fall between two checkpoints.
     */
    private static final Map<String, String> SWITCHES = Map.of("dropin.dev.noHandoff", "false",
            "dropin.dev.checkpointSeconds", "20");
    /** C answers the host's upload test late: it is measured as slow, so B is first in the order. */
    private static final Map<String, String> SLOW = Map.of("dropin.dev.noHandoff", "false",
            "dropin.dev.checkpointSeconds", "20", "dropin.dev.speedDelayMs", "2000");

    private M4Scenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "crash-failover:a", "crash-fallthrough:a" -> crashA(ctx);
            case "crash-failover:b" -> electedB(ctx);
            case "crash-failover:c" -> followerC(ctx);
            case "crash-fallthrough:b" -> doomedB(ctx);
            case "crash-fallthrough:c" -> nextC(ctx);
            case "stale-warning:a" -> staleA(ctx);
            case "stale-warning:b" -> staleB(ctx);
            default -> null;
        };
    }

    // ---- building blocks --------------------------------------------------------------------------

    private static void command(Minecraft mc, String command) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }

    private static boolean blockOnServer(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        return server != null && server.overworld().getBlockState(BLOCK_POS).is(Blocks.DIAMOND_BLOCK);
    }

    private static Election.Peer peer(List<Election.Peer> peers, String uuid) {
        return peers.stream().filter(peer -> peer.uuid().equals(uuid)).findFirst().orElse(null);
    }

    private static void freeLanPort() throws IOException {
        // The games of one run share a LAN port; a game that takes over picks a free one, as real players do.
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
            System.setProperty("dropin.dev.lanPort", Integer.toString(free.getLocalPort()));
        }
    }

    /** A: hosts, places a block, waits until a checkpoint with it is on B and C, and asks to be killed. */
    private static StepRunner crashA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        long[] before = {0};
        return hostA(ctx, state, SWITCHES, 0)
                .step("B and C dropped in", 2 * PEER_TICKS, () -> players(mc) == 3)
                .step("B and C have full copies", 2 * PEER_TICKS, () -> ctx.sharedExists("b-copied") && ctx.sharedExists("c-copied"))
                .action("place a block", () -> {
                    before[0] = HostSync.current().seq();
                    command(mc, BLOCK_COMMAND);
                })
                .step("block placed", SHORT_TICKS, () -> blockOnServer(mc))
                .step("a checkpoint with the block is on B and C", WORLD_TICKS, () -> {
                    HostSync sync = HostSync.current();
                    return sync.seq() > before[0] && sync.syncedByAll() >= sync.seq();
                })
                .action("tell B and C", () -> {
                    LedgerEntry head = HostSync.current().head();
                    DropIn.LOGGER.info("Self-test: the block is in checkpoint {} (root {}), which B and C have", head.seq(), head.root());
                    ctx.writeShared("a-seq", head.seq() + " " + head.root());
                })
                .step("B and C know who comes next", PEER_TICKS, () -> ctx.sharedExists("b-ready") && ctx.sharedExists("c-ready"))
                .action("ask to be killed", () -> {
                    DropIn.LOGGER.info("Self-test: A asks the script to kill this game now");
                    ctx.writeShared("kill-a", "ok");
                })
                // The script ends this game without a result. Still being here is the failure.
                .step("killed", PEER_TICKS, () -> false);
    }

    /**
     * B or C up to the kill: a full copy, the checkpoint with the block applied, and the host's member list
     * naming B first and C second.
     */
    private static StepRunner member(Context ctx, State state, String who) {
        Minecraft mc = ctx.mc();
        int[] drawn = {0};
        StepRunner runner = who.equals("b") ? joinB(ctx, state, SWITCHES) : joinB(ctx, state, SLOW, "b-copied");
        return runner
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("tell A", () -> {
                    freeLanPort();
                    ctx.writeShared(who + "-uuid", mc.getUser().getProfileId().toString());
                    ctx.writeShared(who + "-copied", "ok");
                })
                .step("the checkpoint with the block is here, and the host's list shows B and C at it", 2 * PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    if (!ctx.sharedExists("a-seq") || !ctx.sharedExists("b-uuid") || !ctx.sharedExists("c-uuid")) {
                        return false;
                    }
                    long seq = Long.parseLong(ctx.readShared("a-seq").split(" ")[0]);
                    List<Election.Peer> peers = CrashFailover.peers();
                    Election.Peer b = peer(peers, ctx.readShared("b-uuid"));
                    Election.Peer c = peer(peers, ctx.readShared("c-uuid"));
                    LedgerEntry mine = SyncClient.lastCheckpoint(state.world.worldId());
                    return b != null && c != null && b.syncedSeq() >= seq && c.syncedSeq() >= seq
                            && b.uploadKBps() > 0 && c.uploadKBps() > 0 && mine != null && mine.seq() >= seq;
                })
                .action("the order is B, then C; the status line is right", () -> {
                    String worldId = state.world.worldId();
                    List<Election.Peer> peers = CrashFailover.peers();
                    List<String> order = Election.order(peers, CrashFailover.hostUuid(worldId));
                    require(order.equals(List.of(ctx.readShared("b-uuid"), ctx.readShared("c-uuid"))),
                            "the order is " + order + " from " + peers + ", expected B then C");
                    SessionLine.Info info = SessionLine.info(mc);
                    require(info != null, "no status line in a co-op session");
                    require(!info.hosting() && info.hostName().equals("DevA"), "the status line names '" + info.hostName() + "' as host");
                    require(info.percent() == 100, "the status line says " + info.percent() + "%, the copy is complete");
                    require(ctx.online() || Boolean.TRUE.equals(info.direct()), "the status line does not say the path is direct: " + info.direct());
                    require(info.ageSeconds() >= 0 && info.ageSeconds() < 60, "the last checkpoint is said to be " + info.ageSeconds() + " s old");
                    String text = SessionLine.text(info).getString();
                    require(text.contains("DevA") && text.contains("100"), "the status line reads '" + text + "'");
                    DropIn.LOGGER.info("Self-test: {} sees the order B, C; status line: {}", who.toUpperCase(), text);
                    mc.gui.setScreen(new PauseScreen(true));
                })
                .step("the pause menu is drawn with the line", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    require(mc.gui.screen() instanceof PauseScreen, "the screen is " + mc.gui.screen());
                    return ++drawn[0] > 20;
                })
                .action("tell A", () -> {
                    mc.gui.setScreen(null);
                    ctx.writeShared(who + "-ready", "ok");
                });
    }

    /** The game that took over: its copy is at the checkpoint with the block or a later one, and the block is there. */
    private static StepRunner tookOver(StepRunner runner, Context ctx, String who) {
        Minecraft mc = ctx.mc();
        return runner
                .action("it opened its copy at its last applied checkpoint", () -> {
                    String[] reported = ctx.readShared("a-seq").split(" ");
                    long seq = Long.parseLong(reported[0]);
                    require(CrashFailover.takeoverSeq() >= seq, who + " took over at checkpoint " + CrashFailover.takeoverSeq()
                            + ", the block is in " + seq);
                    require(CrashFailover.takeoverSeq() > seq || CrashFailover.takeoverRoot().equals(reported[1]),
                            who + " took over with root " + CrashFailover.takeoverRoot() + ", A's checkpoint " + seq + " has " + reported[1]);
                    DropIn.LOGGER.info("Self-test: {} took over {} ms after losing the host, at checkpoint {} with root {}", who,
                            CrashFailover.resolvedAfterMs(), CrashFailover.takeoverSeq(), CrashFailover.takeoverRoot());
                    command(mc, "forceload add 0 0");
                })
                .step("the block placed before the last checkpoint is there", SHORT_TICKS, () -> blockOnServer(mc))
                .action("the status line says so", () -> {
                    SessionLine.Info info = SessionLine.info(mc);
                    require(info != null && info.hosting(), "the status line does not say this game hosts");
                });
    }

    // ---- crash-failover ---------------------------------------------------------------------------

    private static StepRunner electedB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        // No input from here on: A is killed, and this game must end up hosting by itself.
        StepRunner runner = member(ctx, state, "b")
                .step("took over as host", 3 * PEER_TICKS, () -> CrashFailover.lastRole() == CrashFailover.Role.ELECTED && hosting(ctx))
                .action("it was B's turn at once", () -> {
                    String me = mc.getUser().getProfileId().toString();
                    require(!CrashFailover.lastOrder().isEmpty() && CrashFailover.lastOrder().get(0).equals(me),
                            "B took over although the order was " + CrashFailover.lastOrder());
                    require(CrashFailover.resolvedAfterMs() < Election.STEP_MS, "B took " + CrashFailover.resolvedAfterMs()
                            + " ms, as long as a member that is not first");
                });
        return tookOver(runner, ctx, "B")
                .action("tell C", () -> ctx.writeShared("b-hosting", "ok"))
                .step("C is back", 3 * PEER_TICKS, () -> players(mc) == 2)
                .step("C confirmed it", PEER_TICKS, () -> ctx.sharedExists("c-back"))
                .action("tell C", () -> ctx.writeShared("b-checked", "ok"))
                .step("C left", PEER_TICKS, () -> players(mc) == 1);
    }

    private static StepRunner followerC(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        // No input from here on: A is killed, B takes over, and this game must be back in the world by itself.
        return member(ctx, state, "c")
                .step("back in the world, on B's session", 4 * PEER_TICKS, () -> CrashFailover.lastRole() == CrashFailover.Role.FOLLOWER
                        && ctx.sharedExists("b-hosting") && inWorld(mc) && mc.getSingleplayerServer() == null
                        && SyncClient.status(state.world.worldId()) != null)
                .step("C sees the block", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return mc.level != null && mc.level.getBlockState(BLOCK_POS).is(Blocks.DIAMOND_BLOCK);
                })
                .action("the host is B now", () -> {
                    SessionLine.Info info = SessionLine.info(mc);
                    require(info != null && !info.hosting() && info.hostName().equals("DevB"),
                            "the status line names '" + (info == null ? null : info.hostName()) + "' as host, expected DevB");
                    DropIn.LOGGER.info("Self-test: C found the new host {} ms after losing A", CrashFailover.resolvedAfterMs());
                    ctx.writeShared("c-back", "ok");
                })
                .step("B checked", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("b-checked");
                });
    }

    // ---- crash-fallthrough ------------------------------------------------------------------------

    /** B is first in the order and is killed together with A: it never takes over. */
    private static StepRunner doomedB(Context ctx) {
        return member(ctx, new State(), "b").step("killed", PEER_TICKS, () -> false);
    }

    private static StepRunner nextC(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        // No input from here on: A and B are killed; B's turn passes unused, then this game hosts.
        StepRunner runner = member(ctx, state, "c")
                .step("took over as host after B's turn", 3 * PEER_TICKS, () -> CrashFailover.lastRole() == CrashFailover.Role.ELECTED && hosting(ctx))
                .action("C waited for B first", () -> {
                    String me = mc.getUser().getProfileId().toString();
                    require(CrashFailover.lastOrder().equals(List.of(ctx.readShared("b-uuid"), me)),
                            "the order was " + CrashFailover.lastOrder() + ", expected B then C");
                    require(CrashFailover.resolvedAfterMs() >= Election.STEP_MS, "C took over after " + CrashFailover.resolvedAfterMs()
                            + " ms, before B's " + Election.STEP_MS + " ms were over");
                });
        return tookOver(runner, ctx, "C");
    }

    // ---- stale-warning ----------------------------------------------------------------------------

    /** A: hosts with B, then hosts a second session alone and changes the world in it. */
    private static StepRunner staleA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner first = hostA(ctx, state, Map.of(), 0)
                .step("B has a full copy and left", 4 * PEER_TICKS, () -> ctx.sharedExists("b-left") && players(mc) == 1);
        StepRunner second = quitToTitle(first, ctx)
                .action("press Play", () -> pressPlay(mc, state))
                .step("hosting again", WORLD_TICKS, () -> hosting(ctx))
                .action("place a block", () -> command(mc, BLOCK_COMMAND))
                .step("block placed", SHORT_TICKS, () -> blockOnServer(mc));
        return quitToTitle(second, ctx)
                .action("report the last checkpoint", () -> {
                    Path file = mc.getLevelSource().getLevelPath(WORLD_ID).resolve(CoopWorldStore.MARKER_DIR).resolve("ledger.jsonl");
                    LedgerEntry last = Ledger.load(state.world.keys().auth(), file).last();
                    require(last != null, "A has no checkpoint");
                    DropIn.LOGGER.info("Self-test: A's second session ended on checkpoint {}", last.seq());
                    ctx.writeShared("a-done", Long.toString(last.seq()));
                })
                .step("B checked", 2 * PEER_TICKS, () -> ctx.sharedExists("b-checked"));
    }

    /** B: its copy is one session behind and nobody is online. Cancel changes nothing; "Play anyway" hosts the older copy. */
    private static StepRunner staleB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        Map<String, String> before = new TreeMap<>();
        int[] stayed = {0};
        return joinB(ctx, state, Map.of())
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("leave", () -> mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE))
                .step("left", SHORT_TICKS, () -> mc.level == null)
                .action("tell A", () -> {
                    mc.gui.setScreen(new TitleScreen());
                    ctx.writeShared("b-left", "ok");
                })
                .step("A played a session without B and quit", 3 * WORLD_TICKS, () -> ctx.sharedExists("a-done"))
                .action("note every file of the copy, press Play", () -> {
                    SyncClient.stopAndWait(state.world.worldId(), 15_000);
                    require(SyncClient.status(state.world.worldId()) == null, "the sync did not let go of the copy");
                    before.putAll(snapshot(folder(mc, state)));
                    require(before.size() > 3, "the copy has only " + before.size() + " files");
                    pressPlay(mc, state);
                })
                .step("the warning is shown", SHORT_TICKS, () -> CoopLauncher.staleWarning() != null && mc.gui.screen() instanceof ConfirmScreen)
                .action("it is about A's last session; Cancel", () -> {
                    RendezvousRecord record = CoopLauncher.staleWarning();
                    long seq = Long.parseLong(ctx.readShared("a-done"));
                    require(record.isState(RendezvousRecord.IDLE), "the record is '" + record.st() + "', expected idle");
                    require(record.name().equals("DevA") && record.seq() == seq, "the warning is about " + record.name()
                            + " at checkpoint " + record.seq() + ", expected DevA at " + seq);
                    Path file = folder(mc, state).resolve(CoopWorldStore.MARKER_DIR).resolve("ledger.jsonl");
                    LedgerEntry mine = Ledger.load(state.world.keys().auth(), file).last();
                    require(mine != null && mine.seq() < seq, "B's copy is at checkpoint " + (mine == null ? 0 : mine.seq()) + ", not behind " + seq);
                    DropIn.LOGGER.info("Self-test: warning shown: B's copy is at checkpoint {}, {}'s last session ended on {}",
                            mine.seq(), record.name(), seq);
                    CoopLauncher.answerStale(false);
                })
                .step("nothing was opened", SHORT_TICKS, () -> {
                    require(mc.getSingleplayerServer() == null && mc.level == null && HostSession.current() == null,
                            "the world was opened after Cancel");
                    require(CoopLauncher.staleWarning() == null && !(mc.gui.screen() instanceof ConfirmScreen), "the warning is still shown");
                    return ++stayed[0] > 100;
                })
                .action("the copy is untouched", () -> {
                    Map<String, String> after = snapshot(folder(mc, state));
                    for (Map.Entry<String, String> entry : before.entrySet()) {
                        require(entry.getValue().equals(after.get(entry.getKey())), entry.getKey() + " changed after Cancel: "
                                + entry.getValue() + " became " + after.get(entry.getKey()));
                    }
                    require(after.keySet().equals(before.keySet()), "files appeared after Cancel: " + after.keySet() + " was " + before.keySet());
                    DropIn.LOGGER.info("Self-test: Cancel left all {} files of B's copy as they were", after.size());
                    pressPlay(mc, state);
                })
                .step("the warning is shown again", SHORT_TICKS, () -> CoopLauncher.staleWarning() != null && mc.gui.screen() instanceof ConfirmScreen)
                .action("Play anyway", () -> CoopLauncher.answerStale(true))
                .step("B hosts its older copy", WORLD_TICKS, () -> hosting(ctx))
                .action("load the block's chunk", () -> command(mc, "forceload add 0 0"))
                .step("the block of A's second session is not in it", SHORT_TICKS, () -> {
                    IntegratedServer server = mc.getSingleplayerServer();
                    require(!blockOnServer(mc), "B's copy has the block A placed while B was away");
                    return server.overworld().getBlockState(Scenarios.PROBE_POS).is(Blocks.GOLD_BLOCK);
                })
                .action("tell A", () -> ctx.writeShared("b-checked", "ok"));
    }

    /** Relative path to "size time sha-256" of every file of a closed world folder. */
    private static Map<String, String> snapshot(Path folder) throws Exception {
        require(folder != null, "B has no folder for the world");
        Map<String, String> files = new TreeMap<>();
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(folder)) {
            paths = walk.filter(Files::isRegularFile).toList();
        }
        for (Path path : paths) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            files.put(folder.relativize(path).toString(), Files.size(path) + " " + Files.getLastModifiedTime(path).toMillis() + " " + hash);
        }
        return files;
    }
}
