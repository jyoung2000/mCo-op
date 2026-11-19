package dropin.core.pkarr;

import java.util.Optional;

/**
 * Whether the rendezvous record shows that another session hosts the world this session hosts (DESIGN 4.3).
 *
 * <p>It happens when only one member's path to this host broke: that member's ping failed, it took the world
 * over, and both sessions now run. A host that keeps re-publishing over the other's record would make the two
 * sessions overwrite each other's announcement every few minutes. So before each publish the host looks at
 * the record; if another session holds it, the host stops announcing (it never overwrites that record again,
 * not even with "idle" when it closes) and tells its players.
 */
public final class HostRival {
    /** A host re-publishes every five minutes; a "host" record older than two heartbeats belongs to a gone session. */
    public static final long HOST_FRESH_SECONDS = 10 * 60;

    private HostRival() {
    }

    /**
     * @param current the record as looked up now
     * @param mySess     this host's session
     * @param myStartSec when this session claimed the world, in seconds. A record from before then is the one
     *                   this session replaced (a crashed host's last "host" record that a slow relay still
     *                   serves, for example), never a session that took the world over from this one.
     * @param nowSec     the time now, in seconds
     * @return the other session's record, if a live other session holds the rendezvous
     */
    public static Optional<RendezvousRecord> rival(Optional<RendezvousRecord> current, String mySess, long myStartSec, long nowSec) {
        if (current.isEmpty()) {
            return Optional.empty();
        }
        RendezvousRecord record = current.get();
        if (record.sess().equals(mySess) || record.ts() < myStartSec) {
            return Optional.empty();
        }
        long age = nowSec - record.ts();
        boolean live = record.isState(RendezvousRecord.HOST) && age <= HOST_FRESH_SECONDS
                || record.isState(RendezvousRecord.STARTING) && age <= HostClaim.STARTING_FRESH_SECONDS;
        return live ? current : Optional.empty();
    }
}
