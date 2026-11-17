package dropin.core.sync;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Who takes over when the host quits (DESIGN 4.9, graceful): a fully synced peer, longest connected first.
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
     */
    public record Candidate(String uuid, long syncedSeq, long connectedAt) {
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
                .min(Comparator.comparingLong(Candidate::connectedAt).thenComparing(Candidate::uuid))
                .map(Candidate::uuid);
    }
}
