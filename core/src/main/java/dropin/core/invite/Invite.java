package dropin.core.invite;

import dropin.core.crypto.Base32;
import dropin.core.crypto.Hkdf;

import java.util.Arrays;
import java.util.Locale;

/**
 * An invite code: {@code dropin1-} + base32(seed ‖ 16-byte owner-key fingerprint), grouped in fives
 * (DESIGN 4.1, 4.13). The base32 alphabet is RFC 4648, upper case, no padding; decoding ignores case,
 * dashes and white space.
 */
public final class Invite {
    public static final String PREFIX = "dropin1-";
    public static final int SEED_LEN = 32;
    public static final int FINGERPRINT_LEN = 16;

    private final byte[] seed;
    private final byte[] ownerFingerprint;

    public Invite(byte[] seed, byte[] ownerFingerprint) {
        if (seed.length != SEED_LEN || ownerFingerprint.length != FINGERPRINT_LEN) {
            throw new IllegalArgumentException("seed must be 32 bytes and the fingerprint 16 bytes");
        }
        this.seed = seed.clone();
        this.ownerFingerprint = ownerFingerprint.clone();
    }

    /** First 16 bytes of SHA-256 over the owner's raw 32-byte public key. */
    public static byte[] fingerprint(byte[] ownerPublicKey) {
        return Arrays.copyOf(Hkdf.sha256(ownerPublicKey), FINGERPRINT_LEN);
    }

    public byte[] seed() {
        return seed.clone();
    }

    public byte[] ownerFingerprint() {
        return ownerFingerprint.clone();
    }

    public boolean matchesOwner(byte[] ownerPublicKey) {
        return Arrays.equals(ownerFingerprint, fingerprint(ownerPublicKey));
    }

    public String encode() {
        byte[] payload = Arrays.copyOf(seed, SEED_LEN + FINGERPRINT_LEN);
        System.arraycopy(ownerFingerprint, 0, payload, SEED_LEN, FINGERPRINT_LEN);
        String body = Base32.RFC4648.encode(payload);
        StringBuilder out = new StringBuilder(PREFIX);
        for (int i = 0; i < body.length(); i += 5) {
            if (i > 0) {
                out.append('-');
            }
            out.append(body, i, Math.min(i + 5, body.length()));
        }
        return out.toString();
    }

    /** @throws InviteFormatException if the text is not a well-formed invite */
    public static Invite decode(String text) {
        String cleaned = text == null ? "" : text.replaceAll("\\s+", "");
        if (cleaned.length() < PREFIX.length()
                || !cleaned.substring(0, PREFIX.length()).toLowerCase(Locale.ROOT).equals(PREFIX)) {
            throw new InviteFormatException("An invite code starts with " + PREFIX);
        }
        String body = cleaned.substring(PREFIX.length()).replace("-", "").toUpperCase(Locale.ROOT);
        byte[] payload;
        try {
            payload = Base32.RFC4648.decode(body);
        } catch (IllegalArgumentException e) {
            throw new InviteFormatException("The invite code has a character that does not belong in it");
        }
        if (payload.length != SEED_LEN + FINGERPRINT_LEN) {
            throw new InviteFormatException("The invite code is the wrong length; check that all of it was copied");
        }
        return new Invite(Arrays.copyOf(payload, SEED_LEN), Arrays.copyOfRange(payload, SEED_LEN, payload.length));
    }

    public static final class InviteFormatException extends IllegalArgumentException {
        public InviteFormatException(String message) {
            super(message);
        }
    }
}
