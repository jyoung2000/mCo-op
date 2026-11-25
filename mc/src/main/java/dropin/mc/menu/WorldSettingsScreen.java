package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import dropin.mc.menu.ui.DButton;
import dropin.mc.menu.ui.DScreen;
import dropin.mc.policy.WorldPolicy;
import dropin.mc.savepoint.SavePointActions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.I18n;

import java.nio.file.Path;

/**
 * The owner's menu of one co-op world, behind the wrench (DESIGN 4.13, 4.16): admins, save points and the
 * world's folders.
 */
public class WorldSettingsScreen extends DScreen {
    private static final String K = "text.dropin_coop.";

    private final CoopWorld world;
    /** The world's folder under saves/, or null if this PC has no copy of it. */
    private final Path folder;
    private volatile String notice = "";

    public WorldSettingsScreen(GuiScreen parent, CoopWorld world, Path folder) {
        super(parent, I18n.format(K + "settings.title", world.name()));
        this.world = world;
        this.folder = folder;
    }

    @Override
    protected void init() {
        int x = this.width / 2 - 100;
        int y = Math.max(30, this.height / 2 - 96);
        add(K + "settings.players", x, y, true, () -> mc.displayGuiScreen(new PlayersScreen(this, world.worldId())));
        add(K + "settings.savepoints", x, y + 24, folder != null,
                () -> mc.displayGuiScreen(new SavePointsScreen(this, world, folder)));
        add(K + "settings.backups", x, y + 48, folder != null, () -> open(SavePointActions.backupsDir(folder)));
        add(K + "settings.mods_folder", x, y + 72, true, () -> open(WorldPolicy.modsDir()));
        add(K + "settings.datapacks", x, y + 96, folder != null, () -> open(folder.resolve("datapacks")));
        add(K + "settings.export_world", x, y + 120, folder != null, this::exportWorld);
        add(K + "button.export_key", x, y + 144, true,
                () -> notice = CoopActions.exportOwnerKey(mc, world).message().getFormattedText());
        button(x, this.height - 26, 200, I18n.format("gui.done"), b -> onClose());
    }

    private void add(String key, int x, int y, boolean active, Runnable action) {
        DButton button = button(x, y, 200, I18n.format(key), pressed -> action.run());
        button.enabled = active;
    }

    /** The whole world as one zip in its backups folder; on a thread, a world can be large. */
    private void exportWorld() {
        if (SavePointActions.isOpen(mc)) {
            notice = I18n.format(K + "export.world_open");
            return;
        }
        notice = I18n.format(K + "export.working");
        Thread thread = new Thread(() -> {
            String result;
            try {
                String name = CoopActions.exportWorld(mc, world, folder).getFileName().toString();
                result = I18n.format(K + "export.done", name);
            } catch (java.io.IOException | RuntimeException e) {
                dropin.mc.DropIn.LOGGER.error("Could not export co-op world '{}'", world.name(), e);
                result = I18n.format(K + "export.failed", String.valueOf(e.getMessage()));
            }
            String shown = result;
            mc.addScheduledTask(() -> notice = shown);
        }, "dropin-coop-export");
        thread.setDaemon(true);
        thread.start();
    }

    /** 1.12.2 port: {@code OpenGlHelper.openFile} (Cleanroom: SDL desktop) stands in for 26.x's {@code Util.getPlatform().openPath}. */
    private void open(Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
            OpenGlHelper.openFile(dir.toFile());
        } catch (java.io.IOException | RuntimeException e) {
            notice = String.valueOf(e.getMessage());
        }
    }

    @Override
    protected int titleY() {
        return 14;
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        drawCenteredString(this.fontRenderer, notice, this.width / 2, this.height - 40, 0xFFFFDC00);
    }
}
