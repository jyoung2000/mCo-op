package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import dropin.core.world.Member;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.policy.PolicyActions;
import dropin.mc.policy.WorldPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The Players tab of DESIGN 4.15, from the members this PC knows about: head, name, owner badge,
 * "playing now" / "last seen". Skins come from the game's own lookup by UUID. The list is this PC's own,
 * kept current by the member lists hosts and members exchange (DESIGN 4.13). The owner also gets the
 * policy controls here: who is banned, and whether members may use commands.
 */
public class PlayersScreen extends Screen {
    private static final String K = "text.dropin_coop.";

    public record Row(String uuid, String name, boolean owner, boolean playingNow, long lastSeen, boolean banned, boolean admin) {
    }

    private final Screen parent;
    private final String worldId;
    private List<Row> rows = List.of();

    public PlayersScreen(Screen parent, String worldId) {
        super(Component.translatable(K + "players.title"));
        this.parent = parent;
        this.worldId = worldId;
    }

    public List<Row> rows() {
        return rows;
    }

    /** The rows for a world, owner first. Also used by the self-tests. */
    public static List<Row> rowsOf(String worldId) {
        List<Row> rows = new ArrayList<>();
        try {
            CoopWorld world = CoopWorlds.store().get(worldId).orElse(null);
            if (world != null) {
                WorldPolicy.Held held = PolicyActions.held(Minecraft.getInstance(), world);
                CoopStatus.Status status = CoopStatus.of(worldId);
                for (Member member : world.members()) {
                    rows.add(new Row(member.uuid(), member.name(), member.isOwner(), status.isOnline(member.uuid()), member.lastSeen(),
                            held != null && held.policy().isBanned(member.uuid()),
                            held != null && held.policy().isAdmin(member.uuid())));
                }
            }
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not read the members of {}", worldId, e);
        }
        rows.sort(Comparator.comparing((Row row) -> !row.owner()).thenComparing(row -> !row.admin())
                .thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private CoopWorld world() {
        try {
            return CoopWorlds.store().get(worldId).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** An owner's change from this tab: signs a new policy version, then shows it. */
    private void change(java.util.concurrent.Callable<?> action) {
        try {
            action.call();
        } catch (Exception e) {
            DropIn.LOGGER.error("Could not change the policy of {}", worldId, e);
        }
        this.rebuildWidgets();
    }

    @Override
    protected void init() {
        rows = rowsOf(worldId);
        CoopWorld world = world();
        WorldPolicy.Held held = world == null ? null : PolicyActions.held(this.minecraft, world);
        boolean owner = false;
        try {
            owner = held != null && CoopWorlds.store().isOwner(worldId);
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not tell who owns {}", worldId, e);
        }
        if (owner) {
            // DESIGN 4.13: only the owner changes the policy; every change is a new signed version.
            boolean allowed = held.policy().membersMayUseCommands();
            this.addRenderableWidget(Button.builder(Component.translatable(K + (allowed ? "players.commands_on" : "players.commands_off")),
                    button -> change(() -> PolicyActions.setMembersMayUseCommands(this.minecraft, world, !allowed)))
                    .bounds(this.width / 2 - 100, this.height - 50, 200, 20).build());
            int left = Math.max(10, this.width / 2 - 150);
            int y = 36;
            for (Row row : rows) {
                if (y > this.height - 74) {
                    break;
                }
                if (!row.owner()) {
                    this.addRenderableWidget(Button.builder(Component.translatable(K + (row.banned() ? "players.unban" : "players.ban")),
                            button -> change(() -> PolicyActions.setBanned(this.minecraft, world, row.uuid(), !row.banned())))
                            .bounds(left + 250, y - 2, 50, 20).build());
                    // DESIGN 4.13: admins get operator rights in every session.
                    this.addRenderableWidget(Button.builder(Component.translatable(K + (row.admin() ? "players.unadmin" : "players.admin")),
                            button -> change(() -> PolicyActions.setAdmin(this.minecraft, world, row.uuid(), !row.admin())))
                            .bounds(left + 176, y - 2, 70, 20).build());
                }
                y += 20;
            }
        }
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    private Component line(Row row) {
        MutableComponent text = Component.literal(row.name());
        if (row.owner()) {
            text.append(" ").append(Component.translatable(K + "players.owner"));
        }
        if (row.admin()) {
            text.append(" ").append(Component.translatable(K + "players.admin_tag"));
        }
        if (row.banned()) {
            text.append(" ").append(Component.translatable(K + "players.banned"));
        }
        text.append("  ");
        if (row.playingNow()) {
            return text.append(Component.translatable(K + "players.playing"));
        }
        long minutes = Math.max(0, System.currentTimeMillis() / 1000 - row.lastSeen()) / 60;
        if (minutes < 1) {
            return text.append(Component.translatable(K + "players.seen_now"));
        } else if (minutes < 120) {
            return text.append(Component.translatable(K + "players.seen_minutes", minutes));
        } else if (minutes < 48 * 60) {
            return text.append(Component.translatable(K + "players.seen_hours", minutes / 60));
        }
        return text.append(Component.translatable(K + "players.seen_days", minutes / (24 * 60)));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 14, -1);
        int left = Math.max(10, this.width / 2 - 150);
        int y = 36;
        if (rows.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable(K + "players.none"), this.width / 2, y, -6250336);
        }
        for (Row row : rows) {
            if (y > this.height - 74) {
                break;
            }
            try {
                PlayerFaceExtractor.extractRenderState(graphics, ResolvableProfile.createUnresolved(UUID.fromString(row.uuid())), left, y, 16);
            } catch (IllegalArgumentException e) {
                // an unreadable uuid only loses the head
            }
            graphics.text(this.font, line(row), left + 22, y + 4, -1);
            y += 20;
        }
    }
}
