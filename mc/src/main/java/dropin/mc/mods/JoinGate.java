package dropin.mc.mods;

import dropin.core.mods.ModDiff;
import dropin.core.world.CoopWorld;
import dropin.mc.DropIn;
import dropin.mc.coop.Prompts;
import dropin.mc.member.SyncClient;
import dropin.mc.menu.ModConsentScreen;
import dropin.mc.policy.PolicyActions;
import dropin.mc.policy.WorldPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import java.io.IOException;

/**
 * "Member is behind" (DESIGN 4.14): before this PC connects to a live host it asks the host for the owner's
 * policy and compares the mod list with its own {@code mods/} folder. A match connects; a mismatch shows
 * the consent screen, and nothing is downloaded without that click.
 */
public final class JoinGate {
    private static final String K = "text.dropin_coop.";
    private static final long POLICY_TIMEOUT_MS = 15_000;

    private JoinGate() {
    }

    /**
     * Any thread.
     *
     * @param back the screen a failed connection or a cancelled consent returns to
     */
    public static void join(Minecraft mc, CoopWorld world, String address, GuiScreen back) {
        Thread thread = new Thread(() -> check(mc, world, address, back), "dropin-coop-join-gate");
        thread.setDaemon(true);
        thread.start();
    }

    /** 26.x {@code CoopWorldsScreen.TITLE}. */
    private static String title() {
        return I18n.format("menu.dropin_coop.title");
    }

    private static void check(Minecraft mc, CoopWorld world, String address, GuiScreen back) {
        Runnable onCancel = () -> mc.displayGuiScreen(back);
        WorldPolicy.Held held = WorldPolicy.load(world, PolicyActions.folder(mc, world));
        try (PolicyLink link = PolicyLink.open(mc, world, address)) {
            if (link.await(POLICY_TIMEOUT_MS)) {
                if (link.rejected() != null) {
                    // DESIGN 4.13: only the key the invite names can make a policy. Do not connect on a forged one.
                    show(mc, new Prompts.Alert(onCancel, title(), new TextComponentTranslation(K + "policy.rejected", link.rejected())));
                    return;
                }
                held = link.effective();
            } else {
                DropIn.LOGGER.warn("The host of '{}' did not answer the policy request; using the policy stored here", world.name());
            }
        } catch (IOException e) {
            DropIn.LOGGER.warn("Could not ask the host of '{}' for its policy ({}); using the policy stored here", world.name(), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (held != null) {
            try {
                ITextComponent game = WorldPolicy.environmentProblem(held);
                if (game != null) {
                    show(mc, new Prompts.Alert(onCancel, title(), game));
                    return;
                }
                ModDiff diff = WorldPolicy.diff(held);
                if (!diff.mayJoin()) {
                    DropIn.LOGGER.info("Co-op world '{}' needs other mods (policy version {}): {} to download, {} to remove",
                            world.name(), held.version(), diff.toDownload().size(), diff.toRemove().size());
                    WorldPolicy.Held shown = held;
                    mc.addScheduledTask(() -> mc.displayGuiScreen(new ModConsentScreen(onCancel, world, shown, diff, address, false)));
                    return;
                }
            } catch (IOException | RuntimeException e) {
                DropIn.LOGGER.error("Could not compare this PC's mods with the mod list of '{}'", world.name(), e);
                show(mc, new Prompts.Alert(onCancel, title(),
                        new TextComponentTranslation(K + "policy.failed", String.valueOf(e.getMessage()))));
                return;
            }
        }
        mc.addScheduledTask(() -> {
            // 1.12.2 port: 26.x ConnectScreen.startConnecting. GuiConnecting connects from its constructor
            // (after loadWorld(null)) and returns to `back` if the connection fails.
            mc.displayGuiScreen(new GuiConnecting(back, mc, new ServerData(world.name(), address, false)));
            SyncClient.start(mc, world, address);
        });
    }

    private static void show(Minecraft mc, GuiScreen screen) {
        mc.addScheduledTask(() -> mc.displayGuiScreen(screen));
    }
}
