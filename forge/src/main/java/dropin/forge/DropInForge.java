package dropin.forge;

import dropin.mc.DropIn;
import dropin.tunnel.E4mcClient;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(E4mcClient.MOD_ID)
public class DropInForge {
    public DropInForge() {
        DropIn.init();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommandEvent(RegisterCommandsEvent event) {
        E4mcClient.registerCommands(event.getDispatcher());
    }
}
