package dropin.mixin.mc;

import com.google.common.util.concurrent.UncheckedExecutionException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 1.12.2 port: "Save and Quit to Title" could hang the client for good. The quit sends the disconnect packet and then
 * calls {@code Minecraft.loadWorld(null)}, whose {@code IntegratedServer.initiateShutdown} queues a task on the server
 * thread ({@code addScheduledTask}) and waits for it with {@code Futures.getUnchecked}. When the server thread drains
 * its task queue and then, in the same tick, handles the disconnect ("Stopping singleplayer server as player logged
 * out"), its loop ends and the task queued in between never runs (thread dumps: no server thread left, the client
 * thread parked in initiateShutdown). This waits for the task only while the server has not stopped; once it has,
 * the task is moot (it logs out the other players, and stopping the server saves and removes every player).
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerShutdownMixin {
    @Redirect(method = "initiateShutdown", at = @At(value = "INVOKE", remap = false,
            target = "Lcom/google/common/util/concurrent/Futures;getUnchecked(Ljava/util/concurrent/Future;)Ljava/lang/Object;"))
    private Object dropin$waitUnlessStopped(Future<?> task) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return task.get(50, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (server.isServerStopped()) {
                        task.cancel(false);
                        return null;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    throw new UncheckedExecutionException(e.getCause());
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
