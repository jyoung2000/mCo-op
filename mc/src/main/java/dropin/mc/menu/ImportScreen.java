package dropin.mc.menu;

import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Import (DESIGN 4.4): pick a world from saves/, or drop a world folder or .zip on the screen. */
public class ImportScreen extends Screen {
    private static final String K = "text.dropin_coop.";
    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 32;

    private final Screen parent;
    private CompletableFuture<List<LevelSummary>> loading;
    private List<LevelSummary> saves;
    private String selected;
    private int page;
    private boolean backup = true;
    private Checkbox backupBox;
    private Button importButton;
    private Component notice = Component.empty();

    public ImportScreen(Screen parent) {
        super(Component.translatable(K + "import.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (loading == null) {
            LevelStorageSource source = this.minecraft.getLevelSource();
            try {
                loading = source.loadLevelSummaries(source.findLevelCandidates());
            } catch (RuntimeException e) {
                DropIn.LOGGER.warn("Could not list the saves folder", e);
                loading = CompletableFuture.completedFuture(List.of());
            }
        }
        int listWidth = Math.min(320, this.width - 20);
        int left = (this.width - listWidth) / 2;
        if (saves != null) {
            int perPage = Math.max(1, (this.height - LIST_TOP - 96) / ROW_HEIGHT);
            int pages = Math.max(1, (saves.size() + perPage - 1) / perPage);
            page = Math.min(page, pages - 1);
            for (int i = 0; i < perPage && page * perPage + i < saves.size(); i++) {
                LevelSummary save = saves.get(page * perPage + i);
                String prefix = save.getLevelId().equals(selected) ? "» " : "";
                this.addRenderableWidget(Button.builder(Component.literal(prefix + save.getLevelName()), button -> {
                    selected = save.getLevelId();
                    this.rebuildWidgets();
                }).bounds(left, LIST_TOP + i * ROW_HEIGHT, listWidth, 20).build());
            }
            if (pages > 1) {
                this.addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                    page = Math.floorMod(page - 1, pages);
                    this.rebuildWidgets();
                }).bounds(left, 8, 20, 20).build());
                this.addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                    page = Math.floorMod(page + 1, pages);
                    this.rebuildWidgets();
                }).bounds(left + listWidth - 20, 8, 20, 20).build());
            }
        }
        backupBox = this.addRenderableWidget(new Checkbox(left, this.height - 74, listWidth, 20,
                Component.translatable(K + "import.backup"), backup));
        int half = (listWidth - 4) / 2;
        importButton = this.addRenderableWidget(Button.builder(Component.translatable(K + "button.import"), button -> importSelected())
                .bounds(left, this.height - 50, half, 20).build());
        importButton.active = selected != null;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(left + half + 4, this.height - 50, half, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (saves == null && loading != null && loading.isDone()) {
            List<LevelSummary> found = new ArrayList<>();
            try {
                for (LevelSummary summary : loading.join()) {
                    if (summary != null && summary.levelVersion() != null && !isKnownCoop(summary)) {
                        found.add(summary);
                    }
                }
            } catch (RuntimeException e) {
                DropIn.LOGGER.warn("Could not read the saves folder", e);
            }
            found.sort(LevelSummary::compareTo);
            saves = found;
            this.rebuildWidgets();
        }
    }

    /** Worlds that are already co-op on this PC are not offered ("not co-op yet"). */
    private boolean isKnownCoop(LevelSummary summary) {
        Optional<String> marker = CoopWorldStore.readMarker(dropin.mc.compat.Compat.levelPath(this.minecraft.getLevelSource(), summary.getLevelId()));
        try {
            return marker.isPresent() && CoopWorlds.store().get(marker.get()).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private void importSelected() {
        if (selected == null) {
            return;
        }
        backup = backupBox == null || backupBox.selected();
        finish(CoopActions.importFromSaves(this.minecraft, selected, backup));
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        finish(CoopActions.importDropped(this.minecraft, files));
    }

    private void finish(CoopActions.Outcome outcome) {
        notice = outcome.message();
        if (outcome.ok()) {
            this.minecraft.setScreen(new InviteScreen(parent, outcome.world()));
        }
    }

    public Component notice() {
        return notice;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
        super.render(graphics, mouseX, mouseY, a);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, -1);
        if (saves == null) {
            graphics.drawCenteredString(this.font, Component.translatable(K + "status.looking"), this.width / 2, LIST_TOP + 20, -6250336);
        } else if (saves.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable(K + "import.none"), this.width / 2, LIST_TOP + 20, -6250336);
        }
        graphics.drawCenteredString(this.font, Component.translatable(K + "drop_hint"), this.width / 2, this.height - 100, -6250336);
        graphics.drawCenteredString(this.font, notice, this.width / 2, this.height - 88, -9216);
    }
}
