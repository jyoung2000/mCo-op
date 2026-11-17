package dropin.core.pkarr;

import java.io.IOException;
import java.util.Optional;

/** "Who is hosting this world right now?" (DESIGN 4.3). One instance per world. */
public interface Rendezvous {
    Optional<RendezvousRecord> lookup() throws IOException;

    void publish(RendezvousRecord record) throws IOException;
}
