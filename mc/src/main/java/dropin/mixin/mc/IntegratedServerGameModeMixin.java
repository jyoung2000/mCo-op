package dropin.mixin.mc;

import dropin.mc.compat.ForceGameModeAccess;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DESIGN 4.4 "Game modes" (1.21.1 port): a published integrated server forces every joining player into its
 * game type. With forcing switched off (as 26.x's {@code setForceGameMode(false)} does) it forces nothing and
 * each player keeps the mode saved in the world.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerGameModeMixin implements ForceGameModeAccess {
    @Unique
    private boolean dropin$force = true;

    @Override
    public void dropin$setForceGameMode(boolean force) {
        this.dropin$force = force;
    }

    @Inject(method = "getForcedGameType", at = @At("HEAD"), cancellable = true)
    private void dropin$noForcedGameType(CallbackInfoReturnable<GameType> cir) {
        if (!this.dropin$force) {
            cir.setReturnValue(null);
        }
    }
}
