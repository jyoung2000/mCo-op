package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import dropin.mc.menu.ui.DScreen;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;

/** Invite (DESIGN 4.4): shows this world's invite code and copies it. */
public class InviteScreen extends DScreen {
    private static final String K = "text.dropin_coop.";
    private final CoopWorld world;
    private final String code;

    public InviteScreen(GuiScreen parent, CoopWorld world) {
        super(parent, I18n.format(K + "invite.title", world.name()));
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
        button(this.width / 2 - 100, this.height / 2 + 30, 200, I18n.format(K + "invite.copy"), button -> {
            setClipboardString(code);
            button.setLabel(I18n.format(K + "invite.copied"));
        });
        button(this.width / 2 - 100, this.height / 2 + 54, 200, I18n.format("gui.done"), button -> onClose());
    }

    /** The code broken at its group separators so every line fits the screen. */
    private List<String> lines() {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String group : code.split("-")) {
            String next = line.length() == 0 ? group : line + "-" + group;
            if (line.length() > 0 && this.fontRenderer.getStringWidth(next) > this.width - 20) {
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
    protected int titleY() {
        return 20;
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        drawCenteredString(this.fontRenderer, I18n.format(K + "invite.explain"), this.width / 2, this.height / 2 - 40, 0xFFA0A0A0);
        int y = this.height / 2 - 20;
        for (String line : lines()) {
            drawCenteredString(this.fontRenderer, line, this.width / 2, y, 0xFFFFFF);
            y += 11;
        }
    }
}
