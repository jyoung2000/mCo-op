package dropin.mc.handoff;

import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopLauncher;
import dropin.mc.menu.CoopStatus;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * "Host is changing…" (DESIGN 4.9 step 5): polls the rendezvous until someone other than the old host is
 * hosting and answers a ping, then reconnects. A Cancel button, no time limit (failover is a later milestone).
 */
public final class HostChangingScreen extends GenericMessageScreen {
    private static final long POLL_MS = 1_000;

    private final CoopWorld world;
    private final String oldHostUuid;
    /** Set when the old host itself comes back (DESIGN 4.16): the session that ended. */
    private final String endedSess;
    private volatile boolean done;
    private boolean started;
    private Button cancel;

    public HostChangingScreen(CoopWorld world, String oldHostUuid) {
        this(world, oldHostUuid, null);
    }

    public HostChangingScreen(CoopWorld world, String oldHostUuid, String endedSess) {
        super(Component.translatable("text.dropin_coop.handoff.changing"));
        this.world = world;
        this.oldHostUuid = oldHostUuid;
        this.endedSess = endedSess;
    }

    /** True if the old host announces a session other than the one that ended. */
    private boolean oldHostIsBack() {
        try {
            return dropin.mc.coop.CoopWorlds.rendezvous(world).lookup()
                    .filter(record -> record.isState(dropin.core.pkarr.RendezvousRecord.HOST) && !record.sess().equals(endedSess))
                    .isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void init() {
        super.init();
        this.cancel = this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> {
            done = true;
            this.minecraft.gui.setScreen(new TitleScreen());
        }).bounds(this.width / 2 - 100, this.height / 2 + 24, 200, 20).build());
        if (!started) {
            started = true;
            Thread thread = new Thread(this::poll, "dropin-coop-host-changing");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    protected void repositionElements() {
        super.repositionElements();
        if (this.cancel != null) {
            this.cancel.setPosition(this.width / 2 - 100, this.height / 2 + 24);
        }
    }

    @Override
    public void removed() {
        done = true;
        super.removed();
    }

    private void poll() {
        long started = System.currentTimeMillis();
        try {
            while (!done) {
                CoopStatus.Status status = CoopStatus.lookup(world);
                if (done) {
                    return;
                }
                if (status.kind() == CoopStatus.Kind.PLAYING
                        && (!status.hostUuid().equals(oldHostUuid) || endedSess != null && oldHostIsBack())) {
                    done = true;
                    DropIn.LOGGER.info("Co-op world '{}': {} is the new host after {} ms, reconnecting to {}", world.name(),
                            status.hostName(), System.currentTimeMillis() - started, status.address());
                    CoopLauncher.remember(world, status.hostUuid(), status.hostName());
                    CoopLauncher.join(this.minecraft, world, status.address());
                    return;
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            DropIn.LOGGER.error("Waiting for the new host of '{}' failed", world.name(), e);
        }
    }
}
