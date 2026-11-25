package dropin.mc.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.entity.Entity;
import net.minecraft.realms.RealmsBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import java.util.UUID;

/**
 * 1.12.2 port: what Minecraft 26.x does in one place and this version does elsewhere. The 26.x method
 * {@code Minecraft.disconnectFromWorld(Component)} is the pause menu's quit button here
 * ({@code GuiIngameMenu.actionPerformed}, case 1); this is that body, with DropIn's hooks of DESIGN 4.9 in front.
 */
public final class Compat {
    /** 26.x {@code ClientLevel.DEFAULT_QUIT_MESSAGE}. 1.12.2 sends no reason when a client quits. */
    public static final ITextComponent DEFAULT_QUIT_MESSAGE = new TextComponentTranslation("multiplayer.status.quitting");

    private Compat() {
    }

    /** The pause menu's "Save and Quit to Title" (or "Disconnect"), with the handoff and crash hooks first. */
    public static void disconnectFromWorld(Minecraft mc, ITextComponent message) {
        // DESIGN 4.9 (crash): a member that leaves by itself has not lost its host.
        dropin.mc.handoff.CrashFailover.leaving(mc);
        if (dropin.mc.handoff.HostHandoff.intercept(mc, message)) {
            return;
        }
        boolean localServer = mc.isIntegratedServerRunning();
        boolean realms = mc.isConnectedToRealms();
        if (mc.world != null) {
            mc.world.sendQuittingDisconnectingPacket();
        }
        mc.loadWorld(null);
        if (localServer) {
            mc.displayGuiScreen(new GuiMainMenu());
        } else if (realms) {
            new RealmsBridge().switchToRealms(new GuiMainMenu());
        } else {
            mc.displayGuiScreen(new GuiMultiplayer(new GuiMainMenu()));
        }
    }

    /** 26.x {@code ServerLevel.getEntityInAnyDimension(UUID)}: the entity in any of the server's worlds, or null. */
    public static Entity entityInAnyDimension(MinecraftServer server, UUID id) {
        return server.getEntityFromUuid(id);
    }
}
