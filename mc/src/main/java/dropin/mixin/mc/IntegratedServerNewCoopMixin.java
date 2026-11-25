package dropin.mixin.mc;

import dropin.mc.menu.CoopActions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DESIGN 4.4 "New": a world created from the Co-op Worlds screen is registered as co-op before its first
 * load. 1.12.2 port: 26.x {@code IntegratedServer.initServer()} is {@code IntegratedServer.init()}, which runs
 * on the server thread before {@code loadAllWorlds}.
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerNewCoopMixin {
    @Inject(method = "init", at = @At("HEAD"))
    private void dropin$registerNewCoopWorld(CallbackInfoReturnable<Boolean> cir) {
        CoopActions.onServerStarting((MinecraftServer) (Object) this);
    }
}
