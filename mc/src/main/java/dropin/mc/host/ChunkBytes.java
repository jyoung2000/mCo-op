package dropin.mc.host;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.nbt.NBTTagString;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The bytes of a chunk record (DESIGN 4.7): the chunk's NBT, uncompressed, in the ordinary binary NBT
 * format, with the keys of every compound written in sorted order. The game keeps compounds in hash
 * maps, so its own writer gives the same data different bytes on different machines; sorted keys make
 * the hash of a chunk the same wherever it is computed. Any NBT reader reads the result.
 *
 * <p>1.12.2 port: the chunk NBT is what {@code AnvilChunkLoader.saveChunk} stores (a root compound with
 * {@code Level} and {@code DataVersion}). {@code NBTBase.write} is package-private here, so the payloads of
 * the plain tags are written by this class, byte for byte as the game writes them.
 */
public final class ChunkBytes {
    private static final byte TAG_END = 0;
    private static final byte TAG_COMPOUND = 10;

    private ChunkBytes() {
    }

    public static byte[] canonical(NBTTagCompound root) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("");
            payload(root, out);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static NBTTagCompound parse(byte[] bytes) throws IOException {
        // CompressedStreamTools.read(DataInputStream) reads uncompressed NBT with no size limit.
        return CompressedStreamTools.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    private static void payload(NBTBase tag, DataOutput out) throws IOException {
        if (tag instanceof NBTTagCompound compound) {
            List<String> keys = new ArrayList<>(compound.getKeySet());
            Collections.sort(keys);
            for (String key : keys) {
                named(key, compound.getTag(key), out);
            }
            out.writeByte(TAG_END);
        } else if (tag instanceof NBTTagList list) {
            // Mirrors NBTTagList.write of 1.12.2: lists hold one element type, the type of the first element
            // (0 for an empty list), then the count, then each element's payload.
            int count = list.tagCount();
            out.writeByte(count == 0 ? TAG_END : list.get(0).getId());
            out.writeInt(count);
            for (int i = 0; i < count; i++) {
                payload(list.get(i), out);
            }
        } else {
            plain(tag, out);
        }
    }

    /** The payload of a tag that holds no other tags, as its own {@code write} method writes it. */
    private static void plain(NBTBase tag, DataOutput out) throws IOException {
        switch (tag.getId()) {
            case 1 -> out.writeByte(((NBTPrimitive) tag).getByte());
            case 2 -> out.writeShort(((NBTPrimitive) tag).getShort());
            case 3 -> out.writeInt(((NBTPrimitive) tag).getInt());
            case 4 -> out.writeLong(((NBTPrimitive) tag).getLong());
            case 5 -> out.writeFloat(((NBTPrimitive) tag).getFloat());
            case 6 -> out.writeDouble(((NBTPrimitive) tag).getDouble());
            case 7 -> {
                byte[] data = ((NBTTagByteArray) tag).getByteArray();
                out.writeInt(data.length);
                out.write(data);
            }
            case 8 -> out.writeUTF(((NBTTagString) tag).getString());
            case 11 -> {
                int[] data = ((NBTTagIntArray) tag).getIntArray();
                out.writeInt(data.length);
                for (int value : data) {
                    out.writeInt(value);
                }
            }
            case 12 -> {
                long[] data = ((NBTTagLongArray) tag).getLongArray();
                out.writeInt(data.length);
                for (long value : data) {
                    out.writeLong(value);
                }
            }
            default -> out.write(viaGame(tag));
        }
    }

    /**
     * Any other tag (none exists in vanilla 1.12.2): the game writes it inside a compound {"": tag}, and the payload
     * is cut out of those bytes (1 type byte and an empty name for the root, the same for the entry, a closing 0).
     */
    private static byte[] viaGame(NBTBase tag) throws IOException {
        NBTTagCompound wrapper = new NBTTagCompound();
        wrapper.setTag("", tag);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.write(wrapper, new DataOutputStream(bytes));
        byte[] all = bytes.toByteArray();
        int from = 3 + 3;
        int to = all.length - 1;
        byte[] payload = new byte[to - from];
        System.arraycopy(all, from, payload, 0, payload.length);
        return payload;
    }

    private static void named(String name, NBTBase tag, DataOutput out) throws IOException {
        out.writeByte(tag.getId());
        if (tag.getId() != TAG_END) {
            out.writeUTF(name);
            payload(tag, out);
        }
    }
}
