package dropin.mixin.mc;

import dropin.mc.host.HostSync;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/** DESIGN 4.13: whoever hosts refuses the UUIDs the owner's policy bans. */
@Mixin(PlayerList.class)
public class PlayerListBanMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void dropin$policyBan(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Component> cir) {
        Component refusal = HostSync.banMessage(this.server, profile.getId());
        if (refusal != null) {
            cir.setReturnValue(refusal);
        }
    }
}
