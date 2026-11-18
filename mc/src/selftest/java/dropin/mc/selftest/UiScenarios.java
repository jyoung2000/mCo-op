package dropin.mc.selftest;

import dropin.mc.DropIn;
import dropin.mc.host.HostSync;
import dropin.mc.menu.CoopStatus;
import dropin.mc.menu.PlayersScreen;
import dropin.mc.policy.PolicyActions;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.M2Scenarios.copyComplete;
import static dropin.mc.selftest.M2Scenarios.hostA;
import static dropin.mc.selftest.M2Scenarios.joinB;
import static dropin.mc.selftest.M3Scenarios.players;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;

/** Admins (DESIGN 4.13) and who is online in the menu (DESIGN 4.15). Development only. */
final class UiScenarios {
    private UiScenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "admin-online:a" -> adminA(ctx);
            case "admin-online:b" -> adminB(ctx);
            default -> null;
        };
    }

    /** Asks for a fresh status every second; true once the announced record says the player is in the session. */
    private static boolean online(State state, String uuid, int[] ticks) {
        if (ticks[0]++ % 20 == 0) {
            CoopStatus.refresh(state.world);
        }
        return CoopStatus.of(state.world.worldId()).isOnline(uuid);
    }

    private static StepRunner adminA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        String[] b = {null};
        int[] ticks = {0};
        return hostA(ctx, state, Map.of(), 0)
                .step("B dropped in and has its copy", 2 * PEER_TICKS, () -> players(mc) == 2 && ctx.sharedExists("b-ready"))
                .action("B is a plain member", () -> {
                    String me = mc.getUser().getProfileId().toString();
                    for (ServerPlayer player : mc.getSingleplayerServer().getPlayerList().getPlayers()) {
                        if (!player.getGameProfile().id().toString().equals(me)) {
                            b[0] = player.getGameProfile().id().toString();
                        }
                    }
                    require(b[0] != null, "B is not on the server");
                    LevelBasedPermissionSet before = HostSync.permissions(mc.getSingleplayerServer(), UUID.fromString(b[0]));
                    require(before != LevelBasedPermissionSet.ADMIN && before != LevelBasedPermissionSet.OWNER, "B already has admin rights");
                    require(HostSync.permissions(mc.getSingleplayerServer(), UUID.fromString(me)) == LevelBasedPermissionSet.OWNER,
                            "the owner does not have owner rights");
                })
                .step("the announcement names both players as online", PEER_TICKS,
                        () -> online(state, b[0], ticks) && online(state, mc.getUser().getProfileId().toString(), ticks))
                .action("make B an admin", () -> {
                    PolicyActions.setAdmin(mc, state.world, b[0], true);
                    require(HostSync.permissions(mc.getSingleplayerServer(), UUID.fromString(b[0])) == LevelBasedPermissionSet.ADMIN,
                            "the host does not give the admin admin rights");
                    require(PlayersScreen.rowsOf(state.world.worldId()).stream().anyMatch(row -> row.uuid().equals(b[0]) && row.admin()
                            && row.playingNow()), "A's player list does not show B as an online admin");
                    DropIn.LOGGER.info("Self-test: B is an admin under policy version {}", HostSync.current().held().version());
                    ctx.writeShared("a-admin", "ok");
                })
                .step("B saw it", PEER_TICKS, () -> ctx.sharedExists("b-done"))
                .action("take it away again", () -> {
                    PolicyActions.setAdmin(mc, state.world, b[0], false);
                    require(HostSync.permissions(mc.getSingleplayerServer(), UUID.fromString(b[0])) != LevelBasedPermissionSet.ADMIN,
                            "B kept admin rights");
                });
    }

    private static StepRunner adminB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        int[] ticks = {0};
        String me = mc.getUser().getProfileId().toString();
        return joinB(ctx, state, Map.of())
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("tell A", () -> {
                    require(PlayersScreen.rowsOf(state.world.worldId()).stream().noneMatch(PlayersScreen.Row::admin), "someone is an admin already");
                    ctx.writeShared("b-ready", "ok");
                })
                .step("A made B an admin", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("a-admin");
                })
                // The signed policy reaches B over the sync channel; the player list is read from the announcement.
                .step("B's own menu data shows B as an online admin and A as the online owner", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    online(state, me, ticks);
                    List<PlayersScreen.Row> rows = PlayersScreen.rowsOf(state.world.worldId());
                    return rows.stream().anyMatch(row -> row.uuid().equals(me) && row.admin() && !row.owner() && row.playingNow())
                            && rows.stream().anyMatch(row -> row.owner() && !row.admin() && row.playingNow());
                })
                .action("tell A", () -> {
                    DropIn.LOGGER.info("Self-test: B sees itself as an online admin");
                    ctx.writeShared("b-done", "ok");
                });
    }
}
