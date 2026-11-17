package dropin.mixin.mc;

import dropin.mc.menu.CoopActions;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DESIGN 4.4 "New": a world created from the Co-op Worlds screen is registered as co-op before its first
 * load. {@code IntegratedServer.initServer()} runs before the level is loaded.
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerNewCoopMixin {
    @Inject(method = "initServer", at = @At("HEAD"))
    private void dropin$registerNewCoopWorld(CallbackInfoReturnable<Boolean> cir) {
        CoopActions.onServerStarting((MinecraftServer) (Object) this);
    }
}
