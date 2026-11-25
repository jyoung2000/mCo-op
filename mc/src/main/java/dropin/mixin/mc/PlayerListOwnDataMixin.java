package dropin.mixin.mc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dropin.mc.coop.CoopWorlds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.nbt.CompoundTag;

/**
 * DESIGN 4.8. Vanilla loads the host of an integrated server from the player data of the world's
 * <em>last</em> singleplayer owner (1.21.1: the {@code Player} tag kept in {@code level.dat}; 26.x: the file
 * named by {@code singleplayer_uuid}). In a co-op world the last owner may be another member, so the host
 * must load their own data like everyone else.
 */
@Mixin(PlayerList.class)
public class PlayerListOwnDataMixin {
    @Shadow
    @Final
    private MinecraftServer server;

    @WrapOperation(method = "load", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/WorldData;getLoadedPlayerTag()Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag dropin$ownPlayerData(WorldData data, Operation<CompoundTag> original) {
        return CoopWorlds.isCoopServer(this.server) ? null : original.call(data);
    }
}
