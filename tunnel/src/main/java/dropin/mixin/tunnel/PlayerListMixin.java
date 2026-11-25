package dropin.mixin.tunnel;

import com.mojang.authlib.GameProfile;
import dropin.tunnel.Config;
import dropin.tunnel.E4mcClient;
import dropin.tunnel.Mirror;
import dropin.tunnel.UserListReload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.server.management.UserListBans;
import net.minecraft.server.management.UserListWhitelist;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.net.SocketAddress;

/** 1.12.2 port: same behaviour as upstream, with 1.12.2's names. */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    void injectListLoads(CallbackInfo ci) {
        if (Config.INSTANCE.restoreDedicatedCommands.value()) {
            PlayerList self = (PlayerList) (Object) this;
            Mirror.setUsingWhitelist(self.getServerInstance(), self, Config.INSTANCE.useWhiteList.value());
            try {
                UserListReload.readSavedFile(self.getBannedPlayers());
            } catch (IOException e) {
                E4mcClient.LOGGER.warn("Failed to load user banlist: ", e);
            }
            try {
                UserListReload.readSavedFile(self.getWhitelistedPlayers());
            } catch (IOException e) {
                E4mcClient.LOGGER.warn("Failed to load whitelist: ", e);
            }
        }
    }

    @Inject(method = "allowUserToConnect", at = @At("HEAD"), cancellable = true)
    public void allowOwnerLogin(SocketAddress socketAddress, GameProfile gameProfile, CallbackInfoReturnable<String> cir) {
        if (Mirror.isSingleplayerOwnerObj(((PlayerList) (Object) this).getServerInstance(), gameProfile)) {
            cir.setReturnValue(null);
        }
    }
}
