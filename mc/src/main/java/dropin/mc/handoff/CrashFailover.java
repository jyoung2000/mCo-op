package dropin.mc.handoff;

import dropin.core.sync.Election;
import dropin.core.world.CoopWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A member's memory of the session it is in, for the moment the host vanishes (DESIGN 4.9, crash): which world,
 * which address, who hosts, and the host's last list of members. When the game connection ends without a
 * handoff and without the player leaving, {@link HostLostScreen} takes it from there.
 */
public final class CrashFailover {
    public enum Role { NONE, ELECTED, FOLLOWER }

    /** The remote session this game is in. */
    private record Session(CoopWorld world, String address) {
    }

    /** World id to {uuid, name} of the host this game last joined. */
    private static final Map<String, String[]> HOSTS = new ConcurrentHashMap<>();
    private static volatile Session session;
    private static volatile List<Election.Peer> peers = List.of();
    private static volatile boolean expected;
    private static volatile Role lastRole = Role.NONE;
    private static volatile long takeoverSeq;
    private static volatile String takeoverRoot = "";
    private static volatile long resolvedAfterMs;
    private static volatile List<String> lastOrder = List.of();

    private CrashFailover() {
    }

    /** Who hosts the session of this world that the game is about to join. */
    public static void host(String worldId, String uuid, String name) {
        HOSTS.put(worldId, new String[]{uuid == null ? "" : uuid, name == null ? "" : name});
    }

    public static String hostUuid(String worldId) {
        String[] host = HOSTS.get(worldId);
        return host == null ? "" : host[0];
    }

    public static String hostName(String worldId) {
        String[] host = HOSTS.get(worldId);
        return host == null ? "" : host[1];
    }

    /** The game starts connecting to a host of this world. */
    public static void joining(CoopWorld world, String address) {
        peers = List.of();
        expected = false;
        session = new Session(world, address);
    }

    /** The world of the remote session this game is in, or null. */
    public static CoopWorld world() {
        Session now = session;
        return now == null ? null : now.world();
    }

    /** The host's list of members, as it just arrived on the sync connection. */
    public static void peers(String worldId, List<Election.Peer> list) {
        Session now = session;
        if (now != null && now.world().worldId().equals(worldId)) {
            peers = List.copyOf(list);
        }
    }

    /** The newest list of members the host sent in this session; empty before the first. */
    public static List<Election.Peer> peers() {
        return peers;
    }

    /** The game connection is about to end for a known reason (the player leaves, or the host hands over). */
    public static void expected() {
        expected = true;
    }

    /** Head of {@code Minecraft.disconnectFromWorld}: a member that leaves on purpose has not lost its host. */
    public static void leaving(Minecraft mc) {
        if (mc.getSingleplayerServer() == null) {
            expected = true;
        }
    }

    /**
     * The game connection ended and the game is about to show {@code vanilla} ("Connection lost"). Client thread.
     *
     * @param address the address of the connection that ended, as the game was given it
     * @return the screen to show instead, or null if this was not a co-op member losing its host unexpectedly
     */
    public static Screen lost(Minecraft mc, String address, Screen vanilla) {
        Session now = session;
        if (now == null || expected || mc.getSingleplayerServer() != null || !now.address().equals(address)) {
            return null;
        }
        session = null;
        return new HostLostScreen(now.world(), now.address(), hostUuid(now.world().worldId()), peers, vanilla);
    }

    static void resolved(Role role, List<String> order, long afterMs, long seq, String root) {
        lastOrder = order;
        resolvedAfterMs = afterMs;
        takeoverSeq = seq;
        takeoverRoot = root;
        lastRole = role;
    }

    /** What this game did the last time it lost its host: took over, or followed someone who did. */
    public static Role lastRole() {
        return lastRole;
    }

    /** The order of succession this game worked out the last time it lost its host. */
    public static List<String> lastOrder() {
        return lastOrder;
    }

    /** How long after the host was lost this game took over or found the new host (milliseconds). */
    public static long resolvedAfterMs() {
        return resolvedAfterMs;
    }

    /** The checkpoint this game's copy was at when it took over (0 if it never did). */
    public static long takeoverSeq() {
        return takeoverSeq;
    }

    /** The root hash of this game's copy when it took over ("" if it never did). */
    public static String takeoverRoot() {
        return takeoverRoot;
    }
}
