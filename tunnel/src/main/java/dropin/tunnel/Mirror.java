package dropin.tunnel;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;

import java.util.function.UnaryOperator;

/**
 * Upstream's version adapter (it bridged 1.18 to 26.x through reflection). 1.12.2 port: one version, so plain calls;
 * the text type is 1.12.2's {@link ITextComponent}.
 */
public class Mirror {
    public static ClickEvent runCommand(String command) {
        return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
    }

    /** 1.12.2 has no copy-to-clipboard click action; the text is put into the chat box instead. */
    public static ClickEvent copyToClipboard(String text) {
        return new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, text);
    }

    public static HoverEvent showText(ITextComponent text) {
        return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
    }

    public static ITextComponent withStyle(ITextComponent component, UnaryOperator<Style> operator) {
        return component.setStyle(operator.apply(component.getStyle()));
    }

    public static ITextComponent append(ITextComponent component, ITextComponent other) {
        return component.appendSibling(other);
    }

    public static ITextComponent literal(String text) {
        return new TextComponentString(text);
    }

    public static ITextComponent translatable(String text, Object... args) {
        return new TextComponentTranslation(text, args);
    }

    public static void sendSuccessToSource(ICommandSender source, ITextComponent message) {
        source.sendMessage(message);
    }

    public static void sendFailureToSource(ICommandSender source, ITextComponent message) {
        message.getStyle().setColor(TextFormatting.RED);
        source.sendMessage(message);
    }

    /** The player who opened this singleplayer world (1.12.2: the integrated server's owner name). */
    public static boolean isSingleplayerOwner(MinecraftServer server, EntityPlayerMP player) {
        return isSingleplayerOwnerObj(server, player.getGameProfile());
    }

    public static boolean isSingleplayerOwnerObj(MinecraftServer server, Object maybeProfile) {
        if (server.isDedicatedServer() || !(maybeProfile instanceof GameProfile profile)) {
            return false;
        }
        String owner = server.getServerOwner();
        return owner != null && profile.getName() != null && owner.equals(profile.getName());
    }

    public static void setUsingWhitelist(MinecraftServer server, PlayerList playerList, boolean enabled) {
        playerList.setWhiteListEnabled(enabled);
    }

    /** DropIn: relay notices (the assigned domain, broadcasts) are logged instead of written to chat. */
    public static void addNotice(ITextComponent message) {
        E4mcClient.LOGGER.info("e4mc: {}", message.getUnformattedText());
    }

    public static void addMessage(ITextComponent message) {
        Minecraft.getMinecraft().addScheduledTask(() -> Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(message));
    }
}
