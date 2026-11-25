package dropin.mc.menu.ui;

import com.cleanroommc.client.sdl.SDL;
import com.cleanroommc.client.sdl.events.DropEvent;
import dropin.mc.DropIn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 1.12.2 port: the parts of 26.x's {@code Screen} that DropIn's menus use, on top of 1.12.2's {@code GuiScreen}.
 * <ul>
 *   <li>a title drawn at the top, and {@code onClose()} that goes back to the parent (Escape calls it);</li>
 *   <li>lambda buttons ({@link DButton}) with tooltips, instead of ids and {@code actionPerformed};</li>
 *   <li>text fields ({@code GuiTextField}) that get keys, clicks, ticks and drawing without per-screen code;</li>
 *   <li>{@link #onFilesDrop(List)}, 26.x's file drop, fed by Cleanroom's SDL drop events.</li>
 * </ul>
 * Subclasses build their widgets in {@link #init()} (26.x {@code Screen.init}), which runs on every resize.
 */
public abstract class DScreen extends GuiScreen {
    protected final GuiScreen parent;
    protected String title;
    private final List<GuiTextField> fields = new ArrayList<>();
    private int nextFieldId;

    protected DScreen(GuiScreen parent, String title) {
        this.parent = parent;
        this.title = title;
    }

    public String title() {
        return title;
    }

    public GuiScreen parent() {
        return parent;
    }

    /** 26.x {@code Screen.init()}: build the widgets. */
    protected abstract void init();

    @Override
    public final void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        labelList.clear();
        fields.clear();
        nextFieldId = 0;
        init();
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    /** 26.x {@code Screen.onClose()}: back to the parent screen. */
    public void onClose() {
        mc.displayGuiScreen(parent);
    }

    /** Files dropped onto the game window while this screen is open (26.x {@code Screen.onFilesDrop}). */
    public void onFilesDrop(List<Path> paths) {
    }

    // ---- widgets

    protected DButton button(int x, int y, int width, int height, String label, Consumer<DButton> onPress) {
        return addButton(new DButton(x, y, width, height, label, onPress));
    }

    protected DButton button(int x, int y, int width, String label, Consumer<DButton> onPress) {
        return button(x, y, width, 20, label, onPress);
    }

    protected GuiTextField textField(int x, int y, int width, int height, int maxLength) {
        GuiTextField field = new GuiTextField(nextFieldId++, fontRenderer, x, y, width, height);
        field.setMaxStringLength(maxLength);
        fields.add(field);
        return field;
    }

    /** The buttons of this screen, for self-tests that press them as a player would. */
    public List<GuiButton> buttons() {
        return List.copyOf(buttonList);
    }

    public List<GuiTextField> textFields() {
        return List.copyOf(fields);
    }

    /** The first enabled-or-not {@link DButton} whose label is {@code label}, or null. */
    public DButton findButton(String label) {
        for (GuiButton b : buttonList) {
            if (b instanceof DButton d && label.equals(d.label())) {
                return d;
            }
        }
        return null;
    }

    // ---- drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawBackground(mouseX, mouseY, partialTicks);
        if (title != null && !title.isEmpty()) {
            drawCenteredString(fontRenderer, title, width / 2, titleY(), 0xFFFFFF);
        }
        drawContents(mouseX, mouseY, partialTicks);
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (GuiTextField field : fields) {
            field.drawTextBox();
        }
        drawForeground(mouseX, mouseY, partialTicks);
        for (GuiButton b : buttonList) {
            if (b instanceof DButton d && d.isHovered(mouseX, mouseY) && !d.tooltipLines().isEmpty()) {
                drawHoveringText(wrapTooltip(d.tooltipLines()), mouseX, mouseY);
                break;
            }
        }
    }

    protected int titleY() {
        return 15;
    }

    protected void drawBackground(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
    }

    /** Drawn after the title and before the buttons (26.x: what a screen renders before {@code super.render}). */
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
    }

    /** Drawn after the buttons and text fields. */
    protected void drawForeground(int mouseX, int mouseY, float partialTicks) {
    }

    private List<String> wrapTooltip(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            out.addAll(fontRenderer.listFormattedStringToWidth(line, Math.max(120, width / 2)));
        }
        return out;
    }

    /** Draws {@code text} wrapped to {@code maxWidth}, centred on {@code centerX}; returns the y below it. */
    protected int drawWrappedCentered(String text, int centerX, int y, int maxWidth, int color) {
        for (String line : fontRenderer.listFormattedStringToWidth(text, maxWidth)) {
            drawCenteredString(fontRenderer, line, centerX, y, color);
            y += fontRenderer.FONT_HEIGHT + 1;
        }
        return y;
    }

    // ---- input

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        for (GuiTextField field : fields) {
            if (field.isFocused() && field.textboxKeyTyped(typedChar, keyCode)) {
                onTextChanged(field);
                return;
            }
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            onClose();
        } else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            onEnter();
        }
    }

    /** A text field changed by typing. */
    protected void onTextChanged(GuiTextField field) {
    }

    /** Enter outside a text field that took it. */
    protected void onEnter() {
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        for (GuiTextField field : fields) {
            field.mouseClicked(mouseX, mouseY, mouseButton);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof DButton d) {
            d.press();
        }
    }

    @Override
    public void updateScreen() {
        for (GuiTextField field : fields) {
            field.updateCursorCounter();
        }
        tick();
    }

    /** 26.x {@code Screen.tick()}. */
    protected void tick() {
    }

    // ---- file drops (Cleanroom's SDL window posts DropEvent.File per file, then DropEvent.Complete)

    private static boolean dropsHooked;

    /** Hooks the window's drop events once; called from the client entry point. */
    public static synchronized void hookFileDrops() {
        if (dropsHooked) {
            return;
        }
        try {
            SDL.events().register(new DropRouter());
            dropsHooked = true;
        } catch (RuntimeException | LinkageError e) {
            DropIn.LOGGER.warn("File drops onto the game window are not available", e);
        }
    }

    public static final class DropRouter {
        private final List<Path> pending = new ArrayList<>();

        @SubscribeEvent
        public void onFile(DropEvent.File event) {
            synchronized (pending) {
                pending.add(event.path());
            }
        }

        @SubscribeEvent
        public void onComplete(DropEvent.Complete event) {
            List<Path> paths;
            synchronized (pending) {
                paths = List.copyOf(pending);
                pending.clear();
            }
            if (paths.isEmpty()) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                if (mc.currentScreen instanceof DScreen screen) {
                    screen.onFilesDrop(paths);
                }
            });
        }
    }
}
