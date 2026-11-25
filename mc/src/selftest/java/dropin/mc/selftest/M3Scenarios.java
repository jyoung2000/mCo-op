package dropin.mc.selftest;

import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.sync.SyncClientSession;
import dropin.core.sync.SyncClientSession.Phase;
import dropin.core.sync.SyncState;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.HostSession;
import dropin.mc.coop.Prompts;
import dropin.mc.handoff.HostHandoff;
import dropin.mc.handoff.MemberHandoff;
import dropin.mc.host.HostSync;
import dropin.mc.member.SyncClient;
import dropin.mc.menu.CoopActions;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import dropin.tunnel.E4mcClient;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.fml.common.Loader;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static dropin.mc.selftest.M1Scenarios.hosting;
import static dropin.mc.selftest.M1Scenarios.joinedRemote;
import static dropin.mc.selftest.M1Scenarios.pressPlay;
import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.M2Scenarios.copyComplete;
import static dropin.mc.selftest.M2Scenarios.folder;
import static dropin.mc.selftest.M2Scenarios.hostA;
import static dropin.mc.selftest.M2Scenarios.joinB;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.SHORT_TICKS;
import static dropin.mc.selftest.Scenarios.STARTUP_TICKS;
import static dropin.mc.selftest.Scenarios.WORLD_ID;
import static dropin.mc.selftest.Scenarios.WORLD_TICKS;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;
import static dropin.mc.selftest.Scenarios.inWorld;

/**
 * The M3 gate scenarios (docs/MILESTONES.md): a graceful handoff with three players, and a host that quits
 * while nobody else has a full copy. Development only.
 */
final class M3Scenarios {
    /** Each player's own item and place: what must be "as it was" after the handoff. */
    record Mark(String player, Item item, String itemId, int x, int z) {
    }

    static final Mark MARK_A = new Mark("DevA", Items.DIAMOND, "minecraft:diamond", 100, 100);
    static final Mark MARK_B = new Mark("DevB", Items.EMERALD, "minecraft:emerald", -100, 60);
    private static final Mark MARK_C = new Mark("DevC", Items.GOLD_INGOT, "minecraft:gold_ingot", 60, -100);

    /**
     * Where a player stands on the self-test's flat world. 1.12.2 port: the default superflat world is bedrock,
     * two dirt and grass (FlatGeneratorInfo.getDefaultFlatGenerator), so the ground is y 4; 1.21.1 used y -60.
     */
    static final int GROUND_Y = 4;

    /** The older scenarios run with the handoff switched off (see {@code M1Scenarios.base}); these do not. */
    private static final Map<String, String> HANDOFF_ON = Map.of("dropin.dev.noHandoff", "false");

    private M3Scenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "handoff-3:a" -> handoffA(ctx);
            case "handoff-3:b" -> handoffB(ctx);
            case "handoff-3:c" -> handoffC(ctx);
            case "handoff-no-full-copy:a" -> noFullCopyA(ctx);
            case "handoff-no-full-copy:b" -> noFullCopyB(ctx);
            default -> null;
        };
    }

    // ---- 1.12.2 helpers shared by the M3, M3b, M4, M5, motion and perf scenarios -------------------------

    /** 1.21.1 {@code mc.getUser().getProfileId()}. */
    static String myUuid(Minecraft mc) {
        return mc.getSession().getProfile().getId().toString();
    }

    /** Runs {@code task} on the server thread and waits for its result (1.21.1 {@code server.submit(task).join()}). */
    static <T> T onServer(MinecraftServer server, Callable<T> task) throws Exception {
        return server.callFromMainThread(task).get(60, TimeUnit.SECONDS);
    }

    /** A command run as the server (all permissions), on the server thread. Returns the command's success count. */
    static int runCommand(MinecraftServer server, String command) {
        return server.getCommandManager().executeCommand(server, command);
    }

    /**
     * Whether the overworld block at {@code pos} is {@code block} on the integrated server; null while there is no
     * server or the block's chunk is not loaded (a load is then asked for, and the caller polls again).
     * 1.12.2 port: {@code World.getBlockState} loads or generates a missing chunk on the calling thread, which
     * must not be the client thread, so only loaded chunks are read here.
     */
    static Boolean blockAt(Minecraft mc, BlockPos pos, Block block) {
        IntegratedServer server = mc.getIntegratedServer();
        if (server == null) {
            return null;
        }
        WorldServer world = server.getWorld(0);
        Chunk chunk = world.getChunkProvider().getLoadedChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            server.addScheduledTask(() -> world.getChunkProvider().provideChunk(pos.getX() >> 4, pos.getZ() >> 4));
            return null;
        }
        return chunk.getBlockState(pos).getBlock() == block;
    }

    /**
     * Loads (or generates) the overworld chunk with the block {@code x z}, scheduled on the server thread: what a
     * 1.21.1 {@code forceload add <x> <z>} was for where only "loaded now" mattered (a /setblock or /fill queued
     * after this call finds the chunk loaded). 1.12.2 port: the chunk is not kept: the next save queues it for
     * unloading unless a player is near. No ticket is taken on purpose, so the world's {@code forcedchunks.dat}
     * does not change (it is a mergeable world file, see the M5 scenarios). Use {@link #forceLoad} to keep chunks.
     */
    static void loadChunk(MinecraftServer server, int x, int z) {
        server.addScheduledTask(() -> server.getWorld(0).getChunkProvider().provideChunk(x >> 4, z >> 4));
    }

    private static volatile boolean ticketCallback;

    /**
     * 1.21.1 {@code forceload add <fromX> <fromZ> <toX> <toZ>} (block coordinates) in the overworld, scheduled on
     * the server thread; the chunks are loaded too. 1.12.2 has no forceload command: the chunks get Forge
     * chunk-loading tickets of this mod (ForgeChunkManager), which keep them loaded while the world runs and,
     * through the ticket data, when it is opened again.
     */
    static void forceLoad(MinecraftServer server, int fromX, int fromZ, int toX, int toZ) {
        server.addScheduledTask(() -> forceLoadNow(server.getWorld(0), fromX, fromZ, toX, toZ, true));
    }

    /** {@link #forceLoad} on the server thread; with {@code load} false the chunks are only kept, not loaded now. */
    static void forceLoadNow(WorldServer world, int fromX, int fromZ, int toX, int toZ, boolean load) {
        Object mod = Loader.instance().getIndexedModList().get(E4mcClient.MOD_ID).getMod();
        if (!ticketCallback) {
            // A ticket stores no chunk list of its own: each ticket's chunks are kept in its mod data and forced again
            // when the world is opened again (DESIGN 4.12: the probe chunk stays loaded across sessions).
            ForgeChunkManager.setForcedChunkLoadingCallback(mod, (tickets, w) -> {
                for (ForgeChunkManager.Ticket ticket : tickets) {
                    int[] chunks = ticket.getModData().getIntArray("chunks");
                    for (int i = 0; i + 1 < chunks.length; i += 2) {
                        ForgeChunkManager.forceChunk(ticket, new ChunkPos(chunks[i], chunks[i + 1]));
                    }
                }
            });
            ticketCallback = true;
        }
        ForgeChunkManager.Ticket ticket = null;
        int[] held = new int[0];
        for (int cx = fromX >> 4; cx <= toX >> 4; cx++) {
            for (int cz = fromZ >> 4; cz <= toZ >> 4; cz++) {
                if (ticket == null || held.length / 2 >= ticket.getMaxChunkListDepth()) {
                    ticket = ForgeChunkManager.requestTicket(mod, world, ForgeChunkManager.Type.NORMAL);
                    require(ticket != null, "no chunk-loading ticket is left for chunk " + cx + " " + cz);
                    held = new int[0];
                }
                ForgeChunkManager.forceChunk(ticket, new ChunkPos(cx, cz));
                held = java.util.Arrays.copyOf(held, held.length + 2);
                held[held.length - 2] = cx;
                held[held.length - 1] = cz;
                NBTTagCompound data = ticket.getModData();
                data.setIntArray("chunks", held);
                if (load) {
                    world.getChunkProvider().provideChunk(cx, cz);
                }
            }
        }
    }

    /** 1.21.1 {@code mc.getLevelSource().getLevelPath(WORLD_ID)}. */
    static Path worldFolder(Minecraft mc) {
        return CoopActions.levelPath(mc, WORLD_ID);
    }

    // ---- building blocks --------------------------------------------------------------------------

    static StepRunner mark(StepRunner runner, Minecraft mc, Mark mark) {
        return runner
                .action("give and move", () -> {
                    mc.player.sendChatMessage("/give @s " + mark.itemId() + " 3");
                    mc.player.sendChatMessage("/tp @s " + mark.x() + " " + GROUND_Y + " " + mark.z());
                })
                .step(mark.player() + " has its item at its place", SHORT_TICKS, () -> marked(mc.player, mark));
    }

    static boolean marked(EntityPlayer player, Mark mark) {
        if (player == null) {
            return false;
        }
        // 1.21.1 Inventory.contains(predicate) looks at every compartment: main, armor and offhand slots.
        boolean has = false;
        for (int i = 0; i < player.inventory.getSizeInventory(); i++) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.getItem() == mark.item()) {
                has = true;
                break;
            }
        }
        return has && Math.abs(player.posX - mark.x()) < 2 && Math.abs(player.posZ - mark.z()) < 2;
    }

    /** What the server (the authority) says about a player. */
    static boolean markedOnServer(Minecraft mc, Mark mark) {
        IntegratedServer server = mc.getIntegratedServer();
        EntityPlayerMP player = server == null ? null : server.getPlayerList().getPlayerByUsername(mark.player());
        return marked(player, mark);
    }

    static int players(Minecraft mc) {
        IntegratedServer server = mc.getIntegratedServer();
        return server == null ? 0 : server.getPlayerList().getCurrentPlayerCount();
    }

    // ---- handoff-3 ----------------------------------------------------------------------------------

    private static StepRunner handoffA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = hostA(ctx, state, HANDOFF_ON, 0)
                .step("B and C dropped in", 2 * PEER_TICKS, () -> players(mc) == 3)
                .step("B and C have full copies and their marks", 2 * PEER_TICKS,
                        () -> ctx.sharedExists("b-marked") && ctx.sharedExists("c-marked"));
        return mark(runner, mc, MARK_A)
                .step("the server knows all three marks", SHORT_TICKS,
                        () -> markedOnServer(mc, MARK_A) && markedOnServer(mc, MARK_B) && markedOnServer(mc, MARK_C))
                .action("A quits", () -> dropin.mc.compat.Compat.disconnectFromWorld(mc, dropin.mc.compat.Compat.DEFAULT_QUIT_MESSAGE))
                .step("the handoff ran and the world closed", WORLD_TICKS + SHORT_TICKS,
                        () -> mc.getIntegratedServer() == null && mc.world == null && HostHandoff.state() == HostHandoff.State.IDLE)
                .action("the world went to B", () -> {
                    String b = ctx.readShared("b-uuid");
                    HostSync.Standing standing = HostHandoff.lastStanding();
                    require(standing != null && b.equals(standing.successorUuid()), "before quitting, the successor was "
                            + (standing == null ? "not looked for" : standing.successorUuid()) + ", expected B " + b);
                    require(b.equals(HostSync.lastHandedTo()), "A handed the world to '" + HostSync.lastHandedTo() + "', expected B " + b);
                    Path file = worldFolder(mc).resolve(CoopWorldStore.MARKER_DIR).resolve("ledger.jsonl");
                    LedgerEntry last = Ledger.load(state.world.keys().auth(), file).last();
                    require(last != null, "A has no checkpoint");
                    require(last.root().equals(HostSync.lastFinalRoot()), "the ledger ends on root " + last.root()
                            + " but the handoff carried " + HostSync.lastFinalRoot());
                    DropIn.LOGGER.info("Self-test: A ended on checkpoint {} with root {} and handed over to {}", last.seq(), last.root(), b);
                    ctx.writeShared("a-root", last.seq() + " " + last.root());
                    mc.displayGuiScreen(new GuiMainMenu());
                })
                .step("B hosts", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists("b-hosting"))
                .action("press Play", () -> pressPlay(mc, state))
                .step("landed in B's session", PEER_TICKS, () -> joinedRemote(mc))
                .step("A has its item at its place", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return marked(mc.player, MARK_A);
                })
                .action("tell B", () -> ctx.writeShared("a-back", "ok"))
                .step("B checked everyone", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("b-checked");
                });
    }

    private static StepRunner handoffB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = joinB(ctx, state, HANDOFF_ON)
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state));
        return mark(runner, mc, MARK_B)
                .action("tell A", () -> {
                    // The clients of one run share a LAN port, and A may still hold it when B starts hosting
                    // (seen through the relay, where A stops a few seconds later). Real players pick a free port.
                    try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
                        System.setProperty("dropin.dev.lanPort", Integer.toString(free.getLocalPort()));
                    }
                    ctx.writeShared("b-uuid", myUuid(mc));
                    ctx.writeShared("b-marked", "ok");
                })
                // No input from here on: A quits, and this game must end up hosting by itself.
                .step("took over as host", 3 * PEER_TICKS, () -> MemberHandoff.lastRole() == MemberHandoff.Role.SUCCESSOR && hosting(ctx))
                .step("A reported its root", WORLD_TICKS, () -> ctx.sharedExists("a-root"))
                .action("the root at takeover is A's final root", () -> {
                    String[] reported = ctx.readShared("a-root").split(" ");
                    require(MemberHandoff.takeoverSeq() == Long.parseLong(reported[0]), "B took over at checkpoint "
                            + MemberHandoff.takeoverSeq() + ", A ended on " + reported[0]);
                    require(MemberHandoff.takeoverRoot().equals(reported[1]), "B took over with root "
                            + MemberHandoff.takeoverRoot() + ", A's final root is " + reported[1]);
                    DropIn.LOGGER.info("Self-test: B took over at checkpoint {} with A's final root {}", reported[0], reported[1]);
                })
                .step("B has its item at its place", SHORT_TICKS, () -> marked(mc.player, MARK_B) && markedOnServer(mc, MARK_B))
                .action("tell A and C", () -> ctx.writeShared("b-hosting", "ok"))
                .step("C is back and A rejoined", 3 * PEER_TICKS, () -> players(mc) == 3)
                .step("A and C are where they were, with their items", SHORT_TICKS,
                        () -> markedOnServer(mc, MARK_A) && markedOnServer(mc, MARK_C))
                .step("A and C confirmed it themselves", PEER_TICKS, () -> ctx.sharedExists("a-back") && ctx.sharedExists("c-back"))
                .action("tell A and C", () -> ctx.writeShared("b-checked", "ok"))
                .step("A and C left", PEER_TICKS, () -> players(mc) == 1);
    }

    private static StepRunner handoffC(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        // C joins after B has its copy: the successor is the fully synced member that connected first, and
        // the scenario expects that to be B (through the relay the two would otherwise race).
        StepRunner runner = joinB(ctx, state, HANDOFF_ON, "b-marked")
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state));
        return mark(runner, mc, MARK_C)
                .action("tell A", () -> ctx.writeShared("c-marked", "ok"))
                // No input from here on: A quits, B takes over, and this game must be back in the world by itself.
                .step("back in the world, on B's session", 4 * PEER_TICKS, () -> MemberHandoff.lastRole() == MemberHandoff.Role.FOLLOWER
                        && ctx.sharedExists("b-hosting") && inWorld(mc) && mc.getIntegratedServer() == null
                        && SyncClient.status(state.world.worldId()) != null)
                .step("C has its item at its place", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return marked(mc.player, MARK_C);
                })
                .action("tell B", () -> ctx.writeShared("c-back", "ok"))
                .step("B checked everyone", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("b-checked");
                });
    }

    // ---- handoff-no-full-copy -------------------------------------------------------------------------

    private static StepRunner noFullCopyA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        // A few hundred records, each held back a quarter of a second: B stays mid-download for minutes.
        return hostA(ctx, state, Map.of("dropin.dev.syncDelayMs", "250", "dropin.dev.noHandoff", "false"), 450)
                .step("B is mid-download", STARTUP_TICKS + 2 * PEER_TICKS, () -> ctx.sharedExists("b-partial"))
                .action("A quits", () -> dropin.mc.compat.Compat.disconnectFromWorld(mc, dropin.mc.compat.Compat.DEFAULT_QUIT_MESSAGE))
                // A checkpoint, then DESIGN 4.9's wait of up to 30 s for B, which cannot finish in time.
                .step("the confirmation is shown", SHORT_TICKS, () -> HostHandoff.state() == HostHandoff.State.CONFIRMING)
                .action("it names B, and the world is still running", () -> {
                    // 1.12.2 port: the 26.x ConfirmScreen is dropin.mc.coop.Prompts.Confirm.
                    require(mc.currentScreen instanceof Prompts.Confirm, "the screen is " + mc.currentScreen);
                    HostSync.Standing standing = HostHandoff.lastStanding();
                    require(standing.successorUuid() == null, "a successor was found: " + standing.successorUuid());
                    require(standing.peers() == 1, standing.peers() + " members were counted, expected 1");
                    require(standing.bestName().equals("DevB"), "the confirmation names '" + standing.bestName() + "', expected DevB");
                    require(standing.bestPercent() > 0 && standing.bestPercent() < 100,
                            "the confirmation says " + standing.bestPercent() + "%, expected B's partial progress");
                    require(mc.getIntegratedServer() != null && mc.getIntegratedServer().isServerRunning() && players(mc) == 2,
                            "the world stopped or B was dropped while the confirmation is open");
                    DropIn.LOGGER.info("Self-test: confirmation shown: nobody has a full copy ({} {}%)", standing.bestName(), standing.bestPercent());
                })
                .action("cancel", () -> HostHandoff.answer(false))
                .step("back in the game, still hosting, B still in", SHORT_TICKS, () -> mc.currentScreen == null && hosting(ctx)
                        && HostHandoff.state() == HostHandoff.State.IDLE && players(mc) == 2)
                .action("tell B", () -> ctx.writeShared("a-cancelled", "ok"))
                .step("B kept playing and downloading", PEER_TICKS, () -> ctx.sharedExists("b-still-in"))
                .action("the session is alive", () -> {
                    HostSync sync = HostSync.current();
                    HostSession session = HostSession.current();
                    require(sync != null && session != null, "the host session ended");
                    require(session.announcedState().equals("host"), "the world is announced as " + session.announcedState());
                    require(HostSync.lastHandedTo() == null || HostSync.lastHandedTo().isEmpty(), "a handoff was sent");
                })
                .step("B left", PEER_TICKS, () -> ctx.sharedExists("b-left") && players(mc) == 1);
    }

    private static StepRunner noFullCopyB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        int[] ticks = {0};
        int[] stayed = {0};
        long[] applied = {0};
        return joinB(ctx, state, HANDOFF_ON)
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
                    require(!saved.complete(), "the download finished before A could quit; the world is too small");
                    return saved.have() > 0;
                })
                .action("tell A", () -> ctx.writeShared("b-partial", "ok"))
                .step("A cancelled its quit; B was never dropped", 2 * SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    require(MemberHandoff.lastRole() == MemberHandoff.Role.NONE, "B received a handoff");
                    return ctx.sharedExists("a-cancelled");
                })
                .action("note the download", () -> applied[0] = SyncClient.appliedRecords(state.world.worldId()))
                .step("still playing, and the download goes on", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    require(joinedRemote(mc), "B is no longer in A's session");
                    SyncClientSession.Status live = SyncClient.status(state.world.worldId());
                    require(live != null && live.phase() != Phase.FAILED, "B's sync ended: " + (live == null ? "stopped" : live.detail()));
                    return ++stayed[0] > 100 && (live.phase() == Phase.LIVE || SyncClient.appliedRecords(state.world.worldId()) > applied[0]);
                })
                .action("tell A", () -> ctx.writeShared("b-still-in", "ok"))
                .action("leave", () -> dropin.mc.compat.Compat.disconnectFromWorld(mc, dropin.mc.compat.Compat.DEFAULT_QUIT_MESSAGE))
                .step("left", SHORT_TICKS, () -> mc.world == null)
                .action("tell A", () -> ctx.writeShared("b-left", "ok"));
    }
}
