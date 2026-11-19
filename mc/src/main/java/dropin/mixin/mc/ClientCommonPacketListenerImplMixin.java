package dropin.mixin.mc;

import dropin.mc.handoff.CrashFailover;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DESIGN 4.9 (crash): a member whose game connection ends without a handoff and without leaving gets the
 * failover screen instead of "Connection lost". {@code onDisconnect} shows whatever
 * {@code createDisconnectScreen} returns.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
    @Shadow
    @Final
    protected ServerData serverData;

    @Inject(method = "createDisconnectScreen", at = @At("RETURN"), cancellable = true)
    private void dropin$hostLost(DisconnectionDetails details, CallbackInfoReturnable<Screen> cir) {
        Screen replaced = CrashFailover.lost(Minecraft.getInstance(), this.serverData == null ? null : this.serverData.ip,
                cir.getReturnValue());
        if (replaced != null) {
            cir.setReturnValue(replaced);
        }
    }
}
