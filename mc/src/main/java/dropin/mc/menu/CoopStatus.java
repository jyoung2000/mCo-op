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

    /**
     * @param address the address that answered the ping, when {@code kind} is PLAYING
     * @param record  the host's announcement, when {@code kind} is PLAYING; otherwise null
     * @param online  the players the host named when it answered the ping (uuid and name); empty otherwise
     */
    public record Status(Kind kind, String hostName, String hostUuid, String address, RendezvousRecord record,
                         java.util.List<StatusPing.Player> online) {
        static final Status LOOKING = new Status(Kind.LOOKING, "", "", "", null, java.util.List.of());
        static final Status NOBODY = new Status(Kind.NOBODY, "", "", "", null, java.util.List.of());
        static final Status UNREACHABLE = new Status(Kind.UNREACHABLE, "", "", "", null, java.util.List.of());

        /** True if the player is in the world's session right now: the host or someone connected to it. */
        public boolean isOnline(String uuid) {
            return kind == Kind.PLAYING && record != null && record.isOnline(uuid);
        }
    }

    private static final long PING_TIMEOUT_MS = 5_000;
    private static final Map<String, Status> STATUS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
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
        if (!IN_FLIGHT.add(world.worldId())) {
            return; // the lookup already running answers this refresh too
        }
        POOL.submit(() -> {
            try {
                STATUS.put(world.worldId(), lookup(world));
            } finally {
                IN_FLIGHT.remove(world.worldId());
            }
        });
    }

    /** Blocking. Who is hosting this world right now, checked with a ping. */
    public static Status lookup(CoopWorld world) {
        try {
            Optional<RendezvousRecord> found = CoopWorlds.rendezvous(world).lookup();
            if (found.isPresent() && found.get().isState(RendezvousRecord.HOST)) {
                RendezvousRecord record = found.get();
                for (String address : new String[]{record.lan(), record.addr()}) {
                    java.util.List<StatusPing.Player> online = address == null || address.isEmpty() ? null
                            : StatusPing.players(address, PING_TIMEOUT_MS);
                    if (online != null) {
                        return new Status(Kind.PLAYING, record.name(), record.host(), address, record, java.util.List.copyOf(online));
                    }
                }
            }
            return Status.NOBODY;
        } catch (Exception e) {
            return Status.UNREACHABLE;
        }
    }
}
