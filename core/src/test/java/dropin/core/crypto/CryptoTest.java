package dropin.core.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void hkdfMatchesRfc5869Case1() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                HEX.formatHex(Hkdf.derive(ikm, salt, info, 42)));
    }

    @Test
    void hkdfMatchesRfc5869Case3EmptySaltAndInfo() {
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
                HEX.formatHex(Hkdf.derive(ikm, new byte[0], new byte[0], 42)));
    }

    @Test
    void ed25519MatchesRfc8032Test1() {
        KeyPair pair = Ed25519.keyPairFromSeed(
                HEX.parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"));
        byte[] publicKey = Ed25519.publicKeyBytes(pair.getPublic());
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", HEX.formatHex(publicKey));
        byte[] signature = Ed25519.sign(pair.getPrivate(), new byte[0]);
        assertEquals("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b", HEX.formatHex(signature));
        assertTrue(Ed25519.verify(publicKey, new byte[0], signature));
    }

    @Test
    void ed25519PublicKeyRoundTripsThroughRawBytes() {
        for (int i = 0; i < 40; i++) {
            byte[] seed = Hkdf.sha256(new byte[]{(byte) i});
            byte[] raw = Ed25519.publicKeyBytes(Ed25519.keyPairFromSeed(seed).getPublic());
            assertArrayEquals(raw, Ed25519.publicKeyBytes(Ed25519.publicKeyFromBytes(raw)), "seed " + i);
        }
    }

    @Test
    void ed25519VerifyRejectsWrongMessageKeyAndGarbage() {
        KeyPair pair = Ed25519.keyPairFromSeed(Hkdf.sha256(new byte[]{1}));
        byte[] publicKey = Ed25519.publicKeyBytes(pair.getPublic());
        byte[] other = Ed25519.publicKeyBytes(Ed25519.keyPairFromSeed(Hkdf.sha256(new byte[]{2})).getPublic());
        byte[] message = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] signature = Ed25519.sign(pair.getPrivate(), message);
        assertTrue(Ed25519.verify(publicKey, message, signature));
        assertFalse(Ed25519.verify(publicKey, "hellp".getBytes(StandardCharsets.UTF_8), signature));
        assertFalse(Ed25519.verify(other, message, signature));
        assertFalse(Ed25519.verify(publicKey, message, new byte[64]));
        assertFalse(Ed25519.verify(publicKey, message, new byte[10]));
        assertFalse(Ed25519.verify(new byte[5], message, signature));
    }

    @Test
    void aeadRoundTripsAndRejectsTampering() throws Exception {
        byte[] key = Hkdf.sha256(new byte[]{7});
        byte[] nonce = new byte[12];
        byte[] aad = {1, 2, 3};
        byte[] plain = "who is hosting".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = Aead.seal(key, nonce, aad, plain);
        assertEquals(plain.length + Aead.TAG_LEN, sealed.length);
        assertArrayEquals(plain, Aead.open(key, nonce, aad, sealed));

        byte[] flipped = sealed.clone();
        flipped[0] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> Aead.open(key, nonce, aad, flipped));
        assertThrows(GeneralSecurityException.class, () -> Aead.open(key, nonce, new byte[]{9}, sealed));
        assertThrows(GeneralSecurityException.class, () -> Aead.open(Hkdf.sha256(new byte[]{8}), nonce, aad, sealed));
        assertThrows(GeneralSecurityException.class, () -> Aead.open(key, nonce, aad, new byte[3]));
    }

    @Test
    void base32MatchesRfc4648Vectors() {
        String[][] vectors = {{"", ""}, {"f", "MY"}, {"fo", "MZXQ"}, {"foo", "MZXW6"}, {"foob", "MZXW6YQ"},
                {"fooba", "MZXW6YTB"}, {"foobar", "MZXW6YTBOI"}};
        for (String[] vector : vectors) {
            byte[] raw = vector[0].getBytes(StandardCharsets.US_ASCII);
            assertEquals(vector[1], Base32.RFC4648.encode(raw));
            assertArrayEquals(raw, Base32.RFC4648.decode(vector[1]));
        }
        assertThrows(IllegalArgumentException.class, () -> Base32.RFC4648.decode("MZ1"));
        assertThrows(IllegalArgumentException.class, () -> Base32.RFC4648.decode("M"));
        assertThrows(IllegalArgumentException.class, () -> Base32.RFC4648.decode("MZ"));
    }

    @Test
    void zBase32EncodesAKeyAs52CharactersAndRoundTrips() {
        byte[] key = Hkdf.sha256(new byte[]{3});
        String text = ZBase32.encode(key);
        assertEquals(52, text.length());
        assertTrue(text.matches("[ybndrfg8ejkmcpqxot1uwisza345h769]+"));
        assertArrayEquals(key, ZBase32.decode(text));
        assertEquals("yyyyyyyy", ZBase32.encode(new byte[5]));
    }

    @Test
    void worldKeysAreDeterministicAndSeparated() {
        byte[] seed = Hkdf.sha256(new byte[]{42});
        WorldKeys a = WorldKeys.fromSeed(seed);
        WorldKeys b = WorldKeys.fromSeed(seed);
        assertEquals(a.worldId(), b.worldId());
        assertEquals(52, a.worldId().length());
        assertArrayEquals(a.auth(), b.auth());
        assertArrayEquals(a.enc(), b.enc());
        assertFalse(java.util.Arrays.equals(a.auth(), a.enc()));
        assertNotEquals(a.worldId(), WorldKeys.fromSeed(Hkdf.sha256(new byte[]{43})).worldId());
        byte[] message = {1};
        assertTrue(Ed25519.verify(a.sigPublicKey(), message, Ed25519.sign(b.sig().getPrivate(), message)));
        assertThrows(IllegalArgumentException.class, () -> WorldKeys.fromSeed(new byte[31]));
    }
}
