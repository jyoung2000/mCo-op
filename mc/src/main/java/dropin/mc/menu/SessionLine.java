package dropin.mc.menu;

import dropin.core.ledger.LedgerEntry;
import dropin.core.sync.SyncClientSession;
import dropin.core.world.CoopWorld;
import dropin.mc.handoff.CrashFailover;
import dropin.mc.host.HostSync;
import dropin.mc.member.SyncClient;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;

/**
 * The status line of the pause menu while in a co-op session (MILESTONES M4): who hosts, how complete the
 * copy is, whether the sync goes straight to the host or through a relay, and how old the newest checkpoint is.
 * <p>1.12.2 port: the line is an {@code ITextComponent} (translated on the client through the resource-pack lang).
 */
public final class SessionLine {
    private static final String K = "text.dropin_coop.line.";

    /**
     * @param hosting     true if this game hosts the session
     * @param hostName    the host's player name ("" if not known)
     * @param percent     for a member: how much of the world its copy holds, 100 once it is complete through the
     *                    newest checkpoint it knows; for the host: the copy of the member furthest behind, -1 if
     *                    nobody is connected
     * @param direct      for a member: whether its sync connection goes straight to the host; null if there is
     *                    none yet, and for the host
     * @param ageSeconds  seconds since the newest checkpoint (for a member: the newest one it holds in full);
     *                    -1 if there is none
     */
    public record Info(boolean hosting, String hostName, int percent, Boolean direct, long ageSeconds) {
    }

    private SessionLine() {
    }

    /** What the line says right now, or null when the game is not in a co-op session. */
    public static Info info(Minecraft mc) {
        if (mc.world == null) {
            return null;
        }
        long now = System.currentTimeMillis() / 1000;
        if (mc.getIntegratedServer() != null) {
            HostSync sync = HostSync.current();
            if (sync == null) {
                return null;
            }
            long at = sync.lastCheckpointSeconds();
            return new Info(true, mc.getSession().getUsername(), sync.slowestPeerPercent(), null, at == 0 ? -1 : Math.max(0, now - at));
        }
        CoopWorld world = CrashFailover.world();
        SyncClientSession.Status status = world == null ? null : SyncClient.status(world.worldId());
        if (status == null) {
            return null;
        }
        int percent = status.phase() == SyncClientSession.Phase.LIVE ? 100
                : status.total() <= 0 ? 0 : (int) Math.min(99, status.have() * 100L / status.total());
        LedgerEntry last = SyncClient.lastCheckpoint(world.worldId());
        return new Info(false, CrashFailover.hostName(world.worldId()), percent, SyncClient.direct(world.worldId()),
                last == null ? -1 : Math.max(0, now - last.ts()));
    }

    public static ITextComponent text(Info info) {
        ITextComponent age = info.ageSeconds() < 0 ? new TextComponentTranslation(K + "age_none")
                : info.ageSeconds() < 120 ? new TextComponentTranslation(K + "age_seconds", info.ageSeconds())
                : new TextComponentTranslation(K + "age_minutes", info.ageSeconds() / 60);
        if (info.hosting()) {
            return info.percent() < 0 ? new TextComponentTranslation(K + "host_alone", age)
                    : new TextComponentTranslation(K + "host", info.percent(), age);
        }
        ITextComponent path = new TextComponentTranslation(K + (info.direct() == null ? "connecting" : info.direct() ? "direct" : "relayed"));
        ITextComponent name = info.hostName().isEmpty() ? new TextComponentTranslation("text.dropin_coop.handoff.a_friend") : new TextComponentString(info.hostName());
        return new TextComponentTranslation(K + "member", name, info.percent(), path, age);
    }
}
