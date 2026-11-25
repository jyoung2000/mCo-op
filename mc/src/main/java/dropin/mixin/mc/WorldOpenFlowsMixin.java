package dropin.mixin.mc;

import dropin.mc.coop.CoopLauncher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one entry point the world list, quick play and the co-op screen use to open a world: co-op worlds go
 * through the launch flow first. 1.20.1 port: that entry point is {@code loadLevel(Screen lastScreen, String levelId)};
 * cancelling goes back to {@code lastScreen} (the later {@code openWorld(String, Runnable onCancel)}).
 */
@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {
    @Inject(method = "loadLevel", at = @At("HEAD"), cancellable = true)
    private void dropin$coopLaunch(Screen lastScreen, String levelId, CallbackInfo ci) {
        if (CoopLauncher.intercept((WorldOpenFlows) (Object) this, levelId, () -> Minecraft.getInstance().setScreen(lastScreen))) {
            ci.cancel();
        }
    }
}
