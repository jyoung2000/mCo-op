package dropin.mc.host;

import dropin.core.crypto.WorldKeys;
import dropin.core.ledger.Deltas;
import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;
import dropin.core.pkarr.RendezvousRecord;
import dropin.core.sync.Successor;
import dropin.core.sync.SyncServerSession;
import dropin.core.world.Member;
import dropin.core.sync.SyncState;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.net.PathPolicy;
import dropin.core.mods.ModManifest;
import dropin.mc.menu.ModConsentScreen;
import dropin.mc.net.SyncPipe;
import dropin.mc.policy.DevHooks;
import dropin.mc.policy.WorldPolicy;
import io.netty.channel.Channel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The sync half of a host session (DESIGN 4.5 to 4.7): checkpoints the hosted world every interval and
 * when the host quits, keeps its manifest and ledger, and serves diffs and records to the members'
 * sync connections. Everything is read through the game's storage objects or, for the non-chunk
 * files, from the world folder after a save.
 */
public final class HostSync implements SyncServerSession.Host {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final long SAVE_TIMEOUT_MS = 120_000;
    private static final long READ_TIMEOUT_S = 60;
    /** How long a quitting host waits for the members to take its last checkpoint. */
    private static final long QUIT_WAIT_MS = 10_000;
    /** DESIGN 4.9: how long a host that hands the world over waits for the members to confirm its last checkpoint. */
    private static final long HANDOFF_WAIT_MS = 30_000;

    /** Publishes the session's last rendezvous record: "handoff" with the successor, or "idle". */
    public interface Announcer {
        void announce(String state, String successorUuid);
    }

    /**
     * Who could take over right now (DESIGN 4.9).
     *
     * @param successorUuid the fully synced peer that would host next, or null if nobody has a full copy
     * @param peers         connected members (sync connections that finished the handshake)
     * @param bestName      the member whose copy is furthest along, "" if unknown
     * @param bestPercent   how far that copy is, 0 to 99
     */
    public record Standing(String successorUuid, int peers, String bestName, int bestPercent) {
    }

    private static volatile HostSync current;

    /**
     * What the checkpoints since the last {@link #takePerf()} cost. {@code saveMs} is time the server thread was
     * held by the save (no game ticks run meanwhile); {@code workMs} is the hashing and writing off that thread.
     */
    public record Perf(int checkpoints, long saveMsSum, long saveMsMax, long workMsSum, long workMsMax, long cpuMsSum,
                       long changed) {
    }

    private final int devDirectRate;
    private static final long SPEED_TEST_EVERY_MS = 5 * 60_000L;

    private record Peer(SyncServerSession session, SyncPipe pipe) {
    }

    private final MinecraftServer server;
    private final CoopWorld world;
    private final WorldKeys keys;
    private final Path root;
    private final Path dropin;
    private final String hostUuid;
    private final String hostName;
    private final String sess;
    private final Ledger ledger;
    private final Manifest manifest;
    private final Map<String, long[]> fileStamps = new HashMap<>();
    private final List<Peer> peers = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private final ReentrantLock checkpointLock = new ReentrantLock();
    private final AtomicLong closedRecordFrames = new AtomicLong();
    private final AtomicLong closedRecordBytes = new AtomicLong();
    private final long intervalMs;
    private final long devDelayMs;
    private volatile boolean needFullScan;
    private volatile boolean stopping;
    private volatile boolean finished;
    private final Object policyLock = new Object();
    private volatile WorldPolicy.Held held;
    private volatile String membersShared = "";
    private final Object announceLock = new Object();
    private final Object perfLock = new Object();
    private volatile boolean returning;
    private int perfCheckpoints;
    private long perfSaveSum;
    private long perfSaveMax;
    private long perfWorkSum;
    private long perfWorkMax;
    private long perfCpuSum;
    private boolean unseenWritesTold;
    private long perfChanged;
    private volatile Announcer announcer;
    private boolean finalAnnounceTaken;
    /** The quit flow ran and the player decided to leave; {@link #expectedSuccessor} is who should take over (or null). */
    private volatile boolean quitDecided;
    private volatile String expectedSuccessor;
    private volatile String handedTo;
    private static volatile String lastHandedTo;
    private static volatile String lastFinalRoot;

    private HostSync(MinecraftServer server, CoopWorld world, Path root, Ledger ledger, Manifest manifest,
                     String hostUuid, String hostName, String sess) {
        this.server = server;
        this.world = world;
        this.keys = world.keys();
        this.root = root;
        this.dropin = root.resolve(CoopWorldStore.MARKER_DIR);
        this.ledger = ledger;
        this.manifest = manifest;
        this.hostUuid = hostUuid;
        this.hostName = hostName;
        this.sess = sess;
        this.held = WorldPolicy.load(world, root);
        this.needFullScan = takeRescan(root) || ledger.size() == 0 || manifest.isEmpty()
                || !manifest.root().equals(ledger.last().root());
        long seconds = DropIn.switches().read("dropin.dev.checkpointSeconds").map(Long::parseLong)
                .orElseGet(() -> (long) CoopWorlds.configInt("checkpointSeconds", 60));
        this.intervalMs = Math.max(5, seconds) * 1000;
        this.devDelayMs = DropIn.switches().read("dropin.dev.syncDelayMs").map(Long::parseLong).orElse(0L);
        // Development only (DESIGN 4.12): the direct cap without writing a config file.
        this.devDirectRate = DropIn.switches().read("dropin.dev.directRateKBps").map(Integer::parseInt).orElse(-1);
    }

    /** The sync state of the co-op world this game hosts, or null. */
    public static HostSync current() {
        return current;
    }

    /** Called once the hosted world is up, before it is opened to LAN. */
    public static void start(MinecraftServer server, CoopWorld world, String hostUuid, String hostName, String sess) {
        start(server, world, hostUuid, hostName, sess, null);
    }

    /** @param announcer publishes this session's last rendezvous record when the server stops (null: none) */
    public static void start(MinecraftServer server, CoopWorld world, String hostUuid, String hostName, String sess,
                             Announcer announcer) {
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path dropin = root.resolve(CoopWorldStore.MARKER_DIR);
        ChunkStores.track(root);
        try {
            Ledger ledger = Ledger.load(world.keys().auth(), dropin.resolve("ledger.jsonl"));
            Manifest manifest = Manifest.load(dropin.resolve("manifest").resolve("current.tsv"));
            Path leftover = dropin.resolve("dirty.txt");
            if (Files.isRegularFile(leftover)) {
                // Chunks the last session wrote after its final checkpoint.
                ChunkStores.markDirty(root, Files.readAllLines(leftover, StandardCharsets.UTF_8));
                Files.delete(leftover);
            }
            HostSync sync = new HostSync(server, world, root, ledger, manifest, hostUuid, hostName, sess);
            sync.announcer = announcer;
            current = sync;
            Thread thread = new Thread(sync::run, "dropin-coop-sync");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("World sync of '{}' could not start; friends can play but will not get a copy", world.name(), e);
        }
    }

    private void run() {
        long next = 0;
        while (!stopping && server.isRunning()) {
            try {
                long now = System.currentTimeMillis();
                if (now >= next) {
                    checkpoint(true);
                    next = System.currentTimeMillis() + intervalMs;
                }
                for (Peer peer : peers) {
                    peer.session().refresh();
                    // DESIGN 4.9: who could host best. Only where bulk data may flow, never through a relay unasked.
                    if (peer.session().syncedSeq() > 0 && bulkAllowed(peer.session())) {
                        peer.session().speedTestIfDue(SPEED_TEST_EVERY_MS);
                    }
                }
                broadcastPeers(false);
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                DropIn.LOGGER.error("Checkpoint of '{}' failed", world.name(), e);
                next = System.currentTimeMillis() + intervalMs;
            }
        }
    }

    // ---- checkpoints --------------------------------------------------------------------------

    /**
     * Takes a checkpoint now (DESIGN 4.5): saves, re-hashes what was written since the last one, and if
     * anything changed appends a ledger entry and pushes it to the members.
     *
     * @return the new entry, or null if nothing changed
     */
    public LedgerEntry checkpoint(boolean save) throws IOException, InterruptedException {
        AtomicLong saveNanos = new AtomicLong();
        if (save && !server.isSameThread()) {
            CompletableFuture<Void> saved = server.submit(() -> {
                long from = System.nanoTime();
                server.saveEverything(true, false, false);
                saveNanos.set(System.nanoTime() - from);
            });
            long deadline = System.currentTimeMillis() + SAVE_TIMEOUT_MS;
            while (!saved.isDone()) {
                if (stopping || System.currentTimeMillis() > deadline) {
                    return null;
                }
                Thread.sleep(50);
            }
        }
        long saveMs = saveNanos.get() / 1_000_000;
        long started = 0;
        long cpuFrom = threadCpuNanos();
        Map<String, String> changed = new LinkedHashMap<>();
        checkpointLock.lock();
        try {
            if (finished) {
                return null;
            }
            boolean full = needFullScan || unseenWrites();
            started = System.currentTimeMillis();
            Set<String> chunkKeys = ChunkStores.drain(root);
            if (full) {
                chunkKeys.addAll(allChunkKeys());
                for (String key : manifest.keys()) {
                    if (RecordKey.isChunk(key)) {
                        chunkKeys.add(key);
                    }
                }
            }
            List<String> failed = new ArrayList<>();
            Exception firstFailure = null;
            for (String key : chunkKeys) {
                try {
                    byte[] bytes = readChunk(key);
                    String hash = bytes == null ? "" : Manifest.hash(bytes);
                    String old = manifest.get(key);
                    if (hash.isEmpty() ? old != null : !hash.equals(old)) {
                        changed.put(key, hash);
                    }
                } catch (IOException | RuntimeException e) {
                    if (firstFailure == null) {
                        firstFailure = e;
                    }
                    failed.add(key);
                }
            }
            if (firstFailure != null) {
                // Not silent: until these are read, the members do not have them.
                DropIn.LOGGER.warn("Checkpoint of '{}': {} chunk records could not be read (first: {}); they are tried again "
                        + "at the next checkpoint", world.name(), failed.size(), failed.get(0), firstFailure);
            }
            ChunkStores.markDirty(root, failed);
            scanFiles(changed);
            if (changed.isEmpty() && ledger.size() > 0) {
                needFullScan = false;
                return null;
            }
            LedgerEntry entry;
            synchronized (lock) {
                changed.forEach((key, hash) -> {
                    if (hash.isEmpty()) {
                        manifest.remove(key);
                    } else {
                        manifest.put(key, hash);
                    }
                });
                entry = LedgerEntry.create(keys.auth(), ledger.headSeq() + 1, ledger.head(), hostUuid, hostName, sess,
                        System.currentTimeMillis() / 1000, manifest.root(), changed.size());
                Deltas.write(dropin.resolve("deltas"), entry.seq(), changed);
                ledger.append(entry);
                manifest.save(dropin.resolve("manifest").resolve("current.tsv"));
                int size = manifest.size();
                new SyncState(entry.hash(), entry.seq(), true, size, size).save(dropin.resolve("state.json"));
                for (Peer peer : peers) {
                    peer.session().checkpoint(entry, changed);
                }
            }
            broadcastPeers(true);
            needFullScan = false;
            DropIn.LOGGER.info("Checkpoint {} of '{}': {} records changed{}, root {}, save {} ms, {} ms", entry.seq(),
                    world.name(), changed.size(), full ? " (full scan)" : "", entry.root(), saveMs,
                    System.currentTimeMillis() - started);
            return entry;
        } finally {
            checkpointLock.unlock();
            if (started != 0) {
                notePerf(saveMs, System.currentTimeMillis() - started, (threadCpuNanos() - cpuFrom) / 1_000_000, changed.size());
            }
        }
    }

    /**
     * DESIGN 4.16: a restore changed the files of the closed world and left {@code dropin/rescan}. The first
     * checkpoint then compares every record with the manifest, so the members get exactly what changed.
     */
    private static boolean takeRescan(Path root) {
        try {
            return Files.deleteIfExists(root.resolve(CoopWorldStore.MARKER_DIR).resolve("rescan"));
        } catch (IOException e) {
            return true;
        }
    }

    /** DESIGN 4.16: this host is about to close the world, restore a save point and host again. */
    public void returning() {
        returning = true;
    }

    /** The world folder. */
    public Path folder() {
        return root;
    }

    /** A copy of the record hashes as of the last checkpoint. */
    public Manifest manifestSnapshot() {
        synchronized (lock) {
            Manifest copy = new Manifest();
            for (String key : manifest.keys()) {
                copy.put(key, manifest.get(key));
            }
            return copy;
        }
    }

    /** CPU time this thread has used; 0 where the JVM does not measure it. */
    private static long threadCpuNanos() {
        java.lang.management.ThreadMXBean threads = java.lang.management.ManagementFactory.getThreadMXBean();
        return threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : 0;
    }

    private void notePerf(long saveMs, long workMs, long cpuMs, int changed) {
        synchronized (perfLock) {
            perfCheckpoints++;
            perfSaveSum += saveMs;
            perfSaveMax = Math.max(perfSaveMax, saveMs);
            perfWorkSum += workMs;
            perfWorkMax = Math.max(perfWorkMax, workMs);
            perfCpuSum += cpuMs;
            perfChanged += changed;
        }
    }

    /**
     * True when another mod replaced the game's chunk storage worker with its own class. Which chunks were
     * written is then unknown, so every checkpoint compares all of them: slower, but nothing is missed.
     */
    private boolean unseenWrites() {
        String writer = ChunkStores.unseenWriter(root);
        if (writer != null && !unseenWritesTold) {
            unseenWritesTold = true;
            DropIn.LOGGER.warn("Another mod stores the chunks of '{}' with its own class ({}). DropIn cannot see which chunks "
                    + "it writes, so every checkpoint compares all chunks of the world: correct, but more work.", world.name(), writer);
        }
        return writer != null;
    }

    /** The cost of the checkpoints since the last call; the counters start again from zero. */
    public Perf takePerf() {
        synchronized (perfLock) {
            Perf perf = new Perf(perfCheckpoints, perfSaveSum, perfSaveMax, perfWorkSum, perfWorkMax, perfCpuSum, perfChanged);
            perfCheckpoints = 0;
            perfSaveSum = 0;
            perfSaveMax = 0;
            perfWorkSum = 0;
            perfWorkMax = 0;
            perfCpuSum = 0;
            perfChanged = 0;
            return perf;
        }
    }

    /** Every chunk position of every region file of the world, by file name only: the files are not opened. */
    private Set<String> allChunkKeys() throws IOException {
        Set<String> all = new HashSet<>();
        for (ChunkStores.Store store : ChunkStores.of(root)) {
            if (!Files.isDirectory(store.dir())) {
                continue;
            }
            try (Stream<Path> files = Files.list(store.dir())) {
                for (Path file : files.toList()) {
                    Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());
                    if (matcher.matches()) {
                        int rx = Integer.parseInt(matcher.group(1));
                        int rz = Integer.parseInt(matcher.group(2));
                        for (int index = 0; index < 1024; index++) {
                            all.add(RecordKey.chunk(store.dim(), store.kind(), rx * 32 + (index & 31), rz * 32 + (index >> 5)));
                        }
                    }
                }
            }
        }
        return all;
    }

    /** The chunk as the game's storage worker has it (a pending write, else what is on disk), or null. */
    private byte[] readChunk(String key) throws IOException {
        RecordKey.Chunk chunk = RecordKey.parseChunk(key);
        ChunkStores.Store store = ChunkStores.find(root, chunk.dim(), chunk.kind());
        if (store == null) {
            return null;
        }
        try {
            Optional<CompoundTag> tag = store.worker().loadAsync(new ChunkPos(chunk.cx(), chunk.cz()))
                    .get(READ_TIMEOUT_S, TimeUnit.SECONDS);
            return tag.map(ChunkBytes::canonical).orElse(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted reading " + key);
        } catch (Exception e) {
            throw new IOException("Could not read " + key, e);
        }
    }

    /** Re-hashes the file records whose size or modification time changed, and notes the ones that are gone. */
    private void scanFiles(Map<String, String> changed) throws IOException {
        Set<String> seen = new HashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String relative = root.relativize(dir).toString().replace('\\', '/');
                boolean regionFolder = relative.startsWith("dimensions/") && RecordKey.KINDS.contains(dir.getFileName().toString());
                return relative.equals(CoopWorldStore.MARKER_DIR) || regionFolder
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!RecordKey.fileAllowed(relative, attrs.size())) {
                    return FileVisitResult.CONTINUE;
                }
                String key = RecordKey.file(relative);
                seen.add(key);
                long[] stamp = {attrs.size(), attrs.lastModifiedTime().toMillis()};
                long[] old = fileStamps.get(key);
                String known = manifest.get(key);
                if (old != null && old[0] == stamp[0] && old[1] == stamp[1] && known != null) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    String hash = Manifest.hash(Files.readAllBytes(file));
                    fileStamps.put(key, stamp);
                    if (!hash.equals(known)) {
                        changed.put(key, hash);
                    }
                } catch (IOException e) {
                    // Being written right now: the next checkpoint takes it.
                    fileStamps.remove(key);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        for (String key : manifest.keys()) {
            if (RecordKey.isFile(key) && !seen.contains(key)) {
                changed.put(key, "");
                fileStamps.remove(key);
            }
        }
    }

    // ---- quitting -----------------------------------------------------------------------------

    /** Head of {@code MinecraftServer.stopServer}, on the server thread. */
    public static void serverStopping(MinecraftServer server) {
        HostSync sync = current;
        if (sync == null || sync.server != server) {
            return;
        }
        sync.stopping = true;
        synchronized (sync.announceLock) {
            // From here on this class publishes the last rendezvous record ("handoff" or "idle"), not HostSession.
            sync.finalAnnounceTaken = true;
        }
        try {
            if (server.getPlayerList() != null) {
                server.saveEverything(true, true, false);
            }
            sync.checkpoint(false);
            long seq = sync.ledger.headSeq();
            // DESIGN 4.9 step 1: 30 s for a handoff. A host that already heard "nobody has a full copy" and quits
            // anyway waits only the short time of a plain quit.
            boolean hopeless = sync.quitDecided && sync.expectedSuccessor == null;
            sync.awaitPeers(seq, sync.returning || handoffDisabled() || hopeless ? QUIT_WAIT_MS : HANDOFF_WAIT_MS);
            if (sync.returning || !handoffDisabled()) {
                sync.handOver(seq);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            DropIn.LOGGER.error("Final checkpoint of '{}' failed", sync.world.name(), e);
        }
        LedgerEntry last = sync.ledger.last();
        lastFinalRoot = last == null ? "" : last.root();
        lastHandedTo = sync.handedTo == null ? "" : sync.handedTo;
        Announcer announcer = sync.announcer;
        if (announcer != null) {
            announcer.announce(sync.handedTo == null ? RendezvousRecord.IDLE : RendezvousRecord.HANDOFF,
                    sync.handedTo == null ? "" : sync.handedTo);
        }
        sync.finished = true;
    }

    /** DESIGN 4.9 steps 2 and 3: choose the successor among the peers that have the final checkpoint and tell everyone. */
    private void handOver(long seq) {
        LedgerEntry last = ledger.last();
        if (returning) {
            // DESIGN 4.16: the successor is this host itself. The members wait for its next session.
            if (last == null) {
                return;
            }
            synchronized (lock) {
                for (Peer peer : peers) {
                    peer.session().handoff(hostUuid, last);
                }
            }
            handedTo = hostUuid;
            DropIn.LOGGER.info("'{}' closes for a restore at checkpoint {}; the members wait for this host", world.name(), last.seq());
            return;
        }
        Standing standing = standing(seq);
        if (standing.successorUuid() == null || last == null) {
            if (standing.peers() > 0) {
                DropIn.LOGGER.warn("Nobody else has a full copy of '{}' ({} {}%): the session ends here", world.name(),
                        standing.bestName(), standing.bestPercent());
            }
            return;
        }
        synchronized (lock) {
            for (Peer peer : peers) {
                peer.session().handoff(standing.successorUuid(), last);
            }
        }
        handedTo = standing.successorUuid();
        DropIn.LOGGER.info("Handing '{}' over to {} at checkpoint {} (root {})", world.name(), standing.successorUuid(),
                last.seq(), last.root());
    }

    /** Development only (DESIGN 4.12): a host with this switch quits without handing the world over. */
    public static boolean handoffDisabled() {
        return DropIn.switches().flag("dropin.dev.noHandoff");
    }

    /**
     * The first half of a graceful quit (DESIGN 4.9 steps 1 and 2), while the world is still running, so the
     * player can still cancel: a checkpoint, the wait for the members, and who would take over. Not on the
     * server thread.
     */
    public Standing prepareHandoff() throws IOException, InterruptedException {
        checkpoint(true);
        long seq = ledger.headSeq();
        awaitPeers(seq, HANDOFF_WAIT_MS);
        return standing(seq);
    }

    /** The player decided to quit after {@link #prepareHandoff}; {@code successorUuid} is null for "quit anyway". */
    public void quitDecided(String successorUuid) {
        expectedSuccessor = successorUuid;
        quitDecided = true;
    }

    private volatile String peersSent = "";

    /**
     * DESIGN 4.9 (crash): every member is told what this host knows about all of them, at each checkpoint and
     * whenever the list changed (someone joined, left, finished a checkpoint or was measured). If this host
     * vanishes, the members order themselves by the last list they got.
     *
     * @param always false: only if the list differs from the one sent last
     */
    private void broadcastPeers(boolean always) {
        List<dropin.core.sync.Election.Peer> list = new ArrayList<>();
        List<SyncServerSession> ready = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (Peer peer : peers) {
            SyncServerSession session = peer.session();
            if (!session.ready() || session.playerUuid().isEmpty()) {
                continue;
            }
            ready.add(session);
            key.append(System.identityHashCode(session)).append(' ');
            if (!session.playerUuid().equals(hostUuid)) {
                list.add(new dropin.core.sync.Election.Peer(session.playerUuid(), session.syncedSeq(), session.uploadKBps()));
            }
        }
        String now = key.append(list).toString();
        if (!always && now.equals(peersSent)) {
            return;
        }
        peersSent = now;
        for (SyncServerSession session : ready) {
            session.peers(list);
        }
    }

    /** When the newest checkpoint was taken (epoch seconds); 0 if there is none yet. */
    public long lastCheckpointSeconds() {
        LedgerEntry last = ledger.last();
        return last == null ? 0 : last.ts();
    }

    /** How much of the world the member that is furthest behind holds (0 to 100); -1 if nobody is connected. */
    public int slowestPeerPercent() {
        int slowest = -1;
        long seq = ledger.headSeq();
        for (Peer peer : peers) {
            SyncServerSession session = peer.session();
            if (session.ready() && !session.playerUuid().isEmpty() && !session.playerUuid().equals(hostUuid)) {
                int percent = session.syncedSeq() >= seq && seq > 0 ? 100 : Math.min(99, session.peerPercent());
                slowest = slowest < 0 ? percent : Math.min(slowest, percent);
            }
        }
        return slowest;
    }

    /** Who could take over at this checkpoint, and how far the best other copy is. */
    public Standing standing(long seq) {
        List<Successor.Candidate> candidates = new ArrayList<>();
        int connected = 0;
        String bestUuid = "";
        int bestPercent = -1;
        for (Peer peer : peers) {
            SyncServerSession session = peer.session();
            if (!session.ready() || session.playerUuid().isEmpty() || session.playerUuid().equals(hostUuid)) {
                continue;
            }
            connected++;
            candidates.add(new Successor.Candidate(session.playerUuid(), session.syncedSeq(), session.connectedAt(),
                    session.uploadKBps()));
            if (session.peerPercent() > bestPercent) {
                bestPercent = session.peerPercent();
                bestUuid = session.playerUuid();
            }
        }
        String successor = Successor.choose(candidates, seq, hostUuid).orElse(null);
        return new Standing(successor, connected, memberName(bestUuid), Math.max(0, Math.min(99, bestPercent)));
    }

    private String memberName(String uuid) {
        if (uuid.isEmpty()) {
            return "";
        }
        try {
            for (Member member : CoopWorlds.store().get(world.worldId()).orElse(world).members()) {
                if (member.uuid().equals(uuid)) {
                    return member.name();
                }
            }
        } catch (IOException | RuntimeException e) {
            // The name is only for the message.
        }
        return "";
    }

    /**
     * Called by the host session when it ends, to publish "idle". Returns false if the server is already
     * stopping: then {@link #serverStopping} publishes the last record itself.
     */
    public boolean announceIfNotStopping(Runnable publishIdle) {
        synchronized (announceLock) {
            if (finalAnnounceTaken) {
                return false;
            }
            publishIdle.run();
            return true;
        }
    }

    /** For the self-tests: who the last host session of this game handed its world to ("" if nobody). */
    public static String lastHandedTo() {
        return lastHandedTo;
    }

    /** For the self-tests: the root of the last checkpoint of the last host session of this game. */
    public static String lastFinalRoot() {
        return lastFinalRoot;
    }

    /** Waits until every member that may receive bulk data has the checkpoint, or the time is up. */
    private void awaitPeers(long seq, long waitMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            boolean waiting = false;
            for (Peer peer : peers) {
                if (peer.session().ready() && bulkAllowed(peer.session()) && peer.session().syncedSeq() < seq) {
                    waiting = true;
                }
            }
            if (!waiting) {
                return;
            }
            Thread.sleep(50);
        }
        DropIn.LOGGER.warn("Not every member took the final checkpoint {} of '{}' in time", seq, world.name());
    }

    /** Tail of {@code MinecraftServer.stopServer}: the storage workers are closed. */
    public static void serverStopped(MinecraftServer server) {
        HostSync sync = current;
        if (sync == null || sync.server != server) {
            return;
        }
        sync.stopping = true;
        sync.finished = true;
        current = null;
        try {
            // Whatever the shutdown itself wrote is hashed by the next host session's first checkpoint.
            Set<String> leftover = ChunkStores.drain(sync.root);
            if (ChunkStores.unseenWriter(sync.root) != null) {
                // What the shutdown wrote is unknown: whoever opens this copy next compares all of it (DESIGN 4.16 marker).
                Files.write(sync.dropin.resolve("rescan"), new byte[0]);
            }
            if (!leftover.isEmpty()) {
                Files.write(sync.dropin.resolve("dirty.txt"), leftover, StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not save the list of unsynced chunks of '{}'", sync.world.name(), e);
            sync.needFullScan = true;
        }
        ChunkStores.untrack(sync.root);
        for (Peer peer : sync.peers) {
            peer.session().close();
        }
    }

    // ---- connections --------------------------------------------------------------------------

    /** A connection that sent the sync magic. Returns its pipeline handler, or null if nothing is hosted. */
    public static SyncPipe accept(Channel channel) {
        HostSync sync = current;
        if (sync == null || sync.finished) {
            return null;
        }
        SyncPipe pipe = new SyncPipe("dropin-sync-serve");
        SyncServerSession session = new SyncServerSession(sync, pipe, RANDOM);
        Peer peer = new Peer(session, pipe);
        pipe.endpoint(new SyncPipe.Endpoint() {
            @Override
            public void onFrame(byte[] frame) {
                session.onFrame(frame);
            }

            @Override
            public void onClosed() {
                session.onClosed();
                if (sync.peers.remove(peer)) {
                    sync.closedRecordFrames.addAndGet(session.recordFrames());
                    sync.closedRecordBytes.addAndGet(session.recordBytes());
                }
            }
        });
        sync.peers.add(peer);
        return pipe;
    }

    private Peer peer(SyncServerSession session) {
        for (Peer peer : peers) {
            if (peer.session() == session) {
                return peer;
            }
        }
        return null;
    }

    // ---- what the self-tests and the menu read --------------------------------------------------

    public CoopWorld world() {
        return world;
    }

    public String rootHash() {
        return manifest.root();
    }

    public long seq() {
        return ledger.headSeq();
    }

    public int records() {
        return manifest.size();
    }

    /** Bytes of world records sent to members in this host session. */
    public long recordBytes() {
        long bytes = closedRecordBytes.get();
        for (Peer peer : peers) {
            bytes += peer.session().recordBytes();
        }
        return bytes;
    }

    /** RECORD frames sent to members in this host session. */
    public long recordFrames() {
        long frames = closedRecordFrames.get();
        for (Peer peer : peers) {
            frames += peer.session().recordFrames();
        }
        return frames;
    }

    /** Sync connections that finished the handshake. */
    public int readyPeers() {
        int ready = 0;
        for (Peer peer : peers) {
            if (peer.session().ready()) {
                ready++;
            }
        }
        return ready;
    }

    /** The newest checkpoint every connected member has fully applied (0 with no member). */
    public long syncedByAll() {
        long seq = Long.MAX_VALUE;
        for (Peer peer : peers) {
            if (peer.session().ready()) {
                seq = Math.min(seq, peer.session().syncedSeq());
            }
        }
        return seq == Long.MAX_VALUE ? 0 : seq;
    }

    // ---- owner policy and mods (DESIGN 4.13, 4.14) ----------------------------------------------

    /** The newest valid policy of the hosted world, or null if it has none yet. */
    public WorldPolicy.Held held() {
        return held;
    }

    /**
     * A newer policy reached this host mid-session (the owner joined, or changed something from the menu).
     * It is stored; then a checkpoint is taken and the policy goes to every member, whose game leaves if its
     * mods no longer match. A host that is itself behind ends the session after the checkpoint.
     */
    public void adoptNewer(WorldPolicy.Held candidate) {
        synchronized (policyLock) {
            WorldPolicy.Held current = held;
            if (current != null && candidate.version() <= current.version()) {
                return;
            }
            try {
                WorldPolicy.adopt(world, root, candidate);
            } catch (IOException | RuntimeException e) {
                DropIn.LOGGER.error("Could not store policy version {} of '{}'", candidate.version(), world.name(), e);
                return;
            }
            held = candidate;
        }
        Thread thread = new Thread(() -> policyChanged(candidate), "dropin-coop-policy");
        thread.setDaemon(true);
        thread.start();
    }

    private void policyChanged(WorldPolicy.Held now) {
        try {
            checkpoint(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            DropIn.LOGGER.error("Checkpoint of '{}' for the new policy failed", world.name(), e);
        }
        for (Peer peer : peers) {
            peer.session().sendPolicy();
        }
        server.execute(() -> {
            for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
                if (now.policy().isBanned(player.getUUID().toString())) {
                    player.connection.disconnect(Component.translatable("text.dropin_coop.policy.banned"));
                }
            }
        });
        boolean behind;
        try {
            behind = WorldPolicy.environmentProblem(now) != null || !WorldPolicy.diff(now).mayJoin();
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not compare this PC's mods with policy version {}", now.version(), e);
            behind = false;
        }
        if (behind) {
            DropIn.LOGGER.info("This host's mods do not match policy version {} of '{}': ending the session", now.version(), world.name());
            ModConsentScreen.leaveAndShow(world, now);
        }
    }

    /** What the policy says about a player's command rights on this server, or null to leave it to the game. */
    public static LevelBasedPermissionSet permissions(MinecraftServer server, java.util.UUID id) {
        HostSync sync = current;
        WorldPolicy.Held policy = sync == null || sync.server != server ? null : sync.held;
        if (policy == null) {
            return null;
        }
        if (policy.policy().isOwner(id.toString())) {
            return LevelBasedPermissionSet.OWNER;
        }
        if (policy.policy().isAdmin(id.toString())) {
            // DESIGN 4.13: commands, kick and ban in every session, whoever hosts.
            return LevelBasedPermissionSet.ADMIN;
        }
        return policy.policy().membersMayUseCommands() ? LevelBasedPermissionSet.GAMEMASTER : LevelBasedPermissionSet.ALL;
    }

    /** Why this player may not log in to the hosted co-op world, or null. */
    public static Component banMessage(MinecraftServer server, java.util.UUID id) {
        HostSync sync = current;
        WorldPolicy.Held policy = sync == null || sync.server != server ? null : sync.held;
        return policy != null && policy.policy().isBanned(id.toString()) ? Component.translatable("text.dropin_coop.policy.banned") : null;
    }

    /** Sends the member list to the connected members when someone new is on it. */
    public void shareMembers() {
        String now = members();
        if (now == null) {
            return;
        }
        String people = CoopWorldStore.parseMembers(now).stream().map(m -> m.uuid() + m.name()).sorted()
                .collect(java.util.stream.Collectors.joining(","));
        if (!people.equals(membersShared)) {
            membersShared = people;
            for (Peer peer : peers) {
                peer.session().sendMembers();
            }
        }
    }

    @Override
    public String[] policy() {
        String[] forged = DevHooks.active() ? DevHooks.policyOverride : null;
        if (forged != null) {
            return forged;
        }
        WorldPolicy.Held now = held;
        return now == null ? null : now.json();
    }

    @Override
    public void policyOffered(SyncServerSession session, String policyJson, String manifestJson) {
        if (policyJson.isEmpty()) {
            return;
        }
        try {
            adoptNewer(WorldPolicy.verify(world, policyJson, manifestJson));
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.warn("A member offered a policy for '{}' that is not valid: {}", world.name(), e.getMessage());
        }
    }

    @Override
    public String members() {
        try {
            return CoopWorldStore.membersJson(CoopWorlds.store().get(world.worldId()).orElse(world).members());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void membersOffered(SyncServerSession session, String json) {
        try {
            WorldPolicy.Held now = held;
            CoopWorlds.store().mergeMembers(world.worldId(), CoopWorldStore.parseMembers(json),
                    now == null ? "" : now.policy().ownerUuid());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.warn("Could not merge a member list into '{}'", world.name(), e);
        }
    }

    /** Only files of the signed mod list are ever served (DESIGN 4.14 "Safety"). */
    @Override
    public Path modFile(String sha512) {
        WorldPolicy.Held now = held;
        ModManifest.Entry entry = now == null ? null : now.manifest().bySha512(sha512);
        if (entry == null) {
            return null;
        }
        Path file = WorldPolicy.modsDir().resolve(entry.path());
        if (!Files.isRegularFile(file)) {
            file = WorldPolicy.store().file(sha512);
        }
        if (Files.isRegularFile(file) && DevHooks.active() && DevHooks.tamperMods) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                bytes[bytes.length - 1] ^= 1;
                Path tampered = Files.createTempFile("dropin-tampered", ".jar");
                Files.write(tampered, bytes);
                tampered.toFile().deleteOnExit();
                return tampered;
            } catch (IOException e) {
                return null;
            }
        }
        return Files.isRegularFile(file) ? file : null;
    }

    // ---- SyncServerSession.Host -----------------------------------------------------------------

    @Override
    public String worldId() {
        return world.worldId();
    }

    @Override
    public byte[] auth() {
        return keys.auth();
    }

    @Override
    public byte[] enc() {
        return keys.enc();
    }

    @Override
    public Object lock() {
        return lock;
    }

    @Override
    public LedgerEntry head() {
        return ledger.last();
    }

    @Override
    public List<LedgerEntry> ledgerAfter(long seq) {
        return ledger.entriesAfter(seq);
    }

    @Override
    public Map<String, String> top() {
        return manifest.top();
    }

    @Override
    public Map<String, String> chunks(String regionKey) {
        return manifest.chunks(regionKey);
    }

    @Override
    public byte[] read(String key) throws IOException {
        if (RecordKey.isChunk(key)) {
            try {
                return readChunk(key);
            } catch (IOException | RuntimeException e) {
                // The session that asked for it ends; without this line nobody would know why.
                DropIn.LOGGER.warn("Could not read {} of '{}' for a member", key, world.name(), e);
                throw e;
            }
        }
        if (!RecordKey.isFile(key) || !RecordKey.fileAllowed(RecordKey.filePath(key))) {
            return null;
        }
        Path file = root.resolve(RecordKey.filePath(key));
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }

    @Override
    public boolean bulkAllowed(SyncServerSession session) {
        Peer peer = peer(session);
        return peer != null && peer.pipe().channel() != null && PathPolicy.bulkAllowed(peer.pipe().channel());
    }

    @Override
    public void throttle(SyncServerSession session, int bytes) throws InterruptedException {
        Peer peer = peer(session);
        if (peer == null) {
            return;
        }
        if (devDelayMs > 0) {
            // Development only: slows the download down so a self-test can interrupt it.
            Thread.sleep(devDelayMs);
        }
        if (!PathPolicy.direct(peer.pipe().channel())) {
            // Opted-in bulk sync over a relay is capped (DESIGN 4.6).
            pace(session, bytes, Math.max(1, rate("relayRateKBps", 128)));
        } else if (!PathPolicy.local(peer.pipe().channel())) {
            // A direct connection over the internet: leave room on the host's upload for everything else
            // on this PC (DESIGN 4.6). 0 switches the cap off. Players on the same network are not capped.
            pace(session, bytes, devDirectRate >= 0 ? devDirectRate : rate("directRateKBps", 2048));
        }
        // Bulk transfer must not slow the game down: wait while the server is behind (below about 18 TPS).
        for (int waited = 0; !stopping && waited < 5000 && server.getAverageTickTimeNanos() > 55_000_000L; waited += 100) {
            Thread.sleep(100);
        }
        peer.pipe().awaitWritable(30_000);
    }

    /** Bytes sent to a connection that its rate cap has not been waited for yet (records are often tiny). */
    private final Map<SyncServerSession, long[]> owed = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, long[]> rates = new java.util.concurrent.ConcurrentHashMap<>();

    /** Waits so that this connection stays under {@code rateKBps}; 0 or less = no cap. */
    private void pace(SyncServerSession session, int bytes, int rateKBps) throws InterruptedException {
        if (rateKBps <= 0) {
            return;
        }
        long[] debt = owed.computeIfAbsent(session, key -> new long[1]);
        debt[0] += bytes;
        long ms = debt[0] * 1000L / (rateKBps * 1024L);
        if (ms > 0) {
            debt[0] -= ms * rateKBps * 1024L / 1000L;
            Thread.sleep(ms);
        }
    }

    /** A rate of {@code config/dropin/coop.json}, read again at most every five seconds (this runs per record). */
    private int rate(String name, int fallback) {
        long now = System.currentTimeMillis();
        long[] cached = rates.get(name);
        if (cached == null || now - cached[1] > 5000) {
            cached = new long[]{CoopWorlds.configInt(name, fallback), now};
            rates.put(name, cached);
        }
        return (int) cached[0];
    }

    @Override
    public void sessionError(SyncServerSession session, Exception error) {
        DropIn.LOGGER.warn("The sync connection of {} to '{}' ended on an error", session.playerUuid().isEmpty()
                ? "a player that has not said who it is" : memberName(session.playerUuid()) + " (" + session.playerUuid() + ")",
                world.name(), error);
    }

    /** The upload speed last measured for a connected player (KB/s); 0 if none was measured. */
    public int uploadKBps(String uuid) {
        int best = 0;
        for (Peer peer : peers) {
            if (peer.session().ready() && peer.session().playerUuid().equals(uuid)) {
                best = Math.max(best, peer.session().uploadKBps());
            }
        }
        return best;
    }

    @Override
    public void peerSynced(SyncServerSession session, long seq) {
        DropIn.LOGGER.info("A member's copy of '{}' is complete through checkpoint {}", world.name(), seq);
    }
}
