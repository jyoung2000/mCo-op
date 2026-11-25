package dropin.mc.selftest;

import dropin.core.crypto.Ed25519;
import dropin.core.crypto.WorldKeys;
import dropin.core.invite.Invite;
import dropin.core.pkarr.RendezvousRecord;
import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.coop.HostSession;
import dropin.mc.menu.CoopActions;
import dropin.mc.menu.CoopWorldsScreen;
import dropin.mc.menu.InviteScreen;
import dropin.mc.menu.PlayersScreen;
import dropin.mc.menu.ui.DButton;
import dropin.mc.menu.ui.DScreen;
import dropin.mc.selftest.Scenarios.Context;
import dropin.tunnel.Agnos;
import dropin.tunnel.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiWorldSelection;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.ScreenShotHelper;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static dropin.mc.selftest.Scenarios.GROUND_Y;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.SHORT_TICKS;
import static dropin.mc.selftest.Scenarios.STARTUP_TICKS;
import static dropin.mc.selftest.Scenarios.WORLD_ID;
import static dropin.mc.selftest.Scenarios.WORLD_TICKS;
import static dropin.mc.selftest.Scenarios.atMainMenu;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;
import static dropin.mc.selftest.Scenarios.inWorld;
import static dropin.mc.selftest.Scenarios.sendCommand;

/**
 * The scripted scenarios of gate suite {@code m1} (MILESTONES). Development only.
 * They go through the same code as the buttons ({@link CoopActions}, {@link CoopWorldsScreen#play()}) and
 * assert on files and game state.
 */
final class M1Scenarios {
    private static final String K = "text.dropin_coop.";
    static final String WORLD_READY = "world-ready";
    static final String INVITE_FILE = "invite.txt";
    private static final String WORLD_COPY = "world-copy";
    static final String A_HOSTING = "a-hosting";

    private M1Scenarios() {}

    /** What a scenario learns as it runs. */
    static final class State {
        CoopWorld world;
        String otherName;
        long goAt;
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "menu:solo" -> menu(ctx);
            case "import-saves:solo" -> importSaves(ctx);
            case "import-zip:solo" -> importZip(ctx);
            case "import-rejects:solo" -> importRejects(ctx);
            case "remove:solo" -> remove(ctx);
            case "owner-invite:solo" -> ownerInvite(ctx);
            case "drop-in:a" -> dropInA(ctx, false);
            case "drop-in:b" -> dropInB(ctx, false);
            case "players-tab:a" -> dropInA(ctx, true);
            case "players-tab:b" -> dropInB(ctx, true);
            case "host-swap-own-inventory:a" -> swapA(ctx);
            case "host-swap-own-inventory:b" -> swapB(ctx);
            case "race:a" -> race(ctx, true);
            case "race:b" -> race(ctx, false);
            default -> null;
        };
    }

    // ---- building blocks --------------------------------------------------------------------------

    static StepRunner base(Context ctx) {
        return new StepRunner()
                .action("configure tunnel", () -> {
                    // The m1, m2 and m2b scenarios were written for a plain quit: the host leaves and the members
                    // are dropped. They keep proving that (owner's decision, 2026-11-17); the m3 scenarios turn
                    // this development-only switch off again and cover the handoff.
                    System.setProperty("dropin.dev.noHandoff", "true");
                    if (!ctx.online()) {
                        // DESIGN 4.12: self-tests run with the e4mc tunnel disabled. Not written to the config file.
                        Config.INSTANCE.hostEnabled.setOverride(false);
                    }
                })
                .step("main menu", STARTUP_TICKS, () -> atMainMenu(ctx.mc()));
    }

    static StepRunner createWorld(StepRunner runner, Context ctx) {
        Minecraft mc = ctx.mc();
        return runner
                .action("create world", () -> Scenarios.createFlatWorld(mc))
                .step("world loaded", WORLD_TICKS, () -> inWorld(mc) && mc.getIntegratedServer() != null);
    }

    static StepRunner quitToTitle(StepRunner runner, Context ctx) {
        Minecraft mc = ctx.mc();
        return runner
                .action("save and quit", () -> dropin.mc.compat.Compat.disconnectFromWorld(mc, dropin.mc.compat.Compat.DEFAULT_QUIT_MESSAGE))
                .step("world closed", WORLD_TICKS, () -> mc.getIntegratedServer() == null && mc.world == null)
                .action("title screen", () -> mc.displayGuiScreen(new GuiMainMenu()))
                .step("back at the main menu", SHORT_TICKS, () -> atMainMenu(mc));
    }

    static StepRunner importWorld(StepRunner runner, Context ctx, State state) {
        return runner.action("import from saves", () -> {
            CoopActions.Outcome outcome = CoopActions.importFromSaves(ctx.mc(), WORLD_ID, true);
            require(outcome.ok(), "import failed: " + outcome.message().getUnformattedText());
            expectKey(outcome, "import.done");
            state.world = outcome.world();
        });
    }

    /** Opens the Co-op Worlds screen, selects the world and presses Play: the real launch flow. */
    static void pressPlay(Minecraft mc, State state) {
        CoopWorldsScreen screen = new CoopWorldsScreen(new GuiMainMenu());
        // 1.12.2: displayGuiScreen initialises the screen (initGui) before it returns.
        mc.displayGuiScreen(screen);
        require(screen.worlds().stream().anyMatch(world -> world.worldId().equals(state.world.worldId())),
                "the Co-op Worlds screen does not list the world");
        screen.select(state.world.worldId());
        screen.play();
    }

    private static long menuShownAt;
    private static long importShownAt;

    static boolean hosting(Context ctx) {
        Minecraft mc = ctx.mc();
        IntegratedServer server = mc.getIntegratedServer();
        HostSession session = HostSession.current();
        // 1.12.2 port: 26.x isPublished() is getPublic().
        return inWorld(mc) && server != null && server.getPublic() && session != null
                && session.announcedState().equals(RendezvousRecord.HOST)
                && (!ctx.online() || !session.announcedAddr().isEmpty());
    }

    static boolean joinedRemote(Minecraft mc) {
        failIfDisconnected(mc);
        return inWorld(mc) && mc.getIntegratedServer() == null;
    }

    // ---- menu -------------------------------------------------------------------------------------

    private static StepRunner menu(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        return base(ctx)
                .action("know one co-op world", () -> {
                    CoopActions.Outcome outcome = CoopActions.joinWithInvite(mc, randomInvite().encode());
                    require(outcome.ok(), "could not register a world: " + outcome.message().getUnformattedText());
                    state.world = outcome.world();
                })
                .action("fresh title screen", () -> mc.displayGuiScreen(new GuiMainMenu()))
                .step("title screen has the button", SHORT_TICKS, () -> titleButton(mc) != null)
                .action("button overlaps nothing", () -> {
                    DButton ours = titleButton(mc);
                    require(ours.x >= 0 && ours.x + ours.width <= mc.currentScreen.width, "button is off screen");
                    for (GuiButton other : buttonsOf(mc.currentScreen)) {
                        if (other != ours && other.visible && overlaps(ours, other)) {
                            throw new IllegalStateException("Co-op Worlds button overlaps '" + other.displayString + "'");
                        }
                    }
                })
                .action("press it", () -> titleButton(mc).press())
                .step("Co-op Worlds screen opened", SHORT_TICKS, () -> mc.currentScreen instanceof CoopWorldsScreen)
                .action("screen lists the world", () -> {
                    CoopWorldsScreen screen = (CoopWorldsScreen) mc.currentScreen;
                    require(screen.worlds().stream().anyMatch(world -> world.worldId().equals(state.world.worldId())),
                            "the known co-op world is not listed");
                    require(buttonContaining(screen, state.world.name()) != null, "no row shows the world's name");
                    // The decluttered layout: three buttons on top, and per world an info button. This world was
                    // joined by invite, so this player is not its owner: no wrench, and nobody has joined it yet.
                    for (String key : new String[]{"button.join", "button.create", "button.import"}) {
                        String label = I18n.format("text.dropin_coop." + key);
                        require(buttonContaining(screen, label) != null, "no '" + label + "' button");
                    }
                    long plain = screen.buttons().size();
                    require(plain == 3 + 2 + 1, "the screen has " + plain + " buttons, expected Join, Create, Import, one row, info, Back");
                    require(screen.roleOf(state.world.worldId()) == CoopWorldsScreen.Role.PLAYER, "a world joined by invite is shown as owned");
                    require(screen.heads(state.world.worldId()).stream().noneMatch(CoopWorldsScreen.Head::online),
                            "a head is shown online while nobody hosts");
                    require(!CoopWorldsScreen.titleText().equals(CoopWorldsScreen.TITLE_KEY), "the language file is not loaded");
                    menuShownAt = System.currentTimeMillis();
                })
                // A picture of the screen for the person reading the results; nothing is asserted on it.
                .step("the screen was drawn", SHORT_TICKS, () -> System.currentTimeMillis() - menuShownAt > 2500)
                .action("save a picture", () -> screenshot(mc, "dropin-coop-menu.png"))
                .step("picture saved", SHORT_TICKS, () -> Files.isRegularFile(
                        mc.gameDir.toPath().resolve("screenshots").resolve("dropin-coop-menu.png")));
    }

    private static DButton titleButton(Minecraft mc) {
        return mc.currentScreen instanceof GuiMainMenu screen ? buttonContaining(screen, CoopWorldsScreen.titleText()) : null;
    }

    /**
     * The first of DropIn's buttons on a screen whose label contains the text. 1.12.2 port: 26.x searched every
     * {@code Button}; DropIn's own buttons are all {@link DButton}s here (vanilla's are plain {@code GuiButton}s).
     */
    static DButton buttonContaining(GuiScreen screen, String text) {
        for (GuiButton button : buttonsOf(screen)) {
            if (button instanceof DButton d && d.label() != null && d.label().contains(text)) {
                return d;
            }
        }
        return null;
    }

    /**
     * Every button of a screen. 1.12.2 port: {@code GuiScreen.buttonList} is protected; a {@link DScreen} hands it
     * out, other screens (the title screen, the world list) are read by reflection (development only).
     */
    static List<GuiButton> buttonsOf(GuiScreen screen) {
        if (screen == null) {
            return List.of();
        }
        if (screen instanceof DScreen d) {
            return d.buttons();
        }
        try {
            Field field = GuiScreen.class.getDeclaredField("buttonList");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<GuiButton> list = (List<GuiButton>) field.get(screen);
            return new ArrayList<>(list);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read the buttons of " + screen.getClass().getName(), e);
        }
    }

    private static boolean overlaps(GuiButton a, GuiButton b) {
        return a.x < b.x + b.width && b.x < a.x + a.width
                && a.y < b.y + b.height && b.y < a.y + a.height;
    }

    /** 1.12.2 port: 26.x {@code Screenshot.grab}; the picture lands in {@code <gameDir>/screenshots/<name>}. */
    private static void screenshot(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.gameDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
    }

    // ---- import, remove, owner --------------------------------------------------------------------

    private static StepRunner importSaves(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = importWorld(quitToTitle(createWorld(base(ctx), ctx), ctx), ctx, state);
        int[] waited = {0};
        return runner
                .action("files and invite", () -> {
                    CoopWorld world = state.world;
                    Path folder = CoopActions.levelPath(mc, WORLD_ID);
                    require(Files.isRegularFile(folder.resolve("dropin/world.json")), "no dropin/world.json");
                    require(CoopWorldStore.readMarker(folder).orElse("").equals(world.worldId()), "marker names another world");
                    require(Files.isRegularFile(worldsDir().resolve(world.worldId() + ".json")), "no key file");
                    // 1.12.2 port: 26.x getBackupPath() is <gameDir>/backups here, where CoopActions writes the backup.
                    try (Stream<Path> backups = Files.list(mc.gameDir.toPath().resolve("backups"))) {
                        require(backups.anyMatch(file -> file.getFileName().toString().contains(WORLD_ID)), "no backup was made");
                    }
                    Invite decoded = Invite.decode(world.invite().encode());
                    require(WorldKeys.fromSeed(decoded.seed()).worldId().equals(world.worldId()), "invite decodes to another world id");
                    require(CoopWorlds.store().list().stream().anyMatch(w -> w.worldId().equals(world.worldId())), "not in the co-op list");
                })
                .action("co-op screen lists it", () -> {
                    CoopWorldsScreen screen = new CoopWorldsScreen(new GuiMainMenu());
                    mc.displayGuiScreen(screen);
                    require(buttonContaining(screen, state.world.name()) != null, "the imported world is not on the screen");
                    // The importer is the owner: the row has the wrench too, and the owner's head with the owner role.
                    long plain = screen.buttons().size();
                    require(plain == 3 + 3 + 1, "the screen has " + plain + " buttons, expected Join, Create, Import, one row, info, wrench, Back");
                    require(screen.roleOf(state.world.worldId()) == CoopWorldsScreen.Role.OWNER, "the importer is not shown as the owner");
                    List<CoopWorldsScreen.Head> heads = screen.heads(state.world.worldId());
                    require(heads.size() == 1 && heads.get(0).role() == CoopWorldsScreen.Role.OWNER
                            && heads.get(0).uuid().equals(mc.getSession().getProfile().getId().toString()) && !heads.get(0).online(),
                            "heads: " + heads);
                    importShownAt = System.currentTimeMillis();
                })
                // A picture of the owner's row for the person reading the results; nothing is asserted on it.
                .step("the screen was drawn", SHORT_TICKS, () -> System.currentTimeMillis() - importShownAt > 2500)
                .action("save a picture", () -> screenshot(mc, "dropin-coop-menu-owner.png"))
                .step("picture saved", SHORT_TICKS, () -> Files.isRegularFile(
                        mc.gameDir.toPath().resolve("screenshots").resolve("dropin-coop-menu-owner.png")))
                .action("still listed", () -> {
                })
                .action("singleplayer list", () -> mc.displayGuiScreen(new GuiWorldSelection(new GuiMainMenu())))
                // long enough for the list to load its entries, which is where the badge is added
                .step("singleplayer list still works and has the Co-op Worlds button", SHORT_TICKS, () -> ++waited[0] >= 100
                        && mc.currentScreen instanceof GuiWorldSelection && CoopActions.isCoopLevel(mc, WORLD_ID)
                        && buttonContaining(mc.currentScreen, CoopWorldsScreen.titleText()) != null);
    }

    private static StepRunner importZip(Context ctx) {
        Minecraft mc = ctx.mc();
        Path zip = ctx.sharedDir().resolve("zipped-world.zip");
        return quitToTitle(createWorld(base(ctx), ctx), ctx)
                .action("zip the world", () -> zipTree(CoopActions.levelPath(mc, WORLD_ID), zip))
                .action("open the Co-op Worlds screen", () -> mc.displayGuiScreen(new CoopWorldsScreen(new GuiMainMenu())))
                .action("drop the zip on it", () -> ((DScreen) mc.currentScreen).onFilesDrop(List.of(zip)))
                .step("invite is shown", SHORT_TICKS, () -> mc.currentScreen instanceof InviteScreen)
                .action("extracted and imported", () -> {
                    CoopWorld world = ((InviteScreen) mc.currentScreen).world();
                    Path folder = CoopActions.savesDir(mc).resolve("zipped-world");
                    require(Files.isRegularFile(folder.resolve("level.dat")), "the zip was not extracted into saves/zipped-world");
                    require(CoopWorldStore.readMarker(folder).orElse("").equals(world.worldId()), "the extracted world is not marked");
                    require(CoopActions.folders(mc).getOrDefault(world.worldId(), "").equals("zipped-world"), "world is not listed with its folder");
                    require(CoopWorldStore.readMarker(CoopActions.levelPath(mc, WORLD_ID)).isEmpty(), "the original world was changed");
                });
    }

    private static StepRunner importRejects(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = createWorld(base(ctx), ctx)
                .action("an open world is refused", () -> {
                    CoopActions.Outcome outcome = CoopActions.importFromSaves(mc, WORLD_ID, true);
                    expectRefused(outcome, "import.open");
                    require(CoopWorldStore.readMarker(CoopActions.levelPath(mc, WORLD_ID)).isEmpty(), "an open world was marked");
                });
        return importWorld(quitToTitle(runner, ctx), ctx, state)
                .action("an already co-op world is refused", () ->
                        expectRefused(CoopActions.importFromSaves(mc, WORLD_ID, true), "import.already_coop"))
                .action("a path-escaping zip is refused", () -> {
                    Path zip = ctx.sharedDir().resolve("evil.zip");
                    try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
                        for (String name : new String[]{"level.dat", "../evil.txt"}) {
                            zos.putNextEntry(new ZipEntry(name));
                            zos.write("x".getBytes(StandardCharsets.UTF_8));
                            zos.closeEntry();
                        }
                    }
                    expectRefused(CoopActions.importDropped(mc, List.of(zip)), "import.zip_escape");
                    Path saves = CoopActions.savesDir(mc);
                    require(!Files.exists(saves.resolve("../evil.txt")), "the escaping entry was written");
                    require(!Files.exists(saves.resolve("evil")), "a folder was created for the refused zip");
                })
                .action("a folder without level.dat is refused", () -> {
                    Path folder = Files.createDirectories(ctx.sharedDir().resolve("not-a-world"));
                    Files.writeString(folder.resolve("readme.txt"), "hello");
                    expectRefused(CoopActions.importDropped(mc, List.of(folder)), "import.no_level_dat");
                    require(!Files.exists(CoopActions.savesDir(mc).resolve("not-a-world")), "the folder was copied into saves");
                });
    }

    private static StepRunner remove(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        return importWorld(quitToTitle(createWorld(base(ctx), ctx), ctx), ctx, state)
                .action("remove", () -> {
                    CoopActions.Outcome outcome = CoopActions.remove(mc, state.world);
                    require(outcome.ok(), "remove failed: " + outcome.message().getUnformattedText());
                    String id = state.world.worldId();
                    require(!Files.exists(CoopActions.levelPath(mc, WORLD_ID).resolve("dropin")), "dropin/ is still there");
                    require(!Files.exists(worldsDir().resolve(id + ".json")), "the key file is still there");
                    require(!Files.exists(worldsDir().resolve(id + ".owner.key")), "the owner key is still there");
                    require(CoopWorlds.store().list().isEmpty(), "the world is still in the co-op list");
                })
                // 1.12.2 port: 26.x WorldOpenFlows.openWorld is launchIntegratedServer (WorldOpenFlowsMixin hooks it).
                .action("open it from singleplayer", () -> mc.launchIntegratedServer(WORLD_ID, WORLD_ID, null))
                .step("world loaded", WORLD_TICKS, () -> inWorld(mc) && mc.getIntegratedServer() != null)
                .action("it is plain singleplayer", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    require(!server.getPublic(), "the world was opened to LAN");
                    require(HostSession.current() == null, "a co-op host session is running");
                    require(!CoopWorlds.isCoopServer(server), "the world still counts as co-op");
                });
    }

    private static StepRunner ownerInvite(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        return importWorld(quitToTitle(createWorld(base(ctx), ctx), ctx), ctx, state)
                .action("invite carries the owner key's fingerprint", () -> {
                    CoopWorld world = state.world;
                    Invite decoded = Invite.decode(world.invite().encode());
                    require(WorldKeys.fromSeed(decoded.seed()).worldId().equals(world.worldId()), "invite decodes to another world id");
                    require(Files.isRegularFile(worldsDir().resolve(world.worldId() + ".owner.key")), "no owner key file");
                    byte[] ownerPublic = Ed25519.publicKeyBytes(CoopWorlds.store().ownerKey(world.worldId()).orElseThrow().getPublic());
                    require(decoded.matchesOwner(ownerPublic), "the invite's fingerprint is not that of the owner key on disk");
                    require(CoopWorlds.store().isOwner(world.worldId()), "the importer is not the owner");
                })
                .action("a world joined by invite has no owner key", () -> {
                    CoopActions.Outcome outcome = CoopActions.joinWithInvite(mc, randomInvite().encode());
                    require(outcome.ok(), "join with invite failed: " + outcome.message().getUnformattedText());
                    String id = outcome.world().worldId();
                    require(!Files.exists(worldsDir().resolve(id + ".owner.key")), "an owner key file was written for a joined world");
                    require(!CoopWorlds.store().isOwner(id), "the joiner counts as owner");
                    expectRefused(CoopActions.exportOwnerKey(mc, outcome.world()), "owner.not_owner");
                    expectRefused(CoopActions.joinWithInvite(mc, "dropin1-AAAAA-AAAAA"), "invite.bad");
                })
                .action("export owner key", () -> {
                    CoopActions.Outcome outcome = CoopActions.exportOwnerKey(mc, state.world);
                    require(outcome.ok(), "export failed: " + outcome.message().getUnformattedText());
                    Path exported = mc.gameDir.toPath().resolve("dropin-owner-key-" + state.world.worldId().substring(0, 8) + ".key");
                    require(Files.readString(exported).equals(Files.readString(worldsDir().resolve(state.world.worldId() + ".owner.key"))),
                            "the exported key differs from the key on disk");
                    Files.delete(exported);
                });
    }

    // ---- two players: shared setup ------------------------------------------------------------------

    /** A: makes the world, imports it, and leaves the invite and a copy of the folder for B. */
    private static StepRunner setupA(Context ctx, State state) {
        Minecraft mc = ctx.mc();
        return importWorld(quitToTitle(createWorld(base(ctx), ctx), ctx), ctx, state)
                .action("share invite and world copy", () -> {
                    copyTree(CoopActions.levelPath(mc, WORLD_ID), ctx.sharedDir().resolve(WORLD_COPY));
                    ctx.writeShared(INVITE_FILE, state.world.invite().encode());
                    ctx.writeShared(WORLD_READY, "ok");
                });
    }

    /** B: joins with the invite and brings in the copy of the folder "by hand" (dropped on the screen). */
    private static StepRunner setupB(Context ctx, State state) {
        Minecraft mc = ctx.mc();
        return base(ctx)
                .step("A shared the world", STARTUP_TICKS + WORLD_TICKS, () -> ctx.sharedExists(WORLD_READY))
                .action("join with invite", () -> {
                    CoopActions.Outcome outcome = CoopActions.joinWithInvite(mc, ctx.readShared(INVITE_FILE));
                    require(outcome.ok(), "join with invite failed: " + outcome.message().getUnformattedText());
                    state.world = outcome.world();
                    require(!CoopWorlds.store().isOwner(state.world.worldId()), "B has an owner key");
                })
                .action("bring in the world copy", () -> {
                    CoopActions.Outcome outcome = CoopActions.importDropped(mc, List.of(ctx.sharedDir().resolve(WORLD_COPY)));
                    require(outcome.ok(), "could not attach the copy: " + outcome.message().getUnformattedText());
                    expectKey(outcome, "import.copy_attached");
                    require(outcome.world().worldId().equals(state.world.worldId()), "the copy belongs to another world");
                });
    }

    // ---- drop-in, players-tab -------------------------------------------------------------------------

    private static StepRunner dropInA(Context ctx, boolean playersTab) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = setupA(ctx, state)
                .action("press Play", () -> pressPlay(mc, state))
                .step("hosting, announced", WORLD_TICKS, () -> hosting(ctx))
                .action("force-load probe chunk", () -> Scenarios.forceLoadChunk(mc.getIntegratedServer(), 0, 0))
                .action("tell B", () -> ctx.writeShared(A_HOSTING, "ok"))
                .step("B dropped in", PEER_TICKS, () -> mc.getIntegratedServer().getCurrentPlayerCount() == 2)
                .step("sees B", SHORT_TICKS, () -> mc.world != null && mc.world.playerEntities.size() == 2)
                .step("reads block placed by B", SHORT_TICKS,
                        () -> Scenarios.blockAt(mc.getIntegratedServer(), Scenarios.PROBE_POS) == Blocks.GOLD_BLOCK);
        if (playersTab) {
            playersTabChecks(runner, mc, state, true);
        }
        return runner
                .action("tell B", () -> ctx.writeShared(Scenarios.HOST_DONE, "ok"))
                .step("B finished", SHORT_TICKS, () -> ctx.sharedExists(Scenarios.PEER_DONE));
    }

    private static StepRunner dropInB(Context ctx, boolean playersTab) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = setupB(ctx, state)
                .step("A is hosting", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(A_HOSTING))
                .action("press Play", () -> pressPlay(mc, state))
                .step("landed in A's session", PEER_TICKS, () -> joinedRemote(mc))
                .step("sees A", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return mc.world != null && mc.world.playerEntities.size() == 2;
                })
                .action("place block", () -> sendCommand(mc, Scenarios.PROBE_COMMAND));
        if (playersTab) {
            playersTabChecks(runner, mc, state, false);
        }
        return runner
                .step("A read the block", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists(Scenarios.HOST_DONE);
                })
                .action("tell A", () -> ctx.writeShared(Scenarios.PEER_DONE, "ok"));
    }

    /** Gate players-tab: both names listed on both sides, with A (the importer) marked as owner. */
    private static void playersTabChecks(StepRunner runner, Minecraft mc, State state, boolean iAmA) {
        runner.step("Players tab lists both names", SHORT_TICKS, () -> {
                    String me = mc.getSession().getUsername();
                    String other = null;
                    for (EntityPlayer player : mc.world.playerEntities) {
                        if (!player.getGameProfile().getName().equals(me)) {
                            other = player.getGameProfile().getName();
                        }
                    }
                    if (other == null) {
                        return false;
                    }
                    state.otherName = other;
                    List<PlayersScreen.Row> rows = PlayersScreen.rowsOf(state.world.worldId());
                    final String otherName = other;
                    return rows.stream().anyMatch(row -> row.name().equals(me))
                            && rows.stream().anyMatch(row -> row.name().equals(otherName));
                })
                .action("A is marked as owner on the owner's PC", () -> {
                    if (!iAmA) {
                        // MILESTONES m1 players-tab: B showing A as owner is gate m2b `owner-known-to-members`.
                        return;
                    }
                    String nameOfA = mc.getSession().getUsername();
                    List<PlayersScreen.Row> rows = PlayersScreen.rowsOf(state.world.worldId());
                    for (PlayersScreen.Row row : rows) {
                        if (row.owner() != row.name().equals(nameOfA)) {
                            throw new IllegalStateException("Players tab: " + row.name() + " owner=" + row.owner()
                                    + ", expected only " + nameOfA + " to be marked as owner");
                        }
                    }
                });
    }

    // ---- host-swap-own-inventory ------------------------------------------------------------------------

    private static StepRunner swapA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        return setupA(ctx, state)
                .action("press Play", () -> pressPlay(mc, state))
                .step("hosting, announced", WORLD_TICKS, () -> hosting(ctx))
                .action("tell B", () -> ctx.writeShared(A_HOSTING, "ok"))
                .step("B dropped in", PEER_TICKS, () -> mc.getIntegratedServer().getCurrentPlayerCount() == 2)
                .action("give and move", () -> {
                    sendCommand(mc, "give @s minecraft:diamond 3");
                    // 1.12.2 port: the flat world's ground is at y 4 (26.x: -60).
                    sendCommand(mc, "tp @s 100 " + GROUND_Y + " 100");
                })
                .step("A has its item at its place", SHORT_TICKS, () -> has(mc, Items.DIAMOND) && near(mc, 100, 100))
                .step("B has its item at its place", SHORT_TICKS, () -> ctx.sharedExists("b-marked"))
                .action("A quits", () -> dropin.mc.compat.Compat.disconnectFromWorld(mc, dropin.mc.compat.Compat.DEFAULT_QUIT_MESSAGE))
                .step("world closed", WORLD_TICKS, () -> mc.getIntegratedServer() == null && mc.world == null)
                .action("title screen", () -> mc.displayGuiScreen(new GuiMainMenu()))
                .action("copy the world folder to B", () -> {
                    copyTree(CoopActions.levelPath(mc, WORLD_ID), ctx.sharedDir().resolve("world-copy-2"));
                    ctx.writeShared("swap-ready", "ok");
                })
                .step("B is hosting", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists("b-hosting"))
                .action("press Play", () -> pressPlay(mc, state))
                .step("landed in B's session", PEER_TICKS, () -> joinedRemote(mc))
                .step("A has A's item at A's position", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return has(mc, Items.DIAMOND) && near(mc, 100, 100);
                })
                .action("A does not have B's item", () -> require(!has(mc, Items.EMERALD), "A got B's inventory"))
                .action("tell B", () -> ctx.writeShared("a-done", "ok"));
    }

    private static StepRunner swapB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        return setupB(ctx, state)
                .step("A is hosting", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(A_HOSTING))
                .action("press Play", () -> pressPlay(mc, state))
                .step("landed in A's session", PEER_TICKS, () -> joinedRemote(mc))
                .action("give and move", () -> {
                    sendCommand(mc, "give @s minecraft:emerald 3");
                    // 1.12.2 port: the flat world's ground is at y 4 (26.x: -60).
                    sendCommand(mc, "tp @s -100 " + GROUND_Y + " -100");
                })
                .step("B has its item at its place", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return has(mc, Items.EMERALD) && near(mc, -100, -100);
                })
                .action("tell A", () -> ctx.writeShared("b-marked", "ok"))
                .step("A quit", PEER_TICKS, () -> mc.currentScreen instanceof GuiDisconnected
                        || (mc.world == null && mc.currentScreen instanceof GuiMainMenu))
                .action("title screen", () -> mc.displayGuiScreen(new GuiMainMenu()))
                .step("A copied the world folder", WORLD_TICKS, () -> ctx.sharedExists("swap-ready"))
                .action("replace the local copy", () -> {
                    Path local = CoopActions.levelPath(mc, CoopActions.folders(mc).get(state.world.worldId()));
                    deleteTree(local);
                    copyTree(ctx.sharedDir().resolve("world-copy-2"), local);
                })
                .action("press Play", () -> pressPlay(mc, state))
                .step("hosting, announced", WORLD_TICKS, () -> hosting(ctx))
                .action("B has B's item at B's position", () -> {
                    require(has(mc, Items.EMERALD), "B hosts without B's item (loaded someone else's player data?)");
                    require(near(mc, -100, -100), "B hosts at " + mc.player.posX + "," + mc.player.posZ + ", not at B's position");
                    require(!has(mc, Items.DIAMOND), "B got A's inventory");
                })
                .action("tell A", () -> ctx.writeShared("b-hosting", "ok"))
                .step("A dropped in", PEER_TICKS, () -> mc.getIntegratedServer().getCurrentPlayerCount() == 2)
                .step("A finished", SHORT_TICKS, () -> ctx.sharedExists("a-done"));
    }

    private static boolean has(Minecraft mc, Item item) {
        return Scenarios.holds(mc.player, stack -> stack.getItem() == item);
    }

    private static boolean near(Minecraft mc, double x, double z) {
        return mc.player != null && Math.abs(mc.player.posX - x) < 2 && Math.abs(mc.player.posZ - z) < 2;
    }

    // ---- race -------------------------------------------------------------------------------------------

    private static StepRunner race(Context ctx, boolean iAmA) {
        Minecraft mc = ctx.mc();
        State state = new State();
        String mine = iAmA ? "race-a" : "race-b";
        String theirs = iAmA ? "race-b" : "race-a";
        StepRunner runner;
        if (iAmA) {
            runner = setupA(ctx, state)
                    .step("B is ready", STARTUP_TICKS, () -> ctx.sharedExists("b-ready"))
                    .action("set the start time", () -> ctx.writeShared("go", Long.toString(System.currentTimeMillis() + 3000)));
        } else {
            runner = setupB(ctx, state)
                    .action("ready", () -> ctx.writeShared("b-ready", "ok"));
        }
        return runner
                .step("start time known", STARTUP_TICKS, () -> {
                    if (!ctx.sharedExists("go")) {
                        return false;
                    }
                    state.goAt = Long.parseLong(ctx.readShared("go"));
                    return true;
                })
                .step("start time reached", SHORT_TICKS, () -> System.currentTimeMillis() >= state.goAt)
                .action("press Play", () -> {
                    DropIn.LOGGER.info("race: pressing Play {} ms after the start time", System.currentTimeMillis() - state.goAt);
                    pressPlay(mc, state);
                })
                .step("in the world", WORLD_TICKS + PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return inWorld(mc);
                })
                .action("report role", () -> ctx.writeShared(mine, mc.getIntegratedServer() != null ? "host" : "join"))
                .step("other reported", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(theirs))
                .action("exactly one hosts", () -> {
                    String myRole = ctx.readShared(mine);
                    String theirRole = ctx.readShared(theirs);
                    require(!myRole.equals(theirRole), "both ended up as '" + myRole + "'");
                })
                .step("both in one session", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return mc.world != null && mc.world.playerEntities.size() == 2;
                })
                .action("tell the other", () -> ctx.writeShared(mine + "-ok", "ok"))
                .step("other finished", SHORT_TICKS, () -> ctx.sharedExists(theirs + "-ok"));
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static Path worldsDir() {
        return Agnos.configDir().resolve("dropin").resolve("worlds");
    }

    static Invite randomInvite() {
        SecureRandom random = new SecureRandom();
        byte[] seed = new byte[32];
        byte[] fingerprint = new byte[16];
        random.nextBytes(seed);
        random.nextBytes(fingerprint);
        return new Invite(seed, fingerprint);
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void expectKey(CoopActions.Outcome outcome, String key) {
        require(outcome.key().equals(K + key), "expected message " + key + ", got " + outcome.key());
        require(!outcome.message().getUnformattedText().startsWith(K), "message " + outcome.key() + " has no translation");
    }

    private static void expectRefused(CoopActions.Outcome outcome, String key) {
        require(!outcome.ok(), "expected a refusal (" + key + "), but it was accepted");
        expectKey(outcome, key);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.getFileName().toString().equals("session.lock")) {
                    Files.copy(file, to.resolve(from.relativize(file).toString()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void zipTree(Path folder, Path zip) throws IOException {
        Files.createDirectories(zip.getParent());
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out);
             Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (file.getFileName().toString().equals("session.lock")) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(folder.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
    }
}
