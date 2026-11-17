package dropin.core.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MembersMergeTest {
    private static final String OWNER = "11111111-2222-3333-4444-555555555555";
    private static final String SAM = "22222222-2222-3333-4444-555555555555";
    private static final String KIM = "33333333-2222-3333-4444-555555555555";

    private static Member find(CoopWorld world, String uuid) {
        return world.members().stream().filter(m -> m.uuid().equals(uuid)).findFirst().orElseThrow();
    }

    @Test
    void membersFromAPeerAreMergedAndTheOwnerComesFromThePolicy(@TempDir Path dir) throws IOException {
        CoopWorldStore store = new CoopWorldStore(dir.resolve("config"));
        Path folder = Files.createDirectories(dir.resolve("world"));
        CoopWorld world = store.importWorld(folder, "World", OWNER, "Alex", 1000, new SecureRandom());
        store.recordMember(world.worldId(), SAM, "Sam", 2000);

        // A peer claims to be the owner, knows Sam under a newer name, and knows Kim.
        String fromPeer = CoopWorldStore.membersJson(List.of(
                new Member(SAM, "Sammy", 1500, 3000, Member.OWNER),
                new Member(KIM, "Kim", 2500, 2600, Member.MEMBER),
                new Member(OWNER, "Alex", 900, 950, Member.MEMBER)));
        List<Member> incoming = CoopWorldStore.parseMembers(fromPeer);
        assertTrue(incoming.stream().noneMatch(Member::isOwner));

        CoopWorld merged = store.mergeMembers(world.worldId(), incoming, OWNER);
        assertEquals(3, merged.members().size());
        assertEquals(new Member(SAM, "Sammy", 1500, 3000, Member.MEMBER), find(merged, SAM));
        assertEquals(new Member(KIM, "Kim", 2500, 2600, Member.MEMBER), find(merged, KIM));
        assertEquals(new Member(OWNER, "Alex", 900, 1000, Member.OWNER), find(merged, OWNER));
        assertEquals(merged, store.get(world.worldId()).orElseThrow());

        // Without a policy the roles this PC already has are kept.
        CoopWorld again = store.mergeMembers(world.worldId(), incoming, "");
        assertEquals(merged, again);
        assertTrue(find(again, OWNER).isOwner());
        assertFalse(find(again, SAM).isOwner());
    }

    @Test
    void malformedMemberEntriesAreDropped() {
        assertEquals(List.of(), CoopWorldStore.parseMembers("not json"));
        assertEquals(List.of(), CoopWorldStore.parseMembers("{\"members\":[{\"uuid\":\"../x\",\"name\":\"A\",\"firstSeen\":1,\"lastSeen\":2}]}"));
        assertEquals(List.of(), CoopWorldStore.parseMembers("{\"members\":[{\"uuid\":\"" + SAM + "\",\"name\":\"A\\nB\",\"firstSeen\":1,\"lastSeen\":2}]}"));
        assertEquals(1, CoopWorldStore.parseMembers("{\"members\":[{\"uuid\":\"" + SAM + "\",\"name\":\"Sam\",\"firstSeen\":1,\"lastSeen\":2},{}]}").size());
    }

    @Test
    void onlyTheKeyTheInviteNamesIsLearnedAsOwnerKey(@TempDir Path dir) throws IOException {
        CoopWorldStore ownerStore = new CoopWorldStore(dir.resolve("owner"));
        CoopWorld owned = ownerStore.importWorld(Files.createDirectories(dir.resolve("world")), "World", OWNER, "Alex", 1000,
                new SecureRandom());
        CoopWorldStore memberStore = new CoopWorldStore(dir.resolve("member"));
        CoopWorld joined = memberStore.joinWithInvite(owned.invite(), "World");
        assertEquals("", joined.ownerKey());

        assertThrows(IOException.class, () -> memberStore.learnOwnerKey(joined.worldId(), "00".repeat(32)));
        assertEquals("", memberStore.get(joined.worldId()).orElseThrow().ownerKey());
        assertEquals(owned.ownerKey(), memberStore.learnOwnerKey(joined.worldId(), owned.ownerKey()).ownerKey());
        assertEquals(owned.ownerKey(), memberStore.get(joined.worldId()).orElseThrow().ownerKey());
    }
}
