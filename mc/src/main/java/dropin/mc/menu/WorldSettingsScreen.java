package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import dropin.mc.policy.WorldPolicy;
import dropin.mc.savepoint.SavePointActions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * The owner's menu of one co-op world, behind the wrench (DESIGN 4.13, 4.16): admins, save points and the
 * world's folders.
 */
public class WorldSettingsScreen extends Screen {
    private static final String K = "text.dropin_coop.";

    private final Screen parent;
    private final CoopWorld world;
    /** The world's folder under saves/, or null if this PC has no copy of it. */
    private final Path folder;
    private Component notice = Component.empty();

    public WorldSettingsScreen(Screen parent, CoopWorld world, Path folder) {
        super(Component.translatable(K + "tooltip.settings"));
        this.parent = parent;
        this.world = world;
        this.folder = folder;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 100;
        int y = Math.max(30, this.height / 2 - 96);
        button(K + "settings.players", x, y, true, () -> this.minecraft.setScreen(new PlayersScreen(this, world.worldId())));
        button(K + "settings.savepoints", x, y + 24, folder != null,
                () -> this.minecraft.setScreen(new SavePointsScreen(this, world, folder)));
        button(K + "settings.backups", x, y + 48, folder != null, () -> open(SavePointActions.backupsDir(folder)));
        button(K + "settings.mods_folder", x, y + 72, true, () -> open(WorldPolicy.modsDir()));
        button(K + "settings.datapacks", x, y + 96, folder != null, () -> open(folder.resolve("datapacks")));
        button(K + "settings.export_world", x, y + 120, folder != null, this::exportWorld);
        button(K + "button.export_key", x, y + 144, true,
                () -> notice = CoopActions.exportOwnerKey(this.minecraft, world).message());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(x, this.height - 26, 200, 20).build());
    }

    private void button(String key, int x, int y, boolean active, Runnable action) {
        Button button = Button.builder(Component.translatable(key), pressed -> action.run()).bounds(x, y, 200, 20).build();
        button.active = active;
        this.addRenderableWidget(button);
    }

    /** The whole world as one zip in its backups folder; on a thread, a world can be large. */
    private void exportWorld() {
        if (SavePointActions.isOpen(this.minecraft)) {
            notice = Component.translatable(K + "export.world_open");
            return;
        }
        notice = Component.translatable(K + "export.working");
        Thread thread = new Thread(() -> {
            Component result;
            try {
                result = Component.translatable(K + "export.done", CoopActions.exportWorld(this.minecraft, world, folder).getFileName().toString());
            } catch (java.io.IOException | RuntimeException e) {
                dropin.mc.DropIn.LOGGER.error("Could not export co-op world '{}'", world.name(), e);
                result = Component.translatable(K + "export.failed", String.valueOf(e.getMessage()));
            }
            Component shown = result;
            this.minecraft.execute(() -> notice = shown);
        }, "dropin-coop-export");
        thread.setDaemon(true);
        thread.start();
    }

    private void open(Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
            net.minecraft.Util.getPlatform().openPath(dir);
        } catch (java.io.IOException | RuntimeException e) {
            notice = Component.literal(String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
        super.render(graphics, mouseX, mouseY, a);
        graphics.drawCenteredString(this.font, Component.translatable(K + "settings.title", world.name()), this.width / 2, 14, -1);
        graphics.drawCenteredString(this.font, notice, this.width / 2, this.height - 40, -9216);
    }
}
