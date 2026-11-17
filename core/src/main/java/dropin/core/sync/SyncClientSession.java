package dropin.core.sync;

import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A member's end of one sync connection (DESIGN 4.6, 4.7): handshake, ledger catch-up, hash diff
 * (root, region, chunk), receiver-driven record download, then live checkpoints.
 * <p>
 * A record is applied only if its bytes hash to the value the host announced for it, and the local
 * ledger only advances once the local manifest root equals the root of a MAC'd, chained checkpoint.
 * So a cut, truncated or tampered transfer can never be committed, and the next connection continues
 * from whatever was applied, because the diff is hash-based.
 * <p>
 * {@link #start}, {@link #onFrame} and {@link #onClosed} must be called one at a time.
 */
public final class SyncClientSession {
    /** Records requested but not yet received (DESIGN 4.6: receiver-driven windows). */
    public static final int WINDOW = 64;
    private static final int GET_BATCH = 16;
    private static final int REGION_BATCH = 32;
    private static final int FLUSH_EVERY = 200;
    private static final int MAX_RECORD = 512 * 1024 * 1024;
    private static final int MAX_REDIFFS = 3;

    public enum Phase { CONNECTING, WAITING_DIRECT, COMPARING, DOWNLOADING, LIVE, DIVERGED, FAILED }

    /**
     * @param have  records this copy holds
     * @param total records the host's copy has, as far as known
     */
    public record Status(Phase phase, int have, int total, String detail) {
    }

    /** The member's copy of the world. */
    public interface Local {
        String worldId();

        byte[] auth();

        byte[] enc();

        String playerUuid();

        Ledger ledger();

        Manifest manifest();

        /** Writes a verified record into the local copy. */
        void apply(String key, byte[] bytes) throws IOException;

        void delete(String key) throws IOException;

        /** Makes everything applied so far durable, then stores the manifest. */
        void flush() throws IOException;

        /** The local copy now equals this checkpoint; the ledger already holds it. */
        void committed(LedgerEntry head) throws IOException;

        void status(Status status);

        /** Version of the newest valid policy this copy holds; 0 if none. */
        default long policyVersion() {
            return 0;
        }

        /**
         * The host's policy and mod manifest (empty strings if it has none).
         *
         * @return {policyJson, manifestJson} to send back when this copy holds a newer one, else null
         */
        default String[] policyReceived(String policyJson, String manifestJson) {
            return null;
        }

        /** The member list this copy knows, as JSON; null to share none. */
        default String membersToOffer() {
            return null;
        }

        default void membersReceived(String json) {
        }

        /**
         * The host is leaving (DESIGN 4.9).
         *
         * @param successorUuid the player who hosts next
         * @param finalEntry    the host's last checkpoint (verified with the world key)
         * @param applied       true if this copy is complete through that checkpoint
         */
        default void handoff(String successorUuid, LedgerEntry finalEntry, boolean applied) {
        }
    }

    private final Local local;
    private final Transport transport;
    private final SecureRandom random;
    private final byte[] nonceC = new byte[SecureChannel.NONCE_LENGTH];
    private SecureChannel channel;
    private boolean closed;

    private Phase phase = Phase.CONNECTING;
    private String detail = "";
    private boolean bulk;
    private boolean headSeen;
    private boolean ledgerDone;
    private boolean diffDone;
    private boolean diffRunning;
    private boolean rediffWanted;
    private boolean awaitingTop;
    private int rediffs;
    private String serverHead = "";

    /** Checkpoints received and verified, not yet reached by the local copy. */
    private final List<LedgerEntry> pending = new ArrayList<>();
    /** Key to the hash its record must have. */
    private final Map<String, String> want = new HashMap<>();
    /** Keys to request: files before chunks, level.dat first, so the world shows up in the world list early. */
    private final ArrayDeque<String> fileQueue = new ArrayDeque<>();
    private final ArrayDeque<String> chunkQueue = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    /** Requested key to the hash that was wanted when it was requested. */
    private final Map<String, String> outstanding = new HashMap<>();
    /** Keys whose record did not match; asked for again after the next checkpoint. */
    private final Set<String> stale = new HashSet<>();
    private final Map<String, ByteArrayOutputStream> pieces = new HashMap<>();
    private final ArrayDeque<String> regionQueue = new ArrayDeque<>();
    private List<String> regionsAsked = List.of();
    private final Map<String, String> diffAnswer = new LinkedHashMap<>();
    private int sinceFlush;
    private long applied;
    private long rejected;

    public SyncClientSession(Local local, Transport transport, SecureRandom random) {
        this.local = local;
        this.transport = transport;
        this.random = random;
    }

    public Phase phase() {
        return phase;
    }

    /** Records written into the local copy on this connection. */
    public long applied() {
        return applied;
    }

    /** Records refused on this connection because their bytes did not match the announced hash. */
    public long rejected() {
        return rejected;
    }

    public void start() {
        random.nextBytes(nonceC);
        transport.send(new Wire.Out(Wire.HELLO).i32(Wire.PROTO).str(local.worldId()).str(local.playerUuid())
                .bytes(nonceC).toBytes());
        report();
    }

    public void onFrame(byte[] frame) {
        if (closed) {
            return;
        }
        try {
            if (channel == null) {
                handshake(new Wire.In(frame));
            } else {
                dispatch(new Wire.In(channel.open(frame)));
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    public void onClosed() {
        if (!closed) {
            closed = true;
            flushQuietly();
        }
    }

    /** Says goodbye and closes the connection, keeping what was applied. */
    public void close() {
        if (!closed) {
            if (channel != null) {
                send(new Wire.Out(Wire.BYE));
            }
            closed = true;
            flushQuietly();
            transport.close();
        }
    }

    private void fail(String why) {
        phase = Phase.FAILED;
        detail = why;
        report();
        if (!closed) {
            closed = true;
            flushQuietly();
            transport.close();
        }
    }

    private void flushQuietly() {
        try {
            local.flush();
        } catch (IOException | RuntimeException e) {
            // The manifest on disk is then older than the data: the next diff downloads a little more.
        }
    }

    private byte[] nonceS;

    private void handshake(Wire.In in) throws IOException {
        if (in.type == Wire.CHALLENGE && nonceS == null) {
            nonceS = in.bytes();
            if (nonceS.length != SecureChannel.NONCE_LENGTH) {
                throw new IOException("Bad sync challenge");
            }
            transport.send(new Wire.Out(Wire.AUTH_C).bytes(SecureChannel.proof(local.auth(), nonceC, nonceS, "c")).toBytes());
        } else if (in.type == Wire.AUTH_S && nonceS != null) {
            if (!MessageDigest.isEqual(in.bytes(), SecureChannel.proof(local.auth(), nonceC, nonceS, "s"))) {
                throw new IOException("The host does not hold the world key");
            }
            channel = new SecureChannel(local.enc(), nonceC, nonceS, true);
            send(new Wire.Out(Wire.POLICY_REQ).i64(local.policyVersion()));
            String members = local.membersToOffer();
            if (members != null) {
                send(new Wire.Out(Wire.MEMBERS).str(members));
            }
        } else {
            throw new IOException("Unexpected handshake message " + in.type);
        }
    }

    private void dispatch(Wire.In in) throws IOException {
        switch (in.type) {
            case Wire.HEAD -> onHead(in.str(), in.i64(), in.str(), in.u8() != 0);
            case Wire.LEDGER_RESP -> onLedger(in.u8() != 0, in.strs());
            case Wire.DIFF_RESP -> onDiff(in.u8() != 0, in.map());
            case Wire.RECORD -> onRecord(in.str(), in.u8(), in.bytes());
            case Wire.CHECKPOINT -> onCheckpoint(LedgerEntry.fromJson(in.str()), in.u8() != 0, in.map());
            case Wire.POLICY -> {
                String[] newer = local.policyReceived(in.str(), in.str());
                if (newer != null) {
                    send(new Wire.Out(Wire.POLICY).str(newer[0]).str(newer[1]));
                }
            }
            case Wire.MEMBERS -> local.membersReceived(in.str());
            case Wire.HANDOFF -> {
                String successor = in.str();
                LedgerEntry finalEntry = LedgerEntry.fromJson(in.str());
                if (!finalEntry.verify(local.auth())) {
                    throw new IOException("The host sent a handoff that does not verify");
                }
                local.handoff(successor, finalEntry, finalEntry.hash().equals(local.ledger().head()));
            }
            case Wire.PEERS, Wire.BRANCH_PUSH, Wire.MOD_DATA -> {
                // Later milestones.
            }
            case Wire.BYE -> onClosed();
            default -> throw new IOException("Unexpected sync message " + in.type);
        }
    }

    private void onHead(String head, long seq, String root, boolean bulkNow) throws IOException {
        serverHead = head;
        boolean was = bulk;
        bulk = bulkNow;
        if (!headSeen) {
            headSeen = true;
            send(new Wire.Out(Wire.LEDGER_REQ).i64(local.ledger().headSeq()));
        } else if (ledgerDone && phase != Phase.DIVERGED) {
            if (bulk && !was) {
                startDiff();
            } else if (!bulk) {
                phase = Phase.WAITING_DIRECT;
                report();
            }
        }
    }

    private void onLedger(boolean more, List<String> entries) throws IOException {
        for (String json : entries) {
            LedgerEntry entry = LedgerEntry.fromJson(json);
            if (!accept(entry)) {
                if (pending.isEmpty() && local.ledger().size() > 0) {
                    diverged();
                    return;
                }
                throw new IOException("The host sent a ledger entry that does not verify");
            }
        }
        if (more) {
            return;
        }
        ledgerDone = true;
        if (pending.isEmpty() && !serverHead.equals(local.ledger().head())) {
            // The host's head is not ahead of this copy's head on the same chain.
            diverged();
            return;
        }
        if (bulk) {
            startDiff();
        } else {
            phase = Phase.WAITING_DIRECT;
            report();
        }
    }

    /** Chains a received entry after the newest known one. False if it does not verify or does not follow. */
    private boolean accept(LedgerEntry entry) {
        LedgerEntry last = pending.isEmpty() ? local.ledger().last() : pending.get(pending.size() - 1);
        String problem = Ledger.rejection(entry, last == null ? "" : last.hash(), last == null ? 0 : last.seq(), local.auth());
        if (problem != null) {
            return false;
        }
        pending.add(entry);
        return true;
    }

    private void diverged() {
        // Merging two lineages is a later milestone (DESIGN 4.10). Nothing of this copy is touched.
        phase = Phase.DIVERGED;
        detail = "this copy and the host's copy have split";
        report();
        close();
    }

    private void startDiff() {
        if (diffRunning) {
            rediffWanted = true;
            return;
        }
        diffRunning = true;
        diffDone = false;
        awaitingTop = true;
        regionQueue.clear();
        diffAnswer.clear();
        phase = Phase.COMPARING;
        report();
        send(new Wire.Out(Wire.DIFF_REQ).strs(List.of()));
    }

    private void onDiff(boolean more, Map<String, String> part) throws IOException {
        if (!diffRunning) {
            return;
        }
        diffAnswer.putAll(part);
        if (more) {
            return;
        }
        Manifest manifest = local.manifest();
        if (awaitingTop) {
            awaitingTop = false;
            Map<String, String> mine = manifest.top();
            for (Map.Entry<String, String> entry : diffAnswer.entrySet()) {
                if (!entry.getValue().equals(mine.get(entry.getKey()))) {
                    if (RecordKey.isRegion(entry.getKey())) {
                        regionQueue.add(entry.getKey());
                    } else {
                        wantKey(entry.getKey(), entry.getValue());
                    }
                }
            }
            for (String key : mine.keySet()) {
                if (!diffAnswer.containsKey(key)) {
                    if (RecordKey.isRegion(key)) {
                        for (String chunk : manifest.chunks(key).keySet()) {
                            delete(chunk);
                        }
                    } else {
                        delete(key);
                    }
                }
            }
        } else {
            for (String region : regionsAsked) {
                Map<String, String> mine = manifest.chunks(region);
                for (String chunk : mine.keySet()) {
                    if (!diffAnswer.containsKey(chunk)) {
                        delete(chunk);
                    }
                }
            }
            for (Map.Entry<String, String> entry : diffAnswer.entrySet()) {
                if (!entry.getValue().equals(manifest.get(entry.getKey()))) {
                    wantKey(entry.getKey(), entry.getValue());
                } else {
                    want.remove(entry.getKey());
                }
            }
        }
        diffAnswer.clear();
        if (!regionQueue.isEmpty()) {
            List<String> ask = new ArrayList<>();
            while (!regionQueue.isEmpty() && ask.size() < REGION_BATCH) {
                ask.add(regionQueue.poll());
            }
            regionsAsked = ask;
            send(new Wire.Out(Wire.DIFF_REQ).strs(ask));
            return;
        }
        regionsAsked = List.of();
        diffRunning = false;
        if (rediffWanted) {
            rediffWanted = false;
            startDiff();
            return;
        }
        diffDone = true;
        phase = Phase.DOWNLOADING;
        report();
        pump();
    }

    private void wantKey(String key, String hash) {
        if (RecordKey.isFile(key) ? !RecordKey.fileAllowed(RecordKey.filePath(key)) : !validChunk(key)) {
            throw new IllegalArgumentException("The host announced a record this copy may not hold: " + key);
        }
        want.put(key, hash);
        stale.remove(key);
        if (!outstanding.containsKey(key)) {
            enqueue(key);
        }
    }

    private void enqueue(String key) {
        if (queued.add(key)) {
            if (key.equals("f|level.dat")) {
                fileQueue.addFirst(key);
            } else if (RecordKey.isFile(key)) {
                fileQueue.addLast(key);
            } else {
                chunkQueue.addLast(key);
            }
        }
    }

    private boolean queueEmpty() {
        return fileQueue.isEmpty() && chunkQueue.isEmpty();
    }

    private String poll() {
        String key = fileQueue.isEmpty() ? chunkQueue.poll() : fileQueue.poll();
        queued.remove(key);
        return key;
    }

    private static boolean validChunk(String key) {
        try {
            RecordKey.parseChunk(key);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void delete(String key) throws IOException {
        want.remove(key);
        stale.remove(key);
        if (local.manifest().get(key) != null) {
            local.delete(key);
            local.manifest().remove(key);
        }
    }

    private void pump() throws IOException {
        if (!diffDone || !bulk || closed) {
            return;
        }
        while (outstanding.size() < WINDOW && !queueEmpty()) {
            List<String> batch = new ArrayList<>();
            while (batch.size() < GET_BATCH && outstanding.size() < WINDOW && !queueEmpty()) {
                String key = poll();
                String hash = want.get(key);
                if (hash != null) {
                    outstanding.put(key, hash);
                    batch.add(key);
                }
            }
            if (!batch.isEmpty()) {
                send(new Wire.Out(Wire.GET).strs(batch));
            }
        }
        if (outstanding.isEmpty() && queueEmpty()) {
            settle();
        }
    }

    private void onRecord(String key, int flags, byte[] piece) throws IOException {
        if (!outstanding.containsKey(key)) {
            return;
        }
        ByteArrayOutputStream buffer = pieces.computeIfAbsent(key, k -> new ByteArrayOutputStream());
        if (buffer.size() + piece.length > MAX_RECORD) {
            throw new IOException("Record too large: " + key);
        }
        buffer.write(piece, 0, piece.length);
        if ((flags & Wire.RECORD_LAST) == 0) {
            return;
        }
        pieces.remove(key);
        String asked = outstanding.remove(key);
        String expected = want.get(key);
        if (expected != null) {
            byte[] bytes = buffer.toByteArray();
            boolean absent = (flags & Wire.RECORD_ABSENT) != 0;
            if (!absent && Manifest.hash(bytes).equals(expected)) {
                local.apply(key, bytes);
                local.manifest().put(key, expected);
                want.remove(key);
                applied++;
                if (++sinceFlush >= FLUSH_EVERY) {
                    sinceFlush = 0;
                    local.flush();
                }
            } else {
                // Not what was announced: never written. If the announcement moved on while the request
                // was under way, ask again now; otherwise the host changed the record since its last
                // checkpoint, and the next checkpoint will announce the new hash.
                rejected++;
                if (!expected.equals(asked)) {
                    enqueue(key);
                } else {
                    stale.add(key);
                }
            }
        }
        report();
        pump();
    }

    private void onCheckpoint(LedgerEntry entry, boolean runDiff, Map<String, String> changed) throws IOException {
        if (!ledgerDone || phase == Phase.DIVERGED) {
            return;
        }
        LedgerEntry last = pending.isEmpty() ? local.ledger().last() : pending.get(pending.size() - 1);
        if (last != null && entry.seq() <= last.seq()) {
            return;
        }
        if (!accept(entry)) {
            throw new IOException("The host sent a checkpoint that does not verify");
        }
        serverHead = entry.hash();
        if (!bulk) {
            phase = Phase.WAITING_DIRECT;
            report();
            return;
        }
        if (runDiff) {
            startDiff();
            return;
        }
        for (Map.Entry<String, String> change : changed.entrySet()) {
            if (change.getValue().isEmpty()) {
                delete(change.getKey());
            } else if (!change.getValue().equals(local.manifest().get(change.getKey()))) {
                wantKey(change.getKey(), change.getValue());
            } else {
                want.remove(change.getKey());
            }
        }
        for (String key : new ArrayList<>(stale)) {
            if (want.containsKey(key) && !outstanding.containsKey(key)) {
                enqueue(key);
            }
        }
        stale.clear();
        if (diffDone) {
            phase = Phase.DOWNLOADING;
            report();
            pump();
        }
    }

    /** Nothing is requested or queued any more. */
    private void settle() throws IOException {
        if (!stale.isEmpty()) {
            detail = stale.size() + " records wait for the host's next checkpoint";
            report();
            return;
        }
        local.flush();
        sinceFlush = 0;
        LedgerEntry target = pending.isEmpty() ? local.ledger().last() : pending.get(pending.size() - 1);
        if (target == null) {
            // The host has no checkpoint yet (its first manifest is still being built).
            phase = Phase.COMPARING;
            detail = "waiting for the host's first checkpoint";
            report();
            return;
        }
        if (!local.manifest().root().equals(target.root())) {
            if (++rediffs > MAX_REDIFFS) {
                throw new IOException("The downloaded world does not match the host's checkpoint " + target.seq());
            }
            startDiff();
            return;
        }
        rediffs = 0;
        for (LedgerEntry entry : pending) {
            local.ledger().append(entry);
        }
        pending.clear();
        local.committed(target);
        send(new Wire.Out(Wire.HEAD).i64(target.seq()));
        phase = Phase.LIVE;
        detail = "";
        report();
    }

    private void report() {
        int have = local.manifest().size();
        int total = have + want.size();
        local.status(new Status(phase, have, total, detail));
        // The host shows this when it quits and nobody has a full copy (DESIGN 4.9). One message per percent at most.
        int percent = total <= 0 ? 0 : (int) (have * 100L / total);
        if (channel != null && !closed && headSeen && percent != sentPercent) {
            sentPercent = percent;
            send(new Wire.Out(Wire.PROGRESS).i32(have).i32(total));
        }
    }

    private int sentPercent = -1;

    private void send(Wire.Out message) {
        if (!closed && channel != null) {
            transport.send(channel.seal(message.toBytes()));
        }
    }
}
