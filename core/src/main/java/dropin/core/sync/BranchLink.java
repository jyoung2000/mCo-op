package dropin.core.sync;

import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.RecordKey;
import dropin.core.merge.BranchStore;
import dropin.core.merge.Branches.Change;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One end of the {@link Wire#BRANCH_PUSH} exchange on a sync connection (DESIGN 4.10); both ends run the same
 * code. Each end says which preserved branches it holds, asks for those it lacks, and receives them record by
 * record, checking every hash. Branches are bulk world data: nothing is offered, asked for or sent while the
 * path policy forbids bulk data.
 * <p>
 * {@link #handle} is called by the connection's reading thread only; {@link #offer} from any thread.
 */
public final class BranchLink {
    private static final int MAX_RECORD = 512 * 1024 * 1024;

    /** What the session and the copy behind it provide. */
    public interface Env {
        /** The copy's branches, or null if it keeps none (then nothing is exchanged). */
        BranchStore store();

        byte[] auth();

        /** Whether this copy wants a branch it does not hold (false if it was merged already). */
        boolean wanted(String head);

        /** Path policy: may bulk data flow on this connection right now? */
        boolean bulk();

        void send(Wire.Out message);

        /** Called before {@code bytes} of a record go out (rate cap, back-pressure). */
        void throttle(int bytes) throws InterruptedException;

        /** A branch arrived in full and is now held by this copy. */
        void received(String head);
    }

    private final Env env;
    private final Set<String> offered = new HashSet<>();
    private BranchStore.Writer incoming;
    private ByteArrayOutputStream piece;

    public BranchLink(Env env) {
        this.env = env;
    }

    /** Tells the other end about every branch held here that it was not told about yet. */
    public void offer() {
        BranchStore store = env.store();
        if (store == null || !env.bulk()) {
            return;
        }
        List<String> fresh = new ArrayList<>();
        try {
            synchronized (offered) {
                for (String head : store.heads()) {
                    if (offered.add(head)) {
                        fresh.add(head);
                    }
                }
            }
        } catch (IOException e) {
            return;
        }
        if (!fresh.isEmpty()) {
            env.send(new Wire.Out(Wire.BRANCH_PUSH).u8(Wire.BRANCH_OFFER).strs(fresh));
        }
    }

    public void handle(Wire.In in) throws IOException, InterruptedException {
        BranchStore store = env.store();
        int part = in.u8();
        if (store == null) {
            return;
        }
        switch (part) {
            case Wire.BRANCH_OFFER -> {
                for (String head : in.strs()) {
                    synchronized (offered) {
                        // The other end holds it: never offered back.
                        offered.add(head);
                    }
                    if (BranchStore.validHead(head) && env.bulk() && !store.has(head) && env.wanted(head)) {
                        env.send(new Wire.Out(Wire.BRANCH_PUSH).u8(Wire.BRANCH_WANT).str(head));
                    }
                }
            }
            case Wire.BRANCH_WANT -> {
                String head = in.str();
                if (env.bulk() && store.has(head)) {
                    send(store, head);
                }
            }
            case Wire.BRANCH_BEGIN -> {
                String head = in.str();
                String ancestor = in.str();
                LedgerEntry entry = LedgerEntry.fromJson(in.str());
                if (incoming != null) {
                    incoming.abort();
                }
                if (!entry.verify(env.auth())) {
                    throw new IOException("A branch was sent whose checkpoint does not verify");
                }
                incoming = store.begin(head, ancestor, entry);
                piece = null;
            }
            case Wire.BRANCH_KEYS -> {
                int count = in.i32();
                if (incoming == null || count < 0 || count > Wire.BRANCH_KEY_BATCH) {
                    throw new IOException("Unexpected branch keys");
                }
                Map<String, Change> keys = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    String key = in.str();
                    if (!validKey(key)) {
                        throw new IOException("Refused branch key " + key);
                    }
                    String hash = in.str();
                    String base = in.str();
                    String content = in.str();
                    String contentBase = in.str();
                    keys.put(key, new Change(hash, base, content, contentBase.equals(BranchStore.UNKNOWN) ? null : contentBase));
                }
                incoming.keys(keys);
            }
            case Wire.BRANCH_DATA -> {
                boolean last = in.u8() != 0;
                byte[] bytes = in.bytes();
                if (incoming == null) {
                    throw new IOException("Unexpected branch record");
                }
                if (piece == null && last) {
                    incoming.record(bytes);
                    return;
                }
                if (piece == null) {
                    piece = new ByteArrayOutputStream();
                }
                if (piece.size() + (long) bytes.length > MAX_RECORD) {
                    throw new IOException("Branch record too large");
                }
                piece.write(bytes);
                if (last) {
                    incoming.record(piece.toByteArray());
                    piece = null;
                }
            }
            case Wire.BRANCH_END -> {
                String head = in.str();
                if (incoming == null || !incoming.head().equals(head)) {
                    throw new IOException("Unexpected end of a branch");
                }
                BranchStore.Writer done = incoming;
                incoming = null;
                done.finish();
                env.received(head);
            }
            default -> throw new IOException("Unexpected branch message part " + part);
        }
    }

    /** The connection ended: what was received of an unfinished branch is dropped. */
    public void closed() {
        if (incoming != null) {
            incoming.abort();
            incoming = null;
        }
    }

    private static boolean validKey(String key) {
        if (RecordKey.isFile(key)) {
            return RecordKey.fileAllowed(RecordKey.filePath(key));
        }
        if (!RecordKey.isChunk(key)) {
            return false;
        }
        try {
            RecordKey.parseChunk(key);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void send(BranchStore store, String head) throws IOException, InterruptedException {
        BranchStore.Branch branch = store.load(head);
        env.send(new Wire.Out(Wire.BRANCH_PUSH).u8(Wire.BRANCH_BEGIN).str(head).str(branch.ancestor()).str(branch.entry().toJson()));
        List<Map.Entry<String, Change>> keys = new ArrayList<>(branch.keys().entrySet());
        for (int from = 0; from < keys.size(); from += Wire.BRANCH_KEY_BATCH) {
            int to = Math.min(keys.size(), from + Wire.BRANCH_KEY_BATCH);
            Wire.Out out = new Wire.Out(Wire.BRANCH_PUSH).u8(Wire.BRANCH_KEYS).i32(to - from);
            for (Map.Entry<String, Change> key : keys.subList(from, to)) {
                Change change = key.getValue();
                out.str(key.getKey()).str(change.hash()).str(change.base()).str(change.content())
                        .str(change.contentBase() == null ? BranchStore.UNKNOWN : change.contentBase());
            }
            env.send(out);
        }
        Set<String> sent = new HashSet<>();
        for (Change change : branch.keys().values()) {
            if (!change.changed() || change.hash().isEmpty() || !sent.add(change.hash())) {
                continue;
            }
            byte[] bytes = store.record(head, change.hash());
            int offset = 0;
            do {
                int length = Math.min(Wire.PIECE, bytes.length - offset);
                env.throttle(length);
                boolean last = offset + length >= bytes.length;
                env.send(new Wire.Out(Wire.BRANCH_PUSH).u8(Wire.BRANCH_DATA).u8(last ? 1 : 0).bytes(bytes, offset, length));
                offset += length;
            } while (offset < bytes.length);
        }
        env.send(new Wire.Out(Wire.BRANCH_PUSH).u8(Wire.BRANCH_END).str(head));
    }
}
