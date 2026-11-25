package dropin.mc.menu;

import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player heads by UUID for the menus (1.21.1 port). The game of this version has no unresolved-profile face
 * renderer, so the default skin is drawn until the session service has returned the player's textures.
 */
public final class Faces {
    private static final Map<UUID, PlayerSkin> SKINS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> ASKED = new ConcurrentHashMap<>();

    private Faces() {
    }

    public static void draw(GuiGraphics graphics, UUID id, int x, int y, int size) {
        PlayerFaceRenderer.draw(graphics, skin(id), x, y, size);
    }

    public static PlayerSkin skin(UUID id) {
        PlayerSkin known = SKINS.get(id);
        if (known != null) {
            return known;
        }
        if (ASKED.putIfAbsent(id, Boolean.TRUE) == null) {
            Minecraft minecraft = Minecraft.getInstance();
            CompletableFuture.supplyAsync(() -> {
                ProfileResult result = minecraft.getMinecraftSessionService().fetchProfile(id, false);
                return result == null ? null : result.profile();
            }, net.minecraft.Util.ioPool()).thenCompose(profile -> profile == null
                    ? CompletableFuture.completedFuture(null)
                    : minecraft.getSkinManager().getOrLoad(profile)).whenComplete((skin, error) -> {
                if (skin != null) {
                    SKINS.put(id, skin);
                }
            });
        }
        return DefaultPlayerSkin.get(id);
    }
}
