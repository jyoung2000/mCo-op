package dropin.mixin.mc.dev;

import dropin.mc.DropIn;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Development only (see DropInMixinPlugin): drives the scripted self-test once per client tick.
 * 1.12.2 port: 26.x {@code Minecraft.tick()} is {@code Minecraft.runTick()}.
 */
@Mixin(Minecraft.class)
public class MinecraftTickMixin {
    @Inject(method = "runTick", at = @At("TAIL"))
    private void dropin$afterTick(CallbackInfo ci) {
        DropIn.onClientTick();
    }
}
