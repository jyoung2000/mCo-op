package dropin.mixin.mc;

import dropin.mc.host.HostSync;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DESIGN 4.5: a last checkpoint when the host quits, pushed to the members before the server closes. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerStopMixin {
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void dropin$stopping(CallbackInfo ci) {
        HostSync.serverStopping((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("TAIL"))
    private void dropin$stopped(CallbackInfo ci) {
        HostSync.serverStopped((MinecraftServer) (Object) this);
    }
}
