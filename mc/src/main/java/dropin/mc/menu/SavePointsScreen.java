package dropin.mc.menu;

import dropin.core.savepoint.SavePoints;
import dropin.core.savepoint.SavePoints.Point;
import dropin.core.world.CoopWorld;
import dropin.mc.savepoint.SavePointActions;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The owner's save points of one world (DESIGN 4.16). Works on a world that is closed on this PC. */
public class SavePointsScreen extends Screen {
    private static final String K = "text.dropin_coop.savepoint.";
    private static final int LIST_TOP = 34;
    private static final int ROW_HEIGHT = 22;

    /** What a button does, off the client thread; returns the line to show. */
    private interface Work {
        Component run() throws Exception;
    }

    private final Screen parent;
    private final CoopWorld world;
    private final Path folder;
    private List<Point> points = List.of();
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<Point> rowPoints = new ArrayList<>();
    private final List<Button> needSelection = new ArrayList<>();
    private String selected;
    private int page;
    private EditBox nameBox;
    private Button createButton;
    private Button importButton;
    private String typed = "";
    private volatile boolean busy;
    private volatile Component notice = Component.empty();
    private volatile boolean stale;

    public SavePointsScreen(Screen parent, CoopWorld world, Path folder) {
        super(Component.translatable(K + "title", world.name()));
        this.parent = parent;
        this.world = world;
        this.folder = folder;
    }

    @Override
    protected void init() {
        try {
            points = SavePointActions.store(folder).list();
        } catch (Exception e) {
            notice = Component.translatable(K + "failed", String.valueOf(e.getMessage()));
        }
        int listWidth = Math.min(320, this.width - 20);
        int left = (this.width - listWidth) / 2;
        int perPage = Math.max(1, (this.height - LIST_TOP - 96) / ROW_HEIGHT);
        int pages = Math.max(1, (points.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);
        rowButtons.clear();
        rowPoints.clear();
        needSelection.clear();
        for (int i = 0; i < perPage && page * perPage + i < points.size(); i++) {
            Point point = points.get(page * perPage + i);
            rowPoints.add(point);
            rowButtons.add(this.addRenderableWidget(Button.builder(Component.empty(), button -> {
                selected = point.id();
                refresh();
            }).bounds(left, LIST_TOP + i * ROW_HEIGHT, listWidth, 20).build()));
        }
        if (pages > 1) {
            this.addRenderableWidget(Button.builder(Component.literal("<"), button -> turnPage(-1, pages)).bounds(left, 8, 20, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal(">"), button -> turnPage(1, pages))
                    .bounds(left + listWidth - 20, 8, 20, 20).build());
        }

        int gap = 4;
        int row1 = this.height - 74;
        int row2 = this.height - 50;
        int createWidth = 80;
        nameBox = new EditBox(this.font, left, row1, listWidth - createWidth - gap, 20, Component.translatable(K + "name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(typed);
        nameBox.setHint(Component.translatable(K + "name"));
        nameBox.setResponder(value -> {
            typed = value;
            refresh();
        });
        this.addRenderableWidget(nameBox);
        createButton = this.addRenderableWidget(Button.builder(Component.translatable(K + "button.create"), button -> {
            String name = typed;
            run(() -> {
                Point point = SavePointActions.createClosed(folder, name);
                return Component.translatable(K + "created", point.name(), point.records());
            });
        }).bounds(left + listWidth - createWidth, row1, createWidth, 20).build());

        int fifth = (listWidth - 4 * gap) / 5;
        needSelection.add(footer("button.restore", left, row2, fifth, 0, gap, () -> confirm("confirm_restore", point -> run(() ->
                Component.translatable(K + "restored", point.name(), SavePointActions.restoreClosed(folder, point))))));
        needSelection.add(footer("button.delete", left, row2, fifth, 1, gap, () -> confirm("confirm_delete", point -> run(() -> {
            SavePointActions.store(folder).delete(point);
            return Component.translatable(K + "deleted", point.name());
        }))));
        needSelection.add(footer("button.export", left, row2, fifth, 2, gap, () -> selectedPoint().ifPresent(point -> run(() ->
                Component.translatable(K + "exported", SavePointActions.export(world, folder, point).getFileName().toString())))));
        importButton = footer("button.import", left, row2, fifth, 3, gap, () -> importFiles(List.of()));
        needSelection.add(footer("button.single", left, row2, fifth, 4, gap, () -> selectedPoint().ifPresent(point -> run(() ->
                Component.translatable(K + "single_done", SavePointActions.makeSinglePlayer(this.minecraft, world, folder, point))))));
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
        refresh();
    }

    private Button footer(String key, int left, int y, int width, int column, int gap, Runnable action) {
        return this.addRenderableWidget(Button.builder(Component.translatable(K + key), button -> action.run())
                .bounds(left + column * (width + gap), y, width, 20).build());
    }

    private void turnPage(int delta, int pages) {
        page = Math.max(0, Math.min(pages - 1, page + delta));
        this.rebuildWidgets();
    }

    private java.util.Optional<Point> selectedPoint() {
        return points.stream().filter(point -> point.id().equals(selected)).findFirst();
    }

    private void confirm(String key, java.util.function.Consumer<Point> action) {
        selectedPoint().ifPresent(point -> this.minecraft.setScreen(new ConfirmScreen(yes -> {
            this.minecraft.setScreen(this);
            if (yes) {
                action.accept(point);
            }
        }, this.title, Component.translatable(K + key, point.name()))));
    }

    private void importFiles(List<Path> files) {
        run(() -> Component.translatable(K + "imported", SavePointActions.importFiles(world, folder, files).size()));
    }

    /** Runs a button's work off the client thread; the list is read again when it ends. */
    private void run(Work work) {
        if (busy) {
            return;
        }
        if (SavePointActions.isOpen(this.minecraft)) {
            notice = Component.translatable(K + "world_open");
            return;
        }
        busy = true;
        notice = Component.translatable(K + "busy");
        refresh();
        Thread thread = new Thread(() -> {
            try {
                notice = work.run();
            } catch (Exception e) {
                notice = Component.translatable(K + "failed", String.valueOf(e.getMessage()));
            } finally {
                busy = false;
                stale = true;
            }
        }, "dropin-coop-savepoint");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void tick() {
        super.tick();
        if (stale) {
            stale = false;
            this.rebuildWidgets();
        }
    }

    private void refresh() {
        for (int i = 0; i < rowButtons.size(); i++) {
            Point point = rowPoints.get(i);
            String prefix = point.id().equals(selected) ? "» " : "";
            rowButtons.get(i).setMessage(Component.literal(prefix).append(
                    Component.translatable(K + "line", point.name(), point.seq(), point.records())));
        }
        boolean any = !busy && selectedPoint().isPresent();
        for (Button button : needSelection) {
            button.active = any;
        }
        if (createButton != null) {
            createButton.active = !busy && SavePoints.validName(typed);
            importButton.active = !busy;
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        importFiles(files);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
        super.render(graphics, mouseX, mouseY, a);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, -1);
        if (points.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable(K + "empty"), this.width / 2, LIST_TOP + 20, -6250336);
        }
        graphics.drawCenteredString(this.font, notice, this.width / 2, this.height - 88, -9216);
    }
}
