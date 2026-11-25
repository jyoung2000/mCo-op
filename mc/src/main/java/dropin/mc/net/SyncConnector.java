package dropin.mc.net;

import dropin.core.sync.Wire;
import dropin.mc.coop.StatusPing;
import dropin.tunnel.DialtoneResolver;
import dropin.tunnel.SmugglersInetSocketAddress;
import dropin.tunnel.dialtone.DialtoneAddress;
import dropin.tunnel.dialtone.DialtoneAmbientSession;
import dropin.tunnel.dialtone.DialtoneChannel;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Opens the member's sync connection (DESIGN 4.6) to the address the game itself connected to, resolved
 * the way the game resolves it: Dialtone when the address carries a ticket, plain TCP otherwise. The
 * first bytes sent are the sync magic.
 */
public final class SyncConnector {
    private static final long CONNECT_TIMEOUT_MS = 20_000;

    private SyncConnector() {
    }

    /** Blocking. The pipe's endpoint must be set. */
    public static Channel connect(String address, SyncPipe pipe) throws IOException, InterruptedException {
        ChannelFuture future;
        // Upstream's Dialtone session start is not safe to run from two threads at once; status pings
        // take the same lock.
        synchronized (StatusPing.class) {
            // 1.12.2 port: the tunnel's DialtoneResolver resolves like ServerAddress.fromString + InetAddress.getByName
            // and returns a SmugglersInetSocketAddress when the server is reached over Dialtone.
            Optional<InetSocketAddress> resolved = DialtoneResolver.resolve(address);
            if (!resolved.isPresent()) {
                throw new IOException("Cannot resolve " + address);
            }
            InetSocketAddress socket = resolved.get();
            Bootstrap bootstrap = new Bootstrap().handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel channel) {
                    channel.pipeline().addLast("dropin_sync", pipe);
                }
            });
            if (socket instanceof SmugglersInetSocketAddress smuggled) {
                future = bootstrap.channel(DialtoneChannel.class).group(DialtoneAmbientSession.INSTANCE.group)
                        .connect(new DialtoneAddress(smuggled.ticket));
            } else {
                // 1.12.2 port: the game's own client worker group and channel class, chosen the way
                // NetworkManager.createNetworkManagerAndConnect chooses them (epoll if available and enabled).
                boolean epoll = Epoll.isAvailable() && Minecraft.getMinecraft().gameSettings.isUsingNativeTransport();
                Class<? extends SocketChannel> type = epoll ? EpollSocketChannel.class : NioSocketChannel.class;
                EventLoopGroup group = epoll ? NetworkManager.CLIENT_EPOLL_EVENTLOOP.getValue()
                        : NetworkManager.CLIENT_NIO_EVENTLOOP.getValue();
                future = bootstrap.channel(type).group(group)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .connect(socket.getAddress(), socket.getPort());
            }
            if (!future.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                future.cancel(true);
                future.channel().close();
                throw new IOException("Timed out connecting the sync channel to " + address);
            }
        }
        if (!future.isSuccess()) {
            throw new IOException("Could not connect the sync channel to " + address, future.cause());
        }
        Channel channel = future.channel();
        channel.writeAndFlush(Unpooled.wrappedBuffer(Wire.magic()));
        return channel;
    }
}
