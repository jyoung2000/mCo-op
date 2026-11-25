package dropin.mc.menu;

import dropin.mc.menu.ui.DScreen;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

/**
 * 1.12.2 port: 26.x {@code AlertScreen}: a title, a wrapped message and one button that runs {@code onDone}
 * (Escape does the same). 1.12.2 has no such screen.
 */
public class NoticeScreen extends DScreen {
    private final String message;
    private final Runnable onDone;

    public NoticeScreen(GuiScreen parent, String title, String message, Runnable onDone) {
        super(parent, title);
        this.message = message;
        this.onDone = onDone;
    }

    public String message() {
        return message;
    }

    @Override
    protected int titleY() {
        return this.height / 2 - 50;
    }

    @Override
    protected void init() {
        button(this.width / 2 - 100, this.height / 2 + 40, 200, I18n.format("gui.done"), b -> onClose());
    }

    @Override
    public void onClose() {
        onDone.run();
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        drawWrappedCentered(message, this.width / 2, this.height / 2 - 30, Math.min(340, this.width - 20), 0xFFFFFF);
    }
}
