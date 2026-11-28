package dropin.mc.selftest;

import dropin.core.dev.SelfTestSpec;
import dropin.mc.DropIn;
import dropin.mc.host.ChunkBytes;
import dropin.tunnel.Config;
import dropin.tunnel.E4mcClient;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.ServerPinger;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /**
     * 1.12.2 port: where a player stands on the ground of a flat world. 26.x's flat world has its surface at y -60;
     * 1.12.2's default flat preset (bedrock, two dirt, grass) has it at y 4, and y -60 is the void.
     */
    static final int GROUND_Y = 4;

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
        StepRunner m5 = M5Scenarios.build(key, ctx);
        if (m5 != null) {
            return m5;
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
                .action("create world", () -> createFlatWorld(mc))
                .step("world loaded", WORLD_TICKS, () -> inWorld(mc) && mc.getIntegratedServer() != null)
                .action("force-load probe chunk", () -> forceLoadChunk(mc.getIntegratedServer(), 0, 0))
                .action("publish to LAN", () -> {
                    // 1.12.2 port: 26.x publishServer(mode, cheats, port) is shareToLAN(mode, cheats); the port is
                    // dropin.dev.lanPort through IntegratedServerOfflineLanMixin, and the call returns it (null on failure).
                    String port = mc.getIntegratedServer().shareToLAN(null, true);
                    if (port == null || !port.equals(Integer.toString(ctx.port()))) {
                        throw new IllegalStateException("shareToLAN returned " + port + ", expected port " + ctx.port());
                    }
                });
    }

    private static StepRunner joinHost(Context ctx) {
        Minecraft mc = ctx.mc();
        return hostBase(ctx)
                .step("joiner connected", PEER_TICKS, () -> mc.getIntegratedServer().getCurrentPlayerCount() == 2)
                .step("sees joiner", SHORT_TICKS, () -> mc.world != null && mc.world.playerEntities.size() == 2)
                .step("reads block placed by joiner", SHORT_TICKS,
                        () -> blockAt(mc.getIntegratedServer(), PROBE_POS) == Blocks.GOLD_BLOCK)
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
                .action("connect", () -> connect(mc, address[0]))
                .step("joined world", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return inWorld(mc);
                })
                .step("sees host", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return mc.world != null && mc.world.playerEntities.size() == 2;
                })
                .action("place block", () -> sendCommand(mc, PROBE_COMMAND))
                .step("host read the block", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists(HOST_DONE);
                })
                .action("tell host", () -> ctx.writeShared(PEER_DONE, "ok"));
    }

    private static StepRunner pinger(Context ctx) {
        Minecraft mc = ctx.mc();
        ServerData data = new ServerData("dropin-selftest", ctx.localAddress(), false);
        ServerPinger pinger = new ServerPinger();
        return new StepRunner()
                .step("main menu", STARTUP_TICKS, () -> atMainMenu(mc))
                .step("host port open", STARTUP_TICKS, new PortProbe(ctx.port())::tick)
                .action("send status ping", () -> {
                    // As GuiMultiplayer.initGui does before any ping: Forge keeps the "modinfo" of status replies in maps
                    // that only setupServerList creates (captureAdditionalData throws on a null map otherwise).
                    net.minecraftforge.fml.client.FMLClientHandler.instance().setupServerList();
                    pinger.ping(data);
                })
                .step("status response", SHORT_TICKS, () -> {
                    pinger.pingPendingNetworks();
                    return onlinePlayers(data) != null;
                })
                .action("check status", () -> {
                    int online = onlinePlayers(data);
                    if (online != 1) {
                        throw new IllegalStateException("expected 1 player online in the status response, got " + online);
                    }
                    DropIn.LOGGER.info("Status response: motd='{}' players={}", data.serverMOTD,
                            TextFormatting.getTextWithoutFormattingCodes(data.populationInfo));
                    pinger.clearPendingNetworks();
                })
                .action("tell host", () -> ctx.writeShared(PEER_DONE, "ok"));
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** 1.12.2 port: there is no loading overlay; a screen without a world is the menu. */
    static boolean atMainMenu(Minecraft mc) {
        return mc.currentScreen != null && mc.world == null;
    }

    static boolean inWorld(Minecraft mc) {
        return mc.player != null && mc.world != null;
    }

    static void failIfDisconnected(Minecraft mc) {
        GuiScreen screen = mc.currentScreen;
        if (screen instanceof GuiDisconnected) {
            throw new IllegalStateException("disconnected: " + disconnectText(screen));
        }
    }

    /**
     * 1.12.2 port: {@code GuiDisconnected} keeps its title ({@code reason}, a String) and message (an
     * ITextComponent) in private fields and has no getters; read them by type (development only).
     */
    static String disconnectText(GuiScreen screen) {
        StringBuilder text = new StringBuilder();
        for (Field field : GuiDisconnected.class.getDeclaredFields()) {
            try {
                if (field.getType() == String.class || field.getType() == ITextComponent.class) {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (value != null) {
                        text.append(text.length() == 0 ? "" : " - ")
                                .append(value instanceof ITextComponent c ? c.getUnformattedText() : value);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // keep what was read
            }
        }
        return text.toString();
    }

    /**
     * Creates and opens the scenarios' flat creative world with commands on and no structures (26.x:
     * {@code createFreshLevel} with the flat preset). 1.12.2 port: {@code launchIntegratedServer} with
     * {@code WorldSettings}; a new world takes its difficulty from the game options, so peaceful is set there.
     */
    static void createFlatWorld(Minecraft mc) {
        mc.gameSettings.difficulty = EnumDifficulty.PEACEFUL;
        WorldSettings settings = new WorldSettings(SEED, GameType.CREATIVE, false, false, WorldType.FLAT).enableCommands();
        OriginSpawn.INSTANCE.expect(settings);
        mc.launchIntegratedServer(WORLD_ID, WORLD_ID, settings);
    }

    /**
     * 1.12.2 port: the flat world spawns at 0,4,0, where the 26.x flat world spawns and where the scenarios build.
     * Left alone, 1.12.2's WorldServer.createSpawnPosition walks at random until WorldProvider.canCoordinateBeSpawn
     * finds grass via getGroundAboveSeaLevel, which starts at sea level 63, above flat ground: the spawn ended up near
     * 1652 4 720, out of sight of every block the scenarios place near the origin. Forge's CreateSpawnPosition event,
     * cancelled, skips that walk (WorldServer.java:764). Only the world created with these settings is touched.
     * The same hook makes the world peaceful, as the 26.x flat preset world is: a new 1.12.2 WorldInfo starts at
     * DEFAULT_DIFFICULTY (normal, WorldInfo.java:220), and the game options' difficulty set above is used only when
     * that is null (IntegratedServer.java:109), so the scenarios' worlds were on normal and spawned slimes.
     */
    static final class OriginSpawn {
        static final OriginSpawn INSTANCE = new OriginSpawn();
        private volatile WorldSettings expected;
        private boolean registered;

        synchronized void expect(WorldSettings settings) {
            expected = settings;
            if (!registered) {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
                registered = true;
            }
        }

        @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
        public void onCreateSpawn(net.minecraftforge.event.world.WorldEvent.CreateSpawnPosition event) {
            if (event.getSettings() != expected || event.getWorld().provider.getDimension() != 0) {
                return;
            }
            event.getWorld().getWorldInfo().setSpawn(new BlockPos(0, 4, 0));
            event.getWorld().getWorldInfo().setDifficulty(EnumDifficulty.PEACEFUL);
            event.setCanceled(true);
            expected = null;
        }
    }

    /**
     * Connects to a server by address, as the multiplayer screen does. 1.12.2 port: 26.x
     * {@code ConnectScreen.startConnecting} is a {@code GuiConnecting} screen.
     */
    static void connect(Minecraft mc, String address) {
        mc.displayGuiScreen(new GuiConnecting(new GuiMainMenu(), mc, new ServerData("dropin-selftest", address, false)));
    }

    /** A command typed by this client's player (no leading slash). 1.12.2 port: 26.x {@code connection.sendCommand}. */
    static void sendCommand(Minecraft mc, String command) {
        mc.player.sendChatMessage("/" + command);
    }

    /** A command run by the server itself (operator rights), on the server thread. */
    static void serverCommand(MinecraftServer server, String command) {
        server.addScheduledTask(() -> server.getCommandManager().executeCommand(server, command));
    }

    /** The block at a position of the server's overworld, loading its chunk if needed. */
    static Block blockAt(MinecraftServer server, BlockPos pos) {
        return server.getWorld(0).getBlockState(pos).getBlock();
    }

    private static volatile boolean chunkCallback;

    /**
     * Keeps one overworld chunk loaded, as 26.x {@code /forceload add} does. 1.12.2 port: there is no forceload
     * command; Forge's chunk manager does it with a ticket of DropIn's mod (whose callback releases the tickets
     * again when the world is next loaded).
     */
    static void forceLoadChunk(MinecraftServer server, int cx, int cz) {
        server.addScheduledTask(() -> {
            ModContainer container = Loader.instance().getIndexedModList().get(E4mcClient.MOD_ID);
            if (container == null) {
                throw new IllegalStateException("mod " + E4mcClient.MOD_ID + " is not loaded");
            }
            Object mod = container.getMod();
            if (!chunkCallback) {
                ForgeChunkManager.setForcedChunkLoadingCallback(mod, (tickets, world) -> { });
                chunkCallback = true;
            }
            WorldServer world = server.getWorld(0);
            ForgeChunkManager.Ticket ticket = ForgeChunkManager.requestTicket(mod, world, ForgeChunkManager.Type.NORMAL);
            if (ticket == null) {
                DropIn.LOGGER.error("Self-test: no chunk-loading ticket for chunk {},{}", cx, cz);
                return;
            }
            ForgeChunkManager.forceChunk(ticket, new ChunkPos(cx, cz));
            world.getChunkProvider().provideChunk(cx, cz);
        });
    }

    /**
     * Generates (and so saves) every overworld chunk in the given chunk range. 1.12.2 port: 26.x scenarios
     * force-load a few hundred chunks to make the world bigger; here they are generated once and kept by the save.
     */
    static void generateChunks(MinecraftServer server, int minCx, int minCz, int maxCx, int maxCz) {
        server.addScheduledTask(() -> {
            WorldServer world = server.getWorld(0);
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    world.getChunkProvider().provideChunk(cx, cz);
                }
            }
        });
    }

    /**
     * True if a chunk record (the chunk's NBT as stored, {@link ChunkBytes}) holds the block anywhere.
     * 1.12.2 port: 26.x chunks name their blocks in palettes, so the scenarios searched the bytes for the name;
     * 1.12.2 stores numeric ids ({@code Blocks} with the high bits in {@code Add}) per section.
     */
    static boolean chunkHasBlock(byte[] chunk, Block block) throws IOException {
        int wanted = Block.getIdFromBlock(block);
        NBTTagCompound level = ChunkBytes.parse(chunk).getCompoundTag("Level");
        NBTTagList sections = level.getTagList("Sections", 10);
        for (int s = 0; s < sections.tagCount(); s++) {
            NBTTagCompound section = sections.getCompoundTagAt(s);
            byte[] ids = section.getByteArray("Blocks");
            byte[] add = section.hasKey("Add", 7) ? section.getByteArray("Add") : null;
            for (int i = 0; i < ids.length; i++) {
                int id = ids[i] & 0xFF;
                if (add != null && i / 2 < add.length) {
                    id |= ((add[i / 2] >> ((i & 1) * 4)) & 0xF) << 8;
                }
                if (id == wanted) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if the player carries a matching stack anywhere (26.x {@code Inventory.contains(Predicate)}). */
    static boolean holds(EntityPlayer player, Predicate<ItemStack> test) {
        if (player == null) {
            return false;
        }
        for (List<ItemStack> part : List.of(player.inventory.mainInventory, player.inventory.armorInventory,
                player.inventory.offHandInventory)) {
            for (ItemStack stack : part) {
                if (!stack.isEmpty() && test.test(stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final Pattern POPULATION = Pattern.compile("(\\d+)/(\\d+)");

    /**
     * The number of players online in a status response, or null before one arrived. 1.12.2 port: the pinger
     * keeps it only as the formatted text {@code online/max} in {@code ServerData.populationInfo}.
     */
    static Integer onlinePlayers(ServerData data) {
        String text = TextFormatting.getTextWithoutFormattingCodes(data.populationInfo);
        if (text == null) {
            return null;
        }
        Matcher m = POPULATION.matcher(text.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : null;
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
