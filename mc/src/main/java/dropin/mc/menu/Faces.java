package dropin.mc.menu;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player heads by UUID for the menus (1.20.1 port). The default skin is drawn until the skin manager has
 * fetched the player's textures ({@code SkinManager.registerSkins} fills the profile from the session service).
 */
public final class Faces {
    private static final Map<UUID, ResourceLocation> SKINS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> ASKED = new ConcurrentHashMap<>();

    private Faces() {
    }

    public static void draw(GuiGraphics graphics, UUID id, int x, int y, int size) {
        PlayerFaceRenderer.draw(graphics, skin(id), x, y, size);
    }

    public static ResourceLocation skin(UUID id) {
        ResourceLocation known = SKINS.get(id);
        if (known != null) {
            return known;
        }
        if (ASKED.putIfAbsent(id, Boolean.TRUE) == null) {
            Minecraft.getInstance().getSkinManager().registerSkins(new GameProfile(id, null), (type, location, texture) -> {
                if (type == MinecraftProfileTexture.Type.SKIN) {
                    SKINS.put(id, location);
                }
            }, false);
        }
        return DefaultPlayerSkin.getDefaultSkin(id);
    }
}
