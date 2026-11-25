package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import dropin.core.world.Member;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopLauncher;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.menu.ui.DButton;
import dropin.mc.menu.ui.DScreen;
import dropin.mc.policy.PolicyActions;
import dropin.mc.policy.WorldPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.storage.ISaveFormat;
import net.minecraft.world.storage.SaveFormatOld;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Co-op Worlds screen of DESIGN 4.4: Join, Create and Import on top, then one row per world with its
 * preview, status and the heads of its players. Everything else is behind the row's info and wrench buttons.
 * <p>1.12.2 port: a {@link DScreen}; the rows, info and wrench buttons are {@link DButton}s that draw themselves.
 */
public class CoopWorldsScreen extends DScreen {
    public static final String TITLE_KEY = "menu.dropin_coop.title";
    /** The screen's name (1.12.2 port: an ITextComponent; translated on the client from the resource-pack lang). */
    public static final ITextComponent TITLE = new TextComponentTranslation(TITLE_KEY);
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
    /** 1.12.2 port: the game's world list draws its play arrow from this sheet (u 0, v 0 / 32 on the icon). */
    private static final ResourceLocation JOIN_SHEET = new ResourceLocation("textures/gui/world_selection.png");
    private static final ResourceLocation ICON_MISSING = new ResourceLocation("textures/misc/unknown_server.png");
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

    private List<CoopWorld> worlds = List.of();
    private Map<String, String> folders = Map.of();
    private final Map<String, Role> roles = new HashMap<>();
    private final Map<String, WorldPolicy.Held> policies = new HashMap<>();
    private final Map<String, List<Head>> heads = new HashMap<>();
    /** The preview texture of each world, or absent for the game's default picture. */
    private final Map<String, ResourceLocation> icons = new HashMap<>();
    private String selected;
    private int page;
    private String notice = "";
    private int ticks;
    /** A head's tooltip found while the rows were drawn; drawn on top in {@link #drawForeground}. */
    private List<String> headTooltip;

    public CoopWorldsScreen(GuiScreen parent) {
        super(parent, I18n.format(TITLE_KEY));
    }

    /** The screen's name, translated now. */
    public static String titleText() {
        return I18n.format(TITLE_KEY);
    }

    /**
     * 1.12.2 port: the folder of a world under saves/ (26.x {@code LevelStorageSource.getLevelPath}). The client's
     * save format is an AnvilSaveConverter, a SaveFormatOld over {@code gameDir/saves}.
     */
    public static Path levelPath(Minecraft mc, String folder) {
        ISaveFormat format = mc.getSaveLoader();
        Path saves = format instanceof SaveFormatOld old ? old.savesDirectory.toPath() : mc.gameDir.toPath().resolve("saves");
        return saves.resolve(folder);
    }

    // ---- state the self-tests read ------------------------------------------------------------

    /** The worlds in the order shown: owned first, then where this player is an admin, then the rest. */
    public List<CoopWorld> worlds() {
        return worlds;
    }

    public String notice() {
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

    public String statusLine(CoopWorld world) {
        return statusLine(mc, world, folders.get(world.worldId()));
    }

    /** The live status line of a world; {@code folder} is its folder name under saves/, or null without a copy. */
    public static String statusLine(Minecraft mc, CoopWorld world, String folder) {
        CoopStatus.Status status = CoopStatus.of(world.worldId());
        boolean hasCopy = folder != null;
        ITextComponent sync = dropin.mc.member.SyncClient.statusLine(world.worldId(), hasCopy ? levelPath(mc, folder) : null);
        if (sync != null) {
            return status.kind() == CoopStatus.Kind.PLAYING
                    ? I18n.format(K + "status.playing", status.hostName()) + " — " + sync.getFormattedText() : sync.getFormattedText();
        }
        return switch (status.kind()) {
            case LOOKING -> I18n.format(K + "status.looking");
            case PLAYING -> I18n.format(hasCopy ? K + "status.playing" : K + "status.playing_no_copy", status.hostName());
            case NOBODY -> I18n.format(hasCopy ? K + "status.nobody" : K + "status.no_copy");
            case UNREACHABLE -> I18n.format(hasCopy ? K + "status.unreachable" : K + "status.no_copy");
        };
    }

    // ---- layout -------------------------------------------------------------------------------

    @Override
    protected void init() {
        CoopActions.pendingNew = false;
        closeIcons();
        reload();
        int listWidth = Math.min(340, this.width - 20);
        int left = (this.width - listWidth) / 2;
        int gap = 4;
        int third = (listWidth - 2 * gap) / 3;
        // Create is the game's own Create World screen, so every option and mod of the player applies.
        top(K + "button.create", left, third, this::createNew);
        top(K + "button.join", left + third + gap, third, () -> mc.displayGuiScreen(new JoinInviteScreen(this)));
        top(K + "button.import", left + 2 * (third + gap), third, () -> mc.displayGuiScreen(new ImportScreen(this)));

        int perPage = Math.max(1, (this.height - LIST_TOP - 44) / ROW_PITCH);
        int pages = Math.max(1, (worlds.size() + perPage - 1) / perPage);
        this.pages = pages;
        page = Math.min(page, pages - 1);
        for (int i = 0; i < perPage && page * perPage + i < worlds.size(); i++) {
            CoopWorld world = worlds.get(page * perPage + i);
            int y = LIST_TOP + i * ROW_PITCH;
            boolean owner = roleOf(world.worldId()) == Role.OWNER;
            addButton(new WorldRow(left, y, listWidth - (owner ? 48 : 24), world));
            int infoX = left + listWidth - (owner ? 44 : 20);
            glyph(infoX, y + 13, K + "tooltip.info", INFO_ICON,
                    () -> mc.displayGuiScreen(new WorldInfoScreen(this, world.worldId())));
            if (owner) {
                // DESIGN 4.13, 4.16: admins, save points and the world's folders are the owner's.
                glyph(left + listWidth - 20, y + 13, K + "tooltip.settings", WRENCH_ICON,
                        () -> mc.displayGuiScreen(new WorldSettingsScreen(this, world, folderOf(world.worldId()))));
            }
        }
        button(this.width / 2 - 100, this.height - 26, 200, I18n.format("gui.back"), b -> onClose());
        if (pages > 1) {
            button(this.width / 2 - 124, this.height - 26, 20, "<", b -> turnPage(-1, pages));
            button(this.width / 2 + 104, this.height - 26, 20, ">", b -> turnPage(1, pages));
        }
    }

    private void top(String key, int x, int width, Runnable action) {
        button(x, 24, width, I18n.format(key), b -> action.run());
    }

    /** A small square button whose face is drawn from pixels, with its name as a tooltip. */
    private void glyph(int x, int y, String key, String[] pixels, Runnable action) {
        addButton(new GlyphButton(x, y, pixels, b -> action.run())).tooltip(I18n.format(key));
    }

    /** The world's folder under saves/, or null if this PC has no copy of it. */
    private Path folderOf(String worldId) {
        String name = folders.get(worldId);
        return name == null ? null : levelPath(mc, name);
    }

    private void turnPage(int delta, int pages) {
        page = Math.floorMod(page + delta, pages);
        initGui();
    }

    private void reload() {
        List<CoopWorld> found;
        try {
            found = new ArrayList<>(CoopWorlds.store().list());
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not read the co-op world list", e);
            found = new ArrayList<>();
            notice = I18n.format(K + "list_failed");
        }
        folders = CoopActions.folders(mc);
        roles.clear();
        policies.clear();
        String me = mc.getSession().getProfile().getId().toString();
        for (CoopWorld world : found) {
            Role role = Role.PLAYER;
            try {
                WorldPolicy.Held held = PolicyActions.held(mc, world);
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
            ResourceLocation icon = loadIcon(world);
            if (icon != null) {
                icons.put(world.worldId(), icon);
            }
        }
        refreshHeads();
    }

    /**
     * The world's preview picture (the game's own icon.png of the local copy), or null for the game's default picture.
     * 1.12.2 port: a DynamicTexture, as the game's world list loads it.
     */
    private ResourceLocation loadIcon(CoopWorld world) {
        Path folder = folderOf(world.worldId());
        Path file = folder == null ? null : folder.resolve("icon.png");
        if (file != null && Files.isRegularFile(file)) {
            try {
                BufferedImage image = ImageIO.read(file.toFile());
                if (image != null) {
                    ResourceLocation location = new ResourceLocation("dropin_coop",
                            "worlds/" + world.worldId().toLowerCase(Locale.ROOT) + "/icon");
                    mc.getTextureManager().loadTexture(location, new DynamicTexture(image));
                    return location;
                }
            } catch (Throwable e) {
                DropIn.LOGGER.warn("Could not read the preview of '{}': {}", world.name(), e.toString());
            }
        }
        return null;
    }

    private void closeIcons() {
        if (mc != null) {
            icons.values().forEach(icon -> mc.getTextureManager().deleteTexture(icon));
        }
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
    protected void tick() {
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
        Minecraft mc = Minecraft.getMinecraft();
        String folder = folders.get(world.worldId());
        if (folder != null) {
            // 1.12.2 port: there is no WorldOpenFlows.openWorld; the world opens the way the game's world list opens it,
            // and WorldOpenFlowsMixin hands a co-op world to CoopLauncher.intercept (Cancel comes back here).
            if (mc.getSaveLoader().canLoadWorld(folder)) {
                mc.launchIntegratedServer(folder, world.name(), null);
            }
            return;
        }
        // First join (DESIGN 4.4): there is nothing to open yet, so it needs a live host.
        mc.displayGuiScreen(new Message(this, "", I18n.format(K + "looking"), null));
        Thread thread = new Thread(() -> {
            CoopStatus.Status status = CoopStatus.lookup(world);
            mc.addScheduledTask(() -> {
                if (status.kind() == CoopStatus.Kind.PLAYING) {
                    CoopLauncher.rememberHost(world, status.hostUuid(), status.hostName());
                    CoopLauncher.remember(world, mc.getSession().getProfile().getId().toString(), mc.getSession().getUsername());
                    dropin.mc.mods.JoinGate.join(mc, world, status.address(), this);
                } else {
                    mc.displayGuiScreen(new Message(this, titleText(), I18n.format(K + "first_join_needs_host"),
                            () -> mc.displayGuiScreen(this)));
                }
            });
        }, "dropin-coop-first-join");
        thread.setDaemon(true);
        thread.start();
    }

    private void createNew() {
        CoopActions.pendingNew = true;
        mc.displayGuiScreen(new GuiCreateWorld(this));
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        CoopActions.Outcome outcome = CoopActions.importDropped(mc, files);
        notice = outcome.message().getFormattedText();
        if (outcome.ok()) {
            mc.displayGuiScreen(new InviteScreen(this, outcome.world()));
        } else {
            initGui();
        }
    }

    @Override
    public void onGuiClosed() {
        closeIcons();
        super.onGuiClosed();
    }

    // ---- drawing ------------------------------------------------------------------------------

    @Override
    protected int titleY() {
        return 9;
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        headTooltip = null;
        if (worlds.isEmpty()) {
            drawCenteredString(this.fontRenderer, I18n.format(K + "empty"), this.width / 2, LIST_TOP + 20, GRAY);
            drawCenteredString(this.fontRenderer, I18n.format(K + "drop_hint"), this.width / 2, LIST_TOP + 34, GRAY);
        }
    }

    @Override
    protected void drawForeground(int mouseX, int mouseY, float partialTicks) {
        drawCenteredString(this.fontRenderer, notice, this.width / 2, this.height - 38, 0xFFFFDC00);
        credit();
        if (headTooltip != null) {
            drawHoveringText(headTooltip, mouseX, mouseY);
        }
    }

    /**
     * "made by yessirski" with the Discord logo, in the bottom-left corner; at the top-left when the page arrows
     * would be in the way.
     */
    private void credit() {
        String credit = I18n.format("menu.dropin_coop.credit", "yessirski");
        int iconWidth = DISCORD_ICON[0].length();
        int width = iconWidth + 3 + this.fontRenderer.getStringWidth(credit);
        int x = 4;
        int y = this.height - 13;
        if (pages > 1 && x + width > this.width / 2 - 126) {
            y = 6;
        }
        for (int row = 0; row < DISCORD_ICON.length; row++) {
            for (int column = 0; column < iconWidth; column++) {
                char c = DISCORD_ICON[row].charAt(column);
                if (c != '.') {
                    drawRect(x + column, y + row, x + column + 1, y + row + 1, c == 'o' ? 0xFFFFFFFF : DISCORD_BLURPLE);
                }
            }
        }
        this.fontRenderer.drawString(credit, x + iconWidth + 3, y + 1, GRAY);
    }

    private static void pixels(String[] rows, int x, int y, int color) {
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                if (rows[row].charAt(column) == '#') {
                    drawRect(x + column + 1, y + row + 1, x + column + 2, y + row + 2, 0xFF3F3F3F);
                    drawRect(x + column, y + row, x + column + 1, y + row + 1, color);
                }
            }
        }
    }

    private static void outline(int x, int y, int width, int height, int color) {
        drawRect(x, y, x + width, y + 1, color);
        drawRect(x, y + height - 1, x + width, y + height, color);
        drawRect(x, y + 1, x + 1, y + height - 1, color);
        drawRect(x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    private static String roleName(Role role) {
        return I18n.format(K + (role == Role.OWNER ? "role.owner" : role == Role.ADMIN ? "role.admin" : "role.player"));
    }

    /** The info and wrench buttons: an empty button with a face drawn from pixels. */
    private static final class GlyphButton extends DButton {
        private final String[] face;

        GlyphButton(int x, int y, String[] face, Consumer<DButton> onPress) {
            super(x, y, 20, 20, "", onPress);
            this.face = face;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
            super.drawButton(mc, mouseX, mouseY, partialTicks);
            if (visible) {
                pixels(face, x + 5, y + 5, hovered ? 0xFFFFFFA0 : 0xFFE0E0E0);
            }
        }
    }

    /** One world: preview with the game's play arrow, name, status line and the heads of its players. */
    private final class WorldRow extends DButton {
        private final CoopWorld world;
        /** The x of the mouse click being handled, or null when the press does not come from the mouse. */
        private Integer clickX;
        private long lastClick;

        WorldRow(int x, int y, int width, CoopWorld world) {
            super(x, y, width, ROW_HEIGHT, world.name(), null);
            this.world = world;
            setOnPress(b -> activate());
        }

        /** 1.12.2 port: the click position is only known here, before the screen presses the button. */
        @Override
        public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
            boolean hit = super.mousePressed(mc, mouseX, mouseY);
            clickX = hit ? mouseX : null;
            return hit;
        }

        private void activate() {
            Integer mouseX = clickX;
            clickX = null;
            if (mouseX == null) {
                // Keyboard (and self-tests): the first press selects, the next one plays.
                if (world.worldId().equals(selected)) {
                    play();
                } else {
                    select(world.worldId());
                }
                return;
            }
            // Mouse: a click selects; a click on the preview or a double click plays, as in the game's world list.
            long now = Minecraft.getSystemTime();
            boolean doubleClick = now - lastClick < 250L;
            lastClick = now;
            select(world.worldId());
            if (doubleClick || mouseX < this.x + 4 + ICON) {
                play();
            }
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
            if (!visible) {
                return;
            }
            int x = this.x;
            int y = this.y;
            hovered = isHovered(mouseX, mouseY);
            boolean chosen = world.worldId().equals(selected);
            if (chosen || hovered) {
                drawRect(x, y, x + this.width, y + this.height, chosen ? 0xA0000000 : 0x60000000);
                outline(x, y, this.width, this.height, chosen ? 0xFFFFFFFF : 0xFF808080);
            }
            ResourceLocation icon = icons.get(world.worldId());
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getTextureManager().bindTexture(icon != null ? icon : ICON_MISSING);
            GlStateManager.enableBlend();
            drawModalRectWithCustomSizedTexture(x + 4, y + 7, 0.0F, 0.0F, ICON, ICON, ICON, ICON);
            GlStateManager.disableBlend();
            if (hovered) {
                boolean onIcon = mouseX < x + 4 + ICON;
                drawRect(x + 4, y + 7, x + 4 + ICON, y + 7 + ICON, -1601138544);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                mc.getTextureManager().bindTexture(JOIN_SHEET);
                drawModalRectWithCustomSizedTexture(x + 4, y + 7, 0.0F, onIcon ? 32.0F : 0.0F, ICON, ICON, 256.0F, 256.0F);
            }
            int textX = x + 4 + ICON + 5;
            int right = x + this.width - 4;
            // 1.12.2 port: no scissor in GUI drawing; the texts are trimmed to the row instead.
            mc.fontRenderer.drawString(mc.fontRenderer.trimStringToWidth(world.name(), right - textX), textX, y + 4, 0xFFFFFF);
            mc.fontRenderer.drawString(mc.fontRenderer.trimStringToWidth(statusLine(world), right - textX), textX, y + 15, GRAY);
            Role mine = roleOf(world.worldId());
            if (mine != Role.PLAYER) {
                String tag = roleName(mine);
                int tagX = right - mc.fontRenderer.getStringWidth(tag);
                drawRect(tagX - 3, y + 3, right + 1, y + 13, 0xC0000000);
                mc.fontRenderer.drawString(tag, tagX, y + 4, mine == Role.OWNER ? GOLD : AQUA);
            }
            heads(mc, textX + 2, y + 29, right, mouseX, mouseY);
        }

        /** Green ring: online. Outer gold ring: the owner; outer aqua ring: an admin. Offline heads are dimmed. */
        private void heads(Minecraft mc, int x, int y, int right, int mouseX, int mouseY) {
            List<Head> list = CoopWorldsScreen.this.heads(world.worldId());
            for (int i = 0; i < list.size(); i++) {
                Head head = list.get(i);
                if (x + HEAD + 2 > right - (i < list.size() - 1 ? 22 : 0)) {
                    mc.fontRenderer.drawString(I18n.format(K + "more", list.size() - i), x, y + 2, GRAY);
                    return;
                }
                if (head.role() != Role.PLAYER) {
                    drawRect(x - 2, y - 2, x + HEAD + 2, y + HEAD + 2, head.role() == Role.OWNER ? GOLD : AQUA);
                }
                if (head.online()) {
                    drawRect(x - 1, y - 1, x + HEAD + 1, y + HEAD + 1, GREEN);
                } else if (head.role() != Role.PLAYER) {
                    drawRect(x - 1, y - 1, x + HEAD + 1, y + HEAD + 1, 0xFF000000);
                }
                try {
                    Faces.draw(UUID.fromString(head.uuid()), x, y, HEAD);
                } catch (IllegalArgumentException e) {
                    drawRect(x, y, x + HEAD, y + HEAD, 0xFF555555);
                }
                if (!head.online()) {
                    drawRect(x, y, x + HEAD, y + HEAD, 0x70000000);
                }
                if (mouseX >= x - 2 && mouseX < x + HEAD + 2 && mouseY >= y - 2 && mouseY < y + HEAD + 2) {
                    headTooltip = List.of(head.name() + " — " + roleName(head.role()) + ", "
                            + I18n.format(K + (head.online() ? "head.online" : "head.offline")));
                }
                x += HEAD + 6;
            }
        }
    }

    /**
     * 1.12.2 port: stands in for 26.x's GenericMessageScreen (no button) and AlertScreen (one button that runs
     * {@code onDone}). Escape runs {@code onDone} too; without one, Escape does nothing (26.x's message screen
     * cannot be closed either: whatever showed it replaces it).
     */
    public static class Message extends DScreen {
        private final String message;
        private final Runnable onDone;

        public Message(GuiScreen parent, String title, String message, Runnable onDone) {
            super(parent, title);
            this.message = message;
            this.onDone = onDone;
        }

        @Override
        protected void init() {
            if (onDone != null) {
                button(this.width / 2 - 100, Math.min(this.height / 2 + 40, this.height - 28), 200, I18n.format("gui.done"), b -> onDone.run());
            }
        }

        @Override
        public void onClose() {
            if (onDone != null) {
                onDone.run();
            }
        }

        @Override
        protected int titleY() {
            return this.height / 2 - 40;
        }

        @Override
        protected void drawContents(int mouseX, int mouseY, float partialTicks) {
            drawWrappedCentered(message, this.width / 2, this.height / 2 - 20, Math.max(100, this.width - 50), 0xFFFFFF);
        }
    }
}
