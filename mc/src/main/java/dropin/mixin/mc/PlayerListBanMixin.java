package dropin.mixin.mc;

import com.mojang.authlib.GameProfile;
import dropin.mc.host.HostSync;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * DESIGN 4.13: whoever hosts refuses the UUIDs the owner's policy bans. 1.12.2 port: 26.x
 * {@code canPlayerLogin} is {@code PlayerList.allowUserToConnect(SocketAddress, GameProfile)}, which returns the
 * refusal as a String; {@code NetHandlerLoginServer} sends it as {@code new TextComponentTranslation(refusal)}.
 * So a refusal without arguments is passed as its translation key, which the refused player's game translates;
 * one with arguments is translated here.
 */
@Mixin(PlayerList.class)
public class PlayerListBanMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @Inject(method = "allowUserToConnect", at = @At("HEAD"), cancellable = true)
    private void dropin$policyBan(SocketAddress address, GameProfile profile, CallbackInfoReturnable<String> cir) {
        ITextComponent refusal = HostSync.banMessage(this.server, profile.getId());
        if (refusal != null) {
            cir.setReturnValue(refusal instanceof TextComponentTranslation translation && translation.getFormatArgs().length == 0
                    ? translation.getKey() : refusal.getUnformattedText());
        }
    }
}
