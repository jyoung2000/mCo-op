package dropin.mixin.tunnel;

import dropin.tunnel.Config;
import dropin.tunnel.E4mcClient;
import dropin.tunnel.QuiclimeSession;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import net.minecraft.network.NetworkSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetAddress;

/** 1.12.2 port: the game's TCP listener is {@code NetworkSystem.addEndpoint} (26.x {@code ServerConnectionListener}). */
@Mixin(NetworkSystem.class)
public abstract class ServerConnectionListenerMixin {
    @Unique
    private ChannelHandler e4mc$childHandler;
    @Unique
    private EventLoopGroup e4mc$group;

    @ModifyArg(method = "addEndpoint", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;childHandler(Lio/netty/channel/ChannelHandler;)Lio/netty/bootstrap/ServerBootstrap;", remap = false))
    private ChannelHandler interceptHandler(ChannelHandler childHandler) {
        childHandler = E4mcClient.wrapChildHandler(childHandler); // DropIn hook, DESIGN section 3 row 6
        e4mc$childHandler = childHandler;
        return childHandler;
    }

    @ModifyArg(method = "addEndpoint", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/ServerBootstrap;", remap = false))
    private EventLoopGroup interceptGroup(EventLoopGroup group) {
        e4mc$group = group;
        return group;
    }

    @Inject(method = "addEndpoint", at = @At(value = "TAIL"))
    private void interceptGroup(InetAddress inetAddress, int i, CallbackInfo ci) {
        if (Config.INSTANCE.hostEnabled.value()) {
            E4mcClient.session = new QuiclimeSession(e4mc$childHandler, e4mc$group);
            e4mc$childHandler = null;
            e4mc$group = null;
            E4mcClient.session.startAsync();
        } else {
            e4mc$childHandler = null;
            e4mc$group = null;
        }
    }

    @Inject(method = "terminateEndpoints", at = @At(value = "HEAD"))
    private void interceptStop(CallbackInfo ci) {
        QuiclimeSession session = E4mcClient.session;
        if ((session != null) && (session.state != QuiclimeSession.State.STOPPED)) {
            session.stop();
            E4mcClient.session = null;
        }
    }
}
