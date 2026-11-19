package dropin.mc.selftest;

import dropin.core.dev.SelfTestSpec;
import dropin.mc.DropIn;
import dropin.tunnel.Config;
import dropin.tunnel.E4mcClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The scripted scenarios (MILESTONES gate suites). Development only.
 * World actions use commands; assertions read game state, not screenshots.
 */
final class Scenarios {
    static final String WORLD_ID = "dropin-selftest";
    static final long SEED = 20261116L;
    /** In a chunk the host force-loads, so the block can be placed and read wherever the players stand. */
    static final BlockPos PROBE_POS = new BlockPos(0, 250, 0);
    static final String PROBE_COMMAND = "setblock 0 250 0 minecraft:gold_block";

    static final int STARTUP_TICKS = 12_000;
    static final int WORLD_TICKS = 6_000;
    static final int PEER_TICKS = 6_000;
    static final int SHORT_TICKS = 2_400;

    static final String HOST_DONE = "host-done";
    static final String PEER_DONE = "peer-done";
    private static final String DOMAIN_FILE = "domain.txt";

    private Scenarios() {}

    record Context(Minecraft mc, int port, Path sharedDir, boolean online) {
        static Context fromSwitches(Minecraft mc) {
            var switches = DropIn.switches();
            int port = switches.read("dropin.dev.lanPort").map(Integer::parseInt)
                    .orElseThrow(() -> new IllegalStateException("-Ddropin.dev.lanPort is required"));
            Path shared = switches.read("dropin.dev.sharedDir").map(Path::of)
                    .orElseThrow(() -> new IllegalStateException("-Ddropin.dev.sharedDir is required"));
            return new Context(mc, port, shared, switches.flag("dropin.dev.online"));
        }

        String localAddress() {
            return "127.0.0.1:" + port;
        }

        boolean sharedExists(String name) {
            return Files.exists(sharedDir.resolve(name));
        }

        void writeShared(String name, String content) throws IOException {
            Files.createDirectories(sharedDir);
            Path tmp = sharedDir.resolve(name + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, sharedDir.resolve(name));
        }

        String readShared(String name) throws IOException {
            return Files.readString(sharedDir.resolve(name), StandardCharsets.UTF_8).trim();
        }
    }

    static StepRunner build(SelfTestSpec spec, Context ctx) {
        String key = spec.scenario() + ":" + spec.role();
        boolean expectOnline = spec.scenario().equals("tunnel-join");
        boolean eitherWay = spec.scenario().equals("drop-in") || spec.scenario().equals("first-download-root-match")
                || spec.scenario().equals("mod-join") || spec.scenario().equals("handoff-3")
                || spec.scenario().equals("pvp") || spec.scenario().equals("realtime")
                || spec.scenario().equals("motion");
        if (!eitherWay && expectOnline != ctx.online()) {
            throw new IllegalStateException(key + " needs dropin.dev.online=" + expectOnline);
        }
        StepRunner m1 = M1Scenarios.build(key, ctx);
        if (m1 != null) {
            return m1;
        }
        StepRunner m2 = M2Scenarios.build(key, ctx);
        if (m2 != null) {
            return m2;
        }
        StepRunner m2b = M2bScenarios.build(key, ctx);
        if (m2b != null) {
            return m2b;
        }
        StepRunner m3 = M3Scenarios.build(key, ctx);
        if (m3 != null) {
            return m3;
        }
        StepRunner m4 = M4Scenarios.build(key, ctx);
        if (m4 != null) {
            return m4;
        }
        StepRunner ui = UiScenarios.build(key, ctx);
        if (ui != null) {
            return ui;
        }
        StepRunner savePoints = SavePointScenarios.build(key, ctx);
        if (savePoints != null) {
            return savePoints;
        }
        StepRunner extras = ExtrasScenarios.build(key, ctx);
        if (extras != null) {
            return extras;
        }
        StepRunner motion = MotionScenarios.build(key, ctx);
        if (motion != null) {
            return motion;
        }
        StepRunner integrity = IntegrityScenarios.build(key, ctx);
        if (integrity != null) {
            return integrity;
        }
        StepRunner perf = PerfScenarios.build(key, ctx);
        if (perf != null) {
            return perf;
        }
        return switch (key) {
            case "lan-join:host", "tunnel-join:host" -> joinHost(ctx);
            case "lan-join:joiner", "tunnel-join:joiner" -> joiner(ctx);
            case "status-ping:host" -> pingHost(ctx);
            case "status-ping:pinger" -> pinger(ctx);
            default -> throw new IllegalArgumentException("Unknown self-test " + key);
        };
    }

    // ---- host side ------------------------------------------------------------------------------

    /** Creates a flat world from code and publishes it to LAN with cheats on. */
    private static StepRunner hostBase(Context ctx) {
        Minecraft mc = ctx.mc();
        return new StepRunner()
                .action("configure tunnel", () -> {
                    if (ctx.online()) {
                        E4mcClient.domainListener = domain -> {
                            try {
                                ctx.writeShared(DOMAIN_FILE, domain);
                            } catch (IOException e) {
                                DropIn.LOGGER.error("Could not publish the assigned domain to the joiner", e);
                            }
                        };
                    } else {
                        // DESIGN §4.12: self-tests run with the e4mc tunnel disabled. Not written to the config file.
                        Config.INSTANCE.hostEnabled.setOverride(false);
                    }
                })
                .step("main menu", STARTUP_TICKS, () -> atMainMenu(mc))
                .action("create world", () -> {
                    LevelSettings settings = new LevelSettings(WORLD_ID, GameType.CREATIVE,
                            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
                            WorldDataConfiguration.DEFAULT);
                    WorldOptions options = new WorldOptions(SEED, false, false);
                    mc.createWorldOpenFlows().createFreshLevel(WORLD_ID, settings, options,
                            registries -> registries.lookupOrThrow(Registries.WORLD_PRESET)
                                    .getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                            mc.gui.screen());
                })
                .step("world loaded", WORLD_TICKS, () -> inWorld(mc) && mc.getSingleplayerServer() != null)
                .action("force-load probe chunk", () -> {
                    IntegratedServer server = mc.getSingleplayerServer();
                    server.execute(() -> server.getCommands()
                            .performPrefixedCommand(server.createCommandSourceStack(), "forceload add 0 0"));
                })
                .action("publish to LAN", () -> {
                    IntegratedServer server = mc.getSingleplayerServer();
                    if (!server.publishServer(MinecraftServer.MultiplayerScope.LAN, true, ctx.port())) {
                        throw new IllegalStateException("publishServer returned false for port " + ctx.port());
                    }
                });
    }

    private static StepRunner joinHost(Context ctx) {
        Minecraft mc = ctx.mc();
        return hostBase(ctx)
                .step("joiner connected", PEER_TICKS, () -> mc.getSingleplayerServer().getPlayerList().getPlayerCount() == 2)
                .step("sees joiner", SHORT_TICKS, () -> mc.level != null && mc.level.players().size() == 2)
                .step("reads block placed by joiner", SHORT_TICKS,
                        () -> mc.getSingleplayerServer().overworld().getBlockState(PROBE_POS).is(Blocks.GOLD_BLOCK))
                .action("tell joiner", () -> ctx.writeShared(HOST_DONE, "ok"))
                .step("joiner finished", SHORT_TICKS, () -> ctx.sharedExists(PEER_DONE));
    }

    private static StepRunner pingHost(Context ctx) {
        return hostBase(ctx)
                .step("pinger finished", PEER_TICKS, () -> ctx.sharedExists(PEER_DONE));
    }

    // ---- joining side ---------------------------------------------------------------------------

    private static StepRunner joiner(Context ctx) {
        Minecraft mc = ctx.mc();
        String[] address = {ctx.localAddress()};
        StepRunner runner = new StepRunner()
                .step("main menu", STARTUP_TICKS, () -> atMainMenu(mc));
        if (ctx.online()) {
            runner.step("host published a domain", STARTUP_TICKS, () -> {
                if (!ctx.sharedExists(DOMAIN_FILE)) {
                    return false;
                }
                address[0] = ctx.readShared(DOMAIN_FILE);
                return true;
            });
        } else {
            runner.step("host port open", STARTUP_TICKS, new PortProbe(ctx.port())::tick);
        }
        return runner
                .action("connect", () -> ConnectScreen.startConnecting(new TitleScreen(), mc,
                        ServerAddress.parseString(address[0]),
                        new ServerData("dropin-selftest", address[0], ServerData.Type.OTHER), false, null))
                .step("joined world", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return inWorld(mc);
                })
                .step("sees host", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return mc.level != null && mc.level.players().size() == 2;
                })
                .action("place block", () -> mc.player.connection.sendCommand(PROBE_COMMAND))
                .step("host read the block", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists(HOST_DONE);
                })
                .action("tell host", () -> ctx.writeShared(PEER_DONE, "ok"));
    }

    private static StepRunner pinger(Context ctx) {
        Minecraft mc = ctx.mc();
        ServerData data = new ServerData("dropin-selftest", ctx.localAddress(), ServerData.Type.OTHER);
        ServerStatusPinger pinger = new ServerStatusPinger();
        return new StepRunner()
                .step("main menu", STARTUP_TICKS, () -> atMainMenu(mc))
                .step("host port open", STARTUP_TICKS, new PortProbe(ctx.port())::tick)
                .action("send status ping", () -> pinger.pingServer(data, () -> {}, () -> {}, EventLoopGroupHolder.remote(false)))
                .step("status response", SHORT_TICKS, () -> {
                    pinger.tick();
                    return data.players != null;
                })
                .action("check status", () -> {
                    if (data.players.online() != 1) {
                        throw new IllegalStateException("expected 1 player online in the status response, got " + data.players.online());
                    }
                    DropIn.LOGGER.info("Status response: motd='{}' players={}/{}", data.motd.getString(),
                            data.players.online(), data.players.max());
                    pinger.removeAll();
                })
                .action("tell host", () -> ctx.writeShared(PEER_DONE, "ok"));
    }

    // ---- helpers --------------------------------------------------------------------------------

    static boolean atMainMenu(Minecraft mc) {
        return mc.gui.overlay() == null && mc.gui.screen() != null && mc.level == null;
    }

    static boolean inWorld(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.gui.overlay() == null;
    }

    static void failIfDisconnected(Minecraft mc) {
        Screen screen = mc.gui.screen();
        if (screen instanceof DisconnectedScreen) {
            throw new IllegalStateException("disconnected: " + screen.getTitle().getString());
        }
    }

    /** Polls once a second whether something listens on the host's LAN port. */
    private static final class PortProbe {
        private final int port;
        private int ticks;

        PortProbe(int port) {
            this.port = port;
        }

        boolean tick() {
            if (ticks++ % 20 != 0) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }
}
