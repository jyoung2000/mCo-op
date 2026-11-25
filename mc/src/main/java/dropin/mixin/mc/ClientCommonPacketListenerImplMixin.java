package dropin.mixin.mc;

import dropin.mc.handoff.CrashFailover;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DESIGN 4.9 (crash): a member whose game connection ends without a handoff and without leaving gets the
 * failover screen instead of "Connection lost".
 * <p>
 * 1.12.2 port: 26.x {@code ClientCommonPacketListenerImpl.createDisconnectScreen} is
 * {@code NetHandlerPlayClient.onDisconnect(ITextComponent)} here, which calls {@code loadWorld(null)} (that clears
 * the current server data and the integrated server) and then shows {@code GuiDisconnected}. The address and
 * whether the connection was to this game's own server are read at its head; the screen is replaced at its end.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class ClientCommonPacketListenerImplMixin {

    @Unique
    private String dropin$address;

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void dropin$remember(ITextComponent reason, CallbackInfo ci) {
        Minecraft client = Minecraft.getMinecraft();
        ServerData data = client.getCurrentServerData();
        // A connection to this game's own integrated server is never a lost co-op host.
        this.dropin$address = client.getIntegratedServer() != null || data == null ? null : data.serverIP;
    }

    @Inject(method = "onDisconnect", at = @At("RETURN"))
    private void dropin$hostLost(ITextComponent reason, CallbackInfo ci) {
        String address = this.dropin$address;
        this.dropin$address = null;
        if (address == null) {
            return;
        }
        Minecraft client = Minecraft.getMinecraft();
        GuiScreen replaced = CrashFailover.lost(client, address, client.currentScreen);
        if (replaced != null) {
            client.displayGuiScreen(replaced);
        }
    }
}
