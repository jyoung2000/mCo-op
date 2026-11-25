package dropin.mc.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import com.mojang.realmsclient.RealmsMainScreen;

/**
 * 1.20.1 port (from the 1.21.1 one): what Minecraft 26.x does in one place and this version does elsewhere. The 26.x method
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
        boolean realms = mc.isConnectedToRealms();
        if (mc.level != null) {
            mc.getConnection().getConnection().disconnect(message);
        }
        if (localServer) {
            mc.clearLevel(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        } else {
            mc.clearLevel();
        }
        TitleScreen titleScreen = new TitleScreen();
        if (localServer) {
            mc.setScreen(titleScreen);
        } else if (realms) {
            mc.setScreen(new RealmsMainScreen(titleScreen));
        } else {
            mc.setScreen(new JoinMultiplayerScreen(titleScreen));
        }
    }

    /** 1.20.1 port: {@code LevelStorageSource.getLevelPath(String)} is private in this version. */
    public static java.nio.file.Path levelPath(net.minecraft.world.level.storage.LevelStorageSource source, String levelId) {
        return source.getBaseDir().resolve(levelId);
    }

    /**
     * 1.20.1 port: the later {@code WorldOpenFlows.openWorld(String, Runnable)}. This version's
     * {@code loadLevel(Screen, String)} goes back to a screen when the world does not open; that screen runs
     * {@code onCancel} as soon as it is shown.
     */
    public static void openWorld(Minecraft mc, net.minecraft.client.gui.screens.worldselection.WorldOpenFlows flows, String levelId,
                                 Runnable onCancel) {
        flows.loadLevel(new net.minecraft.client.gui.screens.Screen(Component.empty()) {
            @Override
            protected void init() {
                mc.execute(onCancel);
            }
        }, levelId);
    }

    /** 1.20.1 port: NBT files are read and written through {@code File} here. */
    public static net.minecraft.nbt.CompoundTag readCompressed(java.nio.file.Path file) throws java.io.IOException {
        return net.minecraft.nbt.NbtIo.readCompressed(file.toFile());
    }

    public static void writeCompressed(net.minecraft.nbt.CompoundTag tag, java.nio.file.Path file) throws java.io.IOException {
        net.minecraft.nbt.NbtIo.writeCompressed(tag, file.toFile());
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
