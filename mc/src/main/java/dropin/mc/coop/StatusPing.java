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

    private static final long SECOND_TRY_MS = 2_000;

    /** The players the server names in its status answer (the game sends up to 12), or null if it did not answer. */
    public static synchronized java.util.List<Player> players(String address, long timeoutMillis) {
        ServerData data = new ServerData("dropin", address, ServerData.Type.OTHER);
        ServerStatusPinger pinger = new ServerStatusPinger();
        try {
            pinger.pingServer(data, () -> {
            }, () -> {
            }, EventLoopGroupHolder.remote(false));
            long started = System.currentTimeMillis();
            long deadline = started + timeoutMillis;
            // Seen on this project's test machine: now and then a status connection is made and never answered,
            // while the host is up. So after two seconds without an answer a second connection asks as well;
            // either answer counts, and the time limit stays the same.
            ServerData second = null;
            while (System.currentTimeMillis() < deadline) {
                pinger.tick();
                ServerData answered = data.players != null ? data : second != null && second.players != null ? second : null;
                if (answered != null) {
                    java.util.List<Player> list = new java.util.ArrayList<>();
                    answered.players.sample().forEach(player -> list.add(new Player(player.id().toString(), player.name())));
                    if (answered == second) {
                        dropin.mc.DropIn.LOGGER.info("The first status ping of {} got no answer; the second did", address);
                    }
                    return list;
                }
                if (second == null && System.currentTimeMillis() - started >= SECOND_TRY_MS) {
                    second = new ServerData("dropin", address, ServerData.Type.OTHER);
                    pinger.pingServer(second, () -> {
                    }, () -> {
                    }, EventLoopGroupHolder.remote(false));
                }
                Thread.sleep(50);
            }
            // To the log only: why a host that is announced counted as not there.
            dropin.mc.DropIn.LOGGER.info("No status answer from {} within {} ms (the game's pinger says: {})", address, timeoutMillis,
                    data.motd == null ? "" : data.motd.getString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            dropin.mc.DropIn.LOGGER.info("The status ping of {} failed: {}", address, e.toString());
            return null;
        } finally {
            pinger.removeAll();
        }
    }
}
