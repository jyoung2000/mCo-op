package dropin.core.policy;

import dropin.core.crypto.Ed25519;
import dropin.core.invite.Invite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyTest {
    private static final KeyPair OWNER = Ed25519.keyPairFromSeed(seed(7));
    private static final KeyPair STRANGER = Ed25519.keyPairFromSeed(seed(8));
    private static final byte[] FINGERPRINT = Invite.fingerprint(Ed25519.publicKeyBytes(OWNER.getPublic()));
    private static final String OWNER_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String BANNED_UUID = "99999999-2222-3333-4444-555555555555";

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        java.util.Arrays.fill(seed, (byte) fill);
        return seed;
    }

    private static Policy unsigned(long version, List<String> banned) {
        return new Policy(version, "", OWNER_UUID, "Alex", "fabric", "26.3", "ab".repeat(32),
                Map.of("pack/pack.mcmeta", "cd".repeat(32), "a.zip", "ef".repeat(32)), List.of("file/pack", "file/a.zip"),
                banned, false, 1_800_000_000L, "");
    }

    private static Policy signed(long version) {
        return Policy.sign(unsigned(version, List.of(BANNED_UUID)), OWNER);
    }

    @Test
    void aPolicySignedByTheOwnerIsValid() {
        Policy policy = signed(1);
        assertNull(policy.rejection(FINGERPRINT));
        assertTrue(policy.isOwner(OWNER_UUID));
        assertTrue(policy.isBanned(BANNED_UUID));
        assertFalse(policy.isBanned(OWNER_UUID));
    }

    @Test
    void jsonAndFileRoundTripKeepTheSignatureValid(@TempDir Path dir) throws IOException {
        Policy policy = signed(3);
        Policy parsed = Policy.fromJson(policy.toJson());
        assertEquals(policy, parsed);
        assertNull(parsed.rejection(FINGERPRINT));
        Path file = dir.resolve("dropin").resolve(Policy.FILE);
        policy.save(file);
        assertEquals(policy, Policy.load(file));
        assertNull(Policy.load(dir.resolve("missing.json")));
    }

    @Test
    void theOrderOfMapsAndBansDoesNotChangeWhatIsSigned() {
        Policy a = new Policy(1, "", OWNER_UUID, "Alex", "fabric", "26.3", "ab".repeat(32),
                new java.util.LinkedHashMap<>(Map.of("b", "1", "a", "2")), List.of("file/x"), List.of("b", "a"), true, 5, "");
        java.util.Map<String, String> reversed = new java.util.LinkedHashMap<>();
        reversed.put("a", "2");
        reversed.put("b", "1");
        Policy b = new Policy(1, "", OWNER_UUID, "Alex", "fabric", "26.3", "ab".repeat(32), reversed, List.of("file/x"),
                List.of("a", "b"), true, 5, "");
        assertEquals(new String(a.canonicalBytes()), new String(b.canonicalBytes()));
    }

    @Test
    void aChangedFieldOrSignatureIsRejected() {
        Policy policy = signed(1);
        Policy unbanned = new Policy(policy.version(), policy.ownerKey(), policy.ownerUuid(), policy.ownerName(), policy.loader(),
                policy.minecraftVersion(), policy.modManifestHash(), policy.datapackFiles(), policy.datapacksEnabled(), List.of(),
                policy.membersMayUseCommands(), policy.ts(), policy.sig());
        assertNotNull(unbanned.rejection(FINGERPRINT));

        Policy commands = new Policy(policy.version(), policy.ownerKey(), policy.ownerUuid(), policy.ownerName(), policy.loader(),
                policy.minecraftVersion(), policy.modManifestHash(), policy.datapackFiles(), policy.datapacksEnabled(),
                policy.banned(), true, policy.ts(), policy.sig());
        assertNotNull(commands.rejection(FINGERPRINT));

        String sig = policy.sig();
        String flipped = (sig.charAt(0) == '0' ? '1' : '0') + sig.substring(1);
        Policy badSig = Policy.fromJson(policy.toJson().replace(sig, flipped));
        assertNotNull(badSig.rejection(FINGERPRINT));

        assertNotNull(Policy.fromJson(policy.toJson().replace(sig, "zz")).rejection(FINGERPRINT));
        assertNotNull(Policy.fromJson(policy.toJson().replace(sig, "")).rejection(FINGERPRINT));
    }

    @Test
    void aKeyThatDoesNotMatchTheInviteFingerprintIsRejected() {
        Policy forged = Policy.sign(unsigned(9, List.of()), STRANGER);
        // The signature itself is fine, but it is not the key the invite names.
        assertNull(forged.rejection(Invite.fingerprint(Ed25519.publicKeyBytes(STRANGER.getPublic()))));
        String problem = forged.rejection(FINGERPRINT);
        assertNotNull(problem);
        assertTrue(problem.contains("owner"), problem);
    }

    @Test
    void anOlderVersionNeverReplacesANewerOne() {
        Policy v1 = signed(1);
        Policy v2 = signed(2);
        assertTrue(Policy.replaces(v1, null));
        assertTrue(Policy.replaces(v2, v1));
        assertFalse(Policy.replaces(v1, v2));
        assertFalse(Policy.replaces(v2, v2));
        assertFalse(Policy.replaces(null, v1));
        assertNotNull(Policy.sign(unsigned(0, List.of()), OWNER).rejection(FINGERPRINT));
    }

    @Test
    void controlCharactersCannotBeSigned() {
        Policy sneaky = new Policy(1, "", OWNER_UUID, "Alex\nbanned nobody", "fabric", "26.3", "ab".repeat(32), Map.of(), List.of(),
                List.of(), false, 5, "");
        assertThrows(IllegalArgumentException.class, () -> Policy.sign(sneaky, OWNER));
        assertThrows(IllegalArgumentException.class, () -> Policy.fromJson("{\"version\":1}"));
    }

    @Test
    void adminsAreSignedAndAPolicyWithoutThemStaysValid() {
        String admin = "aaaaaaaa-2222-3333-4444-555555555555";
        Policy plain = signed(1);
        assertTrue(plain.admins().isEmpty());
        assertFalse(plain.toJson().contains("admins"), "a policy without admins keeps the old form");

        Policy with = Policy.sign(new Policy(2, "", OWNER_UUID, "Alex", "fabric", "26.3", "ab".repeat(32), Map.of(), List.of(),
                List.of(BANNED_UUID), List.of(admin, admin, BANNED_UUID), false, 5, ""), OWNER);
        assertNull(with.rejection(FINGERPRINT));
        assertEquals(2, with.admins().size(), "duplicates are dropped");
        assertTrue(with.isAdmin(admin));
        assertFalse(with.isAdmin(BANNED_UUID), "a ban wins over admin");
        assertFalse(with.isAdmin(OWNER_UUID));
        Policy back = Policy.fromJson(with.toJson());
        assertEquals(with, back);
        assertNull(back.rejection(FINGERPRINT));

        // Adding an admin to a signed policy without signing again must not verify.
        Policy forged = new Policy(with.version(), with.ownerKey(), with.ownerUuid(), with.ownerName(), with.loader(),
                with.minecraftVersion(), with.modManifestHash(), with.datapackFiles(), with.datapacksEnabled(), with.banned(),
                List.of(admin, "bbbbbbbb-2222-3333-4444-555555555555"), with.membersMayUseCommands(), with.ts(), with.sig());
        assertNotNull(forged.rejection(FINGERPRINT));
    }
}
