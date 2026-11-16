package dropin.tunnel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dropin.mixin.tunnel.ServerCommonPacketListenerImplAccessor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.commands.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class E4mcClient {
    public static final String MOD_ID = "dropin";
    public static QuiclimeSession session;
    public static final Logger LOGGER = LoggerFactory.getLogger(E4mcClient.MOD_ID);

    /** DropIn hook (DESIGN section 3 row 8): receives the public domain once the relay has assigned one. */
    public static volatile java.util.function.Consumer<String> domainListener;

    static void onDomainAssigned(String domain) {
        java.util.function.Consumer<String> listener = domainListener;
        if (listener != null) {
            listener.accept(domain);
        }
    }

    public static void init() {
        Config.INSTANCE.id(); // Touch to initialize for McQoy
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (Config.INSTANCE.restoreDedicatedCommands.value() && Agnos.isClient()) {
            BanListCommands.register(dispatcher);
            BanPlayerCommands.register(dispatcher);
            PardonCommand.register(dispatcher);
            WhitelistCommand.register(dispatcher);
        }
        dispatcher.register(
                Commands.literal("e4mc")
                        .requires(src -> {
                            if (src.getServer() == null) {
                                return false;
                            }
                            if (src.getServer().isDedicatedServer()) {
                                return Commands.hasPermission(Commands.LEVEL_OWNERS).test(src);
                            } else {
                                try {
                                    return Mirror.isSingleplayerOwner(src.getServer(), src.getPlayerOrException());
                                } catch (CommandSyntaxException e) {
                                    return false;
                                }
                            }
                        })
                        .then(Commands.literal("stop").executes(ctx -> {
                            if ((session != null) && (session.state != QuiclimeSession.State.STOPPED)) {
                                session.stop();
                                Mirror.sendSuccessToSource(ctx.getSource(), Mirror.translatable("text.dropin.closeServer"));
                            } else {
                                Mirror.sendFailureToSource(ctx.getSource(), Mirror.translatable("text.dropin.serverAlreadyClosed"));
                            }
                            return 1;
                        }))
                        .then(Commands.literal("doctor").executes(ctx -> {
                            var thread = new Thread(() -> {
                                LOGGER.info("generating e4mc doctor report");
                                Mirror.sendSuccessToSource(ctx.getSource(), Mirror.translatable("text.dropin.doctor.start"));
                                var diag = Doctor.doctor();
                                LOGGER.info("e4mc doctor report:\n{}", diag);
                                Mirror.sendSuccessToSource(ctx.getSource(), Mirror.literal(diag));
                            }, "e4mc_minecraft-doctor");
                            thread.setDaemon(true);
                            thread.start();
                            return 1;
                        }))
                        .then(Commands.literal("restart").executes(ctx -> {
                            if ((session != null) && (session.state != QuiclimeSession.State.STARTED)) {
                                session.stop();
                                session = new QuiclimeSession(session.handler, session.group);
                                session.startAsync();
                            }
                            return 1;
                        }))
                        .then(Commands.literal("conninfo").then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                            var diag = ((DialtoneConnectionExtensions) ((ServerCommonPacketListenerImplAccessor) EntityArgument.getPlayer(ctx, "player").connection).getConnection()).e4mc$connInfo();
                            LOGGER.info("e4mc connection information report:\n{}", diag);
                            Mirror.sendSuccessToSource(ctx.getSource(), Mirror.literal(diag));
                            return 1;
                        })))
        );
    }
}
