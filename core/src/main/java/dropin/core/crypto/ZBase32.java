package dropin.core.crypto;

/** z-base-32, the encoding pkarr uses for public keys (a 32-byte key is 52 characters). */
public final class ZBase32 {
    private static final Base32 CODEC = new Base32("ybndrfg8ejkmcpqxot1uwisza345h769");

    private ZBase32() {
    }

    public static String encode(byte[] data) {
        return CODEC.encode(data);
    }

    public static byte[] decode(String text) {
        return CODEC.decode(text);
    }
}
