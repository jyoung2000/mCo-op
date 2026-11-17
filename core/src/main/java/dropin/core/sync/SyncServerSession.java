package dropin.core.sync;

import dropin.core.ledger.LedgerEntry;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The host's end of one sync connection (DESIGN 4.6): handshake, then serving the ledger, hash diffs
 * and records, and pushing checkpoints. Bulk data (diffs and records) is only served while the host
 * allows it for this connection (path policy).
 * <p>
 * {@link #onFrame} and {@link #onClosed} must be called one at a time; {@link #checkpoint} and
 * {@link #refresh} may be called from any thread.
 */
public final class SyncServerSession {
    private static final int LEDGER_BATCH = 500;
    private static final int DIFF_BATCH = 3000;

    /** What the session needs from the hosted world. */
    public interface Host {
        String worldId();

        byte[] auth();

        byte[] enc();

        /**
         * Held by the host while it changes its ledger and manifest and pushes the checkpoint, and by a
         * session while it reads them and sends the answer, so answers and checkpoints reach a client in
         * the order they were made.
         */
        Object lock();

        /** The newest checkpoint, or null before the first one. */
        LedgerEntry head();

        List<LedgerEntry> ledgerAfter(long seq);

        /** Region and file hashes (first diff level). */
        Map<String, String> top();

        /** Chunk hashes of one region (second diff level). */
        Map<String, String> chunks(String regionKey);

        /** The record's bytes as they are now, or null if there is no such record. */
        byte[] read(String key) throws IOException;

        /** Path policy: may diffs and records go to this peer right now? */
        boolean bulkAllowed(SyncServerSession session);

        /** Called before record bytes are sent: rate cap and "do not slow the game" pauses. */
        void throttle(SyncServerSession session, int bytes) throws InterruptedException;

        /** The peer reports that its copy is complete through this checkpoint. */
        void peerSynced(SyncServerSession session, long seq);

        /** The newest valid policy this host holds and its mod manifest, as {policyJson, manifestJson}; null if none. */
        default String[] policy() {
            return null;
        }

        /** A peer offers a policy. The host verifies it and adopts it only if it is newer than its own. */
        default void policyOffered(SyncServerSession session, String policyJson, String manifestJson) {
        }

        /** The member list this host knows, as JSON; null if it has none to share. */
        default String members() {
            return null;
        }

        /** A peer shares the member list it knows. */
        default void membersOffered(SyncServerSession session, String json) {
        }

        /** The jar of the host's current signed mod list with this SHA-512, or null. */
        default java.nio.file.Path modFile(String sha512) {
            return null;
        }
    }

    private final Host host;
    private final Transport transport;
    private final SecureRandom random;
    private byte[] nonceC;
    private byte[] nonceS;
    private SecureChannel channel;
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile boolean bulk;
    private volatile String playerUuid = "";
    private volatile long syncedSeq;
    private volatile long recordFrames;
    private volatile long recordBytes;

    public SyncServerSession(Host host, Transport transport, SecureRandom random) {
        this.host = host;
        this.transport = transport;
        this.random = random;
    }

    public boolean ready() {
        return ready && !closed;
    }

    public String playerUuid() {
        return playerUuid;
    }

    /** The newest checkpoint the peer reported as fully applied. */
    public long syncedSeq() {
        return syncedSeq;
    }

    /** RECORD frames sent on this connection. */
    public long recordFrames() {
        return recordFrames;
    }

    public long recordBytes() {
        return recordBytes;
    }

    public void onFrame(byte[] frame) {
        if (closed) {
            return;
        }
        try {
            if (!ready) {
                handshake(new Wire.In(frame));
            } else {
                byte[] message;
                synchronized (this) {
                    message = channel.open(frame);
                }
                dispatch(new Wire.In(message));
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
        }
    }

    public void onClosed() {
        closed = true;
    }

    public void close() {
        if (!closed) {
            closed = true;
            transport.close();
        }
    }

    /**
     * Pushes a new checkpoint. Call with {@link Host#lock()} held, after the ledger and manifest were
     * updated. The changed keys go along only when bulk is allowed and the list is short; otherwise
     * the peer is told to run a diff.
     */
    public void checkpoint(LedgerEntry entry, Map<String, String> changed) {
        if (!ready()) {
            return;
        }
        boolean listKeys = currentBulk() && changed.size() <= Wire.MAX_CHANGED_KEYS;
        sendSealed(new Wire.Out(Wire.CHECKPOINT).str(entry.toJson()).u8(listKeys ? 0 : 1)
                .map(listKeys ? changed : Map.of()));
    }

    /** Re-evaluates the path policy and tells the peer if it changed. */
    public void refresh() {
        if (ready()) {
            currentBulk();
        }
    }

    private boolean currentBulk() {
        boolean now = host.bulkAllowed(this);
        if (now != bulk) {
            bulk = now;
            sendHead();
        }
        return now;
    }

    private void handshake(Wire.In in) throws IOException {
        if (in.type == Wire.HELLO && nonceC == null) {
            int proto = in.i32();
            String worldId = in.str();
            playerUuid = in.str();
            byte[] nonce = in.bytes();
            if (proto != Wire.PROTO || !worldId.equals(host.worldId()) || nonce.length != SecureChannel.NONCE_LENGTH) {
                throw new IOException("Refused sync hello");
            }
            nonceC = nonce;
            nonceS = new byte[SecureChannel.NONCE_LENGTH];
            random.nextBytes(nonceS);
            transport.send(new Wire.Out(Wire.CHALLENGE).bytes(nonceS).toBytes());
        } else if (in.type == Wire.AUTH_C && nonceC != null) {
            byte[] proof = in.bytes();
            if (!MessageDigest.isEqual(proof, SecureChannel.proof(host.auth(), nonceC, nonceS, "c"))) {
                throw new IOException("Sync peer does not hold the world key");
            }
            synchronized (this) {
                transport.send(new Wire.Out(Wire.AUTH_S).bytes(SecureChannel.proof(host.auth(), nonceC, nonceS, "s")).toBytes());
                channel = new SecureChannel(host.enc(), nonceC, nonceS, false);
                ready = true;
            }
            bulk = host.bulkAllowed(this);
            synchronized (host.lock()) {
                sendHead();
            }
        } else {
            throw new IOException("Unexpected handshake message " + in.type);
        }
    }

    private void dispatch(Wire.In in) throws IOException, InterruptedException {
        switch (in.type) {
            case Wire.HEAD -> {
                syncedSeq = in.i64();
                host.peerSynced(this, syncedSeq);
            }
            case Wire.LEDGER_REQ -> {
                long after = in.i64();
                synchronized (host.lock()) {
                    List<LedgerEntry> entries = host.ledgerAfter(after);
                    int from = 0;
                    do {
                        int to = Math.min(entries.size(), from + LEDGER_BATCH);
                        List<String> batch = new ArrayList<>(to - from);
                        for (LedgerEntry entry : entries.subList(from, to)) {
                            batch.add(entry.toJson());
                        }
                        sendSealed(new Wire.Out(Wire.LEDGER_RESP).u8(to < entries.size() ? 1 : 0).strs(batch));
                        from = to;
                    } while (from < entries.size());
                }
            }
            case Wire.DIFF_REQ -> {
                List<String> regions = in.strs();
                if (!currentBulk()) {
                    sendHead();
                    return;
                }
                synchronized (host.lock()) {
                    Map<String, String> answer;
                    if (regions.isEmpty()) {
                        answer = host.top();
                    } else {
                        answer = new LinkedHashMap<>();
                        for (String region : regions) {
                            answer.putAll(host.chunks(region));
                        }
                    }
                    List<Map.Entry<String, String>> entries = new ArrayList<>(answer.entrySet());
                    int from = 0;
                    do {
                        int to = Math.min(entries.size(), from + DIFF_BATCH);
                        Map<String, String> batch = new LinkedHashMap<>();
                        for (Map.Entry<String, String> entry : entries.subList(from, to)) {
                            batch.put(entry.getKey(), entry.getValue());
                        }
                        sendSealed(new Wire.Out(Wire.DIFF_RESP).u8(to < entries.size() ? 1 : 0).map(batch));
                        from = to;
                    } while (from < entries.size());
                }
            }
            case Wire.GET -> {
                for (String key : in.strs()) {
                    if (closed) {
                        return;
                    }
                    if (!currentBulk()) {
                        sendHead();
                        return;
                    }
                    sendRecord(key);
                }
            }
            case Wire.POLICY_REQ -> {
                in.i64();
                // Always answered, also when the peer is not behind: it then knows this host has nothing newer.
                sendPolicy(true);
            }
            case Wire.POLICY -> host.policyOffered(this, in.str(), in.str());
            case Wire.MEMBERS -> {
                host.membersOffered(this, in.str());
                sendMembers();
            }
            case Wire.MOD_GET -> sendMod(in.str());
            case Wire.BYE -> close();
            default -> throw new IOException("Unexpected sync message " + in.type);
        }
    }

    /** Pushes the host's policy and mod manifest to the peer (after the host adopted a newer one). */
    public void sendPolicy() {
        sendPolicy(false);
    }

    private void sendPolicy(boolean evenIfNone) {
        String[] policy = host.policy();
        if (ready() && (policy != null || evenIfNone)) {
            sendSealed(new Wire.Out(Wire.POLICY).str(policy == null ? "" : policy[0]).str(policy == null ? "" : policy[1]));
        }
    }

    /** Pushes the member list this host knows to the peer. */
    public void sendMembers() {
        String members = host.members();
        if (ready() && members != null) {
            sendSealed(new Wire.Out(Wire.MEMBERS).str(members));
        }
    }

    /** Mod files are bulk data: they only go where records may go, and only files of the signed mod list. */
    private void sendMod(String sha512) throws IOException, InterruptedException {
        if (!currentBulk()) {
            sendSealed(new Wire.Out(Wire.MOD_DATA).str(sha512).u8(Wire.MOD_LAST | Wire.MOD_REFUSED).bytes(new byte[0]));
            return;
        }
        java.nio.file.Path file = host.modFile(sha512);
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            sendSealed(new Wire.Out(Wire.MOD_DATA).str(sha512).u8(Wire.MOD_LAST | Wire.MOD_ABSENT).bytes(new byte[0]));
            return;
        }
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            byte[] piece = in.readNBytes(Wire.PIECE);
            while (!closed) {
                byte[] next = in.readNBytes(Wire.PIECE);
                host.throttle(this, piece.length);
                modBytes += piece.length;
                sendSealed(new Wire.Out(Wire.MOD_DATA).str(sha512).u8(next.length == 0 ? Wire.MOD_LAST : 0).bytes(piece));
                if (next.length == 0) {
                    return;
                }
                piece = next;
            }
        }
    }

    /** Mod file bytes sent on this connection. */
    public long modBytes() {
        return modBytes;
    }

    private volatile long modBytes;

    private void sendRecord(String key) throws IOException, InterruptedException {
        byte[] bytes = host.read(key);
        if (bytes == null) {
            recordFrames++;
            sendSealed(new Wire.Out(Wire.RECORD).str(key).u8(Wire.RECORD_LAST | Wire.RECORD_ABSENT).bytes(new byte[0]));
            return;
        }
        int offset = 0;
        do {
            int length = Math.min(Wire.PIECE, bytes.length - offset);
            host.throttle(this, length);
            boolean last = offset + length >= bytes.length;
            recordFrames++;
            recordBytes += length;
            sendSealed(new Wire.Out(Wire.RECORD).str(key).u8(last ? Wire.RECORD_LAST : 0).bytes(bytes, offset, length));
            offset += length;
        } while (offset < bytes.length);
    }

    private void sendHead() {
        LedgerEntry head = host.head();
        sendSealed(new Wire.Out(Wire.HEAD).str(head == null ? "" : head.hash()).i64(head == null ? 0 : head.seq())
                .str(head == null ? "" : head.root()).u8(bulk ? 1 : 0));
    }

    private synchronized void sendSealed(Wire.Out message) {
        if (!closed && channel != null) {
            transport.send(channel.seal(message.toBytes()));
        }
    }
}
