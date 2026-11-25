package dropin.mixin.tunnel;

import dropin.tunnel.DialtoneConnectionExtensions;
import dropin.tunnel.dialtone.DialtoneAddress;
import net.minecraft.server.network.NetHandlerLoginServer;
import net.minecraft.util.CryptManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;

/**
 * 1.12.2 port: the session hash of a login is computed in {@code NetHandlerLoginServer}'s authenticator thread (its
 * anonymous class {@code $2}). On a Dialtone connection it is the connection's exported keying material, as upstream
 * did in {@code ServerLoginPacketListenerImplMixin.digestData}.
 */
@Mixin(targets = "net.minecraft.server.network.NetHandlerLoginServer$2")
public class ServerLoginAuthenticatorMixin {
    @Shadow(remap = false) @Final
    NetHandlerLoginServer this$0;

    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/CryptManager;getServerIdHash(Ljava/lang/String;Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B"), remap = false)
    private byte[] digestData(String string, PublicKey publicKey, SecretKey secretKey) {
        if (this$0.networkManager.getRemoteAddress() instanceof DialtoneAddress) {
            return ((DialtoneConnectionExtensions) this$0.networkManager).e4mc$exportKeyingMaterial("EXPERIMENTAL mojang authentication".getBytes(StandardCharsets.UTF_8), new byte[0], 20);
        }
        return CryptManager.getServerIdHash(string, publicKey, secretKey);
    }
}
