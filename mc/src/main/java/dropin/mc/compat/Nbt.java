package dropin.mc.compat;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * 1.12.2 port: the "value or default" readers that 26.x has on {@code CompoundTag} and {@code ListTag}
 * ({@code getIntOr}, {@code getListOrEmpty}, {@code compoundStream} and so on), with the same results, over
 * 1.12.2's {@code NBTTagCompound} / {@code NBTTagList}.
 */
public final class Nbt {
    private Nbt() {
    }

    public static NBTTagList listOrEmpty(NBTTagCompound tag, String key) {
        return tag.getTag(key) instanceof NBTTagList list ? list : new NBTTagList();
    }

    public static NBTTagCompound compoundOrEmpty(NBTTagCompound tag, String key) {
        return tag.getTag(key) instanceof NBTTagCompound c ? c : new NBTTagCompound();
    }

    public static int intOr(NBTTagCompound tag, String key, int fallback) {
        return tag.getTag(key) instanceof NBTPrimitive n ? n.getInt() : fallback;
    }

    public static long longOr(NBTTagCompound tag, String key, long fallback) {
        return tag.getTag(key) instanceof NBTPrimitive n ? n.getLong() : fallback;
    }

    public static float floatOr(NBTTagCompound tag, String key, float fallback) {
        return tag.getTag(key) instanceof NBTPrimitive n ? n.getFloat() : fallback;
    }

    public static byte byteOr(NBTTagCompound tag, String key, byte fallback) {
        return tag.getTag(key) instanceof NBTPrimitive n ? n.getByte() : fallback;
    }

    public static String stringOr(NBTTagCompound tag, String key, String fallback) {
        return tag.getTag(key) instanceof NBTTagString s ? s.getString() : fallback;
    }

    public static String stringOr(NBTTagList list, int index, String fallback) {
        return index >= 0 && index < list.tagCount() && list.get(index) instanceof NBTTagString s ? s.getString() : fallback;
    }

    public static double doubleOr(NBTTagList list, int index, double fallback) {
        return index >= 0 && index < list.tagCount() && list.get(index) instanceof NBTPrimitive n ? n.getDouble() : fallback;
    }

    public static Stream<NBTBase> stream(NBTTagList list) {
        return StreamSupport.stream(list.spliterator(), false);
    }

    public static Stream<NBTTagCompound> compounds(NBTTagList list) {
        return stream(list).filter(t -> t instanceof NBTTagCompound).map(t -> (NBTTagCompound) t);
    }
}
