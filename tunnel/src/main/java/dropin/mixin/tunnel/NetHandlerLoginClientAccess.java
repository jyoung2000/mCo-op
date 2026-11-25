package dropin.mixin.tunnel;

import net.minecraft.client.network.NetHandlerLoginClient;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.12.2 port: NetHandlerLoginClient's private {@code networkManager}, for ClientHandshakePacketListenerImplMixin
 * (an accessor, since shadow members are not reobfuscated by CleanroomGradle).
 */
@Mixin(NetHandlerLoginClient.class)
public interface NetHandlerLoginClientAccess {
    @Accessor("networkManager")
    NetworkManager dropin$networkManager();
}
