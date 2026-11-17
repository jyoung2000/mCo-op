package dropin.mc.net;

import dropin.core.sync.PathInfo;
import dropin.mc.DropIn;
import dropin.mc.coop.CoopWorlds;
import dropin.tunnel.dialtone.DialtoneChannel;
import io.netty.channel.Channel;
import io.netty.channel.socket.SocketChannel;

/**
 * DESIGN 2 limit 3 and 4.6: bulk world data only flows over a direct path (LAN or port-forwarded TCP,
 * or a Dialtone connection whose path in use is direct) unless {@code bulkSyncOverRelay} is on.
 */
public final class PathPolicy {
    private PathPolicy() {
    }

    /** Whether a connection accepted by the host goes straight to the peer, not through a relay. */
    public static boolean direct(Channel channel) {
        // Development only (DESIGN 4.12): lets the self-test exercise the relayed case on one PC.
        String forced = DropIn.switches().read("dropin.dev.forcePath").orElse("");
        if (forced.equals("relayed")) {
            return false;
        }
        if (forced.equals("direct")) {
            return true;
        }
        if (channel instanceof DialtoneChannel dialtone) {
            try {
                return PathInfo.direct(dialtone.debugInfo());
            } catch (Throwable e) {
                return false;
            }
        }
        // The vanilla TCP listener: a LAN or port-forwarded connection. A relay connection arrives as a
        // QUIC stream of the tunnel instead, which is not a socket channel.
        return channel instanceof SocketChannel;
    }

    public static boolean bulkAllowed(Channel channel) {
        return direct(channel) || CoopWorlds.configFlag("bulkSyncOverRelay", false);
    }
}
