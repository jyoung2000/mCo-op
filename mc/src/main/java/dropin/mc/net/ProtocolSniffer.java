package dropin.mc.net;

import dropin.core.sync.Wire;
import dropin.mc.DropIn;
import dropin.mc.host.HostSync;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * First handler of every incoming connection of a published world (DESIGN 4.6). It holds back
 * {@code channelActive}, looks at the first bytes, and then either
 * <ul>
 * <li>installs the vanilla initializer, fires {@code channelActive}, replays the bytes and removes
 * itself (anything that is not the sync magic: a login, a status ping, a legacy ping), or</li>
 * <li>installs the sync pipeline (the 8-byte magic {@code DROPIN\0\1}).</li>
 * </ul>
 * It decides as soon as one byte differs, so a vanilla connection is never delayed by more than its
 * first packet.
 */
public final class ProtocolSniffer extends ChannelInboundHandlerAdapter {
    private static final int DECIDE_TIMEOUT_SECONDS = 30;

    private final ChannelHandler vanilla;
    private final byte[] first = new byte[Wire.MAGIC_LENGTH];
    private ByteBuf held = Unpooled.EMPTY_BUFFER;
    private boolean decided;
    private ScheduledFuture<?> timeout;

    private ProtocolSniffer(ChannelHandler vanilla) {
        this.vanilla = vanilla;
    }

    /** Wraps the vanilla child handler: the result puts a sniffer in front of it on every new channel. */
    public static ChannelHandler wrap(ChannelHandler vanilla) {
        return new Installer(vanilla);
    }

    @ChannelHandler.Sharable
    private static final class Installer extends ChannelInitializer<Channel> {
        private final ChannelHandler vanilla;

        Installer(ChannelHandler vanilla) {
            this.vanilla = vanilla;
        }

        @Override
        protected void initChannel(Channel channel) {
            channel.pipeline().addLast("dropin_sniffer", new ProtocolSniffer(vanilla));
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        timeout = ctx.executor().schedule(() -> {
            if (!decided) {
                ctx.close();
            }
        }, DECIDE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Held back until the protocol is known; the vanilla handlers are not in the pipeline yet.
        if (!ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (decided || !(message instanceof ByteBuf buffer)) {
            ctx.fireChannelRead(message);
            return;
        }
        held = held.isReadable() ? Unpooled.wrappedBuffer(held, buffer) : buffer;
        int known = Math.min(first.length, held.readableBytes());
        held.getBytes(held.readerIndex(), first, 0, known);
        int match = Wire.matchMagic(first, known);
        if (match < 0) {
            vanilla(ctx);
        } else if (match > 0) {
            sync(ctx);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        if (decided) {
            ctx.fireChannelReadComplete();
        } else if (!ctx.channel().config().isAutoRead()) {
            ctx.read();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (decided) {
            ctx.fireChannelInactive();
        } else {
            decided = true;
            release();
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (timeout != null) {
            timeout.cancel(false);
        }
        release();
    }

    private void release() {
        if (held.refCnt() > 0 && held != Unpooled.EMPTY_BUFFER) {
            held.release();
        }
        held = Unpooled.EMPTY_BUFFER;
    }

    private void vanilla(ChannelHandlerContext ctx) {
        decided = true;
        ByteBuf bytes = held;
        held = Unpooled.EMPTY_BUFFER;
        ChannelPipeline pipeline = ctx.pipeline();
        // The vanilla handler is a ChannelInitializer: on a registered channel it runs at once, adds its
        // handlers behind this one and removes itself.
        pipeline.addLast(vanilla);
        if (ctx.channel().isActive()) {
            ctx.fireChannelActive();
        }
        ctx.fireChannelRead(bytes);
        pipeline.remove(this);
    }

    private void sync(ChannelHandlerContext ctx) {
        decided = true;
        SyncPipe pipe = HostSync.accept(ctx.channel());
        if (pipe == null) {
            DropIn.LOGGER.debug("Sync connection refused: no co-op world is hosted");
            release();
            ctx.close();
            return;
        }
        held.skipBytes(Wire.MAGIC_LENGTH);
        byte[] rest = new byte[held.readableBytes()];
        held.readBytes(rest);
        release();
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.addLast("dropin_sync", pipe);
        if (rest.length > 0) {
            pipe.feed(rest);
        }
        pipeline.remove(this);
    }
}
