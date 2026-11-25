package dropin.mixin.mc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dropin.mc.coop.CoopWorlds;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.world.storage.WorldInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DESIGN 4.8. Vanilla loads the host of an integrated server from the player data of the world's
 * <em>last</em> singleplayer owner (1.12.2 and 1.21.1: the {@code Player} tag kept in {@code level.dat}; 26.x: the
 * file named by {@code singleplayer_uuid}). In a co-op world the last owner may be another member, so the host
 * must load their own data like everyone else.
 * <p>
 * 1.12.2 port: {@code PlayerList.readPlayerDataFromFile(EntityPlayerMP)} uses the level.dat tag
 * ({@code WorldInfo.getPlayerNBTTagCompound()}) when the player's name is the server owner's; without it, it reads
 * {@code playerdata/<uuid>.dat} through the save handler. The host's own file is written on every save
 * ({@code IntegratedPlayerList.writePlayerData} calls the vanilla writer as well).
 */
@Mixin(PlayerList.class)
public class PlayerListOwnDataMixin {

    // Forge's getPlayerNBT (the dimension a joining player is put in, NetworkDispatcher) makes the same choice.
    @WrapOperation(method = {"readPlayerDataFromFile", "getPlayerNBT"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/storage/WorldInfo;getPlayerNBTTagCompound()Lnet/minecraft/nbt/NBTTagCompound;"))
    private NBTTagCompound dropin$ownPlayerData(WorldInfo info, Operation<NBTTagCompound> original) {
        return CoopWorlds.isCoopServer(((PlayerList) (Object) this).getServerInstance()) ? null : original.call(info);
    }
}
