package dropin.mixin.mc;

import dropin.mc.coop.CoopLauncher;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one entry point the world list, quick play and the co-op screen use to open a world
 * ({@code openWorld(String levelId, Runnable onCancel)}): co-op worlds go through the launch flow first.
 */
@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {
    @Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
    private void dropin$coopLaunch(String levelId, Runnable onCancel, CallbackInfo ci) {
        if (CoopLauncher.intercept((WorldOpenFlows) (Object) this, levelId, onCancel)) {
            ci.cancel();
        }
    }
}
