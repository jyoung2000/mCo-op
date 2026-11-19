package dropin.mc.handoff;

import dropin.core.ledger.Ledger;
import dropin.core.ledger.LedgerEntry;
import dropin.core.manifest.Manifest;
import dropin.core.pkarr.Rendezvous;
import dropin.core.pkarr.RendezvousRecord;
import dropin.core.sync.Election;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopLauncher;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.coop.StatusPing;
import dropin.mc.member.SyncClient;
import dropin.mc.menu.CoopActions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Shown instead of "Connection lost" when a member's host vanished (DESIGN 4.9, crash). First the old address
 * is pinged: if it still answers, or the host announced that it left, this was not a crash and the game's own
 * screen is shown. Otherwise the members take over in the order of {@link Election}: the first at once, each
 * next one 45 s later, unless a new host has appeared by then, which everyone else joins by itself.
 */
public final class HostLostScreen extends Screen {
    private static final String K = "text.dropin_coop.failover.";
    private static final long POLL_MS = 1_000;
    private static final long PING_TIMEOUT_MS = 5_000;

    private final CoopWorld world;
    private final String oldAddress;
    private final String oldHostUuid;
    private final List<Election.Peer> peers;
    private final Screen vanilla;
    private volatile Component message = Component.translatable(K + "lost");
    private volatile boolean done;
    private boolean started;
    private Button cancel;

    HostLostScreen(CoopWorld world, String oldAddress, String oldHostUuid, List<Election.Peer> peers, Screen vanilla) {
        super(Component.translatable("menu.dropin_coop.title"));
        this.world = world;
        this.oldAddress = oldAddress;
        this.oldHostUuid = oldHostUuid;
        this.peers = peers;
        this.vanilla = vanilla;
    }

    @Override
    protected void init() {
        this.cancel = this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> {
            done = true;
            this.minecraft.gui.setScreen(vanilla);
        }).bounds(this.width / 2 - 100, this.height / 2 + 24, 200, 20).build());
        if (!started) {
            started = true;
            Minecraft mc = this.minecraft;
            Thread thread = new Thread(() -> run(mc), "dropin-coop-host-lost");
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
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void removed() {
        done = true;
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, message, this.width / 2, this.height / 2 - 12, -1);
    }

    private void show(Minecraft mc, Screen screen) {
        mc.execute(() -> {
            if (mc.gui.screen() == this) {
                mc.gui.setScreen(screen);
            }
        });
    }

    private void run(Minecraft mc) {
        long lostAt = System.currentTimeMillis();
        String me = mc.getUser().getProfileId().toString();
        try {
            // DESIGN 4.9: each client confirms that the old address no longer answers.
            if (StatusPing.answers(oldAddress, PING_TIMEOUT_MS)) {
                DropIn.LOGGER.info("Co-op world '{}': the connection ended but the host at {} still answers; no failover",
                        world.name(), oldAddress);
                show(mc, vanilla);
                return;
            }
            Rendezvous rendezvous = CoopWorlds.rendezvous(world);
            String deadSess = null;
            long shift = 0;
            try {
                Optional<RendezvousRecord> found = rendezvous.lookup();
                if (found.isPresent()) {
                    RendezvousRecord record = found.get();
                    if (record.isState(RendezvousRecord.IDLE)) {
                        // A clean exit (DESIGN 4.3): the host left without anyone to hand the world to.
                        DropIn.LOGGER.info("Co-op world '{}': the host closed the world; no failover", world.name());
                        show(mc, vanilla);
                        return;
                    }
                    if (record.isState(RendezvousRecord.HOST)
                            && (record.host().equals(oldHostUuid) || oldAddress.equals(record.lan()) || oldAddress.equals(record.addr()))) {
                        deadSess = record.sess();
                    }
                    if (record.isState(RendezvousRecord.HANDOFF) && !me.equals(record.next())) {
                        // The host named someone before it went: that player comes first.
                        shift = Election.STEP_MS;
                    }
                }
            } catch (Exception e) {
                DropIn.LOGGER.warn("Co-op world '{}': could not read the announcement after the host was lost: {}", world.name(), e.toString());
            }
            List<String> order = Election.order(peers, oldHostUuid);
            long turn = Election.turnAfterMs(order, me);
            String known = CoopActions.folders(mc).get(world.worldId());
            Path folder = known == null ? null : mc.getLevelSource().getBaseDir().resolve(known);
            if (folder == null || SyncClient.incomplete(folder)) {
                turn = -1;
            } else if (turn >= 0) {
                turn += shift;
            }
            DropIn.LOGGER.info("Co-op world '{}': the host is gone ({} does not answer). Order of succession: {}. This player {}",
                    world.name(), oldAddress, order, turn < 0 ? "waits for a new host" : "takes over after " + turn / 1000 + " s");
            while (!done) {
                boolean reachable = true;
                try {
                    Optional<RendezvousRecord> found = rendezvous.lookup();
                    if (found.isPresent() && found.get().isState(RendezvousRecord.HOST) && !found.get().sess().equals(deadSess)) {
                        RendezvousRecord record = found.get();
                        for (String address : new String[]{record.lan(), record.addr()}) {
                            if (address != null && !address.isEmpty() && StatusPing.answers(address, PING_TIMEOUT_MS)) {
                                if (done) {
                                    return;
                                }
                                done = true;
                                long after = System.currentTimeMillis() - lostAt;
                                DropIn.LOGGER.info("Co-op world '{}': {} is the new host {} ms after the host was lost, reconnecting to {}",
                                        world.name(), record.name(), after, address);
                                CrashFailover.resolved(CrashFailover.Role.FOLLOWER, order, after, 0, "");
                                CoopLauncher.rememberHost(world, record.host(), record.name());
                                CoopLauncher.join(mc, world, address);
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    reachable = false;
                }
                if (done) {
                    return;
                }
                long waited = System.currentTimeMillis() - lostAt;
                // Without the rendezvous nobody could find this game, and it could not see a host that is there.
                if (turn >= 0 && waited >= turn && reachable) {
                    if (takeOver(mc, folder, order, lostAt)) {
                        return;
                    }
                }
                Thread.sleep(POLL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            DropIn.LOGGER.error("Failover of '{}' failed", world.name(), e);
            show(mc, vanilla);
        }
    }

    /** @return false if it could not be tried now (the screen keeps waiting) */
    private boolean takeOver(Minecraft mc, Path folder, List<String> order, long lostAt) throws InterruptedException {
        message = Component.translatable(K + "taking_over");
        try {
            // The copy is about to be opened by the game: the sync must have closed its files.
            SyncClient.stopAndWait(world.worldId(), 15_000);
            Path dropin = folder.resolve(CoopWorldStore.MARKER_DIR);
            LedgerEntry last = Ledger.load(world.keys().auth(), dropin.resolve("ledger.jsonl")).last();
            if (last == null) {
                throw new IllegalStateException("this copy has no checkpoint");
            }
            String root = Manifest.load(dropin.resolve("manifest").resolve("current.tsv")).root();
            // A checkpoint that was still arriving when the host vanished is partly in this copy; the first
            // checkpoint of the new session records exactly what is on disk.
            DropIn.LOGGER.info("Taking over '{}' as host after the host was lost: this copy is at checkpoint {} (root {}){}",
                    world.name(), last.seq(), last.root(), root.equals(last.root()) ? "" : ", plus part of the next one");
            CoopLauncher.Takeover result = CoopLauncher.hostAfterCrash(mc, world, folder);
            long after = System.currentTimeMillis() - lostAt;
            switch (result) {
                case HOSTING -> CrashFailover.resolved(CrashFailover.Role.ELECTED, order, after, last.seq(), root);
                case JOINED -> CrashFailover.resolved(CrashFailover.Role.FOLLOWER, order, after, 0, "");
                case UNREACHABLE -> {
                    message = Component.translatable(K + "lost");
                    return false;
                }
                default -> {
                }
            }
            done = true;
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not take over '{}' as host", world.name(), e);
            show(mc, vanilla);
            done = true;
            return true;
        }
    }
}
