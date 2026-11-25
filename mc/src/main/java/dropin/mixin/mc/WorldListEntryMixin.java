package dropin.mixin.mc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dropin.mc.menu.CoopActions;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DESIGN 4.4: co-op worlds stay in the Singleplayer list, with a badge. The entry builds its name widget
 * from {@code summary.getLevelName()} in its constructor; the badge is appended there.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.WorldSelectionList$WorldListEntry")
public class WorldListEntryMixin {
    /** The shown name, worked out once: 1.21.1 reads the name on every frame in render (26.x: in the constructor). */
    @org.spongepowered.asm.mixin.Unique
    private String dropin$shownName;

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/LevelSummary;getLevelName()Ljava/lang/String;"))
    private String dropin$coopBadge(LevelSummary summary, Operation<String> original) {
        if (dropin$shownName == null) {
            String name = original.call(summary);
            dropin$shownName = CoopActions.isCoopLevel(Minecraft.getInstance(), summary.getLevelId())
                    ? name + " " + Component.translatable("text.dropin_coop.badge").getString() : name;
        }
        return dropin$shownName;
    }
}
