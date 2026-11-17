package dropin.core.crypto;

/** Base32 without padding over a given 32-character alphabet (most significant bits first). */
public final class Base32 {
    /** RFC 4648 alphabet, used by invite codes. */
    public static final Base32 RFC4648 = new Base32("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567");

    private final char[] alphabet;
    private final int[] values = new int[128];

    public Base32(String alphabet) {
        if (alphabet.length() != 32) {
            throw new IllegalArgumentException("alphabet must have 32 characters");
        }
        this.alphabet = alphabet.toCharArray();
        java.util.Arrays.fill(values, -1);
        for (int i = 0; i < 32; i++) {
            values[this.alphabet[i]] = i;
        }
    }

    public String encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(alphabet[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(alphabet[(buffer << (5 - bits)) & 31]);
        }
        return out.toString();
    }

    /** @throws IllegalArgumentException on a character outside the alphabet, an impossible length or non-zero spare bits */
    public byte[] decode(String text) {
        int spare = text.length() * 5 % 8;
        if (spare >= 5) {
            throw new IllegalArgumentException("impossible base32 length " + text.length());
        }
        byte[] out = new byte[text.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int pos = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int value = c < 128 ? values[c] : -1;
            if (value < 0) {
                throw new IllegalArgumentException("character not in the base32 alphabet at position " + i);
            }
            buffer = ((buffer << 5) | value) & 0xFFFF;
            bits += 5;
            if (bits >= 8) {
                out[pos++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        if (bits > 0 && (buffer & ((1 << bits) - 1)) != 0) {
            throw new IllegalArgumentException("non-zero trailing bits");
        }
        return out;
    }
}
