package dropin.mixin.mc;

import dropin.mc.menu.CoopActions;
import dropin.mc.menu.InviteScreen;
import dropin.mc.menu.SessionLine;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DESIGN 4.4: the pause menu of a singleplayer world that is not co-op (and not open to LAN) gets
 * "Make co-op", which is Import in place. The button sits below the vanilla grid, at the bottom edge.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Shadow
    @Final
    private boolean showPauseMenu;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    private Component dropin$line;
    private long dropin$lineAt;

    /** MILESTONES M4: in a co-op session the pause menu says who hosts, how complete the copy is, the path and the checkpoint age. */
    @Inject(method = "render", at = @At("TAIL"))
    private void dropin$sessionLine(GuiGraphics graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (!this.showPauseMenu) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.dropin$lineAt >= 500) {
            this.dropin$lineAt = now;
            SessionLine.Info info = SessionLine.info(this.minecraft);
            this.dropin$line = info == null ? null : SessionLine.text(info);
        }
        if (this.dropin$line != null) {
            graphics.drawCenteredString(this.font, this.dropin$line, this.width / 2, 24, -1);
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void dropin$makeCoopButton(CallbackInfo ci) {
        if (!this.showPauseMenu || !CoopActions.canMakeCoop(this.minecraft)) {
            return;
        }
        Component label = Component.translatable("text.dropin_coop.button.make_coop");
        this.addRenderableWidget(Button.builder(label, button -> {
            CoopActions.Outcome outcome = CoopActions.makeCoop(this.minecraft);
            if (outcome.ok()) {
                this.minecraft.setScreen(new InviteScreen(null, outcome.world()));
            } else {
                this.minecraft.setScreen(new AlertScreen(() -> this.minecraft.setScreen(null), label, outcome.message()));
            }
        }).bounds(this.width / 2 - 102, this.height - 26, 204, 20).build());
    }
}
