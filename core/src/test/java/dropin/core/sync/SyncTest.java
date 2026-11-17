package dropin.core.sync;

import dropin.core.crypto.WorldKeys;
import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncTest {
    private static final WorldKeys KEYS = WorldKeys.fromSeed(new byte[32]);
    private static final byte[] OTHER_SEED = new byte[32];

    static {
        OTHER_SEED[0] = 1;
    }

    /** A hosted world held in memory. */
    private static final class FakeHost implements SyncServerSession.Host {
        final Map<String, byte[]> records = new TreeMap<>();
        final Manifest manifest = new Manifest();
        final Ledger ledger = new Ledger(KEYS.auth(), null);
        final List<SyncServerSession> sessions = new ArrayList<>();
        final List<Long> acks = new ArrayList<>();
        boolean bulk = true;
        Function<String, byte[]> corrupt = key -> null;
        int reads;

        /** Changes records (null value deletes) and takes a checkpoint. */
        LedgerEntry checkpoint(Map<String, byte[]> changes) throws IOException {
            synchronized (this) {
                Map<String, String> changed = new LinkedHashMap<>();
                changes.forEach((key, bytes) -> {
                    if (bytes == null) {
                        records.remove(key);
                        manifest.remove(key);
                        changed.put(key, "");
                    } else {
                        records.put(key, bytes);
                        manifest.put(key, Manifest.hash(bytes));
                        changed.put(key, Manifest.hash(bytes));
                    }
                });
                LedgerEntry entry = LedgerEntry.create(KEYS.auth(), ledger.headSeq() + 1, ledger.head(), "host-uuid",
                        "Host", "0011223344556677", 1_800_000_000L + ledger.headSeq(), manifest.root(), changed.size());
                ledger.append(entry);
                for (SyncServerSession session : sessions) {
                    session.checkpoint(entry, changed);
                }
                return entry;
            }
        }

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
            return ledger.last();
        }

        @Override
        public List<LedgerEntry> ledgerAfter(long seq) {
            return ledger.entriesAfter(seq);
        }

        @Override
        public Map<String, String> top() {
            return manifest.top();
        }

        @Override
        public Map<String, String> chunks(String regionKey) {
            return manifest.chunks(regionKey);
        }

        @Override
        public byte[] read(String key) {
            reads++;
            byte[] bad = corrupt.apply(key);
            return bad != null ? bad : records.get(key);
        }

        @Override
        public boolean bulkAllowed(SyncServerSession session) {
            return bulk;
        }

        @Override
        public void throttle(SyncServerSession session, int bytes) {
        }

        @Override
        public void peerSynced(SyncServerSession session, long seq) {
            acks.add(seq);
        }
    }

    /** A member's copy held in memory. */
    private static final class FakeLocal implements SyncClientSession.Local {
        final WorldKeys keys;
        final Map<String, byte[]> records = new TreeMap<>();
        final List<String> order = new ArrayList<>();
        final Manifest manifest = new Manifest();
        final Ledger ledger;
        final List<SyncClientSession.Status> statuses = new ArrayList<>();
        LedgerEntry committed;
        int flushes;

        FakeLocal(WorldKeys keys) {
            this.keys = keys;
            this.ledger = new Ledger(keys.auth(), null);
        }

        @Override
        public String worldId() {
            return KEYS.worldId();
        }

        @Override
        public byte[] auth() {
            return keys.auth();
        }

        @Override
        public byte[] enc() {
            return keys.enc();
        }

        @Override
        public String playerUuid() {
            return "member-uuid";
        }

        @Override
        public Ledger ledger() {
            return ledger;
        }

        @Override
        public Manifest manifest() {
            return manifest;
        }

        @Override
        public void apply(String key, byte[] bytes) {
            records.put(key, bytes);
            order.add(key);
        }

        @Override
        public void delete(String key) {
            records.remove(key);
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void committed(LedgerEntry head) {
            committed = head;
        }

        @Override
        public void status(SyncClientSession.Status status) {
            statuses.add(status);
        }

        SyncClientSession.Status status() {
            return statuses.get(statuses.size() - 1);
        }
    }

    /** One connection between a client and a server session, delivered in order on the test thread. */
    private static final class Link {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final SyncServerSession server;
        final SyncClientSession client;
        boolean open = true;
        /** Changes a frame on its way to the client; returning null cuts the connection. */
        Function<byte[], byte[]> toClient = frame -> frame;
        int recordSizedFrames;

        Link(FakeHost host, FakeLocal local) {
            Transport clientSide = new Transport() {
                @Override
                public void send(byte[] frame) {
                    tasks.add(() -> server().onFrame(frame));
                }

                @Override
                public void close() {
                    cut();
                }
            };
            Transport serverSide = new Transport() {
                @Override
                public void send(byte[] frame) {
                    tasks.add(() -> {
                        byte[] changed = toClient.apply(frame);
                        if (changed == null) {
                            cut();
                        } else {
                            client().onFrame(changed);
                        }
                    });
                }

                @Override
                public void close() {
                    cut();
                }
            };
            this.server = new SyncServerSession(host, serverSide, new SecureRandom());
            this.client = new SyncClientSession(local, clientSide, new SecureRandom());
            host.sessions.add(server);
        }

        SyncServerSession server() {
            return server;
        }

        SyncClientSession client() {
            return client;
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

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 200 chunk records in two dimensions and three kinds, plus a few files. */
    private static FakeHost hostWithWorld() throws IOException {
        FakeHost host = new FakeHost();
        Map<String, byte[]> world = new LinkedHashMap<>();
        for (int x = -6; x < 6; x++) {
            for (int z = -6; z < 6; z++) {
                world.put(RecordKey.chunk("minecraft:overworld", "region", x * 7, z * 7), bytes("chunk " + x + " " + z));
            }
        }
        for (int i = 0; i < 40; i++) {
            world.put(RecordKey.chunk("minecraft:overworld", "entities", i, -i), bytes("entities " + i));
        }
        for (int i = 0; i < 16; i++) {
            world.put(RecordKey.chunk("minecraft:the_nether", "poi", i, i), bytes("poi " + i));
        }
        world.put(RecordKey.file("level.dat"), bytes("level"));
        world.put(RecordKey.file("players/data/member.dat"), bytes("player"));
        world.put(RecordKey.file("data/big.dat"), new byte[Wire.PIECE * 2 + 1234]);
        host.checkpoint(world);
        return host;
    }

    private static void assertSameWorld(FakeHost host, FakeLocal local) {
        assertEquals(host.records.keySet(), local.records.keySet());
        host.records.forEach((key, value) -> assertArrayEquals(value, local.records.get(key), key));
        assertEquals(host.manifest.root(), local.manifest.root());
        assertEquals(host.ledger.hashes(), local.ledger.hashes());
        assertEquals(host.ledger.last(), local.committed);
    }

    @Test
    void fullSyncOverAnInMemoryTransport() throws IOException {
        FakeHost host = hostWithWorld();
        FakeLocal local = new FakeLocal(KEYS);
        Link link = new Link(host, local).start();

        assertEquals(SyncClientSession.Phase.LIVE, link.client.phase());
        assertSameWorld(host, local);
        assertEquals(host.records.size(), link.client.applied());
        assertEquals(0, link.client.rejected());
        assertEquals("f|level.dat", local.order.get(0));
        assertEquals("member-uuid", link.server.playerUuid());
        assertEquals(List.of(1L), host.acks);
        assertEquals(host.records.size(), local.status().have());
        assertEquals(host.records.size(), local.status().total());
        assertTrue(local.flushes > 0);
        // The multi-piece record took three frames.
        assertEquals(host.records.size() + 2, link.server.recordFrames());
    }

    @Test
    void liveCheckpointsFollow() throws IOException {
        FakeHost host = hostWithWorld();
        FakeLocal local = new FakeLocal(KEYS);
        Link link = new Link(host, local).start();
        long before = link.client.applied();

        Map<String, byte[]> changes = new LinkedHashMap<>();
        changes.put(RecordKey.chunk("minecraft:overworld", "region", 0, 0), bytes("a block was placed"));
        changes.put(RecordKey.chunk("minecraft:overworld", "region", 500, 500), bytes("new chunk"));
        changes.put(RecordKey.file("level.dat"), bytes("level 2"));
        changes.put(RecordKey.chunk("minecraft:the_nether", "poi", 3, 3), null);
        host.checkpoint(changes);
        link.run();

        assertEquals(SyncClientSession.Phase.LIVE, link.client.phase());
        assertSameWorld(host, local);
        assertEquals(before + 3, link.client.applied());
        assertEquals(2, local.ledger.size());
        assertEquals(List.of(1L, 2L), host.acks);

        // A checkpoint that changes nothing still advances the ledger.
        host.checkpoint(Map.of());
        link.run();
        assertEquals(3, local.ledger.size());
        assertSameWorld(host, local);
    }

    @Test
    void transferCutMidWayResumes() throws IOException {
        FakeHost host = hostWithWorld();
        FakeLocal local = new FakeLocal(KEYS);
        Link first = new Link(host, local);
        int[] big = {0};
        first.toClient = frame -> frame.length > 40 && ++big[0] > 90 ? null : frame;
        first.start();

        assertFalse(first.open);
        assertNotEquals(SyncClientSession.Phase.LIVE, first.client.phase());
        long firstApplied = first.client.applied();
        assertTrue(firstApplied > 0 && firstApplied < host.records.size(), "applied " + firstApplied);
        // Nothing is committed from a partial transfer.
        assertEquals(0, local.ledger.size());
        assertNull(local.committed);
        assertTrue(local.flushes > 0);

        host.sessions.clear();
        int readsBefore = host.reads;
        Link second = new Link(host, local).start();
        assertEquals(SyncClientSession.Phase.LIVE, second.client.phase());
        assertSameWorld(host, local);
        // Only what was missing is downloaded again.
        assertEquals(host.records.size() - firstApplied, second.client.applied());
        assertEquals(host.records.size() - firstApplied, host.reads - readsBefore);
    }

    @Test
    void tamperedRecordIsRejectedAndNeverCommitted() throws IOException {
        FakeHost host = hostWithWorld();
        String victim = RecordKey.chunk("minecraft:overworld", "region", 7, 7);
        host.corrupt = key -> key.equals(victim) ? bytes("tampered") : null;
        FakeLocal local = new FakeLocal(KEYS);
        Link link = new Link(host, local).start();

        assertEquals(SyncClientSession.Phase.DOWNLOADING, link.client.phase());
        assertEquals(1, link.client.rejected());
        assertFalse(local.records.containsKey(victim));
        assertNull(local.manifest.get(victim));
        assertEquals(0, local.ledger.size());
        assertNull(local.committed);
        assertTrue(host.acks.isEmpty());

        // Once the host serves the real record again, the next checkpoint heals the copy.
        host.corrupt = key -> null;
        host.checkpoint(Map.of());
        link.run();
        assertEquals(SyncClientSession.Phase.LIVE, link.client.phase());
        assertSameWorld(host, local);
    }

    @Test
    void tamperedFrameEndsTheSession() throws IOException {
        FakeHost host = hostWithWorld();
        FakeLocal local = new FakeLocal(KEYS);
        Link link = new Link(host, local);
        int[] count = {0};
        link.toClient = frame -> {
            if (++count[0] == 30) {
                byte[] flipped = frame.clone();
                flipped[flipped.length / 2] ^= 1;
                return flipped;
            }
            return frame;
        };
        link.start();

        assertEquals(SyncClientSession.Phase.FAILED, link.client.phase());
        assertFalse(link.open);
        assertEquals(0, local.ledger.size());
        assertNull(local.committed);
    }

    @Test
    void forgedCheckpointIsRejected() throws IOException {
        FakeHost host = hostWithWorld();
        FakeLocal local = new FakeLocal(KEYS);
        Link link = new Link(host, local).start();
        assertEquals(1, local.ledger.size());

        WorldKeys other = WorldKeys.fromSeed(OTHER_SEED);
        LedgerEntry forged = LedgerEntry.create(other.auth(), 2, host.ledger.head(), "x", "x", "x", 1, host.manifest.root(), 1);
        link.server.checkpoint(forged, Map.of(RecordKey.file("level.dat"), Manifest.hash(bytes("evil"))));
        link.run();

        assertEquals(SyncClientSession.Phase.FAILED, link.client.phase());
        assertEquals(1, local.ledger.size());
        assertArrayEquals(bytes("level"), local.records.get("f|level.dat"));
    }

    @Test
    void peerWithoutTheWorldKeyGetsNothing() throws IOException {
        FakeHost host = hostWithWorld();
        FakeLocal local = new FakeLocal(WorldKeys.fromSeed(OTHER_SEED));
        Link link = new Link(host, local).start();

        assertFalse(link.server.ready());
        assertFalse(link.open);
        assertNotEquals(SyncClientSession.Phase.LIVE, link.client.phase());
        assertTrue(local.records.isEmpty());
        assertEquals(0, host.reads);
    }

    @Test
    void noBulkDataOnARelayedPathUntilAllowed() throws IOException {
        FakeHost host = hostWithWorld();
        host.bulk = false;
        FakeLocal local = new FakeLocal(KEYS);
        Link link = new Link(host, local).start();

        assertEquals(SyncClientSession.Phase.WAITING_DIRECT, link.client.phase());
        assertEquals(0, link.server.recordFrames());
        assertEquals(0, host.reads);
        assertTrue(local.records.isEmpty());

        // Checkpoints still arrive as metadata only.
        host.checkpoint(Map.of(RecordKey.file("level.dat"), bytes("level 2")));
        link.run();
        assertEquals(SyncClientSession.Phase.WAITING_DIRECT, link.client.phase());
        assertEquals(0, link.server.recordFrames());
        assertEquals(0, local.ledger.size());

        host.bulk = true;
        link.server.refresh();
        link.run();
        assertEquals(SyncClientSession.Phase.LIVE, link.client.phase());
        assertSameWorld(host, local);

        // And it stops again when the path stops being direct.
        host.bulk = false;
        link.server.refresh();
        long frames = link.server.recordFrames();
        host.checkpoint(Map.of(RecordKey.file("level.dat"), bytes("level 3")));
        link.run();
        assertEquals(SyncClientSession.Phase.WAITING_DIRECT, link.client.phase());
        assertEquals(frames, link.server.recordFrames());
        assertArrayEquals(bytes("level 2"), local.records.get("f|level.dat"));
    }

    @Test
    void divergedCopyIsLeftAlone() throws IOException {
        FakeHost host = hostWithWorld();
        FakeLocal local = new FakeLocal(KEYS);
        local.records.put("f|level.dat", bytes("mine"));
        local.manifest.put("f|level.dat", Manifest.hash(bytes("mine")));
        local.ledger.append(LedgerEntry.create(KEYS.auth(), 1, "", "member-uuid", "Member", "ffffffffffffffff", 5,
                local.manifest.root(), 1));
        Link link = new Link(host, local).start();

        assertEquals(SyncClientSession.Phase.DIVERGED, link.client.phase());
        assertEquals(1, local.records.size());
        assertArrayEquals(bytes("mine"), local.records.get("f|level.dat"));
        assertEquals(1, local.ledger.size());
        assertEquals(0, host.reads);
    }

    @Test
    void recordsOutsideThePolicyAreRefused() throws IOException {
        FakeHost host = hostWithWorld();
        host.checkpoint(Map.of("f|../../outside.txt", bytes("escape")));
        FakeLocal local = new FakeLocal(KEYS);
        Link link = new Link(host, local).start();

        assertEquals(SyncClientSession.Phase.FAILED, link.client.phase());
        assertFalse(local.records.containsKey("f|../../outside.txt"));
        assertEquals(0, local.ledger.size());
    }

    @Test
    void decoderCutsAStreamIntoFrames() throws IOException {
        byte[] a = Wire.frame(bytes("first"));
        byte[] b = Wire.frame(new byte[0]);
        byte[] c = Wire.frame(new byte[70_000]);
        byte[] stream = new byte[a.length + b.length + c.length];
        System.arraycopy(a, 0, stream, 0, a.length);
        System.arraycopy(b, 0, stream, a.length, b.length);
        System.arraycopy(c, 0, stream, a.length + b.length, c.length);

        for (int step : new int[]{1, 3, 4096, stream.length}) {
            Wire.Decoder decoder = new Wire.Decoder();
            List<byte[]> frames = new ArrayList<>();
            for (int offset = 0; offset < stream.length; offset += step) {
                decoder.feed(stream, offset, Math.min(step, stream.length - offset), frames::add);
            }
            assertEquals(3, frames.size(), "step " + step);
            assertArrayEquals(bytes("first"), frames.get(0));
            assertEquals(0, frames.get(1).length);
            assertEquals(70_000, frames.get(2).length);
        }
        byte[] huge = {0x7F, 0, 0, 0};
        assertThrows(IOException.class, () -> new Wire.Decoder().feed(huge, 0, 4, frame -> { }));
        assertThrows(IllegalArgumentException.class, () -> Wire.frame(new byte[Wire.MAX_FRAME + 1]));
    }

    @Test
    void magicIsDecidedAsSoonAsOneByteDiffers() {
        byte[] magic = Wire.magic();
        assertEquals(8, magic.length);
        assertEquals("DROPIN", new String(magic, 0, 6, StandardCharsets.US_ASCII));
        assertEquals(1, Wire.matchMagic(magic, 8));
        assertEquals(0, Wire.matchMagic(magic, 1));
        assertEquals(0, Wire.matchMagic(magic, 7));
        // A vanilla handshake: a length byte, then packet id 0x00. The second byte already differs.
        assertEquals(-1, Wire.matchMagic(new byte[]{0x44, 0x00}, 2));
        assertEquals(-1, Wire.matchMagic(new byte[]{0x10, 0x00, (byte) 0xFE}, 3));
        // The legacy server-list ping starts with 0xFE.
        assertEquals(-1, Wire.matchMagic(new byte[]{(byte) 0xFE}, 1));
    }

    @Test
    void directPathIsReadFromTheConnectionInfo() {
        assertTrue(PathInfo.direct("path 0 active false rtt 40.1 local relay https://r.example/ remote relay https://r.example/\n"
                + "path 1 active true rtt 3.2 local ip v4 priv remote ip v4 glob\n"));
        assertTrue(PathInfo.direct("path 0 active true rtt 1 local ip v6 glob remote ip v6 glob\n"));
        assertFalse(PathInfo.direct("path 0 active true rtt 40.1 local relay https://r.example/ remote relay https://r.example/\n"
                + "path 1 active false rtt 3.2 local ip v4 priv remote ip v4 glob\n"));
        assertFalse(PathInfo.direct("path 0 active true rtt 1 local custom remote custom\n"));
        assertFalse(PathInfo.direct(""));
        assertFalse(PathInfo.direct(null));
        assertFalse(PathInfo.direct("io.netty.channel.socket.nio.NioSocketChannel"));
    }
}
