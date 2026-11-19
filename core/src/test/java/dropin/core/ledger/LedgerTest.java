package dropin.core.ledger;

import dropin.core.sync.SyncState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerTest {
    private static final byte[] AUTH = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final String ROOT = "00112233445566778899aabbccddeeff";

    private static LedgerEntry next(Ledger ledger, String host) {
        return LedgerEntry.create(AUTH, ledger.headSeq() + 1, ledger.head(), host, "Player", "aabbccdd00112233",
                1_800_000_000L + ledger.headSeq(), ROOT, 3);
    }

    private static Ledger chain(int length, String host) throws IOException {
        Ledger ledger = new Ledger(AUTH, null);
        for (int i = 0; i < length; i++) {
            ledger.append(next(ledger, host));
        }
        return ledger;
    }

    @Test
    void entriesChainAndVerify() throws IOException {
        Ledger ledger = chain(3, "host-a");
        assertEquals(3, ledger.size());
        assertEquals(3, ledger.headSeq());
        assertEquals(64, ledger.head().length());
        assertEquals(ledger.last().hash(), ledger.head());
        assertEquals(ledger.hashes().get(1), ledger.last().parent());
        assertTrue(ledger.contains(ledger.hashes().get(0)));
        assertFalse(ledger.contains("ff".repeat(32)));
        assertEquals(2, ledger.entriesAfter(1).size());
    }

    @Test
    void aLedgerKnowsTheShortHeadsOfItsOwnEntriesOnly() throws IOException {
        Ledger ledger = chain(3, "host-a");
        Ledger longer = chain(3, "host-a");
        LedgerEntry newer = next(longer, "host-b");
        longer.append(newer);
        for (String hash : ledger.hashes()) {
            assertTrue(ledger.knows(hash.substring(0, 32)), "an own entry's 16-byte head is not known");
            assertTrue(ledger.knows(hash));
        }
        assertTrue(ledger.knows(""), "nothing announced is never 'behind'");
        assertTrue(ledger.knows(null));
        // The copy that stopped at entry 3 is behind a session that went on to entry 4.
        assertFalse(ledger.knows(newer.hash().substring(0, 32)));
        assertTrue(longer.knows(newer.hash().substring(0, 32)));
        assertFalse(new Ledger(AUTH, null).knows(newer.hash().substring(0, 32)));
    }

    @Test
    void entryRoundTripsThroughJson() throws IOException {
        LedgerEntry entry = chain(2, "host-a").last();
        LedgerEntry back = LedgerEntry.fromJson(entry.toJson());
        assertEquals(entry, back);
        assertEquals(entry.hash(), back.hash());
        assertTrue(back.verify(AUTH));
    }

    @Test
    void tamperedOrForeignEntriesAreRejected() throws IOException {
        Ledger ledger = chain(2, "host-a");
        LedgerEntry good = next(ledger, "host-a");

        LedgerEntry changedRoot = new LedgerEntry(good.seq(), good.parent(), good.host(), good.name(), good.sess(),
                good.ts(), "ff".repeat(16), good.changed(), "", good.mac());
        assertFalse(changedRoot.verify(AUTH));
        assertThrows(IllegalArgumentException.class, () -> ledger.append(changedRoot));

        LedgerEntry wrongKey = LedgerEntry.create(OTHER, good.seq(), good.parent(), "x", "x", "x", 1, ROOT, 0);
        assertEquals("bad MAC", ledger.rejection(wrongKey));

        LedgerEntry wrongParent = LedgerEntry.create(AUTH, good.seq(), "ab".repeat(32), "x", "x", "x", 1, ROOT, 0);
        assertEquals("does not follow the head", ledger.rejection(wrongParent));

        LedgerEntry wrongSeq = LedgerEntry.create(AUTH, good.seq() + 1, good.parent(), "x", "x", "x", 1, ROOT, 0);
        assertEquals("wrong sequence number", ledger.rejection(wrongSeq));

        assertEquals(2, ledger.size());
        ledger.append(good);
        assertEquals(3, ledger.size());
    }

    @Test
    void ledgerFileLoadsBackAndDamageIsDetected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("dropin").resolve("ledger.jsonl");
        Ledger ledger = new Ledger(AUTH, file);
        for (int i = 0; i < 4; i++) {
            ledger.append(next(ledger, "host-a"));
        }
        Ledger loaded = Ledger.load(AUTH, file);
        assertEquals(ledger.hashes(), loaded.hashes());
        loaded.append(next(loaded, "host-b"));
        assertEquals(5, Files.readAllLines(file).size());

        assertEquals(0, Ledger.load(AUTH, dir.resolve("none.jsonl")).size());
        assertThrows(IOException.class, () -> Ledger.load(OTHER, file));
        List<String> lines = Files.readAllLines(file);
        lines.remove(1);
        Files.write(file, lines);
        assertThrows(IOException.class, () -> Ledger.load(AUTH, file));
    }

    @Test
    void compareTellsEqualAheadBehindAndDiverged() throws IOException {
        Ledger base = chain(3, "host-a");
        List<String> three = base.hashes();

        assertEquals(new Ledger.Comparison(Ledger.Relation.EQUAL, three.get(2)), Ledger.compare(three, three));

        Ledger longer = chain(3, "host-a");
        longer.append(next(longer, "host-a"));
        longer.append(next(longer, "host-a"));
        List<String> five = longer.hashes();
        assertEquals(three, five.subList(0, 3));
        assertEquals(new Ledger.Comparison(Ledger.Relation.AHEAD, three.get(2)), Ledger.compare(five, three));
        assertEquals(new Ledger.Comparison(Ledger.Relation.BEHIND, three.get(2)), Ledger.compare(three, five));

        Ledger fork = chain(3, "host-a");
        fork.append(next(fork, "host-b"));
        List<String> forked = fork.hashes();
        assertNotEquals(five.get(3), forked.get(3));
        assertEquals(new Ledger.Comparison(Ledger.Relation.DIVERGED, three.get(2)), Ledger.compare(five, forked));
        assertEquals(new Ledger.Comparison(Ledger.Relation.DIVERGED, three.get(2)), Ledger.compare(forked, five));

        List<String> unrelated = chain(2, "host-z").hashes();
        assertEquals(new Ledger.Comparison(Ledger.Relation.DIVERGED, ""), Ledger.compare(three, unrelated));
        assertEquals(new Ledger.Comparison(Ledger.Relation.BEHIND, ""), Ledger.compare(List.of(), three));
        assertEquals(new Ledger.Comparison(Ledger.Relation.EQUAL, ""), Ledger.compare(List.of(), List.of()));
    }

    @Test
    void deltasRoundTrip(@TempDir Path dir) throws IOException {
        Map<String, String> changed = new LinkedHashMap<>();
        changed.put("c|minecraft:overworld|region|1|-2", ROOT);
        changed.put("f|level.dat", ROOT);
        changed.put("f|data/gone.dat", "");
        Deltas.write(dir.resolve("deltas"), 7, changed);
        assertTrue(Files.isRegularFile(dir.resolve("deltas").resolve("7.bin")));
        assertEquals(changed, Deltas.read(dir.resolve("deltas"), 7));
    }

    @Test
    void stateFileRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("dropin").resolve("state.json");
        assertEquals(SyncState.EMPTY, SyncState.load(file));
        SyncState state = new SyncState("ab".repeat(32), 12, false, 63, 100);
        state.save(file);
        assertEquals(state, SyncState.load(file));
        assertEquals(63, state.percent());
        assertEquals(100, new SyncState("x", 1, true, 1, 9).percent());
        assertEquals(99, new SyncState("x", 1, false, 100, 100).percent());
        Files.writeString(file, "not json");
        assertEquals(SyncState.EMPTY, SyncState.load(file));
    }
}
