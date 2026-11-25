package dropin.mixin.tunnel;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// 1.20.1: the connection field sits on ServerGamePacketListenerImpl (ServerCommonPacketListenerImpl came in 1.20.2).
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerImplAccessor {
    @Accessor("connection")
    Connection getConnection();
}
