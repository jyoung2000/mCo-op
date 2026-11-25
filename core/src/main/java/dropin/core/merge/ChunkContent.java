package dropin.core.merge;

import dropin.core.manifest.Manifest;
import dropin.core.manifest.RecordKey;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The content hash of a record, used only to decide a merge (DESIGN 4.10).
 * <p>
 * The game writes a chunk again whenever it was loaded, with a new {@code LastUpdate} and, if a player was
 * near, a new {@code InhabitedTime}; nothing a player would call a change. By record hash such a chunk is
 * "changed" on every copy that was played, so every chunk two players both walked through would be a conflict.
 * The content hash of a region chunk record leaves those two fields out. For every other record it is the
 * record hash.
 */
public final class ChunkContent {
    /** Root-level fields of a chunk that change without the chunk's content changing. */
    private static final Set<String> VOLATILE = Set.of("LastUpdate", "InhabitedTime");

    private ChunkContent() {
    }

    /** True for the keys whose content hash differs from the record hash: the region record of a chunk. */
    public static boolean applies(String key) {
        return RecordKey.isChunk(key) && RecordKey.parseChunk(key).kind().equals("region");
    }

    /**
     * @param bytes the record (for a chunk: its uncompressed NBT), null if there is none
     * @return "" for no record; the record hash if the key is not a region chunk or the bytes are not the NBT
     *         of a chunk
     */
    public static String hash(String key, byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        if (!applies(key)) {
            return Manifest.hash(bytes);
        }
        try {
            return Manifest.hash(stripped(bytes));
        } catch (RuntimeException e) {
            return Manifest.hash(bytes);
        }
    }

    /**
     * The NBT of a chunk without its volatile fields. Throws a RuntimeException if it is not such NBT. 1.18 and
     * later keep the fields at the root; 1.17 and older (1.12.2) keep them in the root's {@code Level} compound.
     */
    private static byte[] stripped(byte[] nbt) {
        if (nbt.length < 4 || nbt[0] != 10) {
            throw new IllegalArgumentException("not a compound");
        }
        int at = 3 + u16(nbt, 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream(nbt.length);
        out.write(nbt, 0, at);
        int end = strip(nbt, at, out, true);
        if (end != nbt.length) {
            throw new IllegalArgumentException("bytes after the root compound");
        }
        return out.toByteArray();
    }

    /**
     * Copies the entries of the compound payload at {@code at} to {@code out}, without the volatile fields, and
     * its end tag; at the root it does the same inside a {@code Level} compound. Returns the offset after the end tag.
     */
    private static int strip(byte[] nbt, int at, ByteArrayOutputStream out, boolean root) {
        while (true) {
            if (at >= nbt.length) {
                throw new IllegalArgumentException("truncated");
            }
            int type = nbt[at];
            if (type == 0) {
                out.write(0);
                return at + 1;
            }
            int nameLength = u16(nbt, at + 1);
            int payload = at + 3 + nameLength;
            String name = new String(nbt, at + 3, nameLength, StandardCharsets.UTF_8);
            if (root && type == 10 && name.equals("Level")) {
                out.write(nbt, at, payload - at);
                at = strip(nbt, payload, out, false);
                continue;
            }
            int end = skip(nbt, payload, type, 0);
            if (!VOLATILE.contains(name)) {
                out.write(nbt, at, end - at);
            }
            at = end;
        }
    }

    /** @return the offset after the payload of a tag of {@code type} that starts at {@code at} */
    private static int skip(byte[] nbt, int at, int type, int depth) {
        if (depth > 512) {
            throw new IllegalArgumentException("nested too deep");
        }
        switch (type) {
            case 1:
                return at + 1;
            case 2:
                return at + 2;
            case 3, 5:
                return at + 4;
            case 4, 6:
                return at + 8;
            case 7:
                return checked(nbt, at + 4 + (long) i32(nbt, at));
            case 8:
                return checked(nbt, at + 2 + (long) u16(nbt, at));
            case 9: {
                int element = nbt[at];
                int count = i32(nbt, at + 1);
                int next = at + 5;
                for (int i = 0; i < count; i++) {
                    next = skip(nbt, next, element, depth + 1);
                }
                return next;
            }
            case 10: {
                int next = at;
                while (nbt[next] != 0) {
                    int inner = nbt[next];
                    next = skip(nbt, next + 3 + u16(nbt, next + 1), inner, depth + 1);
                }
                return next + 1;
            }
            case 11:
                return checked(nbt, at + 4 + 4L * i32(nbt, at));
            case 12:
                return checked(nbt, at + 4 + 8L * i32(nbt, at));
            default:
                throw new IllegalArgumentException("unknown tag " + type);
        }
    }

    private static int checked(byte[] nbt, long end) {
        if (end < 0 || end > nbt.length) {
            throw new IllegalArgumentException("truncated");
        }
        return (int) end;
    }

    private static int u16(byte[] nbt, int at) {
        return ((nbt[at] & 0xFF) << 8) | (nbt[at + 1] & 0xFF);
    }

    private static int i32(byte[] nbt, int at) {
        int value = ((nbt[at] & 0xFF) << 24) | ((nbt[at + 1] & 0xFF) << 16) | ((nbt[at + 2] & 0xFF) << 8) | (nbt[at + 3] & 0xFF);
        if (value < 0) {
            throw new IllegalArgumentException("negative length");
        }
        return value;
    }
}
