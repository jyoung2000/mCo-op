package dropin.mixin.mc;

import dropin.mc.compat.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DESIGN 4.9: the host's quit. The pause menu's "Save and Quit" ends in {@code PauseScreen.onDisconnect} in
 * 1.21.1 (26.x: {@code Minecraft.disconnectFromWorld}); with friends connected to a co-op world the handoff
 * runs first and may be cancelled. {@link Compat#disconnectFromWorld} is that method with the hooks in front.
 */
@Mixin(PauseScreen.class)
public abstract class MinecraftDisconnectMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void dropin$handoff(CallbackInfo ci) {
        ci.cancel();
        Compat.disconnectFromWorld(Minecraft.getInstance(), Compat.DEFAULT_QUIT_MESSAGE);
    }
}
