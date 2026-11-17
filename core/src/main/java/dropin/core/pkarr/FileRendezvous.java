package dropin.core.pkarr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * A rendezvous kept in one plain JSON file. For self-tests and for players who share a folder;
 * nothing is encrypted and the last writer wins.
 */
public final class FileRendezvous implements Rendezvous {
    private final Path file;

    public FileRendezvous(Path file) {
        this.file = file;
    }

    @Override
    public Optional<RendezvousRecord> lookup() throws IOException {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(RendezvousRecord.fromJson(text));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IllegalArgumentException e) {
            throw new IOException("unreadable rendezvous file " + file, e);
        }
    }

    @Override
    public void publish(RendezvousRecord record) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "rendezvous", ".tmp");
        Files.writeString(temp, record.toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
