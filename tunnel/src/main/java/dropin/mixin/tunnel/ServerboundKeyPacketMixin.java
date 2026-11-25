package dropin.mixin.tunnel;

import net.minecraft.network.login.client.CPacketEncryptionResponse;
import net.minecraft.util.CryptManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.security.Key;

/** 1.12.2 port: 26.x {@code ServerboundKeyPacket} is {@code CPacketEncryptionResponse}. No key: nothing to encrypt. */
@Mixin(CPacketEncryptionResponse.class)
public class ServerboundKeyPacketMixin {
    @Redirect(method = "<init>(Ljavax/crypto/SecretKey;Ljava/security/PublicKey;[B)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/CryptManager;encryptData(Ljava/security/Key;[B)[B"))
    private byte[] encryptUsingKey(Key key, byte[] bs) {
        if (key == null) {
            return new byte[0];
        }
        return CryptManager.encryptData(key, bs);
    }
}
