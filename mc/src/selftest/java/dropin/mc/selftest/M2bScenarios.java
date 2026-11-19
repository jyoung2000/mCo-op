package dropin.mc.selftest;

import dropin.core.crypto.Ed25519;
import dropin.core.mods.HelperLauncher;
import dropin.core.mods.ModDiff;
import dropin.core.mods.ModManifest;
import dropin.core.mods.SwapHelper;
import dropin.core.mods.SwapPlan;
import dropin.core.policy.Policy;
import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.host.HostSync;
import dropin.mc.menu.CoopActions;
import dropin.mc.menu.ModConsentScreen;
import dropin.mc.menu.ModsScreen;
import dropin.mc.menu.PlayersScreen;
import dropin.mc.menu.PolicyUpdateScreen;
import dropin.mc.mods.ModInstall;
import dropin.mc.policy.DevHooks;
import dropin.mc.policy.PolicyActions;
import dropin.mc.policy.WorldPolicy;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import dropin.tunnel.Agnos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static dropin.mc.selftest.M1Scenarios.A_HOSTING;
import static dropin.mc.selftest.M1Scenarios.INVITE_FILE;
import static dropin.mc.selftest.M1Scenarios.WORLD_READY;
import static dropin.mc.selftest.M1Scenarios.base;
import static dropin.mc.selftest.M1Scenarios.createWorld;
import static dropin.mc.selftest.M1Scenarios.hosting;
import static dropin.mc.selftest.M1Scenarios.importWorld;
import static dropin.mc.selftest.M1Scenarios.joinedRemote;
import static dropin.mc.selftest.M1Scenarios.pressPlay;
import static dropin.mc.selftest.M1Scenarios.quitToTitle;
import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.SHORT_TICKS;
import static dropin.mc.selftest.Scenarios.STARTUP_TICKS;
import static dropin.mc.selftest.Scenarios.WORLD_TICKS;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;

/**
 * The M2b gate scenarios (docs/MILESTONES.md, suite m2b): owner policy and mod sync. Development only.
 * <p>
 * Test mods are tiny jars built by the test build ({@code :<loader>:testmodJars}): X and Y each add one item,
 * Z adds nothing and only sets the system property {@code dropin.testmod.z}. The orchestrator puts them into a
 * client's {@code mods/} folder before the first start. A is always the owner.
 */
final class M2bScenarios {
    private static final String K = "text.dropin_coop.";
    static final String ITEM_X = "dropin_testmod_x:item";
    static final String ITEM_Y = "dropin_testmod_y:item";
    static final String Z_PROPERTY = "dropin.testmod.z";
    private static final String JAR_X = "dropin-testmod-x.jar";
    private static final String JAR_Y = "dropin-testmod-y.jar";
    private static final String JAR_Z = "dropin-testmod-z.jar";
    /** Nobody's account: the UUID the owner bans in owner-rights. */
    private static final String BANNED_UUID = "0b5e6a52-6f0c-4d6f-9c7d-2f1f3f0e9a11";

    private static final String B_COMPLETE = "b-complete";
    private static final String A_QUIT = "a-quit";
    private static final String B_HOSTING = "b-hosting";
    private static final String B_DONE = "b-done";

    private M2bScenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        return switch (key) {
            case "swap-spike:a" -> spikeFirstRun(ctx);
            case "swap-spike:a2" -> spikeSecondRun(ctx);
            case "mod-join:a", "swap-restores:a" -> modJoinA(ctx);
            case "mod-join:b" -> modJoinB(ctx, false);
            case "mod-join:b2" -> modJoinB2(ctx, false);
            case "swap-restores:b" -> modJoinB(ctx, true);
            case "swap-restores:b2" -> modJoinB2(ctx, true);
            case "mod-decline:a" -> declineA(ctx);
            case "mod-decline:b" -> declineB(ctx);
            case "mods-tab:a" -> modsTabA(ctx);
            case "mods-tab:b" -> modsTabB(ctx);
            case "mod-tamper:a" -> tamperA(ctx);
            case "mod-tamper:b" -> tamperB(ctx);
            case "mod-keep-extra:a", "owner-known-to-members:a", "datapack-mismatch:a" -> hostThenQuitA(ctx);
            case "mod-keep-extra:b" -> keepExtraB(ctx);
            case "owner-known-to-members:b" -> ownerKnownB(ctx);
            case "datapack-mismatch:b" -> datapackB(ctx);
            case "owner-rights:a" -> ownerRightsA(ctx);
            case "owner-rights:b" -> ownerRightsB(ctx);
            case "mod-change-live:a" -> changeLiveA(ctx);
            case "mod-change-live:a2" -> changeLiveA2(ctx);
            case "mod-change-live:b" -> changeLiveB(ctx);
            case "mod-change-live:b2" -> changeLiveB2(ctx);
            default -> null;
        };
    }

    // ---- building blocks --------------------------------------------------------------------------

    static Path configDir() {
        return Agnos.configDir().resolve("dropin");
    }

    static Path modsDir(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve("mods");
    }

    /** A test mod jar of this loader, as built by {@code :<loader>:testmodJars}. */
    static Path testMod(String name) {
        Path dir = DropIn.switches().read("dropin.dev.testModsDir").map(Path::of)
                .orElseThrow(() -> new IllegalStateException("-Ddropin.dev.testModsDir is required"));
        Path jar = dir.resolve(Agnos.loaderName()).resolve("dropin-testmod-" + name + ".jar");
        require(Files.isRegularFile(jar), "test mod not built: " + jar);
        return jar;
    }

    static boolean itemExists(String id) {
        return BuiltInRegistries.ITEM.containsKey(Identifier.parse(id));
    }

    static boolean zLoaded() {
        return "loaded".equals(System.getProperty(Z_PROPERTY));
    }

    static List<String> jarsIn(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).map(f -> folder.relativize(f).toString().replace('\\', '/')).sorted().toList();
        }
    }

    /** Checkpoints every few seconds, so a scenario does not wait a minute for one. */
    private static StepRunner dev(StepRunner runner) {
        return runner.action("set development switches", () -> System.setProperty("dropin.dev.checkpointSeconds", "5"));
    }

    private static StepRunner pause(StepRunner runner, String name, int ticks) {
        int[] waited = {0};
        return runner.step(name, ticks + 200, () -> ++waited[0] >= ticks);
    }

    private static boolean holds(Minecraft mc, String itemId) {
        return mc.player != null && mc.player.getInventory()
                .contains(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId));
    }

    private static void command(Minecraft mc, String command) {
        IntegratedServer server = mc.getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }

    private static ModConsentScreen consent(Minecraft mc) {
        return mc.gui.screen() instanceof ModConsentScreen screen ? screen : null;
    }

    /** True if an alert is up whose text is the given message (compared up to the message's first argument). */
    private static boolean alertSays(Minecraft mc, String key) {
        Screen screen = mc.gui.screen();
        if (!(screen instanceof AlertScreen)) {
            return false;
        }
        String shown = screen.getNarrationMessage().getString();
        String expected = Component.translatable(K + key, "\u0001").getString();
        int cut = expected.indexOf('\u0001');
        return shown.contains(cut < 0 ? expected : expected.substring(0, cut));
    }

    private static List<String> names(List<ModManifest.Entry> files) {
        return files.stream().map(ModManifest.Entry::path).sorted().toList();
    }

    private static Path folder(Minecraft mc, State state) {
        return PolicyActions.folder(mc, state.world);
    }

    /** Nothing of a download may be left where the loader or a later swap could pick it up. */
    private static void requireNothingInstalled(Minecraft mc, List<String> modsBefore) throws IOException {
        require(jarsIn(modsDir(mc)).equals(modsBefore), "mods/ changed: " + jarsIn(modsDir(mc)) + ", was " + modsBefore);
        List<String> stored = jarsIn(WorldPolicy.store().dir()).stream().filter(name -> name.endsWith(".jar")).toList();
        require(stored.isEmpty(), "the mod store holds " + stored);
        require(!Files.exists(configDir().resolve(SwapPlan.FILE)), "a swap was planned");
    }

    /** A: makes a world, imports it and hosts it; the first policy is signed on the way. B is not told yet. */
    private static StepRunner hostA(Context ctx, State state) {
        Minecraft mc = ctx.mc();
        return importWorld(quitToTitle(createWorld(dev(base(ctx)), ctx), ctx), ctx, state)
                .action("share invite", () -> {
                    ctx.writeShared(INVITE_FILE, state.world.invite().encode());
                    ctx.writeShared(WORLD_READY, "ok");
                })
                .action("press Play", () -> pressPlay(mc, state))
                .step("hosting, announced", WORLD_TICKS, () -> hosting(ctx))
                .step("first checkpoint", WORLD_TICKS, () -> {
                    HostSync sync = HostSync.current();
                    return sync != null && sync.seq() >= 1;
                })
                .action("policy version 1 is signed with this PC's mods", () -> {
                    WorldPolicy.Held held = HostSync.current().held();
                    require(held != null, "the owner hosts without a policy");
                    require(held.version() == 1, "policy version " + held.version());
                    require(held.policy().isOwner(mc.getUser().getProfileId().toString()), "the policy names another owner");
                    require(names(held.manifest().files()).equals(names(WorldPolicy.localMods())),
                            "the signed mod list is " + names(held.manifest().files()) + ", mods/ has " + names(WorldPolicy.localMods()));
                    DropIn.LOGGER.info("Self-test: hosting with policy version 1, mods {}", names(held.manifest().files()));
                });
    }

    private static StepRunner tellB(StepRunner runner, Context ctx) {
        return runner.action("tell B", () -> ctx.writeShared(A_HOSTING, "ok"));
    }

    /** A waits for a file of B; with {@code alone}, B must never get into A's session meanwhile. */
    private static StepRunner.Step shared(Context ctx, String name, boolean alone) {
        Minecraft mc = ctx.mc();
        return () -> {
            if (alone) {
                require(mc.getSingleplayerServer().getPlayerList().getPlayerCount() == 1, "B got into the session");
            }
            return ctx.sharedExists(name);
        };
    }

    /** B: knows the world only by its invite and presses Play once A hosts. */
    private static StepRunner inviteB(Context ctx, State state) {
        Minecraft mc = ctx.mc();
        return dev(base(ctx))
                .step("A shared the invite", STARTUP_TICKS + WORLD_TICKS, () -> ctx.sharedExists(WORLD_READY))
                .action("join with invite", () -> {
                    CoopActions.Outcome outcome = CoopActions.joinWithInvite(mc, ctx.readShared(INVITE_FILE));
                    require(outcome.ok(), "join with invite failed: " + outcome.message().getString());
                    state.world = outcome.world();
                })
                .step("A is hosting", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(A_HOSTING))
                .action("press Play", () -> pressPlay(mc, state));
    }

    /** B after a restart: same game folder, so the world is still in its list. */
    private static StepRunner restarted(Context ctx, State state) {
        return dev(base(ctx)).action("the world is still in the list", () -> {
            List<CoopWorld> worlds = CoopWorlds.store().list();
            require(worlds.size() == 1, "expected one co-op world, found " + worlds.size());
            state.world = worlds.get(0);
        });
    }

    private static StepRunner consentShown(StepRunner runner, Minecraft mc, String name) {
        return runner.step(name, PEER_TICKS, () -> {
            failIfDisconnected(mc);
            require(mc.level == null, "B was connected without being asked about the mods");
            return consent(mc) != null;
        });
    }

    /** B approved: the files are fetched and verified, the helper is started, the game is about to close. */
    private static StepRunner approve(StepRunner runner, Minecraft mc) {
        return runner
                .action("approve: Download and restart", () -> consent(mc).approve())
                .step("mods downloaded, helper started", PEER_TICKS, () -> {
                    ModConsentScreen screen = consent(mc);
                    require(screen != null, "the consent screen went away");
                    require(screen.state() != ModConsentScreen.State.FAILED, "the download failed: " + screen.error().getString());
                    return screen.state() == ModConsentScreen.State.CLOSING;
                })
                .action("a swap is planned", () -> require(Files.isRegularFile(configDir().resolve(SwapPlan.FILE)), "no swap plan was written"));
    }

    private static StepRunner helperSucceeded(StepRunner runner) {
        return runner.action("the helper reported success", () -> {
            String result = Files.readString(configDir().resolve(SwapHelper.RESULT_FILE), StandardCharsets.UTF_8);
            require(result.startsWith("{\"ok\":true"), "helper result: " + result);
        });
    }

    /** B: joins A (no mod question expected) and waits until its copy of the world is complete. */
    private static StepRunner joinAndComplete(Context ctx, State state) {
        Minecraft mc = ctx.mc();
        return inviteB(ctx, state)
                .step("landed in A's session", PEER_TICKS, () -> {
                    require(consent(mc) == null, "B was asked to change its mods");
                    return joinedRemote(mc);
                })
                .step("B's copy is complete", WORLD_TICKS + PEER_TICKS, M2Scenarios.copyComplete(mc, state));
    }

    /** B: tells A the copy is complete, sees A quit and goes back to the title screen. */
    private static StepRunner afterAQuit(StepRunner runner, Context ctx) {
        Minecraft mc = ctx.mc();
        return runner
                .action("tell A", () -> ctx.writeShared(B_COMPLETE, "ok"))
                .step("A quit", PEER_TICKS, () -> mc.gui.screen() instanceof DisconnectedScreen
                        || (mc.level == null && mc.gui.screen() instanceof TitleScreen))
                .action("title screen", () -> mc.gui.setScreen(new TitleScreen()))
                .step("A closed the world", WORLD_TICKS, () -> ctx.sharedExists(A_QUIT));
    }

    /** B pressed Play with nobody hosting and must be told why it cannot host. */
    private static StepRunner refusedAsHost(StepRunner runner, Context ctx, State state, String key) {
        Minecraft mc = ctx.mc();
        runner.action("press Play with nobody hosting", () -> pressPlay(mc, state))
                .step("refused as host: " + key, WORLD_TICKS, () -> {
                    require(mc.getSingleplayerServer() == null, "B opened the world as host");
                    return alertSays(mc, key);
                });
        return pause(runner, "still not hosting", 60)
                .action("the world stayed closed", () -> {
                    require(mc.getSingleplayerServer() == null && mc.level == null, "B opened the world as host");
                    require(HostSync.current() == null, "B started a host sync");
                })
                .action("tell A", () -> ctx.writeShared(B_DONE, "ok"));
    }

    /** A: hosts, waits until B's copy is complete, quits, and waits for B to finish what it does alone. */
    private static StepRunner hostThenQuitA(Context ctx) {
        State state = new State();
        StepRunner runner = tellB(hostA(ctx, state), ctx)
                .step("B's copy is complete", STARTUP_TICKS + PEER_TICKS, () -> ctx.sharedExists(B_COMPLETE));
        return quitToTitle(runner, ctx)
                .action("tell B", () -> ctx.writeShared(A_QUIT, "ok"))
                .step("B finished", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(B_DONE));
    }

    // ---- mod-join, swap-restores --------------------------------------------------------------------

    /** A: hosts with its mods; when B finally gets in, gives it the item of test mod X. */
    private static StepRunner modJoinA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = hostA(ctx, state)
                .action("the mod list is not empty", () -> {
                    ModManifest manifest = HostSync.current().held().manifest();
                    require(!manifest.isEmpty(), "A hosts without mods");
                    if (ctx.online()) {
                        require(manifest.files().stream().allMatch(file -> file.source().equals(ModManifest.MODRINTH)),
                                "Modrinth's hash lookup did not know " + names(manifest.files()));
                    }
                });
        tellB(runner, ctx)
                .step("B got in after its restart", STARTUP_TICKS + STARTUP_TICKS,
                        () -> mc.getSingleplayerServer().getPlayerList().getPlayerCount() == 2);
        if (!ctx.online()) {
            runner.action("give B the item of test mod X", () -> command(mc, "give @a " + ITEM_X));
        }
        return runner.step("B finished", PEER_TICKS, () -> ctx.sharedExists(B_DONE));
    }

    /** B, first start: the consent screen lists what is needed; approving ends in a planned swap and a closing game. */
    private static StepRunner modJoinB(Context ctx, boolean ownMods) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = consentShown(inviteB(ctx, state), mc, "consent screen instead of a connection")
                .action("the screen lists what the world needs", () -> {
                    ModConsentScreen screen = consent(mc);
                    ModDiff diff = screen.diff();
                    List<String> wanted = names(screen.held().manifest().files());
                    require(names(diff.toDownload()).equals(wanted), "to download " + names(diff.toDownload()) + ", the world needs " + wanted);
                    if (!ctx.online()) {
                        require(wanted.equals(List.of(JAR_X)), "the world needs " + wanted);
                        require(!itemExists(ITEM_X), "test mod X is loaded before the swap");
                    }
                    List<String> remove = names(diff.toRemove());
                    require(remove.equals(ownMods ? List.of(JAR_Y, JAR_Z) : List.of()), "to remove " + remove);
                    require(screen.state() == ModConsentScreen.State.ASKING, "state " + screen.state());
                    require(jarsIn(WorldPolicy.store().dir()).isEmpty(), "something was downloaded before the click");
                });
        return approve(runner, mc).action("where the files came from", () -> {
            if (ctx.online()) {
                require(ModInstall.modrinthDownloads() == consent(mc).diff().toDownload().size(),
                        ModInstall.modrinthDownloads() + " files came from Modrinth");
            } else {
                require(ModInstall.modrinthDownloads() == 0, "an offline self-test asked Modrinth");
            }
        });
    }

    /** B, second start: the loader runs the world's mods; Play leads into the session. */
    private static StepRunner modJoinB2(Context ctx, boolean ownMods) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = helperSucceeded(restarted(ctx, state))
                .action("mods/ is the world's mod list", () -> {
                    WorldPolicy.Held held = WorldPolicy.load(state.world, folder(mc, state));
                    require(held != null, "B has no policy stored");
                    ModDiff diff = WorldPolicy.diff(held);
                    require(diff.toDownload().isEmpty() && diff.toRemove().isEmpty(),
                            "still to download " + names(diff.toDownload()) + ", to remove " + names(diff.toRemove()));
                    if (!ctx.online()) {
                        require(itemExists(ITEM_X), "test mod X was not loaded after the swap on " + Agnos.loaderName());
                    }
                });
        if (ownMods) {
            runner.action("B's own jars were moved, not deleted", () -> {
                require(!itemExists(ITEM_Y) && !zLoaded(), "B's own mods are still loaded");
                List<String> jars = jarsIn(modsDir(mc));
                for (String name : List.of("y", "z")) {
                    String jar = "dropin-testmod-" + name + ".jar";
                    require(!jars.contains(jar), jar + " is still directly in mods/: " + jars);
                    List<String> moved = jars.stream().filter(j -> j.startsWith(SwapPlan.DISABLED_DIR + "/") && j.endsWith("/" + jar)).toList();
                    require(moved.size() == 1, jar + " is not under mods/.dropin-disabled/: " + jars);
                    require(SwapHelper.sha512(modsDir(mc).resolve(moved.get(0))).equals(SwapHelper.sha512(testMod(name))),
                            jar + " under mods/.dropin-disabled/ is not the file B had");
                }
            });
        }
        runner.action("press Play", () -> pressPlay(mc, state))
                .step("landed in A's session", PEER_TICKS, () -> {
                    require(consent(mc) == null, "B was asked about the mods again");
                    return joinedRemote(mc);
                });
        if (!ctx.online()) {
            runner.step("holds the item of test mod X", SHORT_TICKS, () -> {
                failIfDisconnected(mc);
                return holds(mc, ITEM_X);
            });
        }
        return runner.action("tell A", () -> ctx.writeShared(B_DONE, "ok"));
    }

    // ---- mod-decline --------------------------------------------------------------------------------

    private static StepRunner declineA(Context ctx) {
        return tellB(hostA(ctx, new State()), ctx)
                .step("B declined and stayed out", STARTUP_TICKS + PEER_TICKS, shared(ctx, B_DONE, true));
    }

    private static StepRunner declineB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = consentShown(inviteB(ctx, state), mc, "consent screen instead of a connection")
                .action("cancel", () -> consent(mc).cancel())
                .step("back at the Co-op Worlds screen", SHORT_TICKS, () -> consent(mc) == null);
        return pause(runner, "nothing happens", 100)
                .action("not connected, nothing downloaded, mods/ unchanged", () -> {
                    require(mc.level == null && mc.getConnection() == null, "B is connected");
                    requireNothingInstalled(mc, List.of());
                })
                .action("tell A", () -> ctx.writeShared(B_DONE, "ok"));
    }

    // ---- mods-tab -----------------------------------------------------------------------------------

    private static StepRunner modsTabA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        return tellB(hostA(ctx, state), ctx)
                .action("the owner's Mods tab lists X without a mark", () -> {
                    List<ModsScreen.Row> rows = ModsScreen.rowsOf(mc, state.world.worldId());
                    require(rows != null && rows.size() == 1, "rows: " + rows);
                    require(rows.get(0).file().equals(JAR_X) && rows.get(0).mark() == ModsScreen.Mark.NONE, "rows: " + rows);
                })
                .step("B finished", STARTUP_TICKS + PEER_TICKS, shared(ctx, B_DONE, true));
    }

    /** B has Z and not X: the tab says X is missing here and Z is extra here; Z can be marked "keep". */
    private static StepRunner modsTabB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        return dev(base(ctx))
                .step("A shared the invite", STARTUP_TICKS + WORLD_TICKS, () -> ctx.sharedExists(WORLD_READY))
                .action("join with invite", () -> {
                    CoopActions.Outcome outcome = CoopActions.joinWithInvite(mc, ctx.readShared(INVITE_FILE));
                    require(outcome.ok(), "join with invite failed: " + outcome.message().getString());
                    state.world = outcome.world();
                })
                .action("the mod list is not known before the first contact", () ->
                        require(ModsScreen.rowsOf(mc, state.world.worldId()) == null, "B knows a mod list it never received"))
                .step("A is hosting", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(A_HOSTING))
                .action("press Play", () -> pressPlay(mc, state))
                .step("consent screen", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return consent(mc) != null;
                })
                .action("Show list", () -> mc.gui.setScreen(new ModsScreen(consent(mc), state.world.worldId())))
                .step("the Mods tab is open", SHORT_TICKS, () -> mc.gui.screen() instanceof ModsScreen)
                .action("X is missing here, Z is extra here", () -> {
                    List<ModsScreen.Row> rows = ((ModsScreen) mc.gui.screen()).rows();
                    require(rows.size() == 2, "rows: " + rows);
                    ModsScreen.Row x = rows.get(0);
                    ModsScreen.Row z = rows.get(1);
                    require(x.file().equals(JAR_X) && x.mark() == ModsScreen.Mark.MISSING, "row of X: " + x);
                    require(x.name().equals("dropin_testmod_x") && !x.version().isEmpty(), "X is listed without its name and version: " + x);
                    require(z.file().equals(JAR_Z) && z.mark() == ModsScreen.Mark.EXTRA && !z.kept(), "row of Z: " + z);
                })
                .action("mark Z keep", () -> ModsScreen.setKeep(JAR_Z, true))
                .action("Z is shown as kept", () -> {
                    List<ModsScreen.Row> rows = ModsScreen.rowsOf(mc, state.world.worldId());
                    require(rows.get(1).file().equals(JAR_Z) && rows.get(1).kept(), "row of Z: " + rows.get(1));
                    require(WorldPolicy.diff(WorldPolicy.load(state.world, folder(mc, state))).toRemove().isEmpty(), "a kept jar is still to be removed");
                })
                .action("tell A", () -> ctx.writeShared(B_DONE, "ok"));
    }

    // ---- mod-tamper ---------------------------------------------------------------------------------

    /** A is a dishonest host: first it sends a policy signed by another key, then mod files with a changed byte. */
    private static StepRunner tamperA(Context ctx) {
        State state = new State();
        StepRunner runner = hostA(ctx, state)
                .action("answer policy requests with a policy signed by another key", () -> {
                    WorldPolicy.Held held = HostSync.current().held();
                    Policy real = held.policy();
                    byte[] seed = new byte[32];
                    Arrays.fill(seed, (byte) 9);
                    Policy forged = Policy.sign(new Policy(real.version() + 1, "", real.ownerUuid(), real.ownerName(), real.loader(),
                            real.minecraftVersion(), real.modManifestHash(), real.datapackFiles(), real.datapacksEnabled(),
                            List.of(), true, real.ts(), ""), Ed25519.keyPairFromSeed(seed));
                    DevHooks.policyOverride = new String[]{forged.toJson(), held.manifest().toJson()};
                });
        return tellB(runner, ctx)
                .step("B rejected the policy", STARTUP_TICKS + PEER_TICKS, shared(ctx, "b-rejected", true))
                .action("serve the real policy, but mod files with one byte changed", () -> {
                    DevHooks.policyOverride = null;
                    DevHooks.tamperMods = true;
                    ctx.writeShared("a-tamper", "ok");
                })
                .step("B rejected the file", PEER_TICKS, shared(ctx, B_DONE, true));
    }

    private static StepRunner tamperB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = inviteB(ctx, state)
                .step("told that the policy is not the owner's", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    require(mc.level == null, "B connected to a host with a forged policy");
                    require(consent(mc) == null, "B was offered mods from a forged policy");
                    return alertSays(mc, "policy.rejected");
                });
        pause(runner, "nothing happens", 60)
                .action("not connected, the forged policy is not stored", () -> {
                    require(mc.level == null && mc.getConnection() == null, "B is connected");
                    require(WorldPolicy.load(state.world, folder(mc, state)) == null, "B stored a policy");
                    requireNothingInstalled(mc, List.of());
                    ctx.writeShared("b-rejected", "ok");
                })
                .step("A serves tampered files now", WORLD_TICKS, () -> ctx.sharedExists("a-tamper"))
                .action("press Play again", () -> pressPlay(mc, state));
        return consentShown(runner, mc, "consent screen for the real policy")
                .action("approve", () -> consent(mc).approve())
                .step("the download is refused", PEER_TICKS, () -> {
                    ModConsentScreen screen = consent(mc);
                    require(screen != null, "the consent screen went away");
                    require(screen.state() != ModConsentScreen.State.CLOSING, "a tampered file was accepted");
                    return screen.state() == ModConsentScreen.State.FAILED;
                })
                .action("the file never reached mods/ or the store", () -> {
                    String shown = consent(mc).error().getString();
                    String expected = Component.translatable(K + "mods.error.hash", JAR_X).getString();
                    require(shown.equals(expected), "the screen says: " + shown);
                    requireNothingInstalled(mc, List.of());
                    require(!itemExists(ITEM_X), "test mod X is loaded");
                })
                .action("tell A", () -> ctx.writeShared(B_DONE, "ok"));
    }

    // ---- mod-keep-extra -----------------------------------------------------------------------------

    /** B has the world's X and a client-only jar Z it marked "keep": it can join, but it cannot host. */
    private static StepRunner keepExtraB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = dev(base(ctx))
                .action("mark Z keep", () -> {
                    require(zLoaded() && itemExists(ITEM_X), "B was not started with X and Z");
                    ModsScreen.setKeep(JAR_Z, true);
                })
                .step("A shared the invite", STARTUP_TICKS + WORLD_TICKS, () -> ctx.sharedExists(WORLD_READY))
                .action("join with invite", () -> {
                    CoopActions.Outcome outcome = CoopActions.joinWithInvite(mc, ctx.readShared(INVITE_FILE));
                    require(outcome.ok(), "join with invite failed: " + outcome.message().getString());
                    state.world = outcome.world();
                })
                .step("A is hosting", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(A_HOSTING))
                .action("press Play", () -> pressPlay(mc, state))
                .step("B can join with the kept jar", PEER_TICKS, () -> {
                    require(consent(mc) == null, "B was asked to remove a kept jar");
                    return joinedRemote(mc);
                })
                .step("B's copy is complete", WORLD_TICKS + PEER_TICKS, M2Scenarios.copyComplete(mc, state))
                .action("Z is still in mods/", () -> require(jarsIn(modsDir(mc)).equals(List.of(JAR_X, JAR_Z)), "mods/ has " + jarsIn(modsDir(mc))));
        return refusedAsHost(afterAQuit(runner, ctx), ctx, state, "policy.kept_extras");
    }

    // ---- owner-known-to-members ---------------------------------------------------------------------

    private static void requireOnlyAIsOwner(Minecraft mc, State state) {
        String me = mc.getUser().getName();
        List<PlayersScreen.Row> rows = PlayersScreen.rowsOf(state.world.worldId());
        require(rows.size() == 2, "Players tab: " + rows);
        for (PlayersScreen.Row row : rows) {
            require(row.owner() != row.name().equals(me), "Players tab on B: " + row.name() + " owner=" + row.owner());
        }
    }

    private static StepRunner ownerKnownB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = joinAndComplete(ctx, state)
                .step("B's Players tab marks A as owner", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    List<PlayersScreen.Row> rows = PlayersScreen.rowsOf(state.world.worldId());
                    return rows.size() == 2 && rows.stream().anyMatch(PlayersScreen.Row::owner);
                })
                .action("only A is marked", () -> requireOnlyAIsOwner(mc, state));
        return afterAQuit(runner, ctx)
                .action("press Play with nobody hosting", () -> pressPlay(mc, state))
                .step("B is hosting", WORLD_TICKS, () -> hosting(ctx))
                .action("A is still marked as owner while B hosts", () -> {
                    requireOnlyAIsOwner(mc, state);
                    require(!CoopWorlds.store().isOwner(state.world.worldId()), "B has an owner key");
                })
                .action("tell A", () -> ctx.writeShared(B_DONE, "ok"));
    }

    // ---- datapack-mismatch --------------------------------------------------------------------------

    private static StepRunner datapackB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = afterAQuit(joinAndComplete(ctx, state), ctx)
                .action("put a datapack into the closed copy that the owner does not have", () -> {
                    Path pack = folder(mc, state).resolve("datapacks").resolve("extra");
                    Files.createDirectories(pack);
                    Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"description\":\"not the owner's\"}}", StandardCharsets.UTF_8);
                });
        return refusedAsHost(runner, ctx, state, "policy.datapacks_mismatch");
    }

    // ---- owner-rights -------------------------------------------------------------------------------

    /** A: turns member commands off and bans a UUID while hosting; later joins B's session and uses a command. */
    private static StepRunner ownerRightsA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = tellB(hostA(ctx, state), ctx)
                .step("B logged in", STARTUP_TICKS + PEER_TICKS, () -> mc.getSingleplayerServer().getPlayerList().getPlayerCount() == 2)
                .action("the owner changes the policy from the Players tab", () -> {
                    PolicyActions.setMembersMayUseCommands(mc, state.world, false);
                    WorldPolicy.Held held = PolicyActions.setBanned(mc, state.world, BANNED_UUID, true);
                    require(held.version() == 3 && HostSync.current().held().version() == 3, "policy version " + held.version());
                    require(PlayersScreen.rowsOf(state.world.worldId()).stream().noneMatch(PlayersScreen.Row::banned), "a member is shown as banned");
                })
                .step("B's copy is complete, with the new policy", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(B_COMPLETE));
        return quitToTitle(runner, ctx)
                .action("tell B", () -> ctx.writeShared(A_QUIT, "ok"))
                .step("B is hosting", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(B_HOSTING))
                .action("press Play", () -> pressPlay(mc, state))
                .step("landed in B's session", PEER_TICKS, () -> joinedRemote(mc))
                .action("the owner uses an operator command", () -> mc.player.connection.sendCommand("give @s minecraft:diamond 3"))
                .step("the command worked", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return holds(mc, "minecraft:diamond");
                })
                .action("tell B", () -> ctx.writeShared("a-commanded", "ok"))
                .step("B finished", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists(B_DONE);
                });
    }

    private static StepRunner ownerRightsB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        int[] ticks = {0};
        StepRunner runner = joinAndComplete(ctx, state)
                .step("policy version 3 arrived", WORLD_TICKS, () -> {
                    failIfDisconnected(mc);
                    if (ticks[0]++ % 20 != 0) {
                        return false;
                    }
                    WorldPolicy.Held held = WorldPolicy.load(state.world, folder(mc, state));
                    return held != null && held.version() == 3;
                })
                .action("it says: no member commands, one ban", () -> {
                    Policy policy = WorldPolicy.load(state.world, folder(mc, state)).policy();
                    require(!policy.membersMayUseCommands() && policy.isBanned(BANNED_UUID), "policy: " + policy.toJson());
                });
        afterAQuit(runner, ctx)
                .action("press Play with nobody hosting", () -> pressPlay(mc, state))
                .step("B is hosting", WORLD_TICKS, () -> hosting(ctx))
                .action("tell A", () -> ctx.writeShared(B_HOSTING, "ok"))
                .step("the owner joined and used a command", PEER_TICKS + WORLD_TICKS, () -> ctx.sharedExists("a-commanded"))
                .action("the host tries the same command", () -> mc.player.connection.sendCommand("give @s minecraft:emerald 3"));
        return pause(runner, "give the command time", 100)
                .action("the host's command was refused", () -> require(!holds(mc, "minecraft:emerald"), "B, not the owner, could use /give"))
                .action("rights on B's server: owner yes, host no", () -> {
                    IntegratedServer server = mc.getSingleplayerServer();
                    Policy policy = HostSync.current().held().policy();
                    NameAndId owner = new NameAndId(UUID.fromString(policy.ownerUuid()), policy.ownerName());
                    NameAndId host = new NameAndId(mc.getUser().getProfileId(), mc.getUser().getName());
                    require(server.getProfilePermissions(owner).equals(LevelBasedPermissionSet.OWNER), "the owner has " + server.getProfilePermissions(owner));
                    require(server.getProfilePermissions(host).equals(LevelBasedPermissionSet.ALL), "the host has " + server.getProfilePermissions(host));
                })
                .action("a banned UUID is refused at login", () -> {
                    IntegratedServer server = mc.getSingleplayerServer();
                    Component refusal = server.getPlayerList().canPlayerLogin(new InetSocketAddress("127.0.0.1", 1),
                            new NameAndId(UUID.fromString(BANNED_UUID), "Banned"));
                    require(refusal != null, "the banned UUID may log in");
                    require(refusal.getString().equals(Component.translatable(K + "policy.banned").getString()), "refusal: " + refusal.getString());
                    require(HostSync.banMessage(server, UUID.fromString(HostSync.current().held().policy().ownerUuid())) == null, "the owner is banned");
                })
                .action("tell A", () -> ctx.writeShared(B_DONE, "ok"));
    }

    // ---- mod-change-live ----------------------------------------------------------------------------

    /** A, first start (mods: X): hosts, hands over to B, puts Y into mods/ and asks to be restarted. */
    private static StepRunner changeLiveA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = tellB(hostA(ctx, state), ctx)
                .step("B's copy is complete", STARTUP_TICKS + PEER_TICKS, () -> ctx.sharedExists(B_COMPLETE));
        return quitToTitle(runner, ctx)
                .action("tell B", () -> ctx.writeShared(A_QUIT, "ok"))
                .step("B is hosting", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists(B_HOSTING))
                .action("the owner adds test mod Y and restarts", () -> {
                    Files.copy(testMod("y"), modsDir(mc).resolve(JAR_Y), StandardCopyOption.REPLACE_EXISTING);
                    ctx.writeShared("restart-a", "ok");
                })
                .step("restarted by the orchestrator", SHORT_TICKS, () -> false);
    }

    /** A, second start (mods: X, Y): signs version 2 when asked, which ends B's session; then hosts. */
    private static StepRunner changeLiveA2(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = restarted(ctx, state)
                .action("Y is loaded", () -> require(itemExists(ITEM_X) && itemExists(ITEM_Y), "the owner was not restarted with X and Y"))
                .action("press Play", () -> pressPlay(mc, state))
                .step("asked: Update this world's mod list?", WORLD_TICKS, () -> mc.gui.screen() instanceof PolicyUpdateScreen)
                .action("the screen lists Y as added", () -> {
                    PolicyUpdateScreen screen = (PolicyUpdateScreen) mc.gui.screen();
                    require(screen.addedMods().equals(List.of(JAR_Y)) && screen.removedMods().isEmpty(),
                            "added " + screen.addedMods() + ", removed " + screen.removedMods());
                    require(!screen.datapacksChanged(), "the datapacks are said to have changed");
                })
                .action("Yes", () -> ((PolicyUpdateScreen) mc.gui.screen()).accept())
                .step("B's session ended with the mods-changed screen", PEER_TICKS, () -> ctx.sharedExists("b-changed"))
                .step("A is out of B's session", SHORT_TICKS, () -> mc.level == null)
                .action("title screen", () -> mc.gui.setScreen(new TitleScreen()));
        pause(runner, "B's host announcement is withdrawn", 100)
                .action("press Play", () -> pressPlay(mc, state))
                // Since M4 (DESIGN 4.4 step 3): B's session went on after A's copy was last updated and B is gone,
                // so A is warned before it opens its older copy.
                .step("warned: the copy is behind B's session", WORLD_TICKS,
                        () -> dropin.mc.coop.CoopLauncher.staleWarning() != null)
                .action("Play anyway", () -> {
                    String last = dropin.mc.coop.CoopLauncher.staleWarning().name();
                    require(last.equals("DevB"), "the warning is about the session of '" + last + "', expected DevB");
                    dropin.mc.coop.CoopLauncher.answerStale(true);
                })
                .step("A is hosting", WORLD_TICKS + SHORT_TICKS, () -> hosting(ctx))
                .action("with policy version 2: X and Y", () -> {
                    WorldPolicy.Held held = HostSync.current().held();
                    require(held.version() == 2 && names(held.manifest().files()).equals(List.of(JAR_X, JAR_Y)),
                            "version " + held.version() + ", mods " + names(held.manifest().files()));
                    ctx.writeShared("a2-hosting", "ok");
                })
                .step("B got back in after updating", STARTUP_TICKS + STARTUP_TICKS,
                        () -> mc.getSingleplayerServer().getPlayerList().getPlayerCount() == 2)
                .action("give B the item of test mod Y", () -> command(mc, "give @a " + ITEM_Y));
        return runner.step("B finished", PEER_TICKS, () -> ctx.sharedExists(B_DONE));
    }

    /**
     * B, first start (mods: X): joins A, then hosts. The owner's new policy reaches B's session: it ends with the
     * mods-changed screen. Play does not get B back in; only "Download and restart" does.
     */
    private static StepRunner changeLiveB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        StepRunner runner = afterAQuit(joinAndComplete(ctx, state), ctx)
                .action("press Play with nobody hosting", () -> pressPlay(mc, state))
                .step("B is hosting", WORLD_TICKS, () -> hosting(ctx))
                .action("tell A", () -> ctx.writeShared(B_HOSTING, "ok"))
                .step("the session ended: mods changed", STARTUP_TICKS + PEER_TICKS,
                        () -> consent(mc) != null && mc.getSingleplayerServer() == null && mc.level == null)
                .action("the screen says the mods changed and lists Y", () -> {
                    ModConsentScreen screen = consent(mc);
                    require(screen.changed(), "the screen does not say the mods changed");
                    require(screen.held().version() == 2, "policy version " + screen.held().version());
                    require(names(screen.diff().toDownload()).equals(List.of(JAR_Y)) && screen.diff().toRemove().isEmpty(),
                            "to download " + names(screen.diff().toDownload()) + ", to remove " + names(screen.diff().toRemove()));
                    require(WorldPolicy.load(state.world, folder(mc, state)).version() == 2, "version 2 is not stored in B's copy");
                    ctx.writeShared("b-changed", "ok");
                })
                .step("A is hosting with the new mods", WORLD_TICKS + PEER_TICKS, () -> ctx.sharedExists("a2-hosting"))
                .action("title screen, press Play without updating", () -> {
                    mc.gui.setScreen(new TitleScreen());
                    pressPlay(mc, state);
                });
        consentShown(runner, mc, "kept out: asked for the mods instead of connecting")
                .action("the screen lists Y", () -> require(names(consent(mc).diff().toDownload()).equals(List.of(JAR_Y)),
                        "to download " + names(consent(mc).diff().toDownload())));
        return approve(runner, mc);
    }

    /** B, second start (mods: X, Y): gets back in. */
    private static StepRunner changeLiveB2(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        return helperSucceeded(restarted(ctx, state))
                .action("Y is loaded", () -> require(itemExists(ITEM_X) && itemExists(ITEM_Y), "B was not restarted with X and Y"))
                .action("press Play", () -> pressPlay(mc, state))
                .step("landed in A's session", PEER_TICKS, () -> {
                    require(consent(mc) == null, "B was asked about the mods again");
                    return joinedRemote(mc);
                })
                .step("holds the item of test mod Y", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return holds(mc, ITEM_Y);
                })
                .action("tell A", () -> ctx.writeShared(B_DONE, "ok"));
    }

    // ---- swap-spike: the helper changes mods/ while the game is closed ------------------------------

    /** Run 1 starts with test mod Z in mods/. It asks for X instead, starts the helper and quits. */
    private static StepRunner spikeFirstRun(Context ctx) {
        Minecraft mc = ctx.mc();
        return new StepRunner()
                .step("main menu", STARTUP_TICKS, () -> Scenarios.atMainMenu(mc))
                .action("Z is loaded from mods/, X is not there", () -> {
                    require(zLoaded(), "test mod Z in mods/ was not loaded by " + Agnos.loaderName());
                    require(!itemExists(ITEM_X), "test mod X is loaded before the swap");
                })
                .action("write the plan and start the helper", () -> {
                    Path store = configDir().resolve("modstore");
                    Path jar = testMod("x");
                    String hash = SwapHelper.sha512(jar);
                    Files.createDirectories(store);
                    Files.copy(jar, store.resolve(hash + ".jar"), StandardCopyOption.REPLACE_EXISTING);
                    Path mods = modsDir(mc);
                    SwapPlan plan = new SwapPlan(ProcessHandle.current().pid(), mods, store,
                            SwapPlan.disabledDir(mods, Instant.now()),
                            List.of(new SwapPlan.Install(jar.getFileName().toString(), hash)), List.of());
                    Path planFile = configDir().resolve(SwapPlan.FILE);
                    plan.write(planFile);
                    HelperLauncher.launch(planFile, configDir().resolve("modswap.log"));
                });
    }

    /** Run 2, started by the orchestrator after the helper reported: the new set is what the loader loaded. */
    private static StepRunner spikeSecondRun(Context ctx) {
        Minecraft mc = ctx.mc();
        return new StepRunner()
                .step("main menu", STARTUP_TICKS, () -> Scenarios.atMainMenu(mc))
                .action("the helper reported success", () -> {
                    String result = Files.readString(configDir().resolve(SwapHelper.RESULT_FILE), StandardCharsets.UTF_8);
                    require(result.startsWith("{\"ok\":true"), "helper result: " + result);
                })
                .action("X is loaded, Z is not", () -> {
                    require(itemExists(ITEM_X), "test mod X was not loaded after the swap on " + Agnos.loaderName());
                    require(!zLoaded(), "test mod Z is still loaded after the swap");
                })
                .action("Z was moved, not deleted", () -> {
                    List<String> jars = jarsIn(modsDir(mc));
                    require(jars.contains("dropin-testmod-x.jar"), "mods/ has " + jars);
                    require(!jars.contains("dropin-testmod-z.jar"), "Z is still directly in mods/: " + jars);
                    require(jars.stream().anyMatch(j -> j.startsWith(SwapPlan.DISABLED_DIR + "/") && j.endsWith("/dropin-testmod-z.jar")),
                            "Z is not under mods/.dropin-disabled/: " + jars);
                });
    }
}
