package dropin.mc.menu;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player heads by UUID for the menus. The default skin is drawn until the session service has returned the
 * player's textures.
 * <p>1.12.2 port: a skin is a texture location; the face is drawn the way the tab list draws it (face, then the
 * hat layer, from the 64x64 skin). A player in the current session gives its skin at once.
 */
public final class Faces {
    private static final Map<UUID, ResourceLocation> SKINS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> ASKED = new ConcurrentHashMap<>();

    private Faces() {
    }

    /** Draws the head of {@code id}, {@code size} pixels square, at {@code x, y}. Client thread only. */
    public static void draw(UUID id, int x, int y, int size) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(skin(id));
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
        Gui.drawScaledCustomSizeModalRect(x, y, 8.0F, 8.0F, 8, 8, size, size, 64.0F, 64.0F);
        Gui.drawScaledCustomSizeModalRect(x, y, 40.0F, 8.0F, 8, 8, size, size, 64.0F, 64.0F);
        GlStateManager.disableBlend();
    }

    /** The skin texture of {@code id}: the real one once known, the default skin until then. Client thread only. */
    public static ResourceLocation skin(UUID id) {
        ResourceLocation known = SKINS.get(id);
        if (known != null) {
            return known;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.getConnection() != null) {
            NetworkPlayerInfo info = minecraft.getConnection().getPlayerInfo(id);
            if (info != null) {
                return info.getLocationSkin();
            }
        }
        if (ASKED.putIfAbsent(id, Boolean.TRUE) == null) {
            Thread thread = new Thread(() -> {
                try {
                    GameProfile profile = minecraft.getSessionService().fillProfileProperties(new GameProfile(id, null), false);
                    if (profile == null) {
                        return;
                    }
                    minecraft.addScheduledTask(() -> minecraft.getSkinManager().loadProfileTextures(profile, (type, location, texture) -> {
                        if (type == MinecraftProfileTexture.Type.SKIN) {
                            SKINS.put(id, location);
                        }
                    }, false));
                } catch (RuntimeException e) {
                    // offline or unknown player: the default skin stays
                }
            }, "dropin-coop-face");
            thread.setDaemon(true);
            thread.start();
        }
        return DefaultPlayerSkin.getDefaultSkin(id);
    }
}
