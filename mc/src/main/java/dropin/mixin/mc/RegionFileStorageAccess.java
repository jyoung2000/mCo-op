package dropin.mixin.mc;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.nio.file.Path;

/** 1.21.1 port: the region storage constructor and {@code write} are package-private / protected here (public in 26.x). */
@Mixin(RegionFileStorage.class)
public interface RegionFileStorageAccess {
    @Invoker("<init>")
    static RegionFileStorage dropin$create(RegionStorageInfo info, Path folder, boolean sync) {
        throw new AssertionError();
    }

    @Invoker("write")
    void dropin$write(ChunkPos pos, CompoundTag tag) throws IOException;
}
