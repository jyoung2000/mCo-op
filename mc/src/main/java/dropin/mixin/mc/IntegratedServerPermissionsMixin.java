package dropin.mixin.mc;

import dropin.mc.host.HostSync;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DESIGN 4.13: in a co-op session the owner has operator rights whoever is hosting, and
 * {@code membersMayUseCommands} applies to the host and everyone else. Vanilla instead gives the rights
 * to whoever opened the world.
 * <p>
 * 1.12.2 port: 26.x / 1.21.1 ask {@code getProfilePermissions(GameProfile)}; 1.12.2 decides every command in
 * {@code EntityPlayerMP.canUseCommand(int level, String name)}: {@code seed}, {@code tell}, {@code help},
 * {@code me} and {@code trigger} first (always allowed), then {@code PlayerList.canSendCommands} and the
 * operator level. DropIn's level replaces the second part, so it is injected right before that call. The level the
 * client is told is set in {@code PlayerListPermissionsMixin}. (The class keeps its 1.21.1 name.)
 */
@Mixin(EntityPlayerMP.class)
public abstract class IntegratedServerPermissionsMixin {

    @Inject(method = "canUseCommand", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/management/PlayerList;canSendCommands(Lcom/mojang/authlib/GameProfile;)Z"))
    private void dropin$policyPermissions(int level, String command, CallbackInfoReturnable<Boolean> cir) {
        Integer set = HostSync.permissions(((EntityPlayerMP) (Object) this).server, ((EntityPlayerMP) (Object) this).getUniqueID());
        if (set != null) {
            cir.setReturnValue(set >= level);
        }
    }
}
