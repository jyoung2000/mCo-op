package dropin.core.mods;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads a {@code mods/} folder (DESIGN 4.14): the {@code *.jar} files directly in it, with their hashes
 * and the mod id and version each jar declares. Subfolders such as {@code .dropin-disabled/} are not
 * looked into, exactly as the loaders do not load from them.
 */
public final class ModScan {
    private static final Pattern TOML_MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TOML_VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]*)\"");

    private ModScan() {
    }

    /** What a jar says about itself; empty strings when it does not say. */
    public record JarInfo(String modId, String version) {
        public static final JarInfo UNKNOWN = new JarInfo("", "");
    }

    /**
     * @param skipModIds mod ids that are never listed (DropIn itself: its version is matched by the sync protocol)
     * @param loader     "fabric" or "neoforge": which of a jar's two metadata files to prefer
     * @throws ModManifest.ModException if a jar's name cannot be used in a manifest
     */
    public static List<ModManifest.Entry> scan(Path modsDir, Set<String> skipModIds, String loader) throws IOException {
        List<ModManifest.Entry> found = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return found;
        }
        List<Path> jars;
        try (Stream<Path> files = Files.list(modsDir)) {
            jars = files.filter(Files::isRegularFile).filter(f -> {
                String name = f.getFileName().toString();
                return !name.startsWith(".") && name.toLowerCase(Locale.ROOT).endsWith(".jar");
            }).sorted(java.util.Comparator.comparing((Path f) -> f.getFileName().toString())).toList();
        }
        for (Path jar : jars) {
            JarInfo info = jarInfo(jar, loader);
            if (!info.modId().isEmpty() && skipModIds.contains(info.modId())) {
                continue;
            }
            String name = ModManifest.safeName(jar.getFileName().toString());
            String[] hashes = hashes(jar);
            found.add(new ModManifest.Entry(name, Files.size(jar), hashes[0], hashes[1], info.modId(), info.version(),
                    ModManifest.PEER, "", ""));
        }
        return found;
    }

    /** SHA-512 and SHA-1 of a file, hex, read once. */
    public static String[] hashes(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[65536];
            for (int n; (n = in.read(buffer)) > 0; ) {
                sha512.update(buffer, 0, n);
                sha1.update(buffer, 0, n);
            }
            return new String[]{HexFormat.of().formatHex(sha512.digest()), HexFormat.of().formatHex(sha1.digest())};
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** The mod id and version from {@code fabric.mod.json} or {@code META-INF/neoforge.mods.toml}. Never throws. */
    public static JarInfo jarInfo(Path jar, String loader) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            JarInfo fabric = fabricInfo(zip);
            JarInfo neoforge = neoforgeInfo(zip);
            if ("neoforge".equals(loader)) {
                return neoforge != null ? neoforge : fabric != null ? fabric : JarInfo.UNKNOWN;
            }
            return fabric != null ? fabric : neoforge != null ? neoforge : JarInfo.UNKNOWN;
        } catch (IOException | RuntimeException e) {
            return JarInfo.UNKNOWN;
        }
    }

    private static String text(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() > (1 << 20)) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8);
        }
    }

    private static JarInfo fabricInfo(ZipFile zip) throws IOException {
        String text = text(zip, "fabric.mod.json");
        if (text == null) {
            return null;
        }
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        return new JarInfo(clean(json.has("id") ? json.get("id").getAsString() : ""),
                clean(json.has("version") ? json.get("version").getAsString() : ""));
    }

    private static JarInfo neoforgeInfo(ZipFile zip) throws IOException {
        String text = text(zip, "META-INF/neoforge.mods.toml");
        if (text == null) {
            return null;
        }
        int mods = text.indexOf("[[mods]]");
        String section = mods < 0 ? text : text.substring(mods);
        Matcher id = TOML_MOD_ID.matcher(section);
        Matcher version = TOML_VERSION.matcher(section);
        String found = version.find() ? version.group(1) : "";
        // "${file.jarVersion}" is filled in by the loader from the jar's manifest; DropIn does not need it.
        return new JarInfo(clean(id.find() ? id.group(1) : ""), clean(found.startsWith("${") ? "" : found));
    }

    private static String clean(String value) {
        String printable = value.replaceAll("[\\p{Cntrl}]", "");
        return printable.length() > 100 ? printable.substring(0, 100) : printable;
    }
}
