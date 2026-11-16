package dropin.fabric;

import dropin.mc.DropIn;
import dropin.tunnel.E4mcClient;
import net.fabricmc.api.ModInitializer;

public class DropInFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        DropIn.init();
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, ignored, ignored2) -> E4mcClient.registerCommands(dispatcher));
    }
}
