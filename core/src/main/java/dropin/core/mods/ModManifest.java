package dropin.core.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The mod list of a co-op world (DESIGN 4.14): the {@code *.jar} files directly under the owner's
 * {@code mods/} folder, sorted by file name. Its hash is part of the owner-signed policy, so a manifest
 * is trusted exactly when it hashes to the value in a valid policy.
 */
public final class ModManifest {
    public static final String FILE = "modmanifest.json";
    public static final String PEER = "peer";
    public static final String MODRINTH = "modrinth";
    public static final ModManifest EMPTY = new ModManifest(List.of());

    /**
     * @param path      the file name in {@code mods/}
     * @param source    {@link #MODRINTH} when Modrinth's hash lookup knows the file, otherwise {@link #PEER}
     * @param versionId Modrinth version id, empty for peer files
     * @param url       Modrinth download URL, empty for peer files
     */
    public record Entry(String path, long size, String sha512, String sha1, String modId, String version, String source,
                        String versionId, String url) {
        public Entry withSource(String newSource, String newVersionId, String newUrl) {
            return new Entry(path, size, sha512, sha1, modId, version, newSource, newVersionId, newUrl);
        }

        /** What the Mods tab shows: the mod id when the jar names one, else the file name. */
        public String displayName() {
            return modId.isEmpty() ? path : modId;
        }
    }

    /** Size caps of DESIGN 4.14 "Safety" ({@code config/dropin/coop.json}: modMaxFileMB, modMaxTotalMB, modMaxFiles). */
    public record Limits(long maxFileBytes, long maxTotalBytes, int maxFiles) {
        public static final Limits DEFAULT = new Limits(512L << 20, 8L << 30, 1000);
    }

    public static final class ModException extends IOException {
        public ModException(String message) {
            super(message);
        }
    }

    private final List<Entry> files;

    public ModManifest(List<Entry> files) {
        List<Entry> sorted = new ArrayList<>(files);
        sorted.sort(Comparator.comparing(Entry::path));
        this.files = List.copyOf(sorted);
    }

    public List<Entry> files() {
        return files;
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public long totalBytes() {
        return files.stream().mapToLong(Entry::size).sum();
    }

    public Entry bySha512(String sha512) {
        return files.stream().filter(f -> f.sha512().equals(sha512)).findFirst().orElse(null);
    }

    /** One line per file, fields separated by tabs, in file-name order. */
    public byte[] canonicalBytes() {
        StringBuilder out = new StringBuilder("dropin-mods 1\n");
        for (Entry f : files) {
            out.append(String.join("\t", f.path(), Long.toString(f.size()), f.sha512(), f.sha1(), f.modId(), f.version(),
                    f.source(), f.versionId(), f.url())).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** {@code modManifestHash}: SHA-256 of the canonical bytes, hex. */
    public String hash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Refuses a manifest that could harm the PC that applies it: an unsafe file name (a path, {@code ..}),
     * two files with the same name, a malformed hash or field, a download URL that is not https, or more
     * data than the limits allow.
     */
    public void validate(Limits limits) throws ModException {
        if (files.size() > limits.maxFiles()) {
            throw new ModException("the mod list has " + files.size() + " files, more than the limit of " + limits.maxFiles());
        }
        Set<String> names = new HashSet<>();
        long total = 0;
        for (Entry f : files) {
            safeName(f.path());
            if (!names.add(f.path().toLowerCase(Locale.ROOT))) {
                throw new ModException("the mod list names " + f.path() + " twice");
            }
            if (!f.sha512().matches("[0-9a-f]{128}") || !f.sha1().matches("[0-9a-f]{40}")) {
                throw new ModException("the mod list has a malformed hash for " + f.path());
            }
            for (String field : List.of(f.modId(), f.version(), f.source(), f.versionId(), f.url())) {
                if (field.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                    throw new ModException("the mod list has a control character in the entry of " + f.path());
                }
            }
            if (f.source().equals(MODRINTH) ? !f.url().startsWith("https://") : !f.source().equals(PEER) || !f.url().isEmpty()) {
                throw new ModException("the mod list has a bad source for " + f.path());
            }
            if (f.size() < 0 || f.size() > limits.maxFileBytes()) {
                throw new ModException(f.path() + " is larger than the limit of " + (limits.maxFileBytes() >> 20) + " MB");
            }
            total += f.size();
            if (total > limits.maxTotalBytes()) {
                throw new ModException("the mod list is larger than the limit of " + (limits.maxTotalBytes() >> 20) + " MB");
            }
        }
    }

    /** A plain {@code *.jar} file name, usable directly under {@code mods/}: no separators, no {@code ..}. */
    public static String safeName(String name) throws ModException {
        try {
            return SwapHelper.checkedName(name);
        } catch (IOException e) {
            throw new ModException(e.getMessage());
        }
    }

    /** The same list with {@code source} set from Modrinth's answer (sha512 to hit); other files stay peer files. */
    public ModManifest withSources(Map<String, ModrinthClient.Hit> hits) {
        List<Entry> updated = new ArrayList<>();
        for (Entry f : files) {
            ModrinthClient.Hit hit = hits.get(f.sha512());
            updated.add(hit != null && hit.size() == f.size() && hit.url().startsWith("https://")
                    ? f.withSource(MODRINTH, hit.versionId(), hit.url()) : f.withSource(PEER, "", ""));
        }
        return new ModManifest(updated);
    }

    public String toJson() {
        JsonArray array = new JsonArray();
        for (Entry f : files) {
            JsonObject json = new JsonObject();
            json.addProperty("path", f.path());
            json.addProperty("size", f.size());
            json.addProperty("sha512", f.sha512());
            json.addProperty("sha1", f.sha1());
            json.addProperty("modId", f.modId());
            json.addProperty("version", f.version());
            json.addProperty("source", f.source());
            if (f.source().equals(MODRINTH)) {
                json.addProperty("versionId", f.versionId());
                json.addProperty("url", f.url());
            }
            array.add(json);
        }
        JsonObject root = new JsonObject();
        root.add("files", array);
        return root.toString();
    }

    /** @throws IllegalArgumentException if the text is not a mod manifest */
    public static ModManifest fromJson(String text) {
        try {
            List<Entry> files = new ArrayList<>();
            for (JsonElement element : JsonParser.parseString(text).getAsJsonObject().getAsJsonArray("files")) {
                JsonObject json = element.getAsJsonObject();
                files.add(new Entry(json.get("path").getAsString(), json.get("size").getAsLong(),
                        json.get("sha512").getAsString(), json.get("sha1").getAsString(), json.get("modId").getAsString(),
                        json.get("version").getAsString(), json.get("source").getAsString(),
                        json.has("versionId") ? json.get("versionId").getAsString() : "",
                        json.has("url") ? json.get("url").getAsString() : ""));
            }
            return new ModManifest(files);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a mod manifest", e);
        }
    }

    /** The manifest in {@code file}, or null if there is none or it cannot be read. */
    public static ModManifest load(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
