package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import dropin.mc.coop.StatusPing;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.List;
import java.util.UUID;

/**
 * Join (DESIGN 4.4): paste an invite, see who is in the world right now, then join. Joining saves the world
 * to the list; if someone is hosting it, it also connects.
 */
public class JoinInviteScreen extends Screen {
    private static final String K = "text.dropin_coop.";
    private static final int GRAY = 0xFFA0A0A0;
    private final Screen parent;
    private EditBox codeBox;
    private Button joinButton;
    private String typed = "";
    private Component notice = Component.empty();
    /** The world the typed code names, or null while the text is not an invite. */
    private CoopWorld candidate;
    /** Who is hosting the candidate; null while the lookup runs. */
    private CoopStatus.Status preview;
    private int lookup;

    public JoinInviteScreen(Screen parent) {
        super(Component.translatable(K + "join.title"));
        this.parent = parent;
    }

    // ---- state the self-tests read and drive ----------------------------------------------------

    public void setCode(String code) {
        if (codeBox != null) {
            codeBox.setValue(code);
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
        codeBox = new EditBox(this.font, (this.width - boxWidth) / 2, 50, boxWidth, 20, Component.translatable(K + "join.label"));
        codeBox.setMaxLength(256);
        codeBox.setValue(typed);
        codeBox.setResponder(this::changed);
        this.addRenderableWidget(codeBox);
        joinButton = this.addRenderableWidget(Button.builder(Component.translatable(K + "button.join"), button -> join())
                .bounds(this.width / 2 - 100, this.height - 52, 200, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
        if (candidate == null) {
            changed(typed);
        }
        joinButton.active = candidate != null;
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(codeBox);
    }

    /** A new text in the box: if it is an invite, look up who is playing that world, off the client thread. */
    private void changed(String value) {
        typed = value;
        CoopWorld world = CoopActions.previewInvite(value);
        boolean same = world != null && candidate != null && world.worldId().equals(candidate.worldId());
        candidate = world;
        notice = world == null && !value.isBlank() ? Component.translatable(K + "invite.bad") : Component.empty();
        if (joinButton != null) {
            joinButton.active = world != null;
        }
        if (same) {
            return;
        }
        preview = null;
        int mine = ++lookup;
        if (world != null) {
            Thread thread = new Thread(() -> {
                CoopStatus.Status status = CoopStatus.lookup(world);
                this.minecraft.execute(() -> {
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
        CoopActions.Outcome outcome = CoopActions.joinWithInvite(this.minecraft, typed);
        if (!outcome.ok()) {
            notice = outcome.message();
            return;
        }
        boolean hosted = preview != null && preview.kind() == CoopStatus.Kind.PLAYING;
        this.minecraft.gui.setScreen(parent);
        if (hosted && parent instanceof CoopWorldsScreen list) {
            list.select(outcome.world().worldId());
            list.play();
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int middle = this.width / 2;
        graphics.centeredText(this.font, this.title, middle, 20, -1);
        graphics.centeredText(this.font, Component.translatable(K + "join.label"), middle, 37, GRAY);
        graphics.centeredText(this.font, notice, middle, 76, -9216);
        if (candidate == null) {
            return;
        }
        if (preview == null) {
            graphics.centeredText(this.font, Component.translatable(K + "status.looking"), middle, 80, GRAY);
            return;
        }
        switch (preview.kind()) {
            case PLAYING -> players(graphics, middle);
            case UNREACHABLE -> graphics.centeredText(this.font, Component.translatable(K + "join.unreachable"), middle, 80, GRAY);
            default -> graphics.centeredText(this.font, Component.translatable(K + "join.nobody"), middle, 80, GRAY);
        }
    }

    /** The players the host named, one per line: head with the green "online" ring, then the name. */
    private void players(GuiGraphicsExtractor graphics, int middle) {
        List<StatusPing.Player> online = preview.online();
        graphics.centeredText(this.font, Component.translatable(K + (online.isEmpty() ? "join.playing_hidden" : "join.playing"),
                preview.hostName()), middle, 80, -1);
        int y = 96;
        for (int i = 0; i < online.size(); i++) {
            if (y + 16 > this.height - 58) {
                graphics.text(this.font, Component.translatable(K + "more", online.size() - i), middle - 60, y, GRAY);
                return;
            }
            StatusPing.Player player = online.get(i);
            int x = middle - 60;
            graphics.fill(x - 1, y - 1, x + 13, y + 13, 0xFF55FF55);
            try {
                PlayerFaceExtractor.extractRenderState(graphics, ResolvableProfile.createUnresolved(UUID.fromString(player.uuid())), x, y, 12);
            } catch (IllegalArgumentException e) {
                graphics.fill(x, y, x + 12, y + 12, 0xFF555555);
            }
            graphics.text(this.font, player.name(), x + 18, y + 2, -1);
            y += 16;
        }
    }
}
