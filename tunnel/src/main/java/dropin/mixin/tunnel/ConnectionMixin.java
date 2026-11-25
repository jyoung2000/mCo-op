package dropin.mixin.tunnel;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dropin.tunnel.DialtoneConnectionExtensions;
import dropin.tunnel.DialtoneResolver;
import dropin.tunnel.dialtone.DialtoneAddress;
import dropin.tunnel.dialtone.DialtoneAmbientSession;
import dropin.tunnel.dialtone.DialtoneChannel;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.crypto.SecretKey;
import java.net.InetAddress;

/**
 * 1.12.2 port: 26.x {@code Connection} is {@code NetworkManager}. 1.12.2 connects with an {@code InetAddress} and a
 * port, so the Dialtone ticket cannot ride inside the resolved address as it did upstream
 * ({@code SmugglersInetSocketAddress}); it is looked up here, from the host name the address was resolved from
 * ({@link DialtoneResolver}, the logic of upstream's {@code ServerNameResolverMixin}).
 */
@Mixin(NetworkManager.class)
public abstract class ConnectionMixin implements DialtoneConnectionExtensions {

    @Unique
    private static DialtoneAddress e4mc$smuggledDialtoneAddress = null;

    @Override
    public byte[] e4mc$exportKeyingMaterial(byte[] label, byte[] context, int length) {
        Channel channel = ((NetworkManager) (Object) this).channel();
        if (channel instanceof DialtoneChannel dialtoneChannel) {
            return dialtoneChannel.exportKeyingMaterial(label, context, length);
        }
        return null;
    }

    @Override
    public String e4mc$connInfo() {
        Channel channel = ((NetworkManager) (Object) this).channel();
        if (channel instanceof DialtoneChannel dialtoneChannel) {
            return dialtoneChannel.debugInfo();
        }
        return channel.getClass().getCanonicalName();
    }

    @Inject(method = "createNetworkManagerAndConnect", at = @At("HEAD"))
    private static void hijackStart(InetAddress address, int port, boolean useNative, CallbackInfoReturnable<NetworkManager> cir) {
        String ticket = DialtoneResolver.ticketFor(address, port);
        e4mc$smuggledDialtoneAddress = ticket == null ? null : new DialtoneAddress(ticket);
    }

    @ModifyArg(method = "createNetworkManagerAndConnect", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
    private static Class hijackChannel(Class clazz) {
        if (e4mc$smuggledDialtoneAddress != null) {
            return DialtoneChannel.class;
        } else {
            return clazz;
        }
    }

    @ModifyArg(method = "createNetworkManagerAndConnect", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
    private static EventLoopGroup hijackGroup(EventLoopGroup group) {
        if (e4mc$smuggledDialtoneAddress != null) {
            return DialtoneAmbientSession.INSTANCE.group;
        } else {
            return group;
        }
    }

    @WrapOperation(method = "createNetworkManagerAndConnect", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;", remap = false))
    private static ChannelFuture hijackConnect(Bootstrap instance, InetAddress inetHost, int inetPort, Operation<ChannelFuture> operation) {
        if (e4mc$smuggledDialtoneAddress != null) {
            var ret = instance.connect(e4mc$smuggledDialtoneAddress);
            e4mc$smuggledDialtoneAddress = null;
            return ret;
        } else {
            return operation.call(instance, inetHost, inetPort);
        }
    }

    @Inject(method = "enableEncryption", at = @At("HEAD"), cancellable = true)
    private void killDoubleEncryption(SecretKey key, CallbackInfo ci) {
        Channel channel = ((NetworkManager) (Object) this).channel();
        if (channel instanceof DialtoneChannel) {
            ci.cancel();
        }
    }
}
