package dropin.mc.member;

import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.sync.SyncClientSession;
import dropin.core.sync.SyncClientSession.Phase;
import dropin.core.sync.SyncState;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.menu.CoopActions;
import dropin.mc.net.SyncConnector;
import dropin.mc.net.SyncPipe;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
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

    private Path dropin;
    private Ledger ledger;
    private Manifest manifest;
    private SyncState state;
    private volatile RecordApplier applier;

    private SyncClient(Minecraft mc, CoopWorld world, String address) {
        this.mc = mc;
        this.world = world;
        this.address = address;
    }

    /** Starts syncing the local copy of {@code world} from the host the game is connecting to at {@code address}. */
    public static void start(Minecraft mc, CoopWorld world, String address) {
        SyncClient client = new SyncClient(mc, world, address);
        SyncClient old = ACTIVE.put(world.worldId(), client);
        if (old != null) {
            old.stop = true;
        }
        Thread thread = new Thread(() -> {
            if (old != null) {
                old.join(15_000);
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

    /** Stops the sync of a world and waits until its files are closed (before the world is opened here). */
    public static void stopAndWait(String worldId, long millis) {
        SyncClient client = ACTIVE.get(worldId);
        if (client != null) {
            client.stop = true;
            client.join(millis);
        }
    }

    private void join(long millis) {
        Thread running = thread;
        if (running != null && running != Thread.currentThread()) {
            try {
                running.join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The sync part of a world's status line on the Co-op Worlds screen (DESIGN 4.4), or null when there
     * is nothing to say: "Downloading 63%", the wait for a direct path, or how far an unfinished copy is.
     *
     * @param folder the world's folder under saves/, or null if there is none yet
     */
    public static Component statusLine(String worldId, Path folder) {
        SyncClientSession.Status live = status(worldId);
        if (live != null) {
            switch (live.phase()) {
                case WAITING_DIRECT:
                    return Component.translatable(K + "sync.waiting_direct");
                case COMPARING, DOWNLOADING:
                    return Component.translatable(K + "sync.downloading", percent(live.have(), live.total()));
                case DIVERGED:
                    return Component.translatable(K + "sync.diverged");
                default:
                    return null;
            }
        }
        if (folder != null) {
            Path file = folder.resolve(CoopWorldStore.MARKER_DIR).resolve("state.json");
            if (Files.isRegularFile(file)) {
                SyncState saved = SyncState.load(file);
                if (!saved.complete()) {
                    return Component.translatable(K + "sync.incomplete", saved.percent());
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

    @Override
    public void run() {
        try {
            if (!awaitJoin()) {
                return;
            }
            Path folder = folder();
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
                    applier.close();
                } catch (IOException e) {
                    DropIn.LOGGER.error("Could not close the local copy of '{}'", world.name(), e);
                }
            }
            ACTIVE.remove(world.worldId(), this);
            thread = null;
        }
    }

    /** One sync connection, from connect to close. Returns the phase it ended in. */
    private Phase connectOnce() throws InterruptedException {
        SyncPipe pipe = new SyncPipe("dropin-sync-client");
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
            SyncConnector.connect(address, pipe);
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
        return !stop && mc.player != null && mc.getSingleplayerServer() == null && mc.getConnection() != null;
    }

    /** The world's folder under saves/; a new one on the first join. */
    private Path folder() throws IOException {
        Path saves = mc.getLevelSource().getBaseDir();
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
        return folder;
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
        return mc.getUser().getProfileId().toString();
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
        applied.incrementAndGet();
    }

    @Override
    public void delete(String key) throws IOException {
        applier.delete(key);
    }

    @Override
    public void flush() throws IOException {
        applier.flush();
        manifest.save(dropin.resolve("manifest").resolve("current.tsv"));
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
    }

    @Override
    public void status(SyncClientSession.Status now) {
        status = now;
        if (now.phase() == Phase.WAITING_DIRECT && !waitingTold) {
            waitingTold = true;
            DropIn.LOGGER.info("World sync of '{}' is waiting for a direct connection", world.name());
            mc.execute(() -> {
                if (mc.player != null) {
                    mc.gui.hud.getChat().addClientSystemMessage(Component.translatable(K + "sync.waiting_direct"));
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
