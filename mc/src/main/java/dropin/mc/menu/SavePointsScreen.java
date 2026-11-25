package dropin.mc.menu;

import dropin.core.savepoint.SavePoints;
import dropin.core.savepoint.SavePoints.Point;
import dropin.core.world.CoopWorld;
import dropin.mc.menu.ui.DButton;
import dropin.mc.menu.ui.DScreen;
import dropin.mc.savepoint.SavePointActions;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.resources.I18n;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The owner's save points of one world (DESIGN 4.16). Works on a world that is closed on this PC. */
public class SavePointsScreen extends DScreen {
    private static final String K = "text.dropin_coop.savepoint.";
    private static final int LIST_TOP = 34;
    private static final int ROW_HEIGHT = 22;

    /** What a button does, off the client thread; returns the line to show. */
    private interface Work {
        String run() throws Exception;
    }

    private final CoopWorld world;
    private final Path folder;
    private List<Point> points = List.of();
    private final List<DButton> rowButtons = new ArrayList<>();
    private final List<Point> rowPoints = new ArrayList<>();
    private final List<DButton> needSelection = new ArrayList<>();
    private String selected;
    private int page;
    private GuiTextField nameBox;
    private DButton createButton;
    private DButton importButton;
    private String typed = "";
    private volatile boolean busy;
    /** 1.12.2 port: the line under the list, already translated (26.x kept a Component). */
    private volatile String notice = "";
    private volatile boolean stale;

    public SavePointsScreen(GuiScreen parent, CoopWorld world, Path folder) {
        super(parent, I18n.format(K + "title", world.name()));
        this.world = world;
        this.folder = folder;
    }

    @Override
    protected int titleY() {
        return 14;
    }

    @Override
    protected void init() {
        try {
            points = SavePointActions.store(folder).list();
        } catch (Exception e) {
            notice = I18n.format(K + "failed", String.valueOf(e.getMessage()));
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
            rowButtons.add(button(left, LIST_TOP + i * ROW_HEIGHT, listWidth, "", b -> {
                selected = point.id();
                refresh();
            }));
        }
        if (pages > 1) {
            button(left, 8, 20, "<", b -> turnPage(-1, pages));
            button(left + listWidth - 20, 8, 20, ">", b -> turnPage(1, pages));
        }

        int gap = 4;
        int row1 = this.height - 74;
        int row2 = this.height - 50;
        int createWidth = 80;
        nameBox = textField(left, row1, listWidth - createWidth - gap, 20, 32);
        nameBox.setText(typed);
        createButton = button(left + listWidth - createWidth, row1, createWidth, I18n.format(K + "button.create"), b -> {
            String name = typed;
            run(() -> {
                Point point = SavePointActions.createClosed(folder, name);
                return I18n.format(K + "created", point.name(), point.records());
            });
        });

        int fifth = (listWidth - 4 * gap) / 5;
        needSelection.add(footer("button.restore", left, row2, fifth, 0, gap, () -> confirm("confirm_restore", point -> run(() ->
                I18n.format(K + "restored", point.name(), SavePointActions.restoreClosed(folder, point))))));
        needSelection.add(footer("button.delete", left, row2, fifth, 1, gap, () -> confirm("confirm_delete", point -> run(() -> {
            SavePointActions.store(folder).delete(point);
            return I18n.format(K + "deleted", point.name());
        }))));
        needSelection.add(footer("button.export", left, row2, fifth, 2, gap, () -> selectedPoint().ifPresent(point -> run(() ->
                I18n.format(K + "exported", SavePointActions.export(world, folder, point).getFileName().toString())))));
        importButton = footer("button.import", left, row2, fifth, 3, gap, () -> importFiles(List.of()));
        needSelection.add(footer("button.single", left, row2, fifth, 4, gap, () -> selectedPoint().ifPresent(point -> run(() ->
                I18n.format(K + "single_done", SavePointActions.makeSinglePlayer(this.mc, world, folder, point))))));
        button(this.width / 2 - 100, this.height - 26, 200, I18n.format("gui.back"), b -> onClose());
        refresh();
    }

    private DButton footer(String key, int left, int y, int width, int column, int gap, Runnable action) {
        return button(left + column * (width + gap), y, width, I18n.format(K + key), b -> action.run());
    }

    private void turnPage(int delta, int pages) {
        page = Math.max(0, Math.min(pages - 1, page + delta));
        this.initGui();
    }

    private java.util.Optional<Point> selectedPoint() {
        return points.stream().filter(point -> point.id().equals(selected)).findFirst();
    }

    /** 1.12.2 port: 26.x ConfirmScreen is {@code GuiYesNo}; its callback comes back to this screen either way. */
    private void confirm(String key, java.util.function.Consumer<Point> action) {
        selectedPoint().ifPresent(point -> this.mc.displayGuiScreen(new GuiYesNo((yes, id) -> {
            this.mc.displayGuiScreen(this);
            if (yes) {
                action.accept(point);
            }
        }, this.title, I18n.format(K + key, point.name()), 0)));
    }

    private void importFiles(List<Path> files) {
        run(() -> I18n.format(K + "imported", SavePointActions.importFiles(world, folder, files).size()));
    }

    /** Runs a button's work off the client thread; the list is read again when it ends. */
    private void run(Work work) {
        if (busy) {
            return;
        }
        if (SavePointActions.isOpen(this.mc)) {
            notice = I18n.format(K + "world_open");
            return;
        }
        busy = true;
        notice = I18n.format(K + "busy");
        refresh();
        Thread thread = new Thread(() -> {
            try {
                notice = work.run();
            } catch (Exception e) {
                notice = I18n.format(K + "failed", String.valueOf(e.getMessage()));
            } finally {
                busy = false;
                stale = true;
            }
        }, "dropin-coop-savepoint");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void tick() {
        if (stale) {
            stale = false;
            this.initGui();
        }
    }

    @Override
    protected void onTextChanged(GuiTextField field) {
        if (field == nameBox) {
            typed = field.getText();
            refresh();
        }
    }

    private void refresh() {
        for (int i = 0; i < rowButtons.size(); i++) {
            Point point = rowPoints.get(i);
            String prefix = point.id().equals(selected) ? "» " : "";
            rowButtons.get(i).setLabel(prefix + I18n.format(K + "line", point.name(), point.seq(), point.records()));
        }
        boolean any = !busy && selectedPoint().isPresent();
        for (DButton button : needSelection) {
            button.enabled = any;
        }
        if (createButton != null) {
            createButton.enabled = !busy && SavePoints.validName(typed);
            importButton.enabled = !busy;
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        importFiles(files);
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        if (points.isEmpty()) {
            drawCenteredString(this.fontRenderer, I18n.format(K + "empty"), this.width / 2, LIST_TOP + 20, 0xA0A0A0);
        }
        drawCenteredString(this.fontRenderer, notice, this.width / 2, this.height - 88, 0xFFDC00);
    }

    @Override
    protected void drawForeground(int mouseX, int mouseY, float partialTicks) {
        // 1.12.2 port: GuiTextField has no hint (26.x EditBox.setHint); drawn here while the box is empty.
        if (nameBox != null && nameBox.getText().isEmpty() && !nameBox.isFocused()) {
            this.fontRenderer.drawStringWithShadow(I18n.format(K + "name"), nameBox.x + 4, nameBox.y + 6, 0x707070);
        }
    }
}
