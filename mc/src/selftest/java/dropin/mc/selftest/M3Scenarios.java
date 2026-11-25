package dropin.mc.selftest;

import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.sync.SyncClientSession;
import dropin.core.sync.SyncClientSession.Phase;
import dropin.core.sync.SyncState;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.HostSession;
import dropin.mc.handoff.HostHandoff;
import dropin.mc.handoff.MemberHandoff;
import dropin.mc.host.HostSync;
import dropin.mc.member.SyncClient;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Predicate;

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

    // ---- building blocks --------------------------------------------------------------------------

    static StepRunner mark(StepRunner runner, Minecraft mc, Mark mark) {
        return runner
                .action("give and move", () -> {
                    mc.player.connection.sendCommand("give @s " + mark.itemId() + " 3");
                    mc.player.connection.sendCommand("tp @s " + mark.x() + " -60 " + mark.z());
                })
                .step(mark.player() + " has its item at its place", SHORT_TICKS, () -> marked(mc.player, mark));
    }

    static boolean marked(Player player, Mark mark) {
        Predicate<ItemStack> isItem = stack -> stack.is(mark.item());
        return player != null && Scenarios.has(player.getInventory(), isItem)
                && Math.abs(player.getX() - mark.x()) < 2 && Math.abs(player.getZ() - mark.z()) < 2;
    }

    /** What the server (the authority) says about a player. */
    static boolean markedOnServer(Minecraft mc, Mark mark) {
        IntegratedServer server = mc.getSingleplayerServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayerByName(mark.player());
        return marked(player, mark);
    }

    static int players(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        return server == null ? 0 : server.getPlayerList().getPlayerCount();
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
                        () -> mc.getSingleplayerServer() == null && mc.level == null && HostHandoff.state() == HostHandoff.State.IDLE)
                .action("the world went to B", () -> {
                    String b = ctx.readShared("b-uuid");
                    HostSync.Standing standing = HostHandoff.lastStanding();
                    require(standing != null && b.equals(standing.successorUuid()), "before quitting, the successor was "
                            + (standing == null ? "not looked for" : standing.successorUuid()) + ", expected B " + b);
                    require(b.equals(HostSync.lastHandedTo()), "A handed the world to '" + HostSync.lastHandedTo() + "', expected B " + b);
                    Path file = dropin.mc.compat.Compat.levelPath(mc.getLevelSource(), WORLD_ID).resolve(CoopWorldStore.MARKER_DIR).resolve("ledger.jsonl");
                    LedgerEntry last = Ledger.load(state.world.keys().auth(), file).last();
                    require(last != null, "A has no checkpoint");
                    require(last.root().equals(HostSync.lastFinalRoot()), "the ledger ends on root " + last.root()
                            + " but the handoff carried " + HostSync.lastFinalRoot());
                    DropIn.LOGGER.info("Self-test: A ended on checkpoint {} with root {} and handed over to {}", last.seq(), last.root(), b);
                    ctx.writeShared("a-root", last.seq() + " " + last.root());
                    mc.setScreen(new TitleScreen());
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
                    ctx.writeShared("b-uuid", mc.getUser().getProfileId().toString());
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
                        && ctx.sharedExists("b-hosting") && inWorld(mc) && mc.getSingleplayerServer() == null
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
                    require(mc.screen instanceof ConfirmScreen, "the screen is " + mc.screen);
                    HostSync.Standing standing = HostHandoff.lastStanding();
                    require(standing.successorUuid() == null, "a successor was found: " + standing.successorUuid());
                    require(standing.peers() == 1, standing.peers() + " members were counted, expected 1");
                    require(standing.bestName().equals("DevB"), "the confirmation names '" + standing.bestName() + "', expected DevB");
                    require(standing.bestPercent() > 0 && standing.bestPercent() < 100,
                            "the confirmation says " + standing.bestPercent() + "%, expected B's partial progress");
                    require(mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isRunning() && players(mc) == 2,
                            "the world stopped or B was dropped while the confirmation is open");
                    DropIn.LOGGER.info("Self-test: confirmation shown: nobody has a full copy ({} {}%)", standing.bestName(), standing.bestPercent());
                })
                .action("cancel", () -> HostHandoff.answer(false))
                .step("back in the game, still hosting, B still in", SHORT_TICKS, () -> mc.screen == null && hosting(ctx)
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
                .step("left", SHORT_TICKS, () -> mc.level == null)
                .action("tell A", () -> ctx.writeShared("b-left", "ok"));
    }
}
