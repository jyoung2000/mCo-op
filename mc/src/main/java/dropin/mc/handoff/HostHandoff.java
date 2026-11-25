package dropin.mc.handoff;

import dropin.mc.DropIn;
import dropin.mc.host.HostSync;
import dropin.mc.coop.Prompts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;

/**
 * The host's side of a graceful handoff (DESIGN 4.9): quitting a co-op world that friends are connected to
 * first takes a checkpoint and waits for them, then either quits (a fully synced friend takes over) or asks
 * "Nobody else has a full copy yet (Sam 62%). Quit anyway?". Cancelling keeps the session alive.
 * <p>
 * The world keeps running during this, so the stopping server takes the true final checkpoint afterwards
 * and sends {@code HANDOFF} ({@link HostSync#serverStopping}).
 */
public final class HostHandoff {
    public enum State { IDLE, PREPARING, CONFIRMING }

    private static final String K = "text.dropin_coop.handoff.";
    /** Client thread only: set while this class itself calls {@code disconnectFromWorld}. */
    private static boolean passThrough;
    private static volatile State state = State.IDLE;
    private static volatile HostSync.Standing lastStanding;
    private static volatile Runnable confirm;
    private static volatile Runnable cancel;

    private HostHandoff() {
    }

    public static State state() {
        return state;
    }

    /** What the last quit found: who would take over, or how far the best other copy was. Null before the first quit. */
    public static HostSync.Standing lastStanding() {
        return lastStanding;
    }

    /** Answers the "Quit anyway?" confirmation, as its buttons do. Client thread. */
    public static void answer(boolean quit) {
        Runnable action = quit ? confirm : cancel;
        if (state == State.CONFIRMING && action != null) {
            action.run();
        }
    }

    /**
     * Head of {@code Compat.disconnectFromWorld} (26.x {@code Minecraft.disconnectFromWorld}), on the client thread.
     *
     * @return true if the quit was taken over (the vanilla call must be cancelled)
     */
    public static boolean intercept(Minecraft mc, ITextComponent message) {
        if (passThrough || !mc.isCallingFromMinecraftThread()) {
            return false;
        }
        HostSync sync = HostSync.current();
        if (sync == null || mc.getIntegratedServer() == null || HostSync.handoffDisabled() || sync.readyPeers() == 0) {
            return false;
        }
        if (state != State.IDLE) {
            return true;
        }
        state = State.PREPARING;
        mc.displayGuiScreen(new Prompts.Message(new TextComponentTranslation(K + "preparing")));
        Thread thread = new Thread(() -> prepare(mc, sync, message), "dropin-coop-handoff");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static void prepare(Minecraft mc, HostSync sync, ITextComponent message) {
        HostSync.Standing standing;
        try {
            standing = sync.prepareHandoff();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            DropIn.LOGGER.error("Could not prepare the handoff of '{}'; quitting without it", sync.world().name(), e);
            mc.addScheduledTask(() -> quit(mc, sync, null, message));
            return;
        }
        lastStanding = standing;
        mc.addScheduledTask(() -> {
            if (standing.successorUuid() != null || standing.peers() == 0) {
                quit(mc, sync, standing.successorUuid(), message);
                return;
            }
            DropIn.LOGGER.info("Nobody else has a full copy of '{}' yet ({} {}%): asking before quitting",
                    sync.world().name(), standing.bestName(), standing.bestPercent());
            confirm = () -> quit(mc, sync, null, message);
            cancel = () -> {
                state = State.IDLE;
                mc.displayGuiScreen(null);
            };
            state = State.CONFIRMING;
            ITextComponent who = standing.bestName().isEmpty() ? new TextComponentTranslation(K + "a_friend")
                    : new TextComponentString(standing.bestName());
            mc.displayGuiScreen(new Prompts.Confirm(yes -> answer(yes), I18n.format("menu.dropin_coop.title"),
                    new TextComponentTranslation(K + "nobody", who, standing.bestPercent())));
        });
    }

    /** DESIGN 4.16: closes the hosted world with no questions; the members are told this host comes back. Client thread. */
    public static void quitToReturn(Minecraft mc, HostSync sync) {
        sync.returning();
        passThrough = true;
        try {
            dropin.mc.compat.Compat.disconnectFromWorld(mc, dropin.mc.compat.Compat.DEFAULT_QUIT_MESSAGE);
        } finally {
            passThrough = false;
        }
    }

    /** The name of the other host this game last closed its world for; "" if none (for the self-tests). */
    private static volatile String lastRivalClosed = "";

    public static String lastRivalClosed() {
        return lastRivalClosed;
    }

    /**
     * Another player started hosting this world while this game hosts it (a friend could not reach this host and
     * took over). Closes the hosted world without a handoff, so the world does not go on as two sessions, and says
     * why. What was played here is kept: the final checkpoint is taken as on any quit, and the copy is merged into
     * the other session's world later (DESIGN 4.10). Client thread.
     */
    public static void quitForRival(Minecraft mc, String rivalName) {
        if (mc.getIntegratedServer() == null) {
            return;
        }
        HostSync sync = HostSync.current();
        if (sync != null) {
            sync.yieldToRival();
        }
        DropIn.LOGGER.warn("Closing the hosted co-op world: {} is hosting it now", rivalName);
        lastRivalClosed = rivalName;
        state = State.IDLE;
        passThrough = true;
        try {
            dropin.mc.compat.Compat.disconnectFromWorld(mc, new TextComponentTranslation("text.dropin_coop.host.rival_closing"));
        } finally {
            passThrough = false;
        }
        mc.displayGuiScreen(new Prompts.Alert(
                () -> mc.displayGuiScreen(new dropin.mc.menu.CoopWorldsScreen(new GuiMainMenu())),
                I18n.format("menu.dropin_coop.title"), new TextComponentTranslation("text.dropin_coop.host.rival_closed", rivalName)));
    }

    private static void quit(Minecraft mc, HostSync sync, String successorUuid, ITextComponent message) {
        state = State.IDLE;
        if (mc.getIntegratedServer() == null) {
            return;
        }
        sync.quitDecided(successorUuid);
        passThrough = true;
        try {
            dropin.mc.compat.Compat.disconnectFromWorld(mc, message);
        } finally {
            passThrough = false;
        }
    }
}
