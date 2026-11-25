package dropin.mixin.tunnel;

import net.minecraft.network.login.server.SPacketEncryptionRequest;
import net.minecraft.util.CryptManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.security.PublicKey;

/**
 * 1.12.2 port: 26.x {@code ClientboundHelloPacket} is {@code SPacketEncryptionRequest}, and the key is encoded in the
 * packet itself (26.x encoded it in the login handler). A Dialtone login sends no key: an empty array both ways.
 */
@Mixin(SPacketEncryptionRequest.class)
public class ClientboundHelloPacketMixin {
    @Redirect(method = "writePacketData", at = @At(value = "INVOKE", target = "Ljava/security/PublicKey;getEncoded()[B", remap = false))
    private byte[] killDoubleEncryption(PublicKey instance) {
        return instance == null ? new byte[0] : instance.getEncoded();
    }

    @Redirect(method = "readPacketData", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/CryptManager;decodePublicKey([B)Ljava/security/PublicKey;"))
    private PublicKey noKey(byte[] encoded) {
        return encoded.length == 0 ? null : CryptManager.decodePublicKey(encoded);
    }
}
