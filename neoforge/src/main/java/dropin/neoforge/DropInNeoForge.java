package dropin.neoforge;

import dropin.mc.DropIn;
import dropin.tunnel.E4mcClient;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(E4mcClient.MOD_ID)
public class DropInNeoForge {
    public DropInNeoForge() {
        DropIn.init();
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommandEvent(RegisterCommandsEvent event) {
        E4mcClient.registerCommands(event.getDispatcher());
    }
}
