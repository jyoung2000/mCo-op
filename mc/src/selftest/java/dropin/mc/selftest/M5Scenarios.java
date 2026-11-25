package dropin.mc.selftest;

import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopLauncher;
import dropin.mc.coop.Prompts;
import dropin.mc.host.ChunkBytes;
import dropin.mc.host.HostSync;
import dropin.mc.member.RecordApplier;
import dropin.mc.member.SyncClient;
import dropin.mc.merge.ColdMerge;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static dropin.mc.selftest.M1Scenarios.hosting;
import static dropin.mc.selftest.M1Scenarios.joinedRemote;
import static dropin.mc.selftest.M1Scenarios.pressPlay;
import static dropin.mc.selftest.M1Scenarios.quitToTitle;
import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.M2Scenarios.copyComplete;
import static dropin.mc.selftest.M2Scenarios.folder;
import static dropin.mc.selftest.M2Scenarios.hostA;
import static dropin.mc.selftest.M2Scenarios.joinB;
import static dropin.mc.selftest.M2Scenarios.quitAndReportRoot;
import static dropin.mc.selftest.M2Scenarios.verifyRoot;
import static dropin.mc.selftest.M3Scenarios.blockAt;
import static dropin.mc.selftest.M3Scenarios.loadChunk;
import static dropin.mc.selftest.M3Scenarios.players;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.SHORT_TICKS;
import static dropin.mc.selftest.Scenarios.WORLD_TICKS;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;

/**
 * The M5 gate scenarios (docs/MILESTONES.md): two copies of one world are each played alone, then meet.
 * <p>
 * All three start the same way: B gets a full copy from A and leaves; A plays a session alone and changes the
 * world; B answers the stale-copy warning with "Play anyway", so its copy becomes a second lineage, and changes
 * the world too. Then A hosts (the main world is the one with a live host) and B joins: B's copy keeps what it
 * changed as a branch, follows A, and hands A the branch. A merges it the next time it opens the world, and B's
 * copy must then hash to A's root. Development only.
 */
final class M5Scenarios {
    private enum Mode { CLEAN, CONFLICT, TOO_OLD }

    /** A's edit while it plays alone: in the spawn chunk, which {@code hostA} keeps loaded. */
    private static final BlockPos A_POS = new BlockPos(0, 251, 0);
    private static final String A_COMMAND = "setblock 0 251 0 minecraft:diamond_block";
    /** B's edit in {@code merge-clean} and {@code merge-too-old}: 500 blocks away, a chunk A never made. */
    private static final BlockPos B_FAR = new BlockPos(500, 250, 500);
    private static final String B_FAR_COMMAND = "setblock 500 250 500 minecraft:emerald_block";
    private static final String FAR_CHUNK = RecordKey.chunk("minecraft:overworld", "region", 31, 31);
    /** B's edit in {@code merge-conflict}: the same chunk as A's. */
    private static final BlockPos B_NEAR = new BlockPos(0, 252, 0);
    private static final String B_NEAR_COMMAND = "setblock 0 252 0 minecraft:emerald_block";

    private M5Scenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "merge-clean:a" -> a(ctx, Mode.CLEAN);
            case "merge-clean:b" -> b(ctx, Mode.CLEAN);
            case "merge-conflict:a" -> a(ctx, Mode.CONFLICT);
            case "merge-conflict:b" -> b(ctx, Mode.CONFLICT);
            case "merge-too-old:a" -> a(ctx, Mode.TOO_OLD);
            case "merge-too-old:b" -> b(ctx, Mode.TOO_OLD);
            default -> null;
        };
    }

    // ---- building blocks --------------------------------------------------------------------------

    private static void command(Minecraft mc, String command) {
        IntegratedServer server = mc.getIntegratedServer();
        server.addScheduledTask(() -> M3Scenarios.runCommand(server, command));
    }

    /**
     * 1.21.1 {@code forceload add <x> <z>}; 1.12.2 has no forceload command (see {@link M3Scenarios#loadChunk}).
     * No chunk-loading ticket: a ticket would change {@code forcedchunks.dat} on both sides of the fork.
     */
    private static void forceLoadAt(Minecraft mc, int x, int z) {
        loadChunk(mc.getIntegratedServer(), x, z);
    }

    private static boolean block(Minecraft mc, BlockPos pos, Block block) {
        return Boolean.TRUE.equals(blockAt(mc, pos, block));
    }

    /** Hosting; a stale-copy warning on the way (the other side's last session is newer) is answered "Play anyway". */
    private static StepRunner.Step hostingAnyway(Context ctx) {
        return () -> {
            if (CoopLauncher.staleWarning() != null && ctx.mc().currentScreen instanceof Prompts.Confirm) {
                CoopLauncher.answerStale(true);
                return false;
            }
            return hosting(ctx);
        };
    }

    private static Ledger ledger(State state, Path folder) throws IOException {
        return Ledger.load(state.world.keys().auth(), folder.resolve(CoopWorldStore.MARKER_DIR).resolve("ledger.jsonl"));
    }

    private static List<String> pendingBranches(Path folder) throws IOException {
        Path branches = folder.resolve(CoopWorldStore.MARKER_DIR).resolve("branches");
        if (!Files.isDirectory(branches)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(branches)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * The block at {@code pos} in a chunk record's bytes (uncompressed chunk NBT, see ChunkBytes), or null if its
     * section is not stored. 1.12.2 port: a 1.12.2 chunk stores numeric block ids per section ({@code Blocks},
     * {@code Add}), not block names, so 1.21.1's search for the text "minecraft:emerald_block" in the bytes becomes
     * a lookup of the block at B's position.
     */
    private static Block blockInRecord(byte[] bytes, BlockPos pos) throws IOException {
        NBTTagCompound level = ChunkBytes.parse(bytes).getCompoundTag("Level");
        NBTTagList sections = level.getTagList("Sections", 10);
        int index = (pos.getY() & 15) << 8 | (pos.getZ() & 15) << 4 | (pos.getX() & 15);
        for (int i = 0; i < sections.tagCount(); i++) {
            NBTTagCompound section = sections.getCompoundTagAt(i);
            if ((section.getByte("Y") & 255) != pos.getY() >> 4) {
                continue;
            }
            int id = section.getByteArray("Blocks")[index] & 255;
            if (section.hasKey("Add", 7)) {
                byte add = section.getByteArray("Add")[index >> 1];
                id |= ((index & 1) == 0 ? add & 15 : (add >> 4) & 15) << 8;
            }
            return Block.getBlockById(id);
        }
        return null;
    }

    // ---- A: the main world --------------------------------------------------------------------------

    private static StepRunner a(Context ctx, Mode mode) {
        Minecraft mc = ctx.mc();
        State state = new State();
        Path world = M3Scenarios.worldFolder(mc);
        StepRunner together = hostA(ctx, state, Map.of(), 0)
                .step("B has a full copy and left", 4 * PEER_TICKS, () -> ctx.sharedExists("b-left") && players(mc) == 1);
        StepRunner alone = quitToTitle(together, ctx)
                .action("press Play", () -> pressPlay(mc, state))
                .step("hosting alone", WORLD_TICKS, () -> hosting(ctx))
                .action("A's edit", () -> {
                    // 1.12.2 port: setblock needs the chunk loaded (M3Scenarios.loadChunk).
                    forceLoadAt(mc, 0, 0);
                    command(mc, A_COMMAND);
                })
                .step("A's block placed", SHORT_TICKS, () -> block(mc, A_POS, Blocks.DIAMOND_BLOCK));
        StepRunner meeting = quitToTitle(alone, ctx)
                .action("tell B", () -> ctx.writeShared("a-done", "ok"))
                .step("B played alone on its copy and quit", 4 * WORLD_TICKS, () -> ctx.sharedExists("b-forked"))
                .action("press Play", () -> pressPlay(mc, state))
                .step("hosting: A is the live host", WORLD_TICKS, hostingAnyway(ctx))
                .action("tell B", () -> ctx.writeShared("a-meeting", "ok"))
                .step("B joined and dealt with its fork", 4 * PEER_TICKS, () -> ctx.sharedExists("b-split"));
        if (mode != Mode.TOO_OLD) {
            meeting.step("B's branch arrived", PEER_TICKS,
                    () -> ("branch:" + HostSync.lastBranchReceived()).equals(ctx.readShared("b-split")));
        }
        StepRunner reopened = quitToTitle(meeting, ctx)
                .action("nothing was merged while the world was open; press Play", () -> {
                    String split = ctx.readShared("b-split");
                    List<String> pending = pendingBranches(world);
                    require(ColdMerge.reports().isEmpty(), "a merge ran before the world was closed");
                    if (mode == Mode.TOO_OLD) {
                        require(split.startsWith("separate:"), "B reported '" + split + "', expected a separate world");
                        require(HostSync.lastBranchReceived().isEmpty(), "A received a branch: " + HostSync.lastBranchReceived());
                        require(pending.isEmpty(), "A has pending branches: " + pending);
                    } else {
                        require(pending.equals(List.of(split.substring("branch:".length()))),
                                "A's pending branches are " + pending + ", B reported " + split);
                    }
                    if (mode != Mode.CONFLICT) {
                        require(RecordApplier.scan(world).get(FAR_CHUNK) == null, "A's world already has B's far chunk");
                    }
                    pressPlay(mc, state);
                })
                .step("hosting", WORLD_TICKS, hostingAnyway(ctx));
        StepRunner checked = switch (mode) {
            case CLEAN -> reopened
                    .action("the merge report: nothing kept", () -> {
                        ColdMerge.Report report = report(world, state, ctx);
                        require(report.chunksApplied() >= 1, "no chunk of B's session was applied");
                        require(report.chunksKept() == 0, report.chunksKept() + " chunks were kept from the main world, expected none");
                        ownFilesFromTheBranch(report);
                        forceLoadAt(mc, 500, 500);
                    })
                    .step("both edits are in the world", SHORT_TICKS,
                            () -> block(mc, B_FAR, Blocks.EMERALD_BLOCK) && block(mc, A_POS, Blocks.DIAMOND_BLOCK));
            case CONFLICT -> reopened
                    .action("the merge report: one conflict, B's chunk under backups/conflicts", () -> {
                        ColdMerge.Report report = report(world, state, ctx);
                        require(report.chunksKept() == 1, report.chunksKept() + " chunks were kept from the main world, expected 1");
                        Path conflicts = world.resolve(CoopWorldStore.MARKER_DIR).resolve("backups").resolve("conflicts");
                        require(report.conflicts() != null && report.conflicts().startsWith(conflicts),
                                "the conflict backup is at " + report.conflicts() + ", expected under " + conflicts);
                        Path chunk = ColdMerge.backupPath(report.conflicts(), RecordKey.chunk("minecraft:overworld", "region", 0, 0));
                        require(Files.isRegularFile(chunk), "B's version of the chunk is not at " + chunk);
                        Block saved = blockInRecord(Files.readAllBytes(chunk), B_NEAR);
                        require(saved == Blocks.EMERALD_BLOCK, chunk + " does not have B's block: " + saved + " at " + B_NEAR);
                        String chat = report.message().getUnformattedText();
                        require(chat.contains(" 1 kept"), "the chat report is '" + chat + "'");
                        ownFilesFromTheBranch(report);
                        // 1.21.1 and 1.12.2 keep a player's own files in playerdata/, advancements/ and stats/
                        for (String folder : List.of("players", "playerdata", "advancements", "stats")) {
                            Path playerFiles = report.conflicts().resolve("files").resolve(folder);
                            require(!Files.exists(playerFiles), "B's own player files were backed up instead of merged: " + playerFiles);
                        }
                        DropIn.LOGGER.info("Self-test: B's version of the chunk is in {}", chunk);
                    })
                    .step("A's version of the chunk is in the world", SHORT_TICKS, () -> {
                        // 1.12.2 port: a block of a chunk that is not loaded yet reads as unknown (null): wait for it.
                        Boolean emerald = blockAt(mc, B_NEAR, Blocks.EMERALD_BLOCK);
                        if (emerald == null) {
                            return false;
                        }
                        require(!emerald, "B's block is in the main world");
                        return block(mc, A_POS, Blocks.DIAMOND_BLOCK);
                    });
            case TOO_OLD -> reopened
                    .step("the main world is as A left it", SHORT_TICKS, () -> {
                        require(ColdMerge.reports().isEmpty(), "a merge ran");
                        for (LedgerEntry entry : ledger(state, world).entries()) {
                            require(entry.parents2().isEmpty(), "checkpoint " + entry.seq() + " is a merge");
                        }
                        return block(mc, A_POS, Blocks.DIAMOND_BLOCK);
                    });
        };
        if (mode != Mode.TOO_OLD) {
            checked.step("the report is in the chat", SHORT_TICKS, () -> ColdMerge.shown() == 1);
        }
        return quitAndReportRoot(checked.action("tell B", () -> ctx.writeShared("a-merged", "ok")), ctx, state, 4 * PEER_TICKS);
    }

    /** The one merge A ran before the world booted, checked against A's ledger. */
    /** B joined the main world before the merge, yet B's offline player files must land in the world (none kept). */
    private static void ownFilesFromTheBranch(ColdMerge.Report report) {
        require(report.filesApplied() >= 1, "none of B's own player files were applied");
        require(report.filesKept() == 0, report.filesKept() + " files were kept from the main world, expected none");
    }

    private static ColdMerge.Report report(Path world, State state, Context ctx) throws IOException {
        List<ColdMerge.Report> reports = ColdMerge.reports();
        require(reports.size() == 1, reports.size() + " merges ran, expected 1");
        ColdMerge.Report report = reports.get(0);
        String head = ctx.readShared("b-split").substring("branch:".length());
        require(report.branchHead().equals(head), "merged branch " + report.branchHead() + ", B's was " + head);
        require(report.player().equals("DevB"), "the report names " + report.player());
        require(report.entry().parents2().equals(head), "the merge checkpoint's second parent is " + report.entry().parents2());
        require(ledger(state, world).hasMerged(head), "A's ledger has no merge of " + head);
        require(pendingBranches(world).isEmpty(), "the merged branch is still pending");
        DropIn.LOGGER.info("Self-test: merge checkpoint {}: {} chunks and {} files applied, {} chunks and {} files kept; chat: {}",
                report.entry().seq(), report.chunksApplied(), report.filesApplied(), report.chunksKept(), report.filesKept(),
                report.message().getUnformattedText());
        return report;
    }

    // ---- B: the copy that was played alone ------------------------------------------------------------

    private static StepRunner b(Context ctx, Mode mode) {
        Minecraft mc = ctx.mc();
        State state = new State();
        String[] fork = new String[2];
        BlockPos pos = mode == Mode.CONFLICT ? B_NEAR : B_FAR;
        StepRunner copied = joinB(ctx, state, Map.of())
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("leave", () -> dropin.mc.compat.Compat.disconnectFromWorld(mc, dropin.mc.compat.Compat.DEFAULT_QUIT_MESSAGE))
                .step("left", SHORT_TICKS, () -> mc.world == null)
                .action("tell A", () -> {
                    mc.displayGuiScreen(new GuiMainMenu());
                    ctx.writeShared("b-left", "ok");
                })
                .step("A played a session without B and quit", 3 * WORLD_TICKS, () -> ctx.sharedExists("a-done"))
                .action("press Play", () -> {
                    SyncClient.stopAndWait(state.world.worldId(), 15_000);
                    pressPlay(mc, state);
                })
                .step("the warning is shown", SHORT_TICKS, () -> CoopLauncher.staleWarning() != null && mc.currentScreen instanceof Prompts.Confirm)
                .action("Play anyway", () -> CoopLauncher.answerStale(true))
                .step("B hosts its older copy", WORLD_TICKS, () -> hosting(ctx))
                .action("B's edit", () -> {
                    forceLoadAt(mc, mode == Mode.CONFLICT ? 0 : 500, mode == Mode.CONFLICT ? 0 : 500);
                    command(mc, mode == Mode.CONFLICT ? B_NEAR_COMMAND : B_FAR_COMMAND);
                })
                .step("B's block placed", SHORT_TICKS, () -> block(mc, pos, Blocks.EMERALD_BLOCK));
        StepRunner met = quitToTitle(copied, ctx)
                .action("note the head of B's own session", () -> {
                    Path folder = folder(mc, state);
                    LedgerEntry last = ledger(state, folder).last();
                    require(last != null && last.name().equals("DevB"), "B's copy does not end on a checkpoint of its own");
                    fork[0] = last.hash();
                    if (mode != Mode.CONFLICT) {
                        Path manifest = folder.resolve(CoopWorldStore.MARKER_DIR).resolve("manifest").resolve("current.tsv");
                        fork[1] = Manifest.load(manifest).get(FAR_CHUNK);
                        require(fork[1] != null, "B's last checkpoint does not have the far chunk");
                    }
                    if (mode == Mode.TOO_OLD) {
                        // What a prune does after the keep time: the lists of what each checkpoint changed are gone.
                        Path deltas = folder.resolve(CoopWorldStore.MARKER_DIR).resolve("deltas");
                        int removed = 0;
                        try (Stream<Path> list = Files.list(deltas)) {
                            for (Path file : list.toList()) {
                                Files.delete(file);
                                removed++;
                            }
                        }
                        require(removed > 0, "B's copy had no delta files");
                        DropIn.LOGGER.info("Self-test: removed {} delta files of B's copy", removed);
                    }
                    DropIn.LOGGER.info("Self-test: B's own session ended on checkpoint {} ({})", last.seq(), last.hash());
                    ctx.writeShared("b-forked", last.hash());
                })
                .step("A hosts", 3 * WORLD_TICKS, () -> ctx.sharedExists("a-meeting"))
                .action("press Play", () -> pressPlay(mc, state))
                .step("landed in A's session", PEER_TICKS, () -> joinedRemote(mc))
                .step("the copy's own session was set aside", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return !SyncClient.lastSplit().isEmpty();
                })
                .step("the copy follows A", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("what became of B's session", () -> {
                    String split = SyncClient.lastSplit();
                    Path folder = folder(mc, state);
                    if (mode == Mode.TOO_OLD) {
                        require(split.startsWith("separate:"), "B's session became '" + split + "', expected a separate world");
                        Path separate = dropin.mc.menu.CoopActions.levelPath(mc, split.substring("separate:".length()));
                        require(!separate.equals(folder) && Files.isRegularFile(separate.resolve("level.dat")), separate + " is not a world");
                        require(!Files.exists(separate.resolve(CoopWorldStore.MARKER_DIR)), "the separate world is still a co-op world");
                        String far = RecordApplier.scan(separate).get(FAR_CHUNK);
                        require(fork[1].equals(far), "the far chunk of the separate world hashes to " + far + ", B's session had " + fork[1]);
                        require(pendingBranches(folder).isEmpty(), "B kept a branch as well: " + pendingBranches(folder));
                        DropIn.LOGGER.info("Self-test: B's session was saved as the world '{}'", separate.getFileName());
                    } else {
                        require(split.equals("branch:" + fork[0]), "B's session became '" + split + "', expected branch " + fork[0]);
                        require(pendingBranches(folder).equals(List.of(fork[0])), "B's branches are " + pendingBranches(folder));
                    }
                    ctx.writeShared("b-split", split);
                })
                .step("A quit", 2 * PEER_TICKS, () -> mc.currentScreen instanceof GuiDisconnected
                        || (mc.world == null && mc.currentScreen instanceof GuiMainMenu))
                .action("title screen", () -> mc.displayGuiScreen(new GuiMainMenu()))
                .step("A opened the world again", 4 * WORLD_TICKS, () -> ctx.sharedExists("a-merged"))
                .action("press Play", () -> pressPlay(mc, state))
                .step("landed in A's session again", PEER_TICKS, () -> joinedRemote(mc))
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state));
        return verifyRoot(met, ctx, state)
                .action("B's copy knows the merge", () -> {
                    Path folder = folder(mc, state);
                    if (mode == Mode.TOO_OLD) {
                        require(RecordApplier.scan(folder).get(FAR_CHUNK) == null, "B's copy of the main world has the far chunk");
                    } else {
                        require(ledger(state, folder).hasMerged(fork[0]), "B's ledger has no merge of " + fork[0]);
                        require(pendingBranches(folder).isEmpty(), "B still keeps the merged branch: " + pendingBranches(folder));
                    }
                })
                .action("tell A", () -> ctx.writeShared("b-verified", "ok"));
    }
}
