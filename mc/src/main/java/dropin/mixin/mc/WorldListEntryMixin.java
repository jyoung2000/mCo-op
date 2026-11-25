package dropin.mixin.mc;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dropin.mc.menu.CoopActions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiListWorldSelectionEntry;
import net.minecraft.client.resources.I18n;
import net.minecraft.world.storage.WorldSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * DESIGN 4.4: co-op worlds stay in the Singleplayer list, with a badge.
 * <p>1.12.2 port: {@code GuiListWorldSelectionEntry.drawEntry} reads {@code worldSummary.getDisplayName()} on every
 * frame; the badge is appended there, and the shown name is worked out once per entry.
 */
@Mixin(GuiListWorldSelectionEntry.class)
public class WorldListEntryMixin {
    @Unique
    private String dropin$shownName;

    @WrapOperation(method = "drawEntry", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/storage/WorldSummary;getDisplayName()Ljava/lang/String;"))
    private String dropin$coopBadge(WorldSummary summary, Operation<String> original) {
        if (dropin$shownName == null) {
            String name = original.call(summary);
            // An empty name stays empty, so the game still shows its "World N" fallback.
            dropin$shownName = name != null && !name.isEmpty() && CoopActions.isCoopLevel(Minecraft.getMinecraft(), summary.getFileName())
                    ? name + " " + I18n.format("text.dropin_coop.badge") : name;
        }
        return dropin$shownName;
    }
}
