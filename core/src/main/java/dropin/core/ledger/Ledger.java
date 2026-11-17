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

    /** Reads {@code ledger.jsonl}. A missing file is an empty ledger; a broken chain is an error. */
    public static Ledger load(byte[] auth, Path file) throws IOException {
        Ledger ledger = new Ledger(auth, null);
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    try {
                        ledger.append(LedgerEntry.fromJson(line));
                    } catch (RuntimeException e) {
                        throw new IOException("Ledger " + file + " is damaged at entry " + (ledger.size() + 1), e);
                    }
                }
            }
        }
        Ledger loaded = new Ledger(auth, file);
        loaded.entries.addAll(ledger.entries);
        loaded.hashes.addAll(ledger.hashes);
        loaded.head = ledger.head;
        return loaded;
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
            Files.writeString(file, entry.toJson() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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

    public synchronized List<LedgerEntry> entriesAfter(long seq) {
        List<LedgerEntry> result = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            if (entry.seq() > seq) {
                result.add(entry);
            }
        }
        return result;
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
