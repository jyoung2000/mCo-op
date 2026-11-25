package dropin.mc.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import com.mojang.realmsclient.RealmsMainScreen;

/**
 * 1.21.1 port: what Minecraft 26.x does in one place and this version does elsewhere. The 26.x method
 * {@code Minecraft.disconnectFromWorld(Component)} is the pause menu's {@code PauseScreen.onDisconnect()} here;
 * this is that body, with DropIn's hooks of DESIGN 4.9 in front as the 26.x mixin had them.
 */
public final class Compat {
    /** 26.x {@code ClientLevel.DEFAULT_QUIT_MESSAGE}; the text 1.21.1's {@code ClientLevel.disconnect()} uses. */
    public static final Component DEFAULT_QUIT_MESSAGE = Component.translatable("multiplayer.status.quitting");

    private Compat() {
    }

    /** The pause menu's "Save and Quit" (or "Disconnect"), with the handoff and crash hooks first. */
    public static void disconnectFromWorld(Minecraft mc, Component message) {
        // DESIGN 4.9 (crash): a member that leaves by itself has not lost its host.
        dropin.mc.handoff.CrashFailover.leaving(mc);
        if (dropin.mc.handoff.HostHandoff.intercept(mc, message)) {
            return;
        }
        boolean localServer = mc.isLocalServer();
        ServerData currentServer = mc.getCurrentServer();
        if (mc.level != null) {
            mc.getConnection().getConnection().disconnect(message);
        }
        if (localServer) {
            mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        } else {
            mc.disconnect();
        }
        TitleScreen titleScreen = new TitleScreen();
        if (localServer) {
            mc.setScreen(titleScreen);
        } else if (currentServer != null && currentServer.isRealm()) {
            mc.setScreen(new RealmsMainScreen(titleScreen));
        } else {
            mc.setScreen(new JoinMultiplayerScreen(titleScreen));
        }
    }

    /** 26.x {@code ServerLevel.getEntityInAnyDimension(UUID)}: the entity in any of the server's levels, or null. */
    public static net.minecraft.world.entity.Entity entityInAnyDimension(net.minecraft.server.level.ServerLevel level, java.util.UUID id) {
        for (net.minecraft.server.level.ServerLevel each : level.getServer().getAllLevels()) {
            net.minecraft.world.entity.Entity entity = each.getEntity(id);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }
}
