package dropin.core.pkarr;

import java.io.IOException;
import java.util.Optional;

/**
 * The "claim before hosting" rule of DESIGN 4.3, as plain logic over a {@link Rendezvous}.
 *
 * <p>One reading of the design is made explicit here: when the record carries someone else's fresh
 * {@code starting} claim, that player cannot answer a ping yet, so we wait for them to come up
 * (at most {@link #WAIT_FOR_OTHER_MS}) instead of hosting next to them.
 */
public final class HostClaim {
    public static final long SETTLE_MS = 2_000;
    public static final long POLL_MS = 1_000;
    public static final long WAIT_FOR_OTHER_MS = 45_000;
    public static final long STARTING_FRESH_SECONDS = 60;
    /** If our own claim took this long to publish, someone may have claimed and settled meanwhile: start over. */
    public static final long SLOW_PUBLISH_MS = 1_500;

    private HostClaim() {
    }

    public interface Env {
        long nowMillis();

        void sleep(long millis) throws InterruptedException;

        /** True if a Minecraft status ping to this record's host answers (lan hint first, then addr). */
        boolean ping(RendezvousRecord record);

        /** 8 random bytes, hex. */
        String newSession();
    }

    public sealed interface Result permits Join, Host {
    }

    /** Someone is hosting: connect to them. */
    public record Join(RendezvousRecord host) implements Result {
    }

    /**
     * Nobody is hosting: open the local copy. {@code announced} is false when the rendezvous could not
     * be reached, in which case the caller keeps retrying the announce (DESIGN 4.3, offline rule).
     */
    public record Host(String sess, boolean announced) implements Result {
    }

    public static Result claim(Rendezvous rendezvous, Env env, String playerUuid, String playerName)
            throws InterruptedException {
        String sess = env.newSession();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                long lookedAt = env.nowMillis();
                Optional<RendezvousRecord> current = rendezvous.lookup();
                Optional<RendezvousRecord> live = liveOrAwaited(rendezvous, env, current, sess);
                if (live.isPresent()) {
                    return new Join(live.get());
                }
                rendezvous.publish(RendezvousRecord.of(RendezvousRecord.STARTING, playerUuid, playerName, sess,
                        env.nowMillis() / 1000));
                if (env.nowMillis() - lookedAt > SLOW_PUBLISH_MS) {
                    continue;
                }
                env.sleep(SETTLE_MS);
                live = liveOrAwaited(rendezvous, env, rendezvous.lookup(), sess);
                if (live.isPresent()) {
                    return new Join(live.get());
                }
                return new Host(sess, true);
            } catch (IOException e) {
                return new Host(sess, false);
            }
        }
        return new Host(sess, true);
    }

    /** A live host from this record, or from waiting on someone else's fresh claim; empty if we should host. */
    private static Optional<RendezvousRecord> liveOrAwaited(Rendezvous rendezvous, Env env,
                                                             Optional<RendezvousRecord> current, String mySess)
            throws InterruptedException, IOException {
        long deadline = env.nowMillis() + WAIT_FOR_OTHER_MS;
        while (true) {
            if (current.isEmpty() || current.get().sess().equals(mySess)) {
                return Optional.empty();
            }
            RendezvousRecord record = current.get();
            if (record.isState(RendezvousRecord.HOST)) {
                return env.ping(record) ? current : Optional.empty();
            }
            boolean othersFreshClaim = record.isState(RendezvousRecord.STARTING)
                    && env.nowMillis() / 1000 - record.ts() <= STARTING_FRESH_SECONDS;
            if (!othersFreshClaim || env.nowMillis() >= deadline) {
                return Optional.empty();
            }
            env.sleep(POLL_MS);
            current = rendezvous.lookup();
        }
    }
}
