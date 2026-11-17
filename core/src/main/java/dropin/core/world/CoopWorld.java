package dropin.core.world;

import dropin.core.crypto.WorldKeys;
import dropin.core.invite.Invite;

import java.util.HexFormat;
import java.util.List;

/**
 * What this PC knows about one co-op world: the contents of {@code config/dropin/worlds/<worldId>.json}.
 *
 * @param seed             hex of the 32-byte invite seed
 * @param ownerFingerprint hex of the 16-byte owner-key fingerprint (from the invite)
 * @param ownerKey         hex of the owner's 32-byte public key, or empty until it is known
 */
public record CoopWorld(String worldId, String seed, String name, String ownerFingerprint, String ownerKey,
                        List<Member> members) {
    private static final HexFormat HEX = HexFormat.of();

    public CoopWorld {
        members = List.copyOf(members);
    }

    public WorldKeys keys() {
        return WorldKeys.fromSeed(HEX.parseHex(seed));
    }

    public Invite invite() {
        return new Invite(HEX.parseHex(seed), HEX.parseHex(ownerFingerprint));
    }

    public CoopWorld withMembers(List<Member> newMembers) {
        return new CoopWorld(worldId, seed, name, ownerFingerprint, ownerKey, newMembers);
    }
}
