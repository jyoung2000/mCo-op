package dropin.mc.menu;

import com.mojang.blaze3d.platform.NativeImage;
import dropin.core.world.CoopWorld;
import dropin.core.world.Member;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopLauncher;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.policy.PolicyActions;
import dropin.mc.policy.WorldPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.FaviconTexture;
import dropin.mc.compat.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Co-op Worlds screen of DESIGN 4.4: Join, Create and Import on top, then one row per world with its
 * preview, status and the heads of its players. Everything else is behind the row's info and wrench buttons.
 */
public class CoopWorldsScreen extends Screen {
    public static final Component TITLE = Component.translatable("menu.dropin_coop.title");
    private static final String K = "text.dropin_coop.";
    private static final int ROW_HEIGHT = 46;
    private static final int ROW_PITCH = 48;
    private static final int HEAD = 12;
    private static final int LIST_TOP = 50;
    private static final int ICON = 32;
    private static final int GOLD = 0xFFFFAA00;
    private static final int AQUA = 0xFF55FFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int GRAY = 0xFFA0A0A0;
    // 1.20.1 port: the world list's join arrow is a region of this texture (no GUI sprites in this version).
    private static final ResourceLocation WORLD_SELECTION = new ResourceLocation("textures/gui/world_selection.png");
    /** The Discord logo in pixels: '#' Discord blurple, 'o' white. */
    private static final String[] DISCORD_ICON = {
            "...#....#...",
            ".##########.",
            "############",
            "###oo##oo###",
            "###oo##oo###",
            "############",
            "############",
            ".####..####.",
            "..##....##.."};
    private static final int DISCORD_BLURPLE = 0xFF5865F2;
    private static final Component CREDIT = Component.translatable("menu.dropin_coop.credit", "yessirski");
    private int pages = 1;

    private static final String[] INFO_ICON = {
            "....##....",
            "....##....",
            "..........",
            "...###....",
            "....##....",
            "....##....",
            "....##....",
            "....##....",
            "...####...",
            ".........."};
    private static final String[] WRENCH_ICON = {
            "......##..",
            ".....##...",
            ".....##..#",
            ".....####.",
            "....####..",
            "...###....",
            "..###.....",
            ".###......",
            "###.......",
            "##........"};

    /** What the player at this PC is in a world. The list is sorted in this order. */
    public enum Role { OWNER, ADMIN, PLAYER }

    /** One player head on a world's row. */
    public record Head(String uuid, String name, Role role, boolean online) {
    }

    private record Glyph(Button button, String[] pixels) {
    }

    private final Screen parent;
    private List<CoopWorld> worlds = List.of();
    private Map<String, String> folders = Map.of();
    private final Map<String, Role> roles = new HashMap<>();
    private final Map<String, WorldPolicy.Held> policies = new HashMap<>();
    private final Map<String, List<Head>> heads = new HashMap<>();
    private final Map<String, FaviconTexture> icons = new HashMap<>();
    private final List<Glyph> glyphs = new ArrayList<>();
    private String selected;
    private int page;
    private Component notice = Component.empty();
    private int ticks;

    public CoopWorldsScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    // ---- state the self-tests read ------------------------------------------------------------

    /** The worlds in the order shown: owned first, then where this player is an admin, then the rest. */
    public List<CoopWorld> worlds() {
        return worlds;
    }

    public Component notice() {
        return notice;
    }

    public void select(String worldId) {
        this.selected = worldId;
    }

    public Role roleOf(String worldId) {
        return roles.getOrDefault(worldId, Role.PLAYER);
    }

    /** The heads on a world's row: owner, admins, then the others; who is online is read from the live status. */
    public List<Head> heads(String worldId) {
        return heads.getOrDefault(worldId, List.of());
    }

    public Component statusLine(CoopWorld world) {
        return statusLine(this.minecraft, world, folders.get(world.worldId()));
    }

    /** The live status line of a world; {@code folder} is its folder name under saves/, or null without a copy. */
    public static Component statusLine(Minecraft mc, CoopWorld world, String folder) {
        CoopStatus.Status status = CoopStatus.of(world.worldId());
        boolean hasCopy = folder != null;
        Component sync = dropin.mc.member.SyncClient.statusLine(world.worldId(),
                hasCopy ? dropin.mc.compat.Compat.levelPath(mc.getLevelSource(), folder) : null);
        if (sync != null) {
            return status.kind() == CoopStatus.Kind.PLAYING
                    ? Component.translatable(K + "status.playing", status.hostName()).append(" — ").append(sync) : sync;
        }
        return switch (status.kind()) {
            case LOOKING -> Component.translatable(K + "status.looking");
            case PLAYING -> Component.translatable(hasCopy ? K + "status.playing" : K + "status.playing_no_copy", status.hostName());
            case NOBODY -> Component.translatable(hasCopy ? K + "status.nobody" : K + "status.no_copy");
            case UNREACHABLE -> Component.translatable(hasCopy ? K + "status.unreachable" : K + "status.no_copy");
        };
    }

    // ---- layout -------------------------------------------------------------------------------

    @Override
    protected void init() {
        CoopActions.pendingNew = false;
        closeIcons();
        glyphs.clear();
        reload();
        int listWidth = Math.min(340, this.width - 20);
        int left = (this.width - listWidth) / 2;
        int gap = 4;
        int third = (listWidth - 2 * gap) / 3;
        // Create is the game's own Create World screen, so every option and mod of the player applies.
        top(K + "button.create", left, third, this::createNew);
        top(K + "button.join", left + third + gap, third, () -> this.minecraft.setScreen(new JoinInviteScreen(this)));
        top(K + "button.import", left + 2 * (third + gap), third, () -> this.minecraft.setScreen(new ImportScreen(this)));

        int perPage = Math.max(1, (this.height - LIST_TOP - 44) / ROW_PITCH);
        int pages = Math.max(1, (worlds.size() + perPage - 1) / perPage);
        this.pages = pages;
        page = Math.min(page, pages - 1);
        for (int i = 0; i < perPage && page * perPage + i < worlds.size(); i++) {
            CoopWorld world = worlds.get(page * perPage + i);
            int y = LIST_TOP + i * ROW_PITCH;
            boolean owner = roleOf(world.worldId()) == Role.OWNER;
            this.addRenderableWidget(new WorldRow(left, y, listWidth - (owner ? 48 : 24), world));
            int infoX = left + listWidth - (owner ? 44 : 20);
            glyph(infoX, y + 13, K + "tooltip.info", INFO_ICON,
                    () -> this.minecraft.setScreen(new WorldInfoScreen(this, world.worldId())));
            if (owner) {
                // DESIGN 4.13, 4.16: admins, save points and the world's folders are the owner's.
                glyph(left + listWidth - 20, y + 13, K + "tooltip.settings", WRENCH_ICON,
                        () -> this.minecraft.setScreen(new WorldSettingsScreen(this, world, folderOf(world.worldId()))));
            }
        }
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
        if (pages > 1) {
            this.addRenderableWidget(Button.builder(Component.literal("<"), button -> turnPage(-1, pages))
                    .bounds(this.width / 2 - 124, this.height - 26, 20, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal(">"), button -> turnPage(1, pages))
                    .bounds(this.width / 2 + 104, this.height - 26, 20, 20).build());
        }
    }

    private void top(String key, int x, int width, Runnable action) {
        this.addRenderableWidget(Button.builder(Component.translatable(key), button -> action.run())
                .bounds(x, 24, width, 20).build());
    }

    /** A small square button whose face is drawn from pixels, with its name as a tooltip. */
    private void glyph(int x, int y, String key, String[] pixels, Runnable action) {
        Button button = Button.builder(Component.empty(), pressed -> action.run()).bounds(x, y, 20, 20).build();
        button.setTooltip(Tooltip.create(Component.translatable(key)));
        glyphs.add(new Glyph(this.addRenderableWidget(button), pixels));
    }

    /** The world's folder under saves/, or null if this PC has no copy of it. */
    private Path folderOf(String worldId) {
        String name = folders.get(worldId);
        return name == null ? null : dropin.mc.compat.Compat.levelPath(this.minecraft.getLevelSource(), name);
    }

    private void turnPage(int delta, int pages) {
        page = Math.floorMod(page + delta, pages);
        this.rebuildWidgets();
    }

    private void reload() {
        List<CoopWorld> found;
        try {
            found = new ArrayList<>(CoopWorlds.store().list());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not read the co-op world list", e);
            found = new ArrayList<>();
            notice = Component.translatable(K + "list_failed");
        }
        folders = CoopActions.folders(this.minecraft);
        roles.clear();
        policies.clear();
        String me = this.minecraft.getUser().getProfileId().toString();
        for (CoopWorld world : found) {
            Role role = Role.PLAYER;
            try {
                WorldPolicy.Held held = PolicyActions.held(this.minecraft, world);
                if (held != null) {
                    policies.put(world.worldId(), held);
                }
                if (CoopWorlds.store().isOwner(world.worldId())) {
                    role = Role.OWNER;
                } else if (held != null && held.policy().isAdmin(me)) {
                    role = Role.ADMIN;
                }
            } catch (Exception e) {
                DropIn.LOGGER.warn("Could not read the policy of {}", world.worldId(), e);
            }
            roles.put(world.worldId(), role);
        }
        found.sort(Comparator.comparing((CoopWorld world) -> roleOf(world.worldId()))
                .thenComparing(CoopWorld::name, String.CASE_INSENSITIVE_ORDER));
        worlds = List.copyOf(found);
        worlds.forEach(CoopStatus::refresh);
        if (worlds.stream().noneMatch(world -> world.worldId().equals(selected))) {
            selected = worlds.isEmpty() ? null : worlds.get(0).worldId();
        }
        for (CoopWorld world : worlds) {
            icons.put(world.worldId(), loadIcon(world));
        }
        refreshHeads();
    }

    /** The world's preview picture (the game's own icon.png of the local copy), or the game's default picture. */
    private FaviconTexture loadIcon(CoopWorld world) {
        FaviconTexture texture = FaviconTexture.forServer(this.minecraft.getTextureManager(), "dropin-coop/" + world.worldId());
        Path folder = folderOf(world.worldId());
        Path file = folder == null ? null : folder.resolve("icon.png");
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream stream = Files.newInputStream(file)) {
                texture.upload(NativeImage.read(stream));
                return texture;
            } catch (Throwable e) {
                DropIn.LOGGER.warn("Could not read the preview of '{}': {}", world.name(), e.toString());
            }
        }
        texture.clear();
        return texture;
    }

    private void closeIcons() {
        icons.values().forEach(icon -> {
            {
                icon.close();
            }
        });
        icons.clear();
    }

    private void refreshHeads() {
        for (CoopWorld world : worlds) {
            CoopStatus.Status status = CoopStatus.of(world.worldId());
            WorldPolicy.Held held = policies.get(world.worldId());
            List<Head> list = new ArrayList<>();
            for (Member member : world.members()) {
                Role role = member.isOwner() ? Role.OWNER
                        : held != null && held.policy().isAdmin(member.uuid()) ? Role.ADMIN : Role.PLAYER;
                list.add(new Head(member.uuid(), member.name(), role, status.isOnline(member.uuid())));
            }
            list.sort(Comparator.comparing(Head::role).thenComparing(head -> !head.online())
                    .thenComparing(Head::name, String.CASE_INSENSITIVE_ORDER));
            heads.put(world.worldId(), list);
        }
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (ticks % 10 == 0) {
            refreshHeads();
        }
        if (ticks % 600 == 0) {
            worlds.forEach(CoopStatus::refresh);
        } else if (ticks % 100 == 0) {
            // A world that shows nobody online is asked again every 5 s, so a friend who starts playing shows up
            // without waiting for the 30 s round (DESIGN 4.4).
            worlds.stream().filter(world -> CoopStatus.of(world.worldId()).kind() != CoopStatus.Kind.PLAYING)
                    .forEach(CoopStatus::refresh);
        }
    }

    // ---- actions ------------------------------------------------------------------------------

    private java.util.Optional<CoopWorld> selectedWorld() {
        return worlds.stream().filter(world -> world.worldId().equals(selected)).findFirst();
    }

    /** Play: the launch flow for a world with a local copy; a plain connect for a world known only by invite. */
    public void play() {
        CoopWorld world = selectedWorld().orElse(null);
        if (world == null) {
            return;
        }
        String folder = folders.get(world.worldId());
        if (folder != null) {
            this.minecraft.createWorldOpenFlows().loadLevel(this, folder);
            return;
        }
        // First join (DESIGN 4.4): there is nothing to open yet, so it needs a live host.
        this.minecraft.setScreen(new GenericMessageScreen(Component.translatable(K + "looking")));
        Thread thread = new Thread(() -> {
            CoopStatus.Status status = CoopStatus.lookup(world);
            this.minecraft.execute(() -> {
                if (status.kind() == CoopStatus.Kind.PLAYING) {
                    CoopLauncher.rememberHost(world, status.hostUuid(), status.hostName());
                    CoopLauncher.remember(world, this.minecraft.getUser().getProfileId().toString(), this.minecraft.getUser().getName());
                    dropin.mc.mods.JoinGate.join(this.minecraft, world, status.address(), this);
                } else {
                    this.minecraft.setScreen(new AlertScreen(() -> this.minecraft.setScreen(this), TITLE,
                            Component.translatable(K + "first_join_needs_host")));
                }
            });
        }, "dropin-coop-first-join");
        thread.setDaemon(true);
        thread.start();
    }

    private void createNew() {
        CoopActions.pendingNew = true;
        CreateWorldScreen.openFresh(this.minecraft, this);
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        CoopActions.Outcome outcome = CoopActions.importDropped(this.minecraft, files);
        notice = outcome.message();
        if (outcome.ok()) {
            this.minecraft.setScreen(new InviteScreen(this, outcome.world()));
        } else {
            this.rebuildWidgets();
        }
    }

    @Override
    public void removed() {
        closeIcons();
        super.removed();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    // ---- drawing ------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
        super.render(graphics, mouseX, mouseY, a);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 9, -1);
        if (worlds.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable(K + "empty"), this.width / 2, LIST_TOP + 20, GRAY);
            graphics.drawCenteredString(this.font, Component.translatable(K + "drop_hint"), this.width / 2, LIST_TOP + 34, GRAY);
        }
        for (Glyph glyph : glyphs) {
            pixels(graphics, glyph.pixels(), glyph.button().getX() + 5, glyph.button().getY() + 5,
                    glyph.button().isHoveredOrFocused() ? 0xFFFFFFA0 : 0xFFE0E0E0);
        }
        graphics.drawCenteredString(this.font, notice, this.width / 2, this.height - 38, -9216);
        credit(graphics);
    }

    /**
     * "made by yessirski" with the Discord logo, in the bottom-left corner; at the top-left when the page arrows
     * would be in the way.
     */
    private void credit(GuiGraphics graphics) {
        int iconWidth = DISCORD_ICON[0].length();
        int width = iconWidth + 3 + this.font.width(CREDIT);
        int x = 4;
        int y = this.height - 13;
        if (pages > 1 && x + width > this.width / 2 - 126) {
            y = 6;
        }
        for (int row = 0; row < DISCORD_ICON.length; row++) {
            for (int column = 0; column < iconWidth; column++) {
                char c = DISCORD_ICON[row].charAt(column);
                if (c != '.') {
                    graphics.fill(x + column, y + row, x + column + 1, y + row + 1, c == 'o' ? 0xFFFFFFFF : DISCORD_BLURPLE);
                }
            }
        }
        graphics.drawString(this.font, CREDIT, x + iconWidth + 3, y + 1, GRAY);
    }

    private static void pixels(GuiGraphics graphics, String[] rows, int x, int y, int color) {
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                if (rows[row].charAt(column) == '#') {
                    graphics.fill(x + column + 1, y + row + 1, x + column + 2, y + row + 2, 0xFF3F3F3F);
                    graphics.fill(x + column, y + row, x + column + 1, y + row + 1, color);
                }
            }
        }
    }

    private Component roleName(Role role) {
        return Component.translatable(K + (role == Role.OWNER ? "role.owner" : role == Role.ADMIN ? "role.admin" : "role.player"));
    }

    /** One world: preview with the game's play arrow, name, status line and the heads of its players. */
    private final class WorldRow extends Button {
        private final CoopWorld world;

        WorldRow(int x, int y, int width, CoopWorld world) {
            super(x, y, width, ROW_HEIGHT, Component.literal(world.name()), button -> { }, DEFAULT_NARRATION);
            this.world = world;
        }

        /** Keyboard: the first press selects, the next one plays. */
        @Override
        public void onPress() {
            if (world.worldId().equals(selected)) {
                play();
            } else {
                select(world.worldId());
            }
        }

        /** Mouse: a click selects; a click on the preview or a double click plays, as in the game's world list. */
        private long lastClick;

        @Override
        public void onClick(double mouseX, double mouseY) {
            long now = net.minecraft.Util.getMillis();
            boolean doubleClick = now - lastClick < 250L;
            lastClick = now;
            select(world.worldId());
            if (doubleClick || mouseX < this.getX() + 4 + ICON) {
                play();
            }
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float a) {
            int x = this.getX();
            int y = this.getY();
            boolean chosen = world.worldId().equals(selected);
            if (chosen || this.isHoveredOrFocused()) {
                graphics.fill(x, y, x + this.width, y + this.height, chosen ? 0xA0000000 : 0x60000000);
                graphics.renderOutline(x, y, this.width, this.height, chosen ? 0xFFFFFFFF : 0xFF808080);
            }
            FaviconTexture icon = icons.get(world.worldId());
            if (icon != null) {
                graphics.blit(icon.textureLocation(), x + 4, y + 7, 0.0F, 0.0F, ICON, ICON, ICON, ICON);
            }
            if (this.isHovered()) {
                boolean onIcon = mouseX < x + 4 + ICON;
                graphics.fill(x + 4, y + 7, x + 4 + ICON, y + 7 + ICON, -1601138544);
                graphics.blit(WORLD_SELECTION, x + 4, y + 7, 0.0F, onIcon ? 32.0F : 0.0F, ICON, ICON, 256, 256);
            }
            int textX = x + 4 + ICON + 5;
            int right = x + this.width - 4;
            graphics.enableScissor(textX, y, right, y + this.height);
            graphics.drawString(font, world.name(), textX, y + 4, -1);
            graphics.drawString(font, statusLine(world), textX, y + 15, GRAY);
            graphics.disableScissor();
            Role mine = roleOf(world.worldId());
            if (mine != Role.PLAYER) {
                Component tag = roleName(mine);
                int tagX = right - font.width(tag);
                graphics.fill(tagX - 3, y + 3, right + 1, y + 13, 0xC0000000);
                graphics.drawString(font, tag, tagX, y + 4, mine == Role.OWNER ? GOLD : AQUA);
            }
            heads(graphics, textX + 2, y + 29, right, mouseX, mouseY);
        }

        /** Green ring: online. Outer gold ring: the owner; outer aqua ring: an admin. Offline heads are dimmed. */
        private void heads(GuiGraphics graphics, int x, int y, int right, int mouseX, int mouseY) {
            List<Head> list = CoopWorldsScreen.this.heads(world.worldId());
            for (int i = 0; i < list.size(); i++) {
                Head head = list.get(i);
                if (x + HEAD + 2 > right - (i < list.size() - 1 ? 22 : 0)) {
                    graphics.drawString(font, Component.translatable(K + "more", list.size() - i), x, y + 2, GRAY);
                    return;
                }
                if (head.role() != Role.PLAYER) {
                    graphics.fill(x - 2, y - 2, x + HEAD + 2, y + HEAD + 2, head.role() == Role.OWNER ? GOLD : AQUA);
                }
                if (head.online()) {
                    graphics.fill(x - 1, y - 1, x + HEAD + 1, y + HEAD + 1, GREEN);
                } else if (head.role() != Role.PLAYER) {
                    graphics.fill(x - 1, y - 1, x + HEAD + 1, y + HEAD + 1, 0xFF000000);
                }
                try {
                    Faces.draw(graphics, UUID.fromString(head.uuid()), x, y, HEAD);
                } catch (IllegalArgumentException e) {
                    graphics.fill(x, y, x + HEAD, y + HEAD, 0xFF555555);
                }
                if (!head.online()) {
                    graphics.fill(x, y, x + HEAD, y + HEAD, 0x70000000);
                }
                if (mouseX >= x - 2 && mouseX < x + HEAD + 2 && mouseY >= y - 2 && mouseY < y + HEAD + 2) {
                    CoopWorldsScreen.this.setTooltipForNextRenderPass(Component.literal(head.name()).append(" — ").append(roleName(head.role()))
                            .append(", ").append(Component.translatable(K + (head.online() ? "head.online" : "head.offline"))));
                }
                x += HEAD + 6;
            }
        }
    }
}
