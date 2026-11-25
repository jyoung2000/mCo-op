package dropin.mc.savepoint;

import dropin.core.savepoint.SavePoints.Point;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.host.HostSync;
import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

import java.util.List;

/**
 * {@code /dropin savepoint create|list|restore} (DESIGN 4.16): for the owner, while the owner hosts.
 *
 * <p>1.12.2 port: there is no Brigadier; dropin.mc.commands.DropInCommands parses the arguments and calls these.
 * A failure (26.x {@code sendFailure}) is a red message to the sender.
 */
public final class SavePointCommands {
    private static final String K = "text.dropin_coop.savepoint.";

    private SavePointCommands() {
    }

    private static void success(ICommandSender sender, ITextComponent message) {
        sender.sendMessage(message);
    }

    private static void failure(ICommandSender sender, ITextComponent message) {
        message.getStyle().setColor(TextFormatting.RED);
        sender.sendMessage(message);
    }

    /** The host's sync if the command comes from the owner playing at this PC, else null (and the sender is told). */
    private static HostSync owner(ICommandSender sender) {
        HostSync sync = HostSync.current();
        EntityPlayerMP player = sender.getCommandSenderEntity() instanceof EntityPlayerMP p ? p : null;
        Minecraft mc = Minecraft.getMinecraft();
        if (sync == null || player == null || !player.getUniqueID().equals(mc.getSession().getProfile().getId())
                || !CoopWorlds.store().isOwner(sync.world().worldId())) {
            failure(sender, new TextComponentTranslation(K + "owner_only"));
            return null;
        }
        return sync;
    }

    public static void create(ICommandSender sender, String name) {
        HostSync sync = owner(sender);
        if (sync == null) {
            return;
        }
        MinecraftServer server = sender.getServer();
        Thread thread = new Thread(() -> {
            try {
                Point point = SavePointActions.createHosted(sync, name);
                DropIn.LOGGER.info("Save point '{}' of '{}': checkpoint {}, {} records", point.name(), sync.world().name(),
                        point.seq(), point.records());
                server.addScheduledTask(() -> success(sender, new TextComponentTranslation(K + "created", point.name(), point.records())));
            } catch (Exception e) {
                DropIn.LOGGER.warn("Save point '{}' was not made: {}", name, e.toString());
                server.addScheduledTask(() -> failure(sender, new TextComponentTranslation(K + "failed", String.valueOf(e.getMessage()))));
            }
        }, "dropin-coop-savepoint");
        thread.setDaemon(true);
        thread.start();
    }

    public static void list(ICommandSender sender) {
        HostSync sync = owner(sender);
        if (sync == null) {
            return;
        }
        try {
            List<Point> points = SavePointActions.store(sync.folder()).list();
            if (points.isEmpty()) {
                success(sender, new TextComponentTranslation(K + "empty"));
            }
            for (Point point : points) {
                success(sender, new TextComponentTranslation(K + "line", point.name(), point.seq(), point.records()));
            }
        } catch (Exception e) {
            failure(sender, new TextComponentTranslation(K + "failed", String.valueOf(e.getMessage())));
        }
    }

    public static void restore(ICommandSender sender, String name) {
        HostSync sync = owner(sender);
        if (sync == null) {
            return;
        }
        try {
            SavePointActions.restoreHosted(Minecraft.getMinecraft(), name);
        } catch (Exception e) {
            failure(sender, new TextComponentTranslation(K + "failed", String.valueOf(e.getMessage())));
        }
    }
}
