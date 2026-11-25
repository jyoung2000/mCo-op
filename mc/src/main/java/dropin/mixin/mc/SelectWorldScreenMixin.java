package dropin.mixin.mc;

import dropin.mc.menu.CoopWorldsScreen;
import dropin.mc.menu.ui.DButton;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiWorldSelection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DESIGN 4.15: mods that replace the title screen can hide our button there, so the Co-op Worlds screen
 * is also reachable from the Singleplayer world list (top right corner, clear of the title).
 * <p>1.12.2 port: added to {@code buttonList} at the end of {@code GuiWorldSelection.initGui} (which runs again on
 * a resize, placing the button anew; the title is drawn at y 20, the list starts at y 32). A {@link DButton} is
 * pressed by DScreen only, so a press reaches it through {@code actionPerformed} here, cancelled before the vanilla
 * id switch.
 */
@Mixin(GuiWorldSelection.class)
public abstract class SelectWorldScreenMixin extends GuiScreen {
    @Inject(method = "initGui", at = @At("TAIL"))
    private void dropin$addCoopButton(CallbackInfo ci) {
        String title = CoopWorldsScreen.titleText();
        int width = Math.min(96, this.fontRenderer.getStringWidth(title) + 12);
        GuiScreen self = this;
        DButton button = new DButton(this.width - width - 4, 2, width, 18, title,
                b -> this.mc.displayGuiScreen(new CoopWorldsScreen(self)));
        button.id = -4201;
        this.buttonList.add(button);
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void dropin$pressCoopButton(GuiButton button, CallbackInfo ci) {
        if (button instanceof DButton ours) {
            ci.cancel();
            ours.press();
        }
    }
}
