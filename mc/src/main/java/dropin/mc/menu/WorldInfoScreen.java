package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.coop.HostSession;
import dropin.mc.menu.ui.DScreen;
import dropin.tunnel.Config;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNo;
import net.minecraft.client.resources.I18n;

/** What every player can do with one co-op world (DESIGN 4.4): see its players and mods, invite, remove it here. */
public class WorldInfoScreen extends DScreen {
    private static final String K = "text.dropin_coop.";

    private final String worldId;
    private CoopWorld world;
    private String notice = "";

    public WorldInfoScreen(GuiScreen parent, String worldId) {
        super(parent, I18n.format(K + "tooltip.info"));
        this.worldId = worldId;
    }

    @Override
    protected void init() {
        try {
            world = CoopWorlds.store().get(worldId).orElse(null);
        } catch (Exception e) {
            world = null;
        }
        title = world == null ? I18n.format(K + "tooltip.info") : world.name();
        int x = this.width / 2 - 100;
        int y = 58;
        if (world != null) {
            button(x, y, 200, I18n.format(K + "button.players"), b -> mc.displayGuiScreen(new PlayersScreen(this, worldId)));
            button(x, y + 24, 200, I18n.format(K + "button.mods"), b -> mc.displayGuiScreen(new ModsScreen(this, worldId)));
            button(x, y + 48, 200, I18n.format(K + "button.invite"), b -> mc.displayGuiScreen(new InviteScreen(this, world)));
            button(x, y + 72, 200, I18n.format(K + "info.remove"), b -> confirmRemove());
        }
        button(x, this.height - 26, 200, I18n.format("gui.done"), b -> onClose());
    }

    private void confirmRemove() {
        // 1.12.2 port: GuiYesNo with a callback stands in for 26.x's ConfirmScreen.
        mc.displayGuiScreen(new GuiYesNo((yes, id) -> {
            if (yes) {
                CoopActions.Outcome outcome = CoopActions.remove(mc, world);
                if (outcome.ok()) {
                    mc.displayGuiScreen(parent);
                    return;
                }
                notice = outcome.message().getFormattedText();
            }
            mc.displayGuiScreen(this);
        }, I18n.format(K + "remove.title", world.name()), I18n.format(K + "remove.question"), 0));
    }

    @Override
    protected int titleY() {
        return 14;
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        if (world != null) {
            String folder = CoopActions.folders(mc).get(worldId);
            drawCenteredString(this.fontRenderer, CoopWorldsScreen.statusLine(mc, world, folder), this.width / 2, 28, 0xFFA0A0A0);
            // The relay address is shown here instead of in chat, and only while this PC hosts the world.
            HostSession session = HostSession.current();
            if (session != null && session.world().worldId().equals(worldId) && !session.announcedAddr().isEmpty()
                    && !Config.INSTANCE.hideDomainInChat.value()) {
                drawCenteredString(this.fontRenderer, I18n.format(K + "info.address", session.announcedAddr()),
                        this.width / 2, 40, 0xFF55FF55);
            }
        }
        drawCenteredString(this.fontRenderer, notice, this.width / 2, this.height - 40, 0xFFFFDC00);
    }
}
