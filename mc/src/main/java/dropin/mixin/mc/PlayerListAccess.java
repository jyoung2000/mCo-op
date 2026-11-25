package dropin.mixin.mc;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 1.12.2 port: PlayerList's private {@code sendPlayerPermissionLevel}, for PlayerListPermissionsMixin. An invoker, not
 * a {@code @Shadow}: CleanroomGradle's reobfJar does not rename shadow members and Cleanroom remaps none at run time,
 * while invoker targets go through the refmap.
 */
@Mixin(PlayerList.class)
public interface PlayerListAccess {
    @Invoker("sendPlayerPermissionLevel")
    void dropin$sendPlayerPermissionLevel(EntityPlayerMP player, int level);
}
