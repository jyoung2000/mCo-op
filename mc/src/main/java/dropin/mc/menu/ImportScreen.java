package dropin.mc.menu;

import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.menu.ui.DButton;
import dropin.mc.menu.ui.DScreen;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.world.storage.WorldSummary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Import (DESIGN 4.4): pick a world from saves/, or drop a world folder or .zip on the screen. */
public class ImportScreen extends DScreen {
    private static final String K = "text.dropin_coop.";
    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 32;
    private static final int GRAY = 0xFFA0A0A0;

    private CompletableFuture<List<WorldSummary>> loading;
    private List<WorldSummary> saves;
    private String selected;
    private int page;
    private boolean backup = true;
    private DButton importButton;
    private String notice = "";

    public ImportScreen(GuiScreen parent) {
        super(parent, I18n.format(K + "import.title"));
    }

    @Override
    protected void init() {
        if (loading == null) {
            // 1.12.2 port: ISaveFormat.getSaveList() reads every level.dat; off the client thread like 26.x's
            // loadLevelSummaries.
            loading = CompletableFuture.supplyAsync(() -> {
                try {
                    return mc.getSaveLoader().getSaveList();
                } catch (Exception e) {
                    DropIn.LOGGER.warn("Could not list the saves folder", e);
                    return List.<WorldSummary>of();
                }
            });
        }
        int listWidth = Math.min(320, this.width - 20);
        int left = (this.width - listWidth) / 2;
        if (saves != null) {
            int perPage = Math.max(1, (this.height - LIST_TOP - 96) / ROW_HEIGHT);
            int pages = Math.max(1, (saves.size() + perPage - 1) / perPage);
            page = Math.min(page, pages - 1);
            for (int i = 0; i < perPage && page * perPage + i < saves.size(); i++) {
                WorldSummary save = saves.get(page * perPage + i);
                String prefix = save.getFileName().equals(selected) ? "» " : "";
                button(left, LIST_TOP + i * ROW_HEIGHT, listWidth, prefix + displayName(save), b -> {
                    selected = save.getFileName();
                    initGui();
                });
            }
            if (pages > 1) {
                button(left, 8, 20, "<", b -> {
                    page = Math.floorMod(page - 1, pages);
                    initGui();
                });
                button(left + listWidth - 20, 8, 20, ">", b -> {
                    page = Math.floorMod(page + 1, pages);
                    initGui();
                });
            }
        }
        // 1.12.2 port: no Checkbox widget; a button whose label shows [x] / [ ] and toggles the flag.
        button(left, this.height - 74, listWidth, backupLabel(), b -> {
            backup = !backup;
            b.setLabel(backupLabel());
        });
        int half = (listWidth - 4) / 2;
        importButton = button(left, this.height - 50, half, I18n.format(K + "button.import"), b -> importSelected());
        importButton.enabled = selected != null;
        button(left + half + 4, this.height - 50, half, I18n.format("gui.cancel"), b -> onClose());
    }

    private String backupLabel() {
        return (backup ? "[x] " : "[ ] ") + I18n.format(K + "import.backup");
    }

    /** The world's name as the game's world list shows it; the folder name if the name is empty. */
    private static String displayName(WorldSummary save) {
        String name = save.getDisplayName();
        return name == null || name.isEmpty() ? save.getFileName() : name;
    }

    @Override
    protected void tick() {
        if (saves == null && loading != null && loading.isDone()) {
            List<WorldSummary> found = new ArrayList<>();
            try {
                for (WorldSummary summary : loading.join()) {
                    // 1.12.2 port: 26.x left out unreadable summaries (levelVersion() == null); getSaveList()
                    // already skips folders without a readable level.dat.
                    if (summary != null && !isKnownCoop(summary)) {
                        found.add(summary);
                    }
                }
            } catch (RuntimeException e) {
                DropIn.LOGGER.warn("Could not read the saves folder", e);
            }
            found.sort(WorldSummary::compareTo);
            saves = found;
            initGui();
        }
    }

    /** Worlds that are already co-op on this PC are not offered ("not co-op yet"). */
    private boolean isKnownCoop(WorldSummary summary) {
        Optional<String> marker = CoopWorldStore.readMarker(CoopWorldsScreen.levelPath(mc, summary.getFileName()));
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
        finish(CoopActions.importFromSaves(mc, selected, backup));
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        finish(CoopActions.importDropped(mc, files));
    }

    private void finish(CoopActions.Outcome outcome) {
        notice = outcome.message().getFormattedText();
        if (outcome.ok()) {
            mc.displayGuiScreen(new InviteScreen(parent, outcome.world()));
        }
    }

    public String notice() {
        return notice;
    }

    @Override
    protected int titleY() {
        return 14;
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        if (saves == null) {
            drawCenteredString(this.fontRenderer, I18n.format(K + "status.looking"), this.width / 2, LIST_TOP + 20, GRAY);
        } else if (saves.isEmpty()) {
            drawCenteredString(this.fontRenderer, I18n.format(K + "import.none"), this.width / 2, LIST_TOP + 20, GRAY);
        }
        drawCenteredString(this.fontRenderer, I18n.format(K + "drop_hint"), this.width / 2, this.height - 100, GRAY);
        drawCenteredString(this.fontRenderer, notice, this.width / 2, this.height - 88, 0xFFFFDC00);
    }
}
