package dropin.core.pkarr;

import dropin.core.crypto.WorldKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Opt-in: publishes one encrypted throwaway record to the real public pkarr relays and reads it back.
 * This is the check that the wire format is what real relays accept. Run with
 * {@code ./gradlew :core:test --tests "*RealRelayOnlineTest" -Pdropin.online=true}.
 */
@EnabledIfSystemProperty(named = "dropin.online", matches = "true")
class RealRelayOnlineTest {
    @Test
    void aRecordPublishedToTheRealRelaysCanBeReadBack() throws Exception {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        WorldKeys keys = WorldKeys.fromSeed(seed);
        HttpPkarrClient client = new HttpPkarrClient(HttpPkarrClient.DEFAULT_RELAYS, Duration.ofSeconds(15));

        assertEquals(Optional.empty(), new PkarrRendezvous(keys, client).lookup(), "a fresh key has no record");

        RendezvousRecord record = new RendezvousRecord(1, RendezvousRecord.HOST, "selftest.invalid", "",
                "00000000-0000-0000-0000-000000000000", "SelfTest", "0011223344556677", "", 0,
                System.currentTimeMillis() / 1000, "");
        new PkarrRendezvous(keys, client).publish(record);

        Optional<RendezvousRecord> read = Optional.empty();
        for (int i = 0; i < 10 && read.isEmpty(); i++) {
            read = new PkarrRendezvous(keys, client).lookup();
            if (read.isEmpty()) {
                Thread.sleep(1000);
            }
        }
        assertEquals(Optional.of(record), read);
        System.out.println("pkarr online check: published and read back at " + keys.worldId());
    }
}
