package dropin.mixin.mc;

import dropin.mc.coop.CoopWorlds;
import dropin.mc.menu.CoopWorldsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DESIGN 4.4: a "Co-op Worlds" button beside Singleplayer, laid out relative to the vanilla button
 * ({@code createNormalMenuOptions(int topPos, int spacing)} puts Singleplayer at {@code width / 2 - 100, topPos},
 * 200 wide). It sits in the free space to the right, so it cannot overlap a vanilla button; on a window too
 * narrow for the label it shrinks to a small square with a tooltip.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "createNormalMenuOptions", at = @At("HEAD"))
    private void dropin$coopWorldsButton(int topPos, int spacing, CallbackInfoReturnable<Integer> cir) {
        if (!CoopWorlds.titleButton()) {
            return;
        }
        int x = this.width / 2 + 104;
        int room = this.width - x - 4;
        int wanted = this.font.width(CoopWorldsScreen.TITLE) + 12;
        Button.Builder builder = Button.builder(room >= wanted ? CoopWorldsScreen.TITLE : Component.literal("Co"),
                button -> this.minecraft.gui.setScreen(new CoopWorldsScreen(this)));
        if (room >= wanted) {
            builder.bounds(x, topPos, Math.min(room, Math.max(wanted, 98)), 20);
        } else {
            builder.bounds(x, topPos, 20, 20).tooltip(Tooltip.create(CoopWorldsScreen.TITLE));
        }
        this.addRenderableWidget(builder.build());
    }
}
