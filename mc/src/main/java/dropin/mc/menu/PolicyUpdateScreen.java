package dropin.mc.menu;

import dropin.mc.menu.ui.DScreen;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * The owner's screen of DESIGN 4.14 "Owner changes mods": lists what was added and removed and asks
 * "Update this world's mod list?". Yes signs a new policy version; No means the owner cannot host until
 * the folder matches again.
 */
public class PolicyUpdateScreen extends DScreen {
    private static final String K = "text.dropin_coop.";
    private static final int SHOWN = 8;

    private final List<String> added;
    private final List<String> removed;
    private final boolean datapacksChanged;
    private final boolean gameChanged;
    private final Runnable onYes;
    private final Runnable onNo;
    private boolean answered;

    public PolicyUpdateScreen(List<String> added, List<String> removed, boolean datapacksChanged, boolean gameChanged,
                              Runnable onYes, Runnable onNo) {
        super(null, I18n.format(K + "policy.update.title"));
        this.added = List.copyOf(added);
        this.removed = List.copyOf(removed);
        this.datapacksChanged = datapacksChanged;
        this.gameChanged = gameChanged;
        this.onYes = onYes;
        this.onNo = onNo;
    }

    public List<String> addedMods() {
        return added;
    }

    public List<String> removedMods() {
        return removed;
    }

    public boolean datapacksChanged() {
        return datapacksChanged;
    }

    @Override
    protected int titleY() {
        return 14;
    }

    @Override
    protected void init() {
        button(this.width / 2 - 154, this.height - 30, 150, I18n.format("gui.yes"), b -> accept());
        button(this.width / 2 + 4, this.height - 30, 150, I18n.format("gui.no"), b -> decline());
    }

    /** "Yes": sign a new policy version and go on. */
    public void accept() {
        if (!answered) {
            answered = true;
            onYes.run();
        }
    }

    public void decline() {
        if (!answered) {
            answered = true;
            onNo.run();
        }
    }

    @Override
    public void onClose() {
        decline();
    }

    private static String shortList(List<String> names) {
        String text = String.join(", ", names.subList(0, Math.min(SHOWN, names.size())));
        return names.size() > SHOWN ? text + ", … (" + names.size() + ")" : text;
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        List<String> lines = new ArrayList<>();
        if (!added.isEmpty()) {
            lines.add(I18n.format(K + "policy.update.added", shortList(added)));
        }
        if (!removed.isEmpty()) {
            lines.add(I18n.format(K + "policy.update.removed", shortList(removed)));
        }
        if (datapacksChanged) {
            lines.add(I18n.format(K + "policy.update.datapacks"));
        }
        if (gameChanged) {
            lines.add(I18n.format(K + "policy.update.game"));
        }
        lines.add("");
        lines.add(I18n.format(K + "policy.update.friends"));
        if (!removed.isEmpty()) {
            lines.add(I18n.format(K + "policy.update.warning"));
        }
        int y = 40;
        for (String line : lines) {
            for (String part : this.fontRenderer.listFormattedStringToWidth(line, Math.min(340, this.width - 20))) {
                drawCenteredString(this.fontRenderer, part, this.width / 2, y, 0xFFFFFF);
                y += 11;
            }
            y += 3;
        }
    }
}
