package dropin.mixin.mc;

import dropin.mc.menu.CoopWorldsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DESIGN 4.15: mods that replace the title screen can hide our button there, so the Co-op Worlds screen
 * is also reachable from the Singleplayer world list (top right corner, clear of the title and search box).
 */
@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin extends Screen {
    @Unique
    private Button dropin$coopButton;

    protected SelectWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void dropin$addCoopButton(CallbackInfo ci) {
        int width = Math.min(96, this.font.width(CoopWorldsScreen.TITLE) + 12);
        dropin$coopButton = this.addRenderableWidget(Button.builder(CoopWorldsScreen.TITLE,
                button -> this.minecraft.setScreen(new CoopWorldsScreen(this)))
                .bounds(this.width - width - 4, 2, width, 18).build());
    }

    // 1.21.1 port: a resize rebuilds the widgets (Screen.repositionElements -> init), which places the button anew.
}
