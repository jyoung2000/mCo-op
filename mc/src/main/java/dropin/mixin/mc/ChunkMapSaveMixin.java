package dropin.mixin.mc;

import dropin.mc.host.HostSync;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DESIGN 4.5 (a checkpoint holds what the game saved), 1.21.1 only. 1.21.1's {@code saveAllChunks(false)} skips a
 * chunk saved in the last 10 s ({@code chunkSaveCooldowns}), so a checkpoint could miss the newest blocks of a chunk
 * for up to 10 s. 26.x clears its {@code nextChunkSaveTime} at the start of the same call; this does that for the
 * worlds DropIn hosts.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapSaveMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    @Final
    private Long2LongMap chunkSaveCooldowns;

    @Inject(method = "saveAllChunks", at = @At("HEAD"))
    private void dropin$noCooldown(boolean flush, CallbackInfo ci) {
        if (!flush && HostSync.hosts(this.level.getServer())) {
            this.chunkSaveCooldowns.clear();
        }
    }
}
