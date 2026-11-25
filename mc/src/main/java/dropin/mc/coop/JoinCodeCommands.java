package dropin.mc.coop;

import dropin.core.world.CoopWorld;
import dropin.mc.host.HostSync;
import dropin.tunnel.Mirror;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

/**
 * {@code /coop code} and {@code /coop code share} (also under {@code /dropin}), DESIGN 4.4: show the join code
 * (invite) of the co-op world being played, or post it in chat for everyone in the session who already holds it.
 *
 * <p>An invite is full trust (DESIGN 2 limit 4), so the code goes only to players who have proven they hold the
 * world key: the host and members whose sync connection passed the handshake. Guests without the mod
 * (DESIGN 4.11) never see it, not even when someone shares it.
 *
 * <p>1.12.2 port: no Brigadier. {@code dropin.mc.commands.DropInCommands} parses the command and calls
 * {@link #show} and {@link #share}; 26.x's {@code sendFailure} is a red message.
 */
public final class JoinCodeCommands {
    private static final String K = "text.dropin_coop.code.";

    private JoinCodeCommands() {
    }

    private static EntityPlayerMP player(ICommandSender source) {
        return source.getCommandSenderEntity() instanceof EntityPlayerMP player ? player : null;
    }

    private static void failure(ICommandSender source, String key) {
        source.sendMessage(styled(new TextComponentTranslation(key), TextFormatting.RED));
    }

    private static ITextComponent styled(ITextComponent text, TextFormatting color) {
        text.getStyle().setColor(color);
        return text;
    }

    /** The hosted world's sync if the source is a player who holds its key, else null (and the source is told). */
    private static HostSync holder(ICommandSender source) {
        HostSync sync = HostSync.current();
        EntityPlayerMP player = player(source);
        if (sync == null || player == null) {
            failure(source, player != null && HostSync.unsyncedWorld(source.getServer()) != null ? K + "sync_failed" : K + "not_coop");
            return null;
        }
        if (!sync.holdsWorldKey(player.getUniqueID().toString())) {
            failure(source, K + "not_holder");
            return null;
        }
        return sync;
    }

    /**
     * The world this game hosts without a sync (its start failed), if the command comes from the host itself.
     * The code needs no sync, so the host can still see it; nobody else has proven they hold the key.
     */
    private static CoopWorld unsyncedForHost(ICommandSender source) {
        CoopWorld world = HostSync.unsyncedWorld(source.getServer());
        EntityPlayerMP player = player(source);
        if (world == null || player == null
                || !player.getUniqueID().equals(net.minecraft.client.Minecraft.getMinecraft().getSession().getProfile().getId())) {
            return null;
        }
        return world;
    }

    /** {@code /coop code}. */
    public static void show(ICommandSender source) {
        CoopWorld unsynced = HostSync.current() == null ? unsyncedForHost(source) : null;
        if (unsynced != null) {
            source.sendMessage(new TextComponentTranslation(K + "show", unsynced.name(), code(unsynced)));
            source.sendMessage(styled(new TextComponentTranslation(K + "sync_failed"), TextFormatting.RED));
            return;
        }
        HostSync sync = holder(source);
        if (sync == null) {
            return;
        }
        source.sendMessage(new TextComponentTranslation(K + "show", sync.world().name(), code(sync.world())));
        source.sendMessage(styled(new TextComponentTranslation(K + "warning"), TextFormatting.GRAY));
    }

    /** {@code /coop code share}. */
    public static void share(ICommandSender source) {
        HostSync sync = holder(source);
        if (sync == null) {
            return;
        }
        MinecraftServer server = source.getServer();
        ITextComponent message = new TextComponentTranslation(K + "shared", player(source).getDisplayName(), sync.world().name(),
                code(sync.world()));
        int sent = 0;
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            if (sync.holdsWorldKey(player.getUniqueID().toString())) {
                player.sendMessage(message);
                sent++;
            }
        }
        int guests = server.getPlayerList().getPlayers().size() - sent;
        if (guests > 0) {
            source.sendMessage(styled(new TextComponentTranslation(K + "guests_skipped", guests), TextFormatting.GRAY));
        }
    }

    /**
     * The code, green and underlined; hovering says what clicking does. 1.12.2 port: there is no copy-to-clipboard
     * click action, so clicking puts the code into the chat box ({@link Mirror#copyToClipboard}).
     */
    private static ITextComponent code(CoopWorld world) {
        String code = world.invite().encode();
        ITextComponent text = new TextComponentString(code);
        text.getStyle().setColor(TextFormatting.GREEN).setUnderlined(true)
                .setClickEvent(Mirror.copyToClipboard(code))
                .setHoverEvent(Mirror.showText(new TextComponentTranslation(K + "click_to_copy")));
        return text;
    }
}
