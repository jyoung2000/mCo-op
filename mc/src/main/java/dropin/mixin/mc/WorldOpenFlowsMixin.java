package dropin.mixin.mc;

import dropin.mc.coop.CoopLauncher;
import net.minecraft.client.Minecraft;
import net.minecraft.world.WorldSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one entry point the world list, world creation and the co-op screens use to open a world: co-op worlds go
 * through the launch flow first (DESIGN 4.4). 1.12.2 port: 26.x {@code WorldOpenFlows.openWorld(levelId, onCancel)}
 * is {@code Minecraft.launchIntegratedServer(folderName, worldName, settings)} here; the world list reaches it
 * through {@code FMLClientHandler.tryLoadExistingWorld}. The end of {@code runTick} is where the launch flow
 * opens worlds itself ({@link CoopLauncher#onClientTick}), outside the lock of the scheduled-task queue.
 */
@Mixin(Minecraft.class)
public class WorldOpenFlowsMixin {
    @Inject(method = "launchIntegratedServer", at = @At("HEAD"), cancellable = true)
    private void dropin$coopLaunch(String folderName, String worldName, WorldSettings settings, CallbackInfo ci) {
        if (CoopLauncher.intercept((Minecraft) (Object) this, folderName, worldName, settings)) {
            ci.cancel();
        }
    }

    @Inject(method = "runTick", at = @At("TAIL"))
    private void dropin$openQueued(CallbackInfo ci) {
        CoopLauncher.onClientTick();
    }
}
