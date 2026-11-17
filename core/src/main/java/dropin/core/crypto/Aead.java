package dropin.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/** ChaCha20-Poly1305 from the JDK. Key 32 bytes, nonce 12 bytes, tag 16 bytes appended to the ciphertext. */
public final class Aead {
    public static final int KEY_LEN = 32;
    public static final int NONCE_LEN = 12;
    public static final int TAG_LEN = 16;

    private Aead() {
    }

    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext) {
        try {
            return cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws GeneralSecurityException if the ciphertext, nonce, key or associated data do not match */
    public static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext) throws GeneralSecurityException {
        if (ciphertext.length < TAG_LEN) {
            throw new GeneralSecurityException("ciphertext shorter than the tag");
        }
        return cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext);
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] aad) throws GeneralSecurityException {
        if (key.length != KEY_LEN || nonce.length != NONCE_LEN) {
            throw new IllegalArgumentException("key must be 32 bytes and nonce 12 bytes");
        }
        Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
        cipher.init(mode, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
        if (aad != null && aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher;
    }
}
