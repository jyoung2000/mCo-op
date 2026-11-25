package dropin.mc.host;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

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
 */
public final class ChunkBytes {
    private ChunkBytes() {
    }

    public static byte[] canonical(CompoundTag root) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 * 1024);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(Tag.TAG_COMPOUND);
            out.writeUTF("");
            payload(root, out);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static CompoundTag parse(byte[] bytes) throws IOException {
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)), NbtAccounter.UNLIMITED);
    }

    private static void payload(Tag tag, DataOutput out) throws IOException {
        if (tag instanceof CompoundTag compound) {
            List<String> keys = new ArrayList<>(compound.getAllKeys());
            Collections.sort(keys);
            for (String key : keys) {
                named(key, compound.get(key), out);
            }
            out.writeByte(Tag.TAG_END);
        } else if (tag instanceof ListTag list) {
            // Mirrors ListTag.write: one element type, or compounds when the elements are mixed, with
            // every non-compound element (and every compound that looks like a wrapper) wrapped as {"": element}.
            byte type = 0;
            for (Tag element : list) {
                if (type == 0) {
                    type = element.getId();
                } else if (type != element.getId()) {
                    type = Tag.TAG_COMPOUND;
                    break;
                }
            }
            out.writeByte(type);
            out.writeInt(list.size());
            for (Tag element : list) {
                boolean plain = type != Tag.TAG_COMPOUND
                        || (element instanceof CompoundTag compound && !(compound.size() == 1 && compound.contains("")));
                if (plain) {
                    payload(element, out);
                } else {
                    named("", element, out);
                    out.writeByte(Tag.TAG_END);
                }
            }
        } else {
            tag.write(out);
        }
    }

    private static void named(String name, Tag tag, DataOutput out) throws IOException {
        out.writeByte(tag.getId());
        if (tag.getId() != Tag.TAG_END) {
            out.writeUTF(name);
            payload(tag, out);
        }
    }
}
