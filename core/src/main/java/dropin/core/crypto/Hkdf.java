package dropin.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** HKDF-SHA256 (RFC 5869), HMAC-SHA256 and SHA-256. DESIGN 4.1. */
public final class Hkdf {
    private static final int HASH_LEN = 32;

    private Hkdf() {
    }

    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // An empty key is legal for HMAC but SecretKeySpec refuses it; HMAC pads keys with zeros anyway.
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[HASH_LEN] : key, "HmacSHA256"));
            for (byte[] part : parts) {
                mac.update(part);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return digest.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Full HKDF: extract with {@code salt} (may be empty), then expand to {@code length} bytes. */
    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (length < 1 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("length " + length);
        }
        byte[] prk = hmac(salt, ikm);
        byte[] out = new byte[length];
        byte[] block = new byte[0];
        int done = 0;
        for (int counter = 1; done < length; counter++) {
            block = hmac(prk, block, info, new byte[]{(byte) counter});
            int n = Math.min(block.length, length - done);
            System.arraycopy(block, 0, out, done, n);
            done += n;
        }
        return out;
    }

    /** HKDF with an empty salt and an ASCII info label, as used for the world keys. */
    public static byte[] derive(byte[] ikm, String info, int length) {
        return derive(ikm, new byte[0], info.getBytes(StandardCharsets.US_ASCII), length);
    }
}
