package dropin.core.sync;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Who takes over when the host quits (DESIGN 4.9, graceful): a fully synced peer, the fastest upload first,
 * then the longest connected.
 * Pure: the host passes in what it knows about its sync connections.
 */
public final class Successor {
    private Successor() {
    }

    /**
     * One sync connection as the host sees it.
     *
     * @param uuid        the peer's player UUID
     * @param syncedSeq   the newest checkpoint the peer reported as fully applied
     * @param connectedAt when the connection finished its handshake (milliseconds; smaller = connected longer)
     * @param uploadKBps  the peer's upload speed as the host last measured it (KB/s); 0 if not measured
     */
    public record Candidate(String uuid, long syncedSeq, long connectedAt, int uploadKBps) {
        /** A peer whose upload speed was not measured. */
        public Candidate(String uuid, long syncedSeq, long connectedAt) {
            this(uuid, syncedSeq, connectedAt, 0);
        }
    }

    /** Speeds from here on count as equal: fast enough to host, and a test on one network measures noise. */
    public static final int FAST_ENOUGH_KBPS = 12_800;

    /**
     * Upload speeds are compared in steps of a factor of two, so that two measurements of about the same
     * link do not decide. 0 (not measured, or only reachable through a relay) is the lowest step.
     */
    public static int speedStep(int uploadKBps) {
        return uploadKBps <= 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.min(uploadKBps, FAST_ENOUGH_KBPS));
    }

    /** How long to wait before sending {@code bytes} so that a connection stays under {@code rateKBps}; 0 or less = no cap. */
    public static long sendDelayMs(int bytes, int rateKBps) {
        return rateKBps <= 0 || bytes <= 0 ? 0 : bytes * 1000L / (rateKBps * 1024L);
    }

    /**
     * @param finalSeq the host's final checkpoint; a peer is fully synced when it applied this one
     * @param hostUuid the quitting host, never chosen
     * @return the UUID of the successor, or empty if nobody else has a full copy
     */
    public static Optional<String> choose(List<Candidate> peers, long finalSeq, String hostUuid) {
        return peers.stream()
                .filter(peer -> peer.uuid() != null && !peer.uuid().isEmpty() && !peer.uuid().equals(hostUuid))
                .filter(peer -> finalSeq > 0 && peer.syncedSeq() >= finalSeq)
                // Ties (the same instant) go to the lowest UUID, the order DESIGN 4.9 uses for failover.
                .min(Comparator.comparingInt((Candidate peer) -> -speedStep(peer.uploadKBps()))
                        .thenComparingLong(Candidate::connectedAt).thenComparing(Candidate::uuid))
                .map(Candidate::uuid);
    }
}
