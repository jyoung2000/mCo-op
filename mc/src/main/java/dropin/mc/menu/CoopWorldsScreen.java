package dropin.mc.menu;

import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopLauncher;
import dropin.mc.coop.CoopWorlds;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The Co-op Worlds screen of DESIGN 4.4. */
public class CoopWorldsScreen extends Screen {
    public static final Component TITLE = Component.translatable("menu.dropin_coop.title");
    private static final String K = "text.dropin_coop.";
    private static final int ROW_HEIGHT = 22;
    private static final int LIST_TOP = 32;

    private final Screen parent;
    private List<CoopWorld> worlds = List.of();
    private Map<String, String> folders = Map.of();
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<CoopWorld> rowWorlds = new ArrayList<>();
    private String selected;
    private int page;
    private Button playButton;
    private Button inviteButton;
    private Button playersButton;
    private Button removeButton;
    private Button exportButton;
    private Component notice = Component.empty();
    private int ticks;

    public CoopWorldsScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    // ---- state the self-tests read ------------------------------------------------------------

    public List<CoopWorld> worlds() {
        return worlds;
    }

    public Component notice() {
        return notice;
    }

    public void select(String worldId) {
        this.selected = worldId;
        refreshRows();
    }

    public Component statusLine(CoopWorld world) {
        CoopStatus.Status status = CoopStatus.of(world.worldId());
        boolean hasCopy = folders.containsKey(world.worldId());
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
        reload();
        int listWidth = Math.min(320, this.width - 20);
        int left = (this.width - listWidth) / 2;
        int perPage = Math.max(1, (this.height - LIST_TOP - 96) / ROW_HEIGHT);
        int pages = Math.max(1, (worlds.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);

        rowButtons.clear();
        rowWorlds.clear();
        for (int i = 0; i < perPage && page * perPage + i < worlds.size(); i++) {
            CoopWorld world = worlds.get(page * perPage + i);
            rowWorlds.add(world);
            rowButtons.add(this.addRenderableWidget(Button.builder(Component.empty(), button -> select(world.worldId()))
                    .bounds(left, LIST_TOP + i * ROW_HEIGHT, listWidth, 20).build()));
        }
        if (pages > 1) {
            this.addRenderableWidget(Button.builder(Component.literal("<"), button -> turnPage(-1, pages))
                    .bounds(left, 8, 20, 20).build());
            this.addRenderableWidget(Button.builder(Component.literal(">"), button -> turnPage(1, pages))
                    .bounds(left + listWidth - 20, 8, 20, 20).build());
        }

        int gap = 4;
        int buttonWidth = (listWidth - 3 * gap) / 4;
        int row1 = this.height - 74;
        int row2 = this.height - 50;
        playButton = footer(K + "button.play", left, row1, buttonWidth, 0, gap, this::play);
        footer(K + "button.new", left, row1, buttonWidth, 1, gap, this::createNew);
        footer(K + "button.import", left, row1, buttonWidth, 2, gap, () -> this.minecraft.gui.setScreen(new ImportScreen(this)));
        footer(K + "button.join", left, row1, buttonWidth, 3, gap, () -> this.minecraft.gui.setScreen(new JoinInviteScreen(this)));
        inviteButton = footer(K + "button.invite", left, row2, buttonWidth, 0, gap,
                () -> selectedWorld().ifPresent(world -> this.minecraft.gui.setScreen(new InviteScreen(this, world))));
        playersButton = footer(K + "button.players", left, row2, buttonWidth, 1, gap,
                () -> selectedWorld().ifPresent(world -> this.minecraft.gui.setScreen(new PlayersScreen(this, world.worldId()))));
        removeButton = footer(K + "button.remove", left, row2, buttonWidth, 2, gap, this::confirmRemove);
        exportButton = footer(K + "button.export_key", left, row2, buttonWidth, 3, gap,
                () -> selectedWorld().ifPresent(world -> notice = CoopActions.exportOwnerKey(this.minecraft, world).message()));
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
                .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
        refreshRows();
    }

    private Button footer(String key, int left, int y, int width, int column, int gap, Runnable action) {
        return this.addRenderableWidget(Button.builder(Component.translatable(key), button -> action.run())
                .bounds(left + column * (width + gap), y, width, 20).build());
    }

    private void turnPage(int delta, int pages) {
        page = Math.floorMod(page + delta, pages);
        this.rebuildWidgets();
    }

    private void reload() {
        try {
            worlds = CoopWorlds.store().list();
        } catch (IOException | RuntimeException e) {
            DropIn.LOGGER.error("Could not read the co-op world list", e);
            worlds = List.of();
            notice = Component.translatable(K + "list_failed");
        }
        folders = CoopActions.folders(this.minecraft);
        worlds.forEach(CoopStatus::refresh);
        if (worlds.stream().noneMatch(world -> world.worldId().equals(selected))) {
            selected = worlds.isEmpty() ? null : worlds.get(0).worldId();
        }
    }

    private void refreshRows() {
        for (int i = 0; i < rowButtons.size(); i++) {
            CoopWorld world = rowWorlds.get(i);
            String prefix = world.worldId().equals(selected) ? "» " : "";
            rowButtons.get(i).setMessage(Component.literal(prefix + world.name() + " — ").append(statusLine(world)));
        }
        boolean any = selectedWorld().isPresent();
        if (playButton != null) {
            playButton.active = any;
            inviteButton.active = any;
            playersButton.active = any;
            removeButton.active = any;
            exportButton.active = any && CoopWorlds.store().isOwner(selected);
        }
    }

    private java.util.Optional<CoopWorld> selectedWorld() {
        return worlds.stream().filter(world -> world.worldId().equals(selected)).findFirst();
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (ticks % 10 == 0) {
            refreshRows();
        }
        if (ticks % 600 == 0) {
            worlds.forEach(CoopStatus::refresh);
        }
    }

    // ---- actions ------------------------------------------------------------------------------

    /** Play: the launch flow for a world with a local copy; a plain connect for a world known only by invite. */
    public void play() {
        CoopWorld world = selectedWorld().orElse(null);
        if (world == null) {
            return;
        }
        String folder = folders.get(world.worldId());
        if (folder != null) {
            this.minecraft.createWorldOpenFlows().openWorld(folder, () -> this.minecraft.gui.setScreen(this));
            return;
        }
        // First join (DESIGN 4.4): there is nothing to open yet, so it needs a live host.
        this.minecraft.gui.setScreen(new GenericMessageScreen(Component.translatable(K + "looking")));
        Thread thread = new Thread(() -> {
            CoopStatus.Status status = CoopStatus.lookup(world);
            this.minecraft.execute(() -> {
                if (status.kind() == CoopStatus.Kind.PLAYING) {
                    CoopLauncher.remember(world, status.hostUuid(), status.hostName());
                    CoopLauncher.remember(world, this.minecraft.getUser().getProfileId().toString(), this.minecraft.getUser().getName());
                    ConnectScreen.startConnecting(this, this.minecraft, ServerAddress.parseString(status.address()),
                            new ServerData(world.name(), status.address(), ServerData.Type.OTHER), false, null);
                } else {
                    this.minecraft.gui.setScreen(new AlertScreen(() -> this.minecraft.gui.setScreen(this), TITLE,
                            Component.translatable(K + "first_join_needs_host")));
                }
            });
        }, "dropin-coop-first-join");
        thread.setDaemon(true);
        thread.start();
    }

    private void createNew() {
        CoopActions.pendingNew = true;
        CreateWorldScreen.openFresh(this.minecraft, () -> this.minecraft.gui.setScreen(this));
    }

    private void confirmRemove() {
        selectedWorld().ifPresent(world -> this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                notice = CoopActions.remove(this.minecraft, world).message();
            }
            this.minecraft.gui.setScreen(this);
        }, Component.translatable(K + "remove.title", world.name()), Component.translatable(K + "remove.question"))));
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        CoopActions.Outcome outcome = CoopActions.importDropped(this.minecraft, files);
        notice = outcome.message();
        if (outcome.ok()) {
            this.minecraft.gui.setScreen(new InviteScreen(this, outcome.world()));
        } else {
            this.rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 14, -1);
        if (worlds.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable(K + "empty"), this.width / 2, LIST_TOP + 20, -6250336);
            graphics.centeredText(this.font, Component.translatable(K + "drop_hint"), this.width / 2, LIST_TOP + 34, -6250336);
        }
        graphics.centeredText(this.font, notice, this.width / 2, this.height - 88, -9216);
    }
}
