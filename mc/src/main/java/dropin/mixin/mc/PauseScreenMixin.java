package dropin.mixin.mc;

import dropin.mc.compat.Compat;
import dropin.mc.menu.CoopActions;
import dropin.mc.menu.InviteScreen;
import dropin.mc.menu.NoticeScreen;
import dropin.mc.menu.SessionLine;
import dropin.mc.menu.ui.DButton;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DESIGN 4.4: the pause menu of a singleplayer world that is not co-op (and not open to LAN) gets
 * "Make co-op", which is Import in place. The button sits below the vanilla grid, at the bottom edge.
 * <p>
 * 1.12.2 port: also DESIGN 4.9's quit hook. 26.x {@code Minecraft.disconnectFromWorld} is case 1 of
 * {@code GuiIngameMenu.actionPerformed} here ("Save and Quit to Title" / "Disconnect"); it is routed through
 * {@link Compat#disconnectFromWorld}, which runs the handoff and crash hooks first (1.21.1: MinecraftDisconnectMixin).
 * {@code GuiIngameMenu.actionPerformed} switches on the button id, and {@link DButton}s have id 0 (Options), so
 * DropIn's buttons are pressed here before the switch sees them.
 */
@Mixin(GuiIngameMenu.class)
public abstract class PauseScreenMixin extends GuiScreen {
    @Unique
    private ITextComponent dropin$line;
    @Unique
    private long dropin$lineAt;

    /** MILESTONES M4: in a co-op session the pause menu says who hosts, how complete the copy is, the path and the checkpoint age. */
    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void dropin$sessionLine(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        long now = System.currentTimeMillis();
        if (now - this.dropin$lineAt >= 500) {
            this.dropin$lineAt = now;
            SessionLine.Info info = SessionLine.info(this.mc);
            this.dropin$line = info == null ? null : SessionLine.text(info);
        }
        if (this.dropin$line != null) {
            this.drawCenteredString(this.fontRenderer, this.dropin$line.getFormattedText(), this.width / 2, 24, 0xFFFFFF);
        }
    }

    @Inject(method = "initGui", at = @At("TAIL"))
    private void dropin$makeCoopButton(CallbackInfo ci) {
        if (!CoopActions.canMakeCoop(this.mc)) {
            return;
        }
        String label = I18n.format("text.dropin_coop.button.make_coop");
        this.addButton(new DButton(this.width / 2 - 102, this.height - 26, 204, 20, label, button -> {
            CoopActions.Outcome outcome = CoopActions.makeCoop(this.mc);
            if (outcome.ok()) {
                this.mc.displayGuiScreen(new InviteScreen(null, outcome.world()));
            } else {
                this.mc.displayGuiScreen(new NoticeScreen(null, label, outcome.message().getFormattedText(),
                        () -> this.mc.displayGuiScreen(null)));
            }
        }));
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void dropin$buttons(GuiButton button, CallbackInfo ci) {
        if (button instanceof DButton dropinButton) {
            ci.cancel();
            dropinButton.press();
        } else if (button.id == 1) {
            // DESIGN 4.9: the quit, with the handoff (which may cancel it) and the crash hook in front.
            ci.cancel();
            button.enabled = false;
            Compat.disconnectFromWorld(this.mc, Compat.DEFAULT_QUIT_MESSAGE);
        }
    }
}
