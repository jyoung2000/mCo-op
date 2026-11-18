package dropin.mc.coop;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.server.network.EventLoopGroupHolder;

/**
 * A blocking Minecraft status ping, for background threads. The address is resolved the way the game
 * resolves it, so an e4mc domain works (DESIGN 4.3: "Liveness is a ping, not the record").
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

    /** The players the server names in its status answer (the game sends up to 12), or null if it did not answer. */
    public static synchronized java.util.List<Player> players(String address, long timeoutMillis) {
        ServerData data = new ServerData("dropin", address, ServerData.Type.OTHER);
        ServerStatusPinger pinger = new ServerStatusPinger();
        try {
            pinger.pingServer(data, () -> {
            }, () -> {
            }, EventLoopGroupHolder.remote(false));
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                pinger.tick();
                if (data.players != null) {
                    java.util.List<Player> list = new java.util.ArrayList<>();
                    data.players.sample().forEach(player -> list.add(new Player(player.id().toString(), player.name())));
                    return list;
                }
                Thread.sleep(50);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            pinger.removeAll();
        }
    }
}
