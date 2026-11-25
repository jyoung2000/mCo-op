package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.coop.HostSession;
import dropin.tunnel.Config;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** What every player can do with one co-op world (DESIGN 4.4): see its players and mods, invite, remove it here. */
public class WorldInfoScreen extends Screen {
    private static final String K = "text.dropin_coop.";

    private final Screen parent;
    private final String worldId;
    private CoopWorld world;
    private Component notice = Component.empty();

    public WorldInfoScreen(Screen parent, String worldId) {
        super(Component.translatable(K + "tooltip.info"));
        this.parent = parent;
        this.worldId = worldId;
    }

    @Override
    protected void init() {
        try {
            world = CoopWorlds.store().get(worldId).orElse(null);
        } catch (Exception e) {
            world = null;
        }
        int x = this.width / 2 - 100;
        int y = 58;
        if (world != null) {
            button(K + "button.players", x, y, () -> this.minecraft.setScreen(new PlayersScreen(this, worldId)));
            button(K + "button.mods", x, y + 24, () -> this.minecraft.setScreen(new ModsScreen(this, worldId)));
            button(K + "button.invite", x, y + 48, () -> this.minecraft.setScreen(new InviteScreen(this, world)));
            button(K + "info.remove", x, y + 72, this::confirmRemove);
        }
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(x, this.height - 26, 200, 20).build());
    }

    private void button(String key, int x, int y, Runnable action) {
        this.addRenderableWidget(Button.builder(Component.translatable(key), button -> action.run()).bounds(x, y, 200, 20).build());
    }

    private void confirmRemove() {
        this.minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                CoopActions.Outcome outcome = CoopActions.remove(this.minecraft, world);
                if (outcome.ok()) {
                    this.minecraft.setScreen(parent);
                    return;
                }
                notice = outcome.message();
            }
            this.minecraft.setScreen(this);
        }, Component.translatable(K + "remove.title", world.name()), Component.translatable(K + "remove.question")));
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
        super.render(graphics, mouseX, mouseY, a);
        graphics.drawCenteredString(this.font, world == null ? this.title : Component.literal(world.name()), this.width / 2, 14, -1);
        if (world != null) {
            String folder = CoopActions.folders(this.minecraft).get(worldId);
            graphics.drawCenteredString(this.font, CoopWorldsScreen.statusLine(this.minecraft, world, folder), this.width / 2, 28, 0xFFA0A0A0);
            // The relay address is shown here instead of in chat, and only while this PC hosts the world.
            HostSession session = HostSession.current();
            if (session != null && session.world().worldId().equals(worldId) && !session.announcedAddr().isEmpty()
                    && !Config.INSTANCE.hideDomainInChat.value()) {
                graphics.drawCenteredString(this.font, Component.translatable(K + "info.address", session.announcedAddr()),
                        this.width / 2, 40, 0xFF55FF55);
            }
        }
        graphics.drawCenteredString(this.font, notice, this.width / 2, this.height - 40, -9216);
    }
}
