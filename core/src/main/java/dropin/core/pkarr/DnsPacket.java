package dropin.core.pkarr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The small part of DNS that pkarr needs here: an answer packet holding one TXT record, and a reader
 * that finds a TXT record by its first label in whatever answer packet a relay returns (it may hold
 * several records and use name compression).
 */
public final class DnsPacket {
    private static final int TYPE_TXT = 16;
    private static final int CLASS_IN = 1;
    private static final int MAX_STRING = 255;

    private DnsPacket() {
    }

    /** One TXT answer for {@code labels} (for example {@code _dropin}, {@code <z-base-32 key>}). */
    public static byte[] encodeTxt(List<String> labels, int ttlSeconds, String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, 0);       // id
        writeU16(out, 0x8400);  // response, authoritative
        writeU16(out, 0);       // questions
        writeU16(out, 1);       // answers
        writeU16(out, 0);       // authority
        writeU16(out, 0);       // additional
        for (String label : labels) {
            byte[] raw = label.getBytes(StandardCharsets.US_ASCII);
            if (raw.length == 0 || raw.length > 63) {
                throw new IllegalArgumentException("label length " + raw.length);
            }
            out.write(raw.length);
            out.writeBytes(raw);
        }
        out.write(0);
        writeU16(out, TYPE_TXT);
        writeU16(out, CLASS_IN);
        writeU16(out, ttlSeconds >>> 16);
        writeU16(out, ttlSeconds & 0xFFFF);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream rdata = new ByteArrayOutputStream();
        int pos = 0;
        do {
            int n = Math.min(MAX_STRING, data.length - pos);
            rdata.write(n);
            rdata.write(data, pos, n);
            pos += n;
        } while (pos < data.length);
        writeU16(out, rdata.size());
        out.writeBytes(rdata.toByteArray());
        return out.toByteArray();
    }

    /**
     * The text of the first TXT answer whose name starts with {@code firstLabel} (case-insensitive),
     * or null if there is none.
     *
     * @throws IllegalArgumentException if the packet is malformed
     */
    public static String findTxt(byte[] packet, String firstLabel) {
        try {
            int questions = u16(packet, 4);
            int answers = u16(packet, 6);
            int pos = 12;
            for (int i = 0; i < questions; i++) {
                pos = skipName(packet, pos) + 4;
            }
            for (int i = 0; i < answers; i++) {
                List<String> name = new ArrayList<>();
                pos = readName(packet, pos, name);
                int type = u16(packet, pos);
                int length = u16(packet, pos + 8);
                int start = pos + 10;
                pos = start + length;
                if (pos > packet.length) {
                    throw new IllegalArgumentException("record runs past the packet");
                }
                if (type == TYPE_TXT && !name.isEmpty() && name.get(0).equalsIgnoreCase(firstLabel)) {
                    ByteArrayOutputStream text = new ByteArrayOutputStream();
                    int p = start;
                    while (p < pos) {
                        int n = packet[p] & 0xFF;
                        if (p + 1 + n > pos) {
                            throw new IllegalArgumentException("string runs past the record");
                        }
                        text.write(packet, p + 1, n);
                        p += 1 + n;
                    }
                    return text.toString(StandardCharsets.UTF_8);
                }
            }
            return null;
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("truncated DNS packet", e);
        }
    }

    private static int readName(byte[] packet, int pos, List<String> labels) {
        int next = -1;
        int jumps = 0;
        while (true) {
            int len = packet[pos] & 0xFF;
            if (len == 0) {
                return next >= 0 ? next : pos + 1;
            }
            if ((len & 0xC0) == 0xC0) {
                if (next < 0) {
                    next = pos + 2;
                }
                if (++jumps > 32) {
                    throw new IllegalArgumentException("compression loop");
                }
                pos = ((len & 0x3F) << 8) | (packet[pos + 1] & 0xFF);
                continue;
            }
            if ((len & 0xC0) != 0) {
                throw new IllegalArgumentException("bad label length");
            }
            labels.add(new String(packet, pos + 1, len, StandardCharsets.US_ASCII));
            pos += 1 + len;
        }
    }

    private static int skipName(byte[] packet, int pos) {
        return readName(packet, pos, new ArrayList<>());
    }

    private static int u16(byte[] packet, int pos) {
        return ((packet[pos] & 0xFF) << 8) | (packet[pos + 1] & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
