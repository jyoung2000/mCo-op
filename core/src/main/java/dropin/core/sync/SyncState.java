package dropin.core.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * {@code dropin/state.json} (DESIGN 4.2): how far this copy is.
 *
 * @param head          hash of the last ledger entry this copy has fully applied, "" if none
 * @param syncedThrough seq of that entry
 * @param complete      true once the copy matched a checkpoint in full at least once
 * @param have          records this copy holds of the download in progress
 * @param total         records the world had when the download was last measured
 */
public record SyncState(String head, long syncedThrough, boolean complete, int have, int total) {
    public static final SyncState EMPTY = new SyncState("", 0, false, 0, 0);

    public int percent() {
        return complete ? 100 : total <= 0 ? 0 : (int) Math.min(99, have * 100L / total);
    }

    /** {@link #EMPTY} if the file is missing or unreadable. */
    public static SyncState load(Path file) {
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return new SyncState(json.get("head").getAsString(), json.get("syncedThrough").getAsLong(),
                    json.get("complete").getAsBoolean(), json.has("have") ? json.get("have").getAsInt() : 0,
                    json.has("total") ? json.get("total").getAsInt() : 0);
        } catch (IOException | RuntimeException e) {
            return EMPTY;
        }
    }

    public void save(Path file) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("head", head);
        json.addProperty("syncedThrough", syncedThrough);
        json.addProperty("complete", complete);
        json.addProperty("have", have);
        json.addProperty("total", total);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
