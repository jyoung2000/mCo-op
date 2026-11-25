package dropin.mixin.mc;

import dropin.mc.coop.HostSession;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DESIGN 4.3: the rendezvous record's LAN hint names the port the hosted world was opened on. 1.12.2 port: 26.x
 * reads it with {@code server.getPort()}; this version's integrated server does not keep it, but
 * {@code shareToLAN} returns it, whoever opens the world to LAN (DropIn's host session or the player).
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerLanPortMixin {
    @Inject(method = "shareToLAN", at = @At("RETURN"))
    private void dropin$rememberPort(GameType type, boolean allowCheats, CallbackInfoReturnable<String> cir) {
        HostSession.lanPublished((IntegratedServer) (Object) this, cir.getReturnValue());
    }
}
