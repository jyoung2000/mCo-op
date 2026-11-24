package dropin.mc.coop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dropin.mc.host.HostSync;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /coop code} and {@code /coop code share} (also under {@code /dropin}), DESIGN 4.4: show the join code
 * (invite) of the co-op world being played, or post it in chat for everyone in the session who already holds it.
 *
 * <p>An invite is full trust (DESIGN 2 limit 4), so the code goes only to players who have proven they hold the
 * world key: the host and members whose sync connection passed the handshake. Guests without the mod
 * (DESIGN 4.11) never see it, not even when someone shares it.
 */
public final class JoinCodeCommands {
    private static final String K = "text.dropin_coop.code.";

    private JoinCodeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(root("coop"));
        dispatcher.register(root("dropin"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name).then(Commands.literal("code")
                .executes(context -> show(context.getSource()))
                .then(Commands.literal("share").executes(context -> share(context.getSource()))));
    }

    /** The hosted world's sync if the source is a player who holds its key, else null (and the source is told). */
    private static HostSync holder(CommandSourceStack source) {
        HostSync sync = HostSync.current();
        ServerPlayer player = source.getPlayer();
        if (sync == null || player == null) {
            source.sendFailure(Component.translatable(K + "not_coop"));
            return null;
        }
        if (!sync.holdsWorldKey(player.getUUID().toString())) {
            source.sendFailure(Component.translatable(K + "not_holder"));
            return null;
        }
        return sync;
    }

    private static int show(CommandSourceStack source) {
        HostSync sync = holder(source);
        if (sync == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(K + "show", sync.world().name(), code(sync)), false);
        source.sendSuccess(() -> Component.translatable(K + "warning").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int share(CommandSourceStack source) {
        HostSync sync = holder(source);
        if (sync == null) {
            return 0;
        }
        Component message = Component.translatable(K + "shared", source.getPlayer().getDisplayName(), sync.world().name(), code(sync));
        int sent = 0;
        for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
            if (sync.holdsWorldKey(player.getUUID().toString())) {
                player.sendSystemMessage(message);
                sent++;
            }
        }
        int guests = source.getServer().getPlayerList().getPlayers().size() - sent;
        if (guests > 0) {
            int count = guests;
            source.sendSuccess(() -> Component.translatable(K + "guests_skipped", count).withStyle(ChatFormatting.GRAY), false);
        }
        return sent;
    }

    /** The code, green and underlined; clicking it copies it, hovering says so. */
    private static MutableComponent code(HostSync sync) {
        String code = sync.world().invite().encode();
        return Component.literal(code).withStyle(style -> style.withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(code))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable(K + "click_to_copy"))));
    }
}
