package dropin.mc.handoff;

import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopLauncher;
import dropin.mc.member.SyncClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * A member's side of a graceful handoff (DESIGN 4.9 steps 4 and 5). The named successor leaves the old
 * session, opens its local copy and hosts it. Everyone else waits on "Host is changing…" until the new
 * host answers, then reconnects.
 */
public final class MemberHandoff {
    public enum Role { NONE, SUCCESSOR, FOLLOWER }

    private static final String K = "text.dropin_coop.handoff.";
    private static volatile Role lastRole = Role.NONE;
    private static volatile String takeoverRoot = "";
    private static volatile long takeoverSeq;

    private MemberHandoff() {
    }

    /** What this game did at the last handoff it saw. */
    public static Role lastRole() {
        return lastRole;
    }

    /** The root hash of the local copy when this game took over as host ("" if it never did). */
    public static String takeoverRoot() {
        return takeoverRoot;
    }

    public static long takeoverSeq() {
        return takeoverSeq;
    }

    /** The host sent {@code HANDOFF}. Any thread. */
    public static void received(Minecraft mc, CoopWorld world, String successorUuid, LedgerEntry finalEntry, boolean applied) {
        boolean me = successorUuid.equals(mc.getUser().getProfileId().toString());
        DropIn.LOGGER.info("The host of '{}' is leaving at checkpoint {}; next host: {}{}", world.name(), finalEntry.seq(),
                successorUuid, me ? " (this player)" : "");
        if (me && !applied) {
            // The host only names a peer that confirmed the final checkpoint, so this should not happen.
            DropIn.LOGGER.error("This copy of '{}' is not at the final checkpoint {}: not taking over", world.name(), finalEntry.seq());
        }
        if (me && applied) {
            lastRole = Role.SUCCESSOR;
            onClient(mc, () -> {
                leave(mc, new GenericMessageScreen(Component.translatable(K + "taking_over")));
                Thread thread = new Thread(() -> takeOver(mc, world, finalEntry), "dropin-coop-takeover");
                thread.setDaemon(true);
                thread.start();
            });
        } else {
            lastRole = Role.FOLLOWER;
            onClient(mc, () -> leave(mc, new HostChangingScreen(world, finalEntry.host())));
        }
    }

    /**
     * Runs {@code task} once on the client thread. {@code Minecraft.disconnect} drops queued tasks
     * ({@code dropAllTasks}), and the old host closes the game connection right after it sent the handoff,
     * so a task queued with {@code execute} alone can be lost: it is queued again until it has run.
     */
    private static void onClient(Minecraft mc, Runnable task) {
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        Thread thread = new Thread(() -> {
            try {
                while (!ran.get()) {
                    mc.execute(() -> {
                        if (ran.compareAndSet(false, true)) {
                            task.run();
                        }
                    });
                    Thread.sleep(250);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "dropin-coop-handoff-member");
        thread.setDaemon(true);
        thread.start();
    }

    /** Leaves the old host's session (if the game is still in it) and shows {@code screen}. Client thread. */
    private static void leave(Minecraft mc, Screen screen) {
        if (mc.level != null && mc.getSingleplayerServer() == null) {
            mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
        mc.gui.setScreen(screen);
    }

    private static void takeOver(Minecraft mc, CoopWorld world, LedgerEntry finalEntry) {
        try {
            // The copy is about to be opened by the game: the sync must have closed its files.
            SyncClient.stopAndWait(world.worldId(), 15_000);
            Path folder = SyncClient.ensureFolder(mc, world);
            Manifest manifest = Manifest.load(folder.resolve(CoopWorldStore.MARKER_DIR).resolve("manifest").resolve("current.tsv"));
            if (!manifest.root().equals(finalEntry.root())) {
                DropIn.LOGGER.error("The local copy of '{}' (root {}) is not the final checkpoint {} (root {}): not taking over",
                        world.name(), manifest.root(), finalEntry.seq(), finalEntry.root());
                mc.execute(() -> mc.gui.setScreen(new HostChangingScreen(world, finalEntry.host())));
                lastRole = Role.FOLLOWER;
                return;
            }
            takeoverRoot = manifest.root();
            takeoverSeq = finalEntry.seq();
            DropIn.LOGGER.info("Taking over '{}' as host at checkpoint {} (root {})", world.name(), finalEntry.seq(), takeoverRoot);
            CoopLauncher.hostAfterHandoff(mc, world, folder);
        } catch (Exception e) {
            DropIn.LOGGER.error("Could not take over '{}' as host", world.name(), e);
            mc.execute(() -> mc.gui.setScreen(new HostChangingScreen(world, finalEntry.host())));
        }
    }
}
