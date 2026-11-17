package dropin.core.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dropin.core.crypto.Ed25519;
import dropin.core.crypto.WorldKeys;
import dropin.core.invite.Invite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The files of DESIGN 4.2 that M1 uses. Secrets live under the config folder, never in the world folder:
 *
 * <pre>
 * &lt;config&gt;/worlds/&lt;worldId&gt;.json        seed, display name, owner fingerprint/key, known members
 * &lt;config&gt;/worlds/&lt;worldId&gt;.owner.key   the owner's private key seed (hex), on the owner's PC only
 * &lt;world&gt;/dropin/world.json            worldId, format version
 * </pre>
 */
public final class CoopWorldStore {
    public static final int FORMAT = 1;
    public static final String MARKER_DIR = "dropin";
    private static final String MARKER_FILE = "world.json";
    private static final HexFormat HEX = HexFormat.of();

    private final Path worldsDir;

    /** @param configDir the mod's config folder ({@code config/dropin}) */
    public CoopWorldStore(Path configDir) {
        this.worldsDir = configDir.resolve("worlds");
    }

    // ---- marker in the world folder --------------------------------------------------------

    /** The world id in {@code <world>/dropin/world.json}, if the folder is marked as a co-op world. */
    public static Optional<String> readMarker(Path worldFolder) {
        Path file = worldFolder.resolve(MARKER_DIR).resolve(MARKER_FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return Optional.of(json.get("worldId").getAsString());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static void writeMarker(Path worldFolder, String worldId) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("worldId", worldId);
        json.addProperty("format", FORMAT);
        writeAtomically(worldFolder.resolve(MARKER_DIR).resolve(MARKER_FILE), json.toString());
    }

    // ---- registry ----------------------------------------------------------------------------

    public List<CoopWorld> list() throws IOException {
        if (!Files.isDirectory(worldsDir)) {
            return List.of();
        }
        List<CoopWorld> worlds = new ArrayList<>();
        try (Stream<Path> files = Files.list(worldsDir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    worlds.add(parse(Files.readString(file, StandardCharsets.UTF_8)));
                } catch (RuntimeException e) {
                    // an unreadable entry must not hide the others
                }
            }
        }
        worlds.sort(Comparator.comparing(CoopWorld::name, String.CASE_INSENSITIVE_ORDER));
        return worlds;
    }

    public Optional<CoopWorld> get(String worldId) throws IOException {
        Path file = entryFile(worldId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new IOException("unreadable world entry " + file.getFileName(), e);
        }
    }

    /** The co-op world a save folder belongs to, if it is marked and this PC has its key. */
    public Optional<CoopWorld> forFolder(Path worldFolder) throws IOException {
        Optional<String> worldId = readMarker(worldFolder);
        return worldId.isPresent() ? get(worldId.get()) : Optional.empty();
    }

    public boolean isOwner(String worldId) {
        return Files.isRegularFile(ownerKeyFile(worldId));
    }

    /** The owner's signing key, on the owner's PC only. */
    public Optional<KeyPair> ownerKey(String worldId) throws IOException {
        Path file = ownerKeyFile(worldId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Ed25519.keyPairFromSeed(HEX.parseHex(Files.readString(file, StandardCharsets.UTF_8).trim())));
        } catch (RuntimeException e) {
            throw new IOException("unreadable owner key for " + worldId, e);
        }
    }

    /** "Export owner key" (DESIGN 4.13): copies the owner key file to {@code target} for backup. */
    public void exportOwnerKey(String worldId, Path target) throws IOException {
        Path file = ownerKeyFile(worldId);
        if (!Files.isRegularFile(file)) {
            throw new IOException("no owner key for " + worldId + " on this PC");
        }
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    // ---- import, join, remove ----------------------------------------------------------------

    /**
     * Turns a world folder into a co-op world owned by this player (DESIGN 4.4 "Then", 4.13): a new seed,
     * a new owner key, the key files, and {@code dropin/world.json}. Adds nothing else to the world.
     *
     * @throws IOException           if a file cannot be written
     * @throws IllegalStateException if the folder is already marked as a co-op world
     */
    public CoopWorld importWorld(Path worldFolder, String name, String ownerUuid, String ownerName, long nowSeconds,
                                 SecureRandom random) throws IOException {
        if (readMarker(worldFolder).isPresent()) {
            throw new IllegalStateException("already a co-op world");
        }
        byte[] seed = new byte[32];
        byte[] ownerSeed = new byte[32];
        random.nextBytes(seed);
        random.nextBytes(ownerSeed);
        byte[] ownerPublic = Ed25519.publicKeyBytes(Ed25519.keyPairFromSeed(ownerSeed).getPublic());
        CoopWorld world = new CoopWorld(WorldKeys.fromSeed(seed).worldId(), HEX.formatHex(seed), name,
                HEX.formatHex(Invite.fingerprint(ownerPublic)), HEX.formatHex(ownerPublic),
                List.of(new Member(ownerUuid, ownerName, nowSeconds, nowSeconds, Member.OWNER)));
        writeAtomically(ownerKeyFile(world.worldId()), HEX.formatHex(ownerSeed));
        save(world);
        writeMarker(worldFolder, world.worldId());
        return world;
    }

    /** Registers a world from an invite. There is no world folder yet and no owner key on this PC. */
    public CoopWorld joinWithInvite(Invite invite, String name) throws IOException {
        String worldId = WorldKeys.fromSeed(invite.seed()).worldId();
        Optional<CoopWorld> known = get(worldId);
        if (known.isPresent()) {
            return known.get();
        }
        CoopWorld world = new CoopWorld(worldId, HEX.formatHex(invite.seed()), name,
                HEX.formatHex(invite.ownerFingerprint()), "", List.of());
        save(world);
        return world;
    }

    /** Marks a folder that holds a copy of a world this PC already has the key for. */
    public void attachFolder(Path worldFolder, String worldId) throws IOException {
        writeMarker(worldFolder, worldId);
    }

    /**
     * Stops treating the world as co-op on this PC (DESIGN 4.4 "Remove"): deletes {@code dropin/} in the world
     * folder (when given) and the local key files. The world itself is left alone.
     */
    public void remove(String worldId, Path worldFolder) throws IOException {
        if (worldFolder != null) {
            deleteTree(worldFolder.resolve(MARKER_DIR));
        }
        Files.deleteIfExists(entryFile(worldId));
        Files.deleteIfExists(ownerKeyFile(worldId));
    }

    /** Adds a player to the known members, or refreshes their name and last-seen time. */
    public CoopWorld recordMember(String worldId, String uuid, String name, long nowSeconds) throws IOException {
        CoopWorld world = get(worldId).orElseThrow(() -> new IOException("unknown world " + worldId));
        List<Member> members = new ArrayList<>();
        boolean found = false;
        for (Member member : world.members()) {
            if (member.uuid().equals(uuid)) {
                members.add(new Member(uuid, name, member.firstSeen(), Math.max(member.lastSeen(), nowSeconds), member.role()));
                found = true;
            } else {
                members.add(member);
            }
        }
        if (!found) {
            members.add(new Member(uuid, name, nowSeconds, nowSeconds, Member.MEMBER));
        }
        CoopWorld updated = world.withMembers(members);
        save(updated);
        return updated;
    }

    // ---- files -------------------------------------------------------------------------------

    private Path entryFile(String worldId) {
        return worldsDir.resolve(safe(worldId) + ".json");
    }

    private Path ownerKeyFile(String worldId) {
        return worldsDir.resolve(safe(worldId) + ".owner.key");
    }

    /** World ids come from files and invites; never let one become a path. */
    private static String safe(String worldId) {
        if (!worldId.matches("[a-z0-9]{52}")) {
            throw new IllegalArgumentException("not a world id");
        }
        return worldId;
    }

    public void save(CoopWorld world) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("worldId", world.worldId());
        json.addProperty("seed", world.seed());
        json.addProperty("name", world.name());
        json.addProperty("ownerFingerprint", world.ownerFingerprint());
        if (!world.ownerKey().isEmpty()) {
            json.addProperty("ownerKey", world.ownerKey());
        }
        JsonArray members = new JsonArray();
        for (Member member : world.members()) {
            JsonObject m = new JsonObject();
            m.addProperty("uuid", member.uuid());
            m.addProperty("name", member.name());
            m.addProperty("firstSeen", member.firstSeen());
            m.addProperty("lastSeen", member.lastSeen());
            m.addProperty("role", member.role());
            members.add(m);
        }
        json.add("members", members);
        writeAtomically(entryFile(world.worldId()), json.toString());
    }

    private static CoopWorld parse(String text) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        List<Member> members = new ArrayList<>();
        if (json.has("members")) {
            for (JsonElement element : json.getAsJsonArray("members")) {
                JsonObject m = element.getAsJsonObject();
                members.add(new Member(m.get("uuid").getAsString(), m.get("name").getAsString(),
                        m.get("firstSeen").getAsLong(), m.get("lastSeen").getAsLong(), m.get("role").getAsString()));
            }
        }
        CoopWorld world = new CoopWorld(safe(json.get("worldId").getAsString()), json.get("seed").getAsString(),
                json.get("name").getAsString(), json.get("ownerFingerprint").getAsString(),
                json.has("ownerKey") ? json.get("ownerKey").getAsString() : "", members);
        if (!world.keys().worldId().equals(world.worldId())) {
            throw new IllegalArgumentException("seed does not belong to this world id");
        }
        return world;
    }

    private static void writeAtomically(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "dropin", ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
