package dropin.core.pkarr;

import java.io.IOException;
import java.util.Optional;

/** Reads and publishes signed packets. The real one talks to pkarr relays over HTTP. */
public interface PkarrClient {
    /** The newest valid packet for the key; empty if a relay answered that there is none. */
    Optional<SignedPacket> get(byte[] publicKey) throws IOException;

    void put(SignedPacket packet) throws IOException;

    /** A relay asked us to slow down (HTTP 429). */
    final class RateLimitedException extends IOException {
        public RateLimitedException(String message) {
            super(message);
        }
    }

    /** Every relay that answered already has a newer packet (HTTP 409). */
    final class StaleRecordException extends IOException {
        public StaleRecordException(String message) {
            super(message);
        }
    }
}
