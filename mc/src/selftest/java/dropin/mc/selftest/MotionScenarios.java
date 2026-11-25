package dropin.mc.selftest;

import dropin.mc.DropIn;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import net.minecraft.block.BlockRailPowered;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityMinecartEmpty;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.M2Scenarios.copyComplete;
import static dropin.mc.selftest.M2Scenarios.hostA;
import static dropin.mc.selftest.M2Scenarios.joinB;
import static dropin.mc.selftest.M3Scenarios.myUuid;
import static dropin.mc.selftest.M3Scenarios.players;
import static dropin.mc.selftest.M3Scenarios.runCommand;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.SHORT_TICKS;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;

/**
 * Things that move by themselves are the same on every screen. The host's server is the only place where a
 * minecart rolls and a mob thinks; this scenario follows a minecart ride, a cow led by wheat (path finding)
 * and a cow fleeing after a hit (random path) tick by tick on the host's server and on the screens of the
 * host and of two other players, and compares the paths, the resting places and the mob's status.
 * Development only.
 */
final class MotionScenarios {
    /** How far a screen may be from the server's path while the thing moves (blocks). */
    private static final double PATH_TOLERANCE = 0.75;
    /** How far a screen may be from the server once the thing stands still (blocks). */
    private static final double REST_TOLERANCE = 0.05;
    /** How far behind the server a screen may be (ms). */
    private static final long LAG_MS = 1_000;
    /** 1.12.2 port: the ground of the flat world is y 4 (M3Scenarios.GROUND_Y); 1.21.1 used y -60. */
    private static final int Y = M3Scenarios.GROUND_Y;

    private static final String CART = "minecart";
    private static final String LED = "cow-led-by-wheat";
    private static final String FLEE = "cow-fleeing";

    private MotionScenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "motion:a" -> motionA(ctx);
            case "motion:b" -> member(ctx, "b");
            case "motion:c" -> member(ctx, "c");
            default -> null;
        };
    }

    private record Sample(long ms, Vec3d at) {
    }

    private record Compared(int samples, double moved, double worst, long lagMs, double rest, String worstAt) {
        String text() {
            return String.format(Locale.ROOT, "%d samples, the server moved it %.2f blocks, farthest from the server's path %.3f "
                    + "blocks, behind the server %d ms, at rest %.4f blocks from the server (farthest: %s)", samples, moved, worst, lagMs, rest, worstAt);
        }
    }

    private static String encode(List<Sample> samples) {
        StringBuilder out = new StringBuilder();
        for (Sample sample : samples) {
            out.append(sample.ms()).append(' ').append(sample.at().x).append(' ').append(sample.at().y).append(' ')
                    .append(sample.at().z).append('\n');
        }
        return out.toString();
    }

    private static List<Sample> decode(String text) {
        List<Sample> samples = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split(" ");
            if (parts.length == 4) {
                samples.add(new Sample(Long.parseLong(parts[0]),
                        new Vec3d(Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]))));
            }
        }
        return samples;
    }

    /**
     * One screen against the server. Every position the screen showed must be near a position the server had
     * shortly before (the screen draws between the server's updates), and the resting places must be equal.
     */
    private static Compared compare(List<Sample> truth, List<Sample> screen) {
        long from = truth.get(0).ms();
        long to = truth.get(truth.size() - 1).ms();
        double moved = 0;
        for (int i = 1; i < truth.size(); i++) {
            moved += truth.get(i).at().distanceTo(truth.get(i - 1).at());
        }
        int samples = 0;
        double worst = 0;
        String worstAt = "never off the path";
        List<Long> lags = new ArrayList<>();
        for (Sample shown : screen) {
            if (shown.ms() < from || shown.ms() > to) {
                continue;
            }
            samples++;
            double nearest = Double.MAX_VALUE;
            int nearestIndex = -1;
            for (int i = 0; i < truth.size(); i++) {
                long age = shown.ms() - truth.get(i).ms();
                if (age < -100 || age > LAG_MS) {
                    continue;
                }
                // The server's path is the line through its samples: the host's game takes them once per client
                // tick, and when that tick is late the thing was still somewhere on the line in between.
                double distance = i == 0 ? truth.get(0).at().distanceTo(shown.at())
                        : toSegment(shown.at(), truth.get(i - 1).at(), truth.get(i).at());
                // Of equally near server positions the newest one counts: a thing that stands still has no lag.
                if (distance <= nearest) {
                    nearest = distance;
                    nearestIndex = i;
                }
            }
            if (nearestIndex < 0) {
                continue;
            }
            if (nearest > worst) {
                worst = nearest;
                Vec3d server = truth.get(nearestIndex).at();
                worstAt = String.format(Locale.ROOT, "%d ms after the start the screen showed %.2f %.2f %.2f, the server's "
                                + "nearest place was %.2f %.2f %.2f", shown.ms() - from, shown.at().x, shown.at().y, shown.at().z,
                        server.x, server.y, server.z);
            }
            boolean moving = nearestIndex > 0
                    && truth.get(nearestIndex).at().distanceTo(truth.get(nearestIndex - 1).at()) > 0.02
                    && nearestIndex + 1 < truth.size()
                    && truth.get(nearestIndex + 1).at().distanceTo(truth.get(nearestIndex).at()) > 0.02;
            if (moving) {
                lags.add(shown.ms() - truth.get(nearestIndex).ms());
            }
        }
        lags.sort(Long::compare);
        long lag = lags.isEmpty() ? 0 : lags.get(lags.size() / 2);
        double rest = screen.isEmpty() ? Double.MAX_VALUE
                : screen.get(screen.size() - 1).at().distanceTo(truth.get(truth.size() - 1).at());
        return new Compared(samples, moved, worst, lag, rest, worstAt);
    }

    private static double toSegment(Vec3d point, Vec3d from, Vec3d to) {
        Vec3d line = to.subtract(from);
        double length = line.lengthSquared();
        if (length < 1.0e-12) {
            return point.distanceTo(to);
        }
        double along = Math.max(0.0, Math.min(1.0, point.subtract(from).dotProduct(line) / length));
        return point.distanceTo(from.add(line.scale(along)));
    }

    /** The longest time between two samples of the server's path (ms). */
    private static long longestGap(List<Sample> truth) {
        long gap = 0;
        for (int i = 1; i < truth.size(); i++) {
            gap = Math.max(gap, truth.get(i).ms() - truth.get(i - 1).ms());
        }
        return gap;
    }

    private static void judge(String phase, String who, Compared result, double mustMove) {
        DropIn.LOGGER.info("Self-test motion: {} on the screen of {}: {}", phase, who, result.text());
        require(result.moved() >= mustMove, phase + ": the server moved it only " + result.moved() + " blocks");
        require(result.samples() >= 20, phase + ": the screen of " + who + " showed it for only " + result.samples() + " ticks");
        require(result.worst() < PATH_TOLERANCE, phase + ": the screen of " + who + " was " + result.worst() + " blocks off the server's path");
        require(result.lagMs() < LAG_MS, phase + ": the screen of " + who + " was " + result.lagMs() + " ms behind");
        require(result.rest() < REST_TOLERANCE, phase + ": at rest the screen of " + who + " is " + result.rest() + " blocks from the server");
    }

    private static Entity onScreen(Minecraft mc, UUID id) {
        if (mc.world != null) {
            // 1.12.2 port: the client world's entities are WorldClient.loadedEntityList (26.x entitiesForRendering).
            for (Entity entity : mc.world.loadedEntityList) {
                if (entity.getUniqueID().equals(id)) {
                    return entity;
                }
            }
        }
        return null;
    }

    /** 1.21.1 {@code entity.position()}. */
    private static Vec3d position(Entity entity) {
        return new Vec3d(entity.posX, entity.posY, entity.posZ);
    }

    private static boolean still(List<Sample> samples, int ticks) {
        if (samples.size() < ticks + 1) {
            return false;
        }
        Vec3d last = samples.get(samples.size() - 1).at();
        for (int i = samples.size() - ticks; i < samples.size(); i++) {
            if (samples.get(i).at().distanceTo(last) > 0.0005) {
                return false;
            }
        }
        return true;
    }

    private static <T> T onServer(IntegratedServer server, Callable<T> task) throws Exception {
        return M3Scenarios.onServer(server, task);
    }

    /**
     * A command as the server, on the server thread. 1.12.2 port: the server's own command sender has no
     * "suppressed output" switch (its feedback goes to the log only); a command that did nothing fails the step.
     */
    private static void command(IntegratedServer server, String command) {
        require(runCommand(server, command) > 0, "the command '" + command + "' did nothing");
    }

    // ---- the host -------------------------------------------------------------------------------------

    /** What the host does to start a phase (on the server thread); returns the thing to follow. */
    private interface Start {
        Entity run(IntegratedServer server, WorldServer level, EntityPlayerMP host) throws Exception;
    }

    /**
     * One phase on the host: start it, follow the thing on the server and on the host's own screen until it has
     * moved and stands still (or {@code freezeAfter} ticks passed and it was stopped), publish the server's
     * path, and wait for both other players to agree.
     */
    private static StepRunner phase(StepRunner runner, Context ctx, String phase, double mustMove, int freezeAfter, Start start) {
        Minecraft mc = ctx.mc();
        UUID[] id = {null};
        List<Sample> truth = new ArrayList<>();
        List<Sample> screen = new ArrayList<>();
        int[] ticks = {0};
        return runner
                .action(phase + ": start", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    id[0] = onServer(server, () -> {
                        EntityPlayerMP host = server.getPlayerList().getPlayerByUUID(UUID.fromString(myUuid(mc)));
                        Entity started = start.run(server, server.getWorld(0), host);
                        // The path begins where the thing is now: the screens show this place first.
                        truth.add(new Sample(System.currentTimeMillis(), position(started)));
                        return started.getUniqueID();
                    });
                    ctx.writeShared("go-" + phase, id[0].toString());
                })
                .step(phase + ": follow it on the server", SHORT_TICKS, () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    boolean freeze = freezeAfter > 0 && ticks[0] == freezeAfter;
                    Vec3d at = onServer(server, () -> {
                        Entity entity = server.getWorld(0).getEntityFromUuid(id[0]);
                        if (entity == null) {
                            return null;
                        }
                        if (freeze && entity instanceof EntityCow cow) {
                            cow.setNoAI(true);
                            cow.motionX = 0.0;
                            cow.motionY = 0.0;
                            cow.motionZ = 0.0;
                            // 1.12.2 port: with no AI the walk input is not cleared (EntityLivingBase.onLivingUpdate only
                            // lets it decay), so the cow would drift on; 26.x stops it. Clear the input and the path.
                            cow.moveForward = 0.0F;
                            cow.moveStrafing = 0.0F;
                            cow.moveVertical = 0.0F;
                            cow.getNavigator().clearPath();
                        }
                        return position(entity);
                    });
                    require(at != null, phase + ": it is gone from the server");
                    long now = System.currentTimeMillis();
                    truth.add(new Sample(now, at));
                    Entity mine = onScreen(mc, id[0]);
                    if (mine != null) {
                        screen.add(new Sample(now, position(mine)));
                    }
                    ticks[0]++;
                    double moved = truth.get(0).at().distanceTo(at);
                    boolean ended = freezeAfter > 0 ? ticks[0] > freezeAfter + 40 : moved >= mustMove * 0.5;
                    if (!ended || !still(truth, 40)) {
                        return false;
                    }
                    DropIn.LOGGER.info("Self-test motion: {}: {} samples of the server's path, longest time between two {} ms",
                            phase, truth.size(), longestGap(truth));
                    ctx.writeShared("truth-" + phase, encode(truth));
                    return true;
                })
                .step(phase + ": the same on the host's own screen", SHORT_TICKS, () -> {
                    Entity mine = onScreen(mc, id[0]);
                    require(mine != null, phase + ": it is not on the host's screen");
                    screen.add(new Sample(System.currentTimeMillis(), position(mine)));
                    if (screen.size() < truth.size() + 40) {
                        return false;
                    }
                    judge(phase, "the host", compare(truth, screen), mustMove);
                    return true;
                })
                .step(phase + ": B and C saw the same", PEER_TICKS, () -> {
                    for (String who : new String[]{"b", "c"}) {
                        if (!ctx.sharedExists(who + "-" + phase)) {
                            return false;
                        }
                    }
                    for (String who : new String[]{"b", "c"}) {
                        DropIn.LOGGER.info("Self-test motion: {} on the screen of {}: {}", phase, who.toUpperCase(Locale.ROOT),
                                ctx.readShared(who + "-" + phase).trim());
                    }
                    return true;
                });
    }

    private static StepRunner motionA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        UUID[] cow = {null};
        StepRunner runner = hostA(ctx, state, Map.of(), 0)
                .step("B and C dropped in", 2 * PEER_TICKS, () -> players(mc) == 3 && ctx.sharedExists("b-ready") && ctx.sharedExists("c-ready"))
                .action("everybody stands at the track", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    String b = ctx.readShared("b-ready").trim();
                    String c = ctx.readShared("c-ready").trim();
                    onServer(server, () -> {
                        WorldServer level = server.getWorld(0);
                        teleport(server.getPlayerList().getPlayerByUUID(UUID.fromString(myUuid(mc))), 2.5, Y, -4.5);
                        teleport(server.getPlayerList().getPlayerByUUID(UUID.fromString(b)), 8.5, Y, 0.5);
                        teleport(server.getPlayerList().getPlayerByUUID(UUID.fromString(c)), 20.5, Y, 0.5);
                        // 1.12.2 port: /fill and /setblock refuse chunks that are not loaded, and the players' chunks
                        // load on their next tick: load the track's chunks (x -1..32, z 4) now.
                        for (int cx = -1; cx <= 2; cx++) {
                            level.getChunkProvider().provideChunk(cx, 0);
                        }
                        // Redstone under 18 rails; powered rails pass power on for 8 more (to x 25). 26 to 31 are off and brake.
                        // 1.12.2 port: the powered rail is minecraft:golden_rail, its state given after the block name.
                        command(server, "fill 0 " + (Y - 1) + " 4 17 " + (Y - 1) + " 4 minecraft:redstone_block");
                        command(server, "fill 0 " + Y + " 4 31 " + Y + " 4 minecraft:golden_rail shape=east_west");
                        command(server, "setblock 32 " + Y + " 4 minecraft:stone");
                        command(server, "setblock -1 " + Y + " 4 minecraft:stone");
                        IBlockState on = level.getBlockState(new BlockPos(5, Y, 4));
                        require(on.getBlock() == Blocks.GOLDEN_RAIL && on.getValue(BlockRailPowered.POWERED),
                                "the rail at 5 " + Y + " 4 is not a powered rail that is on");
                        IBlockState off = level.getBlockState(new BlockPos(28, Y, 4));
                        require(off.getBlock() == Blocks.GOLDEN_RAIL && !off.getValue(BlockRailPowered.POWERED),
                                "the braking rail at 28 " + Y + " 4 is on");
                        return null;
                    });
                })
                .step("the players are there", SHORT_TICKS, () -> mc.player != null && mc.player.getDistanceSq(2.5, Y, -4.5) < 1.0);
        runner = pause(runner, "the screens settle", 40);

        // 1. A minecart: pushed once, driven by powered rails, braked at the end.
        runner = phase(runner, ctx, CART, 10.0, 0, (server, level, host) -> {
            // 1.21.1 EntityType.spawn puts it at the middle of the block's floor.
            EntityMinecartEmpty cart = new EntityMinecartEmpty(level, 1.5, Y, 4.5);
            cart.motionX = 0.3;
            cart.motionY = 0.0;
            cart.motionZ = 0.0;
            require(level.spawnEntity(cart), "the minecart was not spawned");
            return cart;
        });

        // 2. Path finding: a cow walks to the player who holds wheat.
        // 1.12.2 port: EntityAITempt follows only the nearest player within 10 blocks (26.x: the nearest one holding
        // the food), so the cow starts 8 blocks from A and farther from B and C: 2 -12 instead of 1.21.1's 11 -5,
        // where B was nearer than A. The walk is as long (about 6 blocks); every screen still sees it.
        runner = phase(runner, ctx, LED, 3.0, 0, (server, level, host) -> {
            EntityCow spawned = new EntityCow(level);
            spawned.setLocationAndAngles(2.5, Y, -12.5, 0.0F, 0.0F);
            spawned.setGrowingAge(0);
            spawned.enablePersistence();
            require(level.spawnEntity(spawned), "the cow was not spawned");
            host.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(Items.WHEAT));
            cow[0] = spawned.getUniqueID();
            return spawned;
        });

        // 3. A random path: the cow is hit and flees for five seconds, then is stopped.
        runner = phase(runner, ctx, FLEE, 2.0, 100, (server, level, host) -> {
            host.setHeldItem(EnumHand.MAIN_HAND, ItemStack.EMPTY);
            EntityCow hit = (EntityCow) level.getEntityFromUuid(cow[0]);
            require(hit.attackEntityFrom(DamageSource.causePlayerDamage(host), 1.0f), "the hit did no damage");
            return hit;
        });

        // 4. Status: health after the hit, and three flags changed now.
        return runner
                .action("status: the cow becomes a glowing calf with a name", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    String truth = onServer(server, () -> {
                        EntityCow changed = (EntityCow) server.getWorld(0).getEntityFromUuid(cow[0]);
                        // 1.21.1 setBaby(true): a calf's age as a spawned baby has it.
                        changed.setGrowingAge(-24000);
                        changed.setGlowing(true);
                        changed.setCustomNameTag("Synced");
                        return status(changed);
                    });
                    DropIn.LOGGER.info("Self-test motion: status on the server: {}", truth);
                    ctx.writeShared("truth-status", System.currentTimeMillis() + "|" + cow[0] + "|" + truth);
                })
                .step("status: the same on the host's own screen", PEER_TICKS, () -> sameStatus(ctx, mc, "the host") >= 0)
                .step("status: B and C saw the same", PEER_TICKS, () -> {
                    for (String who : new String[]{"b", "c"}) {
                        if (!ctx.sharedExists(who + "-status")) {
                            return false;
                        }
                    }
                    ctx.writeShared("a-done", "ok");
                    return true;
                });
    }

    /** Health, age, glow and name of a mob as one line: equal lines mean equal status. */
    private static String status(Entity entity) {
        EntityLivingBase living = (EntityLivingBase) entity;
        return "health " + living.getHealth() + ", baby " + living.isChild() + ", glowing " + entity.isGlowing()
                + ", name " + entity.getCustomNameTag();
    }

    /** 1.21.1 {@code ServerPlayer.teleportTo(x, y, z)}. */
    private static void teleport(EntityPlayerMP player, double x, double y, double z) {
        player.connection.setPlayerLocation(x, y, z, player.rotationYaw, player.rotationPitch);
    }

    /** Milliseconds until this screen showed the server's status, or -1 while it does not. */
    private static long sameStatus(Context ctx, Minecraft mc, String who) throws IOException {
        if (!ctx.sharedExists("truth-status")) {
            return -1;
        }
        String[] truth = ctx.readShared("truth-status").trim().split("\\|", 3);
        Entity entity = onScreen(mc, UUID.fromString(truth[1]));
        if (entity == null || !status(entity).equals(truth[2])) {
            return -1;
        }
        long delay = Math.max(0, System.currentTimeMillis() - Long.parseLong(truth[0]));
        DropIn.LOGGER.info("Self-test motion: status on the screen of {} equals the server's after {} ms: {}", who, delay, truth[2]);
        require(delay < 2 * LAG_MS, "the status took " + delay + " ms to reach the screen of " + who);
        return delay;
    }

    private static StepRunner pause(StepRunner runner, String name, int ticks) {
        int[] count = {0};
        return runner.step(name, ticks + SHORT_TICKS, () -> ++count[0] >= ticks);
    }

    // ---- the other players ------------------------------------------------------------------------------

    private static StepRunner watch(StepRunner runner, Context ctx, String who, String phase, double mustMove) {
        Minecraft mc = ctx.mc();
        List<Sample> screen = new ArrayList<>();
        UUID[] id = {null};
        int[] after = {0};
        return runner.step(phase + ": watch it", 2 * PEER_TICKS, () -> {
            failIfDisconnected(mc);
            if (id[0] == null) {
                if (!ctx.sharedExists("go-" + phase)) {
                    return false;
                }
                id[0] = UUID.fromString(ctx.readShared("go-" + phase).trim());
            }
            Entity entity = onScreen(mc, id[0]);
            if (entity != null) {
                screen.add(new Sample(System.currentTimeMillis(), position(entity)));
            }
            // The server's path is published when the thing stands still there; this screen gets two more seconds.
            if (!ctx.sharedExists("truth-" + phase) || ++after[0] < 40) {
                return false;
            }
            Compared result = compare(decode(ctx.readShared("truth-" + phase)), screen);
            judge(phase, who.toUpperCase(Locale.ROOT), result, mustMove);
            ctx.writeShared(who + "-" + phase, result.text());
            return true;
        });
    }

    private static StepRunner member(Context ctx, String who) {
        Minecraft mc = ctx.mc();
        State state = new State();
        // C joins once B has its copy: one first download at a time.
        StepRunner runner = (who.equals("b") ? joinB(ctx, state, Map.of()) : joinB(ctx, state, Map.of(), "b-ready"))
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("tell A", () -> ctx.writeShared(who + "-ready", myUuid(mc)));
        runner = watch(runner, ctx, who, CART, 10.0);
        runner = watch(runner, ctx, who, LED, 3.0);
        runner = watch(runner, ctx, who, FLEE, 2.0);
        return runner
                .step("status: the same here", 2 * PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    long delay = sameStatus(ctx, mc, who.toUpperCase(Locale.ROOT));
                    if (delay < 0) {
                        return false;
                    }
                    ctx.writeShared(who + "-status", Long.toString(delay));
                    return true;
                })
                .step("A agrees", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("a-done");
                });
    }
}
