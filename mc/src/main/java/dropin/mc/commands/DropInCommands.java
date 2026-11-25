package dropin.mc.commands;

import dropin.mc.coop.JoinCodeCommands;
import dropin.mc.savepoint.SavePointCommands;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 1.12.2 port: the game has no Brigadier; DropIn's commands are two {@code CommandBase} roots registered at
 * {@code FMLServerStartingEvent}. The command tree is the same as 26.x's:
 * <pre>
 *   /dropin savepoint create &lt;name&gt; | list | restore &lt;name&gt;   (DESIGN 4.16)
 *   /dropin code [share]  and  /coop code [share]                  (join code)
 * </pre>
 * Everyone may run them, as on 26.x; each subcommand checks who is asking itself (owner, key holder).
 */
public final class DropInCommands {
    private DropInCommands() {
    }

    public static void register(MinecraftServer server, Consumer<ICommand> registrar) {
        registrar.accept(new Root("dropin", true));
        registrar.accept(new Root("coop", false));
    }

    static final class Root extends CommandBase {
        private final String name;
        private final boolean savePoints;

        Root(String name, boolean savePoints) {
            this.name = name;
            this.savePoints = savePoints;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return savePoints ? "/dropin <code [share] | savepoint <create|list|restore> [name]>" : "/coop code [share]";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
            return true;
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length >= 1 && args[0].equals("code")) {
                if (args.length == 1) {
                    JoinCodeCommands.show(sender);
                    return;
                }
                if (args.length == 2 && args[1].equals("share")) {
                    JoinCodeCommands.share(sender);
                    return;
                }
            } else if (savePoints && args.length >= 2 && args[0].equals("savepoint")) {
                switch (args[1]) {
                    case "create" -> {
                        if (args.length == 3) {
                            SavePointCommands.create(sender, args[2]);
                            return;
                        }
                    }
                    case "list" -> {
                        if (args.length == 2) {
                            SavePointCommands.list(sender);
                            return;
                        }
                    }
                    case "restore" -> {
                        if (args.length == 3) {
                            SavePointCommands.restore(sender, args[2]);
                            return;
                        }
                    }
                    default -> {
                    }
                }
            }
            throw new WrongUsageException(getUsage(sender));
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
            if (args.length == 1) {
                return savePoints ? getListOfStringsMatchingLastWord(args, "code", "savepoint")
                        : getListOfStringsMatchingLastWord(args, "code");
            }
            if (args.length == 2 && args[0].equals("code")) {
                return getListOfStringsMatchingLastWord(args, "share");
            }
            if (args.length == 2 && savePoints && args[0].equals("savepoint")) {
                return getListOfStringsMatchingLastWord(args, "create", "list", "restore");
            }
            return Collections.emptyList();
        }
    }
}
