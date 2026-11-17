package dropin.mc.menu;

import dropin.core.pkarr.RendezvousRecord;
import dropin.core.world.CoopWorld;
import dropin.mc.coop.CoopWorlds;
import dropin.mc.coop.StatusPing;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The live status line of each co-op world (DESIGN 4.4): a rendezvous lookup plus a ping, off the client thread. */
public final class CoopStatus {
    public enum Kind { LOOKING, PLAYING, NOBODY, UNREACHABLE }

    /** @param address the address that answered the ping, when {@code kind} is PLAYING */
    public record Status(Kind kind, String hostName, String hostUuid, String address) {
        static final Status LOOKING = new Status(Kind.LOOKING, "", "", "");
        static final Status NOBODY = new Status(Kind.NOBODY, "", "", "");
        static final Status UNREACHABLE = new Status(Kind.UNREACHABLE, "", "", "");
    }

    private static final long PING_TIMEOUT_MS = 5_000;
    private static final Map<String, Status> STATUS = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "dropin-coop-status");
        thread.setDaemon(true);
        return thread;
    });

    private CoopStatus() {
    }

    public static Status of(String worldId) {
        return STATUS.getOrDefault(worldId, Status.LOOKING);
    }

    /** Starts a fresh lookup; the old status stays visible until the new one is in. */
    public static void refresh(CoopWorld world) {
        STATUS.putIfAbsent(world.worldId(), Status.LOOKING);
        POOL.submit(() -> STATUS.put(world.worldId(), lookup(world)));
    }

    /** Blocking. Who is hosting this world right now, checked with a ping. */
    public static Status lookup(CoopWorld world) {
        try {
            Optional<RendezvousRecord> found = CoopWorlds.rendezvous(world).lookup();
            if (found.isPresent() && found.get().isState(RendezvousRecord.HOST)) {
                RendezvousRecord record = found.get();
                for (String address : new String[]{record.lan(), record.addr()}) {
                    if (address != null && !address.isEmpty() && StatusPing.answers(address, PING_TIMEOUT_MS)) {
                        return new Status(Kind.PLAYING, record.name(), record.host(), address);
                    }
                }
            }
            return Status.NOBODY;
        } catch (Exception e) {
            return Status.UNREACHABLE;
        }
    }
}
