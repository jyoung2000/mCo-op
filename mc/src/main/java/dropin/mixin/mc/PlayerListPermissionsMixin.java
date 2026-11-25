package dropin.mixin.mc;

import dropin.mc.host.HostSync;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DESIGN 4.13, the client's side of {@code IntegratedServerPermissionsMixin}. 1.12.2 port: the operator level a
 * client knows of itself (F3+F4 game mode switcher, command blocks, which commands it offers) is sent by
 * {@code PlayerList.updatePermissionLevel}, on login and whenever the rights change; in a co-op session it sends
 * DropIn's level instead of vanilla's. (26.x / 1.21.1 derive both from {@code getProfilePermissions}.)
 */
@Mixin(PlayerList.class)
public abstract class PlayerListPermissionsMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Shadow
    private void sendPlayerPermissionLevel(EntityPlayerMP player, int level) {
        throw new AssertionError();
    }

    @Inject(method = "updatePermissionLevel", at = @At("HEAD"), cancellable = true)
    private void dropin$policyLevel(EntityPlayerMP player, CallbackInfo ci) {
        Integer set = HostSync.permissions(this.server, player.getUniqueID());
        if (set != null) {
            sendPlayerPermissionLevel(player, set);
            ci.cancel();
        }
    }
}
