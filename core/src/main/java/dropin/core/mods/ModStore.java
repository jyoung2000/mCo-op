package dropin.core.mods;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The content-addressed mod store of DESIGN 4.14: {@code config/dropin/modstore/<sha512>.jar}. A file only
 * gets its final name after its bytes were hashed and found equal to the SHA-512 of the owner-signed
 * manifest, so nothing that was tampered with on the way is ever under a name the swap helper uses.
 */
public final class ModStore {
    private final Path dir;

    public ModStore(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public Path file(String sha512) {
        if (!sha512.matches("[0-9a-f]{128}")) {
            throw new IllegalArgumentException("not a SHA-512");
        }
        return dir.resolve(sha512 + ".jar");
    }

    /** True if the store holds this file and its bytes still hash to its name. */
    public boolean has(String sha512) {
        Path file = file(sha512);
        try {
            return Files.isRegularFile(file) && SwapHelper.sha512(file).equals(sha512);
        } catch (IOException e) {
            return false;
        }
    }

    /** A download in progress: bytes are written to a temp file and only {@link #commit} can make it a store file. */
    public final class Incoming implements AutoCloseable {
        private final ModManifest.Entry entry;
        private final Path temp;
        private final OutputStream out;
        private long written;

        private Incoming(ModManifest.Entry entry) throws IOException {
            this.entry = entry;
            Files.createDirectories(dir);
            this.temp = Files.createTempFile(dir, "incoming", ".part");
            this.out = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING);
        }

        public Path temp() {
            return temp;
        }

        /** @throws IOException if more arrives than the manifest says the file has */
        public void write(byte[] bytes, int offset, int length) throws IOException {
            written += length;
            if (written > entry.size()) {
                throw new IOException(entry.path() + " is larger than the mod list says");
            }
            out.write(bytes, offset, length);
        }

        /** Checks size and SHA-512 against the manifest entry, then moves the file into the store. */
        public void commit() throws IOException {
            out.close();
            long size = Files.size(temp);
            if (size != entry.size() || !SwapHelper.sha512(temp).equals(entry.sha512())) {
                Files.deleteIfExists(temp);
                throw new IOException(entry.path() + " does not match the hash in the owner's mod list; it was thrown away");
            }
            Files.move(temp, file(entry.sha512()), StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void close() throws IOException {
            out.close();
            Files.deleteIfExists(temp);
        }
    }

    public Incoming receive(ModManifest.Entry entry) throws IOException {
        return new Incoming(entry);
    }

    /** Copies a local file into the store after checking it against the manifest entry. */
    public void put(Path source, ModManifest.Entry entry) throws IOException {
        try (Incoming incoming = receive(entry)) {
            incoming.out.close();
            Files.copy(source, incoming.temp, StandardCopyOption.REPLACE_EXISTING);
            incoming.commit();
        }
    }
}
