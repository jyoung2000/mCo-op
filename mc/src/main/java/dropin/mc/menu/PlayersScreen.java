package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import dropin.core.world.Member;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.menu.ui.DScreen;
import dropin.mc.policy.PolicyActions;
import dropin.mc.policy.WorldPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

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
public class PlayersScreen extends DScreen {
    private static final String K = "text.dropin_coop.";

    public record Row(String uuid, String name, boolean owner, boolean playingNow, long lastSeen, boolean banned, boolean admin) {
    }

    private final String worldId;
    private List<Row> rows = List.of();

    public PlayersScreen(GuiScreen parent, String worldId) {
        super(parent, I18n.format(K + "players.title"));
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
                WorldPolicy.Held held = PolicyActions.held(Minecraft.getMinecraft(), world);
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
        this.initGui();
    }

    @Override
    protected int titleY() {
        return 14;
    }

    @Override
    protected void init() {
        rows = rowsOf(worldId);
        CoopWorld world = world();
        WorldPolicy.Held held = world == null ? null : PolicyActions.held(this.mc, world);
        boolean owner = false;
        try {
            owner = held != null && CoopWorlds.store().isOwner(worldId);
        } catch (Exception e) {
            DropIn.LOGGER.warn("Could not tell who owns {}", worldId, e);
        }
        if (owner) {
            // DESIGN 4.13: only the owner changes the policy; every change is a new signed version.
            boolean allowed = held.policy().membersMayUseCommands();
            button(this.width / 2 - 100, this.height - 50, 200,
                    I18n.format(K + (allowed ? "players.commands_on" : "players.commands_off")),
                    b -> change(() -> PolicyActions.setMembersMayUseCommands(this.mc, world, !allowed)));
            int left = Math.max(10, this.width / 2 - 150);
            int y = 36;
            for (Row row : rows) {
                if (y > this.height - 74) {
                    break;
                }
                if (!row.owner()) {
                    button(left + 250, y - 2, 50, I18n.format(K + (row.banned() ? "players.unban" : "players.ban")),
                            b -> change(() -> PolicyActions.setBanned(this.mc, world, row.uuid(), !row.banned())));
                    // DESIGN 4.13: admins get operator rights in every session.
                    button(left + 176, y - 2, 70, I18n.format(K + (row.admin() ? "players.unadmin" : "players.admin")),
                            b -> change(() -> PolicyActions.setAdmin(this.mc, world, row.uuid(), !row.admin())));
                }
                y += 20;
            }
        }
        button(this.width / 2 - 100, this.height - 26, 200, I18n.format("gui.done"), b -> onClose());
    }

    private static String line(Row row) {
        StringBuilder text = new StringBuilder(row.name());
        if (row.owner()) {
            text.append(' ').append(I18n.format(K + "players.owner"));
        }
        if (row.admin()) {
            text.append(' ').append(I18n.format(K + "players.admin_tag"));
        }
        if (row.banned()) {
            text.append(' ').append(I18n.format(K + "players.banned"));
        }
        text.append("  ");
        if (row.playingNow()) {
            return text.append(I18n.format(K + "players.playing")).toString();
        }
        long minutes = Math.max(0, System.currentTimeMillis() / 1000 - row.lastSeen()) / 60;
        if (minutes < 1) {
            return text.append(I18n.format(K + "players.seen_now")).toString();
        } else if (minutes < 120) {
            return text.append(I18n.format(K + "players.seen_minutes", minutes)).toString();
        } else if (minutes < 48 * 60) {
            return text.append(I18n.format(K + "players.seen_hours", minutes / 60)).toString();
        }
        return text.append(I18n.format(K + "players.seen_days", minutes / (24 * 60))).toString();
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        int left = Math.max(10, this.width / 2 - 150);
        int y = 36;
        if (rows.isEmpty()) {
            drawCenteredString(this.fontRenderer, I18n.format(K + "players.none"), this.width / 2, y, 0xA0A0A0);
        }
        for (Row row : rows) {
            if (y > this.height - 74) {
                break;
            }
            try {
                // 1.12.2 port: Faces.draw without the 26.x GuiGraphics (it binds and draws the skin itself).
                Faces.draw(UUID.fromString(row.uuid()), left, y, 16);
            } catch (IllegalArgumentException e) {
                // an unreadable uuid only loses the head
            }
            drawString(this.fontRenderer, line(row), left + 22, y + 4, 0xFFFFFF);
            y += 20;
        }
    }
}
