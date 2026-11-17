package dropin.mixin.mc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;
import dropin.mc.coop.CoopWorlds;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.WorldData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.UUID;

/**
 * DESIGN 4.8, second read site of {@code singleplayer_uuid}: the chunks loaded first are those around the
 * last singleplayer owner. In a co-op world that is made the local player, who is the one about to spawn.
 */
@Mixin(IntegratedServer.class)
public class IntegratedServerFocusMixin {
    @WrapOperation(method = "selectLevelLoadFocusPos", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/WorldData;getSinglePlayerUUID()Ljava/util/UUID;"))
    private UUID dropin$focusOnLocalPlayer(WorldData data, Operation<UUID> original) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        GameProfile local = server.getSingleplayerProfile();
        return local != null && CoopWorlds.isCoopServer(server) ? local.id() : original.call(data);
    }
}
