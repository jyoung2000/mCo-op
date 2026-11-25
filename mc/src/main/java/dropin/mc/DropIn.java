package dropin.mc;

import dropin.core.dev.DevSwitches;
import dropin.core.dev.SelfTestSpec;
import dropin.tunnel.Agnos;
import dropin.tunnel.E4mcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common entry point, called once by each loader's entry class. */
public final class DropIn {
    public static final Logger LOGGER = LoggerFactory.getLogger("dropin");

    private static final DevSwitches SWITCHES = DevSwitches.fromSystemProperties(Agnos.isDevelopmentEnvironment());
    private static volatile Runnable clientTickHook;

    private DropIn() {}

    /** Development switches. Every one of them reads as absent outside a development environment. */
    public static DevSwitches switches() {
        return SWITCHES;
    }

    public static void init() {
        E4mcClient.init();
        // DESIGN 4.6: world sync shares the game's listener; the first bytes decide which protocol it is.
        E4mcClient.childHandlerWrapper = dropin.mc.net.ProtocolSniffer::wrap;
        if (Agnos.isClient()) {
            dropin.mc.menu.ui.DScreen.hookFileDrops();
            SWITCHES.selfTest().ifPresent(DropIn::startSelfTest);
        }
    }

    /** 1.12.2: commands are registered at {@code FMLServerStartingEvent}, not by a Brigadier mixin (DropInCommands). */
    public static void registerServerCommands(net.minecraft.server.MinecraftServer server,
                                              java.util.function.Consumer<net.minecraft.command.ICommand> registrar) {
        dropin.mc.commands.DropInCommands.register(server, registrar);
    }

    /**
     * The self-test driver is in a development-only source set that the mod jar does not contain, so it is
     * looked up by name; this is reached only when {@link DevSwitches#selfTest()} is present.
     */
    private static void startSelfTest(SelfTestSpec spec) {
        try {
            Class.forName("dropin.mc.selftest.SelfTestBootstrap")
                    .getMethod("start", SelfTestSpec.class)
                    .invoke(null, spec);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("dropin.selftest was requested but the self-test driver is not available", e);
        }
    }

    /** Development only: a callback run at the end of every client tick. */
    public static void setClientTickHook(Runnable hook) {
        if (!SWITCHES.devEnvironment()) {
            throw new IllegalStateException("Client tick hook is development-only");
        }
        clientTickHook = hook;
    }

    /** Called from the development-only tick mixin. */
    public static void onClientTick() {
        Runnable hook = clientTickHook;
        if (hook != null) {
            hook.run();
        }
    }
}
