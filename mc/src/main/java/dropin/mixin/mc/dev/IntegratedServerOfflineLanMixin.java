package dropin.mixin.mc.dev;

import dropin.mc.DropIn;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.HttpUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;

/**
 * Development only (see DropInMixinPlugin), DESIGN §4.12:
 * <ul>
 *   <li>{@code -Ddropin.dev.offlineLan=true} lets the integrated server accept unauthenticated dev accounts, so two
 *   dev clients can meet on LAN. 1.12.2 port: vanilla turns online mode on in {@code IntegratedServer.init()}
 *   ({@code setOnlineMode(true)}); this turns it back off right after. {@code NetHandlerLoginServer} then skips
 *   encryption and session checks for remote players.</li>
 *   <li>{@code -Ddropin.dev.lanPort=N} opens the world to LAN on port N. 1.12.2 port: 26.x passes the port to
 *   {@code publishServer}; 1.12.2's {@code shareToLAN} picks it with {@code HttpUtil.getSuitableLanPort()}.</li>
 * </ul>
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerOfflineLanMixin {
    @Inject(method = "init", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/integrated/IntegratedServer;setOnlineMode(Z)V", shift = At.Shift.AFTER))
    private void dropin$offlineLan(CallbackInfoReturnable<Boolean> cir) {
        if (DropIn.switches().offlineLan()) {
            ((MinecraftServer) (Object) this).setOnlineMode(false);
            DropIn.LOGGER.warn("dropin.dev.offlineLan: integrated server accepts unauthenticated players (development only)");
        }
    }

    @Redirect(method = "shareToLAN", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/HttpUtil;getSuitableLanPort()I"))
    private int dropin$lanPort() throws IOException {
        java.util.Optional<String> wanted = DropIn.switches().read("dropin.dev.lanPort");
        return wanted.isPresent() ? Integer.parseInt(wanted.get()) : HttpUtil.getSuitableLanPort();
    }
}
