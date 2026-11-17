package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Invite (DESIGN 4.4): shows this world's invite code and copies it. */
public class InviteScreen extends Screen {
    private static final String K = "text.dropin_coop.";
    private final Screen parent;
    private final CoopWorld world;
    private final String code;
    private Button copyButton;

    public InviteScreen(Screen parent, CoopWorld world) {
        super(Component.translatable(K + "invite.title", world.name()));
        this.parent = parent;
        this.world = world;
        this.code = world.invite().encode();
    }

    public String code() {
        return code;
    }

    public CoopWorld world() {
        return world;
    }

    @Override
    protected void init() {
        copyButton = this.addRenderableWidget(Button.builder(Component.translatable(K + "invite.copy"), button -> {
            this.minecraft.keyboardHandler.setClipboard(code);
            button.setMessage(Component.translatable(K + "invite.copied"));
        }).bounds(this.width / 2 - 100, this.height / 2 + 30, 200, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(this.width / 2 - 100, this.height / 2 + 54, 200, 20).build());
    }

    /** The code broken at its group separators so every line fits the screen. */
    private List<String> lines() {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String group : code.split("-")) {
            String next = line.length() == 0 ? group : line + "-" + group;
            if (line.length() > 0 && this.font.width(next) > this.width - 20) {
                lines.add(line + "-");
                line = new StringBuilder(group);
            } else {
                line = new StringBuilder(next);
            }
        }
        lines.add(line.toString());
        return lines;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 20, -1);
        graphics.centeredText(this.font, Component.translatable(K + "invite.explain"), this.width / 2, this.height / 2 - 40, -6250336);
        int y = this.height / 2 - 20;
        for (String line : lines()) {
            graphics.centeredText(this.font, line, this.width / 2, y, -1);
            y += 11;
        }
    }
}
