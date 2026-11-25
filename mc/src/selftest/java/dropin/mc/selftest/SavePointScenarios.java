package dropin.mc.selftest;

import dropin.core.savepoint.SavePoints;
import dropin.core.savepoint.SavePoints.Point;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.handoff.MemberHandoff;
import dropin.mc.host.HostSync;
import dropin.mc.member.SyncClient;
import dropin.mc.savepoint.SavePointActions;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dropin.mc.selftest.M1Scenarios.base;
import static dropin.mc.selftest.M1Scenarios.createWorld;
import static dropin.mc.selftest.M1Scenarios.hosting;
import static dropin.mc.selftest.M1Scenarios.importWorld;
import static dropin.mc.selftest.M1Scenarios.quitToTitle;
import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.M2Scenarios.copyComplete;
import static dropin.mc.selftest.M2Scenarios.hostA;
import static dropin.mc.selftest.M2Scenarios.joinB;
import static dropin.mc.selftest.M2Scenarios.quitAndReportRoot;
import static dropin.mc.selftest.M2Scenarios.verifyRoot;
import static dropin.mc.selftest.M3Scenarios.MARK_A;
import static dropin.mc.selftest.M3Scenarios.MARK_B;
import static dropin.mc.selftest.M3Scenarios.mark;
import static dropin.mc.selftest.M3Scenarios.marked;
import static dropin.mc.selftest.M3Scenarios.markedOnServer;
import static dropin.mc.selftest.M3Scenarios.players;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.SHORT_TICKS;
import static dropin.mc.selftest.Scenarios.WORLD_ID;
import static dropin.mc.selftest.Scenarios.WORLD_TICKS;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;
import static dropin.mc.selftest.Scenarios.inWorld;

/** The M3b gate scenarios (docs/MILESTONES.md, DESIGN 4.16): save points. Development only. */
final class SavePointScenarios {
    /** Placed after the save point is made: must be gone after the rollback. */
    private static final BlockPos LATER_POS = new BlockPos(0, 252, 0);

    private SavePointScenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "savepoint-rollback:a" -> rollbackA(ctx);
            case "savepoint-rollback:b" -> rollbackB(ctx);
            case "savepoint-files:solo" -> files(ctx);
            default -> null;
        };
    }

    private static Path folder(Minecraft mc) {
        return mc.getLevelSource().getLevelPath(WORLD_ID);
    }

    private static void serverCommand(Minecraft mc, String command) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }

    // ---- savepoint-rollback -------------------------------------------------------------------------

    private static StepRunner rollbackA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        long[] seq = {0};
        IntegratedServer[] before = {null};
        StepRunner runner = hostA(ctx, state, Map.of(), 0)
                .step("B dropped in, has a full copy and its mark", 2 * PEER_TICKS, () -> players(mc) == 2 && ctx.sharedExists("b-marked"));
        runner = mark(runner, mc, MARK_A)
                .step("the server knows both marks", SHORT_TICKS, () -> markedOnServer(mc, MARK_A) && markedOnServer(mc, MARK_B))
                .action("/dropin savepoint create base", () -> mc.player.connection.sendCommand("dropin savepoint create base"))
                .step("the save point exists", WORLD_TICKS, () -> SavePointActions.store(folder(mc)).find("base").isPresent())
                .action("change the world and A", () -> {
                    Point point = SavePointActions.store(folder(mc)).find("base").orElseThrow();
                    DropIn.LOGGER.info("Self-test: save point base at checkpoint {} with {} records, root {}", point.seq(),
                            point.records(), point.root());
                    serverCommand(mc, "setblock 0 252 0 minecraft:emerald_block");
                    mc.player.connection.sendCommand("clear @s");
                    mc.player.connection.sendCommand("tp @s 20 -60 20");
                    ctx.writeShared("a-changed", "ok");
                })
                .step("the block is there and neither player is as at the save point", 2 * PEER_TICKS,
                        () -> ctx.sharedExists("b-changed") && !markedOnServer(mc, MARK_A) && !markedOnServer(mc, MARK_B)
                                && mc.getSingleplayerServer().overworld().getBlockState(LATER_POS).is(Blocks.EMERALD_BLOCK))
                .action("note the checkpoint", () -> seq[0] = HostSync.current().seq())
                .step("the changes are in a checkpoint", WORLD_TICKS, () -> HostSync.current().seq() > seq[0])
                .action("/dropin savepoint restore base", () -> {
                    before[0] = mc.getSingleplayerServer();
                    mc.player.connection.sendCommand("dropin savepoint restore base");
                })
                // No input from here on: the world closes, is rolled back and is hosted again.
                .step("hosting again", 3 * WORLD_TICKS, () -> mc.getSingleplayerServer() != null
                        && mc.getSingleplayerServer() != before[0] && hosting(ctx) && HostSync.current() != null
                        && HostSync.current().seq() > seq[0])
                .step("B rejoined", 2 * PEER_TICKS, () -> players(mc) == 2)
                .step("the world and both players are as at the save point", SHORT_TICKS,
                        () -> mc.getSingleplayerServer().overworld().getBlockState(LATER_POS).isAir()
                                && mc.getSingleplayerServer().overworld().getBlockState(Scenarios.PROBE_POS).is(Blocks.GOLD_BLOCK)
                                && markedOnServer(mc, MARK_A) && markedOnServer(mc, MARK_B) && marked(mc.player, MARK_A))
                .action("tell B", () -> {
                    SavePoints points = SavePointActions.store(folder(mc));
                    require(points.find(SavePoints.AUTO_NAME).isPresent(), "the restore made no before-restore save point");
                    require(!Files.exists(folder(mc).resolve(CoopWorldStore.MARKER_DIR).resolve("rescan")), "the rescan marker was not taken");
                    DropIn.LOGGER.info("Self-test: rolled back to base; hosting again at checkpoint {}", HostSync.current().seq());
                    ctx.writeShared("a-restored", "ok");
                });
        return quitAndReportRoot(runner, ctx, state, 2 * PEER_TICKS);
    }

    private static StepRunner rollbackB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = joinB(ctx, state, Map.of())
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state));
        runner = mark(runner, mc, MARK_B)
                .action("tell A", () -> ctx.writeShared("b-marked", "ok"))
                .step("A made the save point and changed the world", 2 * PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("a-changed");
                })
                .action("change B", () -> {
                    mc.player.connection.sendCommand("clear @s");
                    mc.player.connection.sendCommand("tp @s -20 -60 -20");
                })
                .step("B lost its item and place", SHORT_TICKS, () -> !marked(mc.player, MARK_B))
                .action("tell A", () -> ctx.writeShared("b-changed", "ok"))
                // No input from here on: A closes the world for the restore and this game must be back by itself.
                .step("back in A's new session", 4 * PEER_TICKS, () -> MemberHandoff.lastRole() == MemberHandoff.Role.FOLLOWER
                        && ctx.sharedExists("a-restored") && inWorld(mc) && mc.getSingleplayerServer() == null
                        && SyncClient.status(state.world.worldId()) != null)
                .step("B has its item at its place again", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return marked(mc.player, MARK_B);
                })
                .step("the copy has the rolled-back world", 2 * PEER_TICKS, copyComplete(mc, state));
        return verifyRoot(runner, ctx, state)
                .action("tell A", () -> ctx.writeShared("b-verified", "ok"));
    }

    // ---- savepoint-files ----------------------------------------------------------------------------

    private static StepRunner files(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        String[] solo = {null};
        StepRunner runner = createWorld(base(ctx), ctx)
                .action("place the probe block", () -> {
                    serverCommand(mc, "forceload add 0 0");
                    serverCommand(mc, Scenarios.PROBE_COMMAND);
                })
                .step("probe block placed", SHORT_TICKS,
                        () -> mc.getSingleplayerServer().overworld().getBlockState(Scenarios.PROBE_POS).is(Blocks.GOLD_BLOCK));
        return importWorld(quitToTitle(runner, ctx), ctx, state)
                .action("the eleventh save point removes the oldest", () -> {
                    Path folder = folder(mc);
                    for (int i = 0; i <= SavePoints.LIMIT; i++) {
                        SavePointActions.createClosed(folder, "p" + i);
                    }
                    SavePoints points = SavePointActions.store(folder);
                    List<Point> all = points.list();
                    require(all.size() == SavePoints.LIMIT, "there are " + all.size() + " save points, expected " + SavePoints.LIMIT);
                    require(points.find("p0").isEmpty(), "the oldest save point is still there");
                    require(all.get(0).name().equals("p1") && points.find("p10").isPresent(), "the wrong save point was removed");
                    DropIn.LOGGER.info("Self-test: {} save points kept, {} records each", all.size(), all.get(0).records());
                })
                .action("an exported file imports back with the same root", () -> {
                    Path folder = folder(mc);
                    SavePoints points = SavePointActions.store(folder);
                    Point point = points.find("p10").orElseThrow();
                    Path file = SavePointActions.export(state.world, folder, point);
                    require(Files.size(file) > 0 && file.getParent().equals(SavePointActions.backupsDir(folder)), "no export file in the backups folder");
                    points.delete(point);
                    require(points.find("p10").isEmpty(), "the save point was not deleted");
                    List<Point> imported = SavePointActions.importFiles(state.world, folder, List.of(file));
                    require(imported.size() == 1 && imported.get(0).name().equals("p10"), "the file did not import as p10");
                    require(imported.get(0).root().equals(point.root()), "imported root " + imported.get(0).root() + ", exported " + point.root());
                    require(imported.get(0).records() == point.records(), "the imported save point has another number of records");
                    DropIn.LOGGER.info("Self-test: {} ({} bytes) imported back with root {}", file.getFileName(), Files.size(file), point.root());
                })
                .action("make a single-player world", () -> {
                    Path folder = folder(mc);
                    Point point = SavePointActions.store(folder).find("p10").orElseThrow();
                    solo[0] = SavePointActions.makeSinglePlayer(mc, state.world, folder, point);
                    Path made = mc.getLevelSource().getLevelPath(solo[0]);
                    require(Files.isRegularFile(made.resolve("level.dat")), "the new world has no level.dat");
                    require(!Files.exists(made.resolve(CoopWorldStore.MARKER_DIR)), "the new world is linked to the co-op world");
                    mc.createWorldOpenFlows().openWorld(solo[0], () -> { });
                })
                .step("the single-player world opens and has the block", WORLD_TICKS, () -> inWorld(mc) && mc.getSingleplayerServer() != null
                        && HostSync.current() == null
                        && mc.getSingleplayerServer().overworld().getBlockState(Scenarios.PROBE_POS).is(Blocks.GOLD_BLOCK))
                .action("save and quit", () -> {
                    DropIn.LOGGER.info("Self-test: single-player world {} opened with the probe block", solo[0]);
                    dropin.mc.compat.Compat.disconnectFromWorld(mc, dropin.mc.compat.Compat.DEFAULT_QUIT_MESSAGE);
                })
                .step("world closed", WORLD_TICKS, () -> mc.getSingleplayerServer() == null && mc.level == null);
    }
}
