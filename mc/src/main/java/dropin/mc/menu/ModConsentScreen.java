package dropin.mc.menu;

import dropin.core.mods.ModDiff;
import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.mods.ModInstall;
import dropin.mc.policy.WorldPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The member's consent screen of DESIGN 4.14: "This world needs 42 mods. 3 to download (18 MB), 1 to
 * remove. [Show list] [Download and restart] [Cancel]". Nothing is downloaded without the click, and the
 * screen says in plain words that mods are programs the owner chose.
 */
public class ModConsentScreen extends Screen {
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
    private volatile Component progress = Component.empty();
    private volatile Component error = Component.empty();
    private int closingTicks;
    private Button downloadButton;
    private Button listButton;

    /**
     * @param address the live host this PC was about to join, or null when nobody is known to be hosting
     * @param changed true when the mod list changed while this player was in the world
     */
    public ModConsentScreen(Runnable onCancel, CoopWorld world, WorldPolicy.Held held, ModDiff diff, String address, boolean changed) {
        super(Component.translatable(K + "mods.consent.title", world.name()));
        this.onCancel = onCancel;
        this.world = world;
        this.held = held;
        this.diff = diff;
        this.address = address;
        this.changed = changed;
    }

    /** The host's mod list moved on and this PC is behind: leave the session and show what is needed (DESIGN 4.14). */
    public static void leaveAndShow(CoopWorld world, WorldPolicy.Held held) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            ModDiff diff;
            try {
                diff = WorldPolicy.diff(held);
            } catch (Exception e) {
                DropIn.LOGGER.error("Could not compare this PC's mods with the new mod list of '{}'", world.name(), e);
                return;
            }
            DropIn.LOGGER.info("The mods of '{}' changed (policy version {}); leaving the session", world.name(), held.version());
            if (mc.level != null) {
                mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
            }
            mc.gui.setScreen(new ModConsentScreen(() -> mc.gui.setScreen(new CoopWorldsScreen(new net.minecraft.client.gui.screens.TitleScreen())),
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

    public Component error() {
        return error;
    }

    public boolean changed() {
        return changed;
    }

    // ---- layout -------------------------------------------------------------------------------

    @Override
    protected void init() {
        int width = Math.min(300, this.width - 20);
        int left = (this.width - width) / 2;
        int third = (width - 8) / 3;
        int y = this.height - 30;
        listButton = this.addRenderableWidget(Button.builder(Component.translatable(K + "mods.consent.show"),
                button -> this.minecraft.gui.setScreen(new ModsScreen(this, world.worldId()))).bounds(left, y, third, 20).build());
        downloadButton = this.addRenderableWidget(Button.builder(Component.translatable(K + "mods.consent.download"),
                button -> approve()).bounds(left + third + 4, y, third, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> cancel())
                .bounds(left + 2 * (third + 4), y, third, 20).build());
        refreshButtons();
    }

    private void refreshButtons() {
        if (downloadButton != null) {
            downloadButton.active = state == State.ASKING || state == State.FAILED;
            listButton.active = state != State.CLOSING;
        }
    }

    /** "Download and restart". */
    public void approve() {
        if (state != State.ASKING && state != State.FAILED) {
            return;
        }
        state = State.DOWNLOADING;
        error = Component.empty();
        refreshButtons();
        Minecraft mc = this.minecraft;
        Thread thread = new Thread(() -> {
            try {
                ModInstall.downloadAndPlan(mc, world, held, address, line -> progress = line);
                progress = Component.translatable(K + "mods.consent.closing");
                state = State.CLOSING;
            } catch (ModInstall.InstallException e) {
                error = e.shown();
                state = State.FAILED;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state = State.FAILED;
            } catch (Exception e) {
                DropIn.LOGGER.error("Installing the mods of '{}' failed", world.name(), e);
                error = Component.translatable(K + "mods.error.failed", String.valueOf(e.getMessage()));
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

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return state == State.ASKING || state == State.FAILED;
    }

    @Override
    public void tick() {
        super.tick();
        refreshButtons();
        if (state == State.CLOSING && ++closingTicks == CLOSE_AFTER_TICKS) {
            // The helper waits for this process to end, then changes mods/. The player starts the game again.
            this.minecraft.stop();
        }
    }

    private static String megabytes(long bytes) {
        return bytes < 1024 * 1024 ? Math.max(1, bytes / 1024) + " KB" : String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private List<Component> lines() {
        List<Component> lines = new ArrayList<>();
        if (changed) {
            lines.add(Component.translatable(K + "mods.consent.changed"));
        }
        if (address == null && !changed) {
            lines.add(Component.translatable(K + "policy.mods_mismatch"));
        }
        lines.add(Component.translatable(K + "mods.consent.summary", held.manifest().files().size(), diff.toDownload().size(),
                megabytes(diff.downloadBytes()), diff.toRemove().size()));
        lines.add(Component.empty());
        lines.add(Component.translatable(K + "mods.consent.trust", held.policy().ownerName()));
        lines.add(Component.translatable(K + "mods.consent.restart"));
        if (!diff.toRemove().isEmpty()) {
            lines.add(Component.translatable(K + "mods.consent.moved"));
        }
        return lines;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 14, -1);
        int y = 40;
        int wrap = Math.min(340, this.width - 20);
        for (Component line : lines()) {
            for (FormattedCharSequence part : this.font.split(line, wrap)) {
                graphics.centeredText(this.font, part, this.width / 2, y, -1);
                y += 11;
            }
            y += 3;
        }
        Component status = state == State.FAILED ? error : progress;
        for (FormattedCharSequence part : this.font.split(status, wrap)) {
            graphics.centeredText(this.font, part, this.width / 2, y + 6, state == State.FAILED ? -43691 : -9216);
            y += 11;
        }
    }
}
