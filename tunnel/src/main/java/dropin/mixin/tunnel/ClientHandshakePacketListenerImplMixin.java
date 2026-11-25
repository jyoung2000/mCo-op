package dropin.mixin.tunnel;

import dropin.tunnel.DialtoneConnectionExtensions;
import dropin.tunnel.dialtone.DialtoneAddress;
import net.minecraft.client.network.NetHandlerLoginClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.login.server.SPacketEncryptionRequest;
import net.minecraft.util.CryptManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;

/** 1.12.2 port: 26.x {@code ClientHandshakePacketListenerImpl} is {@code NetHandlerLoginClient}. */
@Mixin(NetHandlerLoginClient.class)
public class ClientHandshakePacketListenerImplMixin {
    @Shadow @Final private NetworkManager networkManager;

    @Redirect(method = "handleEncryptionRequest", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/login/server/SPacketEncryptionRequest;getPublicKey()Ljava/security/PublicKey;"))
    private PublicKey publicKey(SPacketEncryptionRequest instance) {
        if (networkManager.getRemoteAddress() instanceof DialtoneAddress) {
            return null;
        }
        return instance.getPublicKey();
    }

    @Redirect(method = "handleEncryptionRequest", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/CryptManager;getServerIdHash(Ljava/lang/String;Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B"))
    private byte[] digestData(String string, PublicKey publicKey, SecretKey secretKey) {
        if (networkManager.getRemoteAddress() instanceof DialtoneAddress) {
            return ((DialtoneConnectionExtensions) networkManager).e4mc$exportKeyingMaterial("EXPERIMENTAL mojang authentication".getBytes(StandardCharsets.UTF_8), new byte[0], 20);
        }
        return CryptManager.getServerIdHash(string, publicKey, secretKey);
    }
}
