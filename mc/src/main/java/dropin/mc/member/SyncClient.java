package dropin.mc.member;

import dropin.core.ledger.Deltas;
import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;
import dropin.core.merge.BranchStore;
import dropin.core.merge.Branches;
import dropin.core.merge.ChunkContent;
import dropin.core.sync.SyncClientSession;
import dropin.core.sync.SyncClientSession.Phase;
import dropin.core.sync.SyncState;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.handoff.CrashFailover;
import dropin.mc.handoff.MemberHandoff;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.menu.CoopActions;
import dropin.mc.merge.ColdMerge;
import dropin.mc.net.PathPolicy;
import dropin.mc.net.SyncConnector;
import dropin.mc.menu.ModConsentScreen;
import dropin.mc.net.SyncPipe;
import dropin.mc.policy.WorldPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.SaveFormatOld;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a member's local copy of a co-op world in step with the host while the member plays on it
 * (DESIGN 4.4, 4.6, 4.7): a second connection to the host, a full download on the first join, live
 * checkpoints afterwards, and a reconnect that continues where a cut transfer stopped.
 */
public final class SyncClient implements Runnable, SyncClientSession.Local {
    private static final String K = "text.dropin_coop.";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long JOIN_TIMEOUT_MS = 120_000;
    private static final long RETRY_MS = 3_000;
    private static final Map<String, SyncClient> ACTIVE = new ConcurrentHashMap<>();

    private final Minecraft mc;
    private final CoopWorld world;
    private final String address;
    private volatile Thread thread;
    private volatile boolean stop;
    private volatile SyncClientSession.Status status = new SyncClientSession.Status(Phase.CONNECTING, 0, 0, "");
    private boolean waitingTold;
    private volatile boolean waitingShown;
    private final java.util.concurrent.atomic.AtomicLong applied = new java.util.concurrent.atomic.AtomicLong();

    private volatile Path folderPath;
    private Path dropin;
    private Ledger ledger;
    private Manifest manifest;
    private SyncState state;
    private volatile RecordApplier applier;
    private BranchStore branches;
    private volatile SyncPipe pipe;
    /** Records this PC wrote after its last checkpoint as host: key to the hash that checkpoint had ("" = none). */
    private final Map<String, String> leftoverBase = new java.util.HashMap<>();
    /** Content hash (ChunkContent) of the chunk region records of this copy, as of its last checkpoint; for merges. */
    private Manifest content = new Manifest();
    private static volatile String lastSplit = "";

    private SyncClient(Minecraft mc, CoopWorld world, String address) {
        this.mc = mc;
        this.world = world;
        this.address = address;
    }

    /** Starts syncing the local copy of {@code world} from the host the game is connecting to at {@code address}. */
    public static void start(Minecraft mc, CoopWorld world, String address) {
        SyncClient client = new SyncClient(mc, world, address);
        CrashFailover.joining(world, address);
        SyncClient old = ACTIVE.put(world.worldId(), client);
        if (old != null) {
            old.stop = true;
        }
        Thread thread = new Thread(() -> {
            // Two syncs must never write one copy at once.
            if (old != null && !old.awaitReleased(60_000)) {
                DropIn.LOGGER.error("The earlier sync of '{}' still holds the copy; this one does not start", world.name());
                ACTIVE.remove(world.worldId(), client);
                return;
            }
            client.run();
        }, "dropin-coop-member-sync");
        thread.setDaemon(true);
        client.thread = thread;
        thread.start();
    }

    /** The live sync status of a world this client is downloading right now, or null. */
    public static SyncClientSession.Status status(String worldId) {
        SyncClient client = ACTIVE.get(worldId);
        return client == null ? null : client.status;
    }

    /**
     * Stops the sync of a world and waits until it has let go of the copy: its thread ended, its connection's
     * thread ended (no late frame can write any more) and the region files are closed. Call it before the game
     * opens the copy, and do not open it if this returns false.
     *
     * @return true if no sync holds the copy any more
     */
    public static boolean stopAndWait(String worldId, long millis) {
        SyncClient client = ACTIVE.get(worldId);
        if (client == null) {
            return true;
        }
        client.stop = true;
        boolean released = client.awaitReleased(millis);
        if (!released) {
            DropIn.LOGGER.error("The sync of '{}' did not let go of the copy within {} s", client.world.name(), millis / 1000);
        }
        return released;
    }

    private boolean awaitReleased(long millis) {
        stop = true;
        long deadline = System.currentTimeMillis() + millis;
        try {
            Thread running = thread;
            if (running != null && running != Thread.currentThread()) {
                running.join(Math.max(1, deadline - System.currentTimeMillis()));
                if (running.isAlive()) {
                    return false;
                }
            }
            SyncPipe last = pipe;
            if (last != null) {
                last.close();
                if (!last.awaitStopped(deadline - System.currentTimeMillis())) {
                    return false;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        RecordApplier open = applier;
        if (open != null && !open.isClosed()) {
            return false;
        }
        ACTIVE.remove(world.worldId(), this);
        return true;
    }

    /**
     * The sync part of a world's status line on the Co-op Worlds screen (DESIGN 4.4), or null when there
     * is nothing to say: "Downloading 63%", the wait for a direct path, or how far an unfinished copy is.
     *
     * @param folder the world's folder under saves/, or null if there is none yet
     */
    public static ITextComponent statusLine(String worldId, Path folder) {
        SyncClientSession.Status live = status(worldId);
        if (live != null) {
            switch (live.phase()) {
                case WAITING_DIRECT:
                    return new TextComponentTranslation(K + "sync.waiting_direct");
                case COMPARING, DOWNLOADING:
                    return new TextComponentTranslation(K + "sync.downloading", percent(live.have(), live.total()));
                case DIVERGED:
                    return new TextComponentTranslation(K + "sync.diverged");
                default:
                    return null;
            }
        }
        if (folder != null) {
            Path file = folder.resolve(CoopWorldStore.MARKER_DIR).resolve("state.json");
            if (Files.isRegularFile(file)) {
                SyncState saved = SyncState.load(file);
                if (!saved.complete()) {
                    return new TextComponentTranslation(K + "sync.incomplete", saved.percent());
                }
            }
        }
        return null;
    }

    /** True if the copy in this folder is an unfinished download (it must not be opened or hosted). */
    public static boolean incomplete(Path folder) {
        Path file = folder.resolve(CoopWorldStore.MARKER_DIR).resolve("state.json");
        return Files.isRegularFile(file) && !SyncState.load(file).complete();
    }

    private static int percent(int have, int total) {
        return total <= 0 ? 0 : (int) Math.min(99, have * 100L / total);
    }

    /**
     * This PC hosted the world last (DESIGN 4.9). What its shutdown wrote after the final checkpoint is in no
     * ledger entry: the chunks listed in {@code dirty.txt}, and files such as {@code level.dat}. The manifest
     * is made to say what is on disk for those records, so the comparison with the new host fetches the
     * ledger's version of each one and this copy ends up equal to everyone else's.
     */
    private void reconcileLeftover(Path manifestFile) throws IOException {
        Path leftover = dropin.resolve("dirty.txt");
        Path rescan = dropin.resolve("rescan");
        if (Files.isRegularFile(rescan)) {
            // The files changed in a way nobody recorded (a restored save point, or a mod with its own chunk
            // storage): the whole copy is hashed again.
            manifest = RecordApplier.scan(folderPath);
            manifest.save(manifestFile);
            Files.delete(rescan);
            Files.deleteIfExists(leftover);
            DropIn.LOGGER.info("The copy of '{}' was hashed again in full ({} records)", world.name(), manifest.size());
            return;
        }
        if (!Files.isRegularFile(leftover)) {
            return;
        }
        Set<String> keys = new LinkedHashSet<>(Files.readAllLines(leftover, StandardCharsets.UTF_8));
        for (String key : manifest.keys()) {
            if (RecordKey.isFile(key)) {
                keys.add(key);
            }
        }
        int differ = 0;
        for (String key : keys) {
            if (!RecordKey.isFile(key) && !RecordKey.isChunk(key)) {
                continue;
            }
            String hash;
            try {
                byte[] bytes = applier.read(key);
                hash = bytes == null ? "" : Manifest.hash(bytes);
            } catch (IOException | RuntimeException e) {
                // Unreadable here: treated as missing, so it is downloaded again.
                hash = "";
            }
            String old = manifest.get(key);
            if (hash.isEmpty() ? old != null : !hash.equals(old)) {
                leftoverBase.put(key, old == null ? "" : old);
                if (hash.isEmpty()) {
                    manifest.remove(key);
                } else {
                    manifest.put(key, hash);
                }
                differ++;
            }
        }
        if (differ > 0) {
            manifest.save(manifestFile);
        }
        Files.delete(leftover);
        DropIn.LOGGER.info("'{}' was hosted here last: {} records written after its final checkpoint will be fetched "
                + "from the new host", world.name(), differ);
    }

    @Override
    public void run() {
        try {
            if (!awaitJoin()) {
                return;
            }
            Path folder = ensureFolder(mc, world);
            folderPath = folder;
            dropin = folder.resolve(CoopWorldStore.MARKER_DIR);
            ledger = Ledger.load(world.keys().auth(), dropin.resolve("ledger.jsonl"));
            Path manifestFile = dropin.resolve("manifest").resolve("current.tsv");
            boolean hasWorld = Files.isRegularFile(folder.resolve("level.dat"));
            if (Files.isRegularFile(manifestFile)) {
                manifest = Manifest.load(manifestFile);
            } else if (hasWorld) {
                // A copy brought over by hand: hash it once, so only what differs is downloaded.
                manifest = RecordApplier.scan(folder);
            } else {
                manifest = new Manifest();
            }
            content = Manifest.load(dropin.resolve("manifest").resolve("content.tsv"));
            Path stateFile = dropin.resolve("state.json");
            if (Files.isRegularFile(stateFile)) {
                state = SyncState.load(stateFile);
            } else if (hasWorld) {
                // A whole world that was brought over by hand: it stays usable while it is being updated.
                state = new SyncState("", 0, true, 0, 0);
            } else {
                // A first download: the folder is not a usable world until one checkpoint is complete.
                state = SyncState.EMPTY;
                state.save(stateFile);
            }
            applier = new RecordApplier(folder);
            branches = new BranchStore(dropin.resolve("branches"));
            reconcileLeftover(manifestFile);
            DropIn.LOGGER.info("World sync of '{}' started: {} records here, checkpoint {}", world.name(),
                    manifest.size(), ledger.headSeq());
            int failures = 0;
            while (connected()) {
                Phase ended = connectOnce();
                if (ended == Phase.DIVERGED) {
                    DropIn.LOGGER.warn("The local copy of '{}' and the host's copy have split; it is left as it is", world.name());
                    break;
                }
                failures = ended == Phase.FAILED || ended == Phase.CONNECTING ? failures + 1 : 0;
                if (!connected() || failures > 20) {
                    break;
                }
                Thread.sleep(RETRY_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            DropIn.LOGGER.error("World sync of '{}' stopped", world.name(), e);
        } finally {
            if (applier != null) {
                try {
                    // From here on a late frame fails instead of reopening the region files.
                    applier.close();
                } catch (IOException e) {
                    DropIn.LOGGER.error("Could not close the local copy of '{}'", world.name(), e);
                }
            }
            // The connection's thread may still be working through queued frames (the ledger, the manifest).
            SyncPipe last = pipe;
            boolean pipeDone = true;
            if (last != null) {
                last.close();
                try {
                    pipeDone = last.awaitStopped(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pipeDone = last.isStopped();
                }
            }
            if (pipeDone) {
                ACTIVE.remove(world.worldId(), this);
            } else {
                // Stays registered, so stopAndWait keeps refusing to hand the copy to the game until it ends.
                DropIn.LOGGER.error("The sync connection of '{}' is still busy after it was stopped", world.name());
            }
            thread = null;
        }
    }

    /** One sync connection, from connect to close. Returns the phase it ended in. */
    private Phase connectOnce() throws InterruptedException {
        SyncPipe pipe = new SyncPipe("dropin-sync-client");
        this.pipe = pipe;
        SyncClientSession session = new SyncClientSession(this, pipe, RANDOM);
        CountDownLatch closed = new CountDownLatch(1);
        pipe.endpoint(new SyncPipe.Endpoint() {
            @Override
            public void onFrame(byte[] frame) {
                session.onFrame(frame);
            }

            @Override
            public void onClosed() {
                session.onClosed();
                closed.countDown();
            }
        });
        waitingTold = false;
        try {
            direct = PathPolicy.direct(SyncConnector.connect(address, pipe));
        } catch (IOException e) {
            DropIn.LOGGER.warn("World sync of '{}' could not connect: {}", world.name(), e.toString());
            pipe.close();
            return Phase.CONNECTING;
        }
        pipe.execute(session::start);
        while (!closed.await(250, TimeUnit.MILLISECONDS)) {
            if (!connected()) {
                pipe.execute(session::close);
                closed.await(10, TimeUnit.SECONDS);
                break;
            }
        }
        return session.phase();
    }

    private boolean awaitJoin() throws InterruptedException {
        long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS;
        while (!stop && System.currentTimeMillis() < deadline) {
            if (connected()) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    /** True while this player is in a remote session (the local copy is therefore not open). */
    private boolean connected() {
        return !stop && mc.player != null && mc.getIntegratedServer() == null && mc.getConnection() != null;
    }

    /**
     * The world's folder under saves/; a new one on the first join. A new folder is marked as an unfinished
     * download at once (DESIGN 4.7), so it is never taken for a world before a checkpoint is complete.
     */
    public static synchronized Path ensureFolder(Minecraft mc, CoopWorld world) throws IOException {
        Path saves = savesDir(mc);
        String known = CoopActions.folders(mc).get(world.worldId());
        if (known != null) {
            return saves.resolve(known);
        }
        String base = world.name().replaceAll("[^A-Za-z0-9 _.()-]", "_").trim();
        if (base.isEmpty() || base.startsWith(".")) {
            base = "Co-op world";
        }
        Path folder = saves.resolve(base);
        for (int i = 2; Files.exists(folder); i++) {
            folder = saves.resolve(base + " (" + i + ")");
        }
        Files.createDirectories(folder);
        CoopWorlds.store().attachFolder(folder, world.worldId());
        SyncState.EMPTY.save(folder.resolve(CoopWorldStore.MARKER_DIR).resolve("state.json"));
        return folder;
    }

    /**
     * 1.12.2 port: the saves/ folder of the game's save format (26.x {@code LevelStorageSource.getBaseDir()}).
     * The client's ISaveFormat is an AnvilSaveConverter (a SaveFormatOld) over {@code gameDir/saves}.
     */
    private static Path savesDir(Minecraft mc) {
        ISaveFormat format = mc.getSaveLoader();
        if (format instanceof SaveFormatOld old) {
            return old.savesDirectory.toPath();
        }
        return mc.gameDir.toPath().resolve("saves");
    }

    // ---- SyncClientSession.Local ----------------------------------------------------------------

    @Override
    public String worldId() {
        return world.worldId();
    }

    @Override
    public byte[] auth() {
        return world.keys().auth();
    }

    @Override
    public byte[] enc() {
        return world.keys().enc();
    }

    @Override
    public String playerUuid() {
        return mc.getSession().getProfile().getId().toString();
    }

    @Override
    public Ledger ledger() {
        return ledger;
    }

    @Override
    public Manifest manifest() {
        return manifest;
    }

    @Override
    public void apply(String key, byte[] bytes) throws IOException {
        applier.apply(key, bytes);
        if (ChunkContent.applies(key)) {
            content.put(key, ChunkContent.hash(key, bytes));
        }
        applied.incrementAndGet();
    }

    @Override
    public void delete(String key) throws IOException {
        applier.delete(key);
        content.remove(key);
    }

    @Override
    public void flush() throws IOException {
        applier.flush();
        manifest.save(dropin.resolve("manifest").resolve("current.tsv"));
        content.save(dropin.resolve("manifest").resolve("content.tsv"));
        state = new SyncState(state.head(), state.syncedThrough(), state.complete(), status.have(), status.total());
        state.save(dropin.resolve("state.json"));
    }

    @Override
    public void committed(LedgerEntry head) throws IOException {
        int size = manifest.size();
        state = new SyncState(head.hash(), head.seq(), true, size, size);
        state.save(dropin.resolve("state.json"));
        DropIn.LOGGER.info("The local copy of '{}' is complete through checkpoint {} (root {})", world.name(),
                head.seq(), head.root());
        // A branch the main world has merged by now is no longer pending here either.
        for (String branch : branches.heads()) {
            if (ledger.hasMerged(branch)) {
                branches.delete(branch);
            }
        }
    }

    // ---- DESIGN 4.10: this copy left the main world's lineage -------------------------------------

    @Override
    public BranchStore branches() {
        return branches;
    }

    @Override
    public boolean branchWanted(String head) {
        return !ledger.hasMerged(head) && !ledger.contains(head);
    }

    @Override
    public void branchReceived(String head) {
        DropIn.LOGGER.info("The host of '{}' passed on a preserved offline session ({}); it is merged when the world starts next",
                world.name(), head.substring(0, 16));
    }

    @Override
    public void beforeUpload(int bytes) throws InterruptedException {
        SyncPipe open = pipe;
        if (open != null) {
            open.awaitWritable(30_000);
        }
    }

    /**
     * The host's world is the main one. What this copy changed since the last checkpoint both share is kept as
     * a branch under {@code dropin/branches/<head>/}; if the deltas no longer say what that is, the whole copy
     * is saved as a separate world instead. Then the ledger is cut back to the shared checkpoint, and the sync
     * brings the copy to the host's state. The copy is not open: it is read through the sync's own storage.
     */
    @Override
    public boolean split(LedgerEntry ancestor) throws IOException {
        LedgerEntry head = ledger.last();
        applier.flush();
        Path deltas = dropin.resolve("deltas");
        Map<String, Branches.Change> changed = ancestor == null ? null : Branches.changedSince(ancestor.seq(), head.seq(), seq -> {
            try {
                return Files.isRegularFile(Deltas.file(deltas, seq)) ? Deltas.readFull(deltas, seq) : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        });
        if (changed == null) {
            String saved = ColdMerge.saveSeparate(mc, world, manifest, applier, head.name());
            lastSplit = "separate:" + saved;
            DropIn.LOGGER.warn("The copy of '{}' left the main world at a checkpoint its deltas no longer reach; it was saved as the "
                    + "separate world '{}' and now follows the host", world.name(), saved);
            chat(new TextComponentTranslation(K + "merge.too_old", saved));
        } else {
            Map<String, Branches.Change> keys = Branches.bundle(changed,
                    key -> leftoverBase.containsKey(key) ? emptyToNull(leftoverBase.get(key)) : manifest.get(key),
                    key -> {
                        // As of this copy's last checkpoint; a copy from before content hashes were kept reads the record.
                        String known = content.get(key);
                        if (known != null || !ChunkContent.applies(key)) {
                            return known != null ? known : manifest.get(key);
                        }
                        try {
                            return emptyToNull(ChunkContent.hash(key, applier.read(key)));
                        } catch (IOException | RuntimeException e) {
                            return null;
                        }
                    });
            BranchStore.Writer writer = branches.begin(head.hash(), ancestor.hash(), head);
            int records = 0;
            try {
                for (Map.Entry<String, Branches.Change> entry : keys.entrySet()) {
                    byte[] bytes;
                    try {
                        bytes = applier.read(entry.getKey());
                    } catch (IOException | RuntimeException e) {
                        bytes = null;
                    }
                    // What is on disk now: also what this PC wrote after its last checkpoint.
                    Branches.Change now = new Branches.Change(bytes == null ? "" : Manifest.hash(bytes), entry.getValue().base(),
                            ChunkContent.hash(entry.getKey(), bytes), entry.getValue().contentBase());
                    writer.keys(Map.of(entry.getKey(), now));
                    if (bytes != null && now.changed()) {
                        writer.record(bytes);
                        records++;
                    }
                }
                writer.finish();
            } catch (IOException | RuntimeException e) {
                writer.abort();
                throw e;
            }
            lastSplit = "branch:" + head.hash();
            DropIn.LOGGER.info("The copy of '{}' left the main world after checkpoint {}: {} records of {}'s session are preserved as "
                    + "branch {} and are merged when the world starts next", world.name(), ancestor.seq(), records, head.name(),
                    head.hash().substring(0, 16));
            chat(new TextComponentTranslation(K + "merge.preserved"));
        }
        ledger.truncate(ancestor == null ? 0 : ancestor.seq());
        state = new SyncState(ancestor == null ? "" : ancestor.hash(), ancestor == null ? 0 : ancestor.seq(), state.complete(),
                state.have(), state.total());
        state.save(dropin.resolve("state.json"));
        leftoverBase.clear();
        return true;
    }

    private static String emptyToNull(String hash) {
        return hash == null || hash.isEmpty() ? null : hash;
    }

    private void chat(ITextComponent message) {
        mc.addScheduledTask(() -> {
            if (mc.player != null) {
                mc.ingameGUI.getChatGUI().printChatMessage(message);
            }
        });
    }

    /** For the self-tests: what the last split on this PC did, "branch:&lt;head&gt;" or "separate:&lt;folder&gt;"; "" if none. */
    public static String lastSplit() {
        return lastSplit;
    }

    @Override
    public long policyVersion() {
        WorldPolicy.Held mine = WorldPolicy.load(world, folderPath);
        return mine == null ? 0 : mine.version();
    }

    /**
     * DESIGN 4.14 "Mods changed while people are connected": a newer policy is stored, and if this PC's mods
     * no longer match it, the game leaves the session and shows what is needed.
     */
    @Override
    public String[] policyReceived(String policyJson, String manifestJson) {
        try {
            WorldPolicy.Exchange exchange = WorldPolicy.receive(world, folderPath, () -> folderPath, policyJson, manifestJson);
            WorldPolicy.Held now = exchange.effective();
            if (exchange.adopted() && (WorldPolicy.environmentProblem(now) != null || !WorldPolicy.diff(now).mayJoin())) {
                stop = true;
                ModConsentScreen.leaveAndShow(world, now);
            }
            return exchange.reply();
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.warn("The host of '{}' sent a policy that is not valid: {}", world.name(), e.getMessage());
            return null;
        }
    }

    @Override
    public String membersToOffer() {
        try {
            return CoopWorldStore.membersJson(CoopWorlds.store().get(world.worldId()).orElse(world).members());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void membersReceived(String json) {
        try {
            WorldPolicy.Held mine = WorldPolicy.load(world, folderPath);
            CoopWorlds.store().mergeMembers(world.worldId(), CoopWorldStore.parseMembers(json),
                    mine == null ? "" : mine.policy().ownerUuid());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.warn("Could not merge the member list of '{}'", world.name(), e);
        }
    }

    /** DESIGN 4.9: the host is leaving. This sync ends; the handoff code takes the game from here. */
    @Override
    public void speedTestAsked() {
        // Development only (DESIGN 4.12): makes this player look slow, so a self-test can see who is chosen.
        long delay = DropIn.switches().read("dropin.dev.speedDelayMs").map(Long::parseLong).orElse(0L);
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** DESIGN 4.9 (crash): the host's list of members, kept for the moment the host vanishes. */
    @Override
    public void peers(java.util.List<dropin.core.sync.Election.Peer> peers) {
        CrashFailover.peers(world.worldId(), peers);
    }

    /** Whether the running sync connection of a world goes straight to the host; null if there is none yet. */
    public static Boolean direct(String worldId) {
        SyncClient client = ACTIVE.get(worldId);
        return client == null ? null : client.direct;
    }

    /** The newest checkpoint the running sync of a world has applied in full, or null. */
    public static LedgerEntry lastCheckpoint(String worldId) {
        SyncClient client = ACTIVE.get(worldId);
        Ledger held = client == null ? null : client.ledger;
        return held == null ? null : held.last();
    }

    private volatile Boolean direct;

    @Override
    public void handoff(String successorUuid, LedgerEntry finalEntry, boolean applied) {
        stop = true;
        // The game connection closes right after this: that is the handoff, not a lost host.
        CrashFailover.expected();
        MemberHandoff.received(mc, world, successorUuid, finalEntry, applied);
    }

    @Override
    public void status(SyncClientSession.Status now) {
        status = now;
        if (now.phase() == Phase.WAITING_DIRECT && !waitingTold) {
            waitingTold = true;
            DropIn.LOGGER.info("World sync of '{}' is waiting for a direct connection", world.name());
            mc.addScheduledTask(() -> {
                if (mc.player != null) {
                    mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation(K + "sync.waiting_direct"));
                    waitingShown = true;
                }
            });
        } else if (now.phase() == Phase.FAILED) {
            DropIn.LOGGER.warn("World sync of '{}' failed: {}", world.name(), now.detail());
        }
    }

    /** How many records the running sync of a world has written into the local copy so far. */
    public static long appliedRecords(String worldId) {
        SyncClient client = ACTIVE.get(worldId);
        return client == null ? 0 : client.applied.get();
    }

    /** True once the "waiting for a direct connection" line was put into this player's chat. */
    public static boolean waitingShown(String worldId) {
        SyncClient client = ACTIVE.get(worldId);
        return client != null && client.waitingShown;
    }

    /** A chunk record as it is in the local copy right now, read through the running sync's own storage. */
    public static byte[] localChunk(String worldId, String key) throws IOException {
        SyncClient client = ACTIVE.get(worldId);
        RecordApplier open = client == null ? null : client.applier;
        return open == null ? null : open.readChunk(key);
    }

    /** For the self-tests: the root hash of the local copy as recorded, and its checkpoint. */
    public static SyncState savedState(Path folder) {
        return SyncState.load(folder.resolve(CoopWorldStore.MARKER_DIR).resolve("state.json"));
    }
}
