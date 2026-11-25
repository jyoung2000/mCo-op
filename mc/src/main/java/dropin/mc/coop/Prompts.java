package dropin.mc.coop;

import dropin.mc.menu.ui.DScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;

import java.util.function.Consumer;

/**
 * 1.12.2 port: the three plain screens of 26.x the launch, handoff and policy flows show. 1.12.2 has
 * {@code GuiYesNo} but no message-only or OK-only screen, so all three are small {@link DScreen}s:
 * <ul>
 *   <li>{@link Message}: 26.x {@code GenericMessageScreen} (text, no buttons, Escape does nothing);</li>
 *   <li>{@link Alert}: 26.x {@code AlertScreen} (text and one button; Escape presses it);</li>
 *   <li>{@link Confirm}: 26.x {@code ConfirmScreen} (text, yes and no; Escape answers no).</li>
 * </ul>
 */
public final class Prompts {
    private static final int TEXT_WIDTH = 300;

    private Prompts() {
    }

    /** 26.x {@code Component.getString()} for screen text: translated on this client. */
    static String text(ITextComponent component) {
        return component == null ? "" : component.getFormattedText();
    }

    /** "Looking for a host…", "Preparing…": a line of text and nothing to press. */
    public static class Message extends DScreen {
        private final ITextComponent message;

        public Message(ITextComponent message) {
            super(null, "");
            this.message = message;
        }

        public ITextComponent message() {
            return message;
        }

        @Override
        protected void init() {
        }

        @Override
        public void onClose() {
            // 26.x GenericMessageScreen cannot be closed with Escape.
        }

        @Override
        protected void drawContents(int mouseX, int mouseY, float partialTicks) {
            drawWrappedCentered(text(message), width / 2, height / 2 - 20, TEXT_WIDTH, 0xFFFFFF);
        }
    }

    /** A message with one button ({@code gui.done}) that runs {@code onOk}. */
    public static class Alert extends DScreen {
        private final Runnable onOk;
        private final ITextComponent message;

        public Alert(Runnable onOk, String title, ITextComponent message) {
            super(null, title);
            this.onOk = onOk;
            this.message = message;
        }

        public ITextComponent message() {
            return message;
        }

        @Override
        protected void init() {
            button(width / 2 - 100, height / 2 + 40, 200, I18n.format("gui.done"), b -> onOk.run());
        }

        @Override
        public void onClose() {
            onOk.run();
        }

        @Override
        protected void drawContents(int mouseX, int mouseY, float partialTicks) {
            drawWrappedCentered(text(message), width / 2, height / 2 - 40, TEXT_WIDTH, 0xFFFFFF);
        }
    }

    /** A question with two buttons; the answer goes to {@code answer} (true = the first button). */
    public static class Confirm extends DScreen {
        private final Consumer<Boolean> answer;
        private final ITextComponent message;
        private final String yes;
        private final String no;

        public Confirm(Consumer<Boolean> answer, String title, ITextComponent message, String yes, String no) {
            super(null, title);
            this.answer = answer;
            this.message = message;
            this.yes = yes;
            this.no = no;
        }

        /** With the game's "Yes" and "No". */
        public Confirm(Consumer<Boolean> answer, String title, ITextComponent message) {
            this(answer, title, message, I18n.format("gui.yes"), I18n.format("gui.no"));
        }

        public ITextComponent message() {
            return message;
        }

        @Override
        protected void init() {
            button(width / 2 - 155, height / 2 + 40, 150, yes, b -> answer.accept(true));
            button(width / 2 + 5, height / 2 + 40, 150, no, b -> answer.accept(false));
        }

        @Override
        public void onClose() {
            answer.accept(false);
        }

        @Override
        protected void drawContents(int mouseX, int mouseY, float partialTicks) {
            drawWrappedCentered(text(message), width / 2, height / 2 - 40, TEXT_WIDTH, 0xFFFFFF);
        }
    }
}
