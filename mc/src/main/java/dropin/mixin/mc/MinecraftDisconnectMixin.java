package dropin.mixin.mc;

import dropin.mc.handoff.HostHandoff;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DESIGN 4.9: the host's quit. The pause menu's "Save and Quit" ends in {@code disconnectFromWorld}; with
 * friends connected to a co-op world the handoff runs first and may be cancelled.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftDisconnectMixin {
    @Inject(method = "disconnectFromWorld", at = @At("HEAD"), cancellable = true)
    private void dropin$handoff(Component message, CallbackInfo ci) {
        // DESIGN 4.9 (crash): a member that leaves by itself has not lost its host.
        dropin.mc.handoff.CrashFailover.leaving((Minecraft) (Object) this);
        if (HostHandoff.intercept((Minecraft) (Object) this, message)) {
            ci.cancel();
        }
    }
}
