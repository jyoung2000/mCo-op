package dropin.core.pkarr;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostClaimTest {
    /** Last writer wins, like a relay. */
    private static class MemoryRendezvous implements Rendezvous {
        volatile RendezvousRecord record;
        volatile boolean down;
        final List<String> published = new ArrayList<>();

        @Override
        public Optional<RendezvousRecord> lookup() throws IOException {
            if (down) {
                throw new IOException("relays down");
            }
            return Optional.ofNullable(record);
        }

        @Override
        public synchronized void publish(RendezvousRecord newRecord) throws IOException {
            if (down) {
                throw new IOException("relays down");
            }
            published.add(newRecord.st());
            record = newRecord;
        }
    }

    /** Virtual time: sleeping advances the clock and runs a hook, so tests are instant and deterministic. */
    private static final class FakeEnv implements HostClaim.Env {
        long now = 1_794_900_000_000L;
        final String sess;
        Predicate<RendezvousRecord> ping = record -> false;
        Runnable onSleep = () -> {
        };
        long slept;

        FakeEnv(String sess) {
            this.sess = sess;
        }

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void sleep(long millis) {
            now += millis;
            slept += millis;
            onSleep.run();
        }

        @Override
        public boolean ping(RendezvousRecord record) {
            return ping.test(record);
        }

        @Override
        public String newSession() {
            return sess;
        }
    }

    private static RendezvousRecord record(String st, String sess, long tsSeconds) {
        return RendezvousRecord.of(st, "other-uuid", "Sam", sess, tsSeconds).with(st, "sam.e4mc.link", "", tsSeconds);
    }

    @Test
    void nobodyThereMeansClaimThenHost() throws Exception {
        MemoryRendezvous rendezvous = new MemoryRendezvous();
        FakeEnv env = new FakeEnv("mine");
        HostClaim.Result result = HostClaim.claim(rendezvous, env, "my-uuid", "Alex");
        assertEquals(new HostClaim.Host("mine", true), result);
        assertEquals(List.of("starting"), rendezvous.published);
        assertEquals("my-uuid", rendezvous.record.host());
        assertEquals(HostClaim.SETTLE_MS, env.slept);
    }

    @Test
    void aLiveHostIsJoinedWithoutPublishing() throws Exception {
        MemoryRendezvous rendezvous = new MemoryRendezvous();
        rendezvous.record = record("host", "theirs", 1_794_800_000L);
        FakeEnv env = new FakeEnv("mine");
        env.ping = r -> true;
        HostClaim.Result result = HostClaim.claim(rendezvous, env, "my-uuid", "Alex");
        assertEquals(new HostClaim.Join(rendezvous.record), result);
        assertTrue(rendezvous.published.isEmpty());
    }

    @Test
    void aHostRecordThatDoesNotPingAndAnIdleRecordAreTakenOver() throws Exception {
        for (String state : new String[]{"host", "idle", "handoff"}) {
            MemoryRendezvous rendezvous = new MemoryRendezvous();
            rendezvous.record = record(state, "theirs", 1_794_800_000L);
            HostClaim.Result result = HostClaim.claim(rendezvous, new FakeEnv("mine"), "my-uuid", "Alex");
            assertEquals(new HostClaim.Host("mine", true), result, state);
        }
    }

    @Test
    void someoneElsesFreshClaimIsWaitedForAndJoined() throws Exception {
        MemoryRendezvous rendezvous = new MemoryRendezvous();
        FakeEnv env = new FakeEnv("mine");
        rendezvous.record = record("starting", "theirs", env.now / 1000 - 3);
        AtomicInteger polls = new AtomicInteger();
        env.onSleep = () -> {
            if (polls.incrementAndGet() == 4) {
                rendezvous.record = record("host", "theirs", env.now / 1000);
            }
        };
        env.ping = r -> r.isState("host");
        HostClaim.Result result = HostClaim.claim(rendezvous, env, "my-uuid", "Alex");
        assertInstanceOf(HostClaim.Join.class, result);
        assertEquals("theirs", ((HostClaim.Join) result).host().sess());
        assertTrue(rendezvous.published.isEmpty(), "we never claimed");
    }

    @Test
    void aClaimThatNeverComesUpIsTakenOverAfterTheWait() throws Exception {
        MemoryRendezvous rendezvous = new MemoryRendezvous();
        FakeEnv env = new FakeEnv("mine");
        rendezvous.record = record("starting", "theirs", env.now / 1000);
        HostClaim.Result result = HostClaim.claim(rendezvous, env, "my-uuid", "Alex");
        assertEquals(new HostClaim.Host("mine", true), result);
        assertTrue(env.slept >= HostClaim.WAIT_FOR_OTHER_MS, "waited " + env.slept);
    }

    @Test
    void anOldAbandonedClaimIsNotWaitedFor() throws Exception {
        MemoryRendezvous rendezvous = new MemoryRendezvous();
        FakeEnv env = new FakeEnv("mine");
        rendezvous.record = record("starting", "theirs", env.now / 1000 - 600);
        assertEquals(new HostClaim.Host("mine", true), HostClaim.claim(rendezvous, env, "my-uuid", "Alex"));
        assertEquals(HostClaim.SETTLE_MS, env.slept);
    }

    @Test
    void losingTheClaimDuringTheSettleWaitMeansJoiningTheWinner() throws Exception {
        MemoryRendezvous rendezvous = new MemoryRendezvous();
        FakeEnv env = new FakeEnv("mine");
        AtomicInteger sleeps = new AtomicInteger();
        env.onSleep = () -> {
            int n = sleeps.incrementAndGet();
            if (n == 1) {
                rendezvous.record = record("starting", "theirs", env.now / 1000);   // they overwrote our claim
            } else if (n == 3) {
                rendezvous.record = record("host", "theirs", env.now / 1000);
            }
        };
        env.ping = r -> r.isState("host");
        HostClaim.Result result = HostClaim.claim(rendezvous, env, "my-uuid", "Alex");
        assertInstanceOf(HostClaim.Join.class, result);
        assertEquals(List.of("starting"), rendezvous.published);
    }

    @Test
    void unreachableRendezvousMeansHostingUnannounced() throws Exception {
        MemoryRendezvous rendezvous = new MemoryRendezvous();
        rendezvous.down = true;
        assertEquals(new HostClaim.Host("mine", false), HostClaim.claim(rendezvous, new FakeEnv("mine"), "my-uuid", "Alex"));
    }

    @Test
    void aSlowPublishStartsOverAndFindsTheHostThatAppearedMeanwhile() throws Exception {
        FakeEnv env = new FakeEnv("mine");
        MemoryRendezvous rendezvous = new MemoryRendezvous() {
            @Override
            public synchronized void publish(RendezvousRecord newRecord) {
                env.now += 5_000;                                   // our PUT took five seconds
                record = record("host", "theirs", env.now / 1000);  // and someone finished claiming meanwhile
            }
        };
        env.ping = r -> r.isState("host");
        assertInstanceOf(HostClaim.Join.class, HostClaim.claim(rendezvous, env, "my-uuid", "Alex"));
    }

    /** Two players press Play at the same moment, many times over: exactly one hosts, the other joins it. */
    @Test
    void racingPlayersEndWithExactlyOneHost() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                MemoryRendezvous rendezvous = new MemoryRendezvous();
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<HostClaim.Result>> futures = new ArrayList<>();
                for (String player : new String[]{"A", "B"}) {
                    futures.add(pool.submit(() -> {
                        HostClaim.Env env = new HostClaim.Env() {
                            @Override
                            public long nowMillis() {
                                return System.currentTimeMillis();
                            }

                            @Override
                            public void sleep(long millis) throws InterruptedException {
                                Thread.sleep(Math.max(1, millis / 100));   // real waiting, 100 times faster
                            }

                            @Override
                            public boolean ping(RendezvousRecord record) {
                                return record.isState(RendezvousRecord.HOST);
                            }

                            @Override
                            public String newSession() {
                                return "sess-" + player;
                            }
                        };
                        start.await();
                        HostClaim.Result result = HostClaim.claim(rendezvous, env, "uuid-" + player, player);
                        if (result instanceof HostClaim.Host host) {
                            rendezvous.publish(RendezvousRecord.of(RendezvousRecord.HOST, "uuid-" + player, player,
                                    host.sess(), System.currentTimeMillis() / 1000));
                        }
                        return result;
                    }));
                }
                HostClaim.Result a = futures.get(0).get(30, TimeUnit.SECONDS);
                HostClaim.Result b = futures.get(1).get(30, TimeUnit.SECONDS);
                long hosts = List.of(a, b).stream().filter(r -> r instanceof HostClaim.Host).count();
                assertEquals(1, hosts, "round " + round + ": " + a + " / " + b);
                HostClaim.Result joiner = a instanceof HostClaim.Join ? a : b;
                HostClaim.Host host = (HostClaim.Host) (a instanceof HostClaim.Host ? a : b);
                assertEquals(host.sess(), ((HostClaim.Join) joiner).host().sess(), "round " + round);
                assertFalse(rendezvous.published.isEmpty());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
