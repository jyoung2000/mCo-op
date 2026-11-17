package dropin.mc.savepoint;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dropin.core.savepoint.SavePoints.Point;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.host.HostSync;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** {@code /dropin savepoint create|list|restore} (DESIGN 4.16): for the owner, while the owner hosts. */
public final class SavePointCommands {
    private static final String K = "text.dropin_coop.savepoint.";

    private SavePointCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dropin").then(Commands.literal("savepoint")
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> create(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("list").executes(context -> list(context.getSource())))
                .then(Commands.literal("restore").then(Commands.argument("name", StringArgumentType.word())
                        .executes(context -> restore(context.getSource(), StringArgumentType.getString(context, "name")))))));
    }

    /** The host's sync if the command comes from the owner playing at this PC, else null (and the source is told). */
    private static HostSync owner(CommandSourceStack source) {
        HostSync sync = HostSync.current();
        ServerPlayer player = source.getPlayer();
        Minecraft mc = Minecraft.getInstance();
        if (sync == null || player == null || !player.getUUID().equals(mc.getUser().getProfileId())
                || !CoopWorlds.store().isOwner(sync.world().worldId())) {
            source.sendFailure(Component.translatable(K + "owner_only"));
            return null;
        }
        return sync;
    }

    private static int create(CommandSourceStack source, String name) {
        HostSync sync = owner(source);
        if (sync == null) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        Thread thread = new Thread(() -> {
            try {
                Point point = SavePointActions.createHosted(sync, name);
                DropIn.LOGGER.info("Save point '{}' of '{}': checkpoint {}, {} records", point.name(), sync.world().name(),
                        point.seq(), point.records());
                server.execute(() -> source.sendSuccess(() -> Component.translatable(K + "created", point.name(), point.records()), false));
            } catch (Exception e) {
                DropIn.LOGGER.warn("Save point '{}' was not made: {}", name, e.toString());
                server.execute(() -> source.sendFailure(Component.translatable(K + "failed", String.valueOf(e.getMessage()))));
            }
        }, "dropin-coop-savepoint");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static int list(CommandSourceStack source) {
        HostSync sync = owner(source);
        if (sync == null) {
            return 0;
        }
        try {
            List<Point> points = SavePointActions.store(sync.folder()).list();
            if (points.isEmpty()) {
                source.sendSuccess(() -> Component.translatable(K + "empty"), false);
            }
            for (Point point : points) {
                source.sendSuccess(() -> Component.translatable(K + "line", point.name(), point.seq(), point.records()), false);
            }
            return points.size();
        } catch (Exception e) {
            source.sendFailure(Component.translatable(K + "failed", String.valueOf(e.getMessage())));
            return 0;
        }
    }

    private static int restore(CommandSourceStack source, String name) {
        HostSync sync = owner(source);
        if (sync == null) {
            return 0;
        }
        try {
            SavePointActions.restoreHosted(Minecraft.getInstance(), name);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.translatable(K + "failed", String.valueOf(e.getMessage())));
            return 0;
        }
    }
}
