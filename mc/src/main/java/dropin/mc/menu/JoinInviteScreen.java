package dropin.mc.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Join with invite (DESIGN 4.4): paste an invite code. */
public class JoinInviteScreen extends Screen {
    private static final String K = "text.dropin_coop.";
    private final Screen parent;
    private EditBox codeBox;
    private Button joinButton;
    private String typed = "";
    private Component notice = Component.empty();

    public JoinInviteScreen(Screen parent) {
        super(Component.translatable(K + "join.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(320, this.width - 20);
        codeBox = new EditBox(this.font, (this.width - boxWidth) / 2, this.height / 2 - 30, boxWidth, 20,
                Component.translatable(K + "join.label"));
        codeBox.setMaxLength(256);
        codeBox.setValue(typed);
        codeBox.setResponder(value -> {
            typed = value;
            joinButton.active = !value.isBlank();
        });
        this.addRenderableWidget(codeBox);
        joinButton = this.addRenderableWidget(Button.builder(Component.translatable(K + "button.join"), button -> join())
                .bounds(this.width / 2 - 100, this.height / 2 + 10, 200, 20).build());
        joinButton.active = !typed.isBlank();
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(this.width / 2 - 100, this.height / 2 + 34, 200, 20).build());
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(codeBox);
    }

    private void join() {
        CoopActions.Outcome outcome = CoopActions.joinWithInvite(this.minecraft, typed);
        if (outcome.ok()) {
            onClose();
        } else {
            notice = outcome.message();
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 20, -1);
        graphics.centeredText(this.font, Component.translatable(K + "join.label"), this.width / 2, this.height / 2 - 46, -6250336);
        graphics.centeredText(this.font, notice, this.width / 2, this.height / 2 - 4, -9216);
    }
}
