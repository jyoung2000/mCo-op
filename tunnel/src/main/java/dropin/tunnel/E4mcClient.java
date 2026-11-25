package dropin.tunnel;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.command.server.CommandBanPlayer;
import net.minecraft.command.server.CommandListBans;
import net.minecraft.command.server.CommandPardonPlayer;
import net.minecraft.command.server.CommandWhitelist;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

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

    /** DropIn hook (DESIGN section 3 row 6): wraps the handler every accepted connection is set up with. */
    public static volatile java.util.function.UnaryOperator<io.netty.channel.ChannelHandler> childHandlerWrapper;

    public static io.netty.channel.ChannelHandler wrapChildHandler(io.netty.channel.ChannelHandler handler) {
        java.util.function.UnaryOperator<io.netty.channel.ChannelHandler> wrapper = childHandlerWrapper;
        return wrapper == null ? handler : wrapper.apply(handler);
    }

    public static void init() {
        Config.INSTANCE.id(); // Touch to initialize for McQoy
    }

    /**
     * 1.12.2 port: the game has no Brigadier; commands are {@link ICommand}s registered when the server starts. The
     * dedicated-server ban and whitelist commands (which 1.12.2's integrated server does not register) are subclassed
     * so the singleplayer owner may use them, which upstream did with mixins on the 26.x command builders.
     */
    public static void registerCommands(MinecraftServer server, Consumer<ICommand> register) {
        if (Config.INSTANCE.restoreDedicatedCommands.value() && Agnos.isClient() && !server.isDedicatedServer()) {
            register.accept(new CommandBanPlayer() {
                @Override
                public boolean checkPermission(MinecraftServer s, ICommandSender sender) {
                    return isOwner(s, sender) || super.checkPermission(s, sender);
                }
            });
            register.accept(new CommandListBans() {
                @Override
                public boolean checkPermission(MinecraftServer s, ICommandSender sender) {
                    return isOwner(s, sender) || super.checkPermission(s, sender);
                }
            });
            register.accept(new CommandPardonPlayer() {
                @Override
                public boolean checkPermission(MinecraftServer s, ICommandSender sender) {
                    return isOwner(s, sender) || super.checkPermission(s, sender);
                }
            });
            register.accept(new CommandWhitelist() {
                @Override
                public boolean checkPermission(MinecraftServer s, ICommandSender sender) {
                    return isOwner(s, sender) || super.checkPermission(s, sender);
                }
            });
        }
        register.accept(new E4mcCommand());
    }

    private static boolean isOwner(MinecraftServer server, ICommandSender sender) {
        return sender.getCommandSenderEntity() instanceof EntityPlayerMP player && Mirror.isSingleplayerOwner(server, player);
    }

    /** {@code /e4mc stop|doctor|restart|conninfo <player>}. */
    static final class E4mcCommand extends CommandBase {
        private static final List<String> SUBCOMMANDS = Arrays.asList("stop", "doctor", "restart", "conninfo");

        @Override
        public String getName() {
            return "e4mc";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/e4mc <stop|doctor|restart|conninfo <player>>";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 4;
        }

        @Override
        public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
            if (server == null) {
                return false;
            }
            if (server.isDedicatedServer()) {
                return sender.canUseCommand(4, getName());
            }
            return isOwner(server, sender);
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length == 0) {
                throw new WrongUsageException(getUsage(sender));
            }
            switch (args[0]) {
                case "stop" -> {
                    if ((session != null) && (session.state != QuiclimeSession.State.STOPPED)) {
                        session.stop();
                        Mirror.sendSuccessToSource(sender, Mirror.translatable("text.dropin.closeServer"));
                    } else {
                        Mirror.sendFailureToSource(sender, Mirror.translatable("text.dropin.serverAlreadyClosed"));
                    }
                }
                case "doctor" -> {
                    var thread = new Thread(() -> {
                        LOGGER.info("generating e4mc doctor report");
                        Mirror.sendSuccessToSource(sender, Mirror.translatable("text.dropin.doctor.start"));
                        var diag = Doctor.doctor();
                        LOGGER.info("e4mc doctor report:\n{}", diag);
                        Mirror.sendSuccessToSource(sender, Mirror.literal(diag));
                    }, "e4mc_minecraft-doctor");
                    thread.setDaemon(true);
                    thread.start();
                }
                case "restart" -> {
                    if ((session != null) && (session.state != QuiclimeSession.State.STARTED)) {
                        session.stop();
                        session = new QuiclimeSession(session.handler, session.group);
                        session.startAsync();
                    }
                }
                case "conninfo" -> {
                    if (args.length < 2) {
                        throw new WrongUsageException(getUsage(sender));
                    }
                    EntityPlayerMP player = getPlayer(server, sender, args[1]);
                    var diag = ((DialtoneConnectionExtensions) player.connection.netManager).e4mc$connInfo();
                    LOGGER.info("e4mc connection information report:\n{}", diag);
                    Mirror.sendSuccessToSource(sender, Mirror.literal(diag));
                }
                default -> throw new WrongUsageException(getUsage(sender));
            }
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
            if (args.length == 1) {
                return getListOfStringsMatchingLastWord(args, SUBCOMMANDS);
            }
            if (args.length == 2 && "conninfo".equals(args[0])) {
                return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
            }
            return Collections.emptyList();
        }
    }
}
