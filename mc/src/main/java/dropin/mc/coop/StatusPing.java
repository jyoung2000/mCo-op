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

    public static boolean answers(String address, long timeoutMillis) {
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
                    return true;
                }
                Thread.sleep(50);
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            pinger.removeAll();
        }
    }
}
