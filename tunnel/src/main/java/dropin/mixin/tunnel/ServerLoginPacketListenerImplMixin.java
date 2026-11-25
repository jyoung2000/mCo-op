package dropin.mixin.tunnel;

import dropin.tunnel.dialtone.DialtoneAddress;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.login.client.CPacketEncryptionResponse;
import net.minecraft.server.network.NetHandlerLoginServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.crypto.SecretKey;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

/**
 * 1.12.2 port: 26.x {@code ServerLoginPacketListenerImpl} is {@code NetHandlerLoginServer}. A Dialtone connection is
 * already encrypted, so the login skips the game's own encryption: no public key is sent, the nonce and shared key
 * are not checked, and {@code ConnectionMixin} keeps {@code enableEncryption} from adding ciphers. The session hash
 * is replaced in the authenticator thread ({@code ServerLoginAuthenticatorMixin}).
 */
@Mixin(NetHandlerLoginServer.class)
public class ServerLoginPacketListenerImplMixin {
    @Shadow @Final
    public NetworkManager networkManager;

    @ModifyArg(method = "processLoginStart", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/login/server/SPacketEncryptionRequest;<init>(Ljava/lang/String;Ljava/security/PublicKey;[B)V"), index = 1)
    private PublicKey killDoubleEncryption(PublicKey publicKey) {
        if (networkManager.getRemoteAddress() instanceof DialtoneAddress) {
            return null;
        }
        return publicKey;
    }

    @Redirect(method = "processEncryptionResponse", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/login/client/CPacketEncryptionResponse;getVerifyToken(Ljava/security/PrivateKey;)[B"))
    private byte[] patchGetNonce(CPacketEncryptionResponse instance, PrivateKey privateKey) {
        if (networkManager.getRemoteAddress() instanceof DialtoneAddress) {
            return new byte[0];
        }
        return instance.getVerifyToken(privateKey);
    }

    @Redirect(method = "processEncryptionResponse", at = @At(value = "INVOKE", target = "Ljava/util/Arrays;equals([B[B)Z", remap = false))
    private boolean isNonceEqual(byte[] lhs, byte[] rhs) {
        if (networkManager.getRemoteAddress() instanceof DialtoneAddress) {
            return true;
        }
        return Arrays.equals(lhs, rhs);
    }

    @Redirect(method = "processEncryptionResponse", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/login/client/CPacketEncryptionResponse;getSecretKey(Ljava/security/PrivateKey;)Ljavax/crypto/SecretKey;"))
    private SecretKey getSecretKey(CPacketEncryptionResponse instance, PrivateKey privateKey) {
        if (networkManager.getRemoteAddress() instanceof DialtoneAddress) {
            return null;
        }
        return instance.getSecretKey(privateKey);
    }
}
