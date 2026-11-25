package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import dropin.mc.coop.StatusPing;
import dropin.mc.menu.ui.DButton;
import dropin.mc.menu.ui.DScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

import java.util.List;
import java.util.UUID;

/**
 * Join (DESIGN 4.4): paste an invite, see who is in the world right now, then join. Joining saves the world
 * to the list; if someone is hosting it, it also connects.
 */
public class JoinInviteScreen extends DScreen {
    private static final String K = "text.dropin_coop.";
    private static final int GRAY = 0xFFA0A0A0;
    private GuiTextField codeBox;
    private DButton joinButton;
    private String typed = "";
    private String notice = "";
    /** The world the typed code names, or null while the text is not an invite. */
    private CoopWorld candidate;
    /** Who is hosting the candidate; null while the lookup runs. */
    private CoopStatus.Status preview;
    private int lookup;

    public JoinInviteScreen(GuiScreen parent) {
        super(parent, I18n.format(K + "join.title"));
    }

    // ---- state the self-tests read and drive ----------------------------------------------------

    /** Sets the text of the code box as if typed (1.12.2 port: GuiTextField has no responder, so it is told here). */
    public void setCode(String code) {
        if (codeBox != null) {
            codeBox.setText(code);
            changed(codeBox.getText());
        } else {
            typed = code;
        }
    }

    /** The result of the lookup for the typed invite, or null if there is none yet. */
    public CoopStatus.Status preview() {
        return preview;
    }

    // ---------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        int boxWidth = Math.min(320, this.width - 20);
        codeBox = textField((this.width - boxWidth) / 2, 50, boxWidth, 20, 256);
        codeBox.setText(typed);
        codeBox.setFocused(true);
        joinButton = button(this.width / 2 - 100, this.height - 52, 200, I18n.format(K + "button.join"), b -> join());
        button(this.width / 2 - 100, this.height - 28, 200, I18n.format("gui.cancel"), b -> onClose());
        if (candidate == null) {
            changed(typed);
        }
        joinButton.enabled = candidate != null;
    }

    @Override
    protected void onTextChanged(GuiTextField field) {
        if (field == codeBox) {
            changed(field.getText());
        }
    }

    /** A new text in the box: if it is an invite, look up who is playing that world, off the client thread. */
    private void changed(String value) {
        typed = value;
        CoopWorld world = CoopActions.previewInvite(value);
        boolean same = world != null && candidate != null && world.worldId().equals(candidate.worldId());
        candidate = world;
        notice = world == null && !value.isBlank() ? I18n.format(K + "invite.bad") : "";
        if (joinButton != null) {
            joinButton.enabled = world != null;
        }
        if (same) {
            return;
        }
        preview = null;
        int mine = ++lookup;
        if (world != null) {
            Thread thread = new Thread(() -> {
                CoopStatus.Status status = CoopStatus.lookup(world);
                mc.addScheduledTask(() -> {
                    if (mine == lookup) {
                        preview = status;
                    }
                });
            }, "dropin-coop-join-preview");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public void join() {
        CoopActions.Outcome outcome = CoopActions.joinWithInvite(mc, typed);
        if (!outcome.ok()) {
            notice = outcome.message().getFormattedText();
            return;
        }
        boolean hosted = preview != null && preview.kind() == CoopStatus.Kind.PLAYING;
        mc.displayGuiScreen(parent);
        if (hosted && parent instanceof CoopWorldsScreen list) {
            list.select(outcome.world().worldId());
            list.play();
        }
    }

    @Override
    protected int titleY() {
        return 20;
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        int middle = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format(K + "join.label"), middle, 37, GRAY);
        drawCenteredString(this.fontRenderer, notice, middle, 76, 0xFFFFDC00);
        if (candidate == null) {
            return;
        }
        if (preview == null) {
            drawCenteredString(this.fontRenderer, I18n.format(K + "status.looking"), middle, 80, GRAY);
            return;
        }
        switch (preview.kind()) {
            case PLAYING -> players(middle);
            case UNREACHABLE -> drawCenteredString(this.fontRenderer, I18n.format(K + "join.unreachable"), middle, 80, GRAY);
            default -> drawCenteredString(this.fontRenderer, I18n.format(K + "join.nobody"), middle, 80, GRAY);
        }
    }

    /** The players the host named, one per line: head with the green "online" ring, then the name. */
    private void players(int middle) {
        List<StatusPing.Player> online = preview.online();
        drawCenteredString(this.fontRenderer, I18n.format(K + (online.isEmpty() ? "join.playing_hidden" : "join.playing"),
                preview.hostName()), middle, 80, 0xFFFFFF);
        int y = 96;
        for (int i = 0; i < online.size(); i++) {
            if (y + 16 > this.height - 58) {
                this.fontRenderer.drawString(I18n.format(K + "more", online.size() - i), middle - 60, y, GRAY);
                return;
            }
            StatusPing.Player player = online.get(i);
            int x = middle - 60;
            Gui.drawRect(x - 1, y - 1, x + 13, y + 13, 0xFF55FF55);
            try {
                Faces.draw(UUID.fromString(player.uuid()), x, y, 12);
            } catch (IllegalArgumentException e) {
                Gui.drawRect(x, y, x + 12, y + 12, 0xFF555555);
            }
            this.fontRenderer.drawString(player.name(), x + 18, y + 2, 0xFFFFFF);
            y += 16;
        }
    }
}
