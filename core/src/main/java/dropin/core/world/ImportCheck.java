package dropin.core.world;

/** The three import checks of DESIGN 4.4, in the order the design gives them. */
public final class ImportCheck {
    public enum Result {
        OK,
        /** Check 1: the world is open right now (session lock held). */
        OPEN,
        /** Check 2: already a co-op world on this PC. */
        ALREADY_COOP,
        /** Check 2: marked as co-op, but this PC has no key for it. Ask for the invite instead. */
        NEEDS_INVITE,
        /** Check 3: older than the running game. "Open it once in singleplayer to upgrade it, then import." */
        TOO_OLD,
        /** Check 3: newer than the running game. Refused. */
        TOO_NEW
    }

    private ImportCheck() {
    }

    /**
     * @param locked       another process (or this game) holds the world's session lock
     * @param marked       the folder has {@code dropin/world.json}
     * @param keyKnown     this PC has the key of the world named in that file
     * @param worldVersion data version the world was last saved with
     * @param gameVersion  data version of the running game
     */
    public static Result check(boolean locked, boolean marked, boolean keyKnown, int worldVersion, int gameVersion) {
        if (locked) {
            return Result.OPEN;
        }
        if (marked) {
            return keyKnown ? Result.ALREADY_COOP : Result.NEEDS_INVITE;
        }
        if (worldVersion < gameVersion) {
            return Result.TOO_OLD;
        }
        if (worldVersion > gameVersion) {
            return Result.TOO_NEW;
        }
        return Result.OK;
    }
}
