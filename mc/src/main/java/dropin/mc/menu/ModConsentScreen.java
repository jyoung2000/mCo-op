package dropin.mc.menu;

import dropin.core.mods.ModDiff;
import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.menu.ui.DButton;
import dropin.mc.menu.ui.DScreen;
import dropin.mc.mods.ModInstall;
import dropin.mc.policy.WorldPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;

import java.util.ArrayList;
import java.util.List;

/**
 * The member's consent screen of DESIGN 4.14: "This world needs 42 mods. 3 to download (18 MB), 1 to
 * remove. [Show list] [Download and restart] [Cancel]". Nothing is downloaded without the click, and the
 * screen says in plain words that mods are programs the owner chose.
 */
public class ModConsentScreen extends DScreen {
    private static final String K = "text.dropin_coop.";
    /** Long enough to read "the game closes now", and for the helper to be up before the game is gone. */
    private static final int CLOSE_AFTER_TICKS = 60;

    public enum State { ASKING, DOWNLOADING, FAILED, CLOSING }

    private final Runnable onCancel;
    private final CoopWorld world;
    private final WorldPolicy.Held held;
    private final ModDiff diff;
    private final String address;
    private final boolean changed;
    private volatile State state = State.ASKING;
    private volatile ITextComponent progress = new TextComponentString("");
    private volatile ITextComponent error = new TextComponentString("");
    private int closingTicks;
    private DButton downloadButton;
    private DButton listButton;

    /**
     * @param address the live host this PC was about to join, or null when nobody is known to be hosting
     * @param changed true when the mod list changed while this player was in the world
     */
    public ModConsentScreen(Runnable onCancel, CoopWorld world, WorldPolicy.Held held, ModDiff diff, String address, boolean changed) {
        super(null, I18n.format(K + "mods.consent.title", world.name()));
        this.onCancel = onCancel;
        this.world = world;
        this.held = held;
        this.diff = diff;
        this.address = address;
        this.changed = changed;
    }

    /** The host's mod list moved on and this PC is behind: leave the session and show what is needed (DESIGN 4.14). */
    public static void leaveAndShow(CoopWorld world, WorldPolicy.Held held) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            ModDiff diff;
            try {
                diff = WorldPolicy.diff(held);
            } catch (Exception e) {
                DropIn.LOGGER.error("Could not compare this PC's mods with the new mod list of '{}'", world.name(), e);
                return;
            }
            DropIn.LOGGER.info("The mods of '{}' changed (policy version {}); leaving the session", world.name(), held.version());
            if (mc.world != null) {
                dropin.mc.compat.Compat.disconnectFromWorld(mc, dropin.mc.compat.Compat.DEFAULT_QUIT_MESSAGE);
            }
            mc.displayGuiScreen(new ModConsentScreen(() -> mc.displayGuiScreen(new CoopWorldsScreen(new GuiMainMenu())),
                    world, held, diff, null, true));
        });
    }

    // ---- state the self-tests read ------------------------------------------------------------

    public ModDiff diff() {
        return diff;
    }

    public WorldPolicy.Held held() {
        return held;
    }

    public State state() {
        return state;
    }

    public ITextComponent error() {
        return error;
    }

    public boolean changed() {
        return changed;
    }

    // ---- layout -------------------------------------------------------------------------------

    @Override
    protected int titleY() {
        return 14;
    }

    @Override
    protected void init() {
        int width = Math.min(300, this.width - 20);
        int left = (this.width - width) / 2;
        int third = (width - 8) / 3;
        int y = this.height - 30;
        listButton = button(left, y, third, I18n.format(K + "mods.consent.show"),
                b -> this.mc.displayGuiScreen(new ModsScreen(this, world.worldId())));
        downloadButton = button(left + third + 4, y, third, I18n.format(K + "mods.consent.download"), b -> approve());
        button(left + 2 * (third + 4), y, third, I18n.format("gui.cancel"), b -> cancel());
        refreshButtons();
    }

    private void refreshButtons() {
        if (downloadButton != null) {
            downloadButton.enabled = state == State.ASKING || state == State.FAILED;
            listButton.enabled = state != State.CLOSING;
        }
    }

    /** "Download and restart". */
    public void approve() {
        if (state != State.ASKING && state != State.FAILED) {
            return;
        }
        state = State.DOWNLOADING;
        error = new TextComponentString("");
        refreshButtons();
        Minecraft mc = this.mc != null ? this.mc : Minecraft.getMinecraft();
        Thread thread = new Thread(() -> {
            try {
                ModInstall.downloadAndPlan(mc, world, held, address, line -> progress = line);
                progress = new TextComponentTranslation(K + "mods.consent.closing");
                state = State.CLOSING;
            } catch (ModInstall.InstallException e) {
                error = e.shown();
                state = State.FAILED;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state = State.FAILED;
            } catch (Exception e) {
                DropIn.LOGGER.error("Installing the mods of '{}' failed", world.name(), e);
                error = new TextComponentTranslation(K + "mods.error.failed", String.valueOf(e.getMessage()));
                state = State.FAILED;
            }
        }, "dropin-coop-mod-install");
        thread.setDaemon(true);
        thread.start();
    }

    public void cancel() {
        if (state == State.ASKING || state == State.FAILED) {
            onCancel.run();
        }
    }

    /** Escape (26.x {@code shouldCloseOnEsc} was true only while asking or failed; {@link #cancel()} checks the same). */
    @Override
    public void onClose() {
        cancel();
    }

    @Override
    protected void tick() {
        refreshButtons();
        if (state == State.CLOSING && ++closingTicks == CLOSE_AFTER_TICKS) {
            // The helper waits for this process to end, then changes mods/. The player starts the game again.
            // 1.12.2 port: 26.x Minecraft.stop() is Minecraft.shutdown().
            this.mc.shutdown();
        }
    }

    private static String megabytes(long bytes) {
        return bytes < 1024 * 1024 ? Math.max(1, bytes / 1024) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private List<String> lines() {
        List<String> lines = new ArrayList<>();
        if (changed) {
            lines.add(I18n.format(K + "mods.consent.changed"));
        }
        if (address == null && !changed) {
            lines.add(I18n.format(K + "policy.mods_mismatch"));
        }
        lines.add(I18n.format(K + "mods.consent.summary", held.manifest().files().size(), diff.toDownload().size(),
                megabytes(diff.downloadBytes()), diff.toRemove().size()));
        lines.add("");
        lines.add(I18n.format(K + "mods.consent.trust", held.policy().ownerName()));
        lines.add(I18n.format(K + "mods.consent.restart"));
        if (!diff.toRemove().isEmpty()) {
            lines.add(I18n.format(K + "mods.consent.moved"));
        }
        return lines;
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        int y = 40;
        int wrap = Math.min(340, this.width - 20);
        for (String line : lines()) {
            for (String part : this.fontRenderer.listFormattedStringToWidth(line, wrap)) {
                drawCenteredString(this.fontRenderer, part, this.width / 2, y, 0xFFFFFF);
                y += 11;
            }
            y += 3;
        }
        ITextComponent status = state == State.FAILED ? error : progress;
        for (String part : this.fontRenderer.listFormattedStringToWidth(status.getFormattedText(), wrap)) {
            drawCenteredString(this.fontRenderer, part, this.width / 2, y + 6, state == State.FAILED ? 0xFF5555 : 0xFFDC00);
            y += 11;
        }
    }
}
