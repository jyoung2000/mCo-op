package dropin.core.policy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dropin.core.crypto.Ed25519;
import dropin.core.invite.Invite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The owner-signed world policy of DESIGN 4.13: one record that says which mods and datapacks the world
 * needs, who is banned and whether members may use commands. Only the owner's key can make a valid one,
 * and a higher {@link #version} always wins, so any peer may carry it.
 *
 * @param ownerKey         hex of the owner's raw 32-byte Ed25519 public key
 * @param loader           the owner's mod loader ("fabric" or "neoforge"); binding only when the mod manifest is not empty
 * @param modManifestHash  {@code ModManifest.hash()} of the mod list this policy belongs to
 * @param datapackFiles    path (relative to {@code datapacks/}, '/' separated) to SHA-256 hex of every file there
 * @param datapacksEnabled the world's enabled world-datapacks ({@code file/...} entries of level.dat), in order
 * @param banned           UUIDs that hosts refuse
 * @param admins           UUIDs the owner made admins: operator rights in every session (DESIGN 4.13)
 * @param ts               epoch seconds when it was signed
 * @param sig              hex of the Ed25519 signature over {@link #canonicalBytes()}
 */
public record Policy(long version, String ownerKey, String ownerUuid, String ownerName, String loader, String minecraftVersion,
                     String modManifestHash, Map<String, String> datapackFiles, List<String> datapacksEnabled,
                     List<String> banned, List<String> admins, boolean membersMayUseCommands, long ts, String sig) {
    public static final String FILE = "policy.json";
    private static final HexFormat HEX = HexFormat.of();

    public Policy {
        datapackFiles = java.util.Collections.unmodifiableMap(new TreeMap<>(datapackFiles));
        datapacksEnabled = List.copyOf(datapacksEnabled);
        List<String> sorted = new ArrayList<>(banned);
        java.util.Collections.sort(sorted);
        banned = List.copyOf(sorted);
        List<String> sortedAdmins = new ArrayList<>(new java.util.TreeSet<>(admins));
        admins = List.copyOf(sortedAdmins);
    }

    /** A policy without admins. */
    public Policy(long version, String ownerKey, String ownerUuid, String ownerName, String loader, String minecraftVersion,
                  String modManifestHash, Map<String, String> datapackFiles, List<String> datapacksEnabled,
                  List<String> banned, boolean membersMayUseCommands, long ts, String sig) {
        this(version, ownerKey, ownerUuid, ownerName, loader, minecraftVersion, modManifestHash, datapackFiles, datapacksEnabled,
                banned, List.of(), membersMayUseCommands, ts, sig);
    }

    /** The bytes the owner signs: one line per field in a fixed order, lists sorted or in their given order. */
    public byte[] canonicalBytes() {
        StringBuilder out = new StringBuilder("dropin-policy 1\n");
        line(out, "version", Long.toString(version));
        line(out, "ownerKey", ownerKey);
        line(out, "ownerUuid", ownerUuid);
        line(out, "ownerName", ownerName);
        line(out, "loader", loader);
        line(out, "minecraftVersion", minecraftVersion);
        line(out, "modManifestHash", modManifestHash);
        datapackFiles.forEach((path, hash) -> line(out, "datapack", hash + " " + path));
        datapacksEnabled.forEach(name -> line(out, "enabled", name));
        banned.forEach(uuid -> line(out, "banned", uuid));
        // No line when there are none, so a policy signed before admins existed still verifies.
        admins.forEach(uuid -> line(out, "admin", uuid));
        line(out, "membersMayUseCommands", Boolean.toString(membersMayUseCommands));
        line(out, "ts", Long.toString(ts));
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder out, String key, String value) {
        if (value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            throw new IllegalArgumentException("control character in policy field " + key);
        }
        out.append(key).append(' ').append(value).append('\n');
    }

    /** Signs the fields with the owner's key; {@code ownerKey} and {@code sig} of the argument are replaced. */
    public static Policy sign(Policy unsigned, java.security.KeyPair ownerKeyPair) {
        String ownerKey = HEX.formatHex(Ed25519.publicKeyBytes(ownerKeyPair.getPublic()));
        Policy body = unsigned.with(ownerKey, "");
        PrivateKey key = ownerKeyPair.getPrivate();
        return body.with(ownerKey, HEX.formatHex(Ed25519.sign(key, body.canonicalBytes())));
    }

    private Policy with(String newOwnerKey, String newSig) {
        return new Policy(version, newOwnerKey, ownerUuid, ownerName, loader, minecraftVersion, modManifestHash,
                datapackFiles, datapacksEnabled, banned, admins, membersMayUseCommands, ts, newSig);
    }

    /**
     * Why this policy must not be trusted, or null if it is valid for a world whose invite carries
     * {@code ownerFingerprint}: the key must be the one the invite names and the signature must verify.
     */
    public String rejection(byte[] ownerFingerprint) {
        byte[] key;
        byte[] signature;
        try {
            key = HEX.parseHex(ownerKey);
            signature = HEX.parseHex(sig);
        } catch (IllegalArgumentException e) {
            return "the policy's key or signature is not hex";
        }
        if (version < 1) {
            return "the policy has no version";
        }
        if (key.length != Ed25519.PUBLIC_KEY_LEN || !Arrays.equals(Invite.fingerprint(key), ownerFingerprint)) {
            return "the policy is not signed by this world's owner";
        }
        byte[] message;
        try {
            message = canonicalBytes();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (signature.length != Ed25519.SIGNATURE_LEN || !Ed25519.verify(key, message, signature)) {
            return "the policy's signature does not verify";
        }
        return null;
    }

    /** True if {@code candidate} should take the place of {@code current}: versions only go up. */
    public static boolean replaces(Policy candidate, Policy current) {
        return candidate != null && (current == null || candidate.version() > current.version());
    }

    public boolean isBanned(String uuid) {
        return banned.contains(uuid);
    }

    public boolean isOwner(String uuid) {
        return ownerUuid.equals(uuid);
    }

    /** True for a player the owner made an admin. A ban wins over it. */
    public boolean isAdmin(String uuid) {
        return admins.contains(uuid) && !banned.contains(uuid);
    }

    public String toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("version", version);
        json.addProperty("ownerKey", ownerKey);
        json.addProperty("ownerUuid", ownerUuid);
        json.addProperty("ownerName", ownerName);
        json.addProperty("loader", loader);
        json.addProperty("minecraftVersion", minecraftVersion);
        json.addProperty("modManifestHash", modManifestHash);
        JsonObject datapacks = new JsonObject();
        JsonObject files = new JsonObject();
        datapackFiles.forEach(files::addProperty);
        datapacks.add("files", files);
        JsonArray enabled = new JsonArray();
        datapacksEnabled.forEach(enabled::add);
        datapacks.add("enabled", enabled);
        json.add("datapackHashes", datapacks);
        JsonArray bans = new JsonArray();
        banned.forEach(bans::add);
        json.add("banned", bans);
        if (!admins.isEmpty()) {
            JsonArray list = new JsonArray();
            admins.forEach(list::add);
            json.add("admins", list);
        }
        json.addProperty("membersMayUseCommands", membersMayUseCommands);
        json.addProperty("ts", ts);
        json.addProperty("sig", sig);
        return json.toString();
    }

    /** @throws IllegalArgumentException if the text is not a policy record */
    public static Policy fromJson(String text) {
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            JsonObject datapacks = json.getAsJsonObject("datapackHashes");
            Map<String, String> files = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : datapacks.getAsJsonObject("files").entrySet()) {
                files.put(entry.getKey(), entry.getValue().getAsString());
            }
            List<String> enabled = new ArrayList<>();
            datapacks.getAsJsonArray("enabled").forEach(e -> enabled.add(e.getAsString()));
            List<String> banned = new ArrayList<>();
            json.getAsJsonArray("banned").forEach(e -> banned.add(e.getAsString()));
            List<String> admins = new ArrayList<>();
            if (json.has("admins")) {
                json.getAsJsonArray("admins").forEach(e -> admins.add(e.getAsString()));
            }
            return new Policy(json.get("version").getAsLong(), json.get("ownerKey").getAsString(),
                    json.get("ownerUuid").getAsString(), json.get("ownerName").getAsString(), json.get("loader").getAsString(),
                    json.get("minecraftVersion").getAsString(), json.get("modManifestHash").getAsString(), files, enabled,
                    banned, admins, json.get("membersMayUseCommands").getAsBoolean(), json.get("ts").getAsLong(),
                    json.get("sig").getAsString());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a policy record", e);
        }
    }

    /** The policy in {@code file}, or null if there is none or it cannot be read. */
    public static Policy load(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public void save(Path file) throws IOException {
        writeAtomically(file, toJson());
    }

    public static void writeAtomically(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "dropin", ".tmp");
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
