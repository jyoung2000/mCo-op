package dropin.core.sync;

import dropin.core.crypto.WorldKeys;
import dropin.core.ledger.LedgerEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The policy, member and mod-file messages of the sync channel, host session against the probe session. */
class PolicySyncTest {
    private static final WorldKeys KEYS = WorldKeys.fromSeed(new byte[32]);
    private static final String SHA = "c".repeat(128);

    private static final class FakeHost implements SyncServerSession.Host {
        String[] policy;
        String members = "{\"members\":[\"host\"]}";
        final List<String[]> offered = new ArrayList<>();
        final List<String> membersOffered = new ArrayList<>();
        final Map<String, Path> mods = new HashMap<>();
        boolean bulk = true;
        long throttled;

        @Override
        public String worldId() {
            return KEYS.worldId();
        }

        @Override
        public byte[] auth() {
            return KEYS.auth();
        }

        @Override
        public byte[] enc() {
            return KEYS.enc();
        }

        @Override
        public Object lock() {
            return this;
        }

        @Override
        public LedgerEntry head() {
            return null;
        }

        @Override
        public List<LedgerEntry> ledgerAfter(long seq) {
            return List.of();
        }

        @Override
        public Map<String, String> top() {
            return Map.of();
        }

        @Override
        public Map<String, String> chunks(String regionKey) {
            return Map.of();
        }

        @Override
        public byte[] read(String key) {
            return null;
        }

        @Override
        public boolean bulkAllowed(SyncServerSession session) {
            return bulk;
        }

        @Override
        public void throttle(SyncServerSession session, int bytes) {
            throttled += bytes;
        }

        @Override
        public void peerSynced(SyncServerSession session, long seq) {
        }

        @Override
        public String[] policy() {
            return policy;
        }

        @Override
        public void policyOffered(SyncServerSession session, String policyJson, String manifestJson) {
            offered.add(new String[]{policyJson, manifestJson});
        }

        @Override
        public String members() {
            return members;
        }

        @Override
        public void membersOffered(SyncServerSession session, String json) {
            membersOffered.add(json);
        }

        @Override
        public Path modFile(String sha512) {
            return mods.get(sha512);
        }
    }

    private static final class FakeLocal implements PolicyClientSession.Local {
        byte[] auth = KEYS.auth();
        long policyVersion;
        String membersToOffer = "{\"members\":[\"me\"]}";
        String[] answer;
        final List<String[]> policies = new ArrayList<>();
        final List<String> members = new ArrayList<>();
        final List<Boolean> bulk = new ArrayList<>();
        final Map<String, ByteArrayOutputStream> files = new HashMap<>();
        final List<Integer> pieceSizes = new ArrayList<>();
        final List<String> finished = new ArrayList<>();
        final List<String> failedMods = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        @Override
        public String worldId() {
            return KEYS.worldId();
        }

        @Override
        public byte[] auth() {
            return auth;
        }

        @Override
        public byte[] enc() {
            return KEYS.enc();
        }

        @Override
        public String playerUuid() {
            return "member-uuid";
        }

        @Override
        public long policyVersion() {
            return policyVersion;
        }

        @Override
        public String membersToOffer() {
            return membersToOffer;
        }

        @Override
        public String[] policy(String policyJson, String manifestJson) {
            policies.add(new String[]{policyJson, manifestJson});
            return answer;
        }

        @Override
        public void members(String json) {
            members.add(json);
        }

        @Override
        public void bulk(boolean allowed) {
            bulk.add(allowed);
        }

        @Override
        public void modPiece(String sha512, byte[] piece, boolean last) {
            files.computeIfAbsent(sha512, k -> new ByteArrayOutputStream()).write(piece, 0, piece.length);
            pieceSizes.add(piece.length);
            if (last) {
                finished.add(sha512);
            }
        }

        @Override
        public void modFailed(String sha512, boolean refusedByPath) {
            failedMods.add(sha512 + (refusedByPath ? " refused" : " absent"));
        }

        @Override
        public void failed(String why) {
            failures.add(why);
        }
    }

    /** Frames are queued and delivered one at a time, as on a real connection. */
    private static final class Link {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final SyncServerSession server;
        final PolicyClientSession client;
        boolean open = true;

        Link(FakeHost host, FakeLocal local) {
            Transport clientSide = new Transport() {
                @Override
                public void send(byte[] frame) {
                    tasks.add(() -> server.onFrame(frame));
                }

                @Override
                public void close() {
                    cut();
                }
            };
            Transport serverSide = new Transport() {
                @Override
                public void send(byte[] frame) {
                    tasks.add(() -> client.onFrame(frame));
                }

                @Override
                public void close() {
                    cut();
                }
            };
            this.server = new SyncServerSession(host, serverSide, new SecureRandom());
            this.client = new PolicyClientSession(local, clientSide, new SecureRandom());
        }

        void cut() {
            if (open) {
                open = false;
                tasks.clear();
                server.onClosed();
                client.onClosed();
            }
        }

        Link run() {
            while (open && !tasks.isEmpty()) {
                tasks.poll().run();
            }
            return this;
        }

        Link start() {
            client.start();
            return run();
        }
    }

    @Test
    void theProbeLearnsPolicyManifestMembersAndThePath() {
        FakeHost host = new FakeHost();
        host.policy = new String[]{"{policy}", "{manifest}"};
        FakeLocal local = new FakeLocal();
        Link link = new Link(host, local).start();

        assertEquals(1, local.policies.size());
        assertArrayEquals(new String[]{"{policy}", "{manifest}"}, local.policies.get(0));
        assertEquals(List.of("{\"members\":[\"me\"]}"), host.membersOffered);
        assertEquals(List.of("{\"members\":[\"host\"]}"), local.members);
        assertEquals(List.of(true), local.bulk);
        assertTrue(host.offered.isEmpty());
        assertTrue(local.failures.isEmpty());

        // The host adopts a newer policy later and pushes it.
        host.policy = new String[]{"{policy2}", "{manifest2}"};
        link.server.sendPolicy();
        link.run();
        assertEquals("{policy2}", local.policies.get(1)[0]);

        link.client.close();
        assertTrue(link.client.closed());
        assertTrue(local.failures.isEmpty());
    }

    @Test
    void aHostWithoutAPolicyStillAnswersAndTakesANewerOne() {
        FakeHost host = new FakeHost();
        FakeLocal local = new FakeLocal();
        local.policyVersion = 4;
        local.answer = new String[]{"{mine}", "{my manifest}"};
        local.membersToOffer = null;
        new Link(host, local).start();

        assertArrayEquals(new String[]{"", ""}, local.policies.get(0));
        assertEquals(1, host.offered.size());
        assertArrayEquals(new String[]{"{mine}", "{my manifest}"}, host.offered.get(0));
        assertTrue(host.membersOffered.isEmpty());
        assertTrue(local.members.isEmpty());
    }

    @Test
    void aModFileOfTheListArrivesInPieces(@TempDir Path dir) throws IOException {
        byte[] bytes = new byte[Wire.PIECE * 2 + 1234];
        new java.util.Random(5).nextBytes(bytes);
        Path jar = dir.resolve("big.jar");
        Files.write(jar, bytes);
        Path empty = dir.resolve("empty.jar");
        Files.write(empty, new byte[0]);
        FakeHost host = new FakeHost();
        host.mods.put(SHA, jar);
        host.mods.put("e".repeat(128), empty);
        FakeLocal local = new FakeLocal();
        Link link = new Link(host, local).start();

        link.client.requestMod(SHA);
        link.client.requestMod("e".repeat(128));
        link.run();
        assertEquals(List.of(SHA, "e".repeat(128)), local.finished);
        assertArrayEquals(bytes, local.files.get(SHA).toByteArray());
        assertEquals(0, local.files.get("e".repeat(128)).size());
        assertEquals(List.of(Wire.PIECE, Wire.PIECE, 1234, 0), local.pieceSizes);
        assertEquals(bytes.length, link.server.modBytes());
        assertEquals(bytes.length, host.throttled);
        assertTrue(local.failedMods.isEmpty());
    }

    @Test
    void aFileThatIsNotInTheListIsNeverServed() {
        FakeHost host = new FakeHost();
        FakeLocal local = new FakeLocal();
        Link link = new Link(host, local).start();
        link.client.requestMod(SHA);
        link.run();
        assertEquals(List.of(SHA + " absent"), local.failedMods);
        assertTrue(local.files.isEmpty());
        assertEquals(0, link.server.modBytes());
    }

    @Test
    void modFilesDoNotGoOverAPathThatMayNotCarryBulkData(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("mod.jar");
        Files.write(jar, new byte[5000]);
        FakeHost host = new FakeHost();
        host.mods.put(SHA, jar);
        host.bulk = false;
        FakeLocal local = new FakeLocal();
        Link link = new Link(host, local).start();
        assertEquals(List.of(false), local.bulk);
        // Policy and members are small and signed or harmless: they are exchanged on any path.
        assertEquals(1, local.policies.size());

        link.client.requestMod(SHA);
        link.run();
        assertEquals(List.of(SHA + " refused"), local.failedMods);
        assertTrue(local.files.isEmpty());
        assertEquals(0, link.server.modBytes());

        host.bulk = true;
        link.server.refresh();
        link.run();
        assertEquals(List.of(false, true), local.bulk);
        link.client.requestMod(SHA);
        link.run();
        assertEquals(List.of(SHA), local.finished);
        assertEquals(5000, local.files.get(SHA).size());
    }

    @Test
    void aPeerWithoutTheWorldKeyLearnsNothing() {
        FakeHost host = new FakeHost();
        host.policy = new String[]{"{policy}", "{manifest}"};
        FakeLocal local = new FakeLocal();
        local.auth = new byte[32];
        Link link = new Link(host, local).start();
        assertFalse(link.open);
        assertTrue(local.policies.isEmpty());
        assertTrue(local.members.isEmpty());
        assertEquals(1, local.failures.size());
        assertNotNull(local.failures.get(0));
        assertNull(host.policy == null ? "x" : null);
    }
}
