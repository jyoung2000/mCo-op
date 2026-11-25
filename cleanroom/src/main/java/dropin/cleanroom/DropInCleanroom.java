package dropin.cleanroom;

import dropin.mc.DropIn;
import dropin.tunnel.E4mcClient;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/**
 * Cleanroom (Forge 1.12.2) entry point. The version comes from {@code mcmod.info}. {@code DropIn.init()} runs at
 * construction, as the 26.x loader entry classes did.
 */
@Mod(modid = E4mcClient.MOD_ID, name = "MCo-op", useMetadata = true, acceptedMinecraftVersions = "[1.12.2]")
public class DropInCleanroom {
    public DropInCleanroom() {
        // netty-quic must sit in the app class loader before the tunnel first touches it (see QuicBootstrap).
        QuicBootstrap.install();
        DropIn.init();
    }

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        E4mcClient.registerCommands(event.getServer(), event::registerServerCommand);
        DropIn.registerServerCommands(event.getServer(), event::registerServerCommand);
    }
}
