package dropin.mc.coop;

import dropin.core.pkarr.HostClaim;
import dropin.core.pkarr.Rendezvous;
import dropin.core.pkarr.RendezvousRecord;
import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.member.SyncClient;
import dropin.mc.menu.CoopStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The launch flow of DESIGN 4.4: opening a co-op world first asks the rendezvous who is hosting.
 * A live host is joined; otherwise this player claims the world and opens the local copy.
 */
public final class CoopLauncher {
    private static final long PING_TIMEOUT_MS = 5_000;
    /** Set on the client thread while this class itself calls {@code openWorld}. */
    private static boolean passThrough;

    private CoopLauncher() {
    }

    /**
     * Called at the head of {@code WorldOpenFlows.openWorld}.
     *
     * @return true if the open was taken over (the vanilla call must be cancelled)
     */
    public static boolean intercept(WorldOpenFlows flows, String levelId, Runnable onCancel) {
        if (passThrough) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        Optional<CoopWorld> world = CoopWorlds.forFolder(mc.getLevelSource().getLevelPath(levelId));
        if (world.isEmpty()) {
            return false;
        }
        mc.gui.setScreen(new GenericMessageScreen(Component.translatable("text.dropin_coop.looking")));
        Thread thread = new Thread(() -> launch(mc, flows, world.get(), levelId, onCancel), "dropin-coop-launch");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static void launch(Minecraft mc, WorldOpenFlows flows, CoopWorld world, String levelId, Runnable onCancel) {
        String uuid = mc.getUser().getProfileId().toString();
        String name = mc.getUser().getName();
        Rendezvous rendezvous = CoopWorlds.rendezvous(world);
        java.nio.file.Path folder = mc.getLevelSource().getLevelPath(levelId);
        if (SyncClient.incomplete(folder)) {
            // DESIGN 4.7: an unfinished download is not a world yet. It can only be continued from a live host.
            CoopStatus.Status status = CoopStatus.lookup(world);
            if (status.kind() == CoopStatus.Kind.PLAYING) {
                DropIn.LOGGER.info("Co-op world '{}': continuing the download from {}", world.name(), status.hostName());
                remember(world, status.hostUuid(), status.hostName());
                join(mc, world, status.address());
            } else {
                int percent = SyncClient.savedState(folder).percent();
                DropIn.LOGGER.info("Co-op world '{}': nobody is online and the copy is {}% downloaded", world.name(), percent);
                mc.execute(() -> mc.gui.setScreen(new AlertScreen(onCancel, Component.translatable("menu.dropin_coop.title"),
                        Component.translatable("text.dropin_coop.sync.incomplete_nobody", percent))));
            }
            return;
        }
        // DESIGN 4.14: the owner's mods and datapacks must be what the signed policy says before anyone plays.
        if (!dropin.mc.policy.HostGate.ownerReady(mc, world, folder,
                () -> mc.execute(() -> flows.openWorld(levelId, onCancel)), onCancel)) {
            return;
        }
        Env env = new Env();
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
            remember(world, join.host().host(), join.host().name());
            remember(world, uuid, name);
            join(mc, world, address);
        } else if (result instanceof HostClaim.Host host) {
            DropIn.LOGGER.info("Co-op world '{}': nobody is hosting, opening the local copy (announced={})",
                    world.name(), host.announced());
            remember(world, uuid, name);
            // DESIGN 4.13: a member only hosts a copy that matches the owner's policy.
            net.minecraft.client.gui.screens.Screen problem = dropin.mc.policy.HostGate.memberProblem(mc, world, folder, onCancel);
            if (problem != null) {
                DropIn.LOGGER.info("Co-op world '{}': this PC may not host it ({})", world.name(), problem.getClass().getSimpleName());
                if (host.announced()) {
                    try {
                        rendezvous.publish(RendezvousRecord.of(RendezvousRecord.IDLE, uuid, name, host.sess(), System.currentTimeMillis() / 1000));
                    } catch (Exception e) {
                        DropIn.LOGGER.warn("Could not withdraw the host announcement of '{}': {}", world.name(), e.toString());
                    }
                }
                mc.execute(() -> mc.gui.setScreen(problem));
                return;
            }
            // The copy is about to be opened by the game: a sync from an earlier session must have let go of it.
            SyncClient.stopAndWait(world.worldId(), 15_000);
            mc.execute(() -> {
                HostSession.start(mc, world, rendezvous, host.sess(), uuid, name);
                passThrough = true;
                try {
                    flows.openWorld(levelId, onCancel);
                } finally {
                    passThrough = false;
                }
            });
        }
    }

    /**
     * DESIGN 4.9 step 4: this player was named the next host. Opens the local copy (which is the old host's
     * last checkpoint) and hosts it; the host session publishes the record. Not on the client thread.
     *
     * @return false if this PC may not host the world (a screen says why)
     */
    public static boolean hostAfterHandoff(Minecraft mc, CoopWorld world, java.nio.file.Path folder) {
        String uuid = mc.getUser().getProfileId().toString();
        String name = mc.getUser().getName();
        Runnable onCancel = () -> mc.gui.setScreen(new TitleScreen());
        remember(world, uuid, name);
        net.minecraft.client.gui.screens.Screen problem = dropin.mc.policy.HostGate.memberProblem(mc, world, folder, onCancel);
        if (problem != null) {
            DropIn.LOGGER.info("Co-op world '{}': this PC may not take over as host ({})", world.name(), problem.getClass().getSimpleName());
            mc.execute(() -> mc.gui.setScreen(problem));
            return false;
        }
        String sess = new Env().newSession();
        String levelId = folder.getFileName().toString();
        mc.execute(() -> {
            HostSession.start(mc, world, CoopWorlds.rendezvous(world), sess, uuid, name);
            passThrough = true;
            try {
                mc.createWorldOpenFlows().openWorld(levelId, onCancel);
            } finally {
                passThrough = false;
            }
        });
        return true;
    }

    /**
     * Joins a live host: first the mod check of DESIGN 4.14, then the connection, with the local copy kept in
     * step meanwhile (DESIGN 4.4, 4.7).
     */
    public static void join(Minecraft mc, CoopWorld world, String address) {
        dropin.mc.mods.JoinGate.join(mc, world, address, new TitleScreen());
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

        @Override
        public boolean ping(RendezvousRecord record) {
            for (String address : new String[]{record.lan(), record.addr()}) {
                if (address != null && !address.isEmpty() && StatusPing.answers(address, PING_TIMEOUT_MS)) {
                    liveAddress = address;
                    return true;
                }
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
