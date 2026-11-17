package dropin.core.crypto;

import java.security.KeyPair;

/** Everything derived from a world's 32-byte invite seed (DESIGN 4.1). */
public final class WorldKeys {
    private final KeyPair sig;
    private final byte[] sigPublic;
    private final byte[] auth;
    private final byte[] enc;

    private WorldKeys(KeyPair sig, byte[] auth, byte[] enc) {
        this.sig = sig;
        this.sigPublic = Ed25519.publicKeyBytes(sig.getPublic());
        this.auth = auth;
        this.enc = enc;
    }

    public static WorldKeys fromSeed(byte[] seed) {
        if (seed.length != Ed25519.SEED_LEN) {
            throw new IllegalArgumentException("seed must be 32 bytes");
        }
        return new WorldKeys(
                Ed25519.keyPairFromSeed(Hkdf.derive(seed, "dropin/sig", 32)),
                Hkdf.derive(seed, "dropin/auth", 32),
                Hkdf.derive(seed, "dropin/enc", 32));
    }

    /** Signs rendezvous records. */
    public KeyPair sig() {
        return sig;
    }

    public byte[] sigPublicKey() {
        return sigPublic.clone();
    }

    /** z-base-32 of the signing public key: 52 characters, the pkarr address of the world. */
    public String worldId() {
        return ZBase32.encode(sigPublic);
    }

    /** HMAC key for the sync handshake and ledger entries. */
    public byte[] auth() {
        return auth.clone();
    }

    /** AEAD key for the rendezvous payload and sync frames. */
    public byte[] enc() {
        return enc.clone();
    }
}
