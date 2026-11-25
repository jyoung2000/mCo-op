package dropin.mixin.mc;

import dropin.mc.host.ChunkStores;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

/**
 * DESIGN 4.5 dirty tracking. 1.12.2 port: each dimension keeps its chunks in one {@code AnvilChunkLoader}
 * (only the "region" kind exists), and {@code saveChunk} hands every chunk to {@code addChunkToPending}, which
 * queues it for the file IO thread: that is the one choke point (26.x: {@code IOWorker.store}).
 */
@Mixin(AnvilChunkLoader.class)
public abstract class AnvilChunkLoaderMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void dropin$created(File location, DataFixer fixer, CallbackInfo ci) {
        ChunkStores.register((AnvilChunkLoader) (Object) this, location);
    }

    // After the chunk is in the pending map, so a checkpoint that reads it back always sees it.
    @Inject(method = "addChunkToPending", at = @At("RETURN"))
    private void dropin$stored(ChunkPos pos, NBTTagCompound tag, CallbackInfo ci) {
        ChunkStores.stored((AnvilChunkLoader) (Object) this, pos);
    }
}
