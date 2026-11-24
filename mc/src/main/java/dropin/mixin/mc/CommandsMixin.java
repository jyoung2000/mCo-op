package dropin.mixin.mc;

import com.mojang.brigadier.CommandDispatcher;
import dropin.mc.savepoint.SavePointCommands;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DESIGN 4.16: registers {@code /dropin savepoint} with the game's commands. Client only: the host is a client. */
@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Shadow
    @Final
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void dropin$savePoints(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
        SavePointCommands.register(this.dispatcher);
        dropin.mc.coop.JoinCodeCommands.register(this.dispatcher);
    }
}
