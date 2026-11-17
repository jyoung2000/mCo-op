package dropin.core.pkarr;

import dropin.core.crypto.Aead;
import dropin.core.crypto.WorldKeys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * The rendezvous record as a pkarr packet at the world's key: one TXT record named {@code _dropin}
 * whose value is base64url(nonce ‖ AEAD(enc, payload)).
 */
public final class PkarrRendezvous implements Rendezvous {
    public static final String LABEL = "_dropin";
    private static final int TTL_SECONDS = 300;

    private final WorldKeys keys;
    private final PkarrClient client;
    private final LongSupplier clockMicros;
    private final SecureRandom random = new SecureRandom();
    private long lastTimestamp;

    public PkarrRendezvous(WorldKeys keys, PkarrClient client, LongSupplier clockMicros) {
        this.keys = keys;
        this.client = client;
        this.clockMicros = clockMicros;
    }

    public PkarrRendezvous(WorldKeys keys, PkarrClient client) {
        this(keys, client, () -> System.currentTimeMillis() * 1000);
    }

    private byte[] associatedData() {
        return keys.worldId().getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public Optional<RendezvousRecord> lookup() throws IOException {
        Optional<SignedPacket> packet = client.get(keys.sigPublicKey());
        if (packet.isEmpty()) {
            return Optional.empty();
        }
        lastTimestamp = Math.max(lastTimestamp, packet.get().timestampMicros());
        try {
            String text = DnsPacket.findTxt(packet.get().dns(), LABEL);
            if (text == null) {
                return Optional.empty();
            }
            byte[] sealed = Base64.getUrlDecoder().decode(text);
            byte[] nonce = Arrays.copyOf(sealed, Aead.NONCE_LEN);
            byte[] plain = Aead.open(keys.enc(), nonce, associatedData(),
                    Arrays.copyOfRange(sealed, Aead.NONCE_LEN, sealed.length));
            return Optional.of(RendezvousRecord.fromJson(new String(plain, StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("the published rendezvous record cannot be read: " + e.getMessage(), e);
        }
    }

    @Override
    public void publish(RendezvousRecord record) throws IOException {
        byte[] nonce = new byte[Aead.NONCE_LEN];
        random.nextBytes(nonce);
        byte[] cipher = Aead.seal(keys.enc(), nonce, associatedData(), record.toJson().getBytes(StandardCharsets.UTF_8));
        byte[] sealed = Arrays.copyOf(nonce, nonce.length + cipher.length);
        System.arraycopy(cipher, 0, sealed, nonce.length, cipher.length);
        String text = Base64.getUrlEncoder().withoutPadding().encodeToString(sealed);
        byte[] dns = DnsPacket.encodeTxt(List.of(LABEL, keys.worldId()), TTL_SECONDS, text);
        // A relay refuses a timestamp that is not newer than the one it has.
        long timestamp = Math.max(clockMicros.getAsLong(), lastTimestamp + 1);
        client.put(SignedPacket.sign(keys.sig(), timestamp, dns));
        lastTimestamp = timestamp;
    }
}
