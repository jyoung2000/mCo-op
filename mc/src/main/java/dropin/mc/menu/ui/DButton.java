package dropin.mc.menu.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

import java.util.List;
import java.util.function.Consumer;

/**
 * 1.12.2 port: a {@code GuiButton} that runs its own action, as 26.x's {@code Button.builder(label, onPress)} does,
 * instead of going through an id and {@code GuiScreen.actionPerformed}. {@code enabled} is 26.x's {@code active}.
 */
public class DButton extends GuiButton {
    private Consumer<DButton> onPress;
    private List<String> tooltip = List.of();

    public DButton(int x, int y, int width, int height, String label, Consumer<DButton> onPress) {
        super(0, x, y, width, height, label);
        this.onPress = onPress;
    }

    /** Runs the action, as a click does. Self-tests call this to press a button the way a player would. */
    public void press() {
        if (enabled && visible && onPress != null) {
            onPress.accept(this);
        }
    }

    public void setOnPress(Consumer<DButton> onPress) {
        this.onPress = onPress;
    }

    public String label() {
        return displayString;
    }

    public void setLabel(String label) {
        this.displayString = label;
    }

    public DButton tooltip(String text) {
        this.tooltip = text == null || text.isEmpty() ? List.of() : List.of(text);
        return this;
    }

    public DButton tooltip(List<String> lines) {
        this.tooltip = lines == null ? List.of() : List.copyOf(lines);
        return this;
    }

    public List<String> tooltipLines() {
        return tooltip;
    }

    public boolean isHovered(int mouseX, int mouseY) {
        return visible && mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
    }

    /** Draws the button; subclasses with their own look override this (26.x {@code renderWidget}). */
    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        super.drawButton(mc, mouseX, mouseY, partialTicks);
    }
}
