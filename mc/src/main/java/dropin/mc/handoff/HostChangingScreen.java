package dropin.mc.handoff;

import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopLauncher;
import dropin.mc.menu.CoopStatus;
import dropin.mc.menu.ui.DScreen;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.resources.I18n;

/**
 * "Host is changing…" (DESIGN 4.9 step 5): polls the rendezvous until someone other than the old host is
 * hosting and answers a ping, then reconnects. A Cancel button, no time limit (failover is a later milestone).
 * 1.12.2 port: 26.x GenericMessageScreen is a DScreen whose title is the message, in the middle of the screen.
 */
public final class HostChangingScreen extends DScreen {
    private static final long POLL_MS = 1_000;

    private final CoopWorld world;
    private final String oldHostUuid;
    /** Set when the old host itself comes back (DESIGN 4.16): the session that ended. */
    private final String endedSess;
    private volatile boolean done;
    private boolean started;

    public HostChangingScreen(CoopWorld world, String oldHostUuid) {
        this(world, oldHostUuid, null);
    }

    public HostChangingScreen(CoopWorld world, String oldHostUuid, String endedSess) {
        super(null, I18n.format("text.dropin_coop.handoff.changing"));
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
    protected int titleY() {
        return this.height / 2 - 12;
    }

    @Override
    protected void init() {
        // 1.12.2 port: init runs on every resize, so the button is simply built at its place (26.x repositionElements).
        button(this.width / 2 - 100, this.height / 2 + 24, 200, I18n.format("gui.cancel"), b -> {
            done = true;
            this.mc.displayGuiScreen(new GuiMainMenu());
        });
        if (!started) {
            started = true;
            Thread thread = new Thread(this::poll, "dropin-coop-host-changing");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** 26.x GenericMessageScreen cannot be closed with Escape. */
    @Override
    public void onClose() {
    }

    /** 26.x {@code Screen.removed()}: another screen replaced this one. */
    @Override
    public void onGuiClosed() {
        done = true;
        super.onGuiClosed();
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
                    CoopLauncher.rememberHost(world, status.hostUuid(), status.hostName());
                    CoopLauncher.join(this.mc, world, status.address());
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
