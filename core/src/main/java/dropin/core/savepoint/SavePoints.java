package dropin.core.savepoint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dropin.core.manifest.Manifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The owner's save points of one world (DESIGN 4.16), under {@code dropin/backups/savepoints/}. A save point
 * is a list of record hashes; the records themselves are stored once each, compressed, however many save
 * points name them.
 */
public final class SavePoints {
    public static final int LIMIT = 10;
    public static final String AUTO_NAME = "before-restore";
    public static final String FILE_SUFFIX = ".dropinsave";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{32}");
    private static final long MAX_RECORD_BYTES = 256L * 1024 * 1024;

    /**
     * @param created seconds since 1970
     * @param seq     the checkpoint the world was at
     * @param auto    made by a restore, of the world as it was just before
     */
    public record Point(String id, String name, long created, long seq, String root, int records, boolean auto) {
    }

    /** Where the records of the world come from when a save point is made. */
    public interface Source {
        /** The record's bytes, or null if the world has no such record any more. */
        byte[] read(String key) throws IOException;
    }

    private final Path dir;

    public SavePoints(Path dir) {
        this.dir = dir;
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && !name.equalsIgnoreCase(AUTO_NAME);
    }

    private Path points() {
        return dir.resolve("points");
    }

    private Path object(String hash) {
        return dir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
    }

    /** Every save point, oldest first. */
    public synchronized List<Point> list() throws IOException {
        List<Point> all = new ArrayList<>();
        if (!Files.isDirectory(points())) {
            return all;
        }
        try (Stream<Path> files = Files.list(points())) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().endsWith(".json")) {
                    try {
                        all.add(parse(Files.readString(file, StandardCharsets.UTF_8)));
                    } catch (RuntimeException e) {
                        // Not one of ours, or half written: not a save point.
                    }
                }
            }
        }
        all.sort(Comparator.comparingLong(point -> Long.parseLong(point.id())));
        return all;
    }

    public synchronized Optional<Point> find(String name) throws IOException {
        for (Point point : list()) {
            if (point.name().equalsIgnoreCase(name)) {
                return Optional.of(point);
            }
        }
        return Optional.empty();
    }

    /**
     * Makes a save point of the world described by {@code world}. Records the store already holds are not read.
     *
     * @param auto true for the automatic save point of a restore: it replaces the previous automatic one
     */
    public synchronized Point create(String name, boolean auto, long seq, Manifest world, Source source) throws IOException {
        if (!auto) {
            if (!validName(name)) {
                throw new IOException("not a save point name: " + name);
            }
            if (find(name).isPresent()) {
                throw new IOException("a save point named " + name + " exists");
            }
        }
        Manifest stored = new Manifest();
        for (String key : world.keys()) {
            String hash = world.get(key);
            if (hash == null) {
                continue;
            }
            if (!Files.exists(object(hash))) {
                byte[] bytes = source.read(key);
                if (bytes == null) {
                    continue;
                }
                hash = Manifest.hash(bytes);
                writeObject(hash, bytes);
            }
            stored.put(key, hash);
        }
        if (auto) {
            for (Point old : list()) {
                if (old.auto()) {
                    remove(old);
                }
            }
        }
        Point point = store(auto ? AUTO_NAME : name, System.currentTimeMillis() / 1000, seq, auto, stored);
        List<Point> kept = list().stream().filter(p -> !p.auto()).toList();
        for (int i = 0; i < kept.size() - LIMIT; i++) {
            remove(kept.get(i));
        }
        collectGarbage();
        return point;
    }

    private Point store(String name, long created, long seq, boolean auto, Manifest stored) throws IOException {
        Files.createDirectories(points());
        long id = System.currentTimeMillis();
        while (Files.exists(points().resolve(id + ".json"))) {
            id++;
        }
        Point point = new Point(Long.toString(id), name, created, seq, stored.root(), stored.size(), auto);
        stored.save(points().resolve(id + ".tsv"));
        writeAtomically(points().resolve(id + ".json"), json(point).getBytes(StandardCharsets.UTF_8));
        return point;
    }

    /** The record hashes of a save point. */
    public synchronized Manifest manifest(Point point) throws IOException {
        return Manifest.load(points().resolve(point.id() + ".tsv"));
    }

    /** The bytes of a stored record. */
    public byte[] read(String hash) throws IOException {
        if (!HASH.matcher(hash).matches()) {
            throw new IOException("not a record hash: " + hash);
        }
        try (InputStream in = new InflaterInputStream(Files.newInputStream(object(hash)))) {
            return in.readAllBytes();
        }
    }

    public synchronized void delete(Point point) throws IOException {
        remove(point);
        collectGarbage();
    }

    private void remove(Point point) throws IOException {
        Files.deleteIfExists(points().resolve(point.id() + ".json"));
        Files.deleteIfExists(points().resolve(point.id() + ".tsv"));
    }

    /** Deletes the stored records that no save point names. */
    private void collectGarbage() throws IOException {
        Path objects = dir.resolve("objects");
        if (!Files.isDirectory(objects)) {
            return;
        }
        Set<String> named = new HashSet<>();
        for (Point point : list()) {
            Manifest manifest = manifest(point);
            for (String key : manifest.keys()) {
                named.add(manifest.get(key));
            }
        }
        try (Stream<Path> files = Files.walk(objects)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!named.contains(file.getFileName().toString())) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    // ---- export and import ------------------------------------------------------------------------

    /** Writes one save point, with every record it names, as a single file. */
    public synchronized void exportTo(Point point, String worldId, Path file) throws IOException {
        Manifest manifest = manifest(point);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
            entry(zip, "world.txt", worldId.getBytes(StandardCharsets.UTF_8));
            entry(zip, "point.json", json(point).getBytes(StandardCharsets.UTF_8));
            entry(zip, "point.tsv", Files.readAllBytes(points().resolve(point.id() + ".tsv")));
            Set<String> written = new HashSet<>();
            for (String key : manifest.keys()) {
                String hash = manifest.get(key);
                if (written.add(hash)) {
                    entry(zip, "objects/" + hash, read(hash));
                }
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** Stores an exported save point of the same world. Nothing is kept if the file is wrong or incomplete. */
    public synchronized Point importFrom(String worldId, Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            String world = new String(bytes(zip, "world.txt"), StandardCharsets.UTF_8).trim();
            if (!world.equals(worldId)) {
                throw new IOException("this save point is of another world");
            }
            Point exported;
            try {
                exported = parse(new String(bytes(zip, "point.json"), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new IOException("not a save point file", e);
            }
            if (!validName(exported.name())) {
                throw new IOException("not a save point name: " + exported.name());
            }
            if (find(exported.name()).isPresent()) {
                throw new IOException("a save point named " + exported.name() + " exists");
            }
            Files.createDirectories(dir);
            Path tsv = Files.createTempFile(dir, "import", ".tsv");
            try {
                Files.write(tsv, bytes(zip, "point.tsv"));
                Manifest manifest = Manifest.load(tsv);
                for (String key : manifest.keys()) {
                    String hash = manifest.get(key);
                    if (!HASH.matcher(hash).matches()) {
                        throw new IOException("not a record hash: " + hash);
                    }
                    if (Files.exists(object(hash))) {
                        continue;
                    }
                    byte[] record = bytes(zip, "objects/" + hash);
                    if (!Manifest.hash(record).equals(hash)) {
                        throw new IOException("record " + key + " of the file is damaged");
                    }
                    writeObject(hash, record);
                }
                Point point = store(exported.name(), exported.created(), exported.seq(), false, manifest);
                List<Point> kept = list().stream().filter(p -> !p.auto()).toList();
                for (int i = 0; i < kept.size() - LIMIT; i++) {
                    remove(kept.get(i));
                }
                return point;
            } finally {
                Files.deleteIfExists(tsv);
                collectGarbage();
            }
        }
    }

    private static byte[] bytes(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException("the file has no " + name);
        }
        if (entry.getSize() > MAX_RECORD_BYTES) {
            throw new IOException(name + " is too large");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, MAX_RECORD_BYTES + 1));
            if (bytes.length > MAX_RECORD_BYTES) {
                throw new IOException(name + " is too large");
            }
            return bytes;
        }
    }

    // ---- files --------------------------------------------------------------------------------------

    private void writeObject(String hash, byte[] bytes) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream(Math.max(64, bytes.length / 4));
        try (OutputStream out = new DeflaterOutputStream(packed)) {
            out.write(bytes);
        }
        writeAtomically(object(hash), packed.toByteArray());
    }

    private static void writeAtomically(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String json(Point point) {
        JsonObject json = new JsonObject();
        json.addProperty("id", point.id());
        json.addProperty("name", point.name());
        json.addProperty("created", point.created());
        json.addProperty("seq", point.seq());
        json.addProperty("root", point.root());
        json.addProperty("records", point.records());
        json.addProperty("auto", point.auto());
        return json.toString();
    }

    private static Point parse(String text) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        Point point = new Point(json.get("id").getAsString(), json.get("name").getAsString(), json.get("created").getAsLong(),
                json.get("seq").getAsLong(), json.get("root").getAsString(), json.get("records").getAsInt(),
                json.get("auto").getAsBoolean());
        Long.parseLong(point.id());
        return point;
    }
}
