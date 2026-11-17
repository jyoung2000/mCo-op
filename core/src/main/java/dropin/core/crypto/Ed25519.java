package dropin.core.crypto;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;

/** Ed25519 with the JDK only: key pair from a 32-byte seed, raw 32-byte public keys, sign, verify. */
public final class Ed25519 {
    public static final int SEED_LEN = 32;
    public static final int PUBLIC_KEY_LEN = 32;
    public static final int SIGNATURE_LEN = 64;

    private Ed25519() {
    }

    /** The JDK draws exactly the 32 private-key bytes from the generator's random source. */
    private static final class FixedRandom extends SecureRandom {
        private final byte[] seed;

        FixedRandom(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (bytes.length != seed.length) {
                throw new IllegalStateException("unexpected request for " + bytes.length + " random bytes");
            }
            System.arraycopy(seed, 0, bytes, 0, seed.length);
        }
    }

    public static KeyPair keyPairFromSeed(byte[] seed) {
        if (seed.length != SEED_LEN) {
            throw new IllegalArgumentException("seed must be " + SEED_LEN + " bytes");
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519, new FixedRandom(seed));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** RFC 8032 encoding: y little-endian, top bit = sign of x. */
    public static byte[] publicKeyBytes(PublicKey key) {
        EdECPoint point = ((EdECPublicKey) key).getPoint();
        byte[] big = point.getY().toByteArray();
        byte[] out = new byte[PUBLIC_KEY_LEN];
        for (int i = 0; i < big.length && i < PUBLIC_KEY_LEN; i++) {
            out[i] = big[big.length - 1 - i];
        }
        if (point.isXOdd()) {
            out[PUBLIC_KEY_LEN - 1] |= (byte) 0x80;
        }
        return out;
    }

    public static PublicKey publicKeyFromBytes(byte[] raw) {
        if (raw.length != PUBLIC_KEY_LEN) {
            throw new IllegalArgumentException("public key must be " + PUBLIC_KEY_LEN + " bytes");
        }
        byte[] big = new byte[PUBLIC_KEY_LEN];
        for (int i = 0; i < PUBLIC_KEY_LEN; i++) {
            big[i] = raw[PUBLIC_KEY_LEN - 1 - i];
        }
        boolean xOdd = (big[0] & 0x80) != 0;
        big[0] &= 0x7F;
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(
                    new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, new BigInteger(1, big))));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not an Ed25519 public key", e);
        }
    }

    public static byte[] sign(PrivateKey key, byte[] message) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** False for a wrong signature, a malformed signature or a malformed key. Never throws for bad input. */
    public static boolean verify(byte[] publicKey, byte[] message, byte[] sig) {
        if (sig == null || sig.length != SIGNATURE_LEN || publicKey == null || publicKey.length != PUBLIC_KEY_LEN) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKeyFromBytes(publicKey));
            signature.update(message);
            return signature.verify(sig);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
