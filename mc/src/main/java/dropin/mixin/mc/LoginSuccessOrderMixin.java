package dropin.mixin.mc;

import io.netty.channel.Channel;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.network.NetHandlerLoginServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.TimeUnit;

/**
 * 1.12.2 port. {@code NetHandlerLoginServer.tryAcceptPlayer} runs on the server thread: it sends
 * {@code SPacketLoginSuccess}, which {@code NetworkManager.dispatchPacket} only queues on the channel's event loop
 * (the LOGIN state it checked stays as it is), and then {@code FMLNetworkHandler.fmlServerHandshake} reaches
 * {@code NetworkDispatcher.serverInitiateHandshake}, which sets the channel to PLAY at once, from the server thread.
 * When the queued write runs after that, the encoder looks SPacketLoginSuccess up in PLAY and the player is dropped
 * with "Can't serialize unregistered packet" (seen in 4 of 5 LAN joins of the m0 self-test, the packet logged in PLAY
 * by a probe on NettyPacketEncoder). This writes the packet on the event loop and waits for it, so it is encoded in
 * LOGIN before the handshake moves on. Tasks of one event loop run in order, so a compression packet sent just before
 * still goes out first.
 */
@Mixin(NetHandlerLoginServer.class)
public class LoginSuccessOrderMixin {
    @Redirect(method = "tryAcceptPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkManager;sendPacket(Lnet/minecraft/network/Packet;)V"))
    private void dropin$sendLoginSuccessFirst(NetworkManager manager, Packet<?> packet) {
        Channel channel = manager.channel();
        if (channel == null || !channel.isOpen() || channel.eventLoop().inEventLoop()) {
            manager.sendPacket(packet);
            return;
        }
        try {
            channel.eventLoop().submit(() -> manager.sendPacket(packet)).await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
