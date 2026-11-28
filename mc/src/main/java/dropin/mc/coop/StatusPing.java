package dropin.mc.coop;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.ServerStatusResponse;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.status.INetHandlerStatusClient;
import net.minecraft.network.status.client.CPacketServerQuery;
import net.minecraft.network.status.server.SPacketPong;
import net.minecraft.network.status.server.SPacketServerInfo;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A blocking Minecraft status ping, for background threads. The address is resolved the way the game
 * resolves it, so an e4mc domain works (DESIGN 4.3: "Liveness is a ping, not the record").
 * <p>
 * 1.12.2 port: the game's {@code ServerPinger} keeps only the names of the players a server lists, so this
 * sends the same two packets itself ({@code C00Handshake} for STATUS, then {@code CPacketServerQuery}) on a
 * connection made with {@code NetworkManager.createNetworkManagerAndConnect}, where the tunnel's mixin picks
 * Dialtone for an e4mc domain, and reads the profiles from the {@code SPacketServerInfo} answer.
 */
public final class StatusPing {
    private StatusPing() {
    }

    /**
     * One ping at a time: the first connection to an e4mc domain starts upstream's Dialtone session, and that
     * start is not safe to run from two threads at once (two endpoints are created and later connects hang).
     */
    public static boolean answers(String address, long timeoutMillis) {
        return players(address, timeoutMillis) != null;
    }

    /** A player the server names in its status answer. */
    public record Player(String uuid, String name) {
    }

    private static final long SECOND_TRY_MS = 2_000;
    /** DESIGN 4.3 "lan hint": a plain address gets 1 s to accept a connection. */
    static final int LAN_CONNECT_MS = 1_000;

    /**
     * The connect of {@code NetworkManager.createNetworkManagerAndConnect} blocks until the TCP connect is done,
     * with no time limit of its own (Netty's default is 30 s). From another network nothing answers the host's
     * LAN address, so every such ping would hang for half a minute before the e4mc domain is tried. A plain
     * {@code ip:port} therefore must accept a TCP connection within {@link #LAN_CONNECT_MS} first. Names (the
     * e4mc domain) are left to the game: the tunnel resolves and connects them.
     */
    static boolean reachable(String address) {
        ServerAddress parsed = ServerAddress.fromString(address);
        InetAddress ip;
        try {
            ip = com.google.common.net.InetAddresses.forString(parsed.getIP());
        } catch (IllegalArgumentException notAnIp) {
            return true;
        }
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(ip, parsed.getPort()), LAN_CONNECT_MS);
            return true;
        } catch (java.io.IOException e) {
            dropin.mc.DropIn.LOGGER.info("{} does not accept connections ({}); not pinging it", address, e.toString());
            return false;
        }
    }

    /** One status request on its own connection. */
    private static final class Request implements INetHandlerStatusClient {
        final NetworkManager connection;
        final AtomicReference<List<Player>> answer = new AtomicReference<>();
        volatile String closed = "";

        private Request(NetworkManager connection) {
            this.connection = connection;
        }

        static Request send(String address) throws java.io.IOException {
            // Forge decodes the reply into maps that only the server list screen creates (FMLClientHandlerAccess).
            net.minecraftforge.fml.client.FMLClientHandler fml = net.minecraftforge.fml.client.FMLClientHandler.instance();
            if (((dropin.mixin.mc.FMLClientHandlerAccess) (Object) fml).dropin$extraServerListData() == null) {
                fml.setupServerList();
            }
            ServerAddress parsed = ServerAddress.fromString(address);
            NetworkManager connection = NetworkManager.createNetworkManagerAndConnect(InetAddress.getByName(parsed.getIP()),
                    parsed.getPort(), Minecraft.getMinecraft().gameSettings.isUsingNativeTransport());
            Request request = new Request(connection);
            connection.setNetHandler(request);
            // As ServerPinger.ping: the FML marker tells a Forge server this is a Forge client.
            connection.sendPacket(new C00Handshake(parsed.getIP(), parsed.getPort(), EnumConnectionState.STATUS, true));
            connection.sendPacket(new CPacketServerQuery());
            return request;
        }

        @Override
        public void handleServerInfo(SPacketServerInfo packet) {
            List<Player> list = new ArrayList<>();
            ServerStatusResponse response = packet.getResponse();
            if (response != null && response.getPlayers() != null && response.getPlayers().getPlayers() != null) {
                for (GameProfile profile : response.getPlayers().getPlayers()) {
                    if (profile != null && profile.getId() != null) {
                        list.add(new Player(profile.getId().toString(), profile.getName()));
                    }
                }
            }
            answer.compareAndSet(null, list);
            connection.closeChannel(new TextComponentString("Finished"));
        }

        @Override
        public void handlePong(SPacketPong packet) {
        }

        @Override
        public void onDisconnect(ITextComponent reason) {
            closed = reason == null ? "" : reason.getUnformattedText();
        }

        /** As {@code ServerPinger.pingPendingNetworks}: flushes and notices a closed connection. */
        void tick() {
            if (connection.isChannelOpen()) {
                connection.processReceivedPackets();
            } else {
                connection.handleDisconnection();
            }
        }

        void close() {
            if (connection.isChannelOpen()) {
                connection.closeChannel(new TextComponentString("Cancelled"));
            }
        }
    }

    /** The players the server names in its status answer (the game sends up to 12), or null if it did not answer. */
    public static synchronized List<Player> players(String address, long timeoutMillis) {
        if (!reachable(address)) {
            return null;
        }
        Request first = null;
        Request second = null;
        try {
            first = Request.send(address);
            long started = System.currentTimeMillis();
            long deadline = started + timeoutMillis;
            // Seen on this project's test machine: now and then a status connection is made and never answered,
            // while the host is up. So after two seconds without an answer a second connection asks as well;
            // either answer counts, and the time limit stays the same.
            while (System.currentTimeMillis() < deadline) {
                first.tick();
                if (second != null) {
                    second.tick();
                }
                List<Player> answered = first.answer.get() != null ? first.answer.get() : second != null ? second.answer.get() : null;
                if (answered != null) {
                    if (first.answer.get() == null) {
                        dropin.mc.DropIn.LOGGER.info("The first status ping of {} got no answer; the second did", address);
                    }
                    return answered;
                }
                if (second == null && System.currentTimeMillis() - started >= SECOND_TRY_MS) {
                    second = Request.send(address);
                }
                Thread.sleep(50);
            }
            // To the log only: why a host that is announced counted as not there.
            dropin.mc.DropIn.LOGGER.info("No status answer from {} within {} ms (the connection says: {})", address, timeoutMillis,
                    first.closed);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            dropin.mc.DropIn.LOGGER.info("The status ping of {} failed: {}", address, e.toString());
            return null;
        } finally {
            if (first != null) {
                first.close();
            }
            if (second != null) {
                second.close();
            }
        }
    }
}
