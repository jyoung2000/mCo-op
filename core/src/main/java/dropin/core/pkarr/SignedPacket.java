package dropin.core.pkarr;

import dropin.core.crypto.Ed25519;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;

/**
 * A pkarr signed packet as relays exchange it (https://pubky.github.io/pkarr/design/relays.html, read 2026-11-17):
 * {@code signature (64) ‖ timestamp (8, big-endian, microseconds) ‖ DNS packet (at most 1000 bytes)}.
 * The signature covers the BEP 44 form {@code 3:seqi<timestamp>e1:v<length>:<DNS packet>}.
 */
public record SignedPacket(byte[] publicKey, long timestampMicros, byte[] dns, byte[] signature) {
    public static final int MAX_DNS = 1000;
    private static final int HEADER = Ed25519.SIGNATURE_LEN + 8;

    public static byte[] signable(long timestampMicros, byte[] dns) {
        byte[] head = ("3:seqi" + timestampMicros + "e1:v" + dns.length + ":").getBytes(StandardCharsets.US_ASCII);
        byte[] out = Arrays.copyOf(head, head.length + dns.length);
        System.arraycopy(dns, 0, out, head.length, dns.length);
        return out;
    }

    public static SignedPacket sign(KeyPair key, long timestampMicros, byte[] dns) {
        if (dns.length > MAX_DNS) {
            throw new IllegalArgumentException("DNS packet is " + dns.length + " bytes, the limit is " + MAX_DNS);
        }
        return new SignedPacket(Ed25519.publicKeyBytes(key.getPublic()), timestampMicros, dns.clone(),
                Ed25519.sign(key.getPrivate(), signable(timestampMicros, dns)));
    }

    public byte[] relayPayload() {
        return ByteBuffer.allocate(HEADER + dns.length).put(signature).putLong(timestampMicros).put(dns).array();
    }

    /** @throws IllegalArgumentException if the body is malformed or not signed by {@code publicKey} */
    public static SignedPacket fromRelayPayload(byte[] publicKey, byte[] body) {
        if (body.length < HEADER || body.length > HEADER + MAX_DNS) {
            throw new IllegalArgumentException("relay payload has an impossible size: " + body.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(body);
        byte[] signature = new byte[Ed25519.SIGNATURE_LEN];
        buffer.get(signature);
        long timestamp = buffer.getLong();
        byte[] dns = new byte[buffer.remaining()];
        buffer.get(dns);
        if (!Ed25519.verify(publicKey, signable(timestamp, dns), signature)) {
            throw new IllegalArgumentException("relay payload is not signed by this key");
        }
        return new SignedPacket(publicKey.clone(), timestamp, dns, signature);
    }
}
