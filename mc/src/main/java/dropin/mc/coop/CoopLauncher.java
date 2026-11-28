package dropin.mc.coop;

import dropin.core.pkarr.HostClaim;
import dropin.core.pkarr.Rendezvous;
import dropin.core.pkarr.RendezvousRecord;
import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.member.SyncClient;
import dropin.mc.menu.CoopStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.storage.WorldInfo;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * The launch flow of DESIGN 4.4: opening a co-op world first asks the rendezvous who is hosting.
 * A live host is joined; otherwise this player claims the world and opens the local copy.
 */
public final class CoopLauncher {
    private static final long PING_TIMEOUT_MS = 5_000;
    /** Set on the client thread while this class itself opens the world. */
    private static boolean passThrough;
    /**
     * 1.12.2 port: worlds this class opens wait here for the end of the next client tick. 26.x's
     * {@code openWorld} could be queued with {@code execute}; 1.12.2's {@code launchIntegratedServer} blocks
     * until the integrated server runs, and scheduled tasks run while the game holds the lock of its task
     * queue, so any thread that schedules a task meanwhile (the server thread included) would hang the game.
     */
    private static final Queue<Runnable> NEXT_TICK = new ConcurrentLinkedQueue<>();

    private CoopLauncher() {
    }

    /**
     * Called at the head of {@code Minecraft.launchIntegratedServer(folderName, worldName, settings)}, which the
     * world list ({@code FMLClientHandler.tryLoadExistingWorld}), world creation and DropIn's own screens open a
     * world with (1.12.2 port: 26.x {@code WorldOpenFlows.openWorld(levelId, onCancel)}). Client thread.
     *
     * @return true if the open was taken over (the vanilla call must be cancelled)
     */
    public static boolean intercept(Minecraft mc, String levelId, String worldName, WorldSettings settings) {
        if (passThrough) {
            return false;
        }
        Optional<CoopWorld> world = CoopWorlds.forFolder(folder(mc, levelId));
        if (!world.isPresent()) {
            return false;
        }
        // 1.12.2 port: 26.x was given an onCancel; here Cancel goes back to the screen the world was opened from.
        GuiScreen back = mc.currentScreen;
        Runnable onCancel = () -> mc.displayGuiScreen(back != null ? back : new GuiMainMenu());
        Opener opener = new Opener(levelId, worldName, settings);
        mc.displayGuiScreen(new Prompts.Message(new TextComponentTranslation("text.dropin_coop.looking")));
        Thread thread = new Thread(() -> launch(mc, opener, world.get(), onCancel, false), "dropin-coop-launch");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** End of every client tick, outside the task queue's lock: opens the worlds this class queued. Client thread. */
    public static void onClientTick() {
        Runnable task;
        while ((task = NEXT_TICK.poll()) != null) {
            task.run();
        }
    }

    /** 1.12.2 port: 26.x {@code LevelStorageSource.getLevelPath(levelId)}, absolute. */
    private static Path folder(Minecraft mc, String levelId) {
        return dropin.mc.menu.CoopActions.levelPath(mc, levelId).toAbsolutePath().normalize();
    }

    /** How a world is opened once the launch flow allows it (1.12.2 port of 26.x's {@code WorldOpenFlows} and level id). */
    private record Opener(String levelId, String worldName, WorldSettings settings) {
        /** A world DropIn opens by itself: its display name comes from level.dat (the game saves the name it is given). */
        static Opener of(Minecraft mc, String levelId) {
            WorldInfo info = mc.getSaveLoader().getWorldInfo(levelId);
            String name = info == null || info.getWorldName() == null || info.getWorldName().isEmpty() ? levelId : info.getWorldName();
            return new Opener(levelId, name, null);
        }

        /** Opens the world at the end of the next client tick, past the launch flow. Any thread. */
        void open(Minecraft mc) {
            NEXT_TICK.add(() -> {
                passThrough = true;
                try {
                    mc.launchIntegratedServer(levelId, worldName, settings);
                } finally {
                    passThrough = false;
                }
            });
        }
    }

    /** Runs the launch flow again from the start (the owner just signed a new policy). Any thread. */
    private static void relaunch(Minecraft mc, Opener opener, CoopWorld world, Runnable onCancel) {
        mc.addScheduledTask(() -> {
            mc.displayGuiScreen(new Prompts.Message(new TextComponentTranslation("text.dropin_coop.looking")));
            Thread thread = new Thread(() -> launch(mc, opener, world, onCancel, false), "dropin-coop-launch");
            thread.setDaemon(true);
            thread.start();
        });
    }

    private static volatile RendezvousRecord staleWarning;
    private static volatile Consumer<Boolean> staleAnswer;

    /** The record the "your copy is behind" warning on screen is about, or null if none is shown. */
    public static RendezvousRecord staleWarning() {
        return staleWarning;
    }

    /** Answers the "your copy is behind" warning, as its buttons do. Client thread. */
    public static void answerStale(boolean playAnyway) {
        Consumer<Boolean> answer = staleAnswer;
        if (answer != null) {
            answer.accept(playAnyway);
        }
    }

    /**
     * DESIGN 4.4 launch flow step 3: the last session's record names a checkpoint this copy's ledger does not
     * hold, and that session's host does not answer. Reads the ledger file of the closed copy only.
     *
     * @return the record of the session this copy is behind, or null if it is not behind (or nothing is known)
     */
    private static RendezvousRecord behind(Rendezvous rendezvous, CoopWorld world, Path folder, Env env) {
        try {
            Optional<RendezvousRecord> found = rendezvous.lookup();
            if (!found.isPresent() || found.get().head().isEmpty() || found.get().isState(RendezvousRecord.STARTING)) {
                return null;
            }
            RendezvousRecord record = found.get();
            dropin.core.ledger.Ledger ledger = dropin.core.ledger.Ledger.load(world.keys().auth(),
                    folder.resolve(dropin.core.world.CoopWorldStore.MARKER_DIR).resolve("ledger.jsonl"));
            if (ledger.knows(record.head())) {
                return null;
            }
            // A live host is joined and brings this copy up to date: nothing to warn about.
            return record.isState(RendezvousRecord.HOST) && env.ping(record) ? null : record;
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not compare the copy of '{}' with the last session: {}", world.name(), e.toString());
            return null;
        }
    }

    /** "3 hours ago", from a time in epoch seconds. */
    public static ITextComponent ago(long epochSeconds) {
        long minutes = Math.max(0, System.currentTimeMillis() / 1000 - epochSeconds) / 60;
        if (minutes < 1) {
            return new TextComponentTranslation("text.dropin_coop.ago.now");
        } else if (minutes < 120) {
            return new TextComponentTranslation("text.dropin_coop.ago.minutes", minutes);
        } else if (minutes < 48 * 60) {
            return new TextComponentTranslation("text.dropin_coop.ago.hours", minutes / 60);
        }
        return new TextComponentTranslation("text.dropin_coop.ago.days", minutes / (24 * 60));
    }

    private static String playerUuid(Minecraft mc) {
        return mc.getSession().getProfile().getId().toString();
    }

    private static void launch(Minecraft mc, Opener opener, CoopWorld world, Runnable onCancel, boolean staleAccepted) {
        String uuid = playerUuid(mc);
        String name = mc.getSession().getUsername();
        Rendezvous rendezvous = CoopWorlds.rendezvous(world);
        Path folder = folder(mc, opener.levelId());
        if (SyncClient.incomplete(folder)) {
            // DESIGN 4.7: an unfinished download is not a world yet. It can only be continued from a live host.
            CoopStatus.Status status = CoopStatus.lookup(world);
            if (status.kind() == CoopStatus.Kind.PLAYING) {
                DropIn.LOGGER.info("Co-op world '{}': continuing the download from {}", world.name(), status.hostName());
                rememberHost(world, status.hostUuid(), status.hostName());
                join(mc, world, status.address());
            } else {
                int percent = SyncClient.savedState(folder).percent();
                DropIn.LOGGER.info("Co-op world '{}': nobody is online and the copy is {}% downloaded", world.name(), percent);
                mc.addScheduledTask(() -> mc.displayGuiScreen(new Prompts.Alert(onCancel, I18n.format("menu.dropin_coop.title"),
                        new TextComponentTranslation("text.dropin_coop.sync.incomplete_nobody", percent))));
            }
            return;
        }
        // DESIGN 4.14: the owner's mods and datapacks must be what the signed policy says before anyone plays.
        if (!dropin.mc.policy.HostGate.ownerReady(mc, world, folder, () -> relaunch(mc, opener, world, onCancel), onCancel)) {
            return;
        }
        Env env = new Env();
        RendezvousRecord newer = staleAccepted ? null : behind(rendezvous, world, folder, env);
        if (newer != null) {
            // Before anything is claimed or opened: Cancel must leave this copy exactly as it is.
            DropIn.LOGGER.info("Co-op world '{}': this copy is behind the last session ({}, checkpoint {}) and nobody is online",
                    world.name(), newer.name(), newer.seq());
            mc.addScheduledTask(() -> {
                Consumer<Boolean> answer = playAnyway -> {
                    staleWarning = null;
                    staleAnswer = null;
                    if (!playAnyway) {
                        DropIn.LOGGER.info("Co-op world '{}': not opened, the copy stays as it is", world.name());
                        onCancel.run();
                        return;
                    }
                    DropIn.LOGGER.info("Co-op world '{}': playing on the older copy; the changes are merged later", world.name());
                    mc.displayGuiScreen(new Prompts.Message(new TextComponentTranslation("text.dropin_coop.looking")));
                    Thread thread = new Thread(() -> launch(mc, opener, world, onCancel, true), "dropin-coop-launch");
                    thread.setDaemon(true);
                    thread.start();
                };
                staleWarning = newer;
                staleAnswer = answer;
                mc.displayGuiScreen(new Prompts.Confirm(answer, I18n.format("menu.dropin_coop.title"),
                        new TextComponentTranslation("text.dropin_coop.stale.message", newer.name(), ago(newer.ts())),
                        I18n.format("text.dropin_coop.stale.play_anyway"), I18n.format("gui.cancel")));
            });
            return;
        }
        HostClaim.Result result;
        try {
            result = HostClaim.claim(rendezvous, env, uuid, name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (RuntimeException e) {
            DropIn.LOGGER.error("Looking for a host failed; opening the local copy", e);
            result = new HostClaim.Host(env.newSession(), false);
        }

        if (result instanceof HostClaim.Join join) {
            String address = env.liveAddress != null ? env.liveAddress : join.host().addr();
            DropIn.LOGGER.info("Co-op world '{}': {} is hosting, joining {}", world.name(), join.host().name(), address);
            rememberHost(world, join.host().host(), join.host().name());
            remember(world, uuid, name);
            join(mc, world, address);
        } else if (result instanceof HostClaim.Host host) {
            DropIn.LOGGER.info("Co-op world '{}': nobody is hosting, opening the local copy (announced={})",
                    world.name(), host.announced());
            remember(world, uuid, name);
            // DESIGN 4.13: a member only hosts a copy that matches the owner's policy.
            GuiScreen problem = dropin.mc.policy.HostGate.memberProblem(mc, world, folder, onCancel);
            if (problem != null) {
                DropIn.LOGGER.info("Co-op world '{}': this PC may not host it ({})", world.name(), problem.getClass().getSimpleName());
                if (host.announced()) {
                    withdraw(rendezvous, world, uuid, name, host.sess());
                }
                mc.addScheduledTask(() -> mc.displayGuiScreen(problem));
                return;
            }
            // The copy is about to be opened by the game: a sync from an earlier session must have let go of it.
            if (!syncReleased(mc, world, onCancel)) {
                if (host.announced()) {
                    withdraw(rendezvous, world, uuid, name, host.sess());
                }
                return;
            }
            // DESIGN 4.10: offline sessions that wait to be merged go in now, while the world is still closed.
            dropin.mc.merge.ColdMerge.run(mc, world, folder, uuid, name, host.sess());
            mc.addScheduledTask(() -> {
                HostSession.start(mc, world, rendezvous, host.sess(), uuid, name);
                opener.open(mc);
            });
        }
    }

    private static void withdraw(Rendezvous rendezvous, CoopWorld world, String uuid, String name, String sess) {
        try {
            rendezvous.publish(RendezvousRecord.of(RendezvousRecord.IDLE, uuid, name, sess, System.currentTimeMillis() / 1000));
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not withdraw the host announcement of '{}': {}", world.name(), e.toString());
        }
    }

    /**
     * DESIGN 4.9 step 4: this player was named the next host. Opens the local copy (which is the old host's
     * last checkpoint) and hosts it; the host session publishes the record. Not on the client thread.
     *
     * @return false if this PC may not host the world (a screen says why)
     */
    public static boolean hostAfterHandoff(Minecraft mc, CoopWorld world, Path folder) {
        String uuid = playerUuid(mc);
        String name = mc.getSession().getUsername();
        Runnable onCancel = () -> mc.displayGuiScreen(new GuiMainMenu());
        remember(world, uuid, name);
        GuiScreen problem = dropin.mc.policy.HostGate.memberProblem(mc, world, folder, onCancel);
        if (problem != null) {
            DropIn.LOGGER.info("Co-op world '{}': this PC may not take over as host ({})", world.name(), problem.getClass().getSimpleName());
            mc.addScheduledTask(() -> mc.displayGuiScreen(problem));
            return false;
        }
        openAsHost(mc, world, folder, new Env().newSession(), onCancel);
        return true;
    }

    private static void openAsHost(Minecraft mc, CoopWorld world, Path folder, String sess, Runnable onCancel) {
        String uuid = playerUuid(mc);
        String name = mc.getSession().getUsername();
        String levelId = folder.getFileName().toString();
        if (!syncReleased(mc, world, onCancel)) {
            return;
        }
        dropin.mc.merge.ColdMerge.run(mc, world, folder, uuid, name, sess);
        Opener opener = Opener.of(mc, levelId);
        mc.addScheduledTask(() -> {
            HostSession.start(mc, world, CoopWorlds.rendezvous(world), sess, uuid, name);
            opener.open(mc);
        });
    }

    /** How {@link #hostAfterCrash} ended. */
    public enum Takeover {
        /** This game opens its copy and hosts it. */
        HOSTING,
        /** Someone else turned out to be hosting already; this game joins them. */
        JOINED,
        /** This PC may not host the world; a screen says why. */
        REFUSED,
        /** The rendezvous could not be reached, so nobody could find this game: nothing was done. */
        UNREACHABLE
    }

    /**
     * DESIGN 4.9 (crash): the host vanished and it is this player's turn. Claims the world as DESIGN 4.3 says
     * (so two members whose turns overlap do not both host), then opens the local copy, which is at its last
     * applied checkpoint, and hosts it. Not on the client thread.
     */
    public static Takeover hostAfterCrash(Minecraft mc, CoopWorld world, Path folder) throws InterruptedException {
        String uuid = playerUuid(mc);
        String name = mc.getSession().getUsername();
        Runnable onCancel = () -> mc.displayGuiScreen(new GuiMainMenu());
        GuiScreen problem = dropin.mc.policy.HostGate.memberProblem(mc, world, folder, onCancel);
        if (problem != null) {
            DropIn.LOGGER.info("Co-op world '{}': this PC may not take over as host ({})", world.name(), problem.getClass().getSimpleName());
            mc.addScheduledTask(() -> mc.displayGuiScreen(problem));
            return Takeover.REFUSED;
        }
        Env env = new Env();
        HostClaim.Result result = HostClaim.claim(CoopWorlds.rendezvous(world), env, uuid, name);
        if (result instanceof HostClaim.Join join) {
            String address = env.liveAddress != null ? env.liveAddress : join.host().addr();
            DropIn.LOGGER.info("Co-op world '{}': {} is hosting already, joining {}", world.name(), join.host().name(), address);
            rememberHost(world, join.host().host(), join.host().name());
            join(mc, world, address);
            return Takeover.JOINED;
        }
        HostClaim.Host host = (HostClaim.Host) result;
        if (!host.announced()) {
            return Takeover.UNREACHABLE;
        }
        remember(world, uuid, name);
        openAsHost(mc, world, folder, host.sess(), onCancel);
        return Takeover.HOSTING;
    }

    /**
     * A member sync of this copy must have let go of it (no thread left that could write it) before the game
     * opens it. If it did not within a minute, a screen says so and this returns false: the world is not opened.
     * Not on the client thread.
     */
    public static boolean syncReleased(Minecraft mc, CoopWorld world, Runnable onCancel) {
        if (SyncClient.stopAndWait(world.worldId(), 60_000)) {
            return true;
        }
        mc.addScheduledTask(() -> mc.displayGuiScreen(new Prompts.Alert(onCancel, I18n.format("menu.dropin_coop.title"),
                new TextComponentTranslation("text.dropin_coop.sync_busy"))));
        return false;
    }

    /** As {@link #remember}, and notes who hosts the session this game is about to join (for the status line and failover). */
    public static void rememberHost(CoopWorld world, String uuid, String name) {
        remember(world, uuid, name);
        dropin.mc.handoff.CrashFailover.host(world.worldId(), uuid, name);
    }

    /**
     * Joins a live host: first the mod check of DESIGN 4.14, then the connection, with the local copy kept in
     * step meanwhile (DESIGN 4.4, 4.7).
     */
    public static void join(Minecraft mc, CoopWorld world, String address) {
        // Runs on the launch thread: GuiMainMenu needs the GL context, so JoinGate makes it on the client thread (null).
        dropin.mc.mods.JoinGate.join(mc, world, address, null);
    }

    public static void remember(CoopWorld world, String uuid, String name) {
        if (uuid == null || uuid.isEmpty()) {
            return;
        }
        try {
            CoopWorlds.store().recordMember(world.worldId(), uuid, name, System.currentTimeMillis() / 1000);
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not update the member list of '{}'", world.name(), e);
        }
    }

    private static final class Env implements HostClaim.Env {
        private final SecureRandom random = new SecureRandom();
        /** The address that answered the last successful ping: the LAN hint if it works, else the domain. */
        volatile String liveAddress;

        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }

        /** LAN addresses that did not answer during this launch: not asked again (a launch pings up to three times). */
        private final java.util.Set<String> deadLan = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public boolean ping(RendezvousRecord record) {
            String lan = record.lan();
            if (lan != null && !lan.isEmpty() && !deadLan.contains(lan)) {
                if (StatusPing.answers(lan, PING_TIMEOUT_MS)) {
                    liveAddress = lan;
                    return true;
                }
                deadLan.add(lan);
            }
            String addr = record.addr();
            if (addr != null && !addr.isEmpty() && StatusPing.answers(addr, PING_TIMEOUT_MS)) {
                liveAddress = addr;
                return true;
            }
            return false;
        }

        @Override
        public String newSession() {
            byte[] bytes = new byte[8];
            random.nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        }
    }
}
