package dropin.core.pkarr;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostRivalTest {
    private static final long NOW = 1_794_900_000L;
    /** This session claimed the world an hour ago. */
    private static final long START = NOW - 3600;

    private static Optional<RendezvousRecord> record(String st, String sess, long ts) {
        return Optional.of(RendezvousRecord.of(st, "other-uuid", "Sam", sess, ts).with(st, "sam.e4mc.link", "", ts));
    }

    private static Optional<RendezvousRecord> rival(Optional<RendezvousRecord> current) {
        return HostRival.rival(current, "mine", START, NOW);
    }

    @Test
    void anotherLiveSessionThatStartedLaterIsARival() {
        assertEquals("Sam", rival(record(RendezvousRecord.HOST, "theirs", NOW - 60)).orElseThrow().name());
        assertTrue(rival(record(RendezvousRecord.HOST, "theirs", NOW - HostRival.HOST_FRESH_SECONDS)).isPresent());
        assertTrue(rival(record(RendezvousRecord.STARTING, "theirs", NOW - 10)).isPresent());
    }

    @Test
    void ownOldOrFinishedRecordsAreNot() {
        assertTrue(rival(Optional.empty()).isEmpty());
        assertTrue(rival(record(RendezvousRecord.HOST, "mine", NOW - 60)).isEmpty());
        assertTrue(rival(record(RendezvousRecord.HOST, "theirs", NOW - HostRival.HOST_FRESH_SECONDS - 1)).isEmpty());
        assertTrue(rival(record(RendezvousRecord.STARTING, "theirs", NOW - HostClaim.STARTING_FRESH_SECONDS - 1)).isEmpty());
        assertTrue(rival(record(RendezvousRecord.IDLE, "theirs", NOW)).isEmpty());
        assertTrue(rival(record(RendezvousRecord.HANDOFF, "theirs", NOW)).isEmpty());
    }

    @Test
    void theRecordThisSessionReplacedIsNot() {
        // A crashed host's last "host" record, still fresh, still served by a slow relay after the takeover.
        long justStarted = NOW - 5;
        assertTrue(HostRival.rival(record(RendezvousRecord.HOST, "crashed", NOW - 120), "mine", justStarted, NOW).isEmpty());
        assertTrue(HostRival.rival(record(RendezvousRecord.HOST, "theirs", NOW - 1), "mine", justStarted, NOW).isPresent());
    }
}
