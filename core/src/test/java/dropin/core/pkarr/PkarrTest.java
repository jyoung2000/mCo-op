package dropin.core.pkarr;

import com.sun.net.httpserver.HttpServer;
import dropin.core.crypto.Hkdf;
import dropin.core.crypto.WorldKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PkarrTest {
    private static final WorldKeys KEYS = WorldKeys.fromSeed(Hkdf.sha256(new byte[]{1}));

    private static RendezvousRecord hostRecord() {
        return new RendezvousRecord(1, RendezvousRecord.HOST, "amenity-guide.na.e4mc.link", "192.168.1.20:25565",
                "069a79f4-44e9-4726-a5be-fca90e38aaf5", "Alex", "00112233aabbccdd", "", 0, 1794900000L, "");
    }

    // ---- players in the record (DESIGN 4.15) ---------------------------------------------------

    @Test
    void theRecordNamesWhoIsOnlineAndStillFitsOnePacket() throws IOException {
        String host = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
        List<String> uuids = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            uuids.add(new java.util.UUID(0x1000_0000_0000_0000L + i * 0x0101_0101_0101L, i).toString());
        }
        uuids.add(host);
        uuids.add("not a uuid");
        // The longest fields a session can have.
        RendezvousRecord record = new RendezvousRecord(1, RendezvousRecord.HOST, "a-very-long-relay-name.southamerica.e4mc.link",
                "255.255.255.255:65535", host, "SixteenCharsName", "00112233aabbccdd", "ab".repeat(16), 9_999_999L, 1794900000L, "",
                9_999L, "cd".repeat(32)).withPlayers(uuids);
        assertEquals(RendezvousRecord.PLAYERS_MAX * RendezvousRecord.ID_CHARS, record.pl().length());
        assertTrue(record.isOnline(host));
        assertTrue(record.isOnline(uuids.get(0)));
        assertTrue(!record.isOnline(uuids.get(19)), "players beyond the limit are not listed");
        assertTrue(!record.isOnline("not a uuid") && !record.isOnline("11111111-2222-3333-4444-555555555555"));
        assertTrue(!record.with(RendezvousRecord.STARTING, "", "", 5).isOnline(host), "only a hosted session has players");
        assertEquals(record, RendezvousRecord.fromJson(record.toJson()));
        assertEquals("", record.handoffTo(uuids.get(0), 6).pl());
        assertTrue(!hostRecord().toJson().contains("\"pl\""), "no player list when nobody else is connected");
        assertThrows(IllegalArgumentException.class,
                () -> RendezvousRecord.fromJson(hostRecord().toJson().replace("\"v\":1", "\"v\":1,\"pl\":\"abc\"")));

        MemoryClient client = new MemoryClient();
        new PkarrRendezvous(KEYS, client, () -> 1_000_000).publish(record);
        SignedPacket packet = client.stored.values().iterator().next();
        assertTrue(packet.dns().length <= SignedPacket.MAX_DNS, "DNS packet is " + packet.dns().length + " bytes");
        assertEquals(Optional.of(record), new PkarrRendezvous(KEYS, client, () -> 1_000_000).lookup());
    }

    // ---- DNS ---------------------------------------------------------------------------------

    @Test
    void txtRoundTripsShortAndLongText() {
        for (int length : new int[]{0, 1, 255, 256, 600}) {
            String text = "x".repeat(length);
            byte[] packet = DnsPacket.encodeTxt(List.of("_dropin", KEYS.worldId()), 300, text);
            assertEquals(text, DnsPacket.findTxt(packet, "_dropin"), "length " + length);
            assertEquals(text, DnsPacket.findTxt(packet, "_DROPIN"));
            assertNull(DnsPacket.findTxt(packet, "_other"));
        }
    }

    @Test
    void findsTheRecordBehindAnotherRecordAndACompressedName() {
        // header: 2 answers. Answer 1: A record for "a.key". Answer 2: TXT for "_dropin" + pointer to "key" at offset 14.
        byte[] packet = {
                0, 0, (byte) 0x84, 0, 0, 0, 0, 2, 0, 0, 0, 0,
                1, 'a', 3, 'k', 'e', 'y', 0, 0, 1, 0, 1, 0, 0, 1, 44, 0, 4, 1, 2, 3, 4,
                7, '_', 'd', 'r', 'o', 'p', 'i', 'n', (byte) 0xC0, 14, 0, 16, 0, 1, 0, 0, 1, 44, 0, 6, 2, 'h', 'i', 2, '!', '!'};
        assertEquals("hi!!", DnsPacket.findTxt(packet, "_dropin"));
    }

    @Test
    void malformedPacketsAreRefusedNotCrashedOn() {
        byte[] good = DnsPacket.encodeTxt(List.of("_dropin", "key"), 300, "hello");
        for (int cut = 0; cut < good.length; cut++) {
            byte[] truncated = java.util.Arrays.copyOf(good, cut);
            assertThrows(IllegalArgumentException.class, () -> DnsPacket.findTxt(truncated, "_dropin"), "cut " + cut);
        }
        byte[] loop = {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, (byte) 0xC0, 12, 0, 16, 0, 1, 0, 0, 0, 0, 0, 0};
        assertThrows(IllegalArgumentException.class, () -> DnsPacket.findTxt(loop, "_dropin"));
    }

    // ---- signed packet -----------------------------------------------------------------------

    @Test
    void signableIsTheBep44Form() {
        assertEquals("3:seqi1794900000000000e1:v3:abc",
                new String(SignedPacket.signable(1794900000000000L, "abc".getBytes(StandardCharsets.US_ASCII)),
                        StandardCharsets.US_ASCII));
    }

    @Test
    void relayPayloadRoundTripsAndRejectsTamperingAndWrongKey() {
        byte[] dns = DnsPacket.encodeTxt(List.of("_dropin", KEYS.worldId()), 300, "hello");
        SignedPacket packet = SignedPacket.sign(KEYS.sig(), 1794900000000000L, dns);
        byte[] body = packet.relayPayload();
        assertEquals(64 + 8 + dns.length, body.length);
        assertEquals(1794900000000000L, java.nio.ByteBuffer.wrap(body, 64, 8).getLong());

        SignedPacket read = SignedPacket.fromRelayPayload(KEYS.sigPublicKey(), body);
        assertEquals(packet.timestampMicros(), read.timestampMicros());
        assertArrayEquals(dns, read.dns());

        byte[] tampered = body.clone();
        tampered[body.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> SignedPacket.fromRelayPayload(KEYS.sigPublicKey(), tampered));
        byte[] otherKey = WorldKeys.fromSeed(Hkdf.sha256(new byte[]{2})).sigPublicKey();
        assertThrows(IllegalArgumentException.class, () -> SignedPacket.fromRelayPayload(otherKey, body));
        assertThrows(IllegalArgumentException.class, () -> SignedPacket.fromRelayPayload(KEYS.sigPublicKey(), new byte[40]));
        assertThrows(IllegalArgumentException.class, () -> SignedPacket.sign(KEYS.sig(), 1, new byte[1001]));
    }

    // ---- record and rendezvous ---------------------------------------------------------------

    @Test
    void handoffRecordNamesTheSuccessorAndHasNoAddress() {
        RendezvousRecord handoff = hostRecord().handoffTo("11111111-2222-3333-4444-555555555555", 1794900400L);
        assertTrue(handoff.isState(RendezvousRecord.HANDOFF));
        assertEquals("", handoff.addr());
        assertEquals("", handoff.lan());
        assertEquals(hostRecord().host(), handoff.host());
        assertEquals(hostRecord().sess(), handoff.sess());
        assertEquals(1794900400L, handoff.ts());
        RendezvousRecord back = RendezvousRecord.fromJson(handoff.toJson());
        assertEquals(handoff, back);
        assertEquals("11111111-2222-3333-4444-555555555555", back.next());
        assertTrue(handoff.toJson().contains("\"st\":\"handoff\""));
    }

    @Test
    void recordJsonRoundTripsAndOmitsEmptyOptionalFields() {
        RendezvousRecord record = hostRecord();
        assertEquals(record, RendezvousRecord.fromJson(record.toJson()));
        String starting = RendezvousRecord.of(RendezvousRecord.STARTING, "uuid", "Alex", "aa", 5).toJson();
        assertTrue(!starting.contains("addr") && !starting.contains("next") && !starting.contains("head"), starting);
        assertThrows(IllegalArgumentException.class, () -> RendezvousRecord.fromJson("{\"v\":2,\"st\":\"host\",\"ts\":1}"));
        assertThrows(IllegalArgumentException.class, () -> RendezvousRecord.fromJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> RendezvousRecord.fromJson("{}"));
    }

    /** Stores packets like a relay does: newest timestamp wins, older is refused. */
    private static final class MemoryClient implements PkarrClient {
        final Map<String, SignedPacket> stored = new HashMap<>();

        @Override
        public Optional<SignedPacket> get(byte[] publicKey) {
            return Optional.ofNullable(stored.get(java.util.HexFormat.of().formatHex(publicKey)));
        }

        @Override
        public void put(SignedPacket packet) throws IOException {
            String key = java.util.HexFormat.of().formatHex(packet.publicKey());
            SignedPacket old = stored.get(key);
            if (old != null && old.timestampMicros() >= packet.timestampMicros()) {
                throw new StaleRecordException("older");
            }
            stored.put(key, packet);
        }
    }

    @Test
    void pkarrRendezvousPublishesAnEncryptedRecordOnlyTheWorldCanRead() throws Exception {
        MemoryClient client = new MemoryClient();
        long[] clock = {1_000_000};
        PkarrRendezvous rendezvous = new PkarrRendezvous(KEYS, client, () -> clock[0]);
        assertTrue(rendezvous.lookup().isEmpty());

        rendezvous.publish(hostRecord());
        assertEquals(Optional.of(hostRecord()), new PkarrRendezvous(KEYS, client, () -> clock[0]).lookup());

        SignedPacket packet = client.stored.values().iterator().next();
        assertTrue(packet.dns().length <= SignedPacket.MAX_DNS, "DNS packet is " + packet.dns().length + " bytes");
        String onTheWire = new String(packet.dns(), StandardCharsets.ISO_8859_1);
        assertTrue(!onTheWire.contains("e4mc.link") && !onTheWire.contains("Alex"), "payload must be encrypted");

        // a second publish with a clock that did not move still gets a newer timestamp
        rendezvous.publish(hostRecord().with(RendezvousRecord.IDLE, "", "", 1794900300L));
        assertEquals(RendezvousRecord.IDLE, rendezvous.lookup().orElseThrow().st());

        // another world's keys cannot read it (and would not even look at this address)
        WorldKeys other = WorldKeys.fromSeed(Hkdf.sha256(new byte[]{9}));
        client.stored.put(java.util.HexFormat.of().formatHex(other.sigPublicKey()),
                SignedPacket.sign(other.sig(), 5, client.stored.values().iterator().next().dns()));
        assertThrows(IOException.class, () -> new PkarrRendezvous(other, client, () -> clock[0]).lookup());
    }

    @Test
    void fileRendezvousRoundTrips(@TempDir Path dir) throws Exception {
        FileRendezvous rendezvous = new FileRendezvous(dir.resolve("sub").resolve("world.json"));
        assertTrue(rendezvous.lookup().isEmpty());
        rendezvous.publish(hostRecord());
        assertEquals(Optional.of(hostRecord()), rendezvous.lookup());
    }

    // ---- HTTP client against a fake relay ----------------------------------------------------

    /** Implements the relay API of the pkarr relays spec closely enough to test the client. */
    private static final class FakeRelay implements AutoCloseable {
        final HttpServer server;
        final Map<String, byte[]> stored = new HashMap<>();
        final AtomicInteger forcedStatus = new AtomicInteger();

        FakeRelay() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String key = exchange.getRequestURI().getPath().substring(1);
                int status;
                byte[] response = new byte[0];
                synchronized (stored) {
                    if (forcedStatus.get() != 0) {
                        status = forcedStatus.get();
                    } else if (exchange.getRequestMethod().equals("PUT")) {
                        byte[] body = exchange.getRequestBody().readAllBytes();
                        byte[] old = stored.get(key);
                        if (body.length > 1072) {
                            status = 413;
                        } else if (old != null && java.nio.ByteBuffer.wrap(old, 64, 8).getLong()
                                >= java.nio.ByteBuffer.wrap(body, 64, 8).getLong()) {
                            status = 409;
                        } else {
                            stored.put(key, body);
                            status = 204;
                        }
                    } else if (stored.containsKey(key)) {
                        status = 200;
                        response = stored.get(key);
                    } else {
                        status = 404;
                    }
                }
                exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
                if (response.length > 0) {
                    exchange.getResponseBody().write(response);
                }
                exchange.close();
            });
            server.start();
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static SignedPacket packet(long timestamp) {
        return SignedPacket.sign(KEYS.sig(), timestamp, DnsPacket.encodeTxt(List.of("_dropin", KEYS.worldId()), 300, "t" + timestamp));
    }

    @Test
    void httpClientPutsToEveryRelayAndReadsTheNewest() throws Exception {
        try (FakeRelay a = new FakeRelay(); FakeRelay b = new FakeRelay()) {
            HttpPkarrClient client = new HttpPkarrClient(List.of(a.uri(), b.uri()), Duration.ofSeconds(5));
            assertTrue(client.get(KEYS.sigPublicKey()).isEmpty());

            client.put(packet(10));
            assertEquals(1, a.stored.size());
            assertEquals(1, b.stored.size());
            assertTrue(a.stored.containsKey(KEYS.worldId()), "the path is the z-base-32 key");

            // relay b alone gets a newer packet: a read returns the newest of the two
            new HttpPkarrClient(List.of(b.uri()), Duration.ofSeconds(5)).put(packet(20));
            assertEquals(20, client.get(KEYS.sigPublicKey()).orElseThrow().timestampMicros());

            assertThrows(PkarrClient.StaleRecordException.class,
                    () -> new HttpPkarrClient(List.of(b.uri()), Duration.ofSeconds(5)).put(packet(15)));
        }
    }

    @Test
    void httpClientSurvivesOneDeadRelayAndReportsWhenAllFail() throws Exception {
        URI dead;
        try (FakeRelay gone = new FakeRelay()) {
            dead = gone.uri();
        }
        try (FakeRelay alive = new FakeRelay()) {
            HttpPkarrClient client = new HttpPkarrClient(List.of(dead, alive.uri()), Duration.ofSeconds(5));
            client.put(packet(10));
            assertEquals(10, client.get(KEYS.sigPublicKey()).orElseThrow().timestampMicros());

            alive.forcedStatus.set(429);
            assertThrows(PkarrClient.RateLimitedException.class, () -> client.put(packet(11)));
            assertThrows(PkarrClient.RateLimitedException.class, () -> client.get(KEYS.sigPublicKey()));
            alive.forcedStatus.set(500);
            assertThrows(IOException.class, () -> client.get(KEYS.sigPublicKey()));
        }
        HttpPkarrClient none = new HttpPkarrClient(List.of(dead), Duration.ofSeconds(2));
        assertThrows(IOException.class, () -> none.get(KEYS.sigPublicKey()));
        assertThrows(IOException.class, () -> none.put(packet(12)));
    }

    @Test
    void httpClientRejectsAPacketARelayForged() throws Exception {
        try (FakeRelay relay = new FakeRelay()) {
            WorldKeys attacker = WorldKeys.fromSeed(Hkdf.sha256(new byte[]{66}));
            byte[] forged = SignedPacket.sign(attacker.sig(), 99, DnsPacket.encodeTxt(List.of("_dropin", "x"), 300, "evil")).relayPayload();
            relay.stored.put(KEYS.worldId(), forged);
            HttpPkarrClient client = new HttpPkarrClient(List.of(relay.uri()), Duration.ofSeconds(5));
            assertThrows(IOException.class, () -> client.get(KEYS.sigPublicKey()));
        }
    }
}
