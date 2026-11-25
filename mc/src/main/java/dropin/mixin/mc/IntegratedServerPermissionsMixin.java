package dropin.mixin.mc;

import dropin.mc.host.HostSync;
import net.minecraft.server.MinecraftServer;
import com.mojang.authlib.GameProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DESIGN 4.13: in a co-op session the owner has operator rights whoever is hosting, and
 * {@code membersMayUseCommands} applies to the host and everyone else. Vanilla instead gives the rights
 * to whoever opened the world. (1.21.1 port: the method lives on MinecraftServer in this version.)
 */
@Mixin(MinecraftServer.class)
public abstract class IntegratedServerPermissionsMixin {
    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void dropin$policyPermissions(GameProfile profile, CallbackInfoReturnable<Integer> cir) {
        Integer set = HostSync.permissions((MinecraftServer) (Object) this, profile.getId());
        if (set != null) {
            cir.setReturnValue(set);
        }
    }
}
