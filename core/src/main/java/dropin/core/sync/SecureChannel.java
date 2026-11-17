package dropin.core.sync;

import dropin.core.crypto.Aead;
import dropin.core.crypto.Hkdf;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * The sealed part of a sync connection (DESIGN 4.6). Session key = HKDF(enc, nonceC ‖ nonceS); every
 * frame is AEAD-sealed with a counter nonce, one counter per direction, so a dropped, repeated or
 * reordered frame fails to open. Not thread-safe: seal and send under one lock.
 */
final class SecureChannel {
    static final int NONCE_LENGTH = 16;
    private static final byte[] NO_AAD = new byte[0];

    private final byte[] key;
    private final byte sendDirection;
    private final byte receiveDirection;
    private long sent;
    private long received;

    SecureChannel(byte[] enc, byte[] nonceC, byte[] nonceS, boolean client) {
        this.key = Hkdf.derive(enc, concat(nonceC, nonceS), "dropin/session".getBytes(StandardCharsets.UTF_8), Aead.KEY_LEN);
        this.sendDirection = (byte) (client ? 1 : 2);
        this.receiveDirection = (byte) (client ? 2 : 1);
    }

    /** The proof each side sends in the handshake: HMAC(auth, nonceC ‖ nonceS ‖ "c" or "s"). */
    static byte[] proof(byte[] auth, byte[] nonceC, byte[] nonceS, String side) {
        return Hkdf.hmac(auth, nonceC, nonceS, side.getBytes(StandardCharsets.UTF_8));
    }

    byte[] seal(byte[] message) {
        return Aead.seal(key, nonce(sendDirection, sent++), NO_AAD, message);
    }

    byte[] open(byte[] sealed) throws GeneralSecurityException {
        return Aead.open(key, nonce(receiveDirection, received++), NO_AAD, sealed);
    }

    private static byte[] nonce(byte direction, long counter) {
        byte[] nonce = new byte[Aead.NONCE_LEN];
        nonce[0] = direction;
        for (int i = 0; i < 8; i++) {
            nonce[4 + i] = (byte) (counter >>> (56 - 8 * i));
        }
        return nonce;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] joined = new byte[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return joined;
    }
}
