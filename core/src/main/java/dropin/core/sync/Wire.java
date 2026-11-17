package dropin.core.sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The sync side-channel on the wire (DESIGN 4.6): the magic, length-prefixed frames, message types and
 * the field encoding. A frame is a 4-byte big-endian length followed by that many bytes; its first
 * byte (after decryption, once the handshake is done) is the message type.
 */
public final class Wire {
    /** {@code DROPIN\0\1}: the first bytes a sync client sends. A vanilla handshake cannot start like this. */
    private static final byte[] MAGIC = {0x44, 0x52, 0x4F, 0x50, 0x49, 0x4E, 0x00, 0x01};
    public static final int MAGIC_LENGTH = MAGIC.length;
    public static final int PROTO = 3;
    public static final int MAX_FRAME = 4 * 1024 * 1024;
    /** Largest piece of one record in a RECORD frame. */
    public static final int PIECE = 256 * 1024;
    /** A CHECKPOINT lists its changed keys only up to this many; beyond it the receiver runs a diff. */
    public static final int MAX_CHANGED_KEYS = 2000;

    // Handshake, in the clear.
    public static final int HELLO = 1;
    public static final int CHALLENGE = 2;
    public static final int AUTH_C = 3;
    public static final int AUTH_S = 4;
    // After the handshake, AEAD-sealed.
    public static final int HEAD = 10;
    public static final int LEDGER_REQ = 11;
    public static final int LEDGER_RESP = 12;
    public static final int DIFF_REQ = 13;
    public static final int DIFF_RESP = 14;
    public static final int GET = 15;
    public static final int RECORD = 16;
    public static final int CHECKPOINT = 17;
    /** Reserved for later milestones (DESIGN 4.6): not sent yet. */
    public static final int PEERS = 18;
    /** Host to members when it quits (DESIGN 4.9): {str successorUuid, str finalEntryJson}. Only sent when there is a successor. */
    public static final int HANDOFF = 19;
    public static final int BRANCH_PUSH = 20;
    public static final int BYE = 21;
    /** Owner policy and mod sync (DESIGN 4.13, 4.14). */
    public static final int POLICY = 22;
    public static final int POLICY_REQ = 23;
    public static final int MEMBERS = 24;
    public static final int MOD_GET = 25;
    public static final int MOD_DATA = 26;
    /** Member to host: how much of the world this copy holds, {i32 have, i32 total} (for "Sam 62%", DESIGN 4.9). */
    public static final int PROGRESS = 27;

    public static final int RECORD_LAST = 1;
    public static final int RECORD_ABSENT = 2;

    public static final int MOD_LAST = 1;
    /** The file is not part of the host's signed mod list (or the host no longer has it). */
    public static final int MOD_ABSENT = 2;
    /** Path policy: mod files are bulk data and this connection may not carry bulk data now. */
    public static final int MOD_REFUSED = 4;

    private Wire() {
    }

    public static byte[] magic() {
        return MAGIC.clone();
    }

    /**
     * Compares the first bytes of a connection with the magic.
     *
     * @return 1 if they are the magic, -1 if one byte already differs, 0 if more bytes are needed
     */
    public static int matchMagic(byte[] first, int length) {
        for (int i = 0; i < Math.min(length, MAGIC.length); i++) {
            if (first[i] != MAGIC[i]) {
                return -1;
            }
        }
        return length >= MAGIC.length ? 1 : 0;
    }

    public static byte[] frame(byte[] payload) {
        if (payload.length > MAX_FRAME) {
            throw new IllegalArgumentException("Frame too large: " + payload.length);
        }
        byte[] framed = new byte[payload.length + 4];
        framed[0] = (byte) (payload.length >>> 24);
        framed[1] = (byte) (payload.length >>> 16);
        framed[2] = (byte) (payload.length >>> 8);
        framed[3] = (byte) payload.length;
        System.arraycopy(payload, 0, framed, 4, payload.length);
        return framed;
    }

    /** Cuts a byte stream back into frames. Not thread-safe. */
    public static final class Decoder {
        private byte[] buffer = new byte[8192];
        private int filled;

        /** @throws IOException if a frame announces a length above {@link #MAX_FRAME} */
        public void feed(byte[] data, int offset, int length, Consumer<byte[]> frames) throws IOException {
            if (filled + length > buffer.length) {
                byte[] grown = new byte[Math.max(buffer.length * 2, filled + length)];
                System.arraycopy(buffer, 0, grown, 0, filled);
                buffer = grown;
            }
            System.arraycopy(data, offset, buffer, filled, length);
            filled += length;
            int position = 0;
            while (filled - position >= 4) {
                int size = ((buffer[position] & 0xFF) << 24) | ((buffer[position + 1] & 0xFF) << 16)
                        | ((buffer[position + 2] & 0xFF) << 8) | (buffer[position + 3] & 0xFF);
                if (size < 0 || size > MAX_FRAME + 64) {
                    throw new IOException("Frame length out of range: " + size);
                }
                if (filled - position - 4 < size) {
                    break;
                }
                byte[] frame = new byte[size];
                System.arraycopy(buffer, position + 4, frame, 0, size);
                position += 4 + size;
                frames.accept(frame);
            }
            if (position > 0) {
                System.arraycopy(buffer, position, buffer, 0, filled - position);
                filled -= position;
            }
        }
    }

    /** Writes one message: the type byte, then fields. */
    public static final class Out {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(bytes);

        public Out(int type) {
            bytes.write(type);
        }

        public Out u8(int value) {
            bytes.write(value);
            return this;
        }

        public Out i32(int value) {
            try {
                out.writeInt(value);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        public Out i64(long value) {
            try {
                out.writeLong(value);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        public Out bytes(byte[] value) {
            return bytes(value, 0, value.length);
        }

        public Out bytes(byte[] value, int offset, int length) {
            i32(length);
            bytes.write(value, offset, length);
            return this;
        }

        public Out str(String value) {
            return bytes(value.getBytes(StandardCharsets.UTF_8));
        }

        public Out strs(List<String> values) {
            i32(values.size());
            values.forEach(this::str);
            return this;
        }

        public Out map(Map<String, String> values) {
            i32(values.size());
            values.forEach((key, value) -> str(key).str(value));
            return this;
        }

        public byte[] toBytes() {
            return bytes.toByteArray();
        }
    }

    /** Reads one message. Every method throws {@link IOException} on a short or malformed message. */
    public static final class In {
        private final DataInputStream in;
        private final int length;
        public final int type;

        public In(byte[] message) throws IOException {
            if (message.length == 0) {
                throw new IOException("Empty message");
            }
            this.type = message[0] & 0xFF;
            this.length = message.length;
            this.in = new DataInputStream(new ByteArrayInputStream(message, 1, message.length - 1));
        }

        public int u8() throws IOException {
            return in.readUnsignedByte();
        }

        public int i32() throws IOException {
            return in.readInt();
        }

        public long i64() throws IOException {
            return in.readLong();
        }

        public byte[] bytes() throws IOException {
            int size = in.readInt();
            if (size < 0 || size > length) {
                throw new IOException("Bad field length " + size);
            }
            byte[] value = new byte[size];
            in.readFully(value);
            return value;
        }

        public String str() throws IOException {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        private int count() throws IOException {
            int count = in.readInt();
            if (count < 0 || count > length) {
                throw new IOException("Bad element count " + count);
            }
            return count;
        }

        public List<String> strs() throws IOException {
            int count = count();
            List<String> values = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                values.add(str());
            }
            return values;
        }

        public Map<String, String> map() throws IOException {
            int count = count();
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String key = str();
                values.put(key, str());
            }
            return values;
        }
    }
}
