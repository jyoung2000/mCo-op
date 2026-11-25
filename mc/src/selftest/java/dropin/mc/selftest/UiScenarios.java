package dropin.mc.selftest;

import dropin.core.world.CoopWorld;
import dropin.core.world.CoopWorldStore;
import dropin.core.world.WorldArchive;
import dropin.mc.DropIn;
import dropin.mc.compat.LevelBasedPermissionSet;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.host.HostSync;
import dropin.mc.menu.CoopActions;
import dropin.mc.menu.CoopStatus;
import dropin.mc.menu.CoopWorldsScreen;
import dropin.mc.menu.ImportScreen;
import dropin.mc.menu.InviteScreen;
import dropin.mc.menu.JoinInviteScreen;
import dropin.mc.menu.PlayersScreen;
import dropin.mc.menu.ui.DButton;
import dropin.mc.menu.ui.DScreen;
import dropin.mc.policy.PolicyActions;
import dropin.mc.selftest.M1Scenarios.State;
import dropin.mc.selftest.Scenarios.Context;
import dropin.tunnel.Mirror;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.init.MobEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static dropin.mc.selftest.M1Scenarios.require;
import static dropin.mc.selftest.M2Scenarios.copyComplete;
import static dropin.mc.selftest.M2Scenarios.hostA;
import static dropin.mc.selftest.M2Scenarios.joinB;
import static dropin.mc.selftest.M3Scenarios.players;
import static dropin.mc.selftest.Scenarios.PEER_TICKS;
import static dropin.mc.selftest.Scenarios.SHORT_TICKS;
import static dropin.mc.selftest.Scenarios.failIfDisconnected;

/** Admins (DESIGN 4.13) and who is online in the menu (DESIGN 4.15). Development only. */
final class UiScenarios {
    private UiScenarios() {
    }

    static StepRunner build(String key, Context ctx) {
        shared = ctx;
        return switch (key) {
            case "admin-online:a" -> adminA(ctx);
            case "admin-online:b" -> adminB(ctx);
            case "menu-flows:solo" -> menuFlows(ctx);
            case "pvp:a" -> pvpA(ctx);
            case "pvp:b" -> pvpB(ctx);
            case "realtime:a" -> realtimeA(ctx);
            case "realtime:b" -> realtimeB(ctx);
            case "join-code:a" -> joinCodeA(ctx);
            case "join-code:b" -> joinCodeB(ctx);
            default -> null;
        };
    }

    private static String me(Minecraft mc) {
        return mc.getSession().getProfile().getId().toString();
    }

    // ---- /coop code and /coop code share: the world's own join code in chat, clickable to copy ----

    private static final String CODE_KEY = "text.dropin_coop.code.";

    /**
     * True if a chat line is the message {@code key} (JoinCodeCommands: "show" is "Join code for ...", "shared"
     * is "... shared the join code for ...") and carries the code as a click-to-copy part.
     * <p>
     * 1.12.2 port: the line is recognised by its translation key instead of its English text, so the check does not
     * depend on the language file. 1.12.2 has no copy-to-clipboard click action; the main code's
     * {@link Mirror#copyToClipboard} puts the code into the chat box, and that is the click action looked for.
     */
    @SuppressWarnings("unchecked")
    private static boolean chatHas(Minecraft mc, String key, String code) {
        List<ChatLine> lines;
        try {
            // GuiNewChat keeps every received line in the private field chatLines (MCP name; development only).
            java.lang.reflect.Field field = GuiNewChat.class.getDeclaredField("chatLines");
            field.setAccessible(true);
            lines = List.copyOf((List<ChatLine>) field.get(mc.ingameGUI.getChatGUI()));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read the chat", e);
        }
        ClickEvent wanted = Mirror.copyToClipboard(code);
        for (ChatLine line : lines) {
            ITextComponent content = line.getChatComponent();
            if (!(content instanceof TextComponentTranslation translation) || !translation.getKey().equals(CODE_KEY + key)
                    || !content.getUnformattedText().contains(code)) {
                continue;
            }
            List<ITextComponent> parts = new ArrayList<>();
            parts(content, parts);
            for (ITextComponent part : parts) {
                ClickEvent click = part.getStyle().getClickEvent();
                if (click != null && click.getAction() == wanted.getAction() && click.getValue().equals(code)
                        && part.getUnformattedComponentText().equals(code)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A component, the components among its translation arguments, and its siblings, all the way down. */
    private static void parts(ITextComponent component, List<ITextComponent> out) {
        out.add(component);
        if (component instanceof TextComponentTranslation translation) {
            for (Object arg : translation.getFormatArgs()) {
                if (arg instanceof ITextComponent child) {
                    parts(child, out);
                }
            }
        }
        for (ITextComponent sibling : component.getSiblings()) {
            parts(sibling, out);
        }
    }

    private static StepRunner joinCodeA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        String[] code = {null};
        return hostA(ctx, state, Map.of(), 0)
                .step("B dropped in and has its copy", 2 * PEER_TICKS, () -> players(mc) == 2 && ctx.sharedExists("b-ready"))
                .action("/coop code", () -> {
                    code[0] = state.world.invite().encode();
                    mc.player.sendChatMessage("/coop code");
                })
                .step("A's chat shows this world's code, clickable", SHORT_TICKS,
                        () -> chatHas(mc, "show", code[0]))
                .action("tell B", () -> ctx.writeShared("a-code", code[0]))
                .step("B shared the code", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("b-shared") && chatHas(mc, "shared", code[0]);
                })
                .action("done", () -> {
                    DropIn.LOGGER.info("Self-test: /coop code and B's /coop code share reached A's chat");
                    ctx.writeShared("a-done", "ok");
                });
    }

    private static StepRunner joinCodeB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        String[] code = {null};
        return joinB(ctx, state, Map.of())
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("tell A", () -> ctx.writeShared("b-ready", "ok"))
                .step("A saw its code", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("a-code");
                })
                .action("/dropin code", () -> {
                    code[0] = ctx.readShared("a-code");
                    require(code[0].equals(state.world.invite().encode()), "A's code is not the code of the world B joined");
                    mc.player.sendChatMessage("/dropin code");
                })
                .step("B's chat shows the same code", SHORT_TICKS,
                        () -> chatHas(mc, "show", code[0]))
                .action("/coop code share", () -> mc.player.sendChatMessage("/coop code share"))
                .step("B's chat shows it was shared", SHORT_TICKS,
                        () -> chatHas(mc, "shared", code[0]))
                .action("tell A", () -> ctx.writeShared("b-shared", "ok"))
                .step("A saw it", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("a-done");
                });
    }

    /** Asks for a fresh status every second; true once the announced record says the player is in the session. */
    private static boolean online(State state, String uuid, int[] ticks) {
        if (ticks[0]++ % 20 == 0) {
            CoopStatus.refresh(state.world);
        }
        return CoopStatus.of(state.world.worldId()).isOnline(uuid);
    }

    private static StepRunner adminA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        String[] b = {null};
        int[] ticks = {0};
        CoopStatus.Status[] seen = {null};
        return hostA(ctx, state, Map.of(), 0)
                .step("B dropped in and has its copy", 2 * PEER_TICKS, () -> players(mc) == 2 && ctx.sharedExists("b-ready"))
                .action("B is a plain member", () -> {
                    String me = me(mc);
                    for (EntityPlayerMP player : mc.getIntegratedServer().getPlayerList().getPlayers()) {
                        if (!player.getGameProfile().getId().toString().equals(me)) {
                            b[0] = player.getGameProfile().getId().toString();
                        }
                    }
                    require(b[0] != null, "B is not on the server");
                    Integer before = HostSync.permissions(mc.getIntegratedServer(), UUID.fromString(b[0]));
                    require(!Objects.equals(before, LevelBasedPermissionSet.ADMIN) && !Objects.equals(before, LevelBasedPermissionSet.OWNER),
                            "B already has admin rights");
                    require(Objects.equals(HostSync.permissions(mc.getIntegratedServer(), UUID.fromString(me)), LevelBasedPermissionSet.OWNER),
                            "the owner does not have owner rights");
                })
                .step("the announcement names both players as online", PEER_TICKS,
                        () -> online(state, b[0], ticks) && online(state, me(mc), ticks))
                // What the Join screen shows before joining: the invite alone, a lookup and the host's own answer.
                // The menu asks again every few seconds, so one missed answer is not a failure here either.
                .step("the preview names the host and both players", PEER_TICKS, () -> {
                    if (seen[0] != null && seen[0].kind() == CoopStatus.Kind.PLAYING) {
                        return true;
                    }
                    if (ticks[0]++ % 40 == 0) {
                        CoopWorld byInvite = CoopActions.previewInvite(state.world.invite().encode());
                        require(byInvite != null && byInvite.worldId().equals(state.world.worldId()), "the invite does not name this world");
                        Thread thread = new Thread(() -> {
                            CoopStatus.Status status = CoopStatus.lookup(byInvite);
                            DropIn.LOGGER.info("Self-test: lookup by invite says {}", status.kind());
                            seen[0] = status;
                        }, "selftest-join-preview");
                        thread.setDaemon(true);
                        thread.start();
                    }
                    return false;
                })
                .action("check the preview", () -> {
                    CoopStatus.Status status = seen[0];
                    require(status.kind() == CoopStatus.Kind.PLAYING && status.hostUuid().equals(me(mc)), "preview: " + status);
                    require(status.online().size() == 2 && status.online().stream().anyMatch(player -> player.uuid().equals(b[0]))
                            && status.online().stream().noneMatch(player -> player.name().isEmpty()), "online players: " + status.online());
                    DropIn.LOGGER.info("Self-test: the join preview names {}", status.online());
                })
                .action("make B an admin", () -> {
                    PolicyActions.setAdmin(mc, state.world, b[0], true);
                    require(Objects.equals(HostSync.permissions(mc.getIntegratedServer(), UUID.fromString(b[0])), LevelBasedPermissionSet.ADMIN),
                            "the host does not give the admin admin rights");
                    require(PlayersScreen.rowsOf(state.world.worldId()).stream().anyMatch(row -> row.uuid().equals(b[0]) && row.admin()
                            && row.playingNow()), "A's player list does not show B as an online admin");
                    DropIn.LOGGER.info("Self-test: B is an admin under policy version {}", HostSync.current().held().version());
                    ctx.writeShared("a-admin", "ok");
                })
                .step("B saw it", PEER_TICKS, () -> ctx.sharedExists("b-done"))
                .action("take it away again", () -> {
                    PolicyActions.setAdmin(mc, state.world, b[0], false);
                    require(!Objects.equals(HostSync.permissions(mc.getIntegratedServer(), UUID.fromString(b[0])), LevelBasedPermissionSet.ADMIN),
                            "B kept admin rights");
                });
    }

    // ---- pvp and movement: the session is the game's own server, so the game's own rules must hold in it ----

    private static EntityPlayer seenPlayer(Minecraft mc, String uuid) {
        if (mc.world == null) {
            return null;
        }
        for (EntityPlayer player : new ArrayList<>(mc.world.playerEntities)) {
            if (player.getUniqueID().toString().equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    private static Context shared;
    private static String pigId;

    /**
     * The pig the host spawned for this run, on this client's screen. By its id: the self-test world is kept
     * between runs, so pigs of earlier runs may still be around, and each client would pick a different one.
     */
    private static EntityPig pig(Minecraft mc) {
        try {
            if (pigId == null && shared.sharedExists("pig-id")) {
                pigId = shared.readShared("pig-id").trim();
            }
        } catch (java.io.IOException e) {
            return null;
        }
        if (mc.world != null && pigId != null) {
            for (Entity entity : new ArrayList<>(mc.world.loadedEntityList)) {
                if (entity instanceof EntityPig pig && pig.getUniqueID().toString().equals(pigId)) {
                    return pig;
                }
            }
        }
        return null;
    }

    private static List<EntityPig> serverPigs(WorldServer world) {
        List<EntityPig> pigs = new ArrayList<>();
        for (Entity entity : new ArrayList<>(world.loadedEntityList)) {
            if (entity instanceof EntityPig pig && !pig.isDead) {
                pigs.add(pig);
            }
        }
        return pigs;
    }

    /** On the server thread: removes the pigs of earlier runs, spawns this run's pig without AI and names it to both clients. */
    private static void spawnPig(MinecraftServer server, Context ctx, double x, double y, double z) {
        WorldServer world = server.getWorld(0);
        serverPigs(world).forEach(Entity::setDead);
        // 26.x EntityType.spawn(level, BlockPos.containing(x, y, z), COMMAND): at the middle of that block.
        BlockPos pos = new BlockPos(x, y, z);
        EntityPig pig = new EntityPig(world);
        pig.setLocationAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0f, 0.0f);
        pig.setNoAI(true);
        require(world.spawnEntity(pig), "could not add the pig");
        try {
            ctx.writeShared("pig-id", pig.getUniqueID().toString());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 1.12.2 port: 26.x {@code ServerPlayer.teleportTo}: the game's own /tp call for a player. */
    private static void teleport(EntityPlayerMP player, double x, double y, double z) {
        player.connection.setPlayerLocation(x, y, z, player.rotationYaw, player.rotationPitch);
    }

    private static double horizontalSpeedSqr(Entity entity) {
        return entity.motionX * entity.motionX + entity.motionZ * entity.motionZ;
    }

    /** A real attack, as a left click on the other player does it. */
    private static void hit(Minecraft mc, String uuid) {
        EntityPlayer target = seenPlayer(mc, uuid);
        require(target != null, "the other player is not visible");
        require(mc.player.getDistance(target) < 3.0f, "the other player is " + mc.player.getDistance(target) + " blocks away");
        mc.playerController.attackEntity(mc.player, target);
    }

    /**
     * True once the other player stands still within reach of this one (the host). A hit knocks its victim back,
     * so until then the host puts the other player two blocks away again, every two seconds.
     */
    private static boolean near(Minecraft mc, String uuid, int[] ticks) {
        EntityPlayer seen = seenPlayer(mc, uuid);
        boolean still = seen != null && seen.onGround && mc.player.onGround && horizontalSpeedSqr(mc.player) < 1.0e-6;
        if (still && mc.player.getDistance(seen) < 2.6f && ticks[0] % 40 >= 20) {
            ticks[0] = 0;
            return true;
        }
        if (ticks[0]++ % 40 == 0 && (seen == null || mc.player.getDistance(seen) >= 2.6f)) {
            IntegratedServer server = mc.getIntegratedServer();
            UUID meId = mc.getSession().getProfile().getId();
            server.addScheduledTask(() -> {
                EntityPlayerMP a = server.getPlayerList().getPlayerByUUID(meId);
                EntityPlayerMP other = server.getPlayerList().getPlayerByUUID(UUID.fromString(uuid));
                if (a != null && other != null) {
                    teleport(other, a.posX + 2.0, a.posY, a.posZ);
                }
            });
        }
        return false;
    }

    private static StepRunner pvpA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        String[] b = {null};
        int[] ticks = {0};
        double[] start = new double[3];
        boolean[] anim = {false};
        boolean[] hurt = {false};
        return hostA(ctx, state, Map.of(), 0)
                .step("B dropped in", 2 * PEER_TICKS, () -> players(mc) == 2 && ctx.sharedExists("b-ready"))
                // DESIGN 4.4 "Game modes": the world is created creative, so a forced game type would be creative.
                // 1.12.2 port: MinecraftServer.getForceGamemode() is 26.x getForcedGameType() != null.
                .step("the host forces no game mode on members", 20, () -> !mc.getIntegratedServer().getForceGamemode())
                .action("survival, pvp on, B two blocks from A", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    UUID meId = mc.getSession().getProfile().getId();
                    b[0] = ctx.readShared("b-ready").trim();
                    server.addScheduledTask(() -> {
                        EntityPlayerMP a = server.getPlayerList().getPlayerByUUID(meId);
                        EntityPlayerMP other = server.getPlayerList().getPlayerByUUID(UUID.fromString(b[0]));
                        server.setAllowPvp(true);
                        a.setGameType(GameType.SURVIVAL);
                        other.setGameType(GameType.SURVIVAL);
                        teleport(other, a.posX + 2.0, a.posY, a.posZ);
                    });
                })
                .step("A sees B next to it", PEER_TICKS, () -> {
                    EntityPlayer seen = seenPlayer(mc, b[0]);
                    if (seen == null || mc.player.getDistance(seen) > 2.6f || !seen.onGround || ++ticks[0] < 40) {
                        return false;
                    }
                    require(mc.player.getHealth() == 20.0f, "A is not at full health: " + mc.player.getHealth());
                    start[0] = seen.posX;
                    start[1] = seen.posY;
                    start[2] = seen.posZ;
                    ctx.writeShared("a-setup", me(mc));
                    return true;
                })
                .step("B's hit hurt A, with the hurt animation", PEER_TICKS, () -> {
                    // Kept once seen: a fed player heals one point within half a second.
                    anim[0] |= mc.player.hurtTime > 0;
                    hurt[0] |= mc.player.getHealth() < 20.0f;
                    return ctx.sharedExists("b-hit") && hurt[0] && anim[0];
                })
                // B's hit knocked A back, out of reach.
                .step("B is next to A again", PEER_TICKS, () -> near(mc, b[0], ticks))
                .action("hit back", () -> {
                    DropIn.LOGGER.info("Self-test: B's hit took A to {} health", mc.player.getHealth());
                    anim[0] = false;
                    hurt[0] = false;
                    hit(mc, b[0]);
                })
                .step("A's hit hurt B on the host, and A sees B's hurt animation", PEER_TICKS, () -> {
                    EntityPlayerMP other = mc.getIntegratedServer().getPlayerList().getPlayerByUUID(UUID.fromString(b[0]));
                    EntityPlayer seen = seenPlayer(mc, b[0]);
                    anim[0] |= seen != null && seen.hurtTime > 0;
                    hurt[0] |= other != null && other.getHealth() < 20.0f;
                    if (other == null || !hurt[0] || !anim[0]) {
                        return false;
                    }
                    ctx.writeShared("a-hit", String.valueOf(other.getHealth()));
                    return true;
                })
                .step("B felt it", PEER_TICKS, () -> ctx.sharedExists("b-hurt"))
                .action("pvp off, both healed", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    UUID meId = mc.getSession().getProfile().getId();
                    ticks[0] = 0;
                    server.addScheduledTask(() -> {
                        server.setAllowPvp(false);
                        EntityPlayerMP a = server.getPlayerList().getPlayerByUUID(meId);
                        EntityPlayerMP other = server.getPlayerList().getPlayerByUUID(UUID.fromString(b[0]));
                        a.setHealth(20.0f);
                        other.setHealth(20.0f);
                        teleport(other, a.posX + 2.0, a.posY, a.posZ);
                    });
                })
                .step("both healed", PEER_TICKS, () -> {
                    EntityPlayer seen = seenPlayer(mc, b[0]);
                    if (mc.player.getHealth() < 20.0f || !near(mc, b[0], ticks)) {
                        if (ticks[0] % 400 == 0) {
                            DropIn.LOGGER.info("Self-test: waiting: A health {}, B {}", mc.player.getHealth(),
                                    seen == null ? "not visible" : mc.player.getDistance(seen) + " blocks away, health " + seen.getHealth());
                        }
                        return false;
                    }
                    ticks[0] = 0;
                    ctx.writeShared("pvp-off", "ok");
                    return true;
                })
                // Three seconds after B's second hit: with pvp off it must not hurt.
                .step("B's hit with pvp off does nothing", PEER_TICKS, () -> ctx.sharedExists("b-hit2") && ++ticks[0] > 60)
                .action("A is unhurt", () -> {
                    require(mc.player.getHealth() == 20.0f, "pvp is off but B's hit took A to " + mc.player.getHealth());
                    hit(mc, b[0]);
                    ticks[0] = 0;
                })
                .step("A's hit with pvp off does nothing", PEER_TICKS, () -> ++ticks[0] > 60)
                .action("B is unhurt; a pig next to the players", () -> {
                    EntityPlayerMP unhurt = mc.getIntegratedServer().getPlayerList().getPlayerByUUID(UUID.fromString(b[0]));
                    require(unhurt.getHealth() == 20.0f, "pvp is off but A's hit took B to " + unhurt.getHealth());
                    IntegratedServer server = mc.getIntegratedServer();
                    UUID meId = mc.getSession().getProfile().getId();
                    server.addScheduledTask(() -> {
                        EntityPlayerMP a = server.getPlayerList().getPlayerByUUID(meId);
                        spawnPig(server, ctx, a.posX + 1.0, a.posY, a.posZ + 1.0);
                    });
                })
                // Not before B has the pig too: a client that gets an entity after the hit gets its lower health but,
                // as in the game without this mod, no hurt animation for a hit that is already over.
                .step("A and B see the pig", PEER_TICKS, () -> {
                    if (pig(mc) != null && mc.player.getDistance(pig(mc)) < 3.0f && ctx.sharedExists("b-pig")) {
                        return true;
                    }
                    if (++ticks[0] % 200 == 0) {
                        IntegratedServer server = mc.getIntegratedServer();
                        EntityPlayerMP other = server.getPlayerList().getPlayerByUUID(UUID.fromString(b[0]));
                        StringBuilder pigs = new StringBuilder();
                        for (EntityPig pig : serverPigs(server.getWorld(0))) {
                            pigs.append(pig.getPositionVector()).append(' ');
                        }
                        DropIn.LOGGER.info("Self-test: waiting for the pig: A's screen {}, B says {}, host has pigs at [{}], A at {}, B at {} (alive {}, mode {})",
                                pig(mc) == null ? "none" : mc.player.getDistance(pig(mc)) + " blocks away", ctx.sharedExists("b-pig"), pigs,
                                mc.player.getPositionVector(), other.getPositionVector(), other.isEntityAlive(),
                                other.interactionManager.getGameType());
                    }
                    return false;
                })
                .action("hit the pig", () -> {
                    anim[0] = false;
                    start[0] = pig(mc).getHealth();
                    mc.playerController.attackEntity(mc.player, pig(mc));
                })
                .step("the pig is hurt and plays its hurt animation", PEER_TICKS, () -> {
                    EntityPig pig = pig(mc);
                    anim[0] |= pig != null && pig.hurtTime > 0;
                    return anim[0] && pig != null && pig.getHealth() < start[0];
                })
                .action("B walks", () -> {
                    EntityPlayerMP other = mc.getIntegratedServer().getPlayerList().getPlayerByUUID(UUID.fromString(b[0]));
                    DropIn.LOGGER.info("Self-test: hurt animations seen on A for A, B and the pig");
                    start[0] = other.posX;
                    start[1] = other.posY;
                    start[2] = other.posZ;
                    ctx.writeShared("walk", "ok");
                })
                .step("B walked and stopped", PEER_TICKS, () -> ctx.sharedExists("b-pos"))
                .action("A sees B where B is", () -> {
                    String[] at = ctx.readShared("b-pos").trim().split(",");
                    double x = Double.parseDouble(at[0]);
                    double y = Double.parseDouble(at[1]);
                    double z = Double.parseDouble(at[2]);
                    EntityPlayerMP other = mc.getIntegratedServer().getPlayerList().getPlayerByUUID(UUID.fromString(b[0]));
                    EntityPlayer seen = seenPlayer(mc, b[0]);
                    require(seen != null, "A no longer sees B");
                    double walked = Math.sqrt((x - start[0]) * (x - start[0]) + (z - start[2]) * (z - start[2]));
                    double onHost = Math.sqrt(other.getDistanceSq(x, y, z));
                    double onA = Math.sqrt(seen.getDistanceSq(x, y, z));
                    DropIn.LOGGER.info("Self-test: B walked {} blocks; the host has B {} blocks from where B is, A's screen {} blocks",
                            String.format("%.2f", walked), String.format("%.3f", onHost), String.format("%.3f", onA));
                    require(walked > 2.0, "B walked only " + walked + " blocks");
                    require(onHost < 0.05, "the host has B " + onHost + " blocks from where B is");
                    require(onA < 0.3, "A sees B " + onA + " blocks from where B is");
                    ctx.writeShared("a-done", "ok");
                });
    }

    private static StepRunner pvpB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        int[] ticks = {0};
        String[] a = {null};
        boolean[] anim = {false};
        boolean[] hurt = {false};
        return joinB(ctx, state, Map.of())
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("tell A", () -> ctx.writeShared("b-ready", me(mc)))
                .step("A set the fight up", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("a-setup") && mc.player.getHealth() == 20.0f && !mc.player.capabilities.disableDamage;
                })
                .action("hit A", () -> {
                    a[0] = ctx.readShared("a-setup").trim();
                    hit(mc, a[0]);
                    ctx.writeShared("b-hit", "ok");
                })
                .step("B sees A's hurt animation", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    EntityPlayer seen = seenPlayer(mc, a[0]);
                    return seen != null && seen.hurtTime > 0;
                })
                .step("A's hit hurt B, with the hurt animation", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    anim[0] |= mc.player.hurtTime > 0;
                    hurt[0] |= mc.player.getHealth() < 20.0f;
                    if (!ctx.sharedExists("a-hit") || !hurt[0] || !anim[0]) {
                        return false;
                    }
                    DropIn.LOGGER.info("Self-test: A's hit took B to {} health (the host says {})", mc.player.getHealth(), ctx.readShared("a-hit").trim());
                    ctx.writeShared("b-hurt", "ok");
                    return true;
                })
                .step("pvp is off", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("pvp-off") && mc.player.getHealth() == 20.0f;
                })
                .action("hit A again", () -> {
                    hit(mc, a[0]);
                    anim[0] = false;
                    ctx.writeShared("b-hit2", "ok");
                })
                .step("A says walk", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    if (pig(mc) == null && ++ticks[0] % 200 == 0) {
                        DropIn.LOGGER.info("Self-test: B has no pig: B at {}, on ground {}, {} entities, health {}, screen {}",
                                mc.player.getPositionVector(), mc.player.onGround, mc.world.loadedEntityList.size(), mc.player.getHealth(),
                                mc.currentScreen);
                    }
                    if (pig(mc) != null && !ctx.sharedExists("b-pig")) {
                        DropIn.LOGGER.info("Self-test: B first sees the pig, with {} health", pig(mc).getHealth());
                        ctx.writeShared("b-pig", "ok");
                        ticks[0] = 0;
                    }
                    anim[0] |= pig(mc) != null && pig(mc).hurtTime > 0;
                    // A is in the host's own process and may see the animation, and say "walk", a tick before
                    // the packet reaches B: B looks for it for up to five more seconds before failing.
                    return ctx.sharedExists("walk") && (anim[0] || ++ticks[0] > 100);
                })
                .action("A's hit with pvp off did nothing; walk forward", () -> {
                    require(anim[0], "B did not see the pig's hurt animation when A hit it; B's pig: "
                            + (pig(mc) == null ? "none" : "health " + pig(mc).getHealth() + ", " + mc.player.getDistance(pig(mc)) + " blocks away"));
                    DropIn.LOGGER.info("Self-test: hurt animations seen on B for A, B and the pig");
                    require(mc.player.getHealth() == 20.0f, "pvp is off but A's hit took B to " + mc.player.getHealth());
                    ticks[0] = 0;
                    // Away from A (A is at lower x), so the walk is not into the other player.
                    mc.player.rotationYaw = -90.0f;
                    mc.player.rotationPitch = 0.0f;
                })
                // The key is held each tick: the game lets go of every key when its window loses the focus.
                // 1.12.2 port: KeyBinding.setKeyBindState is 26.x KeyMapping.setDown.
                .step("two seconds of walking", SHORT_TICKS, () -> {
                    failIfDisconnected(mc);
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), true);
                    return ++ticks[0] > 40;
                })
                .action("stop", () -> {
                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
                    ticks[0] = 0;
                })
                .step("standing still", SHORT_TICKS, () -> ++ticks[0] > 40 && horizontalSpeedSqr(mc.player) < 1.0e-6)
                .action("tell A where B is", () -> ctx.writeShared("b-pos", mc.player.posX + "," + mc.player.posY + "," + mc.player.posZ))
                .step("A agrees", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("a-done");
                });
    }

    // ---- realtime: what the host's server does is on the other player's screen at once, and the other way ----

    /** The longest a change may take from the host's server to the other player's screen (both on this PC). */
    private static final long REALTIME_MS = 1_000;

    private static void stamp(Context ctx, String name, String extra) {
        try {
            ctx.writeShared(name, System.currentTimeMillis() + (extra.isEmpty() ? "" : "," + extra));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** True once B has seen the change; then the delay between the two time stamps is logged and checked. */
    private static boolean seenInTime(Context ctx, String what) throws java.io.IOException {
        if (!ctx.sharedExists("o-" + what)) {
            return false;
        }
        long delay = Long.parseLong(ctx.readShared("o-" + what).trim().split(",")[0])
                - Long.parseLong(ctx.readShared("t-" + what).trim().split(",")[0]);
        DropIn.LOGGER.info("Self-test: realtime {}: on the other player's screen after {} ms", what, delay);
        require(delay < REALTIME_MS, what + " took " + delay + " ms to reach the other player");
        return true;
    }

    private static int diamonds(Minecraft mc) {
        int count = 0;
        for (int slot = 0; slot < mc.player.inventory.getSizeInventory(); slot++) {
            ItemStack stack = mc.player.inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getItem() == Items.DIAMOND) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static StepRunner realtimeA(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        String[] b = {null};
        float[] pigHealth = {0.0f};
        return hostA(ctx, state, Map.of(), 0)
                .step("B dropped in", 2 * PEER_TICKS, () -> players(mc) == 2 && ctx.sharedExists("b-ready"))
                .action("the host changes a block", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    b[0] = ctx.readShared("b-ready").trim();
                    server.addScheduledTask(() -> {
                        EntityPlayerMP other = server.getPlayerList().getPlayerByUUID(UUID.fromString(b[0]));
                        other.setGameType(GameType.SURVIVAL);
                        BlockPos pos = new BlockPos(other.posX + 2.0, other.posY + 2.0, other.posZ);
                        server.getWorld(0).setBlockState(pos, Blocks.GOLD_BLOCK.getDefaultState(), 3);
                        stamp(ctx, "t-block", pos.getX() + "," + pos.getY() + "," + pos.getZ());
                    });
                })
                .step("B sees the block", PEER_TICKS, () -> seenInTime(ctx, "block"))
                .action("the host gives B three diamonds", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    server.addScheduledTask(() -> {
                        server.getPlayerList().getPlayerByUUID(UUID.fromString(b[0])).inventory
                                .addItemStackToInventory(new ItemStack(Items.DIAMOND, 3));
                        stamp(ctx, "t-item", "");
                    });
                })
                .step("B has them", PEER_TICKS, () -> seenInTime(ctx, "item"))
                .action("the host sets B's health and gives it an effect", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    server.addScheduledTask(() -> {
                        EntityPlayerMP other = server.getPlayerList().getPlayerByUUID(UUID.fromString(b[0]));
                        other.setHealth(7.0f);
                        other.addPotionEffect(new PotionEffect(MobEffects.SPEED, 1200));
                        stamp(ctx, "t-status", "");
                    });
                })
                .step("B has that health and effect", PEER_TICKS, () -> seenInTime(ctx, "status"))
                .action("a pig appears, then is moved next to B", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    server.addScheduledTask(() -> {
                        EntityPlayerMP other = server.getPlayerList().getPlayerByUUID(UUID.fromString(b[0]));
                        spawnPig(server, ctx, other.posX + 6.0, other.posY, other.posZ);
                    });
                })
                .step("A sees the pig", PEER_TICKS, () -> pig(mc) != null && ctx.sharedExists("b-pig"))
                .action("move the pig", () -> {
                    IntegratedServer server = mc.getIntegratedServer();
                    server.addScheduledTask(() -> {
                        EntityPlayerMP other = server.getPlayerList().getPlayerByUUID(UUID.fromString(b[0]));
                        double x = other.posX + 1.5;
                        for (EntityPig pig : serverPigs(server.getWorld(0))) {
                            pig.setPositionAndUpdate(x, other.posY, other.posZ);
                            pigHealth[0] = pig.getHealth();
                        }
                        stamp(ctx, "t-pig", x + "," + other.posY + "," + other.posZ);
                    });
                })
                .step("B sees the pig where the host put it", PEER_TICKS, () -> seenInTime(ctx, "pig"))
                // The other way: B hits the pig; the host's server and the host's own screen have the damage.
                .step("B's hit on the pig is on the host", PEER_TICKS, () -> {
                    if (!ctx.sharedExists("t-bhit") || pig(mc) == null || pig(mc).getHealth() >= pigHealth[0]) {
                        return false;
                    }
                    for (EntityPig pig : serverPigs(mc.getIntegratedServer().getWorld(0))) {
                        if (pig.getHealth() < pigHealth[0]) {
                            long delay = System.currentTimeMillis() - Long.parseLong(ctx.readShared("t-bhit").trim());
                            DropIn.LOGGER.info("Self-test: realtime hit: B's hit took the pig from {} to {} health, on A's screen within {} ms",
                                    pigHealth[0], pig.getHealth(), delay);
                            require(delay < REALTIME_MS, "B's hit took " + delay + " ms to reach the host's screen");
                            ctx.writeShared("a-done", "ok");
                            return true;
                        }
                    }
                    return false;
                });
    }

    private static StepRunner realtimeB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        return joinB(ctx, state, Map.of())
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("tell A", () -> ctx.writeShared("b-ready", me(mc)))
                .step("the block is there", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    if (!ctx.sharedExists("t-block")) {
                        return false;
                    }
                    String[] at = ctx.readShared("t-block").trim().split(",");
                    BlockPos pos = new BlockPos(Integer.parseInt(at[1]), Integer.parseInt(at[2]), Integer.parseInt(at[3]));
                    if (mc.world.getBlockState(pos).getBlock() != Blocks.GOLD_BLOCK) {
                        return false;
                    }
                    stamp(ctx, "o-block", "");
                    return true;
                })
                .step("three diamonds arrived", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    if (!ctx.sharedExists("t-item") || diamonds(mc) != 3) {
                        return false;
                    }
                    stamp(ctx, "o-item", "");
                    return true;
                })
                .step("health and effect arrived", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    if (!ctx.sharedExists("t-status") || mc.player.getHealth() != 7.0f
                            || !mc.player.isPotionActive(MobEffects.SPEED)) {
                        return false;
                    }
                    stamp(ctx, "o-status", "");
                    return true;
                })
                .step("B sees the pig", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    if (pig(mc) == null) {
                        return false;
                    }
                    ctx.writeShared("b-pig", "ok");
                    return true;
                })
                .step("the pig is where the host put it", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    if (!ctx.sharedExists("t-pig") || pig(mc) == null) {
                        return false;
                    }
                    String[] at = ctx.readShared("t-pig").trim().split(",");
                    if (pig(mc).getDistanceSq(Double.parseDouble(at[1]), Double.parseDouble(at[2]), Double.parseDouble(at[3])) > 0.01) {
                        return false;
                    }
                    stamp(ctx, "o-pig", "");
                    return true;
                })
                .action("hit the pig", () -> {
                    require(mc.player.getDistance(pig(mc)) < 3.0f, "the pig is " + mc.player.getDistance(pig(mc)) + " blocks away");
                    mc.playerController.attackEntity(mc.player, pig(mc));
                    stamp(ctx, "t-bhit", "");
                })
                .step("A agrees", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("a-done");
                });
    }

    // ---- menus, pressed by their buttons ----

    /** The first {@link DButton} of a menu screen whose label contains {@code text}, or null. */
    private static DButton buttonContaining(GuiScreen screen, String text) {
        if (!(screen instanceof DScreen menu)) {
            return null;
        }
        for (GuiButton button : menu.buttons()) {
            if (button instanceof DButton d && d.label() != null && d.label().contains(text)) {
                return d;
            }
        }
        return null;
    }

    /** A menu button by its translated label (1.12.2 port: labels are I18n strings). */
    private static DButton button(Minecraft mc, String key) {
        return buttonContaining(mc.currentScreen, I18n.format("text.dropin_coop." + key));
    }

    /** One client, no world open: the import picker, Export world, and the Join screen, driven by their buttons. */
    private static StepRunner menuFlows(Context ctx) {
        Minecraft mc = ctx.mc();
        CoopWorld[] world = {null};
        String[] invited = {null};
        return M1Scenarios.quitToTitle(M1Scenarios.createWorld(M1Scenarios.base(ctx), ctx), ctx)
                .action("open Co-op Worlds", () -> mc.displayGuiScreen(new CoopWorldsScreen(new GuiMainMenu())))
                .action("the top buttons are Create, Join, Import, left to right", () -> {
                    DButton create = button(mc, "button.create");
                    DButton join = button(mc, "button.join");
                    DButton imports = button(mc, "button.import");
                    require(create != null && join != null && imports != null, "a top button is missing");
                    require(create.x < join.x && join.x < imports.x && create.y == join.y
                            && join.y == imports.y, "the top buttons are not in the order Create, Join, Import");
                    imports.press();
                })
                // ---- Import: the picker lists the ordinary world; choosing it and pressing Import makes it co-op.
                .step("the picker lists the world", SHORT_TICKS, () -> mc.currentScreen instanceof ImportScreen
                        && buttonContaining(mc.currentScreen, Scenarios.WORLD_ID) != null)
                .action("choose it", () -> {
                    require(!button(mc, "button.import").enabled, "Import is active before a world is chosen");
                    buttonContaining(mc.currentScreen, Scenarios.WORLD_ID).press();
                })
                .action("press Import", () -> {
                    require(button(mc, "button.import").enabled, "Import is not active after choosing a world");
                    button(mc, "button.import").press();
                })
                .step("the invite of the new co-op world is shown", SHORT_TICKS, () -> mc.currentScreen instanceof InviteScreen)
                .action("it is a co-op world of this player", () -> {
                    world[0] = ((InviteScreen) mc.currentScreen).world();
                    Path folder = CoopActions.levelPath(mc, Scenarios.WORLD_ID);
                    require(CoopWorldStore.readMarker(folder).orElse("").equals(world[0].worldId()), "the world folder is not marked");
                    require(CoopWorlds.store().isOwner(world[0].worldId()), "the importer is not the owner");
                    mc.displayGuiScreen(new ImportScreen(new CoopWorldsScreen(new GuiMainMenu())));
                })
                .step("the picker no longer offers it", SHORT_TICKS, () -> ++ticks(ctx)[0] > 60 && mc.currentScreen instanceof ImportScreen
                        && buttonContaining(mc.currentScreen, Scenarios.WORLD_ID) == null)
                // ---- Export world: an ordinary world in a zip, which the import takes back.
                .action("export the world", () -> {
                    Path folder = CoopActions.levelPath(mc, Scenarios.WORLD_ID);
                    Path zip = CoopActions.exportWorld(mc, world[0], folder);
                    require(Files.size(zip) > 0 && zip.getParent().equals(folder.resolve(CoopWorldStore.MARKER_DIR).resolve("backups")),
                            "no zip in the backups folder");
                    Path back = WorldArchive.extractZip(zip, Files.createDirectories(ctx.sharedDir().resolve("exported")));
                    require(Files.isRegularFile(back.resolve("level.dat")), "the exported world has no level.dat");
                    require(!Files.exists(back.resolve(CoopWorldStore.MARKER_DIR)), "the exported world is still linked to the co-op world");
                    DropIn.LOGGER.info("Self-test: exported {} ({} bytes), extracted as {}", zip.getFileName(), Files.size(zip), back.getFileName());
                })
                // ---- Join: paste an invite, see that nobody is online, join: the world is saved to the list.
                .action("open Join", () -> {
                    CoopWorldsScreen list = new CoopWorldsScreen(new GuiMainMenu());
                    mc.displayGuiScreen(list);
                    button(mc, "button.join").press();
                })
                .action("type something that is no invite", () -> {
                    require(mc.currentScreen instanceof JoinInviteScreen, "Join did not open its screen");
                    ((JoinInviteScreen) mc.currentScreen).setCode("not an invite");
                    require(!button(mc, "button.join").enabled, "Join is active for a text that is no invite");
                })
                .action("paste an invite", () -> {
                    var invite = M1Scenarios.randomInvite();
                    invited[0] = dropin.core.crypto.WorldKeys.fromSeed(invite.seed()).worldId();
                    ((JoinInviteScreen) mc.currentScreen).setCode("  " + invite.encode() + " ");
                    require(button(mc, "button.join").enabled, "Join is not active for an invite");
                })
                .step("the preview says nobody is online", SHORT_TICKS, () -> {
                    CoopStatus.Status preview = ((JoinInviteScreen) mc.currentScreen).preview();
                    return preview != null && preview.kind() == CoopStatus.Kind.NOBODY && preview.online().isEmpty();
                })
                .action("join", () -> {
                    require(CoopWorlds.store().get(invited[0]).isEmpty(), "looking at an invite already saved the world");
                    button(mc, "button.join").press();
                })
                .step("back on the list, with both worlds, the owned one first", SHORT_TICKS, () -> {
                    if (!(mc.currentScreen instanceof CoopWorldsScreen list) || list.worlds().size() != 2) {
                        return false;
                    }
                    require(list.worlds().get(0).worldId().equals(world[0].worldId()) && list.worlds().get(1).worldId().equals(invited[0]),
                            "the owned world is not listed first");
                    require(list.roleOf(world[0].worldId()) == CoopWorldsScreen.Role.OWNER
                            && list.roleOf(invited[0]) == CoopWorldsScreen.Role.PLAYER, "roles on the list are wrong");
                    return true;
                });
    }

    private static final int[] TICKS = {0};

    private static int[] ticks(Context ctx) {
        return TICKS;
    }

    private static StepRunner adminB(Context ctx) {
        Minecraft mc = ctx.mc();
        State state = new State();
        int[] ticks = {0};
        String me = me(mc);
        return joinB(ctx, state, Map.of())
                .step("copy complete", 2 * PEER_TICKS, copyComplete(mc, state))
                .action("tell A", () -> {
                    require(PlayersScreen.rowsOf(state.world.worldId()).stream().noneMatch(PlayersScreen.Row::admin), "someone is an admin already");
                    ctx.writeShared("b-ready", "ok");
                })
                .step("A made B an admin", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    return ctx.sharedExists("a-admin");
                })
                // The signed policy reaches B over the sync channel; the player list is read from the announcement.
                .step("B's own menu data shows B as an online admin and A as the online owner", PEER_TICKS, () -> {
                    failIfDisconnected(mc);
                    online(state, me, ticks);
                    List<PlayersScreen.Row> rows = PlayersScreen.rowsOf(state.world.worldId());
                    return rows.stream().anyMatch(row -> row.uuid().equals(me) && row.admin() && !row.owner() && row.playingNow())
                            && rows.stream().anyMatch(row -> row.owner() && !row.admin() && row.playingNow());
                })
                .action("tell A", () -> {
                    DropIn.LOGGER.info("Self-test: B sees itself as an online admin");
                    ctx.writeShared("b-done", "ok");
                });
    }
}
