package dropin.mc.coop;

import dropin.core.pkarr.Rendezvous;
import dropin.core.pkarr.RendezvousRecord;
import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.tunnel.Config;
import dropin.tunnel.E4mcClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.HttpUtil;

import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs beside a co-op world this player hosts (DESIGN 4.3, 4.4): opens it to LAN once the world is up,
 * publishes the rendezvous record (LAN hint at once, the e4mc domain when it is assigned), re-publishes
 * every five minutes, keeps the member list, and publishes "idle" when the world closes.
 */
public final class HostSession implements Runnable {
    private static final long WORLD_START_TIMEOUT_MS = 10 * 60_000;
    private static final long HEARTBEAT_MS = 5 * 60_000;
    private static final long RETRY_MS = 60_000;
    private static final long MEMBERS_MS = 5_000;
    private static final long DOMAIN_WAIT_MS = 30_000;

    private static volatile HostSession current;

    private final Minecraft mc;
    private final CoopWorld world;
    private final Rendezvous rendezvous;
    private final RendezvousRecord base;
    private volatile String domain = "";
    private volatile boolean cancelled;
    private volatile String announcedState = "";
    private volatile String announcedAddr = "";

    /** The state last published successfully ("" before the first publish). */
    public String announcedState() {
        return announcedState;
    }

    /** The e4mc domain in the record last published successfully ("" if none). */
    public String announcedAddr() {
        return announcedAddr;
    }

    private HostSession(Minecraft mc, CoopWorld world, Rendezvous rendezvous, String sess, String uuid, String name) {
        this.mc = mc;
        this.world = world;
        this.rendezvous = rendezvous;
        this.base = RendezvousRecord.of(RendezvousRecord.STARTING, uuid, name, sess, System.currentTimeMillis() / 1000);
    }

    /** The session of the co-op world this client is hosting, or null. */
    public static HostSession current() {
        return current;
    }

    public CoopWorld world() {
        return world;
    }

    public String domain() {
        return domain;
    }

    public static void start(Minecraft mc, CoopWorld world, Rendezvous rendezvous, String sess, String uuid, String name) {
        HostSession old = current;
        if (old != null) {
            old.cancelled = true;
        }
        HostSession session = new HostSession(mc, world, rendezvous, sess, uuid, name);
        current = session;
        Thread thread = new Thread(session, "dropin-coop-host");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        Consumer<String> previousListener = E4mcClient.domainListener;
        E4mcClient.domainListener = assigned -> {
            domain = assigned;
            if (previousListener != null) {
                previousListener.accept(assigned);
            }
        };
        try {
            IntegratedServer server = awaitWorld();
            if (server == null) {
                return;
            }
            dropin.mc.host.HostSync.start(server, world, base.host(), base.name(), base.sess(), this::announceLast);
            hostSync = dropin.mc.host.HostSync.current();
            String lan = openToLan(server);
            if (DropIn.switches().flag("dropin.dev.online")) {
                // Development only: the online smoke test must go through the relay, not the LAN shortcut.
                lan = "";
            }
            long started = System.currentTimeMillis();
            String published = null;
            long lastPublish = 0;
            long lastTry = 0;
            long lastMembers = 0;
            while (!cancelled && mc.getSingleplayerServer() == server && server.isRunning()) {
                long now = System.currentTimeMillis();
                String wanted = domain;
                // Until the tunnel has a domain, friends outside the LAN could not reach this session. They are
                // told "starting" (and wait) instead of "host" with no address (and start a second session).
                boolean waitingForDomain = wanted.isEmpty() && Config.INSTANCE.hostEnabled.value()
                        && now - started < DOMAIN_WAIT_MS;
                String state = waitingForDomain ? RendezvousRecord.STARTING : RendezvousRecord.HOST;
                String key = state + "|" + wanted + "|" + policyVersion();
                boolean due = published == null ? now - lastTry >= (lastTry == 0 ? 0 : RETRY_MS)
                        : !published.equals(key) || now - lastPublish >= HEARTBEAT_MS;
                if (due) {
                    lastTry = now;
                    if (publish(state, wanted, lan)) {
                        published = key;
                        lastPublish = now;
                        announcedState = state;
                        announcedAddr = wanted;
                        DropIn.LOGGER.info("Co-op world '{}' announced as {}: domain='{}' lan='{}'", world.name(), state, wanted, lan);
                    }
                }
                if (now - lastMembers >= MEMBERS_MS) {
                    lastMembers = now;
                    recordPlayers(server);
                    dropin.mc.host.HostSync sync = dropin.mc.host.HostSync.current();
                    if (sync != null) {
                        sync.shareMembers();
                    }
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            DropIn.LOGGER.error("Co-op host session of '{}' failed", world.name(), e);
        } finally {
            // DESIGN 4.9: when the server is stopping, the world sync publishes the last record ("handoff" or "idle").
            dropin.mc.host.HostSync sync = hostSync;
            if (sync == null || !sync.announceIfNotStopping(() -> publish(RendezvousRecord.IDLE, "", ""))) {
                if (sync == null) {
                    publish(RendezvousRecord.IDLE, "", "");
                }
            }
            E4mcClient.domainListener = previousListener;
            if (current == this) {
                current = null;
            }
        }
    }

    private volatile dropin.mc.host.HostSync hostSync;

    /** The session's last record, published from the stopping server (DESIGN 4.3 clean exit, 4.9 step 3). */
    private void announceLast(String state, String successorUuid) {
        try {
            long now = System.currentTimeMillis() / 1000;
            rendezvous.publish(RendezvousRecord.HANDOFF.equals(state) ? base.handoffTo(successorUuid, now)
                    : base.with(RendezvousRecord.IDLE, "", "", now));
            announcedState = state;
            announcedAddr = "";
            DropIn.LOGGER.info("Co-op world '{}' announced as {}{}", world.name(), state,
                    successorUuid.isEmpty() ? "" : ", next host " + successorUuid);
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not announce co-op world '{}' ({}): {}", world.name(), state, e.toString());
        }
    }

    private static long policyVersion() {
        dropin.mc.host.HostSync sync = dropin.mc.host.HostSync.current();
        dropin.mc.policy.WorldPolicy.Held held = sync == null ? null : sync.held();
        return held == null ? 0 : held.version();
    }

    private IntegratedServer awaitWorld() throws InterruptedException {
        long deadline = System.currentTimeMillis() + WORLD_START_TIMEOUT_MS;
        while (!cancelled && System.currentTimeMillis() < deadline) {
            IntegratedServer server = mc.getSingleplayerServer();
            if (server != null && mc.player != null) {
                return server;
            }
            Thread.sleep(250);
        }
        return null;
    }

    /** Opens the world to LAN on the client thread, as the Open to LAN screen does. Returns the LAN hint. */
    private String openToLan(IntegratedServer server) throws InterruptedException {
        CompletableFuture<Integer> port = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                if (!server.isPublished()) {
                    int wanted = DropIn.switches().read("dropin.dev.lanPort").map(Integer::parseInt)
                            .orElseGet(HttpUtil::getAvailablePort);
                    // Members get no commands by default; the self-tests drive the game with commands.
                    boolean commands = DropIn.switches().selfTest().isPresent();
                    if (!server.publishServer(MinecraftServer.MultiplayerScope.LAN, commands, wanted)) {
                        port.complete(-1);
                        return;
                    }
                }
                port.complete(server.getPort());
            } catch (RuntimeException e) {
                port.completeExceptionally(e);
            }
        });
        try {
            int value = port.get(30, TimeUnit.SECONDS);
            if (value <= 0) {
                DropIn.LOGGER.error("Could not open co-op world '{}' to LAN", world.name());
                return "";
            }
            return localAddress() + ":" + value;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            DropIn.LOGGER.error("Could not open co-op world '{}' to LAN", world.name(), e);
            return "";
        }
    }

    private static String localAddress() {
        if (DropIn.switches().devEnvironment() && DropIn.switches().selfTest().isPresent()) {
            return "127.0.0.1";
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private boolean publish(String state, String addr, String lan) {
        try {
            RendezvousRecord record = base.with(state, addr, lan, System.currentTimeMillis() / 1000);
            dropin.mc.host.HostSync sync = dropin.mc.host.HostSync.current();
            dropin.mc.policy.WorldPolicy.Held held = sync == null ? null : sync.held();
            // DESIGN 4.13: a member can see a policy change before connecting.
            rendezvous.publish(held == null ? record : record.withPolicy(held.version(), held.policy().modManifestHash()));
            return true;
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not announce co-op world '{}' ({}): {}", world.name(), state, e.toString());
            return false;
        }
    }

    private void recordPlayers(IntegratedServer server) {
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                CoopLauncher.remember(world, player.getGameProfile().id().toString(), player.getGameProfile().name());
            }
        });
    }
}
