package dropin.mixin.mc;

import dropin.mc.menu.CoopActions;
import dropin.mc.menu.InviteScreen;
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

    @Inject(method = "init", at = @At("TAIL"))
    private void dropin$makeCoopButton(CallbackInfo ci) {
        if (!this.showPauseMenu || !CoopActions.canMakeCoop(this.minecraft)) {
            return;
        }
        Component label = Component.translatable("text.dropin_coop.button.make_coop");
        this.addRenderableWidget(Button.builder(label, button -> {
            CoopActions.Outcome outcome = CoopActions.makeCoop(this.minecraft);
            if (outcome.ok()) {
                this.minecraft.gui.setScreen(new InviteScreen(null, outcome.world()));
            } else {
                this.minecraft.gui.setScreen(new AlertScreen(() -> this.minecraft.gui.setScreen(null), label, outcome.message()));
            }
        }).bounds(this.width / 2 - 102, this.height - 26, 204, 20).build());
    }
}
