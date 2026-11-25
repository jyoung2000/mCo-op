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
    /**
     * A relay that is still storing an earlier record of this world refuses the next one. After a handoff that
     * earlier record is the old host's "handoff", so the new host tries again soon, a limited number of times.
     */
    private static final long CONFLICT_RETRY_MS = 2_000;
    private static final int CONFLICT_RETRIES = 15;
    private static final long MEMBERS_MS = 5_000;
    private static final long DOMAIN_WAIT_MS = 30_000;
    /** How often the host looks for a second host of its world, besides before every publish. */
    private static final long RIVAL_CHECK_MS = 60_000;

    private static volatile HostSession current;

    private final Minecraft mc;
    private final CoopWorld world;
    private final Rendezvous rendezvous;
    private final RendezvousRecord base;
    private volatile String domain = "";
    private volatile boolean cancelled;
    /** UUIDs of the players in the session, read on the server thread every few seconds (DESIGN 4.15). */
    private volatile java.util.List<String> connected = java.util.List.of();
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
                // With foreignLan the record names a LAN address nobody answers, as a host on another network's does.
                lan = DropIn.switches().flag("dropin.dev.foreignLan") ? "10.255.255.1:" + lan.substring(lan.lastIndexOf(':') + 1) : "";
            }
            long started = System.currentTimeMillis();
            String published = null;
            long lastPublish = 0;
            long lastTry = 0;
            long lastMembers = 0;
            long lastRivalCheck = System.currentTimeMillis();
            long rivalCheckMs = DropIn.switches().read("dropin.dev.rivalCheckSeconds").map(seconds -> Long.parseLong(seconds) * 1000)
                    .orElse(RIVAL_CHECK_MS);
            while (!cancelled && mc.getSingleplayerServer() == server && server.isRunning()) {
                long now = System.currentTimeMillis();
                String wanted = domain;
                // Until the tunnel has a domain, friends outside the LAN could not reach this session. They are
                // told "starting" (and wait) instead of "host" with no address (and start a second session).
                boolean waitingForDomain = wanted.isEmpty() && Config.INSTANCE.hostEnabled.value()
                        && now - started < DOMAIN_WAIT_MS;
                String state = waitingForDomain ? RendezvousRecord.STARTING : RendezvousRecord.HOST;
                String key = state + "|" + wanted + "|" + policyVersion() + "|" + connected;
                long retryMs = conflict && conflictTries < CONFLICT_RETRIES ? CONFLICT_RETRY_MS : RETRY_MS;
                boolean due = published == null ? now - lastTry >= (lastTry == 0 ? 0 : retryMs)
                        : !published.equals(key) || now - lastPublish >= HEARTBEAT_MS;
                if (due) {
                    lastTry = now;
                    if (conflict) {
                        conflictTries++;
                    }
                    if (yieldsToRival(server)) {
                        // Not overwritten; looked at again at the next heartbeat or change.
                        published = key;
                        lastPublish = now;
                    } else if (publish(state, wanted, lan)) {
                        conflictTries = 0;
                        published = key;
                        lastPublish = now;
                        announcedState = state;
                        announcedAddr = wanted;
                        DropIn.LOGGER.info("Co-op world '{}' announced as {}: domain='{}' lan='{}'", world.name(), state, wanted, lan);
                    }
                }
                if (rivalSess.isEmpty() && now - lastRivalCheck >= rivalCheckMs) {
                    lastRivalCheck = now;
                    yieldsToRival(server);
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
            if (sync == null || !sync.announceIfNotStopping(() -> publishIdleUnlessRival())) {
                if (sync == null) {
                    publishIdleUnlessRival();
                }
            }
            E4mcClient.domainListener = previousListener;
            if (current == this) {
                current = null;
            }
        }
    }

    private volatile dropin.mc.host.HostSync hostSync;
    /** The last announce was refused because a relay holds, or is storing, another record of this world. */
    private boolean conflict;
    private int conflictTries;

    /** The session's last record, published from the stopping server (DESIGN 4.3 clean exit, 4.9 step 3). */
    private void announceLast(String state, String successorUuid) {
        if (yieldsToRival(null)) {
            DropIn.LOGGER.warn("Co-op world '{}' closes without announcing {}: another session holds the record", world.name(), state);
            return;
        }
        try {
            long now = System.currentTimeMillis() / 1000;
            RendezvousRecord last = headed(base);
            rendezvous.publish(RendezvousRecord.HANDOFF.equals(state) ? last.handoffTo(successorUuid, now)
                    : last.with(RendezvousRecord.IDLE, "", "", now));
            announcedState = state;
            announcedAddr = "";
            DropIn.LOGGER.info("Co-op world '{}' announced as {}{}", world.name(), state,
                    successorUuid.isEmpty() ? "" : ", next host " + successorUuid);
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not announce co-op world '{}' ({}): {}", world.name(), state, e.toString());
        }
    }

    /**
     * DESIGN 4.3: every record of a session carries its newest checkpoint, so a player whose copy is older can
     * be told so when nobody is online (DESIGN 4.4 launch flow step 3).
     */
    private RendezvousRecord headed(RendezvousRecord record) {
        dropin.mc.host.HostSync sync = hostSync != null ? hostSync : dropin.mc.host.HostSync.current();
        dropin.core.ledger.LedgerEntry head = sync == null ? null : sync.head();
        return head == null ? record : record.withHead(head.hash(), head.seq());
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
                    // DESIGN 4.4 "Game modes": a published integrated server forces every player without game-master
                    // rights into the host copy's default game type (level.dat), so members flipped to creative
                    // whenever a copy's default said creative. A co-op world keeps each player's own saved mode.
                    ((dropin.mc.compat.ForceGameModeAccess) server).dropin$setForceGameMode(false);
                    if (!server.publishServer(null, commands, wanted)) {
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

    private void publishIdleUnlessRival() {
        if (!yieldsToRival(null)) {
            publish(RendezvousRecord.IDLE, "", "");
        }
    }

    /** The other session this one yields the record to, "" if none. */
    private volatile String rivalSess = "";

    /**
     * True if another live session started after this one and holds the rendezvous record (a friend who could not
     * reach this host took the world over): its record is then not overwritten, this session's players are told,
     * and the world is closed (once). False if the record cannot be read (the offline rule of DESIGN 4.3: announce anyway).
     *
     * @param server this session's server, to tell its players; null to only look
     */
    private boolean yieldsToRival(IntegratedServer server) {
        java.util.Optional<RendezvousRecord> rival;
        try {
            rival = dropin.core.pkarr.HostRival.rival(rendezvous.lookup(), base.sess(), base.ts(), System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            return false;
        }
        if (rival.isEmpty()) {
            if (!rivalSess.isEmpty()) {
                DropIn.LOGGER.info("Co-op world '{}': the other session is gone from the record; announcing again", world.name());
                rivalSess = "";
            }
            return false;
        }
        RendezvousRecord other = rival.get();
        if (!other.sess().equals(rivalSess)) {
            rivalSess = other.sess();
            DropIn.LOGGER.warn("Co-op world '{}' is also hosted by {} (session {}, {}) since this session started; this session stops "
                    + "announcing itself", world.name(), other.name(), other.sess(), other.st());
            if (server != null) {
                // Two sessions of one world must not both go on: this one closes (its play is kept and merged later).
                String name = other.name();
                server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                        net.minecraft.network.chat.Component.translatable("text.dropin_coop.host.rival", name), false));
                mc.execute(() -> dropin.mc.handoff.HostHandoff.quitForRival(mc, name));
            }
        }
        return true;
    }

    /** The session this host yields the rendezvous to, "" if none (for the self-tests and the status line). */
    public String rivalSession() {
        return rivalSess;
    }

    private boolean publish(String state, String addr, String lan) {
        try {
            RendezvousRecord record = headed(base).with(state, addr, lan, System.currentTimeMillis() / 1000);
            if (RendezvousRecord.HOST.equals(state)) {
                record = record.withPlayers(connected);
            }
            dropin.mc.host.HostSync sync = dropin.mc.host.HostSync.current();
            dropin.mc.policy.WorldPolicy.Held held = sync == null ? null : sync.held();
            // DESIGN 4.13: a member can see a policy change before connecting.
            rendezvous.publish(held == null ? record : record.withPolicy(held.version(), held.policy().modManifestHash()));
            conflict = false;
            return true;
        } catch (Exception e) {
            conflict = e instanceof dropin.core.pkarr.PkarrClient.StaleRecordException;
            DropIn.LOGGER.warn("Could not announce co-op world '{}' ({}): {}", world.name(), state, e.toString());
            return false;
        }
    }

    private void recordPlayers(IntegratedServer server) {
        server.execute(() -> {
            java.util.List<String> now = new java.util.ArrayList<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                CoopLauncher.remember(world, player.getGameProfile().getId().toString(), player.getGameProfile().getName());
                now.add(player.getGameProfile().getId().toString());
            }
            java.util.Collections.sort(now);
            connected = java.util.List.copyOf(now);
        });
    }
}
