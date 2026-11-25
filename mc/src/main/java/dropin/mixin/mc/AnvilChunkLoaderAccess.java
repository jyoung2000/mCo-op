package dropin.mixin.mc;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Set;

/**
 * 1.12.2 port: the pending writes of a chunk loader, so a chunk of the open world is read the way the loader
 * itself reads it ({@code loadChunk__Async}: the pending map first, then the region file). DESIGN 4.5.
 */
@Mixin(AnvilChunkLoader.class)
public interface AnvilChunkLoaderAccess {
    @Accessor("chunksToSave")
    Map<ChunkPos, NBTTagCompound> dropin$chunksToSave();

    /** Chunks the file IO thread has taken out of the pending map and is writing right now. */
    @Accessor("chunksBeingSaved")
    Set<ChunkPos> dropin$chunksBeingSaved();
}
