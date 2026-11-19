package dropin.core.merge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.merge.Branches.Change;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code dropin/branches/} of one copy (DESIGN 4.2, 4.10): the preserved branches that wait to be merged, one
 * folder per branch, named by the hash of the branch's last checkpoint:
 * <pre>
 * branches/&lt;head&gt;/branch.json           {"head", "ancestor", "entry"}; written last, so its presence means "complete"
 * branches/&lt;head&gt;/keys.tsv              key TAB hash-on-the-branch TAB hash-at-the-ancestor ("" = no record)
 * branches/&lt;head&gt;/records/&lt;hash&gt;.bin    the bytes of every record the branch changed
 * </pre>
 * A branch that is still being written or received lives in {@code <head>.part}. Thread-safe.
 */
public final class BranchStore {
    /**
     * @param head     hash of the branch's last checkpoint
     * @param ancestor hash of the last checkpoint the branch shares with the main world
     * @param entry    the branch's last checkpoint (who played, and when)
     * @param keys     see {@link Branches#bundle}
     */
    /** Stands for a content hash at the ancestor that the copy did not know, in {@code keys.tsv} and on the wire. */
    public static final String UNKNOWN = "?";

    public record Branch(String head, String ancestor, LedgerEntry entry, Map<String, Change> keys) {
    }

    private final Path dir;

    public BranchStore(Path dir) {
        this.dir = dir;
    }

    public static boolean validHead(String head) {
        return head != null && head.matches("[0-9a-f]{64}");
    }

    private static boolean validHash(String hash) {
        return hash.matches("[0-9a-f]{32}");
    }

    /** The heads of the complete branches held here, in name order. */
    public synchronized List<String> heads() throws IOException {
        List<String> heads = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> list = Files.list(dir)) {
                for (Path folder : list.sorted().toList()) {
                    String name = folder.getFileName().toString();
                    if (validHead(name) && Files.isRegularFile(folder.resolve("branch.json"))) {
                        heads.add(name);
                    }
                }
            }
        }
        return heads;
    }

    public synchronized boolean has(String head) {
        return validHead(head) && Files.isRegularFile(dir.resolve(head).resolve("branch.json"));
    }

    public synchronized Branch load(String head) throws IOException {
        if (!has(head)) {
            throw new IOException("No branch " + head);
        }
        Path folder = dir.resolve(head);
        try {
            JsonObject json = JsonParser.parseString(Files.readString(folder.resolve("branch.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Change> keys = new LinkedHashMap<>();
            for (String line : Files.readAllLines(folder.resolve("keys.tsv"), StandardCharsets.UTF_8)) {
                String[] columns = line.split("\t", -1);
                if (columns.length == 5 && !columns[0].isEmpty()) {
                    keys.put(columns[0], new Change(columns[1], columns[2], columns[3], columns[4].equals(UNKNOWN) ? null : columns[4]));
                }
            }
            return new Branch(head, json.get("ancestor").getAsString(), LedgerEntry.fromJson(json.get("entry").getAsString()), keys);
        } catch (RuntimeException e) {
            throw new IOException("Branch " + head + " is damaged", e);
        }
    }

    /** The bytes of a record of a branch, verified against their hash. */
    public synchronized byte[] record(String head, String hash) throws IOException {
        if (!validHead(head) || !validHash(hash)) {
            throw new IOException("No such branch record");
        }
        byte[] bytes = Files.readAllBytes(dir.resolve(head).resolve("records").resolve(hash + ".bin"));
        if (!Manifest.hash(bytes).equals(hash)) {
            throw new IOException("Record " + hash + " of branch " + head + " is damaged");
        }
        return bytes;
    }

    public synchronized void delete(String head) throws IOException {
        if (validHead(head)) {
            deleteTree(dir.resolve(head));
            deleteTree(dir.resolve(head + ".part"));
        }
    }

    /**
     * Starts writing a branch. Nothing of it counts until {@link Writer#finish()}.
     *
     * @throws IOException if {@code entry} is not the checkpoint {@code head} names
     */
    public synchronized Writer begin(String head, String ancestor, LedgerEntry entry) throws IOException {
        if (!validHead(head) || !entry.hash().equals(head) || !(ancestor.isEmpty() || validHead(ancestor))) {
            throw new IOException("Refused branch " + head);
        }
        Path part = dir.resolve(head + ".part");
        deleteTree(part);
        Files.createDirectories(part.resolve("records"));
        return new Writer(head, ancestor, entry, part);
    }

    /** One branch being written. Not thread-safe. */
    public final class Writer {
        private final String head;
        private final String ancestor;
        private final LedgerEntry entry;
        private final Path part;
        private final Map<String, Change> keys = new LinkedHashMap<>();
        private final Set<String> needed = new HashSet<>();
        private final Set<String> written = new HashSet<>();

        private Writer(String head, String ancestor, LedgerEntry entry, Path part) {
            this.head = head;
            this.ancestor = ancestor;
            this.entry = entry;
            this.part = part;
        }

        public String head() {
            return head;
        }

        /** @throws IOException if a hash is not a record hash */
        public void keys(Map<String, Change> more) throws IOException {
            for (Map.Entry<String, Change> one : more.entrySet()) {
                Change change = one.getValue();
                if (one.getKey().indexOf('\t') >= 0 || one.getKey().indexOf('\n') >= 0
                        || !(change.hash().isEmpty() || validHash(change.hash()))
                        || !(change.base().isEmpty() || validHash(change.base()))
                        || change.content() == null || !(change.content().isEmpty() || validHash(change.content()))
                        || !(change.contentBase() == null || change.contentBase().isEmpty() || validHash(change.contentBase()))) {
                    throw new IOException("Refused branch key " + one.getKey());
                }
                keys.put(one.getKey(), change);
                if (change.changed() && !change.hash().isEmpty()) {
                    needed.add(change.hash());
                }
            }
        }

        /** True if the branch needs a record with this hash and does not have it yet. */
        public boolean wants(String hash) {
            return needed.contains(hash) && !written.contains(hash);
        }

        /** @throws IOException if the bytes are not a record this branch lists */
        public void record(byte[] bytes) throws IOException {
            String hash = Manifest.hash(bytes);
            if (!needed.contains(hash)) {
                throw new IOException("Branch " + head + " does not list a record with hash " + hash);
            }
            if (written.add(hash)) {
                Files.write(part.resolve("records").resolve(hash + ".bin"), bytes);
            }
        }

        /** @throws IOException if a record the branch lists is missing */
        public void finish() throws IOException {
            if (!written.containsAll(needed)) {
                throw new IOException("Branch " + head + " is incomplete: " + (needed.size() - written.size()) + " records missing");
            }
            StringBuilder text = new StringBuilder();
            keys.forEach((key, change) -> text.append(key).append('\t').append(change.hash()).append('\t').append(change.base())
                    .append('\t').append(change.content()).append('\t')
                    .append(change.contentBase() == null ? UNKNOWN : change.contentBase()).append('\n'));
            Files.writeString(part.resolve("keys.tsv"), text, StandardCharsets.UTF_8);
            JsonObject json = new JsonObject();
            json.addProperty("head", head);
            json.addProperty("ancestor", ancestor);
            json.addProperty("entry", entry.toJson());
            Files.writeString(part.resolve("branch.json"), json.toString(), StandardCharsets.UTF_8);
            synchronized (BranchStore.this) {
                Path folder = dir.resolve(head);
                deleteTree(folder);
                Files.move(part, folder);
            }
        }

        public void abort() {
            try {
                deleteTree(part);
            } catch (IOException e) {
                // Left behind; the next begin() of this branch removes it.
            }
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path folder, IOException failure) throws IOException {
                Files.delete(folder);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
