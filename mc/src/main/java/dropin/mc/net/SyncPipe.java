package dropin.mc.net;

import dropin.core.sync.Transport;
import dropin.core.sync.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One sync connection on a Netty channel (LAN TCP, a relay stream or Dialtone): cuts the byte stream
 * into frames and hands them, one at a time and off the event loop, to a sync session. It is also the
 * session's {@link Transport}.
 */
public final class SyncPipe extends ChannelInboundHandlerAdapter implements Transport {
    /** The session behind the pipe. Called on the pipe's own thread, never concurrently. */
    public interface Endpoint {
        void onFrame(byte[] frame);

        void onClosed();
    }

    private final Wire.Decoder decoder = new Wire.Decoder();
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Channel channel;
    private volatile Endpoint endpoint;

    public SyncPipe(String threadName) {
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Must be set before the pipe is added to a pipeline. */
    public void endpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public Channel channel() {
        return channel;
    }

    /** Runs something on the pipe's thread, in order with the frames. */
    public void execute(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            // The connection is already closed.
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        channel = ctx.channel();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (message instanceof ByteBuf buffer) {
            try {
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                feed(bytes);
            } finally {
                buffer.release();
            }
        } else {
            ctx.fireChannelRead(message);
        }
    }

    /** Bytes received on the channel. Event loop only. */
    public void feed(byte[] bytes) {
        try {
            decoder.feed(bytes, 0, bytes.length, frame -> execute(() -> endpoint.onFrame(frame)));
        } catch (IOException e) {
            close();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        finish();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
        finish();
    }

    private void finish() {
        if (closed.compareAndSet(false, true)) {
            execute(endpoint::onClosed);
            executor.shutdown();
        }
    }

    @Override
    public void send(byte[] frame) {
        Channel target = channel;
        if (target != null && target.isOpen()) {
            target.writeAndFlush(Unpooled.wrappedBuffer(Wire.frame(frame)));
        }
    }

    @Override
    public void close() {
        Channel target = channel;
        if (target != null) {
            target.close();
        }
        finish();
    }

    /** Blocks while the channel's send buffer is full, so bulk data never piles up in memory. */
    public void awaitWritable(long maxMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxMillis;
        Channel target = channel;
        while (target != null && target.isOpen() && !target.isWritable() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }
}
