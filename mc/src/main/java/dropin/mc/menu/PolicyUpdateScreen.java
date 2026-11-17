package dropin.mc.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The owner's screen of DESIGN 4.14 "Owner changes mods": lists what was added and removed and asks
 * "Update this world's mod list?". Yes signs a new policy version; No means the owner cannot host until
 * the folder matches again.
 */
public class PolicyUpdateScreen extends Screen {
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
        super(Component.translatable(K + "policy.update.title"));
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
    protected void init() {
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_YES, button -> accept())
                .bounds(this.width / 2 - 154, this.height - 30, 150, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_NO, button -> decline())
                .bounds(this.width / 2 + 4, this.height - 30, 150, 20).build());
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 14, -1);
        List<Component> lines = new ArrayList<>();
        if (!added.isEmpty()) {
            lines.add(Component.translatable(K + "policy.update.added", shortList(added)));
        }
        if (!removed.isEmpty()) {
            lines.add(Component.translatable(K + "policy.update.removed", shortList(removed)));
        }
        if (datapacksChanged) {
            lines.add(Component.translatable(K + "policy.update.datapacks"));
        }
        if (gameChanged) {
            lines.add(Component.translatable(K + "policy.update.game"));
        }
        lines.add(Component.empty());
        lines.add(Component.translatable(K + "policy.update.friends"));
        if (!removed.isEmpty()) {
            lines.add(Component.translatable(K + "policy.update.warning"));
        }
        int y = 40;
        for (Component line : lines) {
            for (FormattedCharSequence part : this.font.split(line, Math.min(340, this.width - 20))) {
                graphics.centeredText(this.font, part, this.width / 2, y, -1);
                y += 11;
            }
            y += 3;
        }
    }
}
