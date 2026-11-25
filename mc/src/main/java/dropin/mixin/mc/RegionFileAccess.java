package dropin.mixin.mc;

import net.minecraft.world.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 1.12.2 port: {@code RegionFile} has no way to remove a chunk (26.x: {@code write(pos, null)}). Setting its
 * offset to 0 is what makes the game treat it as never saved. Only used on region files of a world that is
 * not open on this machine (dropin.mc.member.RecordApplier).
 */
@Mixin(RegionFile.class)
public interface RegionFileAccess {
    /** {@code private void setOffset(int x, int z, int offset) throws IOException}; x and z are 0 to 31. */
    @Invoker("setOffset")
    void dropin$setOffset(int x, int z, int offset) throws IOException;

    /**
     * The open file (null if the constructor failed to open it: it only prints that error). Used to force written
     * chunks to disk before a member's copy counts a checkpoint as applied (26.x {@code RegionFile.flush}).
     */
    @Accessor("dataFile")
    RandomAccessFile dropin$dataFile();
}
