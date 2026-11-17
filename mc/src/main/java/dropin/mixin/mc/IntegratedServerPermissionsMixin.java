package dropin.mixin.mc;

import dropin.mc.host.HostSync;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DESIGN 4.13: in a co-op session the owner has operator rights whoever is hosting, and
 * {@code membersMayUseCommands} applies to the host and everyone else. Vanilla instead gives the rights
 * to whoever opened the world.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerPermissionsMixin {
    @Inject(method = "getProfilePermissions", at = @At("HEAD"), cancellable = true)
    private void dropin$policyPermissions(NameAndId nameAndId, CallbackInfoReturnable<LevelBasedPermissionSet> cir) {
        LevelBasedPermissionSet set = HostSync.permissions((MinecraftServer) (Object) this, nameAndId.id());
        if (set != null) {
            cir.setReturnValue(set);
        }
    }
}
