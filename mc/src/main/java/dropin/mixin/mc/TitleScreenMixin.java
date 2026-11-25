package dropin.mixin.mc;

import dropin.mc.coop.CoopWorlds;
import dropin.mc.menu.CoopWorldsScreen;
import dropin.mc.menu.ui.DButton;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DESIGN 4.4: a "Co-op Worlds" button beside Singleplayer, laid out relative to the vanilla button.
 * <p>1.12.2 port: {@code GuiMainMenu.initGui} puts Singleplayer at {@code (width / 2 - 100, height / 4 + 48)}, 200
 * wide (the language button is on the left, at {@code width / 2 - 124}). The button is added at the end of
 * {@code initGui} to {@code buttonList} and sits in the free space to the right, so it cannot overlap a vanilla
 * button; on a window too narrow for the label it shrinks to a small square with a tooltip. A {@link DButton} is
 * pressed by DScreen only, so a press reaches it through {@code actionPerformed} here (cancelled before the vanilla
 * id switch: DButton ids are 0, which is Options there), and its tooltip is drawn here.
 */
@Mixin(GuiMainMenu.class)
public abstract class TitleScreenMixin extends GuiScreen {
    @Unique
    private DButton dropin$coopButton;

    @Inject(method = "initGui", at = @At("TAIL"))
    private void dropin$coopWorldsButton(CallbackInfo ci) {
        dropin$coopButton = null;
        if (!CoopWorlds.titleButton()) {
            return;
        }
        String title = CoopWorldsScreen.titleText();
        int topPos = this.height / 4 + 48;
        int x = this.width / 2 + 104;
        int room = this.width - x - 4;
        int wanted = this.fontRenderer.getStringWidth(title) + 12;
        GuiScreen self = this;
        DButton button;
        if (room >= wanted) {
            button = new DButton(x, topPos, Math.min(room, Math.max(wanted, 98)), 20, title,
                    b -> this.mc.displayGuiScreen(new CoopWorldsScreen(self)));
        } else {
            button = new DButton(x, topPos, 20, 20, "Co", b -> this.mc.displayGuiScreen(new CoopWorldsScreen(self)));
            button.tooltip(title);
        }
        button.id = -4200;
        dropin$coopButton = button;
        this.buttonList.add(button);
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void dropin$pressCoopButton(GuiButton button, CallbackInfo ci) {
        if (button instanceof DButton ours) {
            ci.cancel();
            ours.press();
        }
    }

    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void dropin$coopTooltip(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        DButton button = dropin$coopButton;
        if (button != null && this.buttonList.contains(button) && button.isHovered(mouseX, mouseY) && !button.tooltipLines().isEmpty()) {
            this.drawHoveringText(button.tooltipLines(), mouseX, mouseY);
        }
    }
}
