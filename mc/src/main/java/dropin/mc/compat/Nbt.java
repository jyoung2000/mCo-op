package dropin.mc.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.stream.Stream;

/**
 * 1.21.1 port: the "value or default" readers that 26.x has on {@code CompoundTag} and {@code ListTag}
 * ({@code getIntOr}, {@code getListOrEmpty}, {@code compoundStream} and so on), with the same results.
 */
public final class Nbt {
    private Nbt() {
    }

    public static ListTag listOrEmpty(CompoundTag tag, String key) {
        return tag.get(key) instanceof ListTag list ? list : new ListTag();
    }

    public static int intOr(CompoundTag tag, String key, int fallback) {
        return tag.get(key) instanceof NumericTag n ? n.getAsInt() : fallback;
    }

    public static float floatOr(CompoundTag tag, String key, float fallback) {
        return tag.get(key) instanceof NumericTag n ? n.getAsFloat() : fallback;
    }

    public static byte byteOr(CompoundTag tag, String key, byte fallback) {
        return tag.get(key) instanceof NumericTag n ? n.getAsByte() : fallback;
    }

    public static String stringOr(CompoundTag tag, String key, String fallback) {
        return tag.get(key) instanceof StringTag s ? s.getAsString() : fallback;
    }

    public static String stringOr(ListTag list, int index, String fallback) {
        return index >= 0 && index < list.size() && list.get(index) instanceof StringTag s ? s.getAsString() : fallback;
    }

    public static double doubleOr(ListTag list, int index, double fallback) {
        return index >= 0 && index < list.size() && list.get(index) instanceof NumericTag n ? n.getAsDouble() : fallback;
    }

    public static Stream<CompoundTag> compounds(ListTag list) {
        return list.stream().filter(t -> t instanceof CompoundTag).map(t -> (CompoundTag) t);
    }

    static boolean isTag(Object o) {
        return o instanceof Tag;
    }
}
