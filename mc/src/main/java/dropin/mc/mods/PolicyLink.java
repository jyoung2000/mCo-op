package dropin.mc.mods;

import dropin.core.mods.ModManifest;
import dropin.core.mods.ModStore;
import dropin.core.sync.PolicyClientSession;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.member.SyncClient;
import dropin.mc.net.SyncConnector;
import dropin.mc.net.SyncPipe;
import dropin.mc.policy.PolicyActions;
import dropin.mc.policy.WorldPolicy;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A short sync connection to a live host that carries no world data (DESIGN 4.13, 4.14): it exchanges the
 * policy, the mod list and the member list before this PC joins, and fetches mod files the host has to
 * serve. What arrives is checked here: a policy against the invite's owner fingerprint, a file against
 * the SHA-512 in the owner-signed mod list.
 */
public final class PolicyLink implements PolicyClientSession.Local, AutoCloseable {
    /** Why a mod file could not be fetched from the host. */
    public static final class FetchException extends IOException {
        public enum Kind { PATH, ABSENT, HASH, LOST }

        private final Kind kind;

        FetchException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    private record Fetch(ModManifest.Entry entry, ModStore.Incoming incoming, CompletableFuture<Void> done) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Minecraft mc;
    private final CoopWorld world;
    private final SyncPipe pipe = new SyncPipe("dropin-policy-link");
    private final PolicyClientSession session = new PolicyClientSession(this, pipe, RANDOM);
    private final CountDownLatch policySeen = new CountDownLatch(1);
    private final CountDownLatch membersSeen = new CountDownLatch(1);
    private volatile WorldPolicy.Held effective;
    private volatile String rejected;
    private volatile String failure;
    private volatile Fetch fetch;

    private PolicyLink(Minecraft mc, CoopWorld world) {
        this.mc = mc;
        this.world = world;
    }

    /** Connects to the host at {@code address} and starts the exchange. Blocking; not on the client thread. */
    public static PolicyLink open(Minecraft mc, CoopWorld world, String address) throws IOException, InterruptedException {
        PolicyLink link = new PolicyLink(mc, world);
        link.effective = WorldPolicy.load(world, PolicyActions.folder(mc, world));
        link.pipe.endpoint(new SyncPipe.Endpoint() {
            @Override
            public void onFrame(byte[] frame) {
                link.session.onFrame(frame);
            }

            @Override
            public void onClosed() {
                link.session.onClosed();
            }
        });
        try {
            SyncConnector.connect(address, link.pipe);
        } catch (IOException e) {
            link.pipe.close();
            throw e;
        }
        link.pipe.execute(link.session::start);
        return link;
    }

    /** Waits for the host's policy answer, and briefly for its member list. False if it did not come. */
    public boolean await(long millis) throws InterruptedException {
        if (!policySeen.await(millis, TimeUnit.MILLISECONDS)) {
            return false;
        }
        membersSeen.await(1500, TimeUnit.MILLISECONDS);
        return failure == null || rejected != null || effective != null;
    }

    /** The newest valid policy either side holds, or null if there is none. */
    public WorldPolicy.Held effective() {
        return effective;
    }

    /** Why the host's policy was refused, or null. */
    public String rejected() {
        return rejected;
    }

    /**
     * Fetches one file of the mod list from the host into the store. The file only gets its store name if
     * its bytes hash to the SHA-512 in the owner-signed list.
     */
    public void fetch(ModManifest.Entry entry, ModStore store) throws IOException, InterruptedException {
        Fetch request = new Fetch(entry, store.receive(entry), new CompletableFuture<>());
        fetch = request;
        try {
            if (failure != null) {
                throw new FetchException(FetchException.Kind.LOST, failure);
            }
            session.requestMod(entry.sha512());
            // Slow links exist; a stalled one must not hang the screen for ever.
            long seconds = 120 + entry.size() / (16 * 1024);
            request.done().get(seconds, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new FetchException(FetchException.Kind.LOST, "the host stopped sending " + entry.path());
        } finally {
            fetch = null;
            request.incoming().close();
        }
    }

    @Override
    public void close() {
        pipe.execute(session::close);
    }

    // ---- PolicyClientSession.Local --------------------------------------------------------------

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
    public long policyVersion() {
        WorldPolicy.Held mine = effective;
        return mine == null ? 0 : mine.version();
    }

    @Override
    public String membersToOffer() {
        try {
            return CoopWorldStore.membersJson(CoopWorlds.store().get(world.worldId()).orElse(world).members());
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public String[] policy(String policyJson, String manifestJson) {
        try {
            WorldPolicy.Exchange exchange = WorldPolicy.receive(world, PolicyActions.folder(mc, world),
                    () -> SyncClient.ensureFolder(mc, world), policyJson, manifestJson);
            effective = exchange.effective();
            return exchange.reply();
        } catch (IOException | RuntimeException e) {
            rejected = String.valueOf(e.getMessage());
            DropIn.LOGGER.warn("The host of '{}' sent a policy that is not valid: {}", world.name(), rejected);
            return null;
        } finally {
            policySeen.countDown();
        }
    }

    @Override
    public void members(String json) {
        try {
            WorldPolicy.Held held = effective;
            CoopWorlds.store().mergeMembers(world.worldId(), CoopWorldStore.parseMembers(json),
                    held == null ? "" : held.policy().ownerUuid());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.warn("Could not merge the member list of '{}'", world.name(), e);
        } finally {
            membersSeen.countDown();
        }
    }

    @Override
    public void bulk(boolean allowed) {
    }

    @Override
    public void modPiece(String sha512, byte[] piece, boolean last) {
        Fetch request = fetch;
        if (request == null || !request.entry().sha512().equals(sha512) || request.done().isDone()) {
            return;
        }
        try {
            request.incoming().write(piece, 0, piece.length);
            if (last) {
                request.incoming().commit();
                request.done().complete(null);
            }
        } catch (IOException e) {
            request.done().completeExceptionally(new FetchException(FetchException.Kind.HASH, e.getMessage()));
        }
    }

    @Override
    public void modFailed(String sha512, boolean refusedByPath) {
        Fetch request = fetch;
        if (request != null && request.entry().sha512().equals(sha512)) {
            request.done().completeExceptionally(refusedByPath
                    ? new FetchException(FetchException.Kind.PATH, "the path to the host may not carry mod files")
                    : new FetchException(FetchException.Kind.ABSENT, "the host does not have " + request.entry().path()));
        }
    }

    @Override
    public void failed(String why) {
        failure = why;
        Fetch request = fetch;
        if (request != null) {
            request.done().completeExceptionally(new FetchException(FetchException.Kind.LOST, why));
        }
        policySeen.countDown();
        membersSeen.countDown();
    }
}
