package dropin.mixin.mc;

import dropin.mc.host.ChunkStores;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * DESIGN 4.5 dirty tracking: region, entities and POI each write through one {@code IOWorker}, and
 * {@code store(ChunkPos, CompoundTag)} calls the Supplier overload, so this is the one choke point.
 */
@Mixin(IOWorker.class)
public abstract class IOWorkerMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void dropin$created(RegionStorageInfo info, Path dir, boolean sync, CallbackInfo ci) {
        ChunkStores.register((IOWorker) (Object) this, info, dir);
    }

    // After the write was queued, so a checkpoint that reads the chunk back always sees it.
    @Inject(method = "store(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("RETURN"))
    private void dropin$stored(ChunkPos pos, Supplier<CompoundTag> supplier, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        ChunkStores.stored((IOWorker) (Object) this, pos);
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void dropin$closed(CallbackInfo ci) {
        ChunkStores.unregister((IOWorker) (Object) this);
    }
}
