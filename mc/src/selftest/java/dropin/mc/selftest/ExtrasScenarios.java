package dropin.mc.selftest;

import dropin.mc.DropIn;
import dropin.mc.host.HostSync;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.M2Scenarios.copyComplete;
import static dropin.mc.selftest.M2Scenarios.folder;
import static dropin.mc.selftest.M2Scenarios.hostA;
import static dropin.mc.selftest.M2Scenarios.joinB;
import static dropin.mc.selftest.M3Scenarios.players;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.WORLD_ID;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;

/**
 * Three things in one session of three players: what a mod keeps in the world's root reaches every copy
 * (DESIGN 4.7), a direct connection over the internet stays under its rate cap (DESIGN 4.6), and the player
 * with the faster upload is the one who would take over (DESIGN 4.9). Development only.
 */
final class ExtrasScenarios {
    /** The cap this scenario sets, far below what one PC does by itself (KB/s). */
    private static final int CAP_KBPS = 256;
    private static final String CONFIG = "serverconfig/testmod-server.toml";
    private static final String STATE = "testmod/deep/state.dat";
    private static final String CONFIG_TEXT = "[general]\n\tsharedWithEveryone = true\n";
    private static final String STATE_TEXT = "what a mod keeps beside the world";

    private ExtrasScenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "sync-extras:a" -> extrasA(ctx);
            case "sync-extras:b" -> member(ctx, "b");
            case "sync-extras:c" -> member(ctx, "c");
            default -> null;
        };
    }

    private static StepRunner extrasA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        List<long[]> sent = new ArrayList<>();
        List<Long> sentAt = new ArrayList<>();
        List<Map<String, Long>> perMember = new ArrayList<>();
        // Both members count as "over the internet" here, so the cap applies although they are on this PC.
        return hostA(ctx, state, Map.of("dropin.dev.forcePath", "internet", "dropin.dev.directRateKBps", Integer.toString(CAP_KBPS)), 450)
                .action("a mod's files appear in the world's root", () -> {
                    Path root = dropin.mc.menu.CoopActions.levelPath(mc, WORLD_ID);
                    Files.createDirectories(root.resolve(CONFIG).getParent());
                    Files.createDirectories(root.resolve(STATE).getParent());
                    Files.writeString(root.resolve(CONFIG), CONFIG_TEXT, StandardCharsets.UTF_8);
                    Files.writeString(root.resolve(STATE), STATE_TEXT, StandardCharsets.UTF_8);
                })
                .step("B and C have the world and the mod's files", 6 * PEER_TICKS, () -> {
                    HostSync sync = HostSync.current();
                    if (sync != null) {
                        long now = System.currentTimeMillis();
                        sent.add(new long[]{now, sync.recordBytes()});
                        sentAt.add(now);
                        perMember.add(sync.devRecordBytesByMember());
                    }
                    return players(mc) == 3 && ctx.sharedExists("b-ready") && ctx.sharedExists("c-ready");
                })
                .action("the downloads stayed under the cap", () -> {
                    long total = sent.get(sent.size() - 1)[1] - sent.get(0)[1];
                    DropIn.LOGGER.info("Self-test sync-extras: {} bytes of records went to B and C", total);
                    require(total >= 768 * 1024, "only " + total + " bytes were sent: too little to see a cap");
                    // 1.12.2 port (owner approved 2026-11-27): the cap is per connection (DESIGN 5, flow control), so each
                    // connection is checked on its own. The summed check of 26.3/1.21.1 passed there only because B was
                    // idle while C downloaded; here B catches up on new checkpoints meanwhile, both at the cap.
                    Map<String, Long> peaks = new java.util.TreeMap<>();
                    for (String member : perMember.get(perMember.size() - 1).keySet()) {
                        long peak = 0;
                        int from = 0;
                        for (int to = 0; to < perMember.size(); to++) {
                            while (sentAt.get(to) - sentAt.get(from) > 1000) {
                                from++;
                            }
                            long before = perMember.get(from).getOrDefault(member, 0L);
                            long after = perMember.get(to).getOrDefault(member, 0L);
                            peak = Math.max(peak, after - before);
                        }
                        peaks.put(member, peak);
                    }
                    DropIn.LOGGER.info("Self-test sync-extras: the most in any one second, per connection: {} bytes, with a cap of "
                            + "{} KB/s per connection", peaks, CAP_KBPS);
                    require(peaks.size() == 2, "expected the connections of B and C, saw " + peaks.keySet());
                    for (Map.Entry<String, Long> peak : peaks.entrySet()) {
                        // One second may hold a little more than the cap: a record that was already on its way.
                        require(peak.getValue() <= 2L * CAP_KBPS * 1024, "in one second " + peak.getValue() + " bytes were sent to "
                                + peak.getKey() + ", the cap is " + CAP_KBPS + " KB/s");
                    }
                    // A broken measurement (nothing sent per connection) must not pass: the busiest connection came near the cap.
                    long top = peaks.values().stream().mapToLong(Long::longValue).max().orElse(0);
                    require(top > CAP_KBPS * 1024 / 2, "no connection got near the cap in any one second (most: " + top + " bytes)");
                })
                .step("both upload speeds are measured and both copies are complete", 2 * PEER_TICKS, () -> {
                    HostSync sync = HostSync.current();
                    String b = ctx.readShared("b-ready").trim();
                    String c = ctx.readShared("c-ready").trim();
                    long seq = sync.seq();
                    if (sync.uploadKBps(b) == 0 || sync.uploadKBps(c) == 0 || sync.syncedByAll() < seq) {
                        return false;
                    }
                    HostSync.Standing standing = sync.standing(seq);
                    DropIn.LOGGER.info("Self-test sync-extras: measured upload of B (connected first, held back) {} KB/s, of C {} KB/s; "
                            + "the one who would take over at checkpoint {}: {}", sync.uploadKBps(b), sync.uploadKBps(c), seq,
                            c.equals(standing.successorUuid()) ? "C" : b.equals(standing.successorUuid()) ? "B" : standing.successorUuid());
                    require(sync.uploadKBps(b) < sync.uploadKBps(c) / 2, "B was not measured as clearly slower than C");
                    require(c.equals(standing.successorUuid()), "the successor is " + standing.successorUuid() + ", expected C " + c);
                    return true;
                })
                .action("tell B and C", () -> ctx.writeShared("a-done", "ok"))
                .step("B and C left", PEER_TICKS, () -> players(mc) == 1);
    }

    private static StepRunner member(Context ctx, String who) {
        Minecraft mc = ctx.mc();
        State state = new State();
        // B answers the host's upload test two seconds late, so it is measured as slow. C joins once B is done:
        // B is the longest connected, and without the speed it would be the one to take over.
        StepRunner runner = who.equals("b")
                ? joinB(ctx, state, Map.of("dropin.dev.speedDelayMs", "2000"))
                : joinB(ctx, state, Map.of(), "b-ready");
        return runner
                .step("copy complete", 6 * PEER_TICKS, copyComplete(mc, state))
                .step("the mod's files are in this copy", 2 * PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    Path root = folder(mc, state);
                    if (root == null || !Files.isRegularFile(root.resolve(CONFIG)) || !Files.isRegularFile(root.resolve(STATE))) {
                        return false;
                    }
                    require(Files.readString(root.resolve(CONFIG), StandardCharsets.UTF_8).equals(CONFIG_TEXT), CONFIG + " differs from the host's");
                    require(Files.readString(root.resolve(STATE), StandardCharsets.UTF_8).equals(STATE_TEXT), STATE + " differs from the host's");
                    DropIn.LOGGER.info("Self-test sync-extras: {} and {} are in the copy of {}, equal to the host's", CONFIG, STATE, who.toUpperCase());
                    return true;
                })
                .action("tell A", () -> ctx.writeShared(who + "-ready", mc.getSession().getProfile().getId().toString()))
                .step("A checked the cap and who would take over", 4 * PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("a-done");
                });
    }
}
