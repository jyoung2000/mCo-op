package dropin.core.world;

import dropin.core.crypto.Ed25519;
import dropin.core.crypto.WorldKeys;
import dropin.core.invite.Invite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoopWorldStoreTest {
    @TempDir
    Path temp;
    private Path config;
    private Path world;
    private CoopWorldStore store;

    @BeforeEach
    void setUp() throws IOException {
        config = temp.resolve("config").resolve("dropin");
        world = temp.resolve("saves").resolve("My World");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "not really");
        store = new CoopWorldStore(config);
    }

    private CoopWorld importIt() throws IOException {
        return store.importWorld(world, "My World", "uuid-alex", "Alex", 1000, new SecureRandom());
    }

    @Test
    void importWritesTheKeyFilesAndTheMarkerAndNothingElseInTheWorld() throws IOException {
        CoopWorld imported = importIt();

        assertEquals(Optional.of(imported.worldId()), CoopWorldStore.readMarker(world));
        try (var files = Files.list(world)) {
            assertEquals(List.of("dropin", "level.dat"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        try (var files = Files.list(world.resolve("dropin"))) {
            assertEquals(List.of("world.json"), files.map(p -> p.getFileName().toString()).toList());
        }
        String marker = Files.readString(world.resolve("dropin").resolve("world.json"));
        assertFalse(marker.contains(imported.seed()), "the seed must never be in the world folder");

        assertEquals(Optional.of(imported), store.get(imported.worldId()));
        assertEquals(Optional.of(imported), store.forFolder(world));
        assertEquals(List.of(imported), store.list());
        assertTrue(store.isOwner(imported.worldId()));
        assertEquals(List.of(new Member("uuid-alex", "Alex", 1000, 1000, Member.OWNER)), imported.members());
    }

    @Test
    void theInviteDecodesToTheSameWorldAndTheOwnerKeyOnDisk() throws IOException {
        CoopWorld imported = importIt();
        Invite decoded = Invite.decode(imported.invite().encode());

        assertEquals(imported.worldId(), WorldKeys.fromSeed(decoded.seed()).worldId());
        byte[] ownerPublic = Ed25519.publicKeyBytes(store.ownerKey(imported.worldId()).orElseThrow().getPublic());
        assertTrue(decoded.matchesOwner(ownerPublic));
        assertEquals(HexFormat.of().formatHex(ownerPublic), imported.ownerKey());
        // the owner key is independent of the invite seed: holding the invite does not make you the owner
        assertFalse(decoded.matchesOwner(WorldKeys.fromSeed(decoded.seed()).sigPublicKey()));
    }

    @Test
    void importingTwiceIsRefused() throws IOException {
        importIt();
        assertThrows(IllegalStateException.class, this::importIt);
    }

    @Test
    void joiningWithAnInviteRegistersTheWorldWithoutAnOwnerKey() throws IOException {
        Invite invite = importIt().invite();
        CoopWorldStore friend = new CoopWorldStore(temp.resolve("friend"));

        CoopWorld joined = friend.joinWithInvite(invite, "My World");
        assertEquals(store.list().get(0).worldId(), joined.worldId());
        assertFalse(friend.isOwner(joined.worldId()));
        assertEquals(Optional.empty(), friend.ownerKey(joined.worldId()));
        assertEquals("", joined.ownerKey());
        assertEquals(List.of(), joined.members());
        assertEquals(joined, friend.joinWithInvite(invite, "another name"), "joining again changes nothing");

        // once the world folder exists on the friend's PC it is attached to the same world
        Path copy = temp.resolve("friend-saves").resolve("My World");
        Files.createDirectories(copy);
        friend.attachFolder(copy, joined.worldId());
        assertEquals(Optional.of(joined), friend.forFolder(copy));
    }

    @Test
    void removeDeletesTheMarkerAndTheKeysAndLeavesTheWorld() throws IOException {
        CoopWorld imported = importIt();
        store.remove(imported.worldId(), world);

        assertFalse(Files.exists(world.resolve("dropin")));
        assertTrue(Files.exists(world.resolve("level.dat")));
        assertEquals(Optional.empty(), CoopWorldStore.readMarker(world));
        assertEquals(List.of(), store.list());
        assertFalse(store.isOwner(imported.worldId()));
        importIt();   // and it can be imported again
    }

    @Test
    void membersAreAddedOnceAndRefreshed() throws IOException {
        CoopWorld imported = importIt();
        store.recordMember(imported.worldId(), "uuid-sam", "Sam", 2000);
        store.recordMember(imported.worldId(), "uuid-sam", "Sammy", 3000);
        store.recordMember(imported.worldId(), "uuid-alex", "Alex", 3000);

        assertEquals(List.of(
                new Member("uuid-alex", "Alex", 1000, 3000, Member.OWNER),
                new Member("uuid-sam", "Sammy", 2000, 3000, Member.MEMBER)),
                store.get(imported.worldId()).orElseThrow().members());
    }

    @Test
    void aMarkerWithoutAKeyOnThisPcIsNotACoopWorldHere() throws IOException {
        CoopWorld imported = importIt();
        CoopWorldStore otherPc = new CoopWorldStore(temp.resolve("other"));
        assertEquals(Optional.of(imported.worldId()), CoopWorldStore.readMarker(world));
        assertEquals(Optional.empty(), otherPc.forFolder(world));
    }

    @Test
    void damagedFilesAreRefusedNotTrusted() throws IOException {
        CoopWorld imported = importIt();
        Path entry = config.resolve("worlds").resolve(imported.worldId() + ".json");
        String good = Files.readString(entry);

        // a seed that does not belong to the world id
        Files.writeString(entry, good.replace(imported.seed(), "00".repeat(32)));
        assertThrows(IOException.class, () -> store.get(imported.worldId()));
        assertEquals(List.of(), store.list());

        Files.writeString(entry, "{ not json");
        assertThrows(IOException.class, () -> store.get(imported.worldId()));

        Files.writeString(world.resolve("dropin").resolve("world.json"), "garbage");
        assertEquals(Optional.empty(), CoopWorldStore.readMarker(world));

        assertThrows(IllegalArgumentException.class, () -> store.get("../../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.isOwner("..\\x"));
    }
}
