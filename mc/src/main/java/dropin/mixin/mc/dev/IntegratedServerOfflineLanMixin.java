package dropin.mixin.mc.dev;

import dropin.mc.DropIn;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Development only (see DropInMixinPlugin): {@code -Ddropin.dev.offlineLan=true} lets the integrated server
 * accept unauthenticated dev accounts, so two dev clients can meet on LAN (DESIGN §4.12).
 * Vanilla turns authentication on in {@code IntegratedServer.initServer()}; this turns it back off right after.
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerOfflineLanMixin {
    @Inject(method = "initServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/server/IntegratedServer;setUsesAuthentication(Z)V", shift = At.Shift.AFTER))
    private void dropin$offlineLan(CallbackInfoReturnable<Boolean> cir) {
        if (DropIn.switches().offlineLan()) {
            ((MinecraftServer) (Object) this).setUsesAuthentication(false);
            DropIn.LOGGER.warn("dropin.dev.offlineLan: integrated server accepts unauthenticated players (development only)");
        }
    }
}
