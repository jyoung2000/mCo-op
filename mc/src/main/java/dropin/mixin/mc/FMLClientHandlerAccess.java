package dropin.mixin.mc;

import com.google.gson.JsonObject;
import net.minecraft.network.ServerStatusResponse;
import net.minecraftforge.fml.client.FMLClientHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * 1.12.2 port: Forge's status-response deserializer hands every reply's "modinfo" to
 * {@code FMLClientHandler.captureAdditionalData}, which puts it in {@code extraServerListData}, a map only
 * {@code setupServerList()} creates (GuiMultiplayer calls it). A status ping sent before the server list was ever opened
 * failed with an NPE while decoding (StatusPing, the drop-in self-tests). A Forge class: nothing to remap.
 */
@Mixin(value = FMLClientHandler.class, remap = false)
public interface FMLClientHandlerAccess {
    @Accessor("extraServerListData")
    Map<ServerStatusResponse, JsonObject> dropin$extraServerListData();
}
