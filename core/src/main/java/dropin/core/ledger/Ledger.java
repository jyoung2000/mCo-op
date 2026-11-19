package dropin.core.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The hash chain of checkpoints of one copy of a world (DESIGN 4.7). Every entry is MAC'd with the
 * world's auth key and chained to its parent; an entry that does not verify cannot be appended.
 * Thread-safe.
 */
public final class Ledger {
    public enum Relation { EQUAL, AHEAD, BEHIND, DIVERGED }

    /** @param commonAncestor hash of the last shared entry, "" if the two chains share nothing */
    public record Comparison(Relation relation, String commonAncestor) {
    }

    private final byte[] auth;
    private final Path file;
    private final List<LedgerEntry> entries = new ArrayList<>();
    private final Set<String> hashes = new HashSet<>();
    private String head = "";

    /** @param file {@code ledger.jsonl}, or null for a ledger that lives in memory only */
    public Ledger(byte[] auth, Path file) {
        this.auth = auth.clone();
        this.file = file;
    }

    /**
     * Reads {@code ledger.jsonl}. A missing file is an empty ledger; a broken chain is an error, except for a
     * last line with no line end that does not parse or verify: that is an append cut short (power lost, the
     * game killed), so it is dropped and the file is rewritten without it. The checkpoint it would have been
     * is simply not there, as if the session had stopped just before it.
     */
    public static Ledger load(byte[] auth, Path file) throws IOException {
        Ledger ledger = new Ledger(auth, null);
        boolean torn = false;
        if (Files.isRegularFile(file)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            // After the last "\n", split leaves "" for a complete file, or the unfinished last line.
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.isBlank()) {
                    continue;
                }
                boolean unfinished = i == lines.length - 1;
                try {
                    ledger.append(LedgerEntry.fromJson(line.strip()));
                } catch (RuntimeException e) {
                    if (unfinished) {
                        torn = true;
                        break;
                    }
                    throw new IOException("Ledger " + file + " is damaged at entry " + (ledger.size() + 1), e);
                }
                if (unfinished) {
                    // A complete entry whose line end was lost: keep it, and write the line end back.
                    torn = true;
                }
            }
        }
        Ledger loaded = new Ledger(auth, file);
        loaded.entries.addAll(ledger.entries);
        loaded.hashes.addAll(ledger.hashes);
        loaded.head = ledger.head;
        if (torn) {
            loaded.rewrite(loaded.entries);
        }
        return loaded;
    }

    private void rewrite(List<LedgerEntry> kept) throws IOException {
        StringBuilder text = new StringBuilder();
        for (LedgerEntry entry : kept) {
            text.append(entry.toJson()).append('\n');
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Why the entry cannot follow the current head, or null if it can. */
    public synchronized String rejection(LedgerEntry entry) {
        return rejection(entry, head, headSeq(), auth);
    }

    /** Why {@code entry} cannot follow an entry with the given hash and seq, or null if it can. */
    public static String rejection(LedgerEntry entry, String parentHash, long parentSeq, byte[] auth) {
        if (!entry.verify(auth)) {
            return "bad MAC";
        }
        if (!entry.parent().equals(parentHash)) {
            return "does not follow the head";
        }
        if (entry.seq() != parentSeq + 1) {
            return "wrong sequence number";
        }
        return null;
    }

    /** @throws IllegalArgumentException if the entry does not verify or does not follow the head */
    public synchronized void append(LedgerEntry entry) throws IOException {
        String problem = rejection(entry);
        if (problem != null) {
            throw new IllegalArgumentException("Ledger entry " + entry.seq() + " rejected: " + problem);
        }
        if (file != null) {
            Files.createDirectories(file.getParent());
            // Forced to disk, so a checkpoint the game reported is not lost with the power; a cut-short append
            // is dropped by load().
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap((entry.toJson() + "\n").getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
        }
        entries.add(entry);
        head = entry.hash();
        hashes.add(head);
    }

    /** Hash of the last entry, "" when empty. */
    public synchronized String head() {
        return head;
    }

    public synchronized long headSeq() {
        return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).seq();
    }

    public synchronized LedgerEntry last() {
        return entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean contains(String hash) {
        return hash.isEmpty() || hashes.contains(hash);
    }

    /**
     * True if this ledger holds an entry whose hash starts with {@code prefix}: the short head a host announces
     * (DESIGN 4.3). A copy whose ledger does not know the announced head is behind that session (DESIGN 4.4).
     * An empty prefix (nothing announced) is always known.
     */
    public synchronized boolean knows(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return true;
        }
        for (String hash : hashes) {
            if (hash.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public synchronized List<LedgerEntry> entriesAfter(long seq) {
        List<LedgerEntry> result = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (entry.seq() > seq) {
                result.add(entry);
            }
        }
        return result;
    }

    /** Every entry, oldest first. */
    public synchronized List<LedgerEntry> entries() {
        return new ArrayList<>(entries);
    }

    /** True if an entry of this ledger merged the branch with this head (DESIGN 4.10: its second parent). */
    public synchronized boolean hasMerged(String branchHead) {
        for (LedgerEntry entry : entries) {
            if (!entry.parents2().isEmpty() && entry.parents2().equals(branchHead)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops every entry after the one with this seq (0 drops all): the copy leaves its own lineage at the common
     * ancestor to follow the main one (DESIGN 4.10). The file is rewritten through a temp file.
     */
    public synchronized void truncate(long seq) throws IOException {
        List<LedgerEntry> kept = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (entry.seq() <= seq) {
                kept.add(entry);
            }
        }
        if (kept.size() == entries.size()) {
            return;
        }
        if (file != null) {
            rewrite(kept);
        }
        entries.clear();
        entries.addAll(kept);
        hashes.clear();
        for (LedgerEntry entry : kept) {
            hashes.add(entry.hash());
        }
        head = kept.isEmpty() ? "" : kept.get(kept.size() - 1).hash();
    }

    /** Entry hashes, oldest first. */
    public synchronized List<String> hashes() {
        List<String> result = new ArrayList<>(entries.size());
        for (LedgerEntry entry : entries) {
            result.add(entry.hash());
        }
        return result;
    }

    /**
     * Compares two copies by their entry hashes, oldest first (DESIGN 4.7): their head is in mine, I am
     * equal or ahead; my head is in theirs, I am behind; neither, the copies diverged and the common
     * ancestor is the last shared hash.
     */
    public static Comparison compare(List<String> mine, List<String> theirs) {
        int shared = 0;
        while (shared < mine.size() && shared < theirs.size() && mine.get(shared).equals(theirs.get(shared))) {
            shared++;
        }
        String ancestor = shared == 0 ? "" : mine.get(shared - 1);
        if (shared == mine.size() && shared == theirs.size()) {
            return new Comparison(Relation.EQUAL, ancestor);
        }
        if (shared == theirs.size()) {
            return new Comparison(Relation.AHEAD, ancestor);
        }
        if (shared == mine.size()) {
            return new Comparison(Relation.BEHIND, ancestor);
        }
        return new Comparison(Relation.DIVERGED, ancestor);
    }
}
