package dropin.mixin.mc;

import dropin.mc.handoff.CrashFailover;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * DESIGN 4.9 (crash): a member whose game connection ends without a handoff and without leaving gets the
 * failover screen instead of "Connection lost". 1.20.1 port: {@code ClientPacketListener.onDisconnect(Component)}
 * builds the disconnected screen itself and hands it to {@code Minecraft.setScreen}; every such call is changed.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerDisconnectMixin {
    @Shadow
    @Final
    private ServerData serverData;

    @ModifyArg(method = "onDisconnect", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private Screen dropin$hostLost(Screen screen) {
        Screen replaced = CrashFailover.lost(Minecraft.getInstance(), this.serverData == null ? null : this.serverData.ip, screen);
        return replaced != null ? replaced : screen;
    }
}
