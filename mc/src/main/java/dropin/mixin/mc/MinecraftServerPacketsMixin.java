package dropin.mixin.mc;

import dropin.mc.host.HostSync;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DESIGN 4.5 (last checkpoint on quit), 1.21.1 only. {@code MinecraftServer.executeIfPossible(Runnable)} throws
 * once the server is stopped, and {@code Connection.channelRead0} then disconnects the player ("Server closed").
 * The flag is set just before {@code stopServer}, so while DropIn pushes the last checkpoint from the head of
 * {@code stopServer} every member that sends a game packet loses its connection, and with it its sync. 26.x
 * queues such packets until after that hook and never handles them; this drops them for that time, the same
 * outcome. {@code PacketUtils.ensureRunningOnSameThread} is the only caller.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerPacketsMixin {
    @Inject(method = "executeIfPossible", at = @At("HEAD"), cancellable = true)
    private void dropin$holdPackets(Runnable task, CallbackInfo ci) {
        if (HostSync.finalCheckpointRunning((MinecraftServer) (Object) this)) {
            ci.cancel();
        }
    }
}
