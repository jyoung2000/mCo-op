package dropin.core.invite;

import dropin.core.crypto.Ed25519;
import dropin.core.crypto.Hkdf;
import dropin.core.crypto.WorldKeys;
import dropin.core.invite.Invite.InviteFormatException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InviteTest {
    private static final byte[] SEED = Hkdf.sha256(new byte[]{1});
    private static final byte[] OWNER_KEY =
            Ed25519.publicKeyBytes(Ed25519.keyPairFromSeed(Hkdf.sha256(new byte[]{2})).getPublic());

    private static Invite invite() {
        return new Invite(SEED, Invite.fingerprint(OWNER_KEY));
    }

    @Test
    void encodesAsPrefixAndGroupsOfFive() {
        String code = invite().encode();
        assertTrue(code.startsWith("dropin1-"));
        String body = code.substring("dropin1-".length());
        // 48 bytes = 77 base32 characters = 15 groups of five and one of two
        assertTrue(body.matches("([A-Z2-7]{5}-){15}[A-Z2-7]{2}"), body);
    }

    @Test
    void roundTripsToTheSameWorldAndOwner() {
        Invite decoded = Invite.decode(invite().encode());
        assertArrayEquals(SEED, decoded.seed());
        assertEquals(WorldKeys.fromSeed(SEED).worldId(), WorldKeys.fromSeed(decoded.seed()).worldId());
        assertTrue(decoded.matchesOwner(OWNER_KEY));
        assertFalse(decoded.matchesOwner(Ed25519.publicKeyBytes(
                Ed25519.keyPairFromSeed(Hkdf.sha256(new byte[]{3})).getPublic())));
    }

    @Test
    void decodingIgnoresCaseDashesAndWhiteSpace() {
        String code = invite().encode();
        String messy = "  " + code.toLowerCase().replace("-", " ").replaceFirst("dropin1 ", "DropIn1-") + "\n";
        assertArrayEquals(SEED, Invite.decode(messy).seed());
    }

    @Test
    void refusesMalformedCodes() {
        String code = invite().encode();
        assertThrows(InviteFormatException.class, () -> Invite.decode(null));
        assertThrows(InviteFormatException.class, () -> Invite.decode(""));
        assertThrows(InviteFormatException.class, () -> Invite.decode(code.substring(8)));
        assertThrows(InviteFormatException.class, () -> Invite.decode("dropin2-" + code.substring(8)));
        assertThrows(InviteFormatException.class, () -> Invite.decode(code.substring(0, code.length() - 6)));
        assertThrows(InviteFormatException.class, () -> Invite.decode(code + "-AAAAA-AAA"));
        assertThrows(InviteFormatException.class, () -> Invite.decode(code.replace(code.charAt(10), '1')));
        // a seed-only (32-byte) code from before the owner fingerprint was added is the wrong length
        assertThrows(InviteFormatException.class,
                () -> Invite.decode("dropin1-" + dropin.core.crypto.Base32.RFC4648.encode(SEED)));
    }

    @Test
    void constructorChecksLengths() {
        assertThrows(IllegalArgumentException.class, () -> new Invite(new byte[31], new byte[16]));
        assertThrows(IllegalArgumentException.class, () -> new Invite(new byte[32], new byte[15]));
    }
}
