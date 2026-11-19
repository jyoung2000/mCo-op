package dropin.core.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who takes over when the host vanishes (DESIGN 4.9, crash). The host tells every member what it knows about
 * all of them ({@code PEERS}); after the host is gone each member puts that list in the same order and waits
 * its turn. Pure: no clock, no network.
 */
public final class Election {
    /** How long each peer in the order is given to appear as the new host before the next one takes over. */
    public static final long STEP_MS = 45_000;
    /** More sync connections than any session has; a longer list is refused. */
    public static final int MAX_PEERS = 1024;

    private Election() {
    }

    /**
     * One member as the host last described it.
     *
     * @param uuid       the member's player UUID
     * @param syncedSeq  the newest checkpoint the member reported as fully applied; 0 if it has no complete copy
     * @param uploadKBps the member's upload speed as the host last measured it (KB/s); 0 if not measured
     */
    public record Peer(String uuid, long syncedSeq, int uploadKBps) {
    }

    /**
     * The order in which the members take over: the newest complete copy first, then the fastest upload (in the
     * steps of {@link Successor#speedStep}), then the lowest UUID. A member without a complete copy cannot host
     * and is left out, and so is the host that vanished. A member listed twice counts once, with its best values.
     */
    public static List<String> order(List<Peer> peers, String hostUuid) {
        Map<String, Peer> best = new LinkedHashMap<>();
        for (Peer peer : peers) {
            if (peer.uuid() == null || peer.uuid().isEmpty() || peer.uuid().equals(hostUuid) || peer.syncedSeq() <= 0) {
                continue;
            }
            best.merge(peer.uuid(), peer, (a, b) -> new Peer(a.uuid(), Math.max(a.syncedSeq(), b.syncedSeq()),
                    Math.max(a.uploadKBps(), b.uploadKBps())));
        }
        List<Peer> sorted = new ArrayList<>(best.values());
        sorted.sort(Comparator.comparingLong((Peer peer) -> -peer.syncedSeq())
                .thenComparingInt(peer -> -Successor.speedStep(peer.uploadKBps()))
                .thenComparing(Peer::uuid));
        List<String> uuids = new ArrayList<>(sorted.size());
        sorted.forEach(peer -> uuids.add(peer.uuid()));
        return uuids;
    }

    /**
     * How long after the host was lost this member takes over if nobody before it in the order has appeared
     * as the new host: 0 for the first, {@link #STEP_MS} for the second, and so on.
     *
     * @return the wait in milliseconds, or -1 if this member is not in the order (it never takes over)
     */
    public static long turnAfterMs(List<String> order, String uuid) {
        int rank = order.indexOf(uuid);
        return rank < 0 ? -1 : rank * STEP_MS;
    }

    /** The body of a {@code PEERS} message: {i32 count, then for each peer str uuid, i64 syncedSeq, i32 uploadKBps}. */
    public static Wire.Out write(Wire.Out out, List<Peer> peers) {
        out.i32(peers.size());
        for (Peer peer : peers) {
            out.str(peer.uuid()).i64(peer.syncedSeq()).i32(peer.uploadKBps());
        }
        return out;
    }

    /** @throws IOException on a short or malformed message */
    public static List<Peer> read(Wire.In in) throws IOException {
        int count = in.i32();
        if (count < 0 || count > MAX_PEERS) {
            throw new IOException("Bad peer count " + count);
        }
        List<Peer> peers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            peers.add(new Peer(in.str(), in.i64(), in.i32()));
        }
        return List.copyOf(peers);
    }
}
